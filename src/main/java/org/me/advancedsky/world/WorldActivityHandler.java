package org.me.advancedsky.world;

import org.me.advancedsky.AdvancedSky;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which hosted islands have players on them, and since when an island has been empty. NewSky
 * tracks island worlds; here every island shares one world, so presence is tracked per island.
 * An island is tracked from the first player entering it until it is cleared after staying empty.
 */
public class WorldActivityHandler {

    private final Map<UUID, Integer> playerCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEmptyTimestamps = new ConcurrentHashMap<>();

    private final AdvancedSky plugin;

    public WorldActivityHandler(AdvancedSky plugin) {
        this.plugin = plugin;
    }

    /**
     * Whether the island is tracked: someone is on it, or it has not been empty long enough to
     * be cleared.
     */
    public boolean isTracked(UUID islandUuid) {
        return playerCounts.containsKey(islandUuid) || lastEmptyTimestamps.containsKey(islandUuid);
    }

    public void playerEnter(UUID islandUuid) {
        playerCounts.merge(islandUuid, 1, Integer::sum);
        lastEmptyTimestamps.remove(islandUuid);
        plugin.debug("WorldActivityHandler", "Player entered island: " + islandUuid + ", current count: " + playerCounts.get(islandUuid));
    }

    public void playerLeave(UUID islandUuid, long currentTime) {
        playerCounts.computeIfPresent(islandUuid, (island, count) -> {
            int newCount = count - 1;
            if (newCount <= 0) {
                lastEmptyTimestamps.put(island, currentTime);
                plugin.debug("WorldActivityHandler", "Island " + island + " is now empty, timestamp recorded: " + currentTime);
                return null;
            }
            return newCount;
        });
    }

    public Map<UUID, Long> getInactiveIslands(long thresholdMillis, long now) {
        Map<UUID, Long> result = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, Long> entry : lastEmptyTimestamps.entrySet()) {
            if (now - entry.getValue() >= thresholdMillis) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public void clearIsland(UUID islandUuid) {
        playerCounts.remove(islandUuid);
        lastEmptyTimestamps.remove(islandUuid);
        plugin.debug("WorldActivityHandler", "Cleared activity tracking for island: " + islandUuid);
    }
}
