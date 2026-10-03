package org.me.advancedsky.network;

import net.kyori.adventure.text.Component;
import org.json.JSONObject;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.cluster.ServerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.PlayerNotOnlineException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.util.ComponentUtils;
import org.me.advancedsky.util.ServerUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Island management runs locally. Only creation, travel, invitation acceptance and automatic
 * co-op cleanup are routed between servers.
 * Travel futures acknowledge routing on cross-server trips; the destination delivers any
 * attached success message independently, after confirmed arrival.
 */
public class IslandDistributor {

    public static final String ACTION_ISLAND_CREATE = "island.create";
    public static final String ACTION_ISLAND_TELEPORT_PREPARE = "island.teleport.prepare";
    public static final String ACTION_INVITATION_ACCEPT = "island.invitation.accept";
    public static final String ACTION_COOP_QUIT = "island.coop.quit";
    public static final String ACTION_PLAYER_CONNECT = "player.connect";

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final TeleportHandler teleportHandler;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final CrossServerMessenger messenger;
    private final ServerRegistry serverRegistry;
    private final String serverID;

    public IslandDistributor(AdvancedSky plugin, DatabaseHandler database, TeleportHandler teleportHandler, OnlinePlayerRegistry onlinePlayerRegistry, CrossServerMessenger messenger, ServerRegistry serverRegistry, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.teleportHandler = teleportHandler;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.messenger = messenger;
        this.serverRegistry = serverRegistry;
        this.serverID = serverID;
    }

    // =====================================================================================
    // Island lifecycle
    // =====================================================================================

    /**
     * Picks a ready island server with the fewest islands; ties use server name order.
     */
    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid, Supplier<CompletableFuture<Void>> localCreate) {
        return supply(() -> {
            Map<String, Integer> islandCounts = database.getIslandCountByServer();
            String targetServer = serverRegistry.getIslandServers().stream()
                    .min(Comparator.comparingInt(server -> islandCounts.getOrDefault(server, 0)))
                    .orElseThrow(() -> new IllegalStateException("No island server is online"));

            if (targetServer.equals(serverID)) {
                return localCreate.get();
            }

            JSONObject payload = new JSONObject();
            payload.put("islandUuid", islandUuid.toString());
            payload.put("ownerUuid", ownerUuid.toString());
            return messenger.requestVoid(targetServer, ACTION_ISLAND_CREATE, payload);
        });
    }

    public CompletableFuture<Void> teleportIsland(UUID islandUuid, UUID playerUuid, String teleportLocation) {
        return teleportIsland(islandUuid, playerUuid, teleportLocation, null);
    }

    /** The destination delivers the optional message after the player actually arrives. */
    public CompletableFuture<Void> teleportIsland(UUID islandUuid, UUID playerUuid, String teleportLocation, Component successMessage) {
        return supply(() -> {
            String host = resolveHost(islandUuid);
            return teleportViaServer(host, playerUuid, host, teleportLocation, successMessage);
        });
    }

    public CompletableFuture<Void> teleportLobby(UUID playerUuid, String lobbyWorld, String lobbyLocation) {
        return teleportLobby(playerUuid, lobbyWorld, lobbyLocation, null);
    }

    public CompletableFuture<Void> teleportLobby(UUID playerUuid, String lobbyWorld, String lobbyLocation, Component successMessage) {
        return supply(() -> {
            List<String> lobbyServers = serverRegistry.getLobbyServers();
            if (lobbyServers.isEmpty()) {
                throw new IllegalStateException("No lobby server is online");
            }
            String lobbyServer = lobbyServers.get(ThreadLocalRandom.current().nextInt(lobbyServers.size()));
            return teleportViaServer(lobbyServer, playerUuid, lobbyWorld, lobbyLocation, successMessage);
        });
    }

    private CompletableFuture<Void> teleportViaServer(String hostServer, UUID playerUuid, String teleportWorld, String teleportLocation, Component successMessage) {
        CompletableFuture<Boolean> prepared;
        if (hostServer.equals(serverID)) {
            prepared = successMessage == null
                    ? teleportHandler.prepareTeleport(playerUuid, teleportWorld, teleportLocation)
                    : teleportHandler.prepareTeleport(playerUuid, teleportWorld, teleportLocation, successMessage);
        } else {
            JSONObject payload = new JSONObject();
            payload.put("playerUuid", playerUuid.toString());
            payload.put("teleportWorld", teleportWorld);
            payload.put("teleportLocation", teleportLocation);
            payload.put("successMessage", successMessage == null ? JSONObject.NULL : ComponentUtils.serialize(successMessage));
            prepared = messenger.request(hostServer, ACTION_ISLAND_TELEPORT_PREPARE, payload).thenApply(resp -> resp.getBoolean("teleported"));
        }

        return prepared.thenCompose(teleported -> {
            // Teleported in place by the prepare step: nothing left to route.
            if (teleported) {
                return CompletableFuture.completedFuture(null);
            }

            // The registry read must stay off the global region thread; a local prepare completes there.
            return CompletableFuture.supplyAsync(() -> onlinePlayerRegistry.getOnlinePlayerServer(playerUuid),
                    plugin.getBukkitAsyncExecutor()).thenComposeAsync(playerServer -> {
                if (playerServer == null) {
                    return CompletableFuture.failedFuture(new PlayerNotOnlineException());
                }
                // Already on the host (arrived since the prepare step): nothing to do.
                if (playerServer.equals(hostServer)) {
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
    // Invitation acceptance and automatic cleanup
    // =====================================================================================

    /**
     * Joining an invitation is intentionally available before the invitee reaches the island.
     */
    public CompletableFuture<Void> acceptMember(UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localAccept) {
        return onIsland(islandUuid, ACTION_INVITATION_ACCEPT, playerIslandPayload(islandUuid, playerUuid), localAccept);
    }

    /**
     * Automatic quit cleanup reaches the host; ordinary uncoop commands stay local.
     */
    public CompletableFuture<Void> removeCoopAfterQuit(UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localCleanup) {
        return onIsland(islandUuid, ACTION_COOP_QUIT, playerIslandPayload(islandUuid, playerUuid), localCleanup);
    }

    // =====================================================================================
    // Routing
    // =====================================================================================

    /**
     * Runs the operation here when this server hosts the island, otherwise on the host.
     */
    private CompletableFuture<Void> onIsland(UUID islandUuid, String action, JSONObject payload, Supplier<CompletableFuture<Void>> operation) {
        return supply(() -> {
            String host = resolveHost(islandUuid);

            if (host.equals(serverID)) {
                return operation.get();
            }

            return messenger.requestVoid(host, action, payload);
        });
    }

    private String resolveHost(UUID islandUuid) {
        return database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
    }

    private JSONObject playerIslandPayload(UUID islandUuid, UUID playerUuid) {
        JSONObject payload = new JSONObject();
        payload.put("islandUuid", islandUuid.toString());
        payload.put("playerUuid", playerUuid.toString());
        return payload;
    }

    private static <T> CompletableFuture<T> supply(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
