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
import org.me.advancedsky.exceptions.HomeLimitReachedException;
import org.me.advancedsky.exceptions.HomeNameNotLegalException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.LocationNotInIslandException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /isadmin sethome <player> <home>
 */
public class AdminSetHomeCommand implements SubCommand, AsyncTabComplete {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public AdminSetHomeCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "sethome";
    }

    @Override
    public String[] getAliases() {
        return config.getAdminSetHomeAliases();
    }

    @Override
    public String getPermission() {
        return config.getAdminSetHomePermission();
    }

    @Override
    public String getSyntax() {
        return config.getAdminSetHomeSyntax();
    }

    @Override
    public String getDescription() {
        return config.getAdminSetHomeDescription();
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

        String homePlayerName = args[1];
        String homeName = args[2];

        Location loc = player.getLocation();

        api.getPlayerUuid(homePlayerName).thenCompose(targetUuidOpt -> {
            if (targetUuidOpt.isEmpty()) {
                sender.sendMessage(config.getUnknownPlayerMessage(homePlayerName));
                return CompletableFuture.completedFuture(null);
            }

            return api.admin(sender).setHome(targetUuidOpt.get(), homeName, loc).thenRun(() -> sender.sendMessage(config.getAdminSetHomeSuccessMessage(homePlayerName, homeName)));
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IslandNotHostedHereException wrongHost) {
                sender.sendMessage(config.getIslandNotHostedHereMessage(wrongHost.getServer()));
            } else if (cause instanceof IslandDoesNotExistException) {
                sender.sendMessage(config.getAdminNoIslandMessage(homePlayerName));
            } else if (cause instanceof LocationNotInIslandException) {
                sender.sendMessage(config.getAdminMustInIslandSetHomeMessage(homePlayerName));
            } else if (cause instanceof HomeNameNotLegalException) {
                sender.sendMessage(config.getHomeNameNotLegalMessage());
            } else if (cause instanceof HomeLimitReachedException) {
                sender.sendMessage(config.getPlayerHomeLimitReachedMessage(cause.getMessage()));
            } else {
                sender.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error setting home " + homeName + " for " + homePlayerName, ex);
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

                return api.getHomeNames(uuidOpt.get()).thenApply(homes -> homes.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()));
            });
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
