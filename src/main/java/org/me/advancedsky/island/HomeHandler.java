package org.me.advancedsky.island;

import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
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

    public HomeHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandDistributor islandDistributor, OnlinePlayerRegistry onlinePlayerRegistry, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.islandGrid = islandGrid;
    }

    /**
     * Sets a home at a location on this server, which must lie inside the boundary of the
     * player's own island: in the island world of the server hosting it.
     */
    public CompletableFuture<Void> setHome(Actor actor, UUID playerUuid, String homeName, Location location) {
        return CompletableFuture.supplyAsync(() -> {
            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);

            String host = database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            int slot = database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            // Beyond the boundary a teleport is always refused, so no point may be set there.
            int size = config.getUpgradeLimit("island-size", database.getIslandUpgradeLevel(islandUuid, "island-size"));
            if (!host.equals(config.getServerName()) || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName()) || !islandGrid.isInArea(slot, location.getX(), location.getZ()) || !islandGrid.isInsideIsland(slot, size, location.getBlockX(), location.getBlockZ())) {
                throw new LocationNotInIslandException();
            }

            String normalizedHomeName = homeName.toLowerCase(Locale.ROOT);
            if (!IslandUtils.isLegalPointName(normalizedHomeName)) {
                throw new HomeNameNotLegalException();
            }

            String homeLocation = location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getYaw() + "," + location.getPitch();

            return islandDistributor.setHome(actor, islandUuid, playerUuid, normalizedHomeName, homeLocation);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(future -> future);
    }

    public CompletableFuture<Void> delHome(UUID playerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);

            return islandDistributor.deleteHome(islandUuid, playerUuid, homeName.toLowerCase(Locale.ROOT));
        }, plugin.getBukkitAsyncExecutor()).thenCompose(future -> future);
    }

    public CompletableFuture<Void> home(UUID playerUuid, String homeName, UUID targetPlayerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (!onlinePlayerRegistry.isOnline(targetPlayerUuid)) {
                throw new PlayerNotOnlineException();
            }

            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);
            String homeLocation = Optional.ofNullable(database.getIslandHomes(islandUuid, playerUuid).get(homeName.toLowerCase(Locale.ROOT))).orElseThrow(HomeDoesNotExistException::new);

            return new HomeTarget(islandUuid, homeLocation);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(target -> islandDistributor.teleportIsland(target.islandUuid(), targetPlayerUuid, config.getIslandWorldName(), target.homeLocation()));
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
