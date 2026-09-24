package org.me.advancedsky.island;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.teleport.TeleportHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Island lifecycle. Writes MySQL first, then applies the change to the world: locally when this
 * server hosts the island, otherwise by messaging the hosting server.
 */
public class IslandHandler {

    public static final String ACTION_ISLAND_CREATE = "island.create";
    public static final String ACTION_ISLAND_DELETE = "island.delete";

    private static final String FIELD_SLOT = "slot";
    private static final String FIELD_ISLAND_UUID = "islandUuid";

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final CrossServerMessenger messenger;
    private final IslandWorldHandler worldHandler;
    private final TeleportHandler teleportHandler;
    private final Executor asyncExecutor;
    private final String serverName;
    private final List<String> islandServers;

    public IslandHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, CrossServerMessenger messenger, IslandWorldHandler worldHandler, TeleportHandler teleportHandler, Executor asyncExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.messenger = messenger;
        this.worldHandler = worldHandler;
        this.teleportHandler = teleportHandler;
        this.asyncExecutor = asyncExecutor;
        this.serverName = config.getServerName();
        this.islandServers = List.copyOf(config.getIslandServers());

        if (islandServers.isEmpty()) {
            throw new IllegalStateException("'servers.island' must list at least one island server");
        }

        // Requests from other servers for islands hosted here.
        messenger.register(ACTION_ISLAND_CREATE, payload -> logFailure(worldHandler.createIsland(payload.get(FIELD_SLOT).getAsInt()), "create", payload));
        messenger.register(ACTION_ISLAND_DELETE, payload -> logFailure(worldHandler.deleteIsland(payload.get(FIELD_SLOT).getAsInt()), "delete", payload));
    }

    /**
     * Creates an island for the player on the least loaded island server and takes the player there.
     * Completes once the player has been teleported (local) or handed to the proxy (remote).
     */
    public CompletableFuture<Void> createIsland(Player player) {
        UUID ownerUuid = player.getUniqueId();

        return CompletableFuture.supplyAsync(() -> database.insertIsland(ownerUuid, chooseServer()), asyncExecutor).thenCompose(island -> {
            plugin.debug("IslandHandler", "Created island " + island.islandUuid() + " for " + ownerUuid + " on " + island.server() + " slot " + island.slot());

            if (isLocal(island)) {
                return worldHandler.createIsland(island.slot()).thenCompose(v -> teleportHandler.teleportToLocalIsland(player, island));
            }

            messenger.send(island.server(), ACTION_ISLAND_CREATE, payload(island));
            player.sendMessage(config.getSendingToServerMessage(island.server()));
            teleportHandler.sendToIslandServer(player, island);
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Deletes the player's island.
     */
    public CompletableFuture<Void> deleteIsland(Player player) {
        UUID ownerUuid = player.getUniqueId();

        return CompletableFuture.supplyAsync(() -> {
            Island island = database.findIslandByOwner(ownerUuid).orElseThrow(IslandDoesNotExistException::new);
            database.deleteIsland(island.islandUuid());
            return island;
        }, asyncExecutor).thenCompose(island -> {
            plugin.debug("IslandHandler", "Deleted island " + island.islandUuid() + " of " + ownerUuid + " on " + island.server() + " slot " + island.slot());

            if (isLocal(island)) {
                return worldHandler.deleteIsland(island.slot());
            }

            messenger.send(island.server(), ACTION_ISLAND_DELETE, payload(island));
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Finishes a cross-server island teleport for a player who has just joined this server.
     */
    public void completePendingTeleport(Player player) {
        UUID playerUuid = player.getUniqueId();

        CompletableFuture.supplyAsync(() -> teleportHandler.consumePendingTeleport(playerUuid).flatMap(database::findIsland), asyncExecutor).thenCompose(pending -> {
            if (pending.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            Island island = pending.get();
            if (!isLocal(island)) {
                plugin.warning("Player " + playerUuid + " arrived here with a pending teleport to island " + island.islandUuid() + " hosted on " + island.server());
                return CompletableFuture.completedFuture(null);
            }

            // The island may still be pasting if the create request arrived just before the player did.
            return worldHandler.awaitBuild(island.slot()).thenCompose(v -> teleportHandler.teleportToLocalIsland(player, island));
        }).exceptionally(ex -> {
            plugin.severe("Failed to complete pending teleport for " + playerUuid, ex);
            return null;
        });
    }

    /**
     * The configured island server with the fewest islands.
     */
    private String chooseServer() {
        Map<String, Integer> counts = database.countIslandsByServer();
        return islandServers.stream().min(Comparator.comparingInt(server -> counts.getOrDefault(server, 0))).orElseThrow();
    }

    private boolean isLocal(Island island) {
        return serverName.equals(island.server());
    }

    private static JsonObject payload(Island island) {
        JsonObject payload = new JsonObject();
        payload.addProperty(FIELD_ISLAND_UUID, island.islandUuid().toString());
        payload.addProperty(FIELD_SLOT, island.slot());
        return payload;
    }

    private void logFailure(CompletableFuture<Void> future, String action, JsonObject payload) {
        future.exceptionally(ex -> {
            plugin.severe("Failed to " + action + " island from cross-server request " + payload, ex);
            return null;
        });
    }
}
