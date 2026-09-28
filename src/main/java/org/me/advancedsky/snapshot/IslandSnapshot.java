package org.me.advancedsky.snapshot;

import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-server cache of the islands hosted here, read on every block break, PvP hit and move.
 * Islands are found by id or, for a location, by the grid slot whose area holds it.
 * <p>
 * A missing or failed (dirty) snapshot is unavailable so listeners fail closed. A reload in
 * flight keeps serving the previous snapshot: it is at most one mutation behind for one DB
 * round trip, and blanking it would let the access listener lobby-bounce legitimate members.
 */
public class IslandSnapshot {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandGrid islandGrid;

    private final Map<UUID, Island> islands = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> islandsBySlot = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> dirty = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loading = new ConcurrentHashMap<>();
    private final AtomicLong nextLoadGeneration = new AtomicLong();

    public IslandSnapshot(AdvancedSky plugin, DatabaseHandler database, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.database = database;
        this.islandGrid = islandGrid;
    }

    public Island get(UUID islandUuid) {
        if (dirty.containsKey(islandUuid)) {
            return null;
        }

        return islands.get(islandUuid);
    }

    /**
     * The island whose area holds the location, or null in the gap, on a free slot, for an
     * island not hosted here, and while its snapshot is unavailable (see {@link #get}). The
     * caller checks that the location is in the island world.
     */
    public Island getAt(Location location) {
        int slot = islandGrid.slotAt(location.getX(), location.getZ());
        if (slot < 0) {
            return null;
        }

        UUID islandUuid = islandsBySlot.get(slot);
        return islandUuid == null ? null : get(islandUuid);
    }

    public CompletableFuture<Void> load(UUID islandUuid) {
        long generation = nextLoadGeneration.incrementAndGet();
        loading.put(islandUuid, generation);

        try {
            Island island = database.getIslandSnapshot(islandUuid);

            if (!Long.valueOf(generation).equals(loading.get(islandUuid))) {
                return CompletableFuture.completedFuture(null);
            }

            if (island == null) {
                islands.remove(islandUuid);
                throw new IllegalStateException("Island org.me.advancedsky.snapshot does not exist: " + islandUuid);
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
                dirty.put(islandUuid, Boolean.TRUE);
                plugin.severe("Failed to load island org.me.advancedsky.snapshot: " + islandUuid, error);
            }

            return CompletableFuture.failedFuture(error);
        }
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
    }
}
