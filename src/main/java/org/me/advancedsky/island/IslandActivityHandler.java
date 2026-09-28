package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many players stand on each island hosted here, and when the last one left. Level scans
 * follow it: an island is scanned while it has players and once more after the last one leaves.
 */
public class IslandActivityHandler {

    private final Map<UUID, Integer> playerCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLeftTimestamps = new ConcurrentHashMap<>();

    private final AdvancedSky plugin;

    public IslandActivityHandler(AdvancedSky plugin) {
        this.plugin = plugin;
    }

    /**
     * Counts a player onto the island. True when they are the only one there, the island having
     * been empty.
     */
    public boolean playerEnter(UUID islandUuid) {
        int count = playerCounts.merge(islandUuid, 1, Integer::sum);
        plugin.debug("IslandActivityHandler", "Player entered island: " + islandUuid + ", current count: " + count);
        return count == 1;
    }

    public void playerLeave(UUID islandUuid, long currentTime) {
        playerCounts.computeIfPresent(islandUuid, (island, count) -> {
            lastLeftTimestamps.put(island, currentTime);
            return count > 1 ? count - 1 : null;
        });
    }

    /**
     * Whether anyone is on the island now or left it at or after the given time.
     */
    public boolean wasActiveSince(UUID islandUuid, long since) {
        if (playerCounts.containsKey(islandUuid)) {
            return true;
        }

        Long lastLeft = lastLeftTimestamps.get(islandUuid);
        return lastLeft != null && lastLeft >= since;
    }
}
