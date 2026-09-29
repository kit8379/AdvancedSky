package org.me.advancedsky.listener;

import org.bukkit.ExplosionResult;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;

import java.util.UUID;

public class IslandProtectionListener implements Listener {

    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final IslandGrid islandGrid;

    public IslandProtectionListener(ConfigHandler config, IslandCache islandCache, IslandGrid islandGrid) {
        this.config = config;
        this.islandCache = islandCache;
        this.islandGrid = islandGrid;
    }

    /**
     * Every island shares the island world; the gap between islands and free slots in it belong
     * to no one and are protected like the outside of an island boundary.
     */
    private boolean isIslandWorld(Location location) {
        if (location == null) {
            return false;
        }

        World world = location.getWorld();
        return world != null && world.getName().equals(config.getIslandWorldName());
    }

    private boolean isInsideIslandBoundary(Island island, Location location) {
        if (island == null || location == null) {
            return false;
        }

        int islandSize = config.getUpgradeLimit("island-size", island.getSizeLevel());
        return islandGrid.isInsideIsland(island.getSlot(), islandSize, location.getBlockX(), location.getBlockZ());
    }

    private boolean canPlayerEdit(Player player, Location location) {
        if (!isIslandWorld(location)) {
            return true;
        }

        if (player.isOp()) {
            return true;
        }

        Island island = islandCache.getAt(location);
        if (island == null) {
            return false;
        }

        if (!isInsideIslandBoundary(island, location)) {
            return false;
        }

        UUID playerUuid = player.getUniqueId();
        return island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid) || island.getCoops().contains(playerUuid);
    }

    private boolean isAllowedByBoundary(Location location) {
        if (!isIslandWorld(location)) {
            return true;
        }

        Island island = islandCache.getAt(location);
        if (island == null) {
            return false;
        }

        return isInsideIslandBoundary(island, location);
    }

    private void deny(Player player) {
        player.sendMessage(config.getCannotEditIslandMessage());
    }

    /**
     * Whether the player may come onto the island: not banned from it, and it is unlocked or
     * they belong to it.
     */
    private boolean mayEnter(Player player, Island island) {
        UUID playerUuid = player.getUniqueId();
        if (island.getBans().contains(playerUuid)) {
            return false;
        }

        return !island.isLock() || island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid) || island.getCoops().contains(playerUuid);
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }

        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }

        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();

        if (!canPlayerEdit(player, loc)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlockPlaced().getLocation();

        if (!canPlayerEdit(player, loc)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Player player = event.getPlayer();
        Location loc = clicked.getLocation();

        if (!canPlayerEdit(player, loc)) {
            event.setCancelled(true);
            // Pressure plates and tripwires re-fire every tick while stood on, so
            // physical interactions deny silently.
            if (event.getAction() != Action.PHYSICAL) {
                deny(player);
            }
        }
    }

    /**
     * A spawn egg used on water names no clicked block, and an interaction without one arrives
     * already cancelled, so the handler above never sees it: the creature appears on the fluid the
     * player looks at, which is held to the edit rule like a clicked block.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnEggUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.useItemInHand() == Event.Result.DENY || item == null || !item.getType().name().endsWith("_SPAWN_EGG")) {
            return;
        }

        Player player = event.getPlayer();
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        RayTraceResult hit = player.rayTraceBlocks(reach == null ? 4.5 : reach.getValue(), FluidCollisionMode.SOURCE_ONLY);
        if (hit != null && hit.getHitBlock() != null && !canPlayerEdit(player, hit.getHitBlock().getLocation())) {
            event.setUseItemInHand(Event.Result.DENY);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketUse(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();

        if (!canPlayerEdit(player, loc)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();

        if (!canPlayerEdit(player, loc)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidSpread(BlockFromToEvent event) {
        if (!isAllowedByBoundary(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        // Frost walker arrives as EntityBlockFormEvent with the player attached;
        // silent because each step forms several blocks.
        if (event instanceof EntityBlockFormEvent formed) {
            Player player = resolvePlayer(formed.getEntity());
            if (player != null) {
                if (!canPlayerEdit(player, event.getBlock().getLocation())) {
                    event.setCancelled(true);
                }
                return;
            }
        }

        if (!isAllowedByBoundary(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // The head itself extends into the space in front even when no blocks move.
        if (!isAllowedByBoundary(event.getBlock().getRelative(event.getDirection()).getLocation())) {
            event.setCancelled(true);
            return;
        }

        for (Block block : event.getBlocks()) {
            if (!isAllowedByBoundary(block.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (!isAllowedByBoundary(block.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // Player-attributed changes (projectiles shattering decorated pots / chorus
        // flowers) are permission-gated; mob and physics changes stay boundary-only.
        Player player = resolvePlayer(event.getEntity());
        if (player != null) {
            if (!canPlayerEdit(player, event.getBlock().getLocation())) {
                event.setCancelled(true);
                deny(player);
            }
            return;
        }

        if (!isAllowedByBoundary(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        // Player-attributed entities (arrows on buttons, plates, tripwires) are
        // permission-gated; silent because plates re-fire every tick while held down.
        Player player = resolvePlayer(event.getEntity());
        if (player == null) {
            return;
        }

        if (!canPlayerEdit(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        event.getBlocks().removeIf(state -> !isAllowedByBoundary(state.getLocation()));
    }

    @SuppressWarnings("UnstableApiUsage")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWindChargeTrigger(EntityExplodeEvent event) {
        if (event.getExplosionResult() != ExplosionResult.TRIGGER_BLOCK) {
            return;
        }

        Player player = resolvePlayer(event.getEntity());
        if (player == null) {
            return;
        }

        boolean removed = event.blockList().removeIf(block -> !canPlayerEdit(player, block.getLocation()));
        if (removed) {
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        if (!canPlayerEdit(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player damager = resolvePlayer(event.getDamager());

        if (damager == null) {
            return;
        }

        if (event.getEntity() instanceof Player || event.getEntity() instanceof Enemy) {
            return;
        }

        if (!canPlayerEdit(damager, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(damager);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = resolvePlayer(event.getRemover());
        if (player == null) {
            return;
        }

        if (!canPlayerEdit(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player player = resolvePlayer(event.getAttacker());
        if (player == null) {
            return;
        }

        if (!canPlayerEdit(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenInventory(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!canPlayerEdit(player, event.getInventory().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFishingRodPull(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || event.getCaught() == null) {
            return;
        }

        Player player = event.getPlayer();

        if (!canPlayerEdit(player, event.getCaught().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        checkArrival(event);
    }

    /**
     * A nether portal trip is not seen by {@link #onTeleport}: PlayerPortalEvent has a handler
     * list of its own. Arriving in the island world through one follows the teleport rule, so
     * walking the scaled-down nether to another island's coordinates leads nowhere it bars.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            checkArrival(event);
        }
    }

    /**
     * The portal a nether trip builds at its far end is built like any other blocks: only where
     * the traveller may edit, or inside a boundary when no player builds it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        Player player = resolvePlayer(event.getEntity());
        for (BlockState state : event.getBlocks()) {
            Location location = state.getLocation();
            if (player != null ? !canPlayerEdit(player, location) : !isAllowedByBoundary(location)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void checkArrival(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();

        if (!isIslandWorld(to)) {
            return;
        }

        Island island = islandCache.getAt(to);
        if (!isInsideIslandBoundary(island, to)) {
            event.setCancelled(true);
            deny(player);
            return;
        }

        if (player.isOp() || mayEnter(player, island)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(island.getBans().contains(player.getUniqueId()) ? config.getPlayerBannedMessage() : config.getIslandLockedMessage());
    }

    /**
     * Walking or flying onto an island from outside its boundary follows the same rule as
     * teleporting there. Silent, because a refused step is tried again every tick.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }

        Location to = event.getTo();
        if (!isIslandWorld(to)) {
            return;
        }

        Player player = event.getPlayer();
        Island island = islandCache.getAt(to);
        if (island == null || player.isOp() || mayEnter(player, island)) {
            return;
        }

        if (isInsideIslandBoundary(island, to) && !isInsideIslandBoundary(island, event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /**
     * Riding onto an island follows the walking rule: a rider fires no PlayerMoveEvent, so the
     * vehicle bringing in a player who may not enter is put back where it came from.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if ((from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) || !isIslandWorld(to)) {
            return;
        }

        Island island = islandCache.getAt(to);
        if (!isInsideIslandBoundary(island, to) || isInsideIslandBoundary(island, from)) {
            return;
        }

        Vehicle vehicle = event.getVehicle();
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player && !player.isOp() && !mayEnter(player, island)) {
                vehicle.setVelocity(new Vector());
                vehicle.teleportAsync(from, PlayerTeleportEvent.TeleportCause.PLUGIN);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();

        if (!canPlayerEdit(player, event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();

        if (!canPlayerEdit(player, event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            // The client sends INTERACT_AT alongside INTERACT for most entities, so the
            // INTERACT handler owns the message; armor stands only send INTERACT_AT.
            if (event.getRightClicked() instanceof ArmorStand) {
                deny(player);
            }
        }
    }
}
