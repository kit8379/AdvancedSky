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
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.util.IslandUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class WarpHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final IslandGrid islandGrid;
    private final IslandOperator islandOperator;

    public WarpHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandDistributor islandDistributor, OnlinePlayerRegistry onlinePlayerRegistry, IslandGrid islandGrid, IslandOperator islandOperator) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.islandGrid = islandGrid;
        this.islandOperator = islandOperator;
    }

    /**
     * Sets a warp at a location on this server, which must lie inside the island's boundary:
     * in the island world of the server hosting it.
     */
    public CompletableFuture<Void> setWarp(Actor actor, UUID islandUuid, String warpName, Location location) {
        return CompletableFuture.supplyAsync(() -> {
            String host = database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            int slot = database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            // Beyond the boundary a teleport is always refused, so no point may be set there.
            int size = config.getUpgradeLimit("island-size", database.getIslandUpgradeLevel(islandUuid, "island-size"));
            if (!host.equals(config.getServerName()) || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName()) || !islandGrid.isInArea(slot, location.getX(), location.getZ()) || !islandGrid.isInsideIsland(slot, size, location.getBlockX(), location.getBlockZ())) {
                throw new LocationNotInIslandException();
            }

            String normalizedWarpName = warpName.toLowerCase(Locale.ROOT);
            if (!IslandUtils.isLegalPointName(normalizedWarpName)) {
                throw new WarpNameNotLegalException();
            }

            String warpLocation = location.getX() + "," + location.getY() + "," + location.getZ() + "," + location.getYaw() + "," + location.getPitch();

            return islandOperator.setWarp(actor, islandUuid, normalizedWarpName, warpLocation);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(future -> future);
    }

    public CompletableFuture<Void> delWarp(Actor actor, UUID islandUuid, String warpName) {
        String normalizedName = warpName.toLowerCase(Locale.ROOT);
        return islandDistributor.deleteWarp(actor, islandUuid, normalizedName, () -> islandOperator.deleteWarp(actor, islandUuid, normalizedName));
    }

    /**
     * Bans and the lock bar players; an admin's warp, like an admin's home, is not held to them.
     */
    public CompletableFuture<Void> warp(Actor actor, UUID islandUuid, String warpName, UUID targetPlayerUuid) {
        return warp(actor, islandUuid, warpName, targetPlayerUuid, null);
    }

    /** Sends the optional success message on the destination after the teleport succeeds. */
    public CompletableFuture<Void> warp(Actor actor, UUID islandUuid, String warpName, UUID targetPlayerUuid, Component successMessage) {
        return CompletableFuture.supplyAsync(() -> {
            if (!onlinePlayerRegistry.isOnline(targetPlayerUuid)) {
                throw new PlayerNotOnlineException();
            }

            boolean bypass = actor instanceof Actor.Bypass;
            if (!bypass && database.getIslandBans(islandUuid).contains(targetPlayerUuid)) {
                throw new PlayerBannedException();
            }

            if (!bypass && database.isIslandLock(islandUuid)) {
                boolean allowed = database.getIslandPlayers(islandUuid).containsKey(targetPlayerUuid) || database.getIslandCoops(islandUuid).contains(targetPlayerUuid);
                if (!allowed) {
                    throw new IslandLockedException();
                }
            }

            return Optional.ofNullable(database.getIslandWarps(islandUuid).get(warpName.toLowerCase(Locale.ROOT))).orElseThrow(WarpDoesNotExistException::new);
        }, plugin.getBukkitAsyncExecutor()).thenComposeAsync(warpLocation -> successMessage == null
                ? islandDistributor.teleportIsland(islandUuid, targetPlayerUuid, warpLocation)
                : islandDistributor.teleportIsland(islandUuid, targetPlayerUuid, warpLocation, successMessage), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<String>> getWarpNames(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandWarps(islandUuid).keySet(), plugin.getBukkitAsyncExecutor());
    }
}
