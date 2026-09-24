# AdvancedSky

Multi-server Skyblock for Paper **and Folia**. Every island is created on one island server and
stays there for its whole life, so no world is ever loaded or unloaded at runtime.

> Status: early. `/island create` pastes the template island and `/island delete` clears the
> island area; everything else is still to come, feature by feature.

## Design

- **Static server lists.** `config.yml` lists every island server and every lobby server. They
  are assumed to be online: no heartbeats, no server registry, no failover.
- **MySQL is the source of truth.** An island row holds its owner, its host server and its grid
  slot. Nothing else needs to be known to find it. Slots are unique across the whole network, so
  an island has the same coordinates on every server and can be moved to another server offline by
  moving its region file and updating one column.
- **Redis carries intent only.** Pub/sub messages ask a host server to apply a change that is
  already committed in MySQL ("build slot 7", "clear slot 7"). Losing a message never corrupts
  data. Redis also holds a 60-second hand-off key for cross-server teleports.
- **One pre-loaded island world per server.** Islands sit on a square spiral grid; the slot number
  in MySQL maps to a position, so no coordinates are stored.
- **The starter island is a world, not a file.** Every island server also loads a template world
  where the starter island is built in game. Creating an island copies that box block by block,
  tile entities included, so no schematic tooling is involved.
- **Folia-compatible scheduling only.** Region scheduler for blocks, entity scheduler for
  teleports and plugin messages, async scheduler for MySQL and Redis. The same jar runs on Paper.

## Flows

`/island create` on server A, island assigned to server B:

1. Async: pick the listed server with the fewest islands (B), insert the island row.
2. Publish `island.create {slot}` to B's channel. B pastes the template, one chunk at a time on
   the region threads that own the chunks.
3. Store `pending_teleport:<player> = <island>` in Redis, send the player to B via the proxy.
4. B's join listener reads and deletes the key, waits for the paste if it is still running, then
   teleports the player onto the island.

If A hosts the island itself, step 2 to 4 collapse into a local paste and a teleport.

`/island delete`: delete the row, then either clear the island area locally or publish
`island.delete` to the host server. Clearing walks the 32x32 chunks of the island's region file in
batches, removes non-player entities and every block, and skips chunks that were never generated.

## Requirements

- Paper or Folia 1.21.11, Java 21
- MySQL / MariaDB
- Redis 6.2 or newer (uses `GETDEL`)
- Velocity or BungeeCord with the BungeeCord plugin message channel enabled (Velocity: `bungee-plugin-message-channel = true`)

Runtime libraries (MySQL driver, HikariCP, Jedis) are downloaded by the plugin loader on first start.

## Configuration

Per server, only `server.name` (the proxy name of this server) and `server.world` differ. Every
other section is identical across the network. See `config.yml` for the details.

Every server running the plugin must be listed once, either under `servers.island` (hosts
islands, needs the island world and the template world at startup) or `servers.lobby` (commands
only). Networks that share one Redis must each use their own `redis.cluster-id`.

`island.template` names the template world and the box to copy: `anchor` is the template block
that lands on the island centre, `min` and `max` are opposite corners. `island.spawn` is where
players arrive, relative to the island centre. Both can change at any time; they only affect
islands created or visited afterwards.

Island geometry is fixed in code (`IslandGrid`): centres are 2048 blocks apart and shifted by
256 blocks so that every island sits in the middle of its own 512x512 region file (region index
a multiple of 4 on both axes; every other region file is gap). The 2048 spacing keeps
neighbouring islands in separate Folia regions even when both are occupied. Slots stop at ring
8191, keeping every island within 16.7 million blocks of the origin; that is about 268 million
islands, after which `/island create` refuses with a message. Changing any of these values would
move or invalidate existing islands. `island.height` must likewise stay the same once islands exist.

## Permissions

- `advancedsky.island.create`
- `advancedsky.island.delete`

Both default to operators until granted by a permission plugin.

## Building

```bash
mvn package
```

The jar is written to `target/AdvancedSky.jar`.
