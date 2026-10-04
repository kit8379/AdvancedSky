package org.me.advancedsky.listener;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.ExplosionResult;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.util.EntityUtils;

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

        if (islandCache.isDeletingAt(location)) {
            return false;
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
     * Bans apply before the lock's member and co-op exemptions.
     */
    private boolean canPlayerEnter(Player player, Island island) {
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

        if (entity instanceof LightningStrike strike && strike.getCausingEntity() instanceof Player player) {
            return player;
        }

        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        if (!canPlayerEdit(player, location)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlockPlaced().getLocation();

        if (!canPlayerEdit(player, location)) {
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
        Location location = clicked.getLocation();

        if (!canPlayerEdit(player, location)) {
            event.setCancelled(true);
            // Pressure plates and tripwires re-fire every tick while stood on, so
            // physical interactions deny silently.
            if (event.getAction() != Action.PHYSICAL) {
                deny(player);
            }
        }
    }

    /**
     * Water spawn eggs have no clicked block and arrive cancelled. Check their fluid target separately.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnEggUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) {
            return;
        }

        Player player = event.getPlayer();
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        RayTraceResult hit = player.rayTraceBlocks(reach == null ? 4.5 : reach.getValue(), FluidCollisionMode.SOURCE_ONLY);
        if (hit == null || hit.getHitBlock() == null) {
            return;
        }

        if (!canPlayerEdit(player, hit.getHitBlock().getLocation())) {
            event.setUseItemInHand(Event.Result.DENY);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketUse(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        if (!canPlayerEdit(player, location)) {
            event.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        if (!canPlayerEdit(player, location)) {
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

    /**
     * Growth and spread events have separate handler lists from block formation.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (!isAllowedByBoundary(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (!isAllowedByBoundary(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Player-started fire uses edit permissions; other fire uses the island boundary.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            player = resolvePlayer(event.getIgnitingEntity());
        }

        Location location = event.getBlock().getLocation();
        boolean allowed = player != null ? canPlayerEdit(player, location) : isAllowedByBoundary(location);
        if (!allowed) {
            event.setCancelled(true);
        }
    }

    /**
     * Keep the dispenser or dropper's target inside the island boundary.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Directional directional)) {
            return;
        }

        Location target = block.getRelative(directional.getFacing()).getLocation();
        if (!isAllowedByBoundary(target)) {
            event.setCancelled(true);
        }
    }

    /**
     * Check Channeling before the strike burns, converts or damages anything.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLightningStrike(LightningStrikeEvent event) {
        if (event.getCause() != LightningStrikeEvent.Cause.TRIDENT) {
            return;
        }

        LightningStrike strike = event.getLightning();
        if (!(strike.getCausingEntity() instanceof Player player)) {
            return;
        }

        if (!canPlayerEdit(player, strike.getLocation())) {
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
        Player attacker = resolveAttacker(event);

        if (attacker == null) {
            return;
        }

        if (event.getEntity() instanceof Player || event.getEntity() instanceof Enemy) {
            return;
        }

        if (!canPlayerEdit(attacker, event.getEntity().getLocation())) {
            event.setCancelled(true);
            deny(attacker);
        }
    }

    /**
     * A push follows the damage rule, also one without damage such as a wind charge's burst.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPushed(EntityKnockbackEvent event) {
        if (!(event instanceof EntityPushedByEntityAttackEvent pushed)) {
            return;
        }
        if (event.getEntity() instanceof Player || event.getEntity() instanceof Enemy) {
            return;
        }

        Player player = resolvePlayer(pushed.getPushedBy());
        if (player != null && !canPlayerEdit(player, event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * The player behind the damage, also for primed TNT, a hit end crystal and a Channeling
     * strike, which carries no causing entity in its damage source.
     */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamageSource().getCausingEntity() instanceof Player player) {
            return player;
        }

        return resolvePlayer(event.getDamager());
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
     * PlayerPortalEvent has its own handler list; nether arrivals use the teleport access rule.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            checkArrival(event);
        }
    }

    /**
     * Player-created portals use edit permissions; other portals use the island boundary.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        Player player = resolvePlayer(event.getEntity());
        for (BlockState state : event.getBlocks()) {
            Location location = state.getLocation();
            boolean allowed = player != null ? canPlayerEdit(player, location) : isAllowedByBoundary(location);
            if (!allowed) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Folia does not fire a teleport event for pearls, so check access when they land.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlLand(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }

        if (isArrivalDenied(player, pearl.getLocation())) {
            event.setCancelled(true);
            pearl.remove();
        }
    }

    private void checkArrival(PlayerTeleportEvent event) {
        if (isArrivalDenied(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
        }
    }

    /**
     * Reject arrivals outside island boundaries or denied by bans and locks, and explain why.
     */
    private boolean isArrivalDenied(Player player, Location destination) {
        if (!isIslandWorld(destination)) {
            return false;
        }

        Island island = islandCache.getAt(destination);
        if (!isInsideIslandBoundary(island, destination)) {
            deny(player);
            return true;
        }

        if (player.isOp() || canPlayerEnter(player, island)) {
            return false;
        }

        boolean banned = island.getBans().contains(player.getUniqueId());
        player.sendMessage(banned ? config.getPlayerBannedMessage() : config.getIslandLockedMessage());
        return true;
    }

    /**
     * Check walking and flying silently because refused movement repeats every tick.
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
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            if (restoreVehiclePosition(vehicle, event.getFrom(), to)) {
                event.setCancelled(true);
            }
            return;
        }

        if (isMoveDenied(player, event.getFrom(), to)) {
            event.setCancelled(true);
        }
    }

    private boolean isMoveDenied(Player player, Location from, Location to) {
        if (islandCache.isDeletingAt(to)) {
            return true;
        }

        boolean fromIslandWorld = isIslandWorld(from);

        Island source = fromIslandWorld ? islandCache.getAt(from) : null;
        if (source == null && fromIslandWorld && islandCache.getIslandUuidAt(from) != null) {
            return true;
        }

        if (movesBeyondBoundary(source, from, to)) {
            return true;
        }

        Island target = islandCache.getAt(to);
        if (target == null) {
            return islandCache.getIslandUuidAt(to) != null;
        }

        if (player.isOp() || canPlayerEnter(player, target)) {
            return false;
        }

        return isInsideIslandBoundary(target, to) && !isInsideIslandBoundary(target, from);
    }

    private boolean movesBeyondBoundary(Island island, Location from, Location to) {
        if (island == null) {
            return false;
        }

        int size = config.getUpgradeLimit("island-size", island.getSizeLevel());
        Location center = islandGrid.center(from.getWorld(), island.getSlot());
        int minOffset = IslandGrid.boundaryMinOffset(size);
        int minX = center.getBlockX() + minOffset;
        int minZ = center.getBlockZ() + minOffset;
        int maxX = minX + size - 1;
        int maxZ = minZ + size - 1;
        return (to.getBlockX() < minX && to.getBlockX() < from.getBlockX()) || (to.getBlockX() > maxX && to.getBlockX() > from.getBlockX()) || (to.getBlockZ() < minZ && to.getBlockZ() < from.getBlockZ()) || (to.getBlockZ() > maxZ && to.getBlockZ() > from.getBlockZ());
    }

    /**
     * Boats and minecarts report their moves here, whoever steers them.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        restoreVehiclePosition(event.getVehicle(), event.getFrom(), event.getTo());
    }

    /**
     * Puts the vehicle and all riders back when any player's movement is refused.
     */
    private boolean restoreVehiclePosition(Entity vehicle, Location from, Location to) {
        boolean changedPosition = from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ();
        if (!changedPosition || !isIslandWorld(to) || vehicle.getVehicle() != null) {
            return false;
        }

        for (Player player : EntityUtils.riders(vehicle)) {
            if (isMoveDenied(player, from, to)) {
                vehicle.setVelocity(new Vector());
                vehicle.teleportAsync(from, PlayerTeleportEvent.TeleportCause.PLUGIN);
                return true;
            }
        }
        return false;
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
            if (event.getRightClicked() instanceof ArmorStand) {
                deny(player);
            }
        }
    }
}
