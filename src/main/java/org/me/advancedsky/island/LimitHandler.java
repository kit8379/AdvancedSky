package org.me.advancedsky.island;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-island block and entity caps from limits.yml. Block counts start from the material
 * histogram of the level scan (see {@link LevelHandler}) and are adjusted live on place and
 * break; a change made in a chunk the running scan has already snapshotted is carried over into
 * the counts it produces. Whatever else removes blocks is caught up by the next scan.
 * Entities are counted inside the island boundary at spawn time. Limit tables are built at startup and on
 * reload, so the event hot path never touches YAML; an unknown name fails startup.
 */
public class LimitHandler {

    /**
     * Limit of a material or entity type that limits.yml does not list.
     */
    public static final int UNLIMITED = -1;

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandGrid islandGrid;

    private final ConcurrentHashMap<UUID, IslandCounts> countsByIsland = new ConcurrentHashMap<>();

    private volatile int[] blockLimitByOrdinal;
    private volatile int[] entityLimitByOrdinal;

    public LimitHandler(AdvancedSky plugin, ConfigHandler config, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.config = config;
        this.islandGrid = islandGrid;
        startup();
    }

    public void startup() {
        int[] blockLimits = new int[Material.values().length];
        Arrays.fill(blockLimits, UNLIMITED);
        for (Map.Entry<String, Integer> limit : config.getBlockLimits().entrySet()) {
            Material material = Material.matchMaterial(limit.getKey());
            if (material == null) {
                throw new IllegalStateException("Block limits: '" + limit.getKey() + "' is not a material");
            }

            if (limit.getValue() < 0) {
                throw new IllegalStateException("Block limits: " + limit.getKey() + " must be 0 or more");
            }

            blockLimits[material.ordinal()] = limit.getValue();
        }

        int[] entityLimits = new int[EntityType.values().length];
        Arrays.fill(entityLimits, UNLIMITED);
        for (Map.Entry<String, Integer> limit : config.getEntityLimits().entrySet()) {
            EntityType type;
            try {
                type = EntityType.valueOf(limit.getKey());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Entity limits: '" + limit.getKey() + "' is not an entity type");
            }

            if (limit.getValue() < 0) {
                throw new IllegalStateException("Entity limits: " + limit.getKey() + " must be 0 or more");
            }

            entityLimits[type.ordinal()] = limit.getValue();
        }

        this.blockLimitByOrdinal = blockLimits;
        this.entityLimitByOrdinal = entityLimits;
        plugin.debug("LimitHandler", "Loaded block limits " + config.getBlockLimits() + " and entity limits " + config.getEntityLimits());
    }

    /**
     * Starts a level scan of the island. Its chunks are snapshotted one after another while
     * blocks keep changing, so every change made in a chunk it has already snapshotted is kept
     * for it until it finishes.
     */
    public Scan beginScan(UUID islandUuid) {
        IslandCounts island = countsByIsland.computeIfAbsent(islandUuid, key -> new IslandCounts());
        synchronized (island) {
            island.scan = new Scan();
            return island.scan;
        }
    }

    /**
     * The scan has snapshotted the chunk, or found it not generated: changes there from now on
     * are missing from its histogram.
     */
    public void chunkScanned(UUID islandUuid, Scan scan, int chunkX, int chunkZ) {
        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return;
        }

        synchronized (island) {
            if (island.scan == scan) {
                scan.chunks.add(chunkKey(chunkX, chunkZ));
            }
        }
    }

    /**
     * Replaces the island's block counts with the histogram the scan produced plus the changes
     * made after their chunk was snapshotted. A scan that a newer one replaced, or of an island
     * dropped meanwhile, changes nothing.
     */
    public void finishScan(UUID islandUuid, Scan scan, int[] countsByMaterialOrdinal) {
        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return;
        }

        synchronized (island) {
            if (island.scan != scan) {
                return;
            }

            for (int i = 0; i < countsByMaterialOrdinal.length; i++) {
                countsByMaterialOrdinal[i] = Math.max(0, countsByMaterialOrdinal[i] + scan.changes[i]);
            }
            island.counts = countsByMaterialOrdinal;
            island.scan = null;
        }
    }

    /**
     * Drops the counts of a deleted island.
     */
    public void unload(UUID islandUuid) {
        countsByIsland.remove(islandUuid);
    }

    /**
     * The cap on that block per island: 0 forbids it outright, {@link #UNLIMITED} when not listed.
     */
    public int getBlockLimit(Material type) {
        return blockLimitByOrdinal[type.ordinal()];
    }

    /**
     * The cap on that entity per island: 0 forbids it outright, {@link #UNLIMITED} when not listed.
     */
    public int getEntityLimit(EntityType type) {
        return entityLimitByOrdinal[type.ordinal()];
    }

    /**
     * Whether a scan has produced counts for the island yet.
     */
    public boolean isReady(UUID islandUuid) {
        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return false;
        }

        synchronized (island) {
            return island.counts != null;
        }
    }

    /**
     * Counts one more of that block, placed in the given chunk, on the island if its cap allows.
     * Denies until the island's first scan since startup has produced counts, so it fails closed
     * rather than open.
     */
    public boolean tryIncrement(UUID islandUuid, Material type, int chunkX, int chunkZ) {
        int limit = blockLimitByOrdinal[type.ordinal()];
        if (limit == UNLIMITED) {
            return true;
        }

        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return false;
        }

        synchronized (island) {
            if (island.counts == null || island.counts[type.ordinal()] >= limit) {
                return false;
            }

            island.counts[type.ordinal()]++;
            island.record(type, 1, chunkX, chunkZ);
            return true;
        }
    }

    /**
     * Counts one less of that block, removed from the given chunk. A scan in progress takes the
     * removal even before the island's first counts exist.
     */
    public void decrement(UUID islandUuid, Material type, int chunkX, int chunkZ) {
        if (blockLimitByOrdinal[type.ordinal()] == UNLIMITED) {
            return;
        }

        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return;
        }

        synchronized (island) {
            if (island.counts != null && island.counts[type.ordinal()] > 0) {
                island.counts[type.ordinal()]--;
            }
            island.record(type, -1, chunkX, chunkZ);
        }
    }

    /**
     * Whether one more entity of that type fits on the island. Runs on the island's region
     * thread: every loaded chunk of one island belongs to the same region, as islands are far
     * smaller than the spacing that keeps neighbours in separate regions.
     */
    public boolean canSpawnEntity(Island island, World world, EntityType type) {
        int limit = entityLimitByOrdinal[type.ordinal()];
        if (limit == UNLIMITED) {
            return true;
        }

        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        Location center = islandGrid.center(world, island.getSlot());
        int minChunkX = Math.floorDiv(center.getBlockX() - size / 2, 16);
        int minChunkZ = Math.floorDiv(center.getBlockZ() - size / 2, 16);
        int maxChunkX = Math.floorDiv(center.getBlockX() - size / 2 + size - 1, 16);
        int maxChunkZ = Math.floorDiv(center.getBlockZ() - size / 2 + size - 1, 16);

        int count = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }

                for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                    Location location = entity.getLocation();
                    if (entity.getType() == type && islandGrid.isInsideIsland(island.getSlot(), size, location.getBlockX(), location.getBlockZ())) {
                        count++;
                    }
                }
            }
        }

        return count < limit;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * An island's block counts, null until its first scan finishes, and the scan in progress.
     * Guarded by its own monitor: events adjust it while a scan snapshots and hands over.
     */
    private static final class IslandCounts {
        private int[] counts;
        private Scan scan;

        private void record(Material type, int change, int chunkX, int chunkZ) {
            if (scan != null && scan.chunks.contains(chunkKey(chunkX, chunkZ))) {
                scan.changes[type.ordinal()] += change;
            }
        }
    }

    /**
     * A level scan in progress: the chunks it has snapshotted and the block changes made in
     * them since.
     */
    public static final class Scan {
        private final Set<Long> chunks = new HashSet<>();
        private final int[] changes = new int[Material.values().length];

        private Scan() {
        }
    }
}
