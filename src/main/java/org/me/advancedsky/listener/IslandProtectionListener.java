package org.me.advancedsky.listener;

import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
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
import org.bukkit.event.world.StructureGrowEvent;
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

        if (player.isOp()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        if (island.getBans().contains(playerUuid)) {
            event.setCancelled(true);
            player.sendMessage(config.getPlayerBannedMessage());
            return;
        }

        boolean allowed = !island.isLock() || island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid) || island.getCoops().contains(playerUuid);

        if (!allowed) {
            event.setCancelled(true);
            player.sendMessage(config.getIslandLockedMessage());
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
