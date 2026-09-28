package org.me.advancedsky.network;

import org.json.JSONObject;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.util.ServerUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
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
    public static final String ACTION_ISLAND_TELEPORT_PREPARE = "island.teleport.prepare";
    public static final String ACTION_ISLAND_HOME_SET = "island.home.set";
    public static final String ACTION_ISLAND_HOME_DELETE = "island.home.delete";
    public static final String ACTION_PLAYER_CONNECT = "player.connect";

    private static final Function<JSONObject, Void> VOID = response -> null;

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandOperator islandOperator;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final CrossServerMessenger messenger;
    private final List<String> islandServerNames;
    private final String serverID;

    public IslandDistributor(AdvancedSky plugin, DatabaseHandler database, IslandOperator islandOperator, OnlinePlayerRegistry onlinePlayerRegistry, CrossServerMessenger messenger, List<String> islandServerNames, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.islandOperator = islandOperator;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
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

    public CompletableFuture<Void> teleportIsland(UUID islandUuid, UUID playerUuid, String teleportWorld, String teleportLocation) {
        return supply(() -> teleportViaServer(resolveHost(islandUuid), playerUuid, teleportWorld, teleportLocation));
    }

    public CompletableFuture<Void> teleportLobby(UUID playerUuid, List<String> lobbyServers, String lobbyWorld, String lobbyLocation) {
        if (lobbyServers.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("servers.lobby lists no lobby server"));
        }

        String lobbyServer = lobbyServers.get(ThreadLocalRandom.current().nextInt(lobbyServers.size()));
        return teleportViaServer(lobbyServer, playerUuid, lobbyWorld, lobbyLocation);
    }

    private CompletableFuture<Void> teleportViaServer(String hostServer, UUID playerUuid, String teleportWorld, String teleportLocation) {
        CompletableFuture<Boolean> prepared;
        if (hostServer.equals(serverID)) {
            prepared = islandOperator.prepareTeleport(playerUuid, teleportWorld, teleportLocation);
        } else {
            JSONObject payload = new JSONObject();
            payload.put("playerUuid", playerUuid.toString());
            payload.put("teleportWorld", teleportWorld);
            payload.put("teleportLocation", teleportLocation);
            prepared = messenger.request(hostServer, ACTION_ISLAND_TELEPORT_PREPARE, payload).thenApply(resp -> resp.getBoolean("teleported"));
        }

        return prepared.thenCompose(teleported -> {
            // Teleported in place by the prepare step: nothing left to route.
            if (teleported) {
                return CompletableFuture.completedFuture(null);
            }

            // The registry read must stay off the global region thread; a local prepare completes there.
            return CompletableFuture.completedFuture(null).thenComposeAsync(v -> {
                String playerServer = onlinePlayerRegistry.getOnlinePlayerServer(playerUuid);

                // Already on the host (arrived since the prepare step): nothing to do.
                // Offline: the pending teleport fires on their next join to the host.
                if (playerServer == null || playerServer.equals(hostServer)) {
                    return CompletableFuture.completedFuture(null);
                }

                if (playerServer.equals(serverID)) {
                    return ServerUtil.connectToServer(plugin, playerUuid, hostServer);
                }

                JSONObject payload = new JSONObject();
                payload.put("playerUuid", playerUuid.toString());
                payload.put("targetServer", hostServer);
                return messenger.requestVoid(playerServer, ACTION_PLAYER_CONNECT, payload);
            }, plugin.getBukkitAsyncExecutor());
        });
    }

    // =====================================================================================
    // Island mutations
    // =====================================================================================

    public CompletableFuture<Void> setHome(Actor actor, UUID islandUuid, UUID playerUuid, String homeName, String homeLocation) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        payload.put("homeName", homeName);
        payload.put("homeLocation", homeLocation);
        return onIsland(islandUuid, ACTION_ISLAND_HOME_SET, payload, () -> islandOperator.setHome(actor, islandUuid, playerUuid, homeName, homeLocation), VOID);
    }

    public CompletableFuture<Void> deleteHome(UUID islandUuid, UUID playerUuid, String homeName) {
        JSONObject payload = new JSONObject();
        payload.put("islandUuid", islandUuid.toString());
        payload.put("playerUuid", playerUuid.toString());
        payload.put("homeName", homeName);
        return onIsland(islandUuid, ACTION_ISLAND_HOME_DELETE, payload, () -> islandOperator.deleteHome(islandUuid, playerUuid, homeName), VOID);
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
