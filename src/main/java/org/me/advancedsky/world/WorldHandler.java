package org.me.advancedsky.world;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Applies island changes to this server's island world. Only called by {@code IslandOperator} for
 * islands hosted here.
 * <p>
 * Creating an island copies the template built in the template world onto the island centre;
 * deleting clears every block and non-player entity in the island's area. All block work runs on
 * the region scheduler of the chunk it touches, and where a player stands is read on that
 * player's own scheduler, so it is safe on Folia and on Paper alike.
 */
public class WorldHandler {

    /**
     * Chunks loaded and processed at the same time while clearing an island area.
     */
    private static final int CHUNKS_PER_BATCH = 32;

    /**
     * How long a delete waits for the players it sent to the lobby to leave the island's area
     * before clearing it anyway: a connect takes effect only once the proxy has moved them.
     */
    private static final long EVACUATION_TIMEOUT_MILLIS = 10_000L;

    private final AdvancedSky plugin;
    private final IslandGrid grid;
    private final World world;
    private final World templateWorld;
    private final BlockVector templateAnchor;
    private final BlockVector templateMin;
    private final BlockVector templateMax;

    public WorldHandler(AdvancedSky plugin, ConfigHandler config, IslandGrid grid) {
        this.plugin = plugin;
        this.grid = grid;
        this.templateAnchor = new BlockVector(config.getTemplateAnchorX(), config.getTemplateAnchorY(), config.getTemplateAnchorZ());

        // Either corner order is accepted, as with a WorldEdit selection.
        this.templateMin = new BlockVector(Math.min(config.getTemplateMinX(), config.getTemplateMaxX()), Math.min(config.getTemplateMinY(), config.getTemplateMaxY()), Math.min(config.getTemplateMinZ(), config.getTemplateMaxZ()));
        this.templateMax = new BlockVector(Math.max(config.getTemplateMinX(), config.getTemplateMaxX()), Math.max(config.getTemplateMinY(), config.getTemplateMaxY()), Math.max(config.getTemplateMinZ(), config.getTemplateMaxZ()));

        if (config.isLobbyOnly()) {
            this.world = null;
            this.templateWorld = null;
        } else {
            // Worlds are all loaded before plugins enable; on Folia none can be added later.
            this.world = requireLoadedWorld(config.getIslandWorldName(), "island world");
            this.templateWorld = requireLoadedWorld(config.getTemplateWorldName(), "template world");
        }
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
     * Sends every player in the island's area to the lobby and waits for them to leave it, so
     * nobody is left standing on blocks about to vanish, then removes every block and non-player
     * entity in it, chunk by chunk. Chunks that were never generated are skipped so the delete
     * does not create them.
     */
    public CompletableFuture<Void> deleteIsland(int slot) {
        long deadline = System.currentTimeMillis() + EVACUATION_TIMEOUT_MILLIS;
        return sendAway(slot, player -> true).thenCompose(sent -> awaitLeft(slot, sent, deadline)).thenCompose(v -> clearInBatches(areaChunks(slot), 0));
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
        return sendAway(slot, shouldRemove).thenApply(sent -> null);
    }

    /**
     * Sends every player in the island's area for whom the predicate holds to the lobby.
     * Completes with the players sent.
     */
    private CompletableFuture<List<Player>> sendAway(int slot, Predicate<Player> shouldRemove) {
        // Players are listed on the global region; where each one stands is read on their own thread.
        return CompletableFuture.supplyAsync(() -> List.<Player>copyOf(Bukkit.getOnlinePlayers()), plugin.getGlobalRegionExecutor()).thenCompose(players -> playersInArea(players, slot, player -> {
            if (!shouldRemove.test(player)) {
                return false;
            }

            sendToLobby(player);
            plugin.debug("WorldHandler", "Removed player " + player.getUniqueId() + " from island area at slot " + slot);
            return true;
        }));
    }

    /**
     * Completes once none of the players is in the island's area here any more, looking again
     * every second, or at the deadline with a warning. A player who left the server counts as
     * gone: their scheduler retires with them.
     */
    private CompletableFuture<Void> awaitLeft(int slot, List<Player> players, long deadline) {
        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (System.currentTimeMillis() >= deadline) {
            plugin.warning("Clearing the island area at slot " + slot + " with " + players.size() + " player(s) sent to the lobby still in it");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS, plugin.getBukkitAsyncExecutor())).thenCompose(v -> playersInArea(players, slot, player -> true)).thenCompose(remaining -> awaitLeft(slot, remaining, deadline));
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

    private List<ChunkPos> areaChunks(int slot) {
        Location center = grid.center(requireWorld(), slot);
        int minChunkX = (center.getBlockX() - IslandGrid.AREA_RADIUS) >> 4;
        int minChunkZ = (center.getBlockZ() - IslandGrid.AREA_RADIUS) >> 4;
        int maxChunkX = (center.getBlockX() + IslandGrid.AREA_RADIUS - 1) >> 4;
        int maxChunkZ = (center.getBlockZ() + IslandGrid.AREA_RADIUS - 1) >> 4;

        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
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
     * entity data, so chests keep their contents.
     */
    private CompletableFuture<Void> copyTemplateChunk(Chunk chunk, int dx, int dy, int dz) {
        int fromX = Math.max(templateMin.getBlockX(), chunk.getX() << 4);
        int toX = Math.min(templateMax.getBlockX(), (chunk.getX() << 4) + 15);
        int fromZ = Math.max(templateMin.getBlockZ(), chunk.getZ() << 4);
        int toZ = Math.min(templateMax.getBlockZ(), (chunk.getZ() << 4) + 15);
        int fromY = Math.max(templateMin.getBlockY(), templateWorld.getMinHeight());
        int toY = Math.min(templateMax.getBlockY(), templateWorld.getMaxHeight() - 1);

        Map<ChunkPos, List<BlockState>> byTargetChunk = new HashMap<>();
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = fromY; y <= toY; y++) {
                    Block block = templateWorld.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    Location target = new Location(world, x + dx, y + dy, z + dz);
                    byTargetChunk.computeIfAbsent(new ChunkPos(target.getBlockX() >> 4, target.getBlockZ() >> 4), key -> new ArrayList<>()).add(block.getState().copy(target));
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
    // Area clearing
    // ===================================================================

    private CompletableFuture<Void> clearInBatches(List<ChunkPos> chunks, int from) {
        if (from >= chunks.size()) {
            return CompletableFuture.completedFuture(null);
        }

        int to = Math.min(from + CHUNKS_PER_BATCH, chunks.size());
        List<CompletableFuture<Void>> batch = new ArrayList<>();
        for (ChunkPos pos : chunks.subList(from, to)) {
            batch.add(onChunk(world, pos.x(), pos.z(), false, chunk -> {
                clearChunk(chunk);
                return CompletableFuture.completedFuture(null);
            }));
        }
        return allOf(batch).thenCompose(v -> clearInBatches(chunks, to));
    }

    /**
     * Runs on the chunk's thread. Only sections that hold any block are visited, so void chunks cost nothing.
     */
    private void clearChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }

        World chunkWorld = chunk.getWorld();
        int minHeight = chunkWorld.getMinHeight();
        int sectionCount = (chunkWorld.getMaxHeight() - minHeight) >> 4;
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false, false);

        // isSectionEmpty takes a 0-based index counted from the lowest section of the world.
        for (int section = 0; section < sectionCount; section++) {
            if (snapshot.isSectionEmpty(section)) {
                continue;
            }
            int baseY = minHeight + (section << 4);
            for (int y = baseY; y < baseY + 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!snapshot.getBlockType(x, y, z).isAir()) {
                            chunk.getBlock(x, y, z).setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
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
