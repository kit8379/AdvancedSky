package org.me.advancedsky.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;

import java.util.UUID;

public class PlayerLobbyCommand implements SubCommand {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerLobbyCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "lobby";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerLobbyAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerLobbyPermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerLobbySyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerLobbyDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        UUID playerUuid = player.getUniqueId();

        api.lobby(playerUuid).thenRun(() -> {
            api.sendPlayerMessage(playerUuid, config.getPlayerLobbySuccessMessage());
        }).exceptionally(ex -> {
            player.sendMessage(config.getUnknownExceptionMessage());
            plugin.severe("Error teleporting player " + player.getName() + " to lobby", ex);
            return null;
        });

        return true;
    }
}
