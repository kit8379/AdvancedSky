# AdvancedSky

Multi-server Skyblock for Paper **and Folia**. Every island is created on one island server and
stays there for its whole life, so no world is ever loaded or unloaded at runtime.

> Status: early. `/island create` pastes the template island and `/island delete` clears the
> island area; everything else is still to come, feature by feature. The code follows NewSky's
> structure (commands → API → handlers → `IslandDistributor` → `IslandOperator`).

## Design

- **Static server lists.** `config.yml` lists every island server and every lobby server. They
  are assumed to be online: no heartbeats, no server registry, no failover.
- **MySQL is the source of truth.** An island row holds its host server and its grid slot; its
  players and their roles live in `island_players`, as in NewSky. Slots are unique across the
  whole network, so an island has the same coordinates on every server and can be moved to
  another server offline by moving its region file and updating one column.
- **The host does the work.** Every island change runs on the server that hosts the island: in
  one serial chain per island, it writes MySQL and applies the change to its world. Another
  server asks the host through a Redis Streams request and waits for the host's answer, so a
  failure on the host (including a typed one such as "already has an island") reaches the player.
- **One pre-loaded island world per server.** Islands sit on a square spiral grid; the slot number
  in MySQL maps to a position, so no coordinates are stored.
- **The starter island is a world, not a file.** Every island server also loads a template world
  where the starter island is built in game. Creating an island copies that box block by block,
  tile entities included, so no schematic tooling is involved.
- **Folia-compatible scheduling only.** Region scheduler for blocks, async scheduler for MySQL
  and Redis. The same jar runs on Paper.

## Flows

`/island create` on server A:

1. Async: pick the listed island server with the fewest islands (B).
2. If B is A, run the create here; otherwise send an `island.create` request to B and wait.
3. On B: insert the island row (MySQL assigns the next slot), then paste the template one chunk
   at a time on the region threads that own the chunks. If the paste fails, the area and the row
   are removed again and the failure is returned.

The player is not teleported yet; that arrives with the home feature.

`/island delete` (asked three times, as in NewSky): read the host from MySQL and run the delete
there. The host deletes the row (owner only, under the island row lock), then clears the island
area: it walks the 32x32 chunks of the island's region file in batches, removes non-player
entities and every block, and skips chunks that were never generated. Slots are never handed out
twice, so a new island can never land on an area that is still being cleared.

## Requirements

- Paper or Folia 1.21.11, Java 21
- MySQL / MariaDB
- Redis 5.0 or newer (Streams)
- Velocity or BungeeCord

Runtime libraries (MySQL driver, HikariCP, Jedis) are downloaded by the plugin loader on first start.

## Configuration

Per server, only `server.name` (the proxy name of this server) and `server.world` differ. Every
other section is identical across the network. See `config.yml` for the details.

Every server running the plugin must be listed once, either under `servers.island` (hosts
islands, needs the island world and the template world at startup) or `servers.lobby` (commands
only). Networks that share one Redis must each use their own `cluster-id`.

`island.template` names the template world and the box to copy: `anchor` is the template block
that lands on the island centre, `min` and `max` are opposite corners. It can change at any time;
it only affects islands created afterwards.

Island geometry is fixed in code (`IslandGrid`): centres are 2048 blocks apart and shifted by
256 blocks so that every island sits in the middle of its own 512x512 region file (region index
a multiple of 4 on both axes; every other region file is gap). The 2048 spacing keeps
neighbouring islands in separate Folia regions even when both are occupied. Slots stop at ring
8191, keeping every island within 16.7 million blocks of the origin; that is about 268 million
islands, after which `/island create` refuses with a message. Changing any of these values would
move or invalidate existing islands. `island.height` must likewise stay the same once islands exist.

Command aliases, permissions, syntax and descriptions are in `commands.yml`; messages are in
`messages.yml`.

## Permissions

- `advancedsky.island.player` (required for `/island` itself)
- `advancedsky.island.player.create`
- `advancedsky.island.player.delete`
- `advancedsky.island.player.help`

All default to operators until granted by a permission plugin.

## Building

```bash
mvn package
```

The jar is written to `target/AdvancedSky.jar`.
