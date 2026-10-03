package org.me.advancedsky.cache;

import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every island hosted on this server, held for the server's whole run: loaded in one read at
 * startup, reloaded by the host after each change it makes, and read on every block break, PvP
 * hit and move. Islands are found by id or, for a location, by the grid slot whose area holds it.
 * <p>
 * An island whose load failed (dirty) is unavailable so listeners fail closed, and is loaded
 * again with a growing delay until a load succeeds. A reload in flight keeps serving the
 * previous entry: it is at most one mutation behind for one DB round trip, and blanking it would
 * let the access listener lobby-bounce legitimate members.
 */
public class IslandCache {

    private static final long RETRY_FIRST_DELAY_SECONDS = 2L;
    private static final long RETRY_MAX_DELAY_SECONDS = 30L;

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandGrid islandGrid;

    private final Map<UUID, Island> islands = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> islandsBySlot = new ConcurrentHashMap<>();
    private final Set<UUID> deleting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> dirty = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loading = new ConcurrentHashMap<>();
    private final AtomicLong nextLoadGeneration = new AtomicLong();

    public IslandCache(AdvancedSky plugin, DatabaseHandler database, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.database = database;
        this.islandGrid = islandGrid;
    }

    public Island get(UUID islandUuid) {
        if (deleting.contains(islandUuid) || dirty.containsKey(islandUuid)) {
            return null;
        }

        return islands.get(islandUuid);
    }

    /**
     * Whether the island is hosted here but its entry is unavailable while it loads again.
     */
    public boolean isUnavailable(UUID islandUuid) {
        return dirty.containsKey(islandUuid);
    }

    /**
     * The island whose area holds the location, or null in the gap, on a free slot, for an
     * island not hosted here, and while its entry is unavailable (see {@link #get}). The
     * caller checks that the location is in the island world.
     */
    public Island getAt(Location location) {
        UUID islandUuid = getIslandUuidAt(location);
        return islandUuid == null ? null : get(islandUuid);
    }

    /**
     * The island hosted here whose area holds the location, even while its entry is
     * unavailable, as NewSky knows an island from its world's name alone. Null in the gap and on
     * free slots.
     */
    public UUID getIslandUuidAt(Location location) {
        int slot = islandGrid.slotAt(location.getX(), location.getZ());
        return slot < 0 ? null : islandsBySlot.get(slot);
    }

    public boolean isDeletingAt(Location location) {
        UUID islandUuid = getIslandUuidAt(location);
        return islandUuid != null && deleting.contains(islandUuid);
    }

    /**
     * Keep the position identifiable but inaccessible while physical deletion is in progress.
     */
    public void beginDeletion(UUID islandUuid, int slot) {
        deleting.add(islandUuid);
        loading.remove(islandUuid);
        dirty.remove(islandUuid);
        islandsBySlot.put(slot, islandUuid);
    }

    /**
     * Loads every island hosted on the server in one read. Called once at startup, before any
     * other load, mutation or listener can touch the cache.
     */
    public void loadAll(String server) {
        database.getDeletingIslands(server).forEach(this::beginDeletion);
        database.getIslandsByServer(server).forEach((islandUuid, island) -> {
            islands.put(islandUuid, island);
            islandsBySlot.put(island.getSlot(), islandUuid);
        });
    }

    public CompletableFuture<Void> load(UUID islandUuid) {
        if (deleting.contains(islandUuid)) return CompletableFuture.completedFuture(null);
        long generation = nextLoadGeneration.incrementAndGet();
        loading.put(islandUuid, generation);

        try {
            Island island = database.getIsland(islandUuid);

            if (!Long.valueOf(generation).equals(loading.get(islandUuid))) {
                return CompletableFuture.completedFuture(null);
            }

            // Gone from the database: its area is a free slot now, not an island to retry.
            if (island == null) {
                unload(islandUuid);
                throw new IllegalStateException("Island does not exist: " + islandUuid);
            }

            loading.compute(islandUuid, (key, current) -> {
                if (!Long.valueOf(generation).equals(current)) {
                    return current;
                }

                islands.put(key, island);
                islandsBySlot.put(island.getSlot(), key);
                dirty.remove(key);
                return null;
            });

            return CompletableFuture.completedFuture(null);
        } catch (Throwable error) {
            if (loading.remove(islandUuid, generation)) {
                plugin.severe("Failed to load island into the cache: " + islandUuid, error);
                Object retry = new Object();
                if (dirty.putIfAbsent(islandUuid, retry) == null) {
                    retryLoad(islandUuid, retry, RETRY_FIRST_DELAY_SECONDS);
                }
            }

            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Loads a dirty island again after the delay, doubling it up to a cap, while the island is
     * still dirty under this retry. Any load that succeeds, or an unload, ends it.
     */
    private void retryLoad(UUID islandUuid, Object retry, long delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS, plugin.getBukkitAsyncExecutor()).execute(() -> {
            if (dirty.get(islandUuid) != retry) {
                return;
            }

            load(islandUuid).whenComplete((v, error) -> {
                if (dirty.get(islandUuid) == retry) {
                    retryLoad(islandUuid, retry, Math.min(delaySeconds * 2, RETRY_MAX_DELAY_SECONDS));
                }
            });
        });
    }

    public CompletableFuture<Void> reload(UUID islandUuid) {
        if (!islands.containsKey(islandUuid) && !loading.containsKey(islandUuid)) {
            return CompletableFuture.completedFuture(null);
        }

        return load(islandUuid);
    }

    public void unload(UUID islandUuid) {
        loading.remove(islandUuid);
        Island island = islands.remove(islandUuid);
        if (island != null) {
            islandsBySlot.remove(island.getSlot(), islandUuid);
        }
        dirty.remove(islandUuid);
        deleting.remove(islandUuid);
        islandsBySlot.entrySet().removeIf(entry -> entry.getValue().equals(islandUuid));
    }
}
