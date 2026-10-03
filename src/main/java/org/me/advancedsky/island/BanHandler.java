package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandOperator;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BanHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandOperator islandOperator;

    public BanHandler(AdvancedSky plugin, DatabaseHandler database, IslandOperator islandOperator) {
        this.plugin = plugin;
        this.database = database;
        this.islandOperator = islandOperator;
    }

    public CompletableFuture<Void> banPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return islandOperator.addBan(actor, islandUuid, playerUuid);
    }

    public CompletableFuture<Void> unbanPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return islandOperator.removeBan(actor, islandUuid, playerUuid);
    }

    public CompletableFuture<Boolean> isPlayerBanned(UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandBans(islandUuid).contains(playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<UUID>> getBannedPlayers(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandBans(islandUuid), plugin.getBukkitAsyncExecutor());
    }
}
