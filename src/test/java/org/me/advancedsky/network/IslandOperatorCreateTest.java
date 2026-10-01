package org.me.advancedsky.network;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandOperatorCreateTest {

    @Test
    void aSlotStillHoldingADeletedIslandIsNotBuiltOn() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        DatabaseHandler database = mock(DatabaseHandler.class);
        IslandCache cache = mock(IslandCache.class);
        WorldHandler world = mock(WorldHandler.class);
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(database.addIslandData(island, owner, "sky1")).thenReturn(5);
        when(cache.load(island)).thenReturn(CompletableFuture.completedFuture(null));
        when(world.isPurgePending(5)).thenReturn(true);
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        IslandOperator operator = new IslandOperator(plugin, database, world, null, cache, null, null, "sky1");

        assertThrows(CompletionException.class, () -> operator.createIsland(island, owner).join());

        verify(world, never()).createIsland(anyInt());
        verify(database).deleteIsland(any(Actor.Bypass.class), eq(island));
        verify(cache).unload(island);
    }
}
