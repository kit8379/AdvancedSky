package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoreHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;

    public CoreHandler(AdvancedSky plugin, DatabaseHandler database, IslandDistributor islandDistributor) {
        this.plugin = plugin;
        this.database = database;
        this.islandDistributor = islandDistributor;
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
}
