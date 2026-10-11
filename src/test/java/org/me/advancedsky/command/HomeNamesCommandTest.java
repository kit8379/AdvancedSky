package org.me.advancedsky.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.api.PlayerActions;
import org.me.advancedsky.command.player.PlayerDelHomeCommand;
import org.me.advancedsky.command.player.PlayerHomeCommand;
import org.me.advancedsky.command.player.PlayerSetHomeCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.HomeNameNotLegalException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class HomeNamesCommandTest {
    private final Player player = mock(Player.class);
    private final UUID uuid = UUID.randomUUID();
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final PlayerActions actions = mock(PlayerActions.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final AdvancedSky plugin = mock(AdvancedSky.class);

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(api.player(uuid)).thenReturn(actions);
    }

    @Test
    void commandsUseLegalHomeNames() {
        String name = "mine_1";
        Component message = Component.text(name);
        Location location = new Location(null, 1, 70, 3);
        when(player.getLocation()).thenReturn(location);
        when(config.getPlayerHomeSuccessMessage(name)).thenReturn(message);
        when(config.getPlayerSetHomeSuccessMessage(name)).thenReturn(message);
        when(config.getPlayerDelHomeSuccessMessage(name)).thenReturn(message);
        when(actions.home(name, message)).thenReturn(CompletableFuture.completedFuture(null));
        when(actions.setHome(name, location)).thenReturn(CompletableFuture.completedFuture(null));
        when(actions.deleteHome(name)).thenReturn(CompletableFuture.completedFuture(null));
        new PlayerHomeCommand(plugin, api, config).execute(player, new String[]{"home", name});
        new PlayerSetHomeCommand(plugin, api, config).execute(player, new String[]{"sethome", name});
        new PlayerDelHomeCommand(plugin, api, config).execute(player, new String[]{"delhome", name});
        verify(actions).home(name, message);
        verify(actions).setHome(name, location);
        verify(actions).deleteHome(name);
    }

    @Test
    void tabCompletionMatchesLegalNames() {
        when(api.getHomeNames(uuid)).thenReturn(CompletableFuture.completedFuture(Set.of("mine_1", "mine_2", "farm-1")));
        List<AsyncTabComplete> commands = List.of(new PlayerHomeCommand(plugin, api, config),
                new PlayerSetHomeCommand(plugin, api, config), new PlayerDelHomeCommand(plugin, api, config));
        for (AsyncTabComplete command : commands) {
            assertEquals(List.of("mine_1", "mine_2"), command.tabCompleteAsync(player, "is", new String[]{"home", "mi"}).join());
            assertEquals(List.of("mine_1"), command.tabCompleteAsync(player, "is", new String[]{"home", "mine_1"}).join());
            assertEquals(List.of(), command.tabCompleteAsync(player, "is", new String[]{"home", "mine", ""}).join());
        }
    }

    @Test
    void deletingDefaultDoesNotBlockOtherLegalNames() {
        Component blocked = Component.text("Cannot delete default");
        Component success = Component.text("Deleted");
        when(config.getPlayerCannotDeleteDefaultHomeMessage()).thenReturn(blocked);
        when(config.getPlayerDelHomeSuccessMessage("default_1")).thenReturn(success);
        when(actions.deleteHome("default_1")).thenReturn(CompletableFuture.completedFuture(null));
        PlayerDelHomeCommand command = new PlayerDelHomeCommand(plugin, api, config);
        command.execute(player, new String[]{"delhome", "Default"});
        command.execute(player, new String[]{"delhome", "default_1"});
        verify(player).sendMessage(blocked);
        verify(actions, never()).deleteHome("Default");
        verify(actions).deleteHome("default_1");
    }

    @Test
    void aSpacedNameIsRejectedWithoutSilentlySettingItsFirstWord() {
        Location location = new Location(null, 1, 70, 3);
        Component rejected = Component.text("Invalid home name");
        when(player.getLocation()).thenReturn(location);
        when(config.getHomeNameNotLegalMessage()).thenReturn(rejected);
        when(actions.setHome("go 1", location)).thenReturn(CompletableFuture.failedFuture(new HomeNameNotLegalException()));
        new PlayerSetHomeCommand(plugin, api, config).execute(player, new String[]{"sethome", "go", "1"});
        verify(actions).setHome("go 1", location);
        verify(actions, never()).setHome(eq("go"), any());
        verify(player).sendMessage(rejected);
    }
}
