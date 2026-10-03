package org.me.advancedsky.island;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.network.IslandOperator;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoreHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;
    private final IslandCache islandCache;
    private final IslandOperator islandOperator;

    public CoreHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandDistributor islandDistributor, IslandCache islandCache, IslandOperator islandOperator) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.islandCache = islandCache;
        this.islandOperator = islandOperator;
    }

    public CompletableFuture<Void> createIsland(UUID ownerUuid) {
        UUID islandUuid = UUID.randomUUID();
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.createIsland(islandUuid, ownerUuid, () -> islandOperator.createIsland(islandUuid, ownerUuid)), plugin.getBukkitAsyncExecutor());
    }

    /**
     * Releases players immediately; physical cleanup keeps the old slot and serial task reserved.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return islandOperator.deleteIsland(actor, islandUuid);
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return islandOperator.toggleIslandLock(actor, islandUuid);
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid) {
        return islandOperator.toggleIslandPvp(actor, islandUuid);
    }

    public CompletableFuture<Boolean> isIslandLock(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.isIslandLock(islandUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Boolean> isIslandPvp(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.isIslandPvp(islandUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<UUID> getIslandUuid(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new), plugin.getBukkitAsyncExecutor());
    }

    /**
     * Own-island management uses the local cache and reads the player on their entity thread.
     */
    public CompletableFuture<UUID> getOwnIslandHere(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> Bukkit.getPlayer(playerUuid), plugin.getGlobalRegionExecutor()).thenCompose(player -> {
            CompletableFuture<UUID> result = new CompletableFuture<>();
            if (player == null) return CompletableFuture.failedFuture(new PlayerNotOnOwnIslandException());
            try {
                if (player.getScheduler().run(plugin, task -> {
                    try {
                        UUID islandUuid = getOwnIslandAt(playerUuid, player.getLocation());
                        if (islandUuid == null) throw new PlayerNotOnOwnIslandException();
                        result.complete(islandUuid);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                }, () -> result.completeExceptionally(new PlayerNotOnOwnIslandException())) == null) {
                    result.completeExceptionally(new PlayerNotOnOwnIslandException());
                }
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
            return result;
        });
    }

    /**
     * Called with a location snapshot taken on the player's thread. Co-ops and visitors do not qualify.
     */
    public UUID getOwnIslandAt(UUID playerUuid, Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName()))
            return null;
        Island island = islandCache.getAt(location);
        if (island == null || !(playerUuid.equals(island.getOwner()) || island.getMembers().contains(playerUuid)))
            return null;
        return island.getIslandUuid();
    }

    /**
     * The island whose area holds the location, among the islands hosted on this server; null
     * anywhere else. NewSky reads this from the island world's name; here every island shares one
     * world, so the grid decides.
     */
    public UUID getIslandUuidAt(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
            return null;
        }

        return islandCache.getIslandUuidAt(location);
    }

}
