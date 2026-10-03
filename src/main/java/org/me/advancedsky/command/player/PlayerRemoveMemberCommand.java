package org.me.advancedsky.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.AsyncTabComplete;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.CannotRemoveOwnerException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.IslandPlayerDoesNotExistException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /is removemember <player>
 */
public class PlayerRemoveMemberCommand implements SubCommand, AsyncTabComplete {
    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerRemoveMemberCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "removemember";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerRemoveMemberAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerRemoveMemberPermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerRemoveMemberSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerRemoveMemberDescription();
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

            if (playerUuid.equals(targetPlayerUuid)) {
                player.sendMessage(config.getPlayerCannotRemoveSelfMessage());
                return CompletableFuture.completedFuture(null);
            }

            return api.player(playerUuid).removeMember(targetPlayerUuid).thenRun(() -> {
                player.sendMessage(config.getPlayerRemoveMemberSuccessMessage(targetPlayerName));
                api.sendPlayerMessage(targetPlayerUuid, config.getWasRemovedFromIslandMessage(player.getName()));
            });
        }).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof PlayerNotOnOwnIslandException) {
                player.sendMessage(config.getPlayerMustOnOwnIslandMessage());
            } else if (cause instanceof IslandDoesNotExistException) {
                player.sendMessage(config.getPlayerNoIslandMessage());
            } else if (cause instanceof CannotRemoveOwnerException) {
                player.sendMessage(config.getCannotRemoveOwnerMessage());
            } else if (cause instanceof IslandPlayerDoesNotExistException) {
                player.sendMessage(config.getIslandMemberNotExistsMessage(targetPlayerName));
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error removing member from island for player " + player.getName(), ex);
            }
            return null;
        });

        return true;
    }

    @Override
    public CompletableFuture<List<String>> tabCompleteAsync(CommandSender sender, String label, String[] args) {
        if (args.length != 2 || !(sender instanceof Player player)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String prefix = args[1].toLowerCase(Locale.ROOT);
        UUID playerUuid = player.getUniqueId();

        return api.getIslandUuid(playerUuid).thenCompose(islandUuid -> api.getIslandMembers(islandUuid).thenCompose(members -> api.getPlayerNames(members).thenApply(nameMap -> members.stream().map(uuid -> java.util.Objects.requireNonNull(nameMap.get(uuid), "Missing player name for " + uuid)).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()))));
    }
}
