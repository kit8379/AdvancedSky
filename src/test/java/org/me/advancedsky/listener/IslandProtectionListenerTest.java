package org.me.advancedsky.listener;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Cow;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class IslandProtectionListenerTest {
    private static final int SLOT = 2;
    private static final int SIZE = 75;
    private final IslandGrid grid = new IslandGrid(64);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID banned = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final Location center = grid.center(world, SLOT);
    // Just outside the west edge of the boundary, and just inside it.
    private final Location outside = center.clone().add(-SIZE / 2 - 1, 0, 0);
    private final Location inside = center.clone().add(-SIZE / 2, 0, 0);
    private final IslandProtectionListener listener = new IslandProtectionListener(config, cache, grid);

    IslandProtectionListenerTest() {
        when(world.getName()).thenReturn("islands");
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(SIZE);
    }

    @Test
    void stepsOntoALockedIslandFromOutsideAreRefusedToOutsiders() {
        island(true, Set.of());

        assertTrue(move(visitor, false, outside, inside));
        assertFalse(move(member, false, outside, inside));
        assertFalse(move(owner, false, outside, inside));
        assertFalse(move(visitor, true, outside, inside));
    }

    @Test
    void bannedPlayersCannotStepOntoAnUnlockedIsland() {
        island(false, Set.of(banned));

        assertTrue(move(banned, false, outside, inside));
        assertFalse(move(visitor, false, outside, inside));
    }

    @Test
    void movingWithinOrOffTheBoundaryIsNotAnEntry() {
        island(true, Set.of(banned));

        assertFalse(move(banned, false, inside, inside.clone().add(1, 0, 0)));
        assertFalse(move(banned, false, inside, outside));
    }

    @Test
    void ridersOntoALockedIslandArePutBackWithTheirVehicle() {
        island(true, Set.of());

        assertTrue(ride(visitor, outside, inside));
        assertFalse(ride(member, outside, inside));
        assertFalse(ride(visitor, inside, inside.clone().add(1, 0, 0)));
    }

    @Test
    void aMountSteeredInWithABannedPassengerIsPutBack() {
        island(false, Set.of(banned));
        Vehicle mount = mock(Vehicle.class);
        Player driver = player(member, false);
        Player passenger = player(banned, false);
        when(driver.getVehicle()).thenReturn(mount);
        when(mount.getPassengers()).thenReturn(List.of(driver, passenger));

        PlayerMoveEvent event = new PlayerMoveEvent(driver, outside, inside);
        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        assertTrue(mockingDetails(mount).getInvocations().stream().anyMatch(invocation -> invocation.getMethod().getName().equals("teleportAsync")));
    }

    @Test
    void aVisitorsChannelingStrikeCannotHurtIslandAnimals() {
        island(false, Set.of());
        Cow cow = mock(Cow.class);
        when(cow.getLocation()).thenReturn(inside);
        LightningStrike strike = mock(LightningStrike.class);
        Player caller = player(visitor, false);
        when(strike.getCausingEntity()).thenReturn(caller);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 5.0);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        functions.put(EntityDamageEvent.DamageModifier.BASE, Functions.constant(-0.0));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(strike, cow, EntityDamageEvent.DamageCause.LIGHTNING, mock(DamageSource.class), modifiers, functions, false);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void netherPortalArrivalsFollowTheTeleportRule() {
        island(false, Set.of(banned));
        Location nether = new Location(mock(World.class), 0, 70, 0);

        assertFalse(portal(owner, nether, inside));
        assertTrue(portal(banned, nether, inside));
        assertTrue(portal(owner, nether, outside));
    }

    @Test
    void theArrivalPortalIsOnlyBuiltWhereTheTravellerMayEdit() {
        island(false, Set.of());

        assertFalse(portalCreate(owner, inside));
        assertTrue(portalCreate(visitor, inside));
        assertTrue(portalCreate(owner, outside));
    }

    private void island(boolean locked, Set<UUID> bans) {
        Island island = new Island(UUID.randomUUID(), SLOT, locked, false, owner, Set.of(member), Set.of(), bans, Map.of(), null, 1, 1);
        when(cache.getAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return grid.slotAt(location.getX(), location.getZ()) == SLOT ? island : null;
        });
    }

    private boolean portal(UUID playerUuid, Location from, Location to) {
        PlayerPortalEvent event = new PlayerPortalEvent(player(playerUuid, false), from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        listener.onPortal(event);
        return event.isCancelled();
    }

    private boolean portalCreate(UUID playerUuid, Location at) {
        BlockState block = mock(BlockState.class);
        when(block.getLocation()).thenReturn(at);
        PortalCreateEvent event = new PortalCreateEvent(List.of(block), world, player(playerUuid, false), PortalCreateEvent.CreateReason.NETHER_PAIR);
        listener.onPortalCreate(event);
        return event.isCancelled();
    }

    private static Player player(UUID playerUuid, boolean op) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOp()).thenReturn(op);
        return player;
    }

    /**
     * Whether the vehicle carrying the player was put back.
     */
    private boolean ride(UUID playerUuid, Location from, Location to) {
        Vehicle vehicle = mock(Vehicle.class);
        Player rider = player(playerUuid, false);
        when(vehicle.getPassengers()).thenReturn(List.of(rider));
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));
        return mockingDetails(vehicle).getInvocations().stream().anyMatch(invocation -> invocation.getMethod().getName().equals("teleportAsync"));
    }

    /**
     * Whether the move was cancelled.
     */
    private boolean move(UUID playerUuid, boolean op, Location from, Location to) {
        PlayerMoveEvent event = new PlayerMoveEvent(player(playerUuid, op), from, to);
        listener.onPlayerMove(event);
        return event.isCancelled();
    }
}
