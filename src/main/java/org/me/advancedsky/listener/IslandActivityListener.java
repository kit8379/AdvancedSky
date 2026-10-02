package org.me.advancedsky.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private static final long RECHECK_PERIOD_TICKS = 20L * 60;

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final LevelUpdateScheduler levelUpdateScheduler;

    private final Map<UUID, UUID> currentIsland = new ConcurrentHashMap<>();

    public IslandActivityListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache, LevelUpdateScheduler levelUpdateScheduler) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
        this.levelUpdateScheduler = levelUpdateScheduler;
    }

    /**
     * Looks again, once a minute, at where each player on an island stands, on their own thread.
     * Folia moves a player by teleportAsync or through a portal without any event, so one who
     * then stands still would keep their old island in use and leave the new one unscanned.
     */
    public void start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (UUID playerUuid : currentIsland.keySet()) {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null) {
                    player.getScheduler().run(plugin, scheduled -> update(player, player.getLocation()), null);
                }
            }
        }, RECHECK_PERIOD_TICKS, RECHECK_PERIOD_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID left = currentIsland.remove(event.getPlayer().getUniqueId());
        if (left != null) {
            levelUpdateScheduler.playerLeave(left);
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
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        update(event.getPlayer(), event.getRespawnLocation());
    }

    private void update(Player player, Location location) {
        UUID islandUuid = getIslandUuid(location);
        UUID playerUuid = player.getUniqueId();
        UUID previous = islandUuid == null ? currentIsland.remove(playerUuid) : currentIsland.get(playerUuid);

        if (islandUuid != null && islandUuid.equals(previous)) {
            return;
        }

        if (islandUuid != null) {
            currentIsland.put(playerUuid, islandUuid);
        }

        if (previous != null) {
            levelUpdateScheduler.playerLeave(previous);
        }

        if (islandUuid != null) {
            levelUpdateScheduler.playerEnter(islandUuid);
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
