package org.me.advancedsky.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandAlreadyExistException;
import org.me.advancedsky.exceptions.IslandGridFullException;
import org.me.advancedsky.exceptions.TeleportCancelledException;

import java.util.UUID;

public class PlayerCreateIslandCommand implements SubCommand {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerCreateIslandCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "create";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerCreateAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerCreatePermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerCreateSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerCreateDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        UUID playerUuid = player.getUniqueId();

        api.player(playerUuid).createIsland().thenRun(() -> {
            player.sendMessage(config.getPlayerCreateSuccessMessage());
        }).thenCompose(v -> {
            return api.player(playerUuid).home("default", config.getPlayerHomeSuccessMessage("default")).exceptionally(ex -> {
                if (!(ex.getCause() instanceof TeleportCancelledException)) {
                    player.sendMessage(config.getUnknownExceptionMessage());
                    plugin.severe("Created an island for player " + player.getName() + " but could not take them home", ex);
                }
                return null;
            });
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IslandAlreadyExistException) {
                player.sendMessage(config.getPlayerAlreadyHasIslandMessage());
            } else if (cause instanceof IslandGridFullException) {
                player.sendMessage(config.getIslandGridFullMessage());
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error creating island for player " + player.getName(), ex);
            }
            return null;
        });

        return true;
    }
}
