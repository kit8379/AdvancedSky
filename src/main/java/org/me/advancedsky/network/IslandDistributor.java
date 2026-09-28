package org.me.advancedsky.network;

import org.json.JSONObject;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Routes island work to the island's host: the server stored with the island, fixed for its
 * whole life. Work for an island hosted here runs locally; anything else is a request to the host
 * that completes with the host's result or failure.
 */
public class IslandDistributor {

    public static final String ACTION_ISLAND_CREATE = "island.create";
    public static final String ACTION_ISLAND_DELETE = "island.delete";

    private static final Function<JSONObject, Void> VOID = response -> null;

    private final DatabaseHandler database;
    private final IslandOperator islandOperator;
    private final CrossServerMessenger messenger;
    private final List<String> islandServerNames;
    private final String serverID;

    public IslandDistributor(DatabaseHandler database, IslandOperator islandOperator, CrossServerMessenger messenger, List<String> islandServerNames, String serverID) {
        this.database = database;
        this.islandOperator = islandOperator;
        this.messenger = messenger;
        this.islandServerNames = List.copyOf(islandServerNames);
        this.serverID = serverID;
    }

    // =====================================================================================
    // Island lifecycle
    // =====================================================================================

    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return supply(() -> {
            String targetServer = selectServer();

            if (targetServer.equals(serverID)) {
                return islandOperator.createIsland(islandUuid, ownerUuid);
            }

            JSONObject payload = new JSONObject();
            payload.put("islandUuid", islandUuid.toString());
            payload.put("ownerUuid", ownerUuid.toString());
            return messenger.requestVoid(targetServer, ACTION_ISLAND_CREATE, payload);
        });
    }

    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return onIsland(islandUuid, ACTION_ISLAND_DELETE, islandActorPayload(actor, islandUuid), () -> islandOperator.deleteIsland(actor, islandUuid), VOID);
    }

    // =====================================================================================
    // Routing
    // =====================================================================================

    /**
     * Runs the operation here when this server hosts the island, otherwise on the host.
     */
    private <T> CompletableFuture<T> onIsland(UUID islandUuid, String action, JSONObject payload, Supplier<CompletableFuture<T>> operation, Function<JSONObject, T> reader) {
        return supply(() -> {
            String host = resolveHost(islandUuid);

            if (host.equals(serverID)) {
                return operation.get();
            }

            return messenger.request(host, action, payload).thenApply(reader);
        });
    }

    private String resolveHost(UUID islandUuid) {
        return database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
    }

    private JSONObject islandActorPayload(Actor actor, UUID islandUuid) {
        JSONObject payload = new JSONObject();
        payload.put("islandUuid", islandUuid.toString());
        payload.put(Actor.FIELD, Actor.toJson(actor));
        return payload;
    }

    /**
     * The listed island server with the fewest islands.
     */
    private String selectServer() {
        Map<String, Integer> counts = database.getIslandCountByServer();
        return islandServerNames.stream().min(Comparator.comparingInt(server -> counts.getOrDefault(server, 0))).orElseThrow();
    }

    private static <T> CompletableFuture<T> supply(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
