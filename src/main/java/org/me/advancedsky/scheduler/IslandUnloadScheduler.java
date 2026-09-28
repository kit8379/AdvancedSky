package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.island.LimitHandler;
import org.me.advancedsky.world.WorldActivityHandler;

import java.util.concurrent.TimeUnit;

/**
 * Islands stay in their world for good, so nothing is unloaded from the world. What NewSky drops
 * with an unloaded island world is dropped here once an island has been empty for the unload
 * interval: its level scans and its block limit counts. The next player to arrive starts both
 * again.
 */
public class IslandUnloadScheduler {

    private final AdvancedSky plugin;
    private final WorldActivityHandler worldActivityHandler;
    private final LevelUpdateScheduler levelUpdateScheduler;
    private final LimitHandler limitHandler;

    private final long unloadInterval;

    private ScheduledTask task;

    public IslandUnloadScheduler(AdvancedSky plugin, ConfigHandler config, WorldActivityHandler worldActivityHandler, LevelUpdateScheduler levelUpdateScheduler, LimitHandler limitHandler) {
        this.plugin = plugin;
        this.worldActivityHandler = worldActivityHandler;
        this.levelUpdateScheduler = levelUpdateScheduler;
        this.limitHandler = limitHandler;
        this.unloadInterval = config.getIslandUnloadInterval();
    }

    public void start() {
        if (task != null) {
            plugin.debug("IslandUnloadScheduler", "Unload scheduler is already running.");
            return;
        }

        plugin.debug("IslandUnloadScheduler", "Starting unload scheduler with interval: " + unloadInterval + " seconds.");
        this.task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> checkInactiveIslands(), unloadInterval, unloadInterval, TimeUnit.SECONDS);
        plugin.debug("IslandUnloadScheduler", "Unload scheduler started successfully.");
    }

    public void stop() {
        if (task != null) {
            plugin.debug("IslandUnloadScheduler", "Stopping unload scheduler.");
            task.cancel();
            task = null;
            plugin.debug("IslandUnloadScheduler", "Unload scheduler stopped.");
        }
    }

    private void checkInactiveIslands() {
        plugin.debug("IslandUnloadScheduler", "Checking for inactive islands...");
        long now = System.currentTimeMillis();
        long thresholdMillis = unloadInterval * 1000L;

        worldActivityHandler.getInactiveIslands(thresholdMillis, now).forEach((islandUuid, timestamp) -> {
            levelUpdateScheduler.unregisterIsland(islandUuid);
            limitHandler.unload(islandUuid);
            worldActivityHandler.clearIsland(islandUuid);
            plugin.debug("IslandUnloadScheduler", "Stopped level updates and limit counting for inactive island: " + islandUuid);
        });
    }
}
