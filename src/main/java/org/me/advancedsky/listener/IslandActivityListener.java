package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follows each player from island to island in the island world and tells the level scheduler
 * when they arrive and leave, so islands are rescanned while in use.
 */
public class IslandActivityListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final LevelUpdateScheduler levelUpdateScheduler;

    private final Map<UUID, UUID> islandByPlayer = new ConcurrentHashMap<>();

    public IslandActivityListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache, LevelUpdateScheduler levelUpdateScheduler) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
        this.levelUpdateScheduler = levelUpdateScheduler;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        update(player, player.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID islandUuid = islandByPlayer.remove(event.getPlayer().getUniqueId());
        if (islandUuid != null) {
            levelUpdateScheduler.playerLeave(islandUuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        update(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }

        update(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        update(event.getPlayer(), event.getRespawnLocation());
    }

    private void update(Player player, Location location) {
        UUID islandUuid = getIslandUuid(location);
        UUID playerUuid = player.getUniqueId();
        UUID previousIsland;
        if (islandUuid == null) {
            previousIsland = islandByPlayer.remove(playerUuid);
        } else {
            previousIsland = islandByPlayer.put(playerUuid, islandUuid);
            if (islandUuid.equals(previousIsland)) {
                return;
            }
        }

        if (previousIsland != null) {
            levelUpdateScheduler.playerLeave(previousIsland);
        }

        if (islandUuid != null) {
            levelUpdateScheduler.playerEnter(islandUuid);
            plugin.debug("IslandActivityListener", "Player " + player.getName() + " entered island: " + islandUuid);
        }
    }

    private UUID getIslandUuid(Location location) {
        if (location == null) {
            return null;
        }

        World world = location.getWorld();
        if (world == null || !world.getName().equals(config.getIslandWorldName())) {
            return null;
        }

        return islandCache.getIslandUuidAt(location);
    }
}
