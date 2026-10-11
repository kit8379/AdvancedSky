package org.me.advancedsky.island;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.util.IslandUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class HomeHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final IslandGrid islandGrid;
    private final IslandOperator islandOperator;

    public HomeHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandDistributor islandDistributor, OnlinePlayerRegistry onlinePlayerRegistry, IslandGrid islandGrid, IslandOperator islandOperator) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.islandGrid = islandGrid;
        this.islandOperator = islandOperator;
    }

    /**
     * Sets a home in the player's island host world. Players must use their own boundary;
     * an operator may preserve a home pointing to another island on that host.
     */
    public CompletableFuture<Void> setHome(Actor actor, UUID playerUuid, String homeName, Location location) {
        return CompletableFuture.supplyAsync(() -> {
            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);

            String host = database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            if (!host.equals(config.getServerName()) || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
                throw new LocationNotInIslandException();
            }
            if (!(actor instanceof Actor.Bypass)) {
                int slot = database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
                int size = config.getUpgradeLimit("island-size", database.getIslandUpgradeLevel(islandUuid, "island-size"));
                if (!islandGrid.isInArea(slot, location.getX(), location.getZ()) || !islandGrid.isInsideIsland(slot, size, location.getBlockX(), location.getBlockZ())) {
                    throw new LocationNotInIslandException();
                }
            }

            String normalizedHomeName = homeName.toLowerCase(Locale.ROOT);
            if (!IslandUtils.isLegalPointName(normalizedHomeName)) {
                throw new HomeNameNotLegalException();
            }

            String homeLocation = location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getYaw() + "," + location.getPitch();

            return islandOperator.setHome(actor, islandUuid, playerUuid, normalizedHomeName, homeLocation);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(future -> future);
    }

    public CompletableFuture<Void> delHome(Actor actor, UUID playerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);

            return delHome(actor, islandUuid, playerUuid, homeName);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(future -> future);
    }

    /**
     * Uses the resolved island, so a membership change cannot retarget the deletion.
     */
    public CompletableFuture<Void> delHome(Actor actor, UUID islandUuid, UUID playerUuid, String homeName) {
        String normalizedName = homeName.toLowerCase(Locale.ROOT);
        return islandDistributor.deleteHome(actor, islandUuid, playerUuid, normalizedName, () -> islandOperator.deleteHome(islandUuid, playerUuid, normalizedName));
    }

    public CompletableFuture<Void> home(UUID playerUuid, String homeName, UUID targetPlayerUuid) {
        return home(playerUuid, homeName, targetPlayerUuid, null);
    }

    /**
     * Sends the optional success message on the destination after the teleport succeeds.
     */
    public CompletableFuture<Void> home(UUID playerUuid, String homeName, UUID targetPlayerUuid, Component successMessage) {
        return CompletableFuture.supplyAsync(() -> {
            if (!onlinePlayerRegistry.isOnline(targetPlayerUuid)) {
                throw new PlayerNotOnlineException();
            }

            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);
            String homeLocation = Optional.ofNullable(database.getIslandHomes(islandUuid, playerUuid).get(homeName.toLowerCase(Locale.ROOT))).orElseThrow(HomeDoesNotExistException::new);

            return new HomeTarget(islandUuid, homeLocation);
        }, plugin.getBukkitAsyncExecutor()).thenComposeAsync(target -> successMessage == null ? islandDistributor.teleportIsland(target.islandUuid(), targetPlayerUuid, target.homeLocation()) : islandDistributor.teleportIsland(target.islandUuid(), targetPlayerUuid, target.homeLocation(), successMessage), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<String>> getHomeNames(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);

            return database.getIslandHomes(islandUuid, playerUuid).keySet();
        }, plugin.getBukkitAsyncExecutor());
    }

    private record HomeTarget(UUID islandUuid, String homeLocation) {
    }
}
