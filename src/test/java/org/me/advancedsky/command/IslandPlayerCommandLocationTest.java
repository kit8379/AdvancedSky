package org.me.advancedsky.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.api.PlayerActions;
import org.me.advancedsky.config.ConfigHandler;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

class IslandPlayerCommandLocationTest {
    private final UUID playerId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final PlayerActions actions = mock(PlayerActions.class);
    private final ConfigHandler config = mock(ConfigHandler.class, call -> call.getMethod().getReturnType() == String[].class ? new String[0] : RETURNS_DEFAULTS.answer(call));
    private final Component mustReturn = Component.text("Return to your island");
    private final Location location = mock(Location.class);
    private IslandPlayerCommand command;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(location);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(config.getPlayerMustOnOwnIslandMessage()).thenReturn(mustReturn);
        when(config.getPlayerBanAliases()).thenReturn(new String[]{"deny"});
        when(api.player(playerId)).thenReturn(actions);
        when(api.getPlayerUuid("Target")).thenReturn(CompletableFuture.completedFuture(Optional.of(targetId)));
        when(actions.banPlayer(targetId)).thenReturn(CompletableFuture.completedFuture(null));
        command = new IslandPlayerCommand(mock(AdvancedSky.class), api, config);
    }

    @Test
    void managementCommandsAndTheirAliasesAreRejectedBeforePlayerLookupOutsideTheIsland() {
        command.onCommand(player, mock(Command.class), "is", new String[]{"ban", "Target"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"deny", "Target"});
        verify(player, times(2)).sendMessage(mustReturn);
        verify(api, never()).getPlayerUuid(anyString());
        verifyNoInteractions(actions);
    }

    @Test
    void anOwnerOrMemberOnTheirIslandCanUseManagementAliases() {
        when(api.isOnOwnIsland(playerId, location)).thenReturn(true);
        command.onCommand(player, mock(Command.class), "is", new String[]{"deny", "Target"});
        verify(actions).banPlayer(targetId);
        verify(player, never()).sendMessage(mustReturn);
    }

    @Test
    void homeAndLobbyStillWorkFromOutsideTheIsland() {
        when(actions.home("default")).thenReturn(CompletableFuture.completedFuture(null));
        when(api.lobby(playerId)).thenReturn(CompletableFuture.completedFuture(null));
        command.onCommand(player, mock(Command.class), "is", new String[]{"home"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"lobby"});
        verify(actions).home("default");
        verify(api).lobby(playerId);
        verify(api, never()).isOnOwnIsland(any(), any());
    }
}
