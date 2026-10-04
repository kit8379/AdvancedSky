package org.me.advancedsky.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;
import org.mockito.InOrder;

import java.util.UUID;

import static org.mockito.Mockito.*;

class IslandActivityListenerTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final LevelUpdateScheduler levels = mock(LevelUpdateScheduler.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final UUID firstIsland = UUID.randomUUID();
    private final UUID secondIsland = UUID.randomUUID();
    private final Location firstLocation = new Location(world, 2304, 100, 256);
    private final Location secondLocation = new Location(world, 4352, 100, 256);
    private final IslandActivityListener listener = new IslandActivityListener(plugin, config, cache, levels);

    IslandActivityListenerTest() {
        when(config.getIslandWorldName()).thenReturn("skyblock");
        when(world.getName()).thenReturn("skyblock");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(firstLocation);
        when(cache.getIslandUuidAt(firstLocation)).thenReturn(firstIsland);
        when(cache.getIslandUuidAt(secondLocation)).thenReturn(secondIsland);
    }

    @Test
    void joiningAndMovingWithinTheSameIslandCountsThePlayerOnce() {
        join();
        moveTo(firstLocation, true);

        verify(levels).playerEnter(firstIsland);
        verifyNoMoreInteractions(levels);
        verify(player, never()).getScheduler();
    }

    @Test
    void movementLeavesThePreviousIslandBeforeEnteringTheNext() {
        join();
        moveTo(secondLocation, true);
        moveTo(secondLocation, true);

        verifyIslandChange();
    }

    @Test
    void aTeleportUsesItsDestinationWithoutWaitingForMovement() {
        join();
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getTo()).thenReturn(secondLocation);

        listener.onPlayerTeleport(event);

        verifyIslandChange();
    }

    @Test
    void aRespawnUsesItsDestinationRatherThanThePlayersOldLocation() {
        join();
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getRespawnLocation()).thenReturn(secondLocation);

        listener.onPlayerRespawn(event);

        verifyIslandChange();
    }

    @Test
    void movementWithoutABlockChangeDoesNotLookUpTheIsland() {
        join();
        clearInvocations(cache, levels);

        moveTo(firstLocation, false);

        verifyNoInteractions(cache, levels);
    }

    @Test
    void leavingTheIslandWorldReleasesItsCountOnce() {
        join();
        World lobby = mock(World.class);
        when(lobby.getName()).thenReturn("lobby");
        Location lobbyLocation = new Location(lobby, 0, 100, 0);

        moveTo(lobbyLocation, true);
        moveTo(lobbyLocation, true);
        quit();

        verify(levels).playerEnter(firstIsland);
        verify(levels).playerLeave(firstIsland);
        verify(cache, never()).getIslandUuidAt(lobbyLocation);
        verifyNoMoreInteractions(levels);
    }

    @Test
    void movingIntoAFreeSlotReleasesThePreviousIsland() {
        join();
        Location gap = new Location(world, 0, 100, 0);

        moveTo(gap, true);
        quit();

        verify(levels).playerEnter(firstIsland);
        verify(levels).playerLeave(firstIsland);
        verifyNoMoreInteractions(levels);
    }

    @Test
    void quittingReleasesTheIslandOnceAndAllowsANewJoin() {
        join();
        quit();
        quit();
        when(player.getLocation()).thenReturn(secondLocation);
        join();
        quit();

        InOrder order = inOrder(levels);
        order.verify(levels).playerEnter(firstIsland);
        order.verify(levels).playerLeave(firstIsland);
        order.verify(levels).playerEnter(secondIsland);
        order.verify(levels).playerLeave(secondIsland);
        order.verifyNoMoreInteractions();
    }

    private void verifyIslandChange() {
        InOrder order = inOrder(levels);
        order.verify(levels).playerEnter(firstIsland);
        order.verify(levels).playerLeave(firstIsland);
        order.verify(levels).playerEnter(secondIsland);
        order.verifyNoMoreInteractions();
    }

    private void join() {
        listener.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
    }

    private void quit() {
        listener.onPlayerQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    private void moveTo(Location destination, boolean changedBlock) {
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getTo()).thenReturn(destination);
        when(event.hasChangedBlock()).thenReturn(changedBlock);
        listener.onPlayerMove(event);
    }
}
