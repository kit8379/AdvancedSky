package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.island.LevelHandler;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LevelUpdateSchedulerTest {
    private final UUID island = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
    private final LevelHandler levelHandler = mock(LevelHandler.class);
    private final LevelUpdateScheduler scheduler = new LevelUpdateScheduler(plugin, levelHandler);

    LevelUpdateSchedulerTest() {
        // The global region thread runs arrivals, leaves and completions inline here.
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void islandLeftBeforeItsScanStartedIsDroppedAfterThatScan(boolean scanSucceeds) {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan, new CompletableFuture<>());
        Runnable tick = startAndCaptureTick();

        scheduler.playerEnter(island);
        scheduler.playerLeave(island);
        tick.run();
        finish(scan, scanSucceeds);
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);

        // Dropped, not merely rescheduled: the next arrival makes it due at the next poll.
        scheduler.playerEnter(island);
        tick.run();
        verify(levelHandler, times(2)).calIslandLevel(island);
    }

    @Test
    void islandLeftDuringItsScanGetsOneMoreScanAtTheNextPoll() {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        CompletableFuture<Integer> last = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan, last);
        Runnable tick = startAndCaptureTick();

        scheduler.playerEnter(island);
        tick.run();
        scheduler.playerLeave(island);
        scan.complete(7);
        tick.run();
        verify(levelHandler, times(2)).calIslandLevel(island);

        // Nobody came back during that one, so it was the last: the next arrival scans again.
        last.complete(7);
        scheduler.playerEnter(island);
        tick.run();
        verify(levelHandler, times(3)).calIslandLevel(island);
    }

    @Test
    void theLastPlayerLeavingBringsTheNextScanForward() {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan, new CompletableFuture<>());
        Runnable tick = startAndCaptureTick();

        scheduler.playerEnter(island);
        scheduler.playerEnter(island);
        tick.run();
        scan.complete(7);
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);

        // Scheduled minutes ahead until the island empties, then due while its chunks are still loaded.
        scheduler.playerLeave(island);
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);
        scheduler.playerLeave(island);
        tick.run();
        verify(levelHandler, times(2)).calIslandLevel(island);
    }

    @Test
    void occupiedIslandIsRescheduledAfterItsScan() {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan);
        Runnable tick = startAndCaptureTick();

        scheduler.playerEnter(island);
        tick.run();
        scan.complete(7);
        tick.run();

        // A second player arriving does not queue a scan either: the island is already scheduled.
        scheduler.playerEnter(island);
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);
    }

    @Test
    void anArrivalWhileScheduledDoesNotResetTheIsland() {
        when(levelHandler.calIslandLevel(island)).thenReturn(new CompletableFuture<>());
        Runnable tick = startAndCaptureTick();

        scheduler.playerEnter(island);
        scheduler.playerLeave(island);
        scheduler.playerEnter(island);
        tick.run();
        // The island emptied and filled again before its scan: still one scan.
        scheduler.playerLeave(island);
        scheduler.playerEnter(island);
        tick.run();

        verify(levelHandler, times(1)).calIslandLevel(island);
    }

    private static void finish(CompletableFuture<Integer> scan, boolean succeeds) {
        if (succeeds) {
            scan.complete(7);
        } else {
            scan.completeExceptionally(new IllegalStateException("Level scan failed"));
        }
    }

    @SuppressWarnings("unchecked")
    private Runnable startAndCaptureTick() {
        ArgumentCaptor<Consumer<ScheduledTask>> tick = ArgumentCaptor.forClass(Consumer.class);
        when(globalScheduler.runAtFixedRate(eq(plugin), tick.capture(), anyLong(), anyLong())).thenReturn(mock(ScheduledTask.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            scheduler.start();
        }
        ScheduledTask task = mock(ScheduledTask.class);
        return () -> tick.getValue().accept(task);
    }
}
