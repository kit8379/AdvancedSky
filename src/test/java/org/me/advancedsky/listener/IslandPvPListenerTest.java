package org.me.advancedsky.listener;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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

    private void island(boolean pvp) {
        Island island = new Island(UUID.randomUUID(), 1, false, pvp, UUID.randomUUID(), Set.of(), Set.of(), Set.of(), Map.of(), null, 1, 1);
        when(cache.getAt(any(Location.class))).thenReturn(island);
    }

    /**
     * Whether the damage the damager dealt the victim, caused by the given entity, was cancelled.
     */
    private boolean damage(Entity damager, Entity causing) {
        DamageSource source = mock(DamageSource.class);
        when(source.getCausingEntity()).thenReturn(causing);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 4.0);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        functions.put(EntityDamageEvent.DamageModifier.BASE, Functions.constant(-0.0));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, victim, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, source, modifiers, functions, false);
        listener.onEntityDamageByEntity(event);
        return event.isCancelled();
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0, 70, 0));
        when(player.getWorld()).thenAnswer(call -> world);
        return player;
    }
}
