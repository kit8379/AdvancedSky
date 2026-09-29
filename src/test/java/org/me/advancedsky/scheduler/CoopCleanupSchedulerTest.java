package org.me.advancedsky.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.exceptions.PlayerNotCoopedException;
import org.me.advancedsky.island.CoopHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CoopCleanupSchedulerTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final CoopHandler coops = mock(CoopHandler.class);
    private final OnlinePlayerRegistry registry = mock(OnlinePlayerRegistry.class);
    private final CoopCleanupScheduler scheduler = new CoopCleanupScheduler(plugin, cache, coops, registry);

    @Test
    void coopsOfPlayersOnlineNowhereAreRemovedAndOnlineOnesKept() {
        UUID islandUuid = UUID.randomUUID();
        UUID online = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID alreadyRemoved = UUID.randomUUID();
        Island island = new Island(islandUuid, 1, false, false, UUID.randomUUID(), Set.of(), Set.of(online, gone, alreadyRemoved), Set.of(), Map.of(), null, 1, 1);
        when(cache.getIslands()).thenReturn(List.of(island));
        when(registry.isOnline(online)).thenReturn(true);
        when(coops.unCoopPlayer(any(), eq(islandUuid), eq(gone))).thenReturn(CompletableFuture.completedFuture(null));
        when(coops.unCoopPlayer(any(), eq(islandUuid), eq(alreadyRemoved))).thenReturn(CompletableFuture.failedFuture(new CompletionException(new PlayerNotCoopedException())));

        startAndCaptureSweep().run();

        verify(coops).unCoopPlayer(any(Actor.Bypass.class), eq(islandUuid), eq(gone));
        verify(coops).unCoopPlayer(any(Actor.Bypass.class), eq(islandUuid), eq(alreadyRemoved));
        verify(coops, never()).unCoopPlayer(any(), any(), eq(online));
        // A co-op something else removed first is not an error.
        verify(plugin, never()).severe(anyString(), any());
    }

    private Runnable startAndCaptureSweep() {
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        ArgumentCaptor<Consumer<ScheduledTask>> sweep = ArgumentCaptor.forClass(Consumer.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), sweep.capture(), anyLong(), anyLong(), eq(TimeUnit.MINUTES))).thenReturn(mock(ScheduledTask.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);
            scheduler.start();
        }
        ScheduledTask task = mock(ScheduledTask.class);
        return () -> sweep.getValue().accept(task);
    }
}
