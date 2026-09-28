package org.me.advancedsky.command.player;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.AsyncTabComplete;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /island info [player]
 */
public class PlayerInfoCommand implements SubCommand, AsyncTabComplete {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerInfoCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "info";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerInfoAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerInfoPermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerInfoSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerInfoDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            return true;
        }

        String targetPlayerName = args.length >= 2 ? args[1] : null;
        CompletableFuture<UUID> islandFuture;

        if (targetPlayerName == null) {
            Location loc = player.getLocation();

            UUID islandUuid = api.getIslandUuidAt(loc);
            if (islandUuid == null) {
                sender.sendMessage(config.getPlayerInfoNotInIslandMessage());
                return true;
            }

            islandFuture = CompletableFuture.completedFuture(islandUuid);
        } else {
            islandFuture = api.getPlayerUuid(targetPlayerName).thenCompose(targetUuidOpt -> {
                if (targetUuidOpt.isEmpty()) {
                    sender.sendMessage(config.getUnknownPlayerMessage(targetPlayerName));
                    return CompletableFuture.completedFuture(null);
                }

                return api.getIslandUuid(targetUuidOpt.get());
            });
        }

        islandFuture.thenCompose(islandUuid -> {
            if (islandUuid == null) {
                return CompletableFuture.completedFuture(null);
            }

            return api.getIslandOwner(islandUuid).thenCompose(ownerUuid -> api.getIslandMembers(islandUuid).thenCompose(members -> api.getIslandLevel(islandUuid).thenCompose(level -> {
                List<UUID> uuidsToResolve = new ArrayList<>(members.size() + 1);
                uuidsToResolve.add(ownerUuid);
                uuidsToResolve.addAll(members);

                return api.getPlayerNames(uuidsToResolve).thenAccept(nameMap -> {
                    String ownerName = nameMap.getOrDefault(ownerUuid, ownerUuid.toString());

                    String memberNames = members.stream().map(uuid -> nameMap.getOrDefault(uuid, uuid.toString())).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));

                    sender.sendMessage(config.getIslandInfoHeaderMessage());
                    sender.sendMessage(config.getIslandInfoUUIDMessage(islandUuid));
                    sender.sendMessage(config.getIslandInfoOwnerMessage(ownerName));
                    sender.sendMessage(config.getIslandInfoLevelMessage(level));

                    if (memberNames.isEmpty()) {
                        sender.sendMessage(config.getIslandInfoNoMembersMessage());
                    } else {
                        sender.sendMessage(config.getIslandInfoMembersMessage(memberNames));
                    }
                });
            })));
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof IslandDoesNotExistException) {
                sender.sendMessage(targetPlayerName == null ? config.getPlayerNoIslandMessage() : config.getNoIslandMessage(targetPlayerName));
            } else {
                sender.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error retrieving island info for player " + player.getName(), ex);
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

        return api.getOnlinePlayersNames().thenApply(names -> names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList())).exceptionally(ex -> Collections.emptyList());
    }
}