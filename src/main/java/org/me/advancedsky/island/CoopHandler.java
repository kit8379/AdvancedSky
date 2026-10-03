package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.PlayerNotOnlineException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.world.WorldHandler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoopHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final LocalIslandTasks tasks;
    private final WorldHandler worldHandler;
    private final IslandDistributor islandDistributor;
    private final OnlinePlayerRegistry onlinePlayerRegistry;

    public CoopHandler(AdvancedSky plugin, DatabaseHandler database, IslandDistributor islandDistributor, OnlinePlayerRegistry onlinePlayerRegistry, LocalIslandTasks tasks, WorldHandler worldHandler) {
        this.plugin = plugin;
        this.database = database;
        this.tasks = tasks;
        this.worldHandler = worldHandler;
        this.islandDistributor = islandDistributor;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
    }

    public CompletableFuture<Void> coopPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return tasks.mutate(islandUuid, () -> {
            if (!onlinePlayerRegistry.isOnline(playerUuid)) throw new PlayerNotOnlineException();
            database.updateCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> unCoopPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return tasks.execute(islandUuid, () -> tasks.refresh(islandUuid, () -> {
            database.deleteCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null));
    }

    public CompletableFuture<Void> deleteAllCoopOfPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getCoopIslandsOfPlayer(playerUuid), plugin.getBukkitAsyncExecutor()).thenComposeAsync(islands -> {
            if (islands.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.allOf(islands.stream().map(islandUuid -> islandDistributor.removeCoopAfterQuit(islandUuid, playerUuid, () -> unCoopPlayer(new Actor.Bypass("coop quit cleanup"), islandUuid, playerUuid)).exceptionally(e -> {
                plugin.severe("Failed to clean up coop for " + playerUuid + " on island " + islandUuid, e);
                return null;
            })).toArray(CompletableFuture[]::new));
        }, plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Boolean> isPlayerCooped(UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandCoops(islandUuid).contains(playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<UUID>> getCoopedPlayers(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandCoops(islandUuid), plugin.getBukkitAsyncExecutor());
    }

    private int getIslandSlot(UUID islandUuid) {
        return database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
    }
}
