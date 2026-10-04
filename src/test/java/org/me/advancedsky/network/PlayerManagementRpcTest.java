package org.me.advancedsky.network;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.CannotExpelIslandPlayerException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.NotIslandOwnerException;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.CoopHandler;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.island.PlayerHandler;
import org.me.advancedsky.island.WarpHandler;
import org.me.advancedsky.messaging.CrossServerMessageHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerManagementRpcTest {
    private final UUID island = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final Actor actor = new Actor.Player(player);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler sourceDatabase = mock(DatabaseHandler.class);
    private final DatabaseHandler hostDatabase = mock(DatabaseHandler.class);
    private final WorldHandler world = mock(WorldHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final OnlinePlayerRegistry online = mock(OnlinePlayerRegistry.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final Map<String, CrossServerMessageHandler> handlers = new HashMap<>();
    private final IslandDistributor distributor = new IslandDistributor(plugin, sourceDatabase, null, null, messenger, null, "lobby");
    private final WarpHandler warps = new WarpHandler(plugin, null, sourceDatabase, distributor, null, null, null);
    private final PlayerHandler members = new PlayerHandler(plugin, sourceDatabase, distributor, null, null, null);
    private final CoreHandler core = new CoreHandler(plugin, null, sourceDatabase, distributor, null, null);
    private final CoopHandler coops = new CoopHandler(plugin, sourceDatabase, distributor, null);
    private final HomeHandler homes = new HomeHandler(plugin, null, sourceDatabase, distributor, null, null, null);

    @BeforeEach
    void setUp() throws Exception {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(sourceDatabase.getIslandServer(island)).thenReturn(Optional.of("host"));
        when(hostDatabase.getIslandServer(island)).thenReturn(Optional.of("host"));
        when(hostDatabase.getIslandSlot(island)).thenReturn(Optional.of(4));
        when(hostDatabase.getIslandPlayers(island)).thenReturn(Map.of(player, "owner"));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        when(online.isOnline(visitor)).thenReturn(true);
        when(world.removePlayerFromIsland(4, visitor)).thenReturn(CompletableFuture.completedFuture(true));

        IslandOperator operator = new IslandOperator(plugin, hostDatabase, world, cache, null, null, online, "host");
        CrossServerMessenger hostMessenger = mock(CrossServerMessenger.class);
        doAnswer(call -> { handlers.put(call.getArgument(0), call.getArgument(1)); return null; }).when(hostMessenger).register(anyString(), any());
        var register = AdvancedSky.class.getDeclaredMethod("registerCrossServerHandlers", CrossServerMessenger.class, IslandOperator.class, TeleportHandler.class);
        register.setAccessible(true);
        register.invoke(plugin, hostMessenger, operator, null);
        when(messenger.request(eq("host"), anyString(), any())).thenAnswer(call -> {
            JSONObject payload = call.getArgument(2);
            assertEquals(island.toString(), payload.getString("islandUuid"));
            return handlers.get(call.<String>getArgument(1)).handle(payload);
        });
    }

    @Test
    void deletingAWarpFromTheLobbyWritesAndRefreshesOnlyTheHost() {
        warps.delWarp(actor, island, "SHOP").join();
        verify(hostDatabase).deleteWarpPoint(actor, island, "shop");
        verify(cache).reload(island);
        verifyNoInteractions(world);
        verify(sourceDatabase, never()).deleteWarpPoint(any(), any(), anyString());
    }

    @Test
    void expellingFromTheLobbyRemovesTheVisitorOnTheHost() {
        members.expelPlayer(actor, island, visitor).join();
        verify(world).removePlayerFromIsland(4, visitor);
        verifyNoInteractions(cache);
    }

    @Test
    void acceptingAnInvitationUsesAddMemberOnTheHostWithTheInvitedPlayersIdentity() {
        members.addMember(actor, island, player, "member").join();
        verify(hostDatabase).addIslandPlayer(actor, island, player, "member");
        verify(cache).reload(island);
        verify(messenger).request(eq("host"), eq(IslandDistributor.ACTION_MEMBER_ADD), any());
    }

    @Test
    void quitCleanupUsesRemoveCoopOnTheHostAndPreservesTheBypassSource() {
        when(sourceDatabase.getCoopIslandsOfPlayer(visitor)).thenReturn(Set.of(island));
        coops.deleteAllCoopOfPlayer(visitor).join();
        verify(hostDatabase).deleteCoopPlayer(new Actor.Bypass("coop quit cleanup"), island, visitor);
        verify(world).removePlayerFromIsland(4, visitor);
        verify(cache).reload(island);
        verify(messenger).request(eq("host"), eq(IslandDistributor.ACTION_COOP_REMOVE), any());
    }

    @Test
    void remoteAdministrativeHomeDeletionKeepsTheTargetPlayerSeparateFromTheActor() {
        homes.delHome(new Actor.Bypass("console"), island, visitor, "FARM").join();
        verify(hostDatabase).deleteHomePoint(island, visitor, "farm");
        verify(cache).reload(island);
    }

    @Test
    void unknownActorTypesAreRejectedBeforeWriting() {
        JSONObject payload = new JSONObject().put("islandUuid", island.toString()).put("pointName", "shop")
                .put(Actor.FIELD, new JSONObject().put("type", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> handlers.get(IslandDistributor.ACTION_WARP_DELETE).handle(payload));
        verify(hostDatabase, never()).deleteWarpPoint(any(), any(), anyString());
    }

    @Test
    void remoteExpelStillCannotRemoveAnIslandMember() {
        when(hostDatabase.getIslandPlayers(island)).thenReturn(Map.of(player, "owner", visitor, "member"));
        Throwable cause = assertThrows(CompletionException.class, () -> members.expelPlayer(actor, island, visitor).join()).getCause();
        assertInstanceOf(CannotExpelIslandPlayerException.class, cause);
        verifyNoInteractions(world, cache);
    }

    @Test
    void togglesReturnTheNewHostStateIncludingFalse() {
        when(hostDatabase.toggleIslandLock(actor, island)).thenReturn(false);
        when(hostDatabase.toggleIslandPvp(actor, island)).thenReturn(true);
        assertFalse(core.toggleIslandLock(actor, island).join());
        assertTrue(core.toggleIslandPvp(actor, island).join());
        verify(cache, times(2)).reload(island);
    }

    @Test
    void remoteDeletionPreservesOwnerAuthorization() {
        doThrow(new NotIslandOwnerException()).when(hostDatabase).beginIslandDeletion(actor, island);
        Throwable cause = assertThrows(CompletionException.class, () -> core.deleteIsland(actor, island).join()).getCause();
        assertInstanceOf(NotIslandOwnerException.class, cause);
        verify(hostDatabase).beginIslandDeletion(actor, island);
        verifyNoInteractions(world, cache);
    }

    @Test
    void missingActorIdentityCannotBecomeAnAdministrativeRequest() {
        JSONObject payload = new JSONObject().put("islandUuid", island.toString()).put("pointName", "shop");
        assertThrows(RuntimeException.class, () -> handlers.get(IslandDistributor.ACTION_WARP_DELETE).handle(payload));
        verify(hostDatabase, never()).deleteWarpPoint(any(), any(), anyString());
    }

    @Test
    void missingHostFailsWithoutSendingOrChangingAnIsland() {
        when(sourceDatabase.getIslandServer(island)).thenReturn(Optional.empty());
        Throwable cause = assertThrows(CompletionException.class, () -> warps.delWarp(actor, island, "shop").join()).getCause();
        assertInstanceOf(IslandDoesNotExistException.class, cause);
        verify(messenger, never()).request(anyString(), anyString(), any());
        verifyNoInteractions(world, cache);
    }
}
