package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.island.IslandActivityHandler;
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
    private final IslandActivityHandler activity = new IslandActivityHandler(plugin);
    private final LevelUpdateScheduler scheduler = new LevelUpdateScheduler(plugin, levelHandler, activity);

    LevelUpdateSchedulerTest() {
        // The global region thread runs registrations and completions inline here.
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void islandLeftBeforeItsScanStartedIsDroppedAfterThatScan(boolean scanSucceeds) {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan, new CompletableFuture<>());
        activity.playerEnter(island);
        activity.playerLeave(island, System.currentTimeMillis() - 60_000L);
        Runnable tick = startAndCaptureTick();

        scheduler.registerIsland(island);
        tick.run();
        if (scanSucceeds) {
            scan.complete(7);
        } else {
            scan.completeExceptionally(new IllegalStateException("Level scan failed"));
        }
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);

        // Dropped, not merely rescheduled: registering again makes it due at the next poll.
        scheduler.registerIsland(island);
        tick.run();
        verify(levelHandler, times(2)).calIslandLevel(island);
    }

    @Test
    void occupiedIslandIsRescheduledAfterItsScan() {
        CompletableFuture<Integer> scan = new CompletableFuture<>();
        when(levelHandler.calIslandLevel(island)).thenReturn(scan);
        activity.playerEnter(island);
        Runnable tick = startAndCaptureTick();

        scheduler.registerIsland(island);
        tick.run();
        scan.complete(7);
        tick.run();

        // Rescheduled minutes ahead: still scheduled, so registering again changes nothing.
        scheduler.registerIsland(island);
        tick.run();
        verify(levelHandler, times(1)).calIslandLevel(island);
    }

    @Test
    void registeringAnAlreadyScheduledIslandDoesNotResetIt() {
        when(levelHandler.calIslandLevel(island)).thenReturn(new CompletableFuture<>());
        activity.playerEnter(island);
        Runnable tick = startAndCaptureTick();

        scheduler.registerIsland(island);
        scheduler.registerIsland(island);
        tick.run();
        // A second player arriving mid-scan does not queue a fresh scan behind the running one.
        scheduler.registerIsland(island);
        tick.run();

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
