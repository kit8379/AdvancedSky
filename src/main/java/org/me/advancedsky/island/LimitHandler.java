package org.me.advancedsky.island;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BoundingBox;
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
    // Where each limited block is counted in an island's counts, -1 for a block without a limit:
    // only limited blocks are counted. The same array while the set of limited blocks stays the
    // same, so the counts built with it stay usable across a reload.
    private volatile int[] countSlotByOrdinal;
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

        int[] countSlots = new int[blockLimits.length];
        int counted = 0;
        for (int ordinal = 0; ordinal < blockLimits.length; ordinal++) {
            countSlots[ordinal] = blockLimits[ordinal] == UNLIMITED ? -1 : counted++;
        }

        this.blockLimitByOrdinal = blockLimits;
        if (!Arrays.equals(countSlots, countSlotByOrdinal)) {
            this.countSlotByOrdinal = countSlots;
        }
        this.entityLimitByOrdinal = entityLimits;
        plugin.debug("LimitHandler", "Loaded block limits " + config.getBlockLimits() + " and entity limits " + config.getEntityLimits());
    }

    /**
     * Starts a level scan of the island. Its chunks are snapshotted one after another while
     * blocks keep changing, so every change made in a chunk it has already snapshotted is kept
     * for it until it finishes.
     */
    public Scan beginScan(UUID islandUuid) {
        // Counts laid out for another set of limited blocks are replaced by the ones this scan builds.
        int[] countSlots = countSlotByOrdinal;
        IslandCounts island = countsByIsland.compute(islandUuid, (key, current) -> current == null || current.countSlots != countSlots ? new IslandCounts(countSlots) : current);
        synchronized (island) {
            island.scan = new Scan(island.countSlots);
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

            int[] counts = new int[scan.changes.length];
            for (int ordinal = 0; ordinal < island.countSlots.length; ordinal++) {
                int slot = island.countSlots[ordinal];
                if (slot >= 0) {
                    counts[slot] = Math.max(0, countsByMaterialOrdinal[ordinal] + scan.changes[slot]);
                }
            }
            island.counts = counts;
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
     * Whether a scan has produced counts for the island yet, for the blocks limited now.
     */
    public boolean isReady(UUID islandUuid) {
        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return false;
        }

        synchronized (island) {
            return island.isCurrent(countSlotByOrdinal);
        }
    }

    /**
     * Whether the island's cap allows one more of that block. Denies until the island's first
     * scan since startup has produced counts, so it fails closed rather than open.
     */
    public boolean hasRoom(UUID islandUuid, Material type) {
        int limit = blockLimitByOrdinal[type.ordinal()];
        if (limit == UNLIMITED) {
            return true;
        }

        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return false;
        }

        synchronized (island) {
            int slot = island.countSlots[type.ordinal()];
            return island.isCurrent(countSlotByOrdinal) && slot >= 0 && island.counts[slot] < limit;
        }
    }

    /**
     * Counts one more of that block, placed in the given chunk, once its placement stands. A scan
     * in progress takes the addition even before the island's first counts exist.
     */
    public void increment(UUID islandUuid, Material type, int chunkX, int chunkZ) {
        if (blockLimitByOrdinal[type.ordinal()] == UNLIMITED) {
            return;
        }

        IslandCounts island = countsByIsland.get(islandUuid);
        if (island == null) {
            return;
        }

        synchronized (island) {
            int slot = island.countSlots[type.ordinal()];
            if (slot < 0) {
                return;
            }
            if (island.counts != null) {
                island.counts[slot]++;
            }
            island.record(slot, 1, chunkX, chunkZ);
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
            int slot = island.countSlots[type.ordinal()];
            if (slot < 0) {
                return;
            }
            if (island.counts != null && island.counts[slot] > 0) {
                island.counts[slot]--;
            }
            island.record(slot, -1, chunkX, chunkZ);
        }
    }

    /**
     * Whether one more entity of that type fits on the island. Runs on the island's region
     * thread: every loaded chunk of one island belongs to the same region, as islands are far
     * smaller than the spacing that keeps neighbours in separate regions. The lookup visits only
     * the loaded entity sections inside the island boundary.
     */
    public boolean canSpawnEntity(Island island, World world, EntityType type) {
        int limit = entityLimitByOrdinal[type.ordinal()];
        if (limit == UNLIMITED) {
            return true;
        }

        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        Location center = islandGrid.center(world, island.getSlot());
        int minOffset = IslandGrid.boundaryMinOffset(size);
        int minX = center.getBlockX() + minOffset;
        int minZ = center.getBlockZ() + minOffset;
        BoundingBox boundary = new BoundingBox(minX, world.getMinHeight(), minZ, minX + size, world.getMaxHeight(), minZ + size);

        // An entity whose box only reaches over the boundary stands outside it.
        int count = world.getNearbyEntities(boundary, entity -> {
            if (entity.getType() != type) {
                return false;
            }
            Location location = entity.getLocation();
            return islandGrid.isInsideIsland(island.getSlot(), size, location.getBlockX(), location.getBlockZ());
        }).size();
        return count < limit;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * An island's counts of its limited blocks, null until its first scan finishes, laid out by
     * the count slots it was made with, and the scan in progress. Guarded by its own monitor:
     * events adjust it while a scan snapshots and hands over.
     */
    private static final class IslandCounts {
        private final int[] countSlots;
        private int[] counts;
        private Scan scan;

        private IslandCounts(int[] countSlots) {
            this.countSlots = countSlots;
        }

        /**
         * Counted, and for the blocks limited now: a reload that limits other blocks needs a scan first.
         */
        private boolean isCurrent(int[] currentCountSlots) {
            return counts != null && countSlots == currentCountSlots;
        }

        private void record(int slot, int change, int chunkX, int chunkZ) {
            if (scan != null && scan.chunks.contains(chunkKey(chunkX, chunkZ))) {
                scan.changes[slot] += change;
            }
        }
    }

    /**
     * A level scan in progress: the chunks it has snapshotted and the block changes made in
     * them since.
     */
    public static final class Scan {
        private final Set<Long> chunks = new HashSet<>();
        private final int[] changes;

        private Scan(int[] countSlots) {
            this.changes = new int[(int) Arrays.stream(countSlots).filter(slot -> slot >= 0).count()];
        }
    }
}
