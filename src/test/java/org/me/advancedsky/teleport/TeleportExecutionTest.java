package org.me.advancedsky.teleport;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

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

    private void runsTasks() {
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
    }

    private CompletableFuture<Boolean> prepare() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);
            bukkit.when(() -> Bukkit.getWorld("skyblock")).thenReturn(world);
            return teleports.prepareTeleport(playerUuid, "skyblock", "1.5,70.0,1.5,0.0,0.0");
        }
    }
}
