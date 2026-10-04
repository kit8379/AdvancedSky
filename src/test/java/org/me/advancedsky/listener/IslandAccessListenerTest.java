package org.me.advancedsky.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.teleport.TeleportHandler;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class IslandAccessListenerTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final TeleportHandler teleports = mock(TeleportHandler.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final Location location = new Location(world, 256, 101, 256);
    private final Component bannedMessage = Component.text("You are banned from this island");
    private final Component lockedMessage = Component.text("This island is locked");
    private final IslandAccessListener listener = new IslandAccessListener(plugin, config, cache, teleports);

    @BeforeEach
    void setUp() {
        when(plugin.getApi()).thenReturn(api);
        when(api.lobby(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(config.getIslandWorldName()).thenReturn("skyblock");
        when(config.getPlayerBannedMessage()).thenReturn(bannedMessage);
        when(config.getIslandLockedMessage()).thenReturn(lockedMessage);
        when(world.getName()).thenReturn("skyblock");
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("Steve");
        when(player.getLocation()).thenReturn(location);
    }

    @Test
    void aJoinOnAFreeSlotIsSentToTheLobby() {
        // World spawn falls on slot 0, which no island ever gets.
        join();

        verify(api).lobby(playerUuid);
        verify(api, never()).sendPlayerMessage(any(), any());
    }

    @Test
    void aLateLoginDestinationRunsBeforeCheckingItsTemporarySpawn() {
        TeleportHandler teleports = mock(TeleportHandler.class);
        when(teleports.hasPendingTeleport(playerUuid)).thenReturn(true);

        new IslandAccessListener(plugin, config, cache, teleports)
                .onPlayerJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));

        verify(api, never()).lobby(any());
        verify(teleports, never()).cancelArrival(any());
        verify(cache, never()).getAt(any());
        verify(player, never()).getLocation();
    }

    @Test
    void eachArrivalCheckRefusesTheGap() {
        IslandAccessListener access = new IslandAccessListener(plugin, config, cache, teleports);

        assertFalse(access.checkArrival(player));
        assertFalse(access.checkArrival(player));

        verify(api, times(2)).lobby(playerUuid);
        verify(teleports, times(2)).cancelArrival(playerUuid);
    }

    @Test
    void anOperatorMayStayInTheGap() {
        when(player.isOp()).thenReturn(true);

        join();

        verify(api, never()).lobby(any());
    }

    @Test
    void anOperatorCannotJoinAnIslandBeingDeleted() {
        when(player.isOp()).thenReturn(true);
        when(cache.isDeletingAt(location)).thenReturn(true);
        join();
        verify(api).lobby(playerUuid);
    }

    @Test
    void anIslandWhoseCacheEntryIsUnavailableFailsClosed() {
        when(cache.getIslandUuidAt(location)).thenReturn(UUID.randomUUID());
        TeleportHandler teleports = mock(TeleportHandler.class);
        IslandAccessListener access = new IslandAccessListener(plugin, config, cache, teleports);

        assertFalse(access.checkArrival(player));

        // The lobby request is asynchronous: cancel the arrival confirmation before routing it.
        var order = inOrder(teleports, api);
        order.verify(teleports).cancelArrival(playerUuid);
        order.verify(api).lobby(playerUuid);
        order.verify(api).sendPlayerMessage(playerUuid, lockedMessage);
        verify(plugin).severe("Cannot check island access for player " + playerUuid
                + ": island cache entry unavailable at " + location);
    }

    @Test
    void bannedPlayersAreSentToTheLobby() {
        Island island = new Island(UUID.randomUUID(), 1, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(playerUuid), Map.of(), null, 1, 1);
        when(cache.getIslandUuidAt(location)).thenReturn(island.getIslandUuid());
        when(cache.getAt(location)).thenReturn(island);

        join();

        verify(api).lobby(playerUuid);
    }

    @Test
    void aWorldChangeOntoABannedIslandSendsThePlayerToTheLobby() {
        Island island = new Island(UUID.randomUUID(), 1, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(playerUuid), Map.of(), null, 1, 1);
        when(cache.getAt(location)).thenReturn(island);
        new IslandAccessListener(plugin, config, cache, teleports)
                .onWorldChange(new PlayerChangedWorldEvent(player, mock(World.class)));

        verify(teleports).cancelArrival(playerUuid);
        verify(api).lobby(playerUuid);
    }

    @Test
    void aWorldChangeIntoTheGapSendsThePlayerToTheLobbySilently() {
        new IslandAccessListener(plugin, config, cache, teleports)
                .onWorldChange(new PlayerChangedWorldEvent(player, mock(World.class)));

        verify(api).lobby(playerUuid);
        verify(api, never()).sendPlayerMessage(any(), any());
    }

    @Test
    void aFailedLobbyRequestIsReported() {
        IllegalStateException error = new IllegalStateException("No lobby server is online");
        when(api.lobby(playerUuid)).thenReturn(CompletableFuture.failedFuture(error));

        join();

        verify(plugin).severe(lobbyFailureMessage(), error);
    }

    @Test
    void membersStay() {
        Island island = new Island(UUID.randomUUID(), 1, true, false, UUID.randomUUID(), Set.of(playerUuid), Set.of(), Set.of(), Map.of(), null, 1, 1);
        when(cache.getIslandUuidAt(location)).thenReturn(island.getIslandUuid());
        when(cache.getAt(location)).thenReturn(island);

        TeleportHandler teleports = mock(TeleportHandler.class);
        IslandAccessListener access = new IslandAccessListener(plugin, config, cache, teleports);

        access.onPlayerJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
        assertTrue(access.checkArrival(player));

        verify(api, never()).lobby(any());
        verify(teleports, never()).cancelArrival(any());
    }

    @Test
    void otherWorldsAreNotSubjectToIslandAccess() {
        when(world.getName()).thenReturn("lobby");

        assertTrue(listener.checkArrival(player));

        verifyNoInteractions(cache, api, teleports);
    }

    @Test
    void aLobbyOnlyServerDoesNotTreatItsNamedWorldAsAnIslandWorld() {
        when(config.isLobbyOnly()).thenReturn(true);

        join();
        assertTrue(listener.checkArrival(player));

        verifyNoInteractions(cache, api);
        verify(teleports, never()).cancelArrival(any());
        verify(player, never()).getLocation();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void anUnlockedIslandAllowsEveryRole(Role role) {
        standOn(islandFor(role, false, false));

        assertTrue(listener.checkArrival(player));

        verifyNoInteractions(api, teleports);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "MEMBER", "COOP"})
    void aLockedIslandAllowsItsOwnerMembersAndCoops(Role role) {
        standOn(islandFor(role, true, false));

        assertTrue(listener.checkArrival(player));

        verifyNoInteractions(api, teleports);
    }

    @Test
    void aLockedIslandRejectsVisitorsWithTheLockedMessage() {
        standOn(islandFor(Role.VISITOR, true, false));

        assertFalse(listener.checkArrival(player));

        verify(teleports).cancelArrival(playerUuid);
        verify(api).lobby(playerUuid);
        verify(api).sendPlayerMessage(playerUuid, lockedMessage);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aBanOverridesLockAndEveryIslandRole(Role role) {
        standOn(islandFor(role, true, true));

        assertFalse(listener.checkArrival(player));

        verify(api).lobby(playerUuid);
        verify(api).sendPlayerMessage(playerUuid, bannedMessage);
        verify(api, never()).sendPlayerMessage(playerUuid, lockedMessage);
    }

    @Test
    void anOperatorMayEnterALockedIslandEvenWhenBanned() {
        when(player.isOp()).thenReturn(true);
        standOn(islandFor(Role.VISITOR, true, true));

        assertTrue(listener.checkArrival(player));

        verifyNoInteractions(api, teleports);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionRejectsPlayersAndOperatorsBeforeAnyCachedAccessRule(boolean operator) {
        when(player.isOp()).thenReturn(operator);
        when(cache.isDeletingAt(location)).thenReturn(true);

        assertFalse(listener.checkArrival(player));

        verify(api).lobby(playerUuid);
        verify(api).sendPlayerMessage(playerUuid, lockedMessage);
        verify(cache, never()).getAt(any());
    }

    @Test
    void aSynchronousLobbyFailureIsReportedAndDoesNotAllowArrival() {
        IllegalStateException error = new IllegalStateException("Lobby executor is stopped");
        when(api.lobby(playerUuid)).thenThrow(error);
        standOn(islandFor(Role.VISITOR, true, false));

        assertFalse(listener.checkArrival(player));

        verify(teleports).cancelArrival(playerUuid);
        verify(plugin).severe(lobbyFailureMessage(), error);
        verify(api).sendPlayerMessage(playerUuid, lockedMessage);
    }

    @Test
    void anAsynchronousLobbyFailureDoesNotReadPlayerStateOffThread() {
        CompletableFuture<Void> lobby = new CompletableFuture<>();
        when(api.lobby(playerUuid)).thenReturn(lobby);
        IllegalStateException error = new IllegalStateException("Proxy request failed");
        assertFalse(listener.checkArrival(player));
        clearInvocations(player);

        CompletableFuture.runAsync(() -> lobby.completeExceptionally(error)).join();

        verifyNoInteractions(player);
        verify(plugin).severe(lobbyFailureMessage(), error);
    }

    @Test
    void aFailedLobbyRequestCanBeRequestedAgainOnTheNextArrivalCheck() {
        when(api.lobby(playerUuid)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Offline lobby")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertFalse(listener.checkArrival(player));
        assertFalse(listener.checkArrival(player));

        verify(api, times(2)).lobby(playerUuid);
        verify(teleports, times(2)).cancelArrival(playerUuid);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDeniedLoginDestinationCannotAnnounceTeleportSuccess(boolean selectedAtSpawn) {
        TeleportHandler handler = new TeleportHandler();
        IslandAccessListener access = new IslandAccessListener(plugin, config, cache, handler);
        handler.setArrivalCheck(access::checkArrival);
        Component successMessage = Component.text("Arrived at island");
        handler.addPendingTeleport(playerUuid, location, successMessage);
        if (selectedAtSpawn) {
            handler.takeSpawnTeleport(playerUuid);
            standOn(islandFor(Role.VISITOR, false, true));
        }
        access.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
        if (!selectedAtSpawn) {
            verifyNoInteractions(api);
            var arrival = handler.claimJoinTeleport(playerUuid);
            standOn(islandFor(Role.VISITOR, false, true));
            when(player.isOnline()).thenReturn(true);
            handler.confirmArrival(player, arrival.pendingTeleport());
        }

        verify(api).lobby(playerUuid);
        verify(api).sendPlayerMessage(playerUuid, bannedMessage);
        verify(player, never()).sendMessage(successMessage);
        assertFalse(handler.hasPendingTeleport(playerUuid));
        assertNull(handler.claimJoinTeleport(playerUuid));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAllowedLoginDestinationConfirmsItsTeleportOnce(boolean selectedAtSpawn) {
        TeleportHandler handler = new TeleportHandler();
        IslandAccessListener access = new IslandAccessListener(plugin, config, cache, handler);
        handler.setArrivalCheck(access::checkArrival);
        Component successMessage = Component.text("Arrived at island");
        handler.addPendingTeleport(playerUuid, location, successMessage);
        if (selectedAtSpawn) {
            handler.takeSpawnTeleport(playerUuid);
            standOn(islandFor(Role.MEMBER, true, false));
        }
        access.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
        var arrival = handler.claimJoinTeleport(playerUuid);
        standOn(islandFor(Role.MEMBER, true, false));
        when(player.isOnline()).thenReturn(true);

        handler.confirmArrival(player, arrival.pendingTeleport());
        handler.confirmArrival(player, arrival.pendingTeleport());

        verifyNoInteractions(api);
        verify(player).sendMessage(successMessage);
    }

    @Test
    void anArrivalWithoutALoadedWorldIsAnExplicitError() {
        when(player.getLocation()).thenReturn(new Location(null, 256, 101, 256));

        NullPointerException error = assertThrows(NullPointerException.class, () -> listener.checkArrival(player));

        assertEquals("Player arrival world is not loaded", error.getMessage());
        verifyNoInteractions(cache, api, teleports);
    }

    @Test
    void aRequiredDenialMessageCannotBeSilentlyOmitted() {
        standOn(islandFor(Role.VISITOR, false, true));
        when(config.getPlayerBannedMessage()).thenReturn(null);

        NullPointerException error = assertThrows(NullPointerException.class, () -> listener.checkArrival(player));

        assertEquals("Player banned message is missing", error.getMessage());
    }

    private Island islandFor(Role role, boolean locked, boolean banned) {
        return new Island(UUID.randomUUID(), 1, locked, false,
                role == Role.OWNER ? playerUuid : UUID.randomUUID(),
                role == Role.MEMBER ? Set.of(playerUuid) : Set.of(),
                role == Role.COOP ? Set.of(playerUuid) : Set.of(),
                banned ? Set.of(playerUuid) : Set.of(), Map.of(), null, 1, 1);
    }

    private void standOn(Island island) {
        when(cache.getIslandUuidAt(location)).thenReturn(island.getIslandUuid());
        when(cache.getAt(location)).thenReturn(island);
    }

    private String lobbyFailureMessage() {
        return "Failed to send player Steve (" + playerUuid + ") to the lobby after denied island access";
    }

    private enum Role {
        OWNER, MEMBER, COOP, VISITOR
    }

    private void join() {
        new IslandAccessListener(plugin, config, cache, teleports).onPlayerJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()));
    }
}
