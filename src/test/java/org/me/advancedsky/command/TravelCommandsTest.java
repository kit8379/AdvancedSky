package org.me.advancedsky.command;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdminActions;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.api.PlayerActions;
import org.me.advancedsky.command.admin.AdminHomeCommand;
import org.me.advancedsky.command.player.PlayerCreateIslandCommand;
import org.me.advancedsky.command.player.PlayerHomeCommand;
import org.me.advancedsky.command.player.PlayerLobbyCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.TeleportCancelledException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TravelCommandsTest {
    private final UUID playerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final Player player = mock(Player.class);
    private final PlayerActions actions = mock(PlayerActions.class);
    private final AdminActions admin = mock(AdminActions.class);
    private final Component homeMessage = Component.text("Teleported to home garden");
    private final Component defaultHomeMessage = Component.text("Teleported to home default");
    private final Component lobbyMessage = Component.text("Teleported to lobby");
    private final Component adminHomeMessage = Component.text("Teleported to Owner's home garden");

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(api.player(playerId)).thenReturn(actions);
        when(api.admin(player)).thenReturn(admin);
        when(api.getPlayerUuid("Owner")).thenReturn(CompletableFuture.completedFuture(Optional.of(ownerId)));
        when(api.getPlayerUuid("Traveler")).thenReturn(CompletableFuture.completedFuture(Optional.of(travelerId)));
        when(config.getPlayerHomeSuccessMessage("garden")).thenReturn(homeMessage);
        when(config.getPlayerHomeSuccessMessage("default")).thenReturn(defaultHomeMessage);
        when(config.getPlayerLobbySuccessMessage()).thenReturn(lobbyMessage);
        when(config.getAdminHomeSuccessMessage("Owner", "garden")).thenReturn(adminHomeMessage);
    }

    @Test
    void playerHomePassesTheMessageBeforeTransferAndDoesNotSendItAtDispatchCompletion() {
        CompletableFuture<Void> dispatch = new CompletableFuture<>();
        when(actions.home("garden", homeMessage)).thenReturn(dispatch);

        assertTrue(new PlayerHomeCommand(plugin, api, config).execute(player, new String[]{"home", "garden"}));
        verify(actions).home("garden", homeMessage);
        verify(api, never()).sendPlayerMessage(any(), any());

        dispatch.complete(null);
        verify(api, never()).sendPlayerMessage(any(), any());
        verify(player, never()).sendMessage(homeMessage);
    }

    @Test
    void defaultHomeAlsoCarriesItsConfiguredMessage() {
        when(actions.home("default", defaultHomeMessage)).thenReturn(CompletableFuture.completedFuture(null));

        new PlayerHomeCommand(plugin, api, config).execute(player, new String[]{"home"});

        verify(actions).home("default", defaultHomeMessage);
        verify(api, never()).sendPlayerMessage(any(), any());
    }

    @Test
    void creationKeepsItsCreateConfirmationAndCarriesTheHomeConfirmationWithTravel() {
        CompletableFuture<Void> create = new CompletableFuture<>();
        Component created = Component.text("Island created");
        when(actions.createIsland()).thenReturn(create);
        when(actions.home("default", defaultHomeMessage)).thenReturn(CompletableFuture.completedFuture(null));
        when(config.getPlayerCreateSuccessMessage()).thenReturn(created);

        new PlayerCreateIslandCommand(plugin, api, config).execute(player, new String[]{"create"});
        verify(actions, never()).home(anyString(), any());

        create.complete(null);
        verify(player).sendMessage(created);
        verify(actions).home("default", defaultHomeMessage);
        verify(api, never()).sendPlayerMessage(any(), any());
    }

    @Test
    void adminHomeCarriesTheMessageForSelfAndNamedTraveler() {
        when(admin.home(ownerId, "garden", playerId, adminHomeMessage)).thenReturn(CompletableFuture.completedFuture(null));
        when(admin.home(ownerId, "garden", travelerId, adminHomeMessage)).thenReturn(CompletableFuture.completedFuture(null));
        AdminHomeCommand command = new AdminHomeCommand(plugin, api, config);

        command.execute(player, new String[]{"home", "Owner", "garden"});
        command.execute(player, new String[]{"home", "Owner", "garden", "Traveler"});

        verify(admin).home(ownerId, "garden", playerId, adminHomeMessage);
        verify(admin).home(ownerId, "garden", travelerId, adminHomeMessage);
        verify(api, never()).sendPlayerMessage(any(), any());
    }

    @Test
    void lobbyCarriesTheConfiguredMessageWithoutASourceServerSuccessSend() {
        when(api.lobby(playerId, lobbyMessage)).thenReturn(CompletableFuture.completedFuture(null));

        new PlayerLobbyCommand(plugin, api, config).execute(player, new String[]{"lobby"});

        verify(api).lobby(playerId, lobbyMessage);
        verify(api, never()).sendPlayerMessage(any(), any());
    }

    @Test
    void cancelledHomePreservesTheExistingQuietErrorHandling() {
        when(actions.home("garden", homeMessage)).thenReturn(CompletableFuture.failedFuture(new CompletionException(new TeleportCancelledException())));

        new PlayerHomeCommand(plugin, api, config).execute(player, new String[]{"home", "garden"});

        verify(api, never()).sendPlayerMessage(any(), any());
        verify(player, never()).sendMessage(any(Component.class));
        verifyNoInteractions(plugin);
    }
}
