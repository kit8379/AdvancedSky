package org.me.advancedsky.listener;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.World;
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

    public IslandPvPListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }

        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
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
     * Protect against player-attributed explosion knockback and mace smash pushes. The current
     * API provides no attacker for a mace's Wind Burst, so that source-less knockback stays vanilla.
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
        Player attacker = resolvePlayer(pushed.getPushedBy());
        if (attacker == null || !isBlockedTarget(victim, attacker.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        attacker.sendMessage(config.getIslandPvpDisabledMessage());
        plugin.debug("IslandPvPListener", "Cancelled " + cause + " knockback from " + attacker.getUniqueId() + " against " + victim.getName() + " in island world: " + victim.getWorld().getName());
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
}
