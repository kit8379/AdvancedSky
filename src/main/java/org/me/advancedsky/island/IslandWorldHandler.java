package org.me.advancedsky.island;

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
import org.bukkit.util.Vector;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Applies island changes to this server's island world. Only called for islands hosted here,
 * either directly or through a cross-server message.
 * <p>
 * Creating an island copies the template built in the template world onto the island centre;
 * deleting clears every block and non-player entity in the island's area. All block work runs on
 * the region scheduler of the chunk it touches, so it is safe on Folia and on Paper alike.
 */
public class IslandWorldHandler {

    /**
     * Chunks loaded and processed at the same time while clearing an island area.
     */
    private static final int CHUNKS_PER_BATCH = 32;

    private final AdvancedSky plugin;
    private final IslandGrid grid;
    private final World world;
    private final World templateWorld;
    private final BlockVector templateAnchor;
    private final BlockVector templateMin;
    private final BlockVector templateMax;
    private final Vector spawnOffset;
    private final float spawnYaw;
    private final float spawnPitch;
    private final Map<Integer, CompletableFuture<Void>> buildsInProgress = new ConcurrentHashMap<>();

    /**
     * @param hostsIslands whether this server is an island server; only then are the island and template worlds required
     */
    public IslandWorldHandler(AdvancedSky plugin, ConfigHandler config, IslandGrid grid, boolean hostsIslands) {
        this.plugin = plugin;
        this.grid = grid;
        this.spawnOffset = config.getIslandSpawnOffset();
        this.spawnYaw = config.getIslandSpawnYaw();
        this.spawnPitch = config.getIslandSpawnPitch();
        this.templateAnchor = config.getTemplateAnchor();

        // Either corner order is accepted, as with a WorldEdit selection.
        BlockVector a = config.getTemplateMin();
        BlockVector b = config.getTemplateMax();
        this.templateMin = new BlockVector(Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()));
        this.templateMax = new BlockVector(Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));

        if (hostsIslands) {
            // Worlds are all loaded before plugins enable; on Folia none can be added later.
            this.world = requireLoadedWorld(config.getIslandWorldName(), "island world");
            this.templateWorld = requireLoadedWorld(config.getTemplateWorldName(), "template world");
        } else {
            this.world = null;
            this.templateWorld = null;
        }
    }

    /**
     * Where a player arrives on the island: the configured offset from its centre block.
     */
    public Location spawn(int slot) {
        Location center = grid.center(requireWorld(), slot);
        return new Location(world, center.getX() + spawnOffset.getX(), center.getY() + spawnOffset.getY(), center.getZ() + spawnOffset.getZ(), spawnYaw, spawnPitch);
    }

    /**
     * Copies the template onto the island. Completes once every block has been placed.
     */
    public CompletableFuture<Void> createIsland(int slot) {
        CompletableFuture<Void> build = pasteTemplate(grid.center(requireWorld(), slot));
        buildsInProgress.put(slot, build);
        build.whenComplete((v, ex) -> buildsInProgress.remove(slot));
        return build;
    }

    /**
     * Completes once a build of this island still in progress has finished, or at once if there is
     * none. Never fails: a failed build is reported where it was started.
     */
    public CompletableFuture<Void> awaitBuild(int slot) {
        CompletableFuture<Void> build = buildsInProgress.get(slot);
        return build == null ? CompletableFuture.completedFuture(null) : build.exceptionally(ex -> null);
    }

    /**
     * Removes every block and non-player entity in the island's area, chunk by chunk. Chunks that
     * were never generated are skipped so the delete does not create them.
     */
    public CompletableFuture<Void> deleteIsland(int slot) {
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
        return clearInBatches(chunks, 0);
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
    private CompletableFuture<Void> onChunk(World target, int chunkX, int chunkZ, boolean generate, Function<Chunk, CompletableFuture<Void>> action) {
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
