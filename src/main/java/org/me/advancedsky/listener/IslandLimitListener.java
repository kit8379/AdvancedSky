package org.me.advancedsky.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.island.LimitHandler;
import org.me.advancedsky.model.Island;

import java.util.UUID;

/**
 * Enforces limits.yml on islands. Runs after protection has had its say, so a placement
 * outside the border or by a non-member is already cancelled here. Break and burn release a
 * block's count; anything else that removes blocks is caught up by the next level scan.
 * Capped blocks formed (a generator's roll) or put down by a dispenser are counted as well.
 */
public final class IslandLimitListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final LimitHandler limitHandler;
    private final IslandCache islandCache;

    public IslandLimitListener(AdvancedSky plugin, ConfigHandler config, LimitHandler limitHandler, IslandCache islandCache) {
        this.plugin = plugin;
        this.config = config;
        this.limitHandler = limitHandler;
        this.islandCache = islandCache;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();
        int limit = limitHandler.getBlockLimit(type);
        if (limit == LimitHandler.UNLIMITED || addsToExisting(event)) {
            return;
        }

        UUID islandUuid = getIslandUuid(block.getLocation());
        if (islandUuid == null) {
            return;
        }

        if (!limitHandler.isReady(islandUuid)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(config.getBlockLimitCalculatingMessage());
            plugin.debug("IslandLimitListener", "Denied block place before first scan: island=" + islandUuid + ", type=" + type + ", player=" + event.getPlayer().getUniqueId());
            return;
        }

        if (!limitHandler.hasRoom(islandUuid, type)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(config.getBlockLimitReachedMessage(type.name(), limit));
            plugin.debug("IslandLimitListener", "Denied block place due to limit: island=" + islandUuid + ", type=" + type + ", player=" + event.getPlayer().getUniqueId());
        }
    }

    /**
     * Counts a capped block once its placement stands: a handler after the check above may still
     * cancel it. Places on one island run one after another on its region thread, so none can
     * slip in between the check and the count.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }

        Block block = event.getBlockPlaced();
        Material type = block.getType();
        if (limitHandler.getBlockLimit(type) == LimitHandler.UNLIMITED || addsToExisting(event)) {
            return;
        }

        UUID islandUuid = getIslandUuid(block.getLocation());
        if (islandUuid != null) {
            limitHandler.increment(islandUuid, type, block.getX() >> 4, block.getZ() >> 4);
        }
    }

    /**
     * A second slab, another candle, pickle, egg or snow layer goes into the block already there.
     */
    private static boolean addsToExisting(BlockPlaceEvent event) {
        return event.getBlockReplacedState().getType() == event.getBlockPlaced().getType();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        release(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        release(event.getBlock());
    }

    private void release(Block block) {
        Material type = block.getType();
        if (limitHandler.getBlockLimit(type) == LimitHandler.UNLIMITED) {
            return;
        }

        UUID islandUuid = getIslandUuid(block.getLocation());
        if (islandUuid == null) {
            return;
        }

        // A bed counts in its foot's chunk, the half the scan counts, also when broken by its head.
        Block counted = block.getBlockData() instanceof Bed bed && bed.getPart() == Bed.Part.HEAD ? block.getRelative(bed.getFacing().getOppositeFace()) : block;
        limitHandler.decrement(islandUuid, type, counted.getX() >> 4, counted.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        Material type = event.getNewState().getType();
        if (limitHandler.getBlockLimit(type) == LimitHandler.UNLIMITED || block.getType() == type) {
            return;
        }

        UUID islandUuid = getIslandUuid(block.getLocation());
        if (islandUuid != null) {
            limitHandler.increment(islandUuid, type, block.getX() >> 4, block.getZ() >> 4);
        }
    }

    /**
     * A dispenser puts down a shulker box, pumpkin or skull without a BlockPlaceEvent: past the
     * cap it dispenses nothing.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Material type = event.getItem().getType();
        Block front = dispensedInto(event, type);
        if (front == null) {
            return;
        }

        UUID islandUuid = getIslandUuid(front.getLocation());
        if (islandUuid != null && !limitHandler.hasRoom(islandUuid, type)) {
            event.setCancelled(true);
            plugin.debug("IslandLimitListener", "Denied dispense due to limit: island=" + islandUuid + ", type=" + type);
        }
    }

    /**
     * Counts the block a dispense put down, a tick later: the dispense runs after its event, and
     * puts nothing down when the space in front is taken.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispensed(BlockDispenseEvent event) {
        Material type = event.getItem().getType();
        Block front = dispensedInto(event, type);
        if (front == null || front.getType() == type) {
            return;
        }

        UUID islandUuid = getIslandUuid(front.getLocation());
        if (islandUuid == null) {
            return;
        }

        Bukkit.getRegionScheduler().runDelayed(plugin, front.getLocation(), task -> {
            if (front.getType() == type) {
                limitHandler.increment(islandUuid, type, front.getX() >> 4, front.getZ() >> 4);
            }
        }, 1L);
    }

    /**
     * The block in front of a dispenser dispensing a capped block, or null.
     */
    private Block dispensedInto(BlockDispenseEvent event, Material type) {
        Block dispenser = event.getBlock();
        if (dispenser.getType() != Material.DISPENSER || limitHandler.getBlockLimit(type) == LimitHandler.UNLIMITED || !(dispenser.getBlockData() instanceof Directional directional)) {
            return null;
        }

        return dispenser.getRelative(directional.getFacing());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        denyIfFull(event, event.getLocation(), event.getEntityType());
    }

    /** Vehicles, armor stands and end crystals placed by a player. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        denyIfFull(event, event.getBlock().getLocation(), event.getEntityType());
    }

    /** Minecarts and boats a dispenser puts out fire neither of the events above. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        denyIfFull(event, event.getVehicle().getLocation(), event.getVehicle().getType());
    }

    /** Item frames and paintings. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        denyIfFull(event, event.getBlock().getLocation(), event.getEntity().getType());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTransform(EntityTransformEvent event) {
        Location location = event.getEntity().getLocation();
        for (Entity transformed : event.getTransformedEntities()) {
            if (denyIfFull(event, location, transformed.getType())) {
                return;
            }
        }
    }

    private boolean denyIfFull(Cancellable event, Location location, EntityType type) {
        if (limitHandler.getEntityLimit(type) == LimitHandler.UNLIMITED || !isIslandWorld(location.getWorld())) {
            return false;
        }

        // Outside any island (gap, free slot) or while its cache entry is unavailable: fail closed.
        Island island = islandCache.getAt(location);
        if (island != null && limitHandler.canSpawnEntity(island, location.getWorld(), type)) {
            return false;
        }

        event.setCancelled(true);
        plugin.debug("IslandLimitListener", "Denied entity due to limit: location=" + location + ", type=" + type);
        return true;
    }

    private UUID getIslandUuid(Location location) {
        if (!isIslandWorld(location.getWorld())) {
            return null;
        }

        return islandCache.getIslandUuidAt(location);
    }

    private boolean isIslandWorld(World world) {
        return world != null && world.getName().equals(config.getIslandWorldName());
    }
}
