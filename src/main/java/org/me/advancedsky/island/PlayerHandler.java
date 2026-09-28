package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PlayerHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;

    public PlayerHandler(AdvancedSky plugin, DatabaseHandler database) {
        this.plugin = plugin;
        this.database = database;
    }

    public CompletableFuture<UUID> getIslandOwner(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandOwner(islandUuid).orElseThrow(IslandDoesNotExistException::new), plugin.getBukkitAsyncExecutor());
    }
}
