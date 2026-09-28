package org.me.advancedsky.cache;

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
 * Every island hosted on this server, held for the server's whole run: loaded in one read at
 * startup, reloaded by the host after each change it makes, and read on every block break, PvP
 * hit and move. Islands are found by id or, for a location, by the grid slot whose area holds it.
 * <p>
 * An island whose load failed (dirty) is unavailable so listeners fail closed. A reload in
 * flight keeps serving the previous entry: it is at most one mutation behind for one DB
 * round trip, and blanking it would let the access listener lobby-bounce legitimate members.
 */
public class IslandCache {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandGrid islandGrid;

    private final Map<UUID, Island> islands = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> islandsBySlot = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> dirty = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loading = new ConcurrentHashMap<>();
    private final AtomicLong nextLoadGeneration = new AtomicLong();

    public IslandCache(AdvancedSky plugin, DatabaseHandler database, IslandGrid islandGrid) {
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

    /**
     * Loads every island hosted on the server in one read. Called once at startup, before any
     * other load, mutation or listener can touch the cache.
     */
    public void loadAll(String server) {
        database.getIslandCachesByServer(server).forEach((islandUuid, island) -> {
            islands.put(islandUuid, island);
            islandsBySlot.put(island.getSlot(), islandUuid);
        });
    }

    public CompletableFuture<Void> load(UUID islandUuid) {
        long generation = nextLoadGeneration.incrementAndGet();
        loading.put(islandUuid, generation);

        try {
            Island island = database.getIslandCache(islandUuid);

            if (!Long.valueOf(generation).equals(loading.get(islandUuid))) {
                return CompletableFuture.completedFuture(null);
            }

            if (island == null) {
                islands.remove(islandUuid);
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
                dirty.put(islandUuid, Boolean.TRUE);
                plugin.severe("Failed to load island into the cache: " + islandUuid, error);
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
