package org.me.advancedsky.teleport;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.TeleportCancelledException;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.me.advancedsky.world.WorldHandler;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeleportExecutionTest {
    private final UUID playerUuid = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final EntityScheduler scheduler = mock(EntityScheduler.class);
    private final TeleportHandler teleports = new TeleportHandler(plugin, null, null);

    TeleportExecutionTest() {
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 1.5, 70.0, 1.5));
    }

    @Test
    void aTeleportCancelledInPlaceFails() {
        runsTasks();
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(false));

        CompletionException error = assertThrows(CompletionException.class, () -> prepare().join());
        assertInstanceOf(TeleportCancelledException.class, error.getCause());
        assertNull(teleports.removePendingTeleport(playerUuid));
    }

    @Test
    void aPlayerWhoLeftBeforeTheTeleportRanGetsItOnTheirReturn() {
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return mock(ScheduledTask.class);
        });

        assertFalse(prepare().join());
        assertNotNull(teleports.removePendingTeleport(playerUuid));
    }

    @Test
    void aTeleportInPlaceCompletesTrue() {
        runsTasks();
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(prepare().join());
        // A rider is taken off their mount first, as Folia refuses to teleport a passenger.
        InOrder order = inOrder(player);
        order.verify(player).leaveVehicle();
        order.verify(player).teleportAsync(any(Location.class));
    }

    @Test
    void aTeleportOntoADeletingIslandIsRefusedBeforeLoadingTheDestination() {
        WorldHandler worlds = mock(WorldHandler.class);
        IslandCache cache = mock(IslandCache.class);
        when(worlds.isIslandLocation(any())).thenReturn(true);
        when(cache.isDeletingAt(any())).thenReturn(true);
        TeleportHandler deleting = new TeleportHandler(plugin, worlds, cache);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("skyblock")).thenReturn(world);
            var error = assertThrows(CompletionException.class, () -> deleting.prepareTeleport(playerUuid, "skyblock", "1.5,70.0,1.5,0.0,0.0").join());
            assertInstanceOf(IslandDoesNotExistException.class, error.getCause());
        }
        verify(player, never()).teleportAsync(any());
        assertNull(teleports.removePendingTeleport(playerUuid));
    }

    @Test
    void successWaitsForTheTeleportAndRunsOnTheEntityScheduler() {
        List<Consumer<ScheduledTask>> tasks = new ArrayList<>();
        when(scheduler.run(any(), any(), nullable(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
        CompletableFuture<Boolean> moved = new CompletableFuture<>();
        when(player.teleportAsync(any(Location.class))).thenReturn(moved);
        Component message = Component.text("Teleported to home");

        var prepared = prepare(message);
        tasks.getFirst().accept(mock(ScheduledTask.class));
        verify(player, never()).sendMessage(any(Component.class));
        moved.complete(true);
        assertTrue(prepared.join());
        verify(player, never()).sendMessage(any(Component.class));
        assertEquals(2, tasks.size());
        tasks.get(1).accept(mock(ScheduledTask.class));
        verify(player).sendMessage(message);
    }

    @Test
    void cancelledAndFailedTeleportsNeverSendSuccess() {
        runsTasks();
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(false));
        assertThrows(CompletionException.class, () -> prepare(Component.text("home")).join());
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("failed")));
        assertThrows(CompletionException.class, () -> prepare(Component.text("home")).join());

        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void offlinePlayersDoNotReceiveSuccessAfterATeleportCompletes() {
        runsTasks();
        when(player.isOnline()).thenReturn(false);
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(prepare(Component.text("home")).join());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void retiredPlayersKeepTheirDestinationAndMessageForArrival() {
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return mock(ScheduledTask.class);
        });
        Component message = Component.text("home");

        assertFalse(prepare(message).join());
        assertSame(message, teleports.takePendingTeleport(playerUuid).successMessage());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void aNewLocalTeleportInvalidatesAnEarlierSpawnConfirmation() {
        Component oldMessage = Component.text("old");
        teleports.addPendingTeleport(playerUuid, new Location(world, 1.5, 70, 1.5), oldMessage);
        var oldArrival = teleports.takeSpawnTeleport(playerUuid);
        teleports.claimJoinTeleport(playerUuid);
        runsTasks();
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        Component newMessage = Component.text("new");

        assertTrue(prepare(newMessage).join());
        assertFalse(teleports.completeArrival(playerUuid, oldArrival));
        verify(player).sendMessage(newMessage);
        verify(player, never()).sendMessage(oldMessage);
    }

    @Test
    void anOldRetiredCallbackCannotOverwriteANewerPendingRequest() {
        List<Runnable> retired = new ArrayList<>();
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            retired.add(call.getArgument(2));
            return mock(ScheduledTask.class);
        });
        var oldRequest = prepare(Component.text("old"));
        Component newerMessage = Component.text("new");
        var newerRequest = prepare(newerMessage);

        retired.getFirst().run();
        var error = assertThrows(CompletionException.class, oldRequest::join);
        assertInstanceOf(TeleportCancelledException.class, error.getCause());
        retired.get(1).run();

        assertFalse(newerRequest.join());
        assertSame(newerMessage, teleports.takePendingTeleport(playerUuid).successMessage());
    }

    @Test
    void anExistingAccessRedirectCancelsTheImmediateTeleportSuccess() {
        List<Consumer<ScheduledTask>> tasks = new ArrayList<>();
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

        var prepared = prepare(Component.text("home"));
        tasks.getFirst().accept(mock(ScheduledTask.class));
        assertTrue(prepared.join());
        teleports.cancelArrival(playerUuid);
        tasks.get(1).accept(mock(ScheduledTask.class));

        verify(player, never()).sendMessage(any(Component.class));
        assertNull(teleports.claimJoinTeleport(playerUuid));
    }

    @Test
    void anImmediateTeleportPreservesTheExistingAdministrativeAccessBypass() {
        runsTasks();
        AtomicInteger checks = new AtomicInteger();
        teleports.setArrivalCheck(arriving -> {
            checks.incrementAndGet();
            return false;
        });
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

        Component message = Component.text("home");
        assertTrue(prepare(message).join());

        assertEquals(0, checks.get());
        verify(player).sendMessage(message);
    }

    @Test
    void aPlayerWhoseConfirmationSchedulerIsAlreadyRetiredNeverGetsSuccess() {
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        }).thenReturn(null);
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(prepare(Component.text("home")).join());

        verify(player, never()).sendMessage(any(Component.class));
        assertNull(teleports.claimJoinTeleport(playerUuid));
    }

    @Test
    void movementWithinTheDestinationWorldDoesNotSuppressATrueTeleportSuccess() {
        runsTasks();
        when(player.getLocation()).thenReturn(new Location(world, 20, 65, 20));
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        Component message = Component.text("home");

        assertTrue(prepare(message).join());

        verify(player).sendMessage(message);
    }

    @Test
    void aRedirectToAnotherWorldBeforeConfirmationSuppressesTheSuccess() {
        runsTasks();
        when(player.getLocation()).thenReturn(new Location(mock(World.class), 1.5, 70, 1.5));
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(prepare(Component.text("home")).join());

        verify(player, never()).sendMessage(any(Component.class));
    }

    private void runsTasks() {
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
    }

    private CompletableFuture<Boolean> prepare() {
        return prepare(null);
    }

    private CompletableFuture<Boolean> prepare(Component message) {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);
            bukkit.when(() -> Bukkit.getWorld("skyblock")).thenReturn(world);
            return teleports.prepareTeleport(playerUuid, "skyblock", "1.5,70.0,1.5,0.0,0.0", message);
        }
    }
}
