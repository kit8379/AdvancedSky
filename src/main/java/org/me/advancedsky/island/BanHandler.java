package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.world.WorldHandler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BanHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final LocalIslandTasks tasks;
    private final WorldHandler worldHandler;

    public BanHandler(AdvancedSky plugin, DatabaseHandler database, LocalIslandTasks tasks, WorldHandler worldHandler) {
        this.plugin = plugin;
        this.database = database;
        this.tasks = tasks;
        this.worldHandler = worldHandler;
    }

    public CompletableFuture<Void> banPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return tasks.execute(islandUuid, () -> tasks.refresh(islandUuid, () -> {
            database.updateBanPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null));
    }

    public CompletableFuture<Void> unbanPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return tasks.mutate(islandUuid, () -> {
            database.deleteBanPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Boolean> isPlayerBanned(UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandBans(islandUuid).contains(playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<UUID>> getBannedPlayers(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandBans(islandUuid), plugin.getBukkitAsyncExecutor());
    }

    private int getIslandSlot(UUID islandUuid) {
        return database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
    }
}
