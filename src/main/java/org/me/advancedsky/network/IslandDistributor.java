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
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.util.ComponentUtils;
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
 * Routes island management to the island's host. Location-based edits run locally;
 * upgrade purchases require the player to be on their own island.
 * Travel futures acknowledge routing on cross-server trips; the destination delivers any
 * attached success message independently, after confirmed arrival.
 */
public class IslandDistributor {

    public static final String ACTION_ISLAND_CREATE = "island.create";
    public static final String ACTION_ISLAND_TELEPORT_PREPARE = "island.teleport.prepare";
    public static final String ACTION_PLAYER_CONNECT = "player.connect";
    public static final String ACTION_ISLAND_DELETE = "island.delete";
    public static final String ACTION_MEMBER_ADD = "island.member.add";
    public static final String ACTION_MEMBER_REMOVE = "island.member.remove";
    public static final String ACTION_OWNER_SET = "island.owner.set";
    public static final String ACTION_PLAYER_EXPEL = "island.player.expel";
    public static final String ACTION_BAN_ADD = "island.ban.add";
    public static final String ACTION_BAN_REMOVE = "island.ban.remove";
    public static final String ACTION_COOP_ADD = "island.coop.add";
    public static final String ACTION_COOP_REMOVE = "island.coop.remove";
    public static final String ACTION_HOME_DELETE = "island.home.delete";
    public static final String ACTION_WARP_DELETE = "island.warp.delete";
    public static final String ACTION_LOCK_TOGGLE = "island.lock.toggle";
    public static final String ACTION_PVP_TOGGLE = "island.pvp.toggle";

    private static final Function<JSONObject, Void> VOID = response -> null;

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
            String targetServer = serverRegistry.getIslandServers().stream().min(Comparator.comparingInt(server -> islandCounts.getOrDefault(server, 0))).orElseThrow(() -> new IllegalStateException("No island server is online"));

            if (targetServer.equals(serverID)) {
                return localCreate.get();
            }

            JSONObject payload = new JSONObject();
            payload.put("islandUuid", islandUuid.toString());
            payload.put("ownerUuid", ownerUuid.toString());
            return messenger.requestVoid(targetServer, ACTION_ISLAND_CREATE, payload);
        });
    }

    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid, Supplier<CompletableFuture<Void>> localDelete) {
        return onIsland(actor, islandUuid, ACTION_ISLAND_DELETE, new JSONObject(), localDelete, VOID);
    }

    public CompletableFuture<Void> teleportIsland(UUID islandUuid, UUID playerUuid, String teleportLocation) {
        return teleportIsland(islandUuid, playerUuid, teleportLocation, null);
    }

    /**
     * The destination delivers the optional message after the player actually arrives.
     */
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
            prepared = successMessage == null ? teleportHandler.prepareTeleport(playerUuid, teleportWorld, teleportLocation) : teleportHandler.prepareTeleport(playerUuid, teleportWorld, teleportLocation, successMessage);
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
            return CompletableFuture.supplyAsync(() -> onlinePlayerRegistry.getOnlinePlayerServer(playerUuid), plugin.getBukkitAsyncExecutor()).thenComposeAsync(playerServer -> {
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
    // Island mutations
    // =====================================================================================

    public CompletableFuture<Void> addMember(Actor actor, UUID islandUuid, UUID playerUuid, String role, Supplier<CompletableFuture<Void>> localAdd) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString()).put("role", role);
        return onIsland(actor, islandUuid, ACTION_MEMBER_ADD, payload, localAdd, VOID);
    }

    public CompletableFuture<Void> removeMember(Actor actor, UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localRemove) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_MEMBER_REMOVE, payload, localRemove, VOID);
    }

    public CompletableFuture<Void> setOwner(Actor actor, UUID islandUuid, UUID newOwnerUuid, Supplier<CompletableFuture<Void>> localSet) {
        JSONObject payload = new JSONObject().put("targetUuid", newOwnerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_OWNER_SET, payload, localSet, VOID);
    }

    public CompletableFuture<Void> expelPlayer(Actor actor, UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localExpel) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_PLAYER_EXPEL, payload, localExpel, VOID);
    }

    public CompletableFuture<Void> addBan(Actor actor, UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localAdd) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_BAN_ADD, payload, localAdd, VOID);
    }

    public CompletableFuture<Void> removeBan(Actor actor, UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localRemove) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_BAN_REMOVE, payload, localRemove, VOID);
    }

    public CompletableFuture<Void> addCoop(Actor actor, UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localAdd) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_COOP_ADD, payload, localAdd, VOID);
    }

    public CompletableFuture<Void> removeCoop(Actor actor, UUID islandUuid, UUID playerUuid, Supplier<CompletableFuture<Void>> localRemove) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString());
        return onIsland(actor, islandUuid, ACTION_COOP_REMOVE, payload, localRemove, VOID);
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid, Supplier<CompletableFuture<Boolean>> localToggle) {
        return onIsland(actor, islandUuid, ACTION_LOCK_TOGGLE, new JSONObject(), localToggle, response -> response.getBoolean("enabled"));
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid, Supplier<CompletableFuture<Boolean>> localToggle) {
        return onIsland(actor, islandUuid, ACTION_PVP_TOGGLE, new JSONObject(), localToggle, response -> response.getBoolean("enabled"));
    }

    public CompletableFuture<Void> deleteHome(Actor actor, UUID islandUuid, UUID playerUuid, String homeName, Supplier<CompletableFuture<Void>> localDelete) {
        JSONObject payload = new JSONObject().put("targetUuid", playerUuid.toString()).put("pointName", homeName);
        return onIsland(actor, islandUuid, ACTION_HOME_DELETE, payload, localDelete, VOID);
    }

    public CompletableFuture<Void> deleteWarp(Actor actor, UUID islandUuid, String warpName, Supplier<CompletableFuture<Void>> localDelete) {
        JSONObject payload = new JSONObject().put("pointName", warpName);
        return onIsland(actor, islandUuid, ACTION_WARP_DELETE, payload, localDelete, VOID);
    }

    // =====================================================================================
    // Routing
    // =====================================================================================

    private <T> CompletableFuture<T> onIsland(Actor actor, UUID islandUuid, String action, JSONObject payload, Supplier<CompletableFuture<T>> operation, Function<JSONObject, T> readResponse) {
        payload.put("islandUuid", islandUuid.toString());
        payload.put(Actor.FIELD, Actor.toJson(actor));
        return CompletableFuture.supplyAsync(() -> resolveHost(islandUuid), plugin.getBukkitAsyncExecutor()).thenCompose(host -> {
            if (host.equals(serverID)) {
                return supply(operation);
            }

            return messenger.request(host, action, payload).thenApply(readResponse);
        });
    }

    private String resolveHost(UUID islandUuid) {
        return database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
    }

    private static <T> CompletableFuture<T> supply(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
