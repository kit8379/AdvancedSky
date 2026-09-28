package org.me.advancedsky.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.player.*;
import org.me.advancedsky.config.ConfigHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * /is
 */
public class IslandPlayerCommand implements CommandExecutor, AsyncCommandTabRouter {
    private final ConfigHandler config;
    private final Map<String, SubCommand> subCommandMap = new HashMap<>();
    private final Set<SubCommand> subCommands = new HashSet<>();

    public IslandPlayerCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.config = config;

        subCommands.add(new PlayerCreateIslandCommand(plugin, api, config));
        subCommands.add(new PlayerDeleteIslandCommand(plugin, api, config));
        subCommands.add(new PlayerHelpCommand(config, subCommands));

        for (SubCommand cmd : subCommands) {
            registerSubCommand(cmd);
        }
    }

    private void registerSubCommand(SubCommand cmd) {
        subCommandMap.put(cmd.getName().toLowerCase(Locale.ROOT), cmd);
        for (String alias : cmd.getAliases()) {
            subCommandMap.put(alias.toLowerCase(Locale.ROOT), cmd);
        }
    }

    private List<String> getRootSuggestions(CommandSender sender, String arg0) {
        String prefix = arg0.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();

        for (SubCommand cmd : subCommands) {
            String perm = cmd.getPermission();
            if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) {
                continue;
            }

            String name = cmd.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                suggestions.add(name);
            }

            for (String alias : cmd.getAliases()) {
                if (alias.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(alias);
                }
            }
        }

        return suggestions;
    }

    @Override
    public CompletableFuture<List<String>> completeAsync(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        if (args.length == 1) {
            return CompletableFuture.completedFuture(getRootSuggestions(sender, args[0]));
        }

        SubCommand target = subCommandMap.get(args[0].toLowerCase(Locale.ROOT));
        if (target == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String perm = target.getPermission();
        if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        if (target instanceof AsyncTabComplete asyncTabComplete) {
            return asyncTabComplete.tabCompleteAsync(sender, label, args).thenApply(list -> list == null ? Collections.<String>emptyList() : list).exceptionally(ex -> Collections.emptyList());
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission("advancedsky.island.player")) {
            sender.sendMessage(config.getNoPermissionCommandMessage());
            return true;
        }

        if (args.length == 0) {
            SubCommand helpCmd = subCommandMap.get("help");
            if (helpCmd != null) {
                return helpCmd.execute(sender, new String[]{"help"});
            }
            return true;
        }

        String subName = args[0].toLowerCase(Locale.ROOT);
        SubCommand target = subCommandMap.get(subName);
        if (target == null) {
            sender.sendMessage(config.getPlayerUnknownSubCommandMessage());
            return true;
        }

        String perm = target.getPermission();
        if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) {
            sender.sendMessage(config.getNoPermissionCommandMessage());
            return true;
        }

        boolean success = target.execute(sender, args);
        if (!success) {
            sender.sendMessage(config.getPlayerCommandUsageMessage(target.getName(), target.getSyntax()));
        }
        return true;
    }
}
