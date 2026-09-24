package org.me.advancedsky.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandAlreadyExistException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.IslandGridFullException;
import org.me.advancedsky.island.IslandHandler;

import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * /island create | delete
 */
public class IslandCommand {

    private static final String PERMISSION_CREATE = "advancedsky.island.create";
    private static final String PERMISSION_DELETE = "advancedsky.island.delete";

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandHandler islandHandler;

    public IslandCommand(AdvancedSky plugin, ConfigHandler config, IslandHandler islandHandler) {
        this.plugin = plugin;
        this.config = config;
        this.islandHandler = islandHandler;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("island").then(Commands.literal("create").requires(source -> source.getSender().hasPermission(PERMISSION_CREATE)).executes(asPlayer(this::create))).then(Commands.literal("delete").requires(source -> source.getSender().hasPermission(PERMISSION_DELETE)).executes(asPlayer(this::delete))).build();
    }

    /**
     * Wraps a player-only action as a Brigadier command; non-players get a message instead.
     */
    private Command<CommandSourceStack> asPlayer(Consumer<Player> action) {
        return context -> {
            CommandSender sender = context.getSource().getSender();
            if (sender instanceof Player player) {
                action.accept(player);
            } else {
                sender.sendMessage(config.getOnlyPlayerCanRunCommandMessage());
            }
            return Command.SINGLE_SUCCESS;
        };
    }

    private void create(Player player) {
        islandHandler.createIsland(player).thenRun(() -> player.sendMessage(config.getIslandCreatedMessage())).exceptionally(ex -> {
            Throwable cause = unwrap(ex);
            if (cause instanceof IslandAlreadyExistException) {
                player.sendMessage(config.getAlreadyHasIslandMessage());
            } else if (cause instanceof IslandGridFullException) {
                player.sendMessage(config.getIslandGridFullMessage());
                plugin.warning("Island grid is full: refused to create an island for " + player.getName());
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error creating island for player " + player.getName(), cause);
            }
            return null;
        });
    }

    private void delete(Player player) {
        islandHandler.deleteIsland(player).thenRun(() -> player.sendMessage(config.getIslandDeletedMessage())).exceptionally(ex -> {
            Throwable cause = unwrap(ex);
            if (cause instanceof IslandDoesNotExistException) {
                player.sendMessage(config.getNoIslandMessage());
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error deleting island for player " + player.getName(), cause);
            }
            return null;
        });
    }

    private static Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }
}

