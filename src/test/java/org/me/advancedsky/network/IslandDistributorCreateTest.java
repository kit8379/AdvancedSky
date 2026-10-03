package org.me.advancedsky.network;

import org.junit.jupiter.api.Test;
import org.json.JSONObject;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandAlreadyExistException;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;

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
    private final CoreHandler core = mock(CoreHandler.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final List<String> servers = List.of("local", "remote");
    private final IslandDistributor distributor = new IslandDistributor(plugin, database, null, null, messenger, servers, "local");

    @Test
    void creationChoosesTheLeastLoadedConfiguredServerFromTheDatabase() {
        when(database.getIslandCountByServer()).thenReturn(Map.of("local", 3, "remote", 2, "unlisted", 0));
        CompletableFuture<Void> created = new CompletableFuture<>();
        when(messenger.requestVoid(eq("remote"), eq(IslandDistributor.ACTION_ISLAND_CREATE), any())).thenAnswer(call -> {
            JSONObject payload = call.getArgument(2);
            assertEquals(island.toString(), payload.getString("islandUuid"));
            assertEquals(owner.toString(), payload.getString("ownerUuid"));
            return created;
        });

        assertSame(created, distributor.createIsland(island, owner, () -> core.createIsland(island, owner)));

        verify(messenger).requestVoid(eq("remote"), eq(IslandDistributor.ACTION_ISLAND_CREATE), any());
        verifyNoInteractions(core);
    }

    @Test
    void aConfiguredServerMissingFromTheCountsHasZeroIslands() {
        when(database.getIslandCountByServer()).thenReturn(Map.of("remote", 2));
        CompletableFuture<Void> created = new CompletableFuture<>();
        when(core.createIsland(island, owner)).thenReturn(created);

        assertSame(created, distributor.createIsland(island, owner, () -> core.createIsland(island, owner)));

        verify(core).createIsland(island, owner);
        verifyNoInteractions(messenger);
    }

    @Test
    void equalCountsUseTheFirstServerInConfigOrder() {
        when(database.getIslandCountByServer()).thenReturn(Map.of("local", 2, "remote", 2));
        IslandDistributor reordered = new IslandDistributor(plugin, database, null, null, messenger, List.of("remote", "local"), "local");
        when(messenger.requestVoid(eq("remote"), eq(IslandDistributor.ACTION_ISLAND_CREATE), any())).thenReturn(CompletableFuture.completedFuture(null));

        reordered.createIsland(island, owner, () -> core.createIsland(island, owner)).join();

        verify(messenger).requestVoid(eq("remote"), eq(IslandDistributor.ACTION_ISLAND_CREATE), any());
        verifyNoInteractions(core);
    }

    @Test
    void aFailedLocalCreateKeepsItsCause() {
        when(database.getIslandCountByServer()).thenReturn(Map.of("local", 1, "remote", 2));
        when(core.createIsland(island, owner)).thenReturn(CompletableFuture.failedFuture(new IslandAlreadyExistException()));

        CompletionException error = assertThrows(CompletionException.class, () -> distributor.createIsland(island, owner, () -> core.createIsland(island, owner)).join());

        assertInstanceOf(IslandAlreadyExistException.class, error.getCause());
        verifyNoInteractions(messenger);
    }

    @Test
    void aFailedRemoteCreateKeepsItsCause() {
        when(database.getIslandCountByServer()).thenReturn(Map.of("local", 3, "remote", 2));
        RuntimeException failure = new IllegalStateException("host unavailable");
        when(messenger.requestVoid(eq("remote"), eq(IslandDistributor.ACTION_ISLAND_CREATE), any())).thenReturn(CompletableFuture.failedFuture(failure));

        CompletionException error = assertThrows(CompletionException.class, () -> distributor.createIsland(island, owner, () -> core.createIsland(island, owner)).join());

        assertSame(failure, error.getCause());
        verifyNoInteractions(core);
    }

    @Test
    void anEmptyServerListCannotCreateAnIsland() {
        when(database.getIslandCountByServer()).thenReturn(Map.of());
        IslandDistributor empty = new IslandDistributor(plugin, database, null, null, messenger, List.of(), "local");

        CompletionException error = assertThrows(CompletionException.class, () -> empty.createIsland(island, owner, () -> core.createIsland(island, owner)).join());

        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals("servers.island lists no island server", error.getCause().getMessage());
        verifyNoInteractions(core, messenger);
    }
}
