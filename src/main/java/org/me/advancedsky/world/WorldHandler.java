package org.me.advancedsky.world;

import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Applies island changes to this server's island world. Called by the local island handlers for
 * islands hosted here.
 * <p>
 * Creating an island copies a schematic template onto the island centre;
 * deleting immediately evicts and clears that area's region files before its database row goes.
 * All block work runs on the region scheduler of the chunk it touches, and
 * where a player stands is read on that player's own scheduler, so it is safe on Folia and on
 * Paper alike.
 */
public class WorldHandler {

    private final AdvancedSky plugin;
    private final IslandGrid grid;
    private volatile boolean stopped;
    private final World world;
    private final List<Path> storageFolders;
    private final SchematicTemplate template;

    public WorldHandler(AdvancedSky plugin, ConfigHandler config, IslandGrid grid) {
        this.plugin = plugin;
        this.grid = grid;
        if (config.isLobbyOnly()) {
            this.world = null;
            this.template = null;
            this.storageFolders = List.of();
        } else {
            // Worlds are all loaded before plugins enable; on Folia none can be added later.
            this.world = requireLoadedWorld(config.getIslandWorldName(), "island world");
            this.storageFolders = storageFolders(world);
            Path dataFolder = plugin.getDataFolder().toPath();
            String schematic = config.getTemplateSchematic();
            if (SchematicTemplate.DEFAULT_PATH.equals(schematic) && !Files.exists(dataFolder.resolve(schematic))) {
                plugin.saveResource(SchematicTemplate.DEFAULT_PATH, false);
            }
            this.template = SchematicTemplate.load(dataFolder, schematic, world, config.getIslandHeight());
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

    public boolean isIslandLocation(Location location) {
        return world != null && world.equals(location.getWorld());
    }

    /**
     * Copies the template onto the island. Completes once every block has been placed.
     */
    public CompletableFuture<Void> createIsland(int slot) {
        return pasteTemplate(grid.center(requireWorld(), slot));
    }

    /**
     * Clears the physical area now. The caller keeps its database row until this succeeds.
     * Player transfers and chunk eviction are asynchronous; no region thread waits on I/O.
     */
    public CompletableFuture<Void> deleteIsland(int slot) {
        List<Path> files = regionFiles(slot);
        Location center = grid.center(requireWorld(), slot);
        int rx = Math.floorDiv(center.getBlockX(), 512);
        int rz = Math.floorDiv(center.getBlockZ(), 512);
        AtomicBoolean cancelled = new AtomicBoolean();
        return removePlayersFromIsland(slot, player -> true).thenCompose(v -> awaitRegionUnloaded(rx * 32, rz * 32, 60, cancelled).orTimeout(120, TimeUnit.SECONDS).whenComplete((ready, error) -> {
            if (error != null) cancelled.set(true);
        })).thenRunAsync(() -> {
            if (stopped) throw new IllegalStateException("Island world handler stopped");
            RegionStorageAccess.flushAndEvict(world, rx, rz);
            if (stopped || RegionStorageAccess.hasHolders(world, rx * 32, rz * 32)) {
                throw new IllegalStateException("Island region is still in use: " + slot);
            }
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("Could not clear island slot " + slot, e);
                }
            }
        }, plugin.getBukkitAsyncExecutor());
    }

    public void stop() {
        stopped = true;
    }

    /**
     * Every island area occupies exactly one region, with separate entity and POI storage.
     */
    public List<Path> regionFiles(int slot) {
        Location center = grid.center(requireWorld(), slot);
        String name = "r." + Math.floorDiv(center.getBlockX(), 512) + "." + Math.floorDiv(center.getBlockZ(), 512) + ".mca";
        return storageFolders.stream().map(folder -> folder.resolve(name)).toList();
    }

    private CompletableFuture<Void> awaitRegionUnloaded(int baseX, int baseZ, int attempts, AtomicBoolean cancelled) {
        if (stopped || cancelled.get())
            return CompletableFuture.failedFuture(new IllegalStateException("Island cleanup stopped"));
        List<CompletableFuture<Void>> evictions = new ArrayList<>();
        for (int x = baseX; x < baseX + 32; x++) {
            for (int z = baseZ; z < baseZ + 32; z++) {
                if (!world.isChunkLoaded(x, z)) continue;
                int cx = x;
                int cz = z;
                CompletableFuture<Void> eviction = new CompletableFuture<>();
                evictions.add(eviction);
                Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                    try {
                        if (stopped || cancelled.get()) throw new IllegalStateException("Island cleanup stopped");
                        // False can mean a player transfer or chunk ticket is still holding it.
                        // Retrying on later ticks also covers Folia's deferred unload.
                        world.unloadChunk(cx, cz, true);
                        eviction.complete(null);
                    } catch (Throwable e) {
                        eviction.completeExceptionally(e);
                    }
                });
            }
        }
        return allOf(evictions).thenCompose(v -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                try {
                    if (stopped || cancelled.get()) throw new IllegalStateException("Island cleanup stopped");
                    boolean loaded = false;
                    for (int x = baseX; x < baseX + 32 && !loaded; x++) {
                        for (int z = baseZ; z < baseZ + 32; z++) {
                            if (world.isChunkLoaded(x, z)) {
                                loaded = true;
                                break;
                            }
                        }
                    }
                    if (!loaded && !RegionStorageAccess.hasHolders(world, baseX, baseZ)) {
                        result.complete(null);
                    } else if (attempts == 1) {
                        result.completeExceptionally(new IllegalStateException("Island region did not unload; database row retained"));
                    } else {
                        awaitRegionUnloaded(baseX, baseZ, attempts - 1, cancelled).whenComplete((ready, error) -> {
                            if (error == null) result.complete(null);
                            else result.completeExceptionally(error);
                        });
                    }
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            }, 20L);
            return result;
        });
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
     * Runs the action on the thread that owns the player; a player who left yields ifGone.
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
        int dx = center.getBlockX() + template.offsetX();
        int dy = center.getBlockY() + template.offsetY();
        int dz = center.getBlockZ() + template.offsetZ();
        Map<ChunkPos, List<BlockState>> byTargetChunk = new HashMap<>();
        for (BlockState state : template.blocks()) {
            ChunkPos pos = new ChunkPos((state.getX() + dx) >> 4, (state.getZ() + dz) >> 4);
            byTargetChunk.computeIfAbsent(pos, key -> new ArrayList<>()).add(state);
        }
        List<CompletableFuture<Void>> placements = new ArrayList<>();
        byTargetChunk.forEach((pos, states) -> placements.add(onChunk(world, pos.x(), pos.z(), true, targetChunk -> {
            for (BlockState state : states) {
                // Each paste gets its own tile-entity snapshot, on the destination's thread.
                Location target = new Location(world, state.getX() + dx, state.getY() + dy, state.getZ() + dz);
                if (!state.copy(target).update(true, false)) {
                    throw new IllegalStateException("Could not place schematic block at " + target);
                }
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
                    if (stopped) throw new IllegalStateException("Island world handler stopped");
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
