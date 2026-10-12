package org.me.advancedsky.listener;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
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
import org.me.advancedsky.teleport.TeleportHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeleportRequestListenerTest {

    @Test
    void aLateTeleportRunsATickAfterJoinAndOnlyMessagesAfterTrue() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        when(fixture.player.teleportAsync(fixture.target)).thenReturn(moved);
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);

        fixture.join();
        verify(fixture.player, never()).teleportAsync(any());
        verify(fixture.player, never()).sendMessage(any(Component.class));
        fixture.runJoinTick();
        var order = inOrder(fixture.player);
        order.verify(fixture.player).leaveVehicle();
        order.verify(fixture.player).teleportAsync(fixture.target);
        verify(fixture.player, never()).sendMessage(any(Component.class));
        verify(fixture.player, never()).setFallDistance(anyFloat());
        moved.complete(true);
        verify(fixture.player).setFallDistance(0);
        verify(fixture.player, never()).sendMessage(any(Component.class));
        fixture.runConfirmationTick();
        verify(fixture.player).sendMessage(fixture.message);
    }

    @Test
    void spawnSelectionKeepsTheMessageUntilJoinAndNeverTeleportsAgain() {
        Fixture fixture = new Fixture();
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);

        var spawn = fixture.spawn();
        verify(spawn).setSpawnLocation(fixture.target);
        verify(fixture.player, never()).sendMessage(any(Component.class));
        fixture.join();
        fixture.runJoinTick();
        verify(fixture.player).sendMessage(fixture.message);
        verify(fixture.player, never()).teleportAsync(any());

        fixture.join();
        assertTrue(fixture.delayed.isEmpty());
        verify(fixture.player, times(1)).sendMessage(fixture.message);
    }

    @Test
    void aNewerLateRequestReplacesTheSpawnConfirmation() {
        Fixture fixture = new Fixture();
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);
        fixture.spawn();
        Location newer = mock(Location.class);
        when(newer.isWorldLoaded()).thenReturn(true);
        Component newerMessage = Component.text("newer");
        fixture.teleports.addPendingTeleport(fixture.playerUuid, newer, newerMessage);
        when(fixture.player.teleportAsync(newer)).thenReturn(CompletableFuture.completedFuture(true));

        fixture.join();
        fixture.runJoinTick();
        fixture.runConfirmationTick();

        verify(fixture.player).teleportAsync(newer);
        verify(fixture.player).sendMessage(newerMessage);
        verify(fixture.player, never()).sendMessage(fixture.message);
    }

    @Test
    void cancellationAndExceptionalCompletionDoNotSendSuccess() {
        for (CompletableFuture<Boolean> moved : List.of(
                CompletableFuture.completedFuture(false),
                CompletableFuture.<Boolean>failedFuture(new IllegalStateException("cancelled")))) {
            Fixture fixture = new Fixture();
            when(fixture.player.teleportAsync(fixture.target)).thenReturn(moved);
            fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);

            fixture.join();
            fixture.runJoinTick();
            fixture.runConfirmationTick();

            verify(fixture.player, never()).sendMessage(any(Component.class));
            assertNull(fixture.teleports.claimJoinTeleport(fixture.playerUuid));
        }
    }

    @Test
    void anOverriddenSpawnNeverConfirmsTheRequestedDestination() {
        Fixture fixture = new Fixture();
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);
        fixture.spawn();
        Location elsewhere = new Location(mock(World.class), 10, 70, 10);
        when(fixture.player.getLocation()).thenReturn(elsewhere);

        fixture.join();
        fixture.runJoinTick();

        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    void unloadedDestinationIsDiscardedWithoutSpawnOrSuccess() {
        Fixture fixture = new Fixture();
        when(fixture.target.isWorldLoaded()).thenReturn(false);
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);

        var spawn = fixture.spawn();
        fixture.join();

        verify(spawn, never()).setSpawnLocation(any());
        assertTrue(fixture.delayed.isEmpty());
        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    void quittingBeforeTheJoinTickDiscardsConfirmation() {
        Fixture fixture = new Fixture();
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);
        fixture.spawn();
        fixture.join();
        fixture.listener.onQuit(new PlayerQuitEvent(fixture.player, Component.empty()));

        fixture.runJoinTick();
        fixture.runConfirmationTick();

        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    void anOfflinePlayerNeverGetsAJoinSuccess() {
        Fixture fixture = new Fixture();
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);
        fixture.spawn();
        fixture.join();
        when(fixture.player.isOnline()).thenReturn(false);

        fixture.runJoinTick();

        verify(fixture.player, never()).sendMessage(any(Component.class));
        verify(fixture.player, never()).teleportAsync(any());
    }

    @Test
    void accessDenialCancelsASpawnConfirmationBeforeTheJoinTask() {
        Fixture fixture = new Fixture();
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);
        fixture.spawn();
        fixture.teleports.cancelArrival(fixture.playerUuid);

        fixture.join();

        assertTrue(fixture.delayed.isEmpty());
        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedLateTeleportStillChecksAccessAtTheTemporarySpawn() {
        Fixture fixture = new Fixture();
        Predicate<Player> access = mock(Predicate.class);
        when(access.test(fixture.player)).thenReturn(false);
        fixture.teleports.setArrivalCheck(access);
        when(fixture.player.teleportAsync(fixture.target)).thenReturn(CompletableFuture.completedFuture(false));
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);

        fixture.join();
        fixture.runJoinTick();
        fixture.runConfirmationTick();

        verify(access).test(fixture.player);
        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLateTeleportWithoutMessageStillChecksAccessAtTheDestination() {
        Fixture fixture = new Fixture();
        Predicate<Player> access = mock(Predicate.class);
        when(access.test(fixture.player)).thenReturn(true);
        fixture.teleports.setArrivalCheck(access);
        when(fixture.player.teleportAsync(fixture.target)).thenReturn(CompletableFuture.completedFuture(true));
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target);

        fixture.join();
        fixture.runJoinTick();
        fixture.runConfirmationTick();

        verify(access).test(fixture.player);
        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    void aNewerRequestCancelsTheOldLateConfirmationWhileItsFutureIsStillRunning() {
        Fixture fixture = new Fixture();
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        when(fixture.player.teleportAsync(fixture.target)).thenReturn(moved);
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, fixture.message);
        fixture.join();
        fixture.runJoinTick();
        Component newer = Component.text("newer");
        fixture.teleports.addPendingTeleport(fixture.playerUuid, fixture.target, newer);
        moved.complete(true);

        fixture.runConfirmationTick();

        verify(fixture.player, never()).sendMessage(any(Component.class));
        assertTrue(fixture.teleports.hasPendingTeleport(fixture.playerUuid));
    }

    private static final class Fixture {
        private final AdvancedSky plugin = mock(AdvancedSky.class);
        private final TeleportHandler teleports = new TeleportHandler(plugin, null, null);
        private final TeleportRequestListener listener = new TeleportRequestListener(plugin, teleports);
        private final UUID playerUuid = UUID.randomUUID();
        private final Player player = mock(Player.class);
        private final EntityScheduler scheduler = mock(EntityScheduler.class);
        private final Location target = mock(Location.class);
        private final Component message = Component.text("Teleported to home");
        private final List<Consumer<ScheduledTask>> delayed = new ArrayList<>();
        private final List<Consumer<ScheduledTask>> ready = new ArrayList<>();

        private Fixture() {
            when(player.getUniqueId()).thenReturn(playerUuid);
            when(player.isOnline()).thenReturn(true);
            when(player.getScheduler()).thenReturn(scheduler);
            when(player.getLocation()).thenReturn(target);
            when(target.isWorldLoaded()).thenReturn(true);
            when(scheduler.runDelayed(any(), any(), any(), eq(1L))).thenAnswer(call -> {
                delayed.add(call.getArgument(1));
                return mock(ScheduledTask.class);
            });
            when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
                ready.add(call.getArgument(1));
                return mock(ScheduledTask.class);
            });
        }

        private AsyncPlayerSpawnLocationEvent spawn() {
            var event = mock(AsyncPlayerSpawnLocationEvent.class, RETURNS_DEEP_STUBS);
            when(event.getConnection().getProfile().getId()).thenReturn(playerUuid);
            when(event.getConnection().getProfile().getName()).thenReturn("arrival");
            listener.onSpawnLocation(event);
            return event;
        }

        private void join() {
            listener.onJoin(new PlayerJoinEvent(player, Component.empty()));
        }

        private void runJoinTick() {
            delayed.removeFirst().accept(mock(ScheduledTask.class));
        }

        private void runConfirmationTick() {
            ready.removeFirst().accept(mock(ScheduledTask.class));
        }
    }
}
