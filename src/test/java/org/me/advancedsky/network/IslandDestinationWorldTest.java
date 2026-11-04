package org.me.advancedsky.network;

import net.kyori.adventure.text.Component;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.ServerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.NoActiveServerException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.util.ComponentUtils;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandDestinationWorldTest {
    private final UUID island = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);

    @Test
    void tripsFromTheLobbyAndAnotherIslandServerUseTheDestinationWorldAndKeepTheMessage() {
        Component message = Component.text("Arrived");
        when(database.getIslandServer(island)).thenReturn(Optional.of("sky2"));
        when(messenger.request(eq("sky2"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), any()))
                .thenReturn(CompletableFuture.completedFuture(new JSONObject().put("teleported", true)));
        ServerRegistry servers = mock(ServerRegistry.class);
        when(servers.getIslandServers()).thenReturn(List.of("sky1", "sky2"));
        for (String source : new String[]{"lobby", "sky1"}) {
            new IslandDistributor(mock(AdvancedSky.class), database, null, null, messenger,
                    servers, source).teleportIsland(island, player, "1,100,2,0,0", message).join();
        }
        ArgumentCaptor<JSONObject> payload = ArgumentCaptor.forClass(JSONObject.class);
        verify(messenger, times(2)).request(eq("sky2"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), payload.capture());
        for (JSONObject request : payload.getAllValues()) {
            assertEquals("sky2", request.getString("teleportWorld"));
            assertEquals("1,100,2,0,0", request.getString("teleportLocation"));
            assertEquals(message, ComponentUtils.deserialize(request.getString("successMessage")));
        }
    }

    @Test
    void localIslandTripsAlsoUseTheHostName() {
        TeleportHandler teleports = mock(TeleportHandler.class);
        when(database.getIslandServer(island)).thenReturn(Optional.of("sky1"));
        when(teleports.prepareTeleport(player, "sky1", "1,100,2,0,0"))
                .thenReturn(CompletableFuture.completedFuture(true));
        new IslandDistributor(mock(AdvancedSky.class), database, teleports, null, messenger,
                mock(ServerRegistry.class), "sky1").teleportIsland(island, player, "1,100,2,0,0").join();
        verify(teleports).prepareTeleport(player, "sky1", "1,100,2,0,0");
        verifyNoInteractions(messenger);
    }

    @Test
    void aTripToAnOfflineHostFailsAtOnceWithoutMessagingIt() {
        ServerRegistry servers = mock(ServerRegistry.class);
        when(servers.getIslandServers()).thenReturn(List.of("sky1"));
        when(database.getIslandServer(island)).thenReturn(Optional.of("sky2"));

        var error = assertThrows(CompletionException.class, () -> new IslandDistributor(mock(AdvancedSky.class), database, null, null, messenger,
                servers, "sky1").teleportIsland(island, player, "1,100,2,0,0").join());

        assertInstanceOf(NoActiveServerException.class, error.getCause());
        verifyNoInteractions(messenger);
    }

    @Test
    void aHostThatNeverAnswersIsReportedOffline() {
        ServerRegistry servers = mock(ServerRegistry.class);
        when(servers.getIslandServers()).thenReturn(List.of("sky1", "sky2"));
        when(database.getIslandServer(island)).thenReturn(Optional.of("sky2"));
        when(messenger.request(eq("sky2"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException()));

        var error = assertThrows(CompletionException.class, () -> new IslandDistributor(mock(AdvancedSky.class), database, null, null, messenger,
                servers, "sky1").teleportIsland(island, player, "1,100,2,0,0").join());

        assertInstanceOf(NoActiveServerException.class, error.getCause());
    }
}
