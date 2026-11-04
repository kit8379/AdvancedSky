package org.me.advancedsky.command;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Subcommand-level async tab completion.
 * Example: /is removemember <player>
 */
public interface AsyncTabComplete {
    CompletableFuture<List<String>> tabCompleteAsync(CommandSender sender, String label, String[] args);

    /**
     * How many typed letters of the last argument the candidates are loaded by.
     */
    default int candidatePrefixLength(String[] args) {
        return 0;
    }
}