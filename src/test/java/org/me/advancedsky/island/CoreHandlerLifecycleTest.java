package org.me.advancedsky.island;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.island.LimitHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreHandlerLifecycleTest {
    private final UUID island = UUID.randomUUID();
    private final Actor actor = new Actor.Bypass("test");
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final CoreHandler core = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), null, null, "local");

    @BeforeEach
    void setUp() {
        inlineAsync();
        when(database.getIslandServer(any())).thenReturn(Optional.of("local"));
    }

    @Test
    void aCreateWhoseCommitAnswerWasLostIsBuiltWhenTheRowLanded() {
        UUID owner = UUID.randomUUID();
        WorldHandler world = mock(WorldHandler.class);
        when(world.createIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(database.addIslandData(island, owner, "local")).thenThrow(new RuntimeException("connection reset during commit"));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        when(cache.load(island)).thenReturn(CompletableFuture.completedFuture(null));
        inlineAsync();

        assertDoesNotThrow(() -> new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").createIsland(island, owner).join());
        verify(world).createIsland(5);
        var order = inOrder(world, database, cache);
        order.verify(world).deleteIsland(5);
        order.verify(world).createIsland(5);
        order.verify(database).finishIslandCreation(island);
        order.verify(cache).load(island);
    }

    @Test
    void aDeleteWhoseCommitAnswerWasLostCompletesWhenTheRowIsGone() {
        WorldHandler world = mock(WorldHandler.class);
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5), Optional.empty());
        doThrow(new RuntimeException("connection reset during commit")).when(database).finishIslandDeletion(island);
        LimitHandler limits = mock(LimitHandler.class);
        inlineAsync();

        assertDoesNotThrow(() -> new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, limits, "local").deleteIsland(actor, island).join());
        verify(cache).unload(island);
        verify(world).deleteIsland(5);
    }

    @Test
    void anInterruptedCreateRetainsItsRowForCleanupOnTheNextStartup() {
        UUID owner = UUID.randomUUID();
        UUID built = UUID.randomUUID();
        WorldHandler world = mock(WorldHandler.class);
        when(world.createIsland(5)).thenReturn(new CompletableFuture<>());
        when(world.createIsland(6)).thenReturn(CompletableFuture.completedFuture(null));
        when(world.deleteIsland(anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(database.addIslandData(island, owner, "local")).thenReturn(5);
        when(database.addIslandData(built, owner, "local")).thenReturn(6);
        when(cache.load(any())).thenReturn(CompletableFuture.completedFuture(null));
        inlineAsync();
        CoreHandler core = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local");
        core.createIsland(island, owner);
        core.createIsland(built, owner).join();

        core.abortCreates();

        verify(database).beginIslandDeletion(any(Actor.Bypass.class), eq(island));
        verify(database, never()).finishIslandDeletion(any());
        verify(database, never()).beginIslandDeletion(any(), eq(built));
        verify(world).stop();
    }

    private void inlineAsync() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
    }

    @Test
    void deletionAcknowledgesBeforeCleanupButKeepsTheSlotAndCacheBlocked() {
        WorldHandler world = mock(WorldHandler.class);
        CompletableFuture<Void> clearing = new CompletableFuture<>();
        when(world.deleteIsland(5)).thenReturn(clearing);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        inlineAsync();
        var deleting = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").deleteIsland(actor, island);
        assertTrue(deleting.isDone());
        deleting.join();
        assertFalse(clearing.isDone());
        verify(database).beginIslandDeletion(actor, island);
        verify(cache).beginDeletion(island, 5);
        verify(database, never()).finishIslandDeletion(any());
        verify(cache, never()).unload(island);
        clearing.complete(null);
        deleting.join();
        var order = inOrder(database, world, cache);
        order.verify(database).beginIslandDeletion(actor, island);
        order.verify(cache).beginDeletion(island, 5);
        order.verify(world).deleteIsland(5);
        order.verify(database).finishIslandDeletion(island);
        order.verify(cache).unload(island);
    }

    @Test
    void aPhysicalCleanupFailureRetainsTheDatabaseRowAndBlockedCache() {
        WorldHandler world = mock(WorldHandler.class);
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("chunk held")));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        inlineAsync();
        var deleting = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").deleteIsland(actor, island);
        assertDoesNotThrow(deleting::join);
        verify(database).beginIslandDeletion(actor, island);
        verify(database, never()).finishIslandDeletion(any());
        verify(cache, never()).unload(island);
        verify(plugin).severe(contains("retained for physical cleanup"), any(Throwable.class));
    }

    @Test
    void aDatabaseDeleteFailureCanRetryPhysicalCleanupWithoutFreeingTheSlotEarly() {
        WorldHandler world = mock(WorldHandler.class);
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        doThrow(new IllegalStateException("database down")).doNothing().when(database).finishIslandDeletion(island);
        when(database.getDeletingIslands("local")).thenReturn(java.util.Map.of(island, 5));
        when(database.isIslandDeleting(island)).thenReturn(true);
        inlineAsync();
        CoreHandler core = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local");
        assertDoesNotThrow(() -> core.deleteIsland(actor, island).join());
        verify(cache, never()).unload(island);
        assertEquals(new CoreHandler.DeletionResult(1, 0), core.retryDeletions().join());
        verify(world, times(2)).deleteIsland(5);
        verify(cache).unload(island);
    }

    @Test
    void unauthorizedDeletionNeverTouchesThePhysicalArea() {
        WorldHandler world = mock(WorldHandler.class);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        doThrow(new IslandDoesNotExistException()).when(database).beginIslandDeletion(actor, island);
        assertThrows(CompletionException.class, () -> new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").deleteIsland(actor, island).join());
        verifyNoInteractions(world);
        verify(cache, never()).beginDeletion(any(), anyInt());
    }

    @Test
    void aDeletionWhoseCommitReplyWasLostAcknowledgesWhenPlayersHaveBeenDetached() {
        WorldHandler world = mock(WorldHandler.class);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        doThrow(new RuntimeException("commit reply lost")).when(database).beginIslandDeletion(actor, island);
        when(database.isIslandDeleting(island)).thenReturn(true);
        when(database.getIslandPlayers(island)).thenReturn(java.util.Map.of());
        when(world.deleteIsland(5)).thenReturn(new CompletableFuture<>());

        assertDoesNotThrow(() -> new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").deleteIsland(actor, island).join());
        verify(cache).beginDeletion(island, 5);
        verify(world).deleteIsland(5);
    }

    @Test
    void aFailedDeletionCannotAcknowledgeAnUnfinishedCreateThatStillHasItsOwner() {
        WorldHandler world = mock(WorldHandler.class);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        doThrow(new RuntimeException("transaction rolled back")).when(database).beginIslandDeletion(actor, island);
        when(database.isIslandDeleting(island)).thenReturn(true);
        when(database.getIslandPlayers(island)).thenReturn(java.util.Map.of(UUID.randomUUID(), "owner"));

        assertThrows(CompletionException.class, () -> new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").deleteIsland(actor, island).join());
        verifyNoInteractions(world, cache);
    }

    @Test
    void recoveryClearsOnlyPersistedDeletingRowsAndReportsFailures() {
        WorldHandler world = mock(WorldHandler.class);
        UUID failed = UUID.randomUUID();
        when(database.getDeletingIslands("local")).thenReturn(java.util.Map.of(island, 5, failed, 6));
        when(database.isIslandDeleting(island)).thenReturn(true);
        when(database.isIslandDeleting(failed)).thenReturn(true);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        when(database.getIslandSlot(failed)).thenReturn(Optional.of(6));
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null));
        when(world.deleteIsland(6)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("held")));
        inlineAsync();
        var result = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").retryDeletions().join();
        assertEquals(new CoreHandler.DeletionResult(1, 1), result);
        verify(database).finishIslandDeletion(island);
        verify(database, never()).finishIslandDeletion(failed);
        verify(cache, never()).unload(failed);
    }

    @Test
    void aRetryQueuedDuringDeletionDoesNotClearTheSlotAfterTheRowIsGone() {
        WorldHandler world = mock(WorldHandler.class);
        CompletableFuture<Void> clearing = new CompletableFuture<>();
        when(world.deleteIsland(5)).thenReturn(clearing);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(5));
        when(database.getDeletingIslands("local")).thenReturn(java.util.Map.of(island, 5));
        when(database.isIslandDeleting(island)).thenReturn(false);
        inlineAsync();
        CoreHandler core = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local");
        CompletableFuture<Void> delete = core.deleteIsland(actor, island);
        var retry = core.retryDeletions();
        clearing.complete(null);
        delete.join();
        assertEquals(new CoreHandler.DeletionResult(0, 0), retry.join());
        verify(world, times(1)).deleteIsland(5);
    }

    @Test
    void aFailedPasteRetainsItsRowWhenPhysicalRollbackFails() {
        WorldHandler world = mock(WorldHandler.class);
        UUID owner = UUID.randomUUID();
        when(database.addIslandData(island, owner, "local")).thenReturn(5);
        when(world.deleteIsland(5)).thenReturn(CompletableFuture.completedFuture(null), CompletableFuture.failedFuture(new IllegalStateException("cleanup failed")));
        when(world.createIsland(5)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("paste failed")));
        inlineAsync();
        var created = new CoreHandler(plugin, null, database, null, cache, new LocalIslandTasks(plugin, database, cache, "local"), world, null, "local").createIsland(island, owner);
        assertThrows(CompletionException.class, created::join);
        verify(database).beginIslandDeletion(any(Actor.Bypass.class), eq(island));
        verify(database, never()).finishIslandDeletion(any());
        verify(cache, never()).unload(island);
    }

    @Test
    void committedMutationKeepsItsResultWhenCacheReloadFails() {
        when(database.toggleIslandPvp(actor, island)).thenReturn(true);
        when(cache.reload(island)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload failed")));

        assertTrue(core.toggleIslandPvp(actor, island).join());
        verify(plugin).severe(eq("Island data saved but could not refresh the island cache: " + island), any(Throwable.class));
    }

    @Test
    void mutationRefusedByARuleLeavesTheCacheAlone() {
        IslandDoesNotExistException failure = new IslandDoesNotExistException();
        doThrow(failure).when(database).deleteWarpPoint(actor, island, "shop");

        CompletionException error = assertThrows(CompletionException.class, () -> new WarpHandler(plugin, null, database, null, null, null, new LocalIslandTasks(plugin, database, cache, "local")).delWarp(actor, island, "shop").join());
        assertSame(failure, error.getCause());
        verify(cache, never()).reload(island);
    }

    @Test
    void failedMutationThatMayHaveCommittedKeepsItsErrorWhenCacheReloadAlsoFails() {
        RuntimeException failure = new RuntimeException(new SQLException("connection reset during commit"));
        doThrow(failure).when(database).deleteWarpPoint(actor, island, "shop");
        when(cache.reload(island)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload failed")));

        CompletionException error = assertThrows(CompletionException.class, () -> new WarpHandler(plugin, null, database, null, null, null, new LocalIslandTasks(plugin, database, cache, "local")).delWarp(actor, island, "shop").join());
        assertSame(failure, error.getCause());
        verify(cache).reload(island);
        verify(plugin, never()).severe(anyString(), any(Throwable.class));
    }
}
