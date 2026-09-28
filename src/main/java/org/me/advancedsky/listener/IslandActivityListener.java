package org.me.advancedsky.listener;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.island.IslandActivityHandler;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follows each player from island to island in the island world. The first player to arrive on
 * an empty island schedules its level scans, which also make its block limit counts ready.
 */
public class IslandActivityListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final IslandActivityHandler islandActivityHandler;
    private final LevelUpdateScheduler levelUpdateScheduler;

    private final Map<UUID, UUID> currentIsland = new ConcurrentHashMap<>();

    public IslandActivityListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache, IslandActivityHandler islandActivityHandler, LevelUpdateScheduler levelUpdateScheduler) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
        this.islandActivityHandler = islandActivityHandler;
        this.levelUpdateScheduler = levelUpdateScheduler;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID left = currentIsland.remove(event.getPlayer().getUniqueId());
        if (left != null) {
            islandActivityHandler.playerLeave(left, System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        update(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            update(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(PlayerPostRespawnEvent event) {
        update(event.getPlayer(), event.getRespawnedLocation());
    }

    private void update(Player player, Location location) {
        UUID islandUuid = getIslandUuid(location);
        UUID playerUuid = player.getUniqueId();
        UUID previous = islandUuid == null ? currentIsland.remove(playerUuid) : currentIsland.put(playerUuid, islandUuid);

        if (islandUuid != null && islandUuid.equals(previous)) {
            return;
        }

        if (previous != null) {
            islandActivityHandler.playerLeave(previous, System.currentTimeMillis());
        }

        if (islandUuid != null) {
            if (islandActivityHandler.playerEnter(islandUuid)) {
                levelUpdateScheduler.registerIsland(islandUuid);
            }
            plugin.debug("IslandActivityListener", "Player " + player.getName() + " entered island: " + islandUuid);
        }
    }

    private UUID getIslandUuid(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
            return null;
        }

        return islandCache.getIslandUuidAt(location);
    }
}
