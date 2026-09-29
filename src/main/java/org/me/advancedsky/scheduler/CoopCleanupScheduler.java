package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.exceptions.PlayerNotCoopedException;
import org.me.advancedsky.island.CoopHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Ends the co-ops of players who are online nowhere in the network. A co-op normally ends a few
 * seconds after its player quits, but no quit is seen for the players online when a server stops
 * (plugins are disabled before they are kicked) or crashes, so their co-ops would outlive them.
 * Every few minutes the host looks at the co-ops of the islands it hosts and removes those whose
 * player the online registry no longer lists.
 */
public final class CoopCleanupScheduler {

    private static final long PERIOD_MINUTES = 5L;

    private final AdvancedSky plugin;
    private final IslandCache islandCache;
    private final CoopHandler coopHandler;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private ScheduledTask task;

    public CoopCleanupScheduler(AdvancedSky plugin, IslandCache islandCache, CoopHandler coopHandler, OnlinePlayerRegistry onlinePlayerRegistry) {
        this.plugin = plugin;
        this.islandCache = islandCache;
        this.coopHandler = coopHandler;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
    }

    public void start() {
        if (task != null) {
            return;
        }

        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> sweep(), PERIOD_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sweep() {
        for (Island island : islandCache.getIslands()) {
            for (UUID playerUuid : island.getCoops()) {
                try {
                    if (onlinePlayerRegistry.isOnline(playerUuid)) {
                        continue;
                    }
                } catch (RuntimeException e) {
                    // The registry logged it; the next sweep tries again.
                    return;
                }

                coopHandler.unCoopPlayer(new Actor.Bypass("coop offline cleanup"), island.getIslandUuid(), playerUuid).thenRun(() -> {
                    plugin.debug("CoopCleanupScheduler", "Removed coop of offline player " + playerUuid + " on island " + island.getIslandUuid());
                }).exceptionally(e -> {
                    // A quit cleanup or an uncoop may have removed it meanwhile.
                    if (!(e instanceof CompletionException && e.getCause() instanceof PlayerNotCoopedException)) {
                        plugin.severe("Failed to remove coop of offline player " + playerUuid + " on island " + island.getIslandUuid(), e);
                    }
                    return null;
                });
            }
        }
    }
}
