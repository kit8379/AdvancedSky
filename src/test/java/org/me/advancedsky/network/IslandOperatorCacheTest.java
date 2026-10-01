package org.me.advancedsky.network;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
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
