package org.me.advancedsky.uuid;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UuidHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;

    public UuidHandler(AdvancedSky plugin, DatabaseHandler database) {
        this.plugin = plugin;
        this.database = database;
    }

    public CompletableFuture<Void> updatePlayerUuid(UUID uuid, String name) {
        return CompletableFuture.runAsync(() -> database.updatePlayerName(uuid, name), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Optional<UUID>> getPlayerUuid(String name) {
        return CompletableFuture.supplyAsync(() -> database.getPlayerUuid(name), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Optional<String>> getPlayerName(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> database.getPlayerName(uuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Map<UUID, String>> getPlayerNames(Collection<UUID> uuids) {
        return CompletableFuture.supplyAsync(() -> database.getPlayerNames(uuids), plugin.getBukkitAsyncExecutor());
    }
}
