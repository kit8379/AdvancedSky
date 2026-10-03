package org.me.advancedsky.network;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandNotHostedHereException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandDistributorMembershipTest {
    private final UUID island = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final IslandDistributor distributor = new IslandDistributor(mock(AdvancedSky.class), database, null, null, messenger, List.of("local", "remote"), "local");

    @Test
    void sameHostAcceptanceAndQuitCleanupCallTheLocalHandlersWithoutMessaging() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        CompletableFuture<Void> joining = new CompletableFuture<>();
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        assertSame(joining, distributor.acceptMember(island, player, () -> joining));
        assertSame(cleanup, distributor.removeCoopAfterQuit(island, player, () -> cleanup));
        verifyNoInteractions(messenger);
    }

    @Test
    void invitationAcceptanceAndQuitCleanupStillReachTheHostWithNarrowPayloads() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("remote"));
        when(messenger.requestVoid(eq("remote"), anyString(), any())).thenAnswer(call -> {
            JSONObject payload = call.getArgument(2);
            assertEquals(island.toString(), payload.getString("islandUuid"));
            assertEquals(player.toString(), payload.getString("playerUuid"));
            assertEquals(2, payload.length());
            return CompletableFuture.completedFuture(null);
        });
        distributor.acceptMember(island, player, () -> { fail("remote invitation ran locally"); return null; }).join();
        distributor.removeCoopAfterQuit(island, player, () -> { fail("remote cleanup ran locally"); return null; }).join();
        verify(messenger).requestVoid(eq("remote"), eq(IslandDistributor.ACTION_INVITATION_ACCEPT), any());
        verify(messenger).requestVoid(eq("remote"), eq(IslandDistributor.ACTION_COOP_QUIT), any());
    }
}
