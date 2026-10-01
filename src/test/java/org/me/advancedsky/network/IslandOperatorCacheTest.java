package org.me.advancedsky.network;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.world.WorldHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.island.LimitHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

class IslandOperatorCacheTest {
    private final UUID island = UUID.randomUUID();
    private final Actor actor = new Actor.Bypass("test");
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final IslandOperator operator = new IslandOperator(plugin, database, null, null, cache, null, null, "local");

    @Test
    void aCreateWhoseCommitAnswerWasLostIsBuiltWhenTheRowLanded() {
        UUID owner = UUID.randomUUID();
        WorldHandler world = mock(WorldHandler.class);
        when(world.createIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(database.addIslandData(island, owner, "local")).thenThrow(new RuntimeException("connection reset during commit"));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        when(cache.load(island)).thenReturn(CompletableFuture.completedFuture(null));
        inlineAsync();

        assertDoesNotThrow(() -> new IslandOperator(plugin, database, world, null, cache, null, null, "local").createIsland(island, owner).join());
        verify(world).createIsland(5);
    }

    @Test
    void aDeleteWhoseCommitAnswerWasLostCompletesWhenTheRowIsGone() {
        WorldHandler world = mock(WorldHandler.class);
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5), Optional.empty());
        doThrow(new RuntimeException("connection reset during commit")).when(database).deleteIsland(actor, island);
        LimitHandler limits = mock(LimitHandler.class);

        assertDoesNotThrow(() -> new IslandOperator(plugin, database, world, null, cache, limits, null, "local").deleteIsland(actor, island).join());
        verify(cache).unload(island);
        verify(world).deleteIsland(5);
    }

    private void inlineAsync() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
    }

    @Test
    void committedMutationKeepsItsResultWhenCacheReloadFails() {
        when(database.toggleIslandPvp(actor, island)).thenReturn(true);
        when(cache.reload(island)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload failed")));

        assertTrue(operator.toggleIslandPvp(actor, island).join());
        verify(plugin).severe(eq("Island data saved but could not refresh the island cache: " + island), any(Throwable.class));
    }

    @Test
    void mutationRefusedByARuleLeavesTheCacheAlone() {
        IslandDoesNotExistException failure = new IslandDoesNotExistException();
        doThrow(failure).when(database).deleteWarpPoint(actor, island, "shop");

        CompletionException error = assertThrows(CompletionException.class, () -> operator.deleteWarp(actor, island, "shop").join());
        assertSame(failure, error.getCause());
        verify(cache, never()).reload(island);
    }

    @Test
    void failedMutationThatMayHaveCommittedKeepsItsErrorWhenCacheReloadAlsoFails() {
        RuntimeException failure = new RuntimeException(new SQLException("connection reset during commit"));
        doThrow(failure).when(database).deleteWarpPoint(actor, island, "shop");
        when(cache.reload(island)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload failed")));

        CompletionException error = assertThrows(CompletionException.class, () -> operator.deleteWarp(actor, island, "shop").join());
        assertSame(failure, error.getCause());
        verify(cache).reload(island);
        verifyNoInteractions(plugin);
    }
}
