package org.me.advancedsky.command.player;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.command.AsyncTabComplete;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.BiomeNotUnlockedException;
import org.me.advancedsky.exceptions.InvalidBiomeException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.LocationNotInIslandException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * /is biome <biome>
 */
public class PlayerBiomeCommand implements SubCommand, AsyncTabComplete {

    private final AdvancedSky plugin;
    private final AdvancedSkyAPI api;
    private final ConfigHandler config;

    public PlayerBiomeCommand(AdvancedSky plugin, AdvancedSkyAPI api, ConfigHandler config) {
        this.plugin = plugin;
        this.api = api;
        this.config = config;
    }

    @Override
    public String getName() {
        return "biome";
    }

    @Override
    public String[] getAliases() {
        return config.getPlayerBiomeAliases();
    }

    @Override
    public String getPermission() {
        return config.getPlayerBiomePermission();
    }

    @Override
    public String getSyntax() {
        return config.getPlayerBiomeSyntax();
    }

    @Override
    public String getDescription() {
        return config.getPlayerBiomeDescription();
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

        String biomeName = args[1].toLowerCase(Locale.ROOT);

        UUID playerUuid = player.getUniqueId();
        Location loc = player.getLocation();

        api.player(playerUuid).applyBiome(loc, biomeName).thenRun(() -> player.sendMessage(config.getPlayerBiomeChangeSuccessMessage(biomeName))).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof PlayerNotOnOwnIslandException) {
                player.sendMessage(config.getPlayerMustOnOwnIslandMessage());
            } else if (cause instanceof IslandDoesNotExistException) {
                player.sendMessage(config.getPlayerNoIslandMessage());
            } else if (cause instanceof LocationNotInIslandException) {
                player.sendMessage(config.getPlayerBiomeMustInOwnIslandMessage());
            } else if (cause instanceof BiomeNotUnlockedException) {
                player.sendMessage(config.getPlayerBiomeNotUnlockedMessage(biomeName));
                player.sendMessage(config.getPlayerBiomeAllowedListMessage(cause.getMessage()));
            } else if (cause instanceof InvalidBiomeException) {
                player.sendMessage(config.getPlayerBiomeInvalidMessage(biomeName));
            } else {
                player.sendMessage(config.getUnknownExceptionMessage());
                plugin.severe("Error handling biome command for player " + player.getName() + " biome=" + biomeName, ex);
            }

            return null;
        });

        return true;
    }

    @Override
    public CompletableFuture<List<String>> tabCompleteAsync(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);

            return CompletableFuture.completedFuture(getAllBiomeNames().stream().filter(name -> name.startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList()));
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    private List<String> getAllBiomeNames() {
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);

        return registry.stream().map(registry::getKey).filter(Objects::nonNull).map(NamespacedKey::getKey).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
