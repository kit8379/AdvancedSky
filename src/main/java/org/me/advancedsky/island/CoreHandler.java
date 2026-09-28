package org.me.advancedsky.island;

import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.snapshot.IslandSnapshot;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoreHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;
    private final IslandSnapshot islandSnapshot;

    public CoreHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandDistributor islandDistributor, IslandSnapshot islandSnapshot) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.islandSnapshot = islandSnapshot;
    }

    public CompletableFuture<Void> createIsland(UUID ownerUuid) {
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.createIsland(UUID.randomUUID(), ownerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.deleteIsland(actor, islandUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.toggleIslandLock(actor, islandUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid) {
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.toggleIslandPvp(actor, islandUuid), plugin.getBukkitAsyncExecutor());
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
     * The island whose area holds the location, among the islands hosted on this server; null
     * anywhere else. NewSky reads this from the island world's name; here every island shares one
     * world, so the grid decides.
     */
    public UUID getIslandUuidAt(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
            return null;
        }

        Island island = islandSnapshot.getAt(location);
        return island == null ? null : island.getIslandUuid();
    }
}
