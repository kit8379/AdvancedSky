package org.me.advancedsky.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;

import java.util.UUID;

/**
 * /is pvp
 */
public class PlayerPvpCommand implements SubCommand {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerPvpCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "pvp";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerPvpAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerPvpPermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerPvpSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerPvpDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        UUID playerUuid = player.getUniqueId();

        api.player(playerUuid).togglePvp().thenAccept(isPvpEnabled -> {
            if (isPvpEnabled) {
                player.sendMessage(config.getPlayerPvpEnableSuccessMessage());
            } else {
                player.sendMessage(config.getPlayerPvpDisableSuccessMessage());
            }
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof PlayerNotOnOwnIslandException) {
                player.sendMessage(config.getPlayerMustOnOwnIslandMessage());
            } else if (cause instanceof IslandDoesNotExistException) {
                player.sendMessage(config.getPlayerNoIslandMessage());
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error toggling PvP status for player " + player.getName(), ex);
            }
            return null;
        });

        return true;
    }
}
