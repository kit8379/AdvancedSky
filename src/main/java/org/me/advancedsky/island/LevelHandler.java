package org.me.advancedsky.island;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.model.IslandTop;
import org.me.advancedsky.world.WorldHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LevelHandler {

    private static final int SCAN_BATCH_SIZE = 16;
    private static final long RANKING_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final LimitHandler limitHandler;
    private final WorldHandler worldHandler;
    private final IslandCache islandCache;
    private final IslandGrid islandGrid;

    private volatile int[] pointsByMaterialOrdinal;

    private CompletableFuture<Ranking> ranking;
    private long rankingLoadedAt;

    public LevelHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, LimitHandler limitHandler, WorldHandler worldHandler, IslandCache islandCache, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.limitHandler = limitHandler;
        this.worldHandler = worldHandler;
        this.islandCache = islandCache;
        this.islandGrid = islandGrid;
        startup();
    }

    public void startup() {
        Material[] materials = Material.values();
        int[] table = new int[materials.length];

        for (Material material : materials) {
            table[material.ordinal()] = config.getBlockLevel(material.name());
        }

        this.pointsByMaterialOrdinal = table;
    }

    /**
     * Scans the island's boundary for the island size the cache holds, on the region threads
     * that own the chunks. Islands not hosted here, or whose cache entry is unavailable, keep their
     * stored level.
     */
    public CompletableFuture<Integer> calIslandLevel(UUID islandUuid) {
        Island island = islandCache.get(islandUuid);

        // No scan while its entry loads again: failing makes the scheduler retry in seconds, with
        // the island's capped blocks still waiting for their counts, instead of in minutes.
        if (island == null && islandCache.isUnavailable(islandUuid)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Island " + islandUuid + " cannot be scanned while its cache entry loads again"));
        }

        if (island == null) {
            return getIslandLevel(islandUuid).thenApply(cachedLevel -> {
                plugin.debug("LevelHandler", "Island " + islandUuid + " is not available here, returning cached level: " + cachedLevel);
                return cachedLevel;
            });
        }

        World world = worldHandler.getWorld();
        Location center = islandGrid.center(world, island.getSlot());
        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        int minOffset = IslandGrid.boundaryMinOffset(size);
        int minBlockX = center.getBlockX() + minOffset;
        int maxBlockX = minBlockX + size - 1;
        int minBlockZ = center.getBlockZ() + minOffset;
        int maxBlockZ = minBlockZ + size - 1;

        int minChunkX = Math.floorDiv(minBlockX, 16);
        int minChunkZ = Math.floorDiv(minBlockZ, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int maxChunkZ = Math.floorDiv(maxBlockZ, 16);

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        List<int[]> chunkCoords = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunkCoords.add(new int[]{cx, cz});
            }
        }

        // Scan in small sequential batches so the snapshot work never hits the region threads
        // as one full-island burst, and only a batch worth of chunks is loaded at a time.
        // Every batch adds to one material histogram, which yields both the level and the
        // block counts the limit handler starts from.
        int[] counts = new int[Material.values().length];
        int[] secondHalves = new int[counts.length];
        LimitHandler.Scan scan = limitHandler.beginScan(islandUuid);
        CompletableFuture<Void> scanned = CompletableFuture.completedFuture(null);
        for (int start = 0; start < chunkCoords.size(); start += SCAN_BATCH_SIZE) {
            List<int[]> batch = chunkCoords.subList(start, Math.min(start + SCAN_BATCH_SIZE, chunkCoords.size()));
            scanned = scanned.thenCompose(v -> scanBatch(world, islandUuid, scan, batch, minY, maxY, minBlockX, maxBlockX, minBlockZ, maxBlockZ, counts, secondHalves));
        }

        return scanned.thenApplyAsync(v -> {
            int[] table = this.pointsByMaterialOrdinal;
            long total = 0;
            for (int i = 0; i < counts.length; i++) {
                total += (long) counts[i] * table[i];
            }

            // Hand the counts over before the database write, so a failed write still leaves
            // the island with fresh limit counts; place and break adjust them from here on. A
            // bed or door is one block to place and break, so its second block is not counted.
            for (int i = 0; i < counts.length; i++) {
                counts[i] -= secondHalves[i];
            }
            limitHandler.finishScan(islandUuid, scan, counts);

            int totalLevel = (int) Math.round((double) total / 100.0);
            database.updateIslandLevel(islandUuid, totalLevel);
            plugin.debug("LevelHandler", "Calculated level for island " + islandUuid + ": " + totalLevel);
            return totalLevel;
        }, plugin.getBukkitAsyncExecutor());
    }

    private CompletableFuture<Void> scanBatch(World world, UUID islandUuid, LimitHandler.Scan scan, List<int[]> batch, int minY, int maxY, int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, int[] counts, int[] secondHalves) {
        List<ChunkSnapshot> snapshots = Collections.synchronizedList(new ArrayList<>(batch.size()));
        List<CompletableFuture<Void>> taken = new ArrayList<>(batch.size());
        for (int[] coord : batch) {
            // gen=false: a never-generated chunk holds nothing and scores zero, so skip it
            // instead of generating and persisting it just to scan.
            taken.add(worldHandler.onChunk(world, coord[0], coord[1], false, chunk -> {
                snapshots.add(chunk.getChunkSnapshot(false, false, false, false));
                limitHandler.chunkScanned(islandUuid, scan, chunk.getX(), chunk.getZ());
                return CompletableFuture.completedFuture(null);
            }));
        }

        return CompletableFuture.allOf(taken.toArray(new CompletableFuture[0])).thenAcceptAsync(v -> {
            // Chunks not generated yet held nothing when looked at; blocks placed in them from
            // now on are the scan's to carry over as well.
            for (int[] coord : batch) {
                limitHandler.chunkScanned(islandUuid, scan, coord[0], coord[1]);
            }

            for (ChunkSnapshot snapshot : snapshots) {
                countSnapshot(snapshot, minY, maxY, minBlockX, maxBlockX, minBlockZ, maxBlockZ, counts, secondHalves);
            }
        }, plugin.getBukkitAsyncExecutor());
    }

    /**
     * Adds the snapshot's blocks inside the bounds to the counts, and to the second halves the
     * upper or head block of each capped two-block block (bed, door, tall plant).
     */
    private void countSnapshot(ChunkSnapshot snapshot, int minY, int maxY, int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, int[] counts, int[] secondHalves) {

        int minX = Math.max(0, minBlockX - snapshot.getX() * 16);
        int maxX = Math.min(15, maxBlockX - snapshot.getX() * 16);
        int minZ = Math.max(0, minBlockZ - snapshot.getZ() * 16);
        int maxZ = Math.min(15, maxBlockZ - snapshot.getZ() * 16);

        // A section holding only air scores nothing and most of an island is air, so those are
        // skipped. Section indexes count from 0 at the world's lowest section.
        for (int section = 0; section << 4 < maxY - minY; section++) {
            if (snapshot.isSectionEmpty(section)) {
                continue;
            }

            int fromY = minY + (section << 4);
            int toY = Math.min(fromY + 16, maxY);
            for (int y = fromY; y < toY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {

                        Material type = snapshot.getBlockType(x, y, z);
                        counts[type.ordinal()]++;
                        if (limitHandler.getBlockLimit(type) != LimitHandler.UNLIMITED && isSecondHalf(snapshot.getBlockData(x, y, z))) {
                            secondHalves[type.ordinal()]++;
                        }

                    }
                }
            }
        }
    }

    private static boolean isSecondHalf(BlockData data) {
        if (data instanceof Bed bed) {
            return bed.getPart() == Bed.Part.HEAD;
        }

        // Stairs and trapdoors are single blocks whose half is only how they sit.
        return data instanceof Bisected bisected && !(data instanceof Stairs) && !(data instanceof TrapDoor) && bisected.getHalf() == Bisected.Half.TOP;
    }

    public CompletableFuture<Integer> getIslandLevel(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> {
            return database.getIslandLevel(islandUuid);
        }, plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<List<IslandTop>> getTopIslandLevels(int limit) {
        return ranking().thenApplyAsync(ranking -> {
            List<IslandTop> top = ranking.order().subList(0, Math.min(Math.max(1, limit), ranking.order().size()));
            Map<UUID, Set<UUID>> members = database.getIslandMembers(top.stream().map(IslandTop::getIslandUuid).toList());
            return top.stream().map(island -> new IslandTop(island.getIslandUuid(), island.getOwnerUuid(), island.getLevel(), members.getOrDefault(island.getIslandUuid(), Set.of()))).toList();
        }, plugin.getBukkitAsyncExecutor());
    }

    /**
     * An island created since the ranking was read is not in it yet.
     */
    public CompletableFuture<Long> getIslandRank(UUID islandUuid) {
        return ranking().thenApply(ranking -> {
            Integer position = ranking.positions().get(islandUuid);
            if (position == null) {
                throw new IslandDoesNotExistException();
            }
            return position + 1L;
        });
    }

    /**
     * Every island in leaderboard order, read in one query at most once a minute and shared by
     * the top list and every rank, rather than sorting or counting all islands for each. A failed
     * read is retried on the next request.
     */
    private synchronized CompletableFuture<Ranking> ranking() {
        long now = System.nanoTime();
        if (ranking == null || ranking.isCompletedExceptionally() || (ranking.isDone() && now - rankingLoadedAt >= RANKING_TTL_NANOS)) {
            ranking = CompletableFuture.supplyAsync(() -> new Ranking(database.getIslandRanking()), plugin.getBukkitAsyncExecutor());
            rankingLoadedAt = now;
        }
        return ranking;
    }

    private record Ranking(List<IslandTop> order, Map<UUID, Integer> positions) {

        private Ranking(List<IslandTop> order) {
            this(order, positionsOf(order));
        }

        private static Map<UUID, Integer> positionsOf(List<IslandTop> order) {
            Map<UUID, Integer> positions = new HashMap<>(order.size() * 2);
            for (int i = 0; i < order.size(); i++) {
                positions.put(order.get(i).getIslandUuid(), i);
            }
            return positions;
        }
    }
}
