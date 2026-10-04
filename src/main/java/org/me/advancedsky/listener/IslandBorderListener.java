package org.me.advancedsky.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.util.EntityUtils;

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

    private static final long NO_BOUNDARY = -1L;

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

    /**
     * A respawn makes the client show its world's own border, and Folia fires no respawn event, so
     * the border is dropped at death and the first move or teleport after the respawn sends it again.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (shownBorders.remove(player.getUniqueId()) != null) {
            player.setWorldBorder(null);
        }
    }

    /**
     * Also after a nether portal trip, which is no teleport event here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        resendBorder(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        // A rider steering a vehicle moves it, and from and to are the vehicle's. Folia's own undo of
        // a cancelled move teleports the rider, which fails for a passenger, so the vehicle is put back.
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            if (putBack(vehicle, from, to)) {
                event.setCancelled(true);
                return;
            }
        } else {
            // Leaving the boundary of the island they stand on is refused, as a world border would.
            long boundary = boundaryAt(player, from);
            if (boundary != NO_BOUNDARY && isInside(boundary, from) && !isInside(boundary, to)) {
                event.setCancelled(true);
                return;
            }
        }

        showBorder(player, to);
    }

    /**
     * Boats and minecarts report their moves here, whoever steers them.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        putBack(event.getVehicle(), event.getFrom(), event.getTo());
    }

    /**
     * Puts the vehicle back where it came from, riders and all, when the move would carry a
     * player out of the boundary of the island it is on.
     */
    private boolean putBack(Entity vehicle, Location from, Location to) {
        // A vehicle that itself rides one moves with it, and the one at the bottom is put back.
        if ((from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) || vehicle.getVehicle() != null) {
            return false;
        }

        for (Player player : EntityUtils.riders(vehicle)) {
            long boundary = boundaryAt(player, from);
            if (boundary != NO_BOUNDARY && isInside(boundary, from) && !isInside(boundary, to)) {
                vehicle.setVelocity(new Vector());
                vehicle.teleportAsync(from, PlayerTeleportEvent.TeleportCause.PLUGIN);
                return true;
            }
        }
        return false;
    }

    /**
     * Sends the border of the island at the location, or clears the player's border when the
     * location is on no island. Runs on the player's thread, as per-player borders require.
     */
    private void showBorder(Player player, Location location) {
        Island island = getIsland(location);
        UUID playerUuid = player.getUniqueId();

        // While the island's cache entry loads again, the border it showed stays.
        if (island == null && isIslandWorld(location) && islandCache.getIslandUuidAt(location) != null) {
            return;
        }

        if (island == null) {
            if (shownBorders.remove(playerUuid) != null) {
                player.setWorldBorder(null);
            }
            return;
        }

        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        long key = ((long) island.getSlot() << 32) | size;
        Long shown = shownBorders.get(playerUuid);
        if (shown != null && shown == key) {
            return;
        }
        shownBorders.put(playerUuid, key);

        // The boundary runs from size/2 blocks before the centre block to size - size/2 - 1 after
        // it, so an odd size is centred on the middle of that block, not on its corner.
        Location center = islandGrid.center(location.getWorld(), island.getSlot());
        double offset = (size % 2) / 2.0;
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(center.getBlockX() + offset, center.getBlockZ() + offset);
        border.setSize(size);
        border.setWarningDistance(0);
        player.setWorldBorder(border);
    }

    /**
     * A world change makes the client show its world's own border, so the island's is sent again
     * even when it has not changed, and one sent before is cleared off an island.
     */
    private void resendBorder(Player player, Location location) {
        boolean shown = shownBorders.remove(player.getUniqueId()) != null;
        showBorder(player, location);
        if (shown && !shownBorders.containsKey(player.getUniqueId())) {
            player.setWorldBorder(null);
        }
    }

    private Island getIsland(Location location) {
        if (!isIslandWorld(location)) {
            return null;
        }

        return islandCache.getAt(location);
    }

    private boolean isIslandWorld(Location location) {
        return location != null && location.getWorld() != null && location.getWorld().getName().equals(config.getIslandWorldName());
    }

    /**
     * The boundary the player is held to at the location, as slot and size keyed like
     * {@link #shownBorders}: that of the island there or, while its cache entry loads again, the
     * one last sent to them for it. None in the gap, on a free slot or off the island world.
     */
    private long boundaryAt(Player player, Location location) {
        Island island = getIsland(location);
        if (island != null) {
            return ((long) island.getSlot() << 32) | config.getUpgradeLimit("island-size", island.getSizeLevel());
        }

        if (!isIslandWorld(location) || islandCache.getIslandUuidAt(location) == null) {
            return NO_BOUNDARY;
        }

        Long shown = shownBorders.get(player.getUniqueId());
        return shown != null && (int) (shown >>> 32) == islandGrid.slotAt(location.getX(), location.getZ()) ? shown : NO_BOUNDARY;
    }

    private boolean isInside(long boundary, Location location) {
        return isIslandWorld(location) && islandGrid.isInsideIsland((int) (boundary >>> 32), (int) boundary, location.getBlockX(), location.getBlockZ());
    }
}
