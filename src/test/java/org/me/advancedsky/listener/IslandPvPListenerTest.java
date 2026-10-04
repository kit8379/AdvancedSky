package org.me.advancedsky.listener;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandPvPListenerTest {
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);
    private final Player victim = player();
    private final Player attacker = player();
    private final IslandPvPListener listener = new IslandPvPListener(mock(AdvancedSky.class), config, cache);

    IslandPvPListenerTest() {
        when(world.getName()).thenReturn("islands");
        when(config.getIslandWorldName()).thenReturn("islands");
    }

    @Test
    void explosionsAPlayerSetOffAreHeldToThePvpSetting() {
        island(false);
        assertTrue(damage(mock(TNTPrimed.class), attacker));
        assertTrue(damage(mock(EnderCrystal.class), attacker));
        assertFalse(damage(mock(TNTPrimed.class), null));
        assertFalse(damage(mock(TNTPrimed.class), victim));

        island(true);
        assertFalse(damage(mock(TNTPrimed.class), attacker));
    }

    @Test
    void aChannelingStrikeIsTracedToItsPlayer() {
        island(false);
        LightningStrike strike = mock(LightningStrike.class);
        when(strike.getCausingEntity()).thenReturn(attacker);

        assertTrue(damage(strike, null));
        assertFalse(damage(mock(LightningStrike.class), null));
    }

    @Test
    void theMaceSmashPushOnPlayersNearItsTargetIsHeldToThePvpSetting() {
        island(false);
        assertTrue(knockback(EntityKnockbackEvent.Cause.PUSH, attacker));
        assertFalse(knockback(EntityKnockbackEvent.Cause.PUSH, victim));

        island(true);
        assertFalse(knockback(EntityKnockbackEvent.Cause.PUSH, attacker));
    }

    @Test
    void playerAttributedExplosionKnockbackIsHeldToThePvpSetting() {
        island(false);
        // Paper identifies the causing player for wind charges and player-lit TNT/crystals.
        assertTrue(knockback(EntityKnockbackEvent.Cause.EXPLOSION, attacker));
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, victim));

        island(true);
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, attacker));
    }

    @Test
    void aPlayerProjectileIsTracedToItsShooterWithoutAHitMarker() {
        island(false);
        WindCharge charge = mock(WindCharge.class);
        when(charge.getShooter()).thenReturn(attacker);

        assertTrue(knockback(EntityKnockbackEvent.Cause.EXPLOSION, charge));
        assertTrue(knockback(EntityKnockbackEvent.Cause.PUSH, charge));

        when(charge.getShooter()).thenReturn(victim);
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, charge));

        when(charge.getShooter()).thenReturn(null);
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, charge));

        island(true);
        when(charge.getShooter()).thenReturn(attacker);
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, charge));
    }

    @Test
    void aPriorPlayerExplosionCannotSupplyAnAttackerForLaterUnownedKnockback() {
        island(false);
        assertTrue(knockback(EntityKnockbackEvent.Cause.EXPLOSION, attacker));

        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, null));
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, mock(TNTPrimed.class)));
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, mock(WindCharge.class)));
        assertFalse(knockback(EntityKnockbackEvent.Cause.PUSH, mock(Entity.class)));
    }

    @Test
    void aDamageEventCannotSupplyAnAttackerForSourceLessWindBurst() {
        island(false);
        listener.onEntityDamageByEntity(damageEvent(attacker, mock(Entity.class)));

        // Wind Burst carries no attacker in the current API, including the wielder's own lift.
        assertFalse(knockback(EntityKnockbackEvent.Cause.EXPLOSION, null));
        assertFalse(knockback(attacker, EntityKnockbackEvent.Cause.EXPLOSION, null));
        verify(attacker, never()).getInventory();
    }

    /**
     * Whether the knockback the pusher gave the victim was cancelled.
     */
    private boolean knockback(EntityKnockbackEvent.Cause cause, Entity pushedBy) {
        return knockback(victim, cause, pushedBy);
    }

    /**
     * Whether the knockback the pusher, or no one when null, gave the player was cancelled.
     */
    private boolean knockback(Player pushed, EntityKnockbackEvent.Cause cause, Entity pushedBy) {
        EntityKnockbackEvent event = pushedBy == null ? new EntityKnockbackEvent(pushed, cause, new Vector(1, 0, 0)) : new EntityPushedByEntityAttackEvent(pushed, cause, pushedBy, new Vector(1, 0, 0));
        listener.onKnockback(event);
        return event.isCancelled();
    }

    private void island(boolean pvp) {
        Island island = new Island(UUID.randomUUID(), 1, false, pvp, UUID.randomUUID(), Set.of(), Set.of(), Set.of(), Map.of(), null, 1, 1);
        when(cache.getAt(any(Location.class))).thenReturn(island);
    }

    /**
     * Whether the damage the damager dealt the victim, caused by the given entity, was cancelled.
     */
    private boolean damage(Entity damager, Entity causing) {
        EntityDamageByEntityEvent event = damageEvent(damager, victim);
        when(event.getDamageSource().getCausingEntity()).thenReturn(causing);
        listener.onEntityDamageByEntity(event);
        return event.isCancelled();
    }

    private static EntityDamageByEntityEvent damageEvent(Entity damager, Entity damaged) {
        DamageSource source = mock(DamageSource.class);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 4.0);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        functions.put(EntityDamageEvent.DamageModifier.BASE, Functions.constant(-0.0));
        return new EntityDamageByEntityEvent(damager, damaged, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, source, modifiers, functions, false);
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0, 70, 0));
        when(player.getWorld()).thenAnswer(call -> world);
        return player;
    }
}
