package org.me.advancedsky.command.admin;

import org.bukkit.command.CommandSender;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.IslandNotHostedHereException;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.AsyncTabComplete;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.CannotCoopIslandPlayerException;
import org.me.advancedsky.exceptions.CoopLimitReachedException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.PlayerAlreadyCoopedException;
import org.me.advancedsky.exceptions.PlayerNotOnlineException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /isadmin coop <owner> <player>
 */
public class AdminCoopCommand implements SubCommand, AsyncTabComplete {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public AdminCoopCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "coop";
    }

    @Override
    public String[] getAliases() {
        return config.getAdminCoopAliases();
    }

    @Override
    public String getPermission() {
        return config.getAdminCoopPermission();
    }

    @Override
    public String getSyntax() {
        return config.getAdminCoopSyntax();
    }

    @Override
    public String getDescription() {
        return config.getAdminCoopDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            return false;
        }

        String ownerName = args[1];
        String targetName = args[2];

        api.getPlayerUuid(ownerName).thenCompose(ownerUuidOpt -> {
            if (ownerUuidOpt.isEmpty()) {
                sender.sendMessage(config.getUnknownPlayerMessage(ownerName));
                return CompletableFuture.completedFuture(null);
            }

            return api.getPlayerUuid(targetName).thenCompose(targetUuidOpt -> {
                if (targetUuidOpt.isEmpty()) {
                    sender.sendMessage(config.getUnknownPlayerMessage(targetName));
                    return CompletableFuture.completedFuture(null);
                }

                return api.getIslandUuid(ownerUuidOpt.get()).thenCompose(islandUuid -> {
                    return api.admin(sender).addCoop(islandUuid, targetUuidOpt.get());
                }).thenRun(() -> {
                    sender.sendMessage(config.getAdminCoopSuccessMessage(ownerName, targetName));
                    api.sendPlayerMessage(targetUuidOpt.get(), config.getWasCoopedToIslandMessage(ownerName));
                });
            });
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IslandNotHostedHereException wrongHost) {
                sender.sendMessage(config.getIslandNotHostedHereMessage(wrongHost.getServer()));
            } else if (cause instanceof IslandDoesNotExistException) {
                sender.sendMessage(config.getAdminNoIslandMessage(ownerName));
            } else if (cause instanceof PlayerAlreadyCoopedException) {
                sender.sendMessage(config.getPlayerAlreadyCoopedMessage(targetName));
            } else if (cause instanceof CannotCoopIslandPlayerException) {
                sender.sendMessage(config.getPlayerCannotCoopIslandPlayerMessage());
            } else if (cause instanceof CoopLimitReachedException) {
                sender.sendMessage(config.getPlayerCoopLimitReachedMessage(cause.getMessage()));
            } else if (cause instanceof PlayerNotOnlineException) {
                sender.sendMessage(config.getPlayerNotOnlineMessage(targetName));
            } else {
                sender.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error cooping player " + targetName + " to island of " + ownerName, ex);
            }
            return null;
        });

        return true;
    }

    @Override
    public CompletableFuture<List<String>> tabCompleteAsync(CommandSender sender, String label, String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return api.getOnlinePlayersNames().thenApply(names -> names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()));
        }

        if (args.length == 3) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return api.getOnlinePlayersNames().thenApply(names -> names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()));
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
