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
        when(config.getPlayerUpgradeAliases()).thenReturn(new String[]{"up"});
        when(api.player(playerId)).thenReturn(actions);
        when(api.getPlayerUuid("Target")).thenReturn(CompletableFuture.completedFuture(Optional.of(targetId)));
        when(actions.banPlayer(targetId)).thenReturn(CompletableFuture.completedFuture(null));
        command = new IslandPlayerCommand(mock(AdvancedSky.class), api, config);
    }

    @Test
    void managementCommandsAndTheirAliasesWorkOutsideTheIsland() {
        command.onCommand(player, mock(Command.class), "is", new String[]{"ban", "Target"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"deny", "Target"});
        verify(actions, times(2)).banPlayer(targetId);
        verify(player, never()).sendMessage(mustReturn);
        verify(api, never()).isOnOwnIsland(any(), any());
    }

    @Test
    void anOwnerOrMemberOnTheirIslandCanUseManagementAliases() {
        when(api.isOnOwnIsland(playerId, location)).thenReturn(true);
        command.onCommand(player, mock(Command.class), "is", new String[]{"deny", "Target"});
        verify(actions).banPlayer(targetId);
        verify(player, never()).sendMessage(mustReturn);
    }

    @Test
    void delwarpAndExpelWorkOutsideTheIsland() {
        when(actions.deleteWarp("shop")).thenReturn(CompletableFuture.completedFuture(null));
        when(actions.expelPlayer(targetId)).thenReturn(CompletableFuture.completedFuture(null));
        command.onCommand(player, mock(Command.class), "is", new String[]{"delwarp", "shop"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"expel", "Target"});
        verify(actions).deleteWarp("shop");
        verify(actions).expelPlayer(targetId);
        verify(api, never()).isOnOwnIsland(any(), any());
        verify(player, never()).sendMessage(mustReturn);
    }

    @Test
    void upgradeDetailsPurchasesAndAliasesRequireThePlayersOwnIsland() {
        command.onCommand(player, mock(Command.class), "is", new String[]{"upgrade", "team-limit"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"upgrade", "team-limit", "buy"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"up", "team-limit", "buy"});
        verify(player, times(3)).sendMessage(mustReturn);
        verify(api, never()).getIslandUuid(any());
        verifyNoInteractions(actions);
    }

    @Test
    void upgradeAliasAllowsPurchasesOnThePlayersOwnIsland() {
        when(api.isOnOwnIsland(playerId, location)).thenReturn(true);
        when(actions.buyUpgrade("team-limit")).thenReturn(CompletableFuture.completedFuture(2));
        command.onCommand(player, mock(Command.class), "is", new String[]{"up", "team-limit", "buy"});
        verify(actions).buyUpgrade("team-limit");
        verify(player, never()).sendMessage(mustReturn);
    }

    @Test
    void homeAndLobbyStillWorkFromOutsideTheIsland() {
        Component homeMessage = Component.text("Teleported to home default");
        Component lobbyMessage = Component.text("Teleported to lobby");
        when(config.getPlayerHomeSuccessMessage("default")).thenReturn(homeMessage);
        when(config.getPlayerLobbySuccessMessage()).thenReturn(lobbyMessage);
        when(actions.home("default", homeMessage)).thenReturn(CompletableFuture.completedFuture(null));
        when(api.lobby(playerId, lobbyMessage)).thenReturn(CompletableFuture.completedFuture(null));
        command.onCommand(player, mock(Command.class), "is", new String[]{"home"});
        command.onCommand(player, mock(Command.class), "is", new String[]{"lobby"});
        verify(actions).home("default", homeMessage);
        verify(api).lobby(playerId, lobbyMessage);
        verify(api, never()).isOnOwnIsland(any(), any());
    }
}
