package org.me.advancedsky.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.AsyncTabComplete;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.CannotExpelIslandPlayerException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.PlayerNotInIslandException;
import org.me.advancedsky.exceptions.PlayerNotOnlineException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /is expel <player>
 */
public class PlayerExpelCommand implements SubCommand, AsyncTabComplete {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerExpelCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "expel";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerExpelAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerExpelPermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerExpelSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerExpelDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        if (args.length < 2) {
            return false;
        }

        String targetPlayerName = args[1];
        UUID playerUuid = player.getUniqueId();

        api.getPlayerUuid(targetPlayerName).thenCompose(targetUuidOpt -> {
            if (targetUuidOpt.isEmpty()) {
                player.sendMessage(config.getUnknownPlayerMessage(targetPlayerName));
                return CompletableFuture.completedFuture(null);
            }

            UUID targetPlayerUuid = targetUuidOpt.get();

            return api.player(playerUuid).expelPlayer(targetPlayerUuid).thenRun(() -> player.sendMessage(config.getPlayerExpelSuccessMessage(targetPlayerName)));
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof PlayerNotOnOwnIslandException) {
                player.sendMessage(config.getPlayerMustOnOwnIslandMessage());
            } else if (cause instanceof IslandDoesNotExistException) {
                player.sendMessage(config.getPlayerNoIslandMessage());
            } else if (cause instanceof PlayerNotOnlineException) {
                player.sendMessage(config.getPlayerNotOnlineMessage(targetPlayerName));
            } else if (cause instanceof PlayerNotInIslandException) {
                player.sendMessage(config.getPlayerExpelNotInIslandMessage(targetPlayerName));
            } else if (cause instanceof CannotExpelIslandPlayerException) {
                player.sendMessage(config.getPlayerCannotExpelIslandPlayerMessage());
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Failed to expel player " + targetPlayerName + " from island of player " + player.getName(), ex);
            }
            return null;
        });

        return true;
    }

    @Override
    public CompletableFuture<List<String>> tabCompleteAsync(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String prefix = args[1].toLowerCase(Locale.ROOT);

        return api.getOnlinePlayersNames().thenApply(names -> names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()));
    }
}
