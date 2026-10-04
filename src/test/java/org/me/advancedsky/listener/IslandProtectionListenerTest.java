package org.me.advancedsky.listener;

import com.google.common.base.Function;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import com.google.common.base.Functions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.util.Vector;
import org.bukkit.block.data.type.Dispenser;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void deletingIslandsRefuseEntryEvenToOwnersAndOperators() {
        island(false, Set.of());
        when(cache.isDeletingAt(inside)).thenReturn(true);
        assertTrue(move(owner, false, outside, inside));
        assertTrue(move(visitor, true, outside, inside));
    }

    @Test
    void movementWithinTheBoundaryDoesNotCheckEntryAgain() {
        island(true, Set.of(banned));

        assertFalse(move(banned, false, inside, inside.clone().add(1, 0, 0)));
        assertTrue(move(banned, false, inside, outside));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leavingTheBoundaryIsRefusedWithoutAClientBorder(boolean operator) {
        island(false, Set.of());

        assertTrue(move(visitor, operator, inside, outside));
        assertFalse(move(visitor, operator, inside, inside.clone().add(1, 0, 0)));
    }

    @Test
    void anExpandedBoundaryUsesTheCurrentSizeImmediately() {
        island(false, Set.of());
        assertTrue(move(member, false, inside, outside));

        when(config.getUpgradeLimit("island-size", 1)).thenReturn(100);

        assertFalse(move(member, false, inside, outside));
    }

    @Test
    void aPlayerAlreadyOutsideCanReturnButCannotMoveFartherOut() {
        island(false, Set.of());

        assertTrue(move(member, false, outside, outside.clone().add(-1, 0, 0)));
        assertFalse(move(member, false, outside, inside));
        assertFalse(move(member, false, outside, outside.clone().add(0, 0, 1)));
        Location east = center.clone().add(SIZE - SIZE / 2, 0, 0);
        assertTrue(move(member, false, east, east.clone().add(1, 0, 0)));
        assertFalse(move(member, false, east, east.clone().add(-1, 0, 0)));
        Location north = center.clone().add(0, 0, -SIZE / 2 - 1);
        assertTrue(move(member, false, north, north.clone().add(0, 0, -1)));
        assertFalse(move(member, false, north, north.clone().add(0, 0, 1)));
        Location south = center.clone().add(0, 0, SIZE - SIZE / 2);
        assertTrue(move(member, false, south, south.clone().add(0, 0, 1)));
        assertFalse(move(member, false, south, south.clone().add(0, 0, -1)));
    }

    @Test
    void anUnavailableIslandRefusesMovementUntilItsCurrentDataIsLoaded() {
        UUID islandUuid = UUID.randomUUID();
        when(cache.getIslandUuidAt(any(Location.class))).thenReturn(islandUuid);

        assertTrue(move(member, false, inside, outside));
        assertTrue(move(member, false, inside, inside.clone().add(1, 0, 0)));

        island(false, Set.of());

        assertFalse(move(member, false, inside, inside.clone().add(1, 0, 0)));
    }

    @Test
    void anUnavailableDestinationRefusesEntry() {
        when(cache.getIslandUuidAt(inside)).thenReturn(UUID.randomUUID());

        assertTrue(move(visitor, false, outside, inside));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRiderLeavingTheBoundaryIsPutBackWithTheVehicle(boolean operator) {
        island(false, Set.of());
        Vehicle mount = mock(Vehicle.class);
        Player rider = player(visitor, operator);
        when(rider.getVehicle()).thenReturn(mount);
        when(mount.getPassengers()).thenReturn(List.of(rider));
        PlayerMoveEvent event = new PlayerMoveEvent(rider, inside, outside);

        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        verify(mount).teleportAsync(inside, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Test
    void aNestedPassengerLeavingTheBoundaryIsPutBackWithTheRootVehicle() {
        island(false, Set.of());
        Vehicle minecart = mock(Vehicle.class);
        Entity pig = mock(Entity.class);
        Player rider = player(visitor, false);
        when(minecart.getPassengers()).thenReturn(List.of(pig));
        when(pig.getPassengers()).thenReturn(List.of(rider));

        listener.onVehicleMove(new VehicleMoveEvent(minecart, inside, outside));

        verify(minecart).teleportAsync(inside, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Test
    void ridersOntoALockedIslandArePutBackWithTheirVehicle() {
        island(true, Set.of());

        assertTrue(ride(visitor, outside, inside));
        assertFalse(ride(member, outside, inside));
        assertFalse(ride(visitor, inside, inside.clone().add(1, 0, 0)));
    }

    @Test
    void aDispenserFacingOutOfTheBoundaryDoesNothing() {
        island(false, Set.of());
        assertTrue(dispense(inside, outside));
        assertFalse(dispense(inside, inside.clone().add(1, 0, 0)));
    }

    @Test
    void aChannelingBoltStrikesOnlyWhereItsThrowerMayEdit() {
        island(false, Set.of());
        assertTrue(strike(player(visitor, false), LightningStrikeEvent.Cause.TRIDENT));
        assertFalse(strike(player(member, false), LightningStrikeEvent.Cause.TRIDENT));
        assertFalse(strike(null, LightningStrikeEvent.Cause.WEATHER));
    }

    /**
     * Whether a dispenser at the location facing the target was stopped.
     */
    private boolean dispense(Location at, Location target) {
        Block dispenser = mock(Block.class);
        Block front = mock(Block.class);
        Dispenser data = mock(Dispenser.class);
        when(data.getFacing()).thenReturn(BlockFace.WEST);
        when(dispenser.getBlockData()).thenReturn(data);
        when(dispenser.getLocation()).thenReturn(at);
        when(dispenser.getRelative(BlockFace.WEST)).thenReturn(front);
        when(front.getLocation()).thenReturn(target);
        BlockDispenseEvent event = new BlockDispenseEvent(dispenser, mock(ItemStack.class), new Vector());
        listener.onDispense(event);
        return event.isCancelled();
    }

    /**
     * Whether a bolt the thrower called on the island was stopped.
     */
    private boolean strike(Player thrower, LightningStrikeEvent.Cause cause) {
        LightningStrike bolt = mock(LightningStrike.class);
        when(bolt.getCausingEntity()).thenReturn(thrower);
        when(bolt.getLocation()).thenReturn(inside);
        LightningStrikeEvent event = new LightningStrikeEvent(world, bolt, cause);
        listener.onLightningStrike(event);
        return event.isCancelled();
    }

    @Test
    void aRiderOnAMobInAMinecartIsPutBackWithTheMinecart() {
        island(true, Set.of());
        Vehicle minecart = mock(Vehicle.class);
        Entity pig = mock(Entity.class);
        Player rider = player(visitor, false);
        when(minecart.getPassengers()).thenReturn(List.of(pig));
        when(pig.getPassengers()).thenReturn(List.of(rider));

        listener.onVehicleMove(new VehicleMoveEvent(minecart, outside, inside));

        verify(minecart).teleportAsync(outside, PlayerTeleportEvent.TeleportCause.PLUGIN);
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
    void aVisitorsWindChargeCannotPushIslandAnimals() {
        island(false, Set.of());

        assertTrue(push(visitor));
        assertFalse(push(member));
    }

    @Test
    void growthAndSpreadStayInsideTheBoundary() {
        island(false, Set.of());

        BlockGrowEvent fruitOutside = new BlockGrowEvent(block(outside), mock(BlockState.class));
        listener.onBlockGrow(fruitOutside);
        assertTrue(fruitOutside.isCancelled());

        BlockGrowEvent fruitInside = new BlockGrowEvent(block(inside), mock(BlockState.class));
        listener.onBlockGrow(fruitInside);
        assertFalse(fruitInside.isCancelled());

        BlockSpreadEvent vineOutside = new BlockSpreadEvent(block(outside), block(inside), mock(BlockState.class));
        listener.onBlockSpread(vineOutside);
        assertTrue(vineOutside.isCancelled());
    }

    @Test
    void aVisitorsChannelingStrikeCannotSetIslandFire() {
        island(false, Set.of());

        assertTrue(ignite(player(visitor, false), inside));
        assertFalse(ignite(player(member, false), inside));
        assertFalse(ignite(null, inside));
        assertTrue(ignite(null, outside));
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

    @Test
    void anEnderPearlLandingFollowsTheTeleportRule() {
        island(false, Set.of(banned));

        assertFalse(pearl(visitor, inside));
        assertTrue(pearl(visitor, outside));
        assertTrue(pearl(banned, inside));
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

    private static Block block(Location at) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(at);
        return block;
    }

    /**
     * Whether the fire a strike called down by the player, or a natural one when null, would
     * light was cancelled.
     */
    private boolean ignite(Player caller, Location at) {
        LightningStrike strike = mock(LightningStrike.class);
        when(strike.getCausingEntity()).thenReturn(caller);
        BlockIgniteEvent event = new BlockIgniteEvent(block(at), BlockIgniteEvent.IgniteCause.LIGHTNING, strike);
        listener.onBlockIgnite(event);
        return event.isCancelled();
    }

    /**
     * Whether the push of an island cow by the player's wind charge burst was cancelled.
     */
    private boolean push(UUID playerUuid) {
        Cow cow = mock(Cow.class);
        when(cow.getLocation()).thenReturn(inside);
        EntityPushedByEntityAttackEvent event = new EntityPushedByEntityAttackEvent(cow, EntityKnockbackEvent.Cause.EXPLOSION, player(playerUuid, false), new Vector(0, 0.5, 0.5));
        listener.onEntityPushed(event);
        return event.isCancelled();
    }

    /**
     * Whether the landing was refused and the pearl removed.
     */
    private boolean pearl(UUID playerUuid, Location at) {
        EnderPearl pearl = mock(EnderPearl.class);
        Player thrower = player(playerUuid, false);
        when(pearl.getShooter()).thenReturn(thrower);
        when(pearl.getLocation()).thenReturn(at);
        ProjectileHitEvent event = new ProjectileHitEvent(pearl, null, mock(Block.class), BlockFace.UP);
        listener.onEnderPearlLand(event);
        if (event.isCancelled()) {
            verify(pearl).remove();
        }
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
