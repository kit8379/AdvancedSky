package org.me.advancedsky.listener;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class IslandActivityListenerTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final LevelUpdateScheduler levels = mock(LevelUpdateScheduler.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final EntityScheduler scheduler = mock(EntityScheduler.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final UUID firstIsland = UUID.randomUUID();
    private final UUID secondIsland = UUID.randomUUID();
    private final Location firstLocation = new Location(world, 2304, 100, 256);
    private final Location secondLocation = new Location(world, 4352, 100, 256);
    private final List<Consumer<ScheduledTask>> samples = new ArrayList<>();
    private final List<Runnable> retired = new ArrayList<>();
    private final List<ScheduledTask> tasks = new ArrayList<>();
    private final IslandActivityListener listener = new IslandActivityListener(plugin, config, cache, levels);

    IslandActivityListenerTest() {
        when(config.getIslandWorldName()).thenReturn("skyblock");
        when(world.getName()).thenReturn("skyblock");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(firstLocation);
        when(player.getScheduler()).thenReturn(scheduler);
        when(cache.getIslandUuidAt(firstLocation)).thenReturn(firstIsland);
        when(cache.getIslandUuidAt(secondLocation)).thenReturn(secondIsland);
        when(scheduler.runAtFixedRate(eq(plugin), any(), any(), eq(1L), eq(20L))).thenAnswer(call -> {
            samples.add(call.getArgument(1));
            retired.add(call.getArgument(2));
            ScheduledTask task = mock(ScheduledTask.class);
            tasks.add(task);
            return task;
        });
    }

    @Test
    void joinSamplesOnThePlayerThreadAndAnUnchangedLocationIsCountedOnce() {
        join();
        verify(player, never()).getLocation();
        verifyNoInteractions(levels);

        sample(0);
        sample(0);

        verify(levels).playerEnter(firstIsland);
        verifyNoMoreInteractions(levels);
        assertEquals(1, tasks.size());
    }

    @Test
    void aStationaryTeleportMovesTheOccupancyWithoutAMovementEvent() {
        join();
        sample(0);

        when(player.getLocation()).thenReturn(secondLocation);
        sample(0);
        sample(0);

        InOrder order = inOrder(levels);
        order.verify(levels).playerEnter(firstIsland);
        order.verify(levels).playerLeave(firstIsland);
        order.verify(levels).playerEnter(secondIsland);
        order.verifyNoMoreInteractions();
    }

    @Test
    void respawnAtAnotherIslandIsObservedWithoutARespawnEvent() {
        join();
        sample(0);

        when(player.isDead()).thenReturn(true);
        sample(0);
        when(player.isDead()).thenReturn(false);
        when(player.getLocation()).thenReturn(secondLocation);
        sample(0);

        verify(levels).playerLeave(firstIsland);
        verify(levels).playerEnter(secondIsland);
        verify(levels, times(1)).playerEnter(firstIsland);
    }

    @Test
    void leavingTheIslandWorldReleasesItsCountOnce() {
        join();
        sample(0);
        World lobby = mock(World.class);
        when(lobby.getName()).thenReturn("lobby");
        Location lobbyLocation = new Location(lobby, 0, 100, 0);
        when(player.getLocation()).thenReturn(lobbyLocation);

        sample(0);
        sample(0);

        verify(levels).playerEnter(firstIsland);
        verify(levels).playerLeave(firstIsland);
        verify(cache, never()).getIslandUuidAt(lobbyLocation);
        verifyNoMoreInteractions(levels);
    }

    @Test
    void quitAndRetirementCannotReleaseTheSameCountTwiceOrEnterAgain() {
        join();
        sample(0);

        quit();
        retired.getFirst().run();
        when(player.getLocation()).thenReturn(secondLocation);
        sample(0);

        verify(tasks.getFirst()).cancel();
        verify(levels).playerLeave(firstIsland);
        verify(levels, never()).playerEnter(secondIsland);
        verify(levels, times(1)).playerEnter(firstIsland);
    }

    @Test
    void schedulerRetirementReleasesTheCountWithoutAQuitEvent() {
        join();
        sample(0);

        retired.getFirst().run();
        retired.getFirst().run();
        quit();

        verify(levels).playerLeave(firstIsland);
        verify(tasks.getFirst()).cancel();
        verify(levels, times(1)).playerEnter(firstIsland);
    }

    @Test
    void aPreviousConnectionsRetirementCannotRemoveTheNewConnectionsTracking() {
        join();
        sample(0);
        quit();
        when(player.getLocation()).thenReturn(secondLocation);
        join();
        sample(1);

        retired.getFirst().run();
        sample(0);
        sample(1);
        quit();

        InOrder order = inOrder(levels);
        order.verify(levels).playerEnter(firstIsland);
        order.verify(levels).playerLeave(firstIsland);
        order.verify(levels).playerEnter(secondIsland);
        order.verify(levels).playerLeave(secondIsland);
        order.verifyNoMoreInteractions();
        verify(tasks.get(1)).cancel();
    }

    @Test
    void anAlreadyRetiredSchedulerDoesNotLeaveAnActiveTrackingSession() {
        when(scheduler.runAtFixedRate(eq(plugin), any(), any(), eq(1L), eq(20L))).thenReturn(null);

        join();
        quit();

        verifyNoInteractions(levels);
        verify(player, never()).getLocation();
    }

    @Test
    void shutdownCancelsEverySampleWithoutPostingLeavesOrAllowingNewSessions() {
        join();
        sample(0);
        Player secondPlayer = mock(Player.class);
        when(secondPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(secondPlayer.getName()).thenReturn("Alex");
        when(secondPlayer.getScheduler()).thenReturn(scheduler);
        when(secondPlayer.getLocation()).thenReturn(secondLocation);
        listener.onPlayerJoin(new PlayerJoinEvent(secondPlayer, Component.empty()));
        sample(1);

        listener.stop();
        listener.stop();
        retired.get(0).run();
        retired.get(1).run();
        quit();
        listener.onPlayerQuit(new PlayerQuitEvent(secondPlayer, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        sample(0);
        sample(1);
        join();

        assertEquals(2, tasks.size());
        verify(tasks.get(0)).cancel();
        verify(tasks.get(1)).cancel();
        verify(levels).playerEnter(firstIsland);
        verify(levels).playerEnter(secondIsland);
        verify(levels, never()).playerLeave(any());
        verifyNoMoreInteractions(levels);
    }

    private void join() {
        listener.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
    }

    private void quit() {
        listener.onPlayerQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    private void sample(int session) {
        samples.get(session).accept(tasks.get(session));
    }
}
