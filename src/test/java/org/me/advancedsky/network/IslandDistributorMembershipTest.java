package org.me.advancedsky.network;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.ServerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandDistributorMembershipTest {
    private final UUID island = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final IslandDistributor distributor = new IslandDistributor(plugin, database, null, null, messenger, mock(ServerRegistry.class), "local");

    @BeforeEach
    void setUp() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
    }

    @Test
    void sameHostAcceptanceAndQuitCleanupCallTheLocalHandlersWithoutMessaging() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        CompletableFuture<Void> joining = new CompletableFuture<>();
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        CompletableFuture<Void> added = distributor.addMember(new Actor.Player(player), island, player, "member", () -> joining);
        CompletableFuture<Void> removed = distributor.removeCoop(new Actor.Bypass("coop quit cleanup"), island, player, () -> cleanup);
        assertFalse(added.isDone());
        assertFalse(removed.isDone());
        joining.complete(null);
        cleanup.complete(null);
        added.join();
        removed.join();
        verifyNoInteractions(messenger);
    }

    @Test
    void invitationAcceptanceAndQuitCleanupUseTheNormalMutationMessages() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("remote"));
        when(messenger.request(eq("remote"), anyString(), any())).thenAnswer(call -> {
            JSONObject payload = call.getArgument(2);
            assertEquals(island.toString(), payload.getString("islandUuid"));
            assertEquals(player.toString(), payload.getString("targetUuid"));
            if (IslandDistributor.ACTION_MEMBER_ADD.equals(call.getArgument(1))) {
                assertEquals(new Actor.Player(player), Actor.fromJson(payload));
                assertEquals("member", payload.getString("role"));
            } else {
                assertEquals(new Actor.Bypass("coop quit cleanup"), Actor.fromJson(payload));
            }
            return CompletableFuture.completedFuture(new JSONObject());
        });
        distributor.addMember(new Actor.Player(player), island, player, "member", () -> { fail("remote invitation ran locally"); return null; }).join();
        distributor.removeCoop(new Actor.Bypass("coop quit cleanup"), island, player, () -> { fail("remote cleanup ran locally"); return null; }).join();
        verify(messenger).request(eq("remote"), eq(IslandDistributor.ACTION_MEMBER_ADD), any());
        verify(messenger).request(eq("remote"), eq(IslandDistributor.ACTION_COOP_REMOVE), any());
    }
}
