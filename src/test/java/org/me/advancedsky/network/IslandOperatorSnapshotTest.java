package org.me.advancedsky.network;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.snapshot.IslandSnapshot;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandOperatorSnapshotTest {
    private final UUID island = UUID.randomUUID();
    private final Actor actor = new Actor.Bypass("test");
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandSnapshot snapshots = mock(IslandSnapshot.class);
    private final IslandOperator operator = new IslandOperator(plugin, database, null, null, snapshots, "local");

    @Test
    void committedMutationKeepsItsResultWhenSnapshotReloadFails() {
        when(database.toggleIslandPvp(actor, island)).thenReturn(true);
        when(snapshots.reload(island)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload failed")));

        assertTrue(operator.toggleIslandPvp(actor, island).join());
        verify(plugin).severe(eq("Island data saved but could not refresh the snapshot: " + island), any(Throwable.class));
    }

    @Test
    void rejectedMutationKeepsItsOriginalErrorWhenSnapshotReloadAlsoFails() {
        IslandDoesNotExistException failure = new IslandDoesNotExistException();
        doThrow(failure).when(database).deleteWarpPoint(actor, island, "shop");
        when(snapshots.reload(island)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("reload failed")));

        CompletionException error = assertThrows(CompletionException.class, () -> operator.deleteWarp(actor, island, "shop").join());
        assertSame(failure, error.getCause());
        verify(snapshots).reload(island);
        verifyNoInteractions(plugin);
    }
}
