package org.me.advancedsky.command.admin;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.IslandNotHostedHereException;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.AsyncTabComplete;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.LocationNotInIslandException;
import org.me.advancedsky.exceptions.WarpLimitReachedException;
import org.me.advancedsky.exceptions.WarpNameNotLegalException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /isadmin setwarp <player> <warp>
 */
public class AdminSetWarpCommand implements SubCommand, AsyncTabComplete {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public AdminSetWarpCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "setwarp";
    }

    @Override
    public String[] getAliases() {
        return config.getAdminSetWarpAliases();
    }

    @Override
    public String getPermission() {
        return config.getAdminSetWarpPermission();
    }

    @Override
    public String getSyntax() {
        return config.getAdminSetWarpSyntax();
    }

    @Override
    public String getDescription() {
        return config.getAdminSetWarpDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        if (args.length < 3) {
            return false;
        }

        String warpPlayerName = args[1];
        String warpName = args[2];

        Location loc = player.getLocation();

        api.getPlayerUuid(warpPlayerName).thenCompose(targetUuidOpt -> {
            if (targetUuidOpt.isEmpty()) {
                sender.sendMessage(config.getUnknownPlayerMessage(warpPlayerName));
                return CompletableFuture.completedFuture(null);
            }

            UUID targetUuid = targetUuidOpt.get();

            return api.getIslandUuid(targetUuid).thenCompose(islandUuid -> api.admin(sender).setWarp(islandUuid, warpName, loc)).thenRun(() -> sender.sendMessage(config.getAdminSetWarpSuccessMessage(warpPlayerName, warpName)));
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IslandNotHostedHereException wrongHost) {
                sender.sendMessage(config.getIslandNotHostedHereMessage(wrongHost.getServer()));
            } else if (cause instanceof IslandDoesNotExistException) {
                sender.sendMessage(config.getAdminNoIslandMessage(warpPlayerName));
            } else if (cause instanceof LocationNotInIslandException) {
                sender.sendMessage(config.getAdminMustInIslandSetWarpMessage(warpPlayerName));
            } else if (cause instanceof WarpNameNotLegalException) {
                sender.sendMessage(config.getWarpNameNotLegalMessage());
            } else if (cause instanceof WarpLimitReachedException) {
                sender.sendMessage(config.getPlayerWarpLimitReachedMessage(cause.getMessage()));
            } else {
                sender.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error setting warp " + warpName + " for " + warpPlayerName, ex);
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
            return api.getPlayerUuid(args[1]).thenCompose(uuidOpt -> {
                if (uuidOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(Collections.<String>emptyList());
                }

                UUID targetUuid = uuidOpt.get();

                return api.getIslandUuid(targetUuid).thenCompose(api::getWarpNames).thenApply(warps -> warps.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()));
            });
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
