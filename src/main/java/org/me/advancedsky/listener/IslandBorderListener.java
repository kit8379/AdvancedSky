package org.me.advancedsky.listener;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NewSky gives each island world a world border the size of the island. Here every island shares
 * one world, so each player gets a border of their own around the island they stand on, and a
 * move that would take them out of that island's boundary is cancelled. A border follows the
 * island size, so a bought size upgrade shows on the next move.
 */
public class IslandBorderListener implements Listener {

    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final IslandGrid islandGrid;

    /**
     * The island slot and size of the border each player was last sent.
     */
    private final Map<UUID, Long> shownBorders = new ConcurrentHashMap<>();

    public IslandBorderListener(ConfigHandler config, IslandCache islandCache, IslandGrid islandGrid) {
        this.config = config;
        this.islandCache = islandCache;
        this.islandGrid = islandGrid;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        showBorder(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        shownBorders.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        showBorder(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(PlayerPostRespawnEvent event) {
        showBorder(event.getPlayer(), event.getRespawnedLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        // Leaving the boundary of the island they stand on is refused, as a world border would.
        Island island = getIsland(from);
        if (island != null && !player.isOp() && isInside(island, from) && !isInside(island, to)) {
            event.setCancelled(true);
            return;
        }

        showBorder(player, to);
    }

    /**
     * Sends the border of the island at the location, or clears the player's border when the
     * location is on no island. Runs on the player's thread, as per-player borders require.
     */
    private void showBorder(Player player, Location location) {
        Island island = getIsland(location);
        UUID playerUuid = player.getUniqueId();

        if (island == null) {
            if (shownBorders.remove(playerUuid) != null) {
                player.setWorldBorder(null);
            }
            return;
        }

        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        long key = ((long) island.getSlot() << 32) | size;
        Long shown = shownBorders.put(playerUuid, key);
        if (shown != null && shown == key) {
            return;
        }

        Location center = islandGrid.center(location.getWorld(), island.getSlot());
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(center.getBlockX(), center.getBlockZ());
        border.setSize(size);
        border.setWarningDistance(0);
        player.setWorldBorder(border);
    }

    private Island getIsland(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
            return null;
        }

        return islandCache.getAt(location);
    }

    private boolean isInside(Island island, Location location) {
        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        return location.getWorld() != null && location.getWorld().getName().equals(config.getIslandWorldName()) && islandGrid.isInsideIsland(island.getSlot(), size, location.getBlockX(), location.getBlockZ());
    }
}
