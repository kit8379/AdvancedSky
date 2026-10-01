package org.me.advancedsky.world;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Applies island changes to this server's island world. Only called by {@code IslandOperator} for
 * islands hosted here.
 * <p>
 * Creating an island copies the template built in the template world onto the island centre;
 * deleting lists the region files of the island's area for deletion at the next startup (see
 * {@link RegionPurge}). All block work runs on the region scheduler of the chunk it touches, and
 * where a player stands is read on that player's own scheduler, so it is safe on Folia and on
 * Paper alike.
 */
public class WorldHandler {

    private final AdvancedSky plugin;
    private final IslandGrid grid;
    private final RegionPurge regionPurge;
    private final World world;
    private final List<Path> storageFolders;
    private final World templateWorld;
    private final BlockVector templateAnchor;
    private final BlockVector templateMin;
    private final BlockVector templateMax;

    public WorldHandler(AdvancedSky plugin, ConfigHandler config, IslandGrid grid, RegionPurge regionPurge) {
        this.plugin = plugin;
        this.grid = grid;
        this.regionPurge = regionPurge;
        this.templateAnchor = new BlockVector(config.getTemplateAnchorX(), config.getTemplateAnchorY(), config.getTemplateAnchorZ());

        // Either corner order is accepted, as with a WorldEdit selection.
        this.templateMin = new BlockVector(Math.min(config.getTemplateMinX(), config.getTemplateMaxX()), Math.min(config.getTemplateMinY(), config.getTemplateMaxY()), Math.min(config.getTemplateMinZ(), config.getTemplateMaxZ()));
        this.templateMax = new BlockVector(Math.max(config.getTemplateMinX(), config.getTemplateMaxX()), Math.max(config.getTemplateMinY(), config.getTemplateMaxY()), Math.max(config.getTemplateMinZ(), config.getTemplateMaxZ()));

        if (config.isLobbyOnly()) {
            this.world = null;
            this.templateWorld = null;
            this.storageFolders = List.of();
        } else {
            // Worlds are all loaded before plugins enable; on Folia none can be added later.
            this.world = requireLoadedWorld(config.getIslandWorldName(), "island world");
            this.templateWorld = requireLoadedWorld(config.getTemplateWorldName(), "template world");
            this.storageFolders = storageFolders(world);
        }
    }

    /**
     * Where the world keeps its region, entity and point-of-interest files: in its own folder for
     * the main world and worlds made by plugins, in a dimensions folder for a datapack dimension.
     */
    private static List<Path> storageFolders(World world) {
        NamespacedKey key = world.getKey();
        Path folder = world.getWorldPath().toAbsolutePath().normalize();
        if (!key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
            folder = folder.resolve("dimensions").resolve(key.getNamespace()).resolve(key.getKey());
        } else if (world.getEnvironment() == World.Environment.NETHER) {
            folder = folder.resolve("DIM-1");
        } else if (world.getEnvironment() == World.Environment.THE_END) {
            folder = folder.resolve("DIM1");
        }
        return List.of(folder.resolve("region"), folder.resolve("entities"), folder.resolve("poi"));
    }

    /**
     * The island world. Only on island servers.
     */
    public World getWorld() {
        return requireWorld();
    }

    /**
     * Copies the template onto the island. Completes once every block has been placed.
     */
    public CompletableFuture<Void> createIsland(int slot) {
        return pasteTemplate(grid.center(requireWorld(), slot));
    }

    /**
     * Lists the region files of the island's area for deletion at the next startup and sends
     * every player in the area to the lobby. Its blocks and entities stay until then, on a slot
     * nobody may stand on and that is never handed out again.
     */
    public CompletableFuture<Void> deleteIsland(int slot) {
        try {
            regionPurge.schedule(regionFiles(slot));
        } catch (IOException e) {
            plugin.severe("Could not list the region files of the island area at slot " + slot + " for deletion; its blocks stay in the world", e);
        }

        return removePlayersFromIsland(slot, player -> true);
    }

    /**
     * The region, entity and point-of-interest files holding the island's area, which is exactly
     * one 512x512 region file.
     */
    private List<Path> regionFiles(int slot) {
        Location center = grid.center(requireWorld(), slot);
        String name = "r." + Math.floorDiv(center.getBlockX(), 512) + "." + Math.floorDiv(center.getBlockZ(), 512) + ".mca";
        return storageFolders.stream().map(folder -> folder.resolve(name)).toList();
    }

    /**
     * Sends the player to the lobby if they stand in the island's area here. Completes with
     * whether they did.
     */
    public CompletableFuture<Boolean> removePlayerFromIsland(int slot, UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> Bukkit.getPlayer(playerUuid), plugin.getGlobalRegionExecutor()).thenCompose(player -> {
            if (player == null) {
                return CompletableFuture.completedFuture(false);
            }

            return onPlayer(player, () -> {
                if (!isInArea(player, slot)) {
                    return false;
                }

                sendToLobby(player);
                return true;
            }, false);
        });
    }

    /**
     * Sends every player in the island's area for whom the predicate holds to the lobby.
     */
    public CompletableFuture<Void> removePlayersFromIsland(int slot, Predicate<Player> shouldRemove) {
        // Players are listed on the global region; where each one stands is read on their own thread.
        return CompletableFuture.supplyAsync(() -> List.<Player>copyOf(Bukkit.getOnlinePlayers()), plugin.getGlobalRegionExecutor()).thenCompose(players -> playersInArea(players, slot, player -> {
            if (!shouldRemove.test(player)) {
                return false;
            }

            sendToLobby(player);
            plugin.debug("WorldHandler", "Removed player " + player.getUniqueId() + " from island area at slot " + slot);
            return true;
        })).thenApply(sent -> null);
    }

    /**
     * The players among those given who stand in the island's area here and pass the check,
     * which runs on each player's own thread.
     */
    private CompletableFuture<List<Player>> playersInArea(List<Player> players, int slot, Predicate<Player> check) {
        List<CompletableFuture<Player>> checks = new ArrayList<>(players.size());
        for (Player player : players) {
            checks.add(onPlayer(player, () -> isInArea(player, slot) && check.test(player) ? player : null, null));
        }

        return CompletableFuture.allOf(checks.toArray(new CompletableFuture<?>[0])).thenApply(v -> checks.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
    }

    /**
     * Runs on the thread that owns the player.
     */
    private boolean isInArea(Player player, int slot) {
        Location location = player.getLocation();
        return location.getWorld().equals(world) && grid.isInArea(slot, location.getX(), location.getZ());
    }

    /**
     * Runs the action on the thread that owns the player; a player who left meanwhile yields
     * the fallback.
     */
    private <T> CompletableFuture<T> onPlayer(Player player, Supplier<T> action, T ifGone) {
        CompletableFuture<T> result = new CompletableFuture<>();
        boolean scheduled = player.getScheduler().run(plugin, task -> {
            try {
                result.complete(action.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, () -> result.complete(ifGone)) != null;

        if (!scheduled) {
            result.complete(ifGone);
        }
        return result;
    }

    // ===================================================================
    // Template paste
    // ===================================================================

    private CompletableFuture<Void> pasteTemplate(Location center) {
        int dx = center.getBlockX() - templateAnchor.getBlockX();
        int dy = center.getBlockY() - templateAnchor.getBlockY();
        int dz = center.getBlockZ() - templateAnchor.getBlockZ();

        List<CompletableFuture<Void>> copies = new ArrayList<>();
        for (int cx = templateMin.getBlockX() >> 4; cx <= templateMax.getBlockX() >> 4; cx++) {
            for (int cz = templateMin.getBlockZ() >> 4; cz <= templateMax.getBlockZ() >> 4; cz++) {
                copies.add(onChunk(templateWorld, cx, cz, true, chunk -> copyTemplateChunk(chunk, dx, dy, dz)));
            }
        }
        return allOf(copies);
    }

    /**
     * Runs on the template chunk's thread: snapshots every non-air block inside the template box,
     * then places the snapshots on the threads owning the target chunks. Block states carry tile
     * entity data, so chests keep their contents. Air, most of the box, is found in a chunk
     * snapshot, so only the blocks that are copied are looked up in the world.
     */
    private CompletableFuture<Void> copyTemplateChunk(Chunk chunk, int dx, int dy, int dz) {
        int fromX = Math.max(templateMin.getBlockX(), chunk.getX() << 4);
        int toX = Math.min(templateMax.getBlockX(), (chunk.getX() << 4) + 15);
        int fromZ = Math.max(templateMin.getBlockZ(), chunk.getZ() << 4);
        int toZ = Math.min(templateMax.getBlockZ(), (chunk.getZ() << 4) + 15);
        int minHeight = templateWorld.getMinHeight();
        int fromY = Math.max(templateMin.getBlockY(), minHeight);
        int toY = Math.min(templateMax.getBlockY(), templateWorld.getMaxHeight() - 1);
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false, false);

        Map<ChunkPos, List<BlockState>> byTargetChunk = new HashMap<>();
        for (int y = fromY; y <= toY; y++) {
            // isSectionEmpty takes a 0-based index counted from the lowest section of the world.
            if (snapshot.isSectionEmpty((y - minHeight) >> 4)) {
                continue;
            }
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    if (snapshot.getBlockType(x & 15, y, z & 15).isAir()) {
                        continue;
                    }
                    Location target = new Location(world, x + dx, y + dy, z + dz);
                    byTargetChunk.computeIfAbsent(new ChunkPos(target.getBlockX() >> 4, target.getBlockZ() >> 4), key -> new ArrayList<>()).add(templateWorld.getBlockAt(x, y, z).getState().copy(target));
                }
            }
        }

        List<CompletableFuture<Void>> placements = new ArrayList<>();
        byTargetChunk.forEach((pos, states) -> placements.add(onChunk(world, pos.x(), pos.z(), true, targetChunk -> {
            for (BlockState state : states) {
                state.update(true, false);
            }
            return CompletableFuture.completedFuture(null);
        })));
        return allOf(placements);
    }

    // ===================================================================
    // Scheduling helpers
    // ===================================================================

    /**
     * Loads the chunk (generating it only if asked) and runs the action on the thread that owns
     * it. A chunk that does not exist and is not to be generated is skipped.
     */
    public CompletableFuture<Void> onChunk(World target, int chunkX, int chunkZ, boolean generate, Function<Chunk, CompletableFuture<Void>> action) {
        return target.getChunkAtAsync(chunkX, chunkZ, generate).thenCompose(chunk -> {
            if (chunk == null) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> done = new CompletableFuture<>();
            Bukkit.getRegionScheduler().run(plugin, target, chunkX, chunkZ, task -> {
                try {
                    action.apply(chunk).whenComplete((v, ex) -> {
                        if (ex != null) {
                            done.completeExceptionally(ex);
                        } else {
                            done.complete(null);
                        }
                    });
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            return done;
        });
    }

    private void sendToLobby(Player player) {
        plugin.getApi().lobby(player.getUniqueId()).exceptionally(e -> {
            plugin.severe("Failed to send player " + player.getUniqueId() + " to the lobby off an island", e);
            return null;
        });
    }

    private static CompletableFuture<Void> allOf(List<CompletableFuture<Void>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    private static World requireLoadedWorld(String name, String role) {
        World loaded = Bukkit.getWorld(name);
        if (loaded == null) {
            throw new IllegalStateException("The " + role + " '" + name + "' is not loaded; an island server needs it at startup");
        }
        return loaded;
    }

    private World requireWorld() {
        if (world == null) {
            throw new IllegalStateException("This server is a lobby and hosts no islands");
        }
        return world;
    }

    private record ChunkPos(int x, int z) {
    }
}
