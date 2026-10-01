package org.me.advancedsky.network;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.CreateReservations;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandAlreadyExistException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.thread.BukkitAsyncExecutor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandDistributorCreateTest {
    private final UUID island = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandOperator operator = mock(IslandOperator.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final CreateReservations reservations = mock(CreateReservations.class);
    private final List<String> servers = List.of("local", "remote");
    private final IslandDistributor distributor = new IslandDistributor(plugin, database, operator, null, messenger, reservations, servers, "local");

    IslandDistributorCreateTest() {
        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        when(database.getIslandCountByServer()).thenReturn(Map.of("local", 3, "remote", 2));
    }

    @Test
    void aCreateGoesWhereItIsReservedAndReleasesItOnceDone() {
        when(reservations.reserve(servers, Map.of("local", 3, "remote", 2), island)).thenReturn("remote");
        when(messenger.requestVoid(eq("remote"), eq(IslandDistributor.ACTION_ISLAND_CREATE), any())).thenReturn(CompletableFuture.completedFuture(null));

        distributor.createIsland(island, owner).join();

        verify(reservations).release("remote", island);
        verifyNoInteractions(operator);
    }

    @Test
    void aFailedCreateReleasesItsReservationAndKeepsItsCause() {
        when(reservations.reserve(any(), any(), eq(island))).thenReturn("local");
        when(operator.createIsland(island, owner)).thenReturn(CompletableFuture.failedFuture(new IslandAlreadyExistException()));

        CompletionException error = assertThrows(CompletionException.class, () -> distributor.createIsland(island, owner).join());

        assertInstanceOf(IslandAlreadyExistException.class, error.getCause());
        verify(reservations).release("local", island);
    }

    @Test
    void aReleaseThatFailsLeavesTheCreateDone() {
        when(reservations.reserve(any(), any(), eq(island))).thenReturn("local");
        when(operator.createIsland(island, owner)).thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new RuntimeException("redis down")).when(reservations).release("local", island);

        assertDoesNotThrow(() -> distributor.createIsland(island, owner).join());
    }
}
