# AdvancedSky

Multi-server Skyblock for Paper **and Folia**. Every island is created on one island server and
stays there for its whole life, so no world is ever loaded or unloaded at runtime.

AdvancedSky has NewSky's feature set and code structure (commands → API → handlers →
`IslandDistributor` → `IslandOperator` → database / world). What differs is where islands live:
NewSky gives each island its own Advanced Slime Paper world and moves it between servers on
demand; AdvancedSky keeps every island of a server in one pre-loaded world, on a fixed grid.

> Status: alpha. Every NewSky feature is ported. Run it on a test network first and keep
> backups of your MySQL database.

## Design

- **Static server lists.** `config.yml` lists every island server and every lobby server. They
  are assumed to be online: no heartbeats, no server registry, no failover. Their clocks must
  agree (NTP), because a request between servers is refused once it is ten seconds old.
- **MySQL is the source of truth.** An island row holds its host server and its grid slot; its
  players, homes, warps, bans, co-ops, level and upgrades live in NewSky's tables. Slots are
  unique across the whole network and never reused, so an island has the same coordinates on
  every server and can be moved to another server offline by moving its region file and
  updating one column.
- **The host does the work.** Every change to an island runs on the server that hosts it, in one
  serial chain per island: it writes MySQL, applies the change to its world and refreshes its
  island cache. Another server asks the host through a Redis Streams request and waits for the
  answer, so a failure on the host (including a typed one such as "already has an island")
  reaches the player.
- **One pre-loaded island world per server.** Islands sit on a square spiral grid; the slot
  number in MySQL maps to a position, so no coordinates are stored. Each island server keeps an
  in-memory cache of all its islands, read in one pass at startup before anyone can join and
  refreshed after every change it makes, for the listeners to read.
- **Co-ops end when their player is gone.** A co-op ends a few seconds after its player quits
  and is not online elsewhere. Players online when a server stops or crashes never quit there,
  so each island server also removes, every five minutes, the co-ops on its islands whose player
  the online registry no longer lists.
- **Borders per player.** NewSky's per-world border becomes a border sent to each player around
  the island they stand on, and a move out of the island boundary is refused.
- **The starter island is a world, not a file.** Every island server also loads a template world
  where the starter island is built in game. Creating an island copies that box block by block,
  tile entities included, so no schematic tooling is involved.
- **Folia-compatible scheduling only.** Region scheduler for blocks and chunk scans, the
  player's own scheduler for teleports and plugin messages, the global region scheduler where
  NewSky uses the main thread, async scheduler for MySQL and Redis. The same jar runs on Paper.

## Flows

`/island create` on server A:

1. Async: pick the listed island server with the fewest islands (B).
2. If B is A, run the create here; otherwise send an `island.create` request to B and wait.
3. On B: insert the island row (MySQL assigns the next slot) with the owner's `default` home,
   add it to its island cache, then paste the template one chunk at a time on the region threads that
   own the chunks. If the paste fails, the area and the row are removed again and the failure
   is returned.
4. Take the player to the island's `default` home, as `/island home` does.

`/island home [name]`, `/island warp` and `/island lobby`: read the destination, then ask the
destination server to teleport the player. If the player is on another server, that server
keeps the teleport and the player is sent there through the proxy; it fires when they join.

`/island delete` (asked three times, as in NewSky): the host deletes the row (owner only, under
the island row lock), sends everyone standing in the island area to the lobby, then clears the
area: it walks the 32x32 chunks of the island's region file in batches, removes non-player
entities and every block, and skips chunks that were never generated. Slots are never handed
out twice, so a new island can never land on an area that is still being cleared, and the
command completes as soon as the row is gone instead of waiting for the area.

## Features

- Island create / delete on the least loaded island server
- Teams: invite, accept, reject, remove member, leave, transfer ownership
- Co-op, bans, expel, lock, PvP toggle
- Per-member homes and per-island warps, from any server
- Island levels from `levels.yml`, `/is top`, `/is value`, and a level scan while players are on
  an island
- Upgrades bought through Vault: island size, team / co-op / home / warp limits, biome unlocks,
  generator rates
- Per-island block and entity limits
- Cobblestone generator rates by upgrade level
- Biome changer per chunk
- Protection for build, interact, explosion, vehicle, hanging, teleport and PvP
- PlaceholderAPI expansion with a level leaderboard
- Public async API for other plugins
- Configurable commands, aliases, permissions and MiniMessage messages

## Requirements

- Paper or Folia 1.21.11, Java 21
- MySQL / MariaDB (MySQL 8.0+ or MariaDB 10.2.4+, which keep AUTO_INCREMENT across restarts)
- Redis 5.0 or newer (Streams)
- Vault with an economy plugin
- Velocity or BungeeCord with the BungeeCord plugin message channel enabled (Velocity: `bungee-plugin-message-channel = true`)
- PlaceholderAPI (optional)

Runtime libraries (MySQL driver, HikariCP, Jedis) are downloaded by the plugin loader on first start.

## Configuration

Per server, only `server.name` (the proxy name of this server) differs. Every other section is
identical across the network. See `config.yml` for the details.

Every server running the plugin must be listed once, either under `servers.island` (hosts
islands, needs `island.world` and the template world at startup) or `servers.lobby` (commands
only). Networks that share one Redis must each use their own `cluster-id`.

`island.template` names the template world and the box to copy: `anchor` is the template block
that lands on the island centre, `min` and `max` are opposite corners. `island.spawn` is a new
island's `default` home, relative to its centre. Both can change at any time; they only affect
islands created afterwards. `lobby.world-name` and `lobby.location` are where `/island lobby`
and every forced removal (delete, lock, ban, expel) send players on the lobby servers.

Island geometry is fixed in code (`IslandGrid`): centres are 2048 blocks apart and shifted by
256 blocks so that every island sits in the middle of its own 512x512 region file (region index
a multiple of 4 on both axes; every other region file is gap). The 2048 spacing keeps
neighbouring islands in separate Folia regions even when both are occupied. Slots stop at ring
8191, keeping every island within 16.7 million blocks of the origin; that is about 268 million
islands, after which `/island create` refuses with a message. Changing any of these values would
move or invalidate existing islands. `island.height` must likewise stay the same once islands
exist, and no `island-size` in `upgrades.yml` may exceed 512.

Command aliases, permissions, syntax and descriptions are in `commands.yml`; messages are in
`messages.yml`; `upgrades.yml`, `limits.yml` and `levels.yml` are NewSky's.

## Commands

Base command: `/island`, alias `/is`. Admin: `/islandadmin`, alias `/isadmin`.
`command.base-command-mode` decides whether a bare `/is` shows help or teleports home.

### Player

| Command | Permission (`advancedsky.island.player.*`) | Description |
| --- | --- | --- |
| `/is create` | `.create` | Create a new island |
| `/is delete` | `.delete` | Delete your island |
| `/is setowner <player>` | `.setowner` | Hand ownership to a member |
| `/is leave` | `.leave` | Leave the island you belong to |
| `/is invite <player>` | `.invite` | Invite a player to your island |
| `/is accept` | `.accept` | Accept a pending invitation |
| `/is reject` | `.reject` | Reject a pending invitation |
| `/is removemember <player>` | `.removemember` | Remove a member |
| `/is expel <player>` | `.expel` | Send a visitor back to the lobby |
| `/is coop <player>` | `.coop` | Grant co-op access |
| `/is uncoop <player>` | `.uncoop` | Revoke co-op access |
| `/is cooplist` | `.cooplist` | List co-op players |
| `/is ban <player>` | `.ban` | Ban a player from your island |
| `/is unban <player>` | `.unban` | Unban a player |
| `/is banlist` | `.banlist` | List banned players |
| `/is lock` | `.lock` | Toggle the island lock |
| `/is pvp` | `.pvp` | Toggle PvP |
| `/is sethome [name]` | `.sethome` | Set one of your homes |
| `/is delhome <name>` | `.delhome` | Delete one of your homes |
| `/is home [name]` *(alias `go`)* | `.home` | Teleport to your home |
| `/is setwarp [name]` | `.setwarp` | Set an island warp |
| `/is delwarp <name>` | `.delwarp` | Delete an island warp |
| `/is warp <player> [name]` | `.warp` | Teleport to another island's warp |
| `/is info [player]` | `.info` | Show island information |
| `/is level` | `.level` | Show your island level |
| `/is top` | `.top` | Island leaderboard by level |
| `/is value` | `.value` | Point value of the block in your hand |
| `/is upgrade <id> [buy]` | `.upgrade` | View or buy an upgrade |
| `/is biome <biome>` | `.biome` | Set the biome of the chunk you stand in |
| `/is lobby` | `.lobby` | Go back to the lobby |
| `/is help [page]` | `.help` | Command list |

`/is sethome`, `/is setwarp` and `/is biome` work on your own island only: in `island.world`, on
the server hosting it, inside its 512x512 area.

### Admin

| Command | Permission (`advancedsky.island.admin.*`) | Description |
| --- | --- | --- |
| `/isadmin create <player>` | `.create` | Create an island for a player |
| `/isadmin delete <player>` | `.delete` | Delete a player's island |
| `/isadmin addmember <member> <owner>` | `.addmember` | Force-add a member |
| `/isadmin removemember <member> <owner>` | `.removemember` | Force-remove a member |
| `/isadmin coop <owner> <player>` | `.coop` | Add a co-op player |
| `/isadmin uncoop <owner> <player>` | `.uncoop` | Remove a co-op player |
| `/isadmin ban <owner> <player>` | `.ban` | Ban from an island |
| `/isadmin unban <owner> <player>` | `.unban` | Unban from an island |
| `/isadmin lock <player>` | `.lock` | Toggle an island's lock |
| `/isadmin pvp <player>` | `.pvp` | Toggle an island's PvP |
| `/isadmin sethome <player> <home>` | `.sethome` | Set a home at your location |
| `/isadmin delhome <player> <home>` | `.delhome` | Delete a home |
| `/isadmin home <player> [home] [target]` | `.home` | Send someone to a home |
| `/isadmin setwarp <player> <warp>` | `.setwarp` | Set a warp at your location |
| `/isadmin delwarp <player> <warp>` | `.delwarp` | Delete a warp |
| `/isadmin warp <player> [warp] [target]` | `.warp` | Send someone to a warp |
| `/isadmin upgrade <player> <id> [set <level>]` | `.upgrade` | View or set an upgrade level |
| `/isadmin biome <biome>` | `.biome` | Set the biome of the chunk you stand in |
| `/isadmin lobby <player>` | `.lobby` | Send a player to the lobby |
| `/isadmin reload` | `.reload` | Reload the config files |
| `/isadmin help [page]` | `.help` | Admin command list |

NewSky's `load` and `unload` do not exist: islands never leave their host.

## Permissions

Permission nodes live in `commands.yml`, one per sub-command. `/island` itself needs
`advancedsky.island.player`. All default to operators until granted by a permission plugin.

```
advancedsky.island.player.<subcommand>
advancedsky.island.admin.<subcommand>
```

Operators bypass island access and protection checks.

## Upgrades, limits and levels

These work as in NewSky, with the island area in place of the island world:

- `island-size` is the island width: the protection boundary, each player's border and the area
  the level scan and entity limits cover, centred on the island.
- Level scans follow the players: the first player to arrive on an island starts them, they
  repeat every few minutes while it has players, and the first scan after the last player left
  is its last, so the stored level matches what they left behind.
- Block limits ride the level scan. Their counts start with an island's first scan after a
  restart (placing a capped block is refused until then) and are kept until the island is
  deleted.
- Entity limits count the entities inside the island boundary.

## PlaceholderAPI

The expansion (identifier `advancedsky`) registers itself when PlaceholderAPI is installed.

| Placeholder | Value |
| --- | --- |
| `%advancedsky_island_level%` | Last stored island level |
| `%advancedsky_island_members%` | Member count, excluding the owner and co-op players |
| `%advancedsky_island_owner%` | Owner's last known name, or their UUID |
| `%advancedsky_island_owner_uuid%` | Owner UUID |
| `%advancedsky_island_uuid%` | Island UUID |
| `%advancedsky_island_role%` | `owner`, `member` or `none` |
| `%advancedsky_island_lock%` | `true` / `false` |
| `%advancedsky_island_pvp%` | `true` / `false` |
| `%advancedsky_island_players%` | Owner plus members |
| `%advancedsky_island_coops%` | Co-op player count |
| `%advancedsky_island_bans%` | Banned player count |
| `%advancedsky_island_rank%` | Position in the level leaderboard; `0` with no island |
| `%advancedsky_upgrade_<id>_level%` | The island's current level of that upgrade |
| `%advancedsky_upgrade_<id>_value%` | What the current level grants |
| `%advancedsky_upgrade_<id>_max%` | Highest configured level |
| `%advancedsky_upgrade_<id>_maxed%` | `true` at the highest level |
| `%advancedsky_upgrade_<id>_next_price%` | Price of the next level |
| `%advancedsky_upgrade_<id>_next_require_level%` | Island level needed for the next level |
| `%advancedsky_upgrade_<id>_next_value%` | What the next level grants |
| `%advancedsky_top_<n>_owner%` | Owner name at position n |
| `%advancedsky_top_<n>_level%` | Island level at position n |
| `%advancedsky_top_<n>_uuid%` | Island UUID at position n |
| `%advancedsky_top_<n>_members%` | Member count at position n |

Ranks, the `top_` placeholders and `/is top` come from one leaderboard each server reads at most
once a minute, so a new island has no rank (`0`) until the next read.

## Developer API

Everything is asynchronous and returns `CompletableFuture`. Failures arrive as completion
exceptions carrying the typed exceptions in `org.me.advancedsky.exceptions`.

```java
AdvancedSky advancedSky = (AdvancedSky) Bukkit.getPluginManager().getPlugin("AdvancedSky");
AdvancedSkyAPI api = advancedSky.getApi();

api.getIslandUuid(playerUuid)
   .thenCompose(api::getIslandLevel)
   .thenAccept(level -> player.sendMessage("Island level: " + level));

api.player(playerUuid).createIsland();          // acts as that player, on their island
api.admin(sender).deleteIsland(islandUuid);     // arbitrary target, sender's name in logs
```

`player(uuid)` can only act as that player on that player's own island; `admin(sender)` takes
arbitrary targets. Homes, warps and biomes are set at a `Location`, which must lie on the
island on the server hosting it (admin biomes: any chunk). `getIslandUuidAt(location)` finds the
island under a location among the islands hosted on the server it runs on. Completions can land on any thread; hop to the right
region thread yourself before touching world, entity or inventory state.

## Building

```bash
mvn package
```

The jar is written to `target/AdvancedSky.jar`.
