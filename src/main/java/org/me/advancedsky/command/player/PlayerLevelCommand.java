package org.me.advancedsky.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;

import java.util.UUID;

/**
 * /is level
 */
public class PlayerLevelCommand implements SubCommand {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerLevelCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "level";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerLevelAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerLevelPermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerLevelSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerLevelDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        UUID playerUuid = player.getUniqueId();

        api.getIslandUuid(playerUuid).thenCompose(api::getIslandLevel).thenAccept(level -> {
            player.sendMessage(config.getIslandLevelMessage(level));
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IslandDoesNotExistException) {
                player.sendMessage(config.getPlayerNoIslandMessage());
            } else {
                plugin.severe("Error calculating island level for player " + player.getName(), ex);
                player.sendMessage(config.getUnknownExceptionMessage());
            }
            return null;
        });

        return true;
    }
}