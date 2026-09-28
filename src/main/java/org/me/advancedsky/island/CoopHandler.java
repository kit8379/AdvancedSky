package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.PlayerNotOnlineException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoopHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;
    private final OnlinePlayerRegistry onlinePlayerRegistry;

    public CoopHandler(AdvancedSky plugin, DatabaseHandler database, IslandDistributor islandDistributor, OnlinePlayerRegistry onlinePlayerRegistry) {
        this.plugin = plugin;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
    }

    public CompletableFuture<Void> coopPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.runAsync(() -> {
            if (!onlinePlayerRegistry.isOnline(playerUuid)) {
                throw new PlayerNotOnlineException();
            }
        }, plugin.getBukkitAsyncExecutor()).thenCompose(v -> {
            return islandDistributor.addCoop(actor, islandUuid, playerUuid);
        });
    }

    public CompletableFuture<Void> unCoopPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.removeCoop(actor, islandUuid, playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Void> deleteAllCoopOfPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getCoopIslandsOfPlayer(playerUuid), plugin.getBukkitAsyncExecutor()).thenCompose(islands -> {
            if (islands.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.allOf(islands.stream().map(islandUuid -> islandDistributor.removeCoop(new Actor.Bypass("coop quit cleanup"), islandUuid, playerUuid).exceptionally(e -> {
                plugin.severe("Failed to clean up coop for " + playerUuid + " on island " + islandUuid, e);
                return null;
            })).toArray(CompletableFuture[]::new));
        });
    }

    public CompletableFuture<Boolean> isPlayerCooped(UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandCoops(islandUuid).contains(playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<UUID>> getCoopedPlayers(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandCoops(islandUuid), plugin.getBukkitAsyncExecutor());
    }
}
