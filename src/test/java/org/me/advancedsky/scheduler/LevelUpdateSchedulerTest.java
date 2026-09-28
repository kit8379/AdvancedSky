package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
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
        // The global region thread runs registrations and completions inline here.
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
    }

    @Test
    void staleScanCompletionLeavesTheFreshRegistrationDueNow() {
        CompletableFuture<Integer> staleScan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(staleScan, new CompletableFuture<>());
        Runnable tick = startAndCaptureTick();

        scheduler.registerIsland(island);
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);

        // The island is unregistered mid-scan and registered again: a fresh entry is due for its first scan.
        scheduler.unregisterIsland(island);
        scheduler.registerIsland(island);

        staleScan.complete(7);
        tick.run();

        verify(levelHandler, times(2)).calIslandLevel(island);
    }

    @Test
    void staleScanFailureLeavesTheFreshRegistrationDueNow() {
        CompletableFuture<Integer> staleScan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(staleScan, new CompletableFuture<>());
        Runnable tick = startAndCaptureTick();

        scheduler.registerIsland(island);
        tick.run();
        scheduler.unregisterIsland(island);
        scheduler.registerIsland(island);

        staleScan.completeExceptionally(new IllegalStateException("Level scan failed"));
        tick.run();

        verify(levelHandler, times(2)).calIslandLevel(island);
    }

    @Test
    void ownCompletionStillReschedulesTheIsland() {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan);
        Runnable tick = startAndCaptureTick();

        scheduler.registerIsland(island);
        tick.run();
        scan.complete(7);
        tick.run();

        // Rescheduled minutes ahead, so the next poll does not start another scan.
        verify(levelHandler, times(1)).calIslandLevel(island);
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
