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
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;
import org.me.advancedsky.snapshot.IslandSnapshot;
import org.me.advancedsky.world.WorldActivityHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Follows each player from island to island inside the island world, which NewSky reads from
 * world changes. The first player to arrive on an island starts its level updates (NewSky does
 * this when the island world loads), so its block limit counts become ready.
 */
public class WorldActivityListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandSnapshot islandSnapshot;
    private final WorldActivityHandler worldActivityHandler;
    private final LevelUpdateScheduler levelUpdateScheduler;

    private final Map<UUID, UUID> currentIsland = new ConcurrentHashMap<>();

    public WorldActivityListener(AdvancedSky plugin, ConfigHandler config, IslandSnapshot islandSnapshot, WorldActivityHandler worldActivityHandler, LevelUpdateScheduler levelUpdateScheduler) {
        this.plugin = plugin;
        this.config = config;
        this.islandSnapshot = islandSnapshot;
        this.worldActivityHandler = worldActivityHandler;
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
            worldActivityHandler.playerLeave(left, System.currentTimeMillis());
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
            worldActivityHandler.playerLeave(previous, System.currentTimeMillis());
        }

        if (islandUuid != null) {
            if (!worldActivityHandler.isTracked(islandUuid)) {
                levelUpdateScheduler.registerIsland(islandUuid);
            }
            worldActivityHandler.playerEnter(islandUuid);
            plugin.debug("WorldActivityListener", "Player " + player.getName() + " entered island: " + islandUuid);
        }
    }

    private UUID getIslandUuid(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
            return null;
        }

        Island island = islandSnapshot.getAt(location);
        return island == null ? null : island.getIslandUuid();
    }
}
