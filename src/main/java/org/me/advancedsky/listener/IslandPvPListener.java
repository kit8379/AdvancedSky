package org.me.advancedsky.listener;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;

import java.util.UUID;

public class IslandPvPListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;

    // The handlers run on the region thread that owns the entities; the burst follows the
    // projectile or mace hit within the same call stack, so a tick-scoped mark per thread is enough.
    private final ThreadLocal<WindBurst> windBurst = new ThreadLocal<>();

    public IslandPvPListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
    }

    private UUID resolvePlayerUuid(Entity entity) {
        if (entity instanceof Player player) {
            return player.getUniqueId();
        }

        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }

        return null;
    }

    /**
     * The player behind the damage: the attacker, the shooter, the one who lit the TNT or hit the
     * end crystal. Lightning carries no causing entity in its damage source, so a Channeling
     * strike is traced through the strike itself.
     */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamageSource().getCausingEntity() instanceof Player player) {
            return player;
        }

        if (event.getDamager() instanceof LightningStrike strike && strike.getCausingEntity() instanceof Player player) {
            return player;
        }

        return null;
    }

    private boolean isPvpAllowed(Player victim) {
        Location location = victim.getLocation();
        World world = location.getWorld();
        if (world == null || !world.getName().equals(config.getIslandWorldName())) {
            return true;
        }

        Island island = islandCache.getAt(location);
        return island != null && island.isPvp();
    }

    private boolean isBlockedTarget(Player victim, UUID attackerUuid) {
        return !victim.getUniqueId().equals(attackerUuid) && !isPvpAllowed(victim);
    }

    private boolean isHarmful(Iterable<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }

        return false;
    }

    private boolean isHarmful(AreaEffectCloud cloud) {
        if (isHarmful(cloud.getCustomEffects())) {
            return true;
        }

        PotionType basePotionType = cloud.getBasePotionType();
        return basePotionType != null && isHarmful(basePotionType.getPotionEffects());
    }

    private void notifyAttacker(Entity damager) {
        Entity attacker = damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : damager;
        if (attacker instanceof Player player) {
            player.sendMessage(config.getIslandPvpDisabledMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        if (isPvpAllowed(victim)) {
            return;
        }

        event.setCancelled(true);
        attacker.sendMessage(config.getIslandPvpDisabledMessage());
        plugin.debug("IslandPvPListener", "Cancelled PvP from " + attacker.getUniqueId() + " against " + victim.getName() + " in island world: " + victim.getWorld().getName());
    }

    /**
     * The knockback event carries the causing player for wind bursts and player-lit TNT/crystals
     * alike, so they are indistinguishable there. A wind charge's hit marks the tick and its
     * shooter, and only explosion knockback matching that mark is treated as a wind burst — TNT
     * and crystal knockback stays fully vanilla.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWindChargeHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof AbstractWindCharge windCharge && windCharge.getShooter() instanceof Player shooter) {
            windBurst.set(new WindBurst(Bukkit.getCurrentTick(), shooter.getUniqueId()));
        }
    }

    /**
     * A mace hit marks its attacker the same way: Wind Burst on the mace bursts around them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && attacker.getInventory().getItemInMainHand().getType() == Material.MACE) {
            windBurst.set(new WindBurst(Bukkit.getCurrentTick(), attacker.getUniqueId()));
        }
    }

    /**
     * Knockback another player gives without damage: a wind burst, or the push a mace smash gives
     * everyone near its target.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        EntityKnockbackEvent.Cause cause = event.getCause();
        if (cause != EntityKnockbackEvent.Cause.EXPLOSION && cause != EntityKnockbackEvent.Cause.PUSH) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        if (!(event instanceof EntityPushedByEntityAttackEvent pushed)) {
            return;
        }

        UUID attackerUuid = resolvePlayerUuid(pushed.getPushedBy());
        if (attackerUuid == null || attackerUuid.equals(victim.getUniqueId())) {
            return;
        }

        if (cause == EntityKnockbackEvent.Cause.EXPLOSION) {
            WindBurst burst = windBurst.get();
            if (burst == null || burst.tick() != Bukkit.getCurrentTick() || !burst.playerUuid().equals(attackerUuid)) {
                return;
            }
        }

        if (isPvpAllowed(victim)) {
            return;
        }

        event.setCancelled(true);
        notifyAttacker(pushed.getPushedBy());
        plugin.debug("IslandPvPListener", "Cancelled " + cause + " knockback from " + attackerUuid + " against " + victim.getName() + " in island world: " + victim.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishingRodPull(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || !(event.getCaught() instanceof Player victim)) {
            return;
        }

        Player attacker = event.getPlayer();
        if (!isBlockedTarget(victim, attacker.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        attacker.sendMessage(config.getIslandPvpDisabledMessage());
        plugin.debug("IslandPvPListener", "Cancelled fishing rod pull from " + attacker.getName() + " against " + victim.getName() + " in island world: " + victim.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof Player attacker) || !isHarmful(potion.getEffects())) {
            return;
        }

        boolean blocked = false;
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim && isBlockedTarget(victim, attacker.getUniqueId())) {
                event.setIntensity(victim, 0.0);
                blocked = true;
            }
        }

        if (blocked) {
            attacker.sendMessage(config.getIslandPvpDisabledMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player attacker) || !isHarmful(cloud)) {
            return;
        }

        int affectedBefore = event.getAffectedEntities().size();
        event.getAffectedEntities().removeIf(entity ->
                entity instanceof Player victim && isBlockedTarget(victim, attacker.getUniqueId()));

        if (event.getAffectedEntities().size() < affectedBefore) {
            attacker.sendMessage(config.getIslandPvpDisabledMessage());
        }
    }

    private record WindBurst(int tick, UUID playerUuid) {
    }
}
