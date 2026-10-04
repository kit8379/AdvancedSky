package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.network.IslandOperator;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CoopHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandOperator islandOperator;
    private final IslandDistributor islandDistributor;

    public CoopHandler(AdvancedSky plugin, DatabaseHandler database, IslandDistributor islandDistributor, IslandOperator islandOperator) {
        this.plugin = plugin;
        this.database = database;
        this.islandOperator = islandOperator;
        this.islandDistributor = islandDistributor;
    }

    public CompletableFuture<Void> coopPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return islandDistributor.addCoop(actor, islandUuid, playerUuid, () -> islandOperator.addCoop(actor, islandUuid, playerUuid));
    }

    public CompletableFuture<Void> unCoopPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return islandDistributor.removeCoop(actor, islandUuid, playerUuid, () -> islandOperator.removeCoop(actor, islandUuid, playerUuid));
    }

    public CompletableFuture<Void> deleteAllCoopOfPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getCoopIslandsOfPlayer(playerUuid), plugin.getBukkitAsyncExecutor()).thenComposeAsync(islands -> {
            if (islands.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            return CompletableFuture.allOf(islands.stream().map(islandUuid -> unCoopPlayer(new Actor.Bypass("coop quit cleanup"), islandUuid, playerUuid).exceptionally(e -> {
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
}
