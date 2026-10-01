package org.me.advancedsky.network;

import org.bukkit.Bukkit;
import org.json.JSONObject;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.CreateReservations;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.util.ServerUtil;

import java.util.List;
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
    public static final String ACTION_ISLAND_MEMBER_ADD = "island.member.add";
    public static final String ACTION_ISLAND_MEMBER_REMOVE = "island.member.remove";
    public static final String ACTION_ISLAND_OWNER_SET = "island.owner.set";
    public static final String ACTION_ISLAND_BAN_ADD = "island.ban.add";
    public static final String ACTION_ISLAND_BAN_REMOVE = "island.ban.remove";
    public static final String ACTION_ISLAND_COOP_ADD = "island.coop.add";
    public static final String ACTION_ISLAND_COOP_REMOVE = "island.coop.remove";
    public static final String ACTION_ISLAND_LOCK_TOGGLE = "island.lock.toggle";
    public static final String ACTION_ISLAND_PVP_TOGGLE = "island.pvp.toggle";
    public static final String ACTION_ISLAND_EXPEL = "island.expel";
    public static final String ACTION_ISLAND_HOME_SET = "island.home.set";
    public static final String ACTION_ISLAND_HOME_DELETE = "island.home.delete";
    public static final String ACTION_ISLAND_WARP_SET = "island.warp.set";
    public static final String ACTION_ISLAND_WARP_DELETE = "island.warp.delete";
    public static final String ACTION_ISLAND_UPGRADE_SET = "island.upgrade.set";
    public static final String ACTION_ISLAND_UPGRADE_BUY = "island.upgrade.buy";
    public static final String ACTION_PLAYER_CONNECT = "player.connect";

    private static final Function<JSONObject, Void> VOID = response -> null;

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandOperator islandOperator;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final CrossServerMessenger messenger;
    private final CreateReservations createReservations;
    private final List<String> islandServerNames;
    private final String serverID;

    public IslandDistributor(AdvancedSky plugin, DatabaseHandler database, IslandOperator islandOperator, OnlinePlayerRegistry onlinePlayerRegistry, CrossServerMessenger messenger, CreateReservations createReservations, List<String> islandServerNames, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.islandOperator = islandOperator;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.messenger = messenger;
        this.createReservations = createReservations;
        this.islandServerNames = List.copyOf(islandServerNames);
        this.serverID = serverID;
    }

    // =====================================================================================
    // Island lifecycle
    // =====================================================================================

    /**
     * On the listed island server with the fewest islands, counting the creates still under way
     * on each, so concurrent creates spread instead of all picking the same one.
     */
    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return supply(() -> {
            String targetServer = createReservations.reserve(islandServerNames, database.getIslandCountByServer(), islandUuid);

            CompletableFuture<Void> created;
            if (targetServer.equals(serverID)) {
                created = islandOperator.createIsland(islandUuid, ownerUuid);
            } else {
                JSONObject payload = new JSONObject();
                payload.put("islandUuid", islandUuid.toString());
                payload.put("ownerUuid", ownerUuid.toString());
                created = messenger.requestVoid(targetServer, ACTION_ISLAND_CREATE, payload);
            }

            // Counted by the database from now on, or never.
            return created.whenCompleteAsync((v, error) -> {
                try {
                    createReservations.release(targetServer, islandUuid);
                } catch (RuntimeException releaseError) {
                    // Already logged; the reservation expires on its own.
                }
            }, plugin.getBukkitAsyncExecutor());
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
                String registered = onlinePlayerRegistry.getOnlinePlayerServer(playerUuid);
                if (registered != null) {
                    return CompletableFuture.completedFuture(registered);
                }

                // No entry reads as offline, but a join's entry may not be written yet: a player
                // here is on this server whatever the registry says.
                return CompletableFuture.supplyAsync(() -> Bukkit.getPlayer(playerUuid) != null ? serverID : null, plugin.getGlobalRegionExecutor());
            }, plugin.getBukkitAsyncExecutor()).thenComposeAsync(playerServer -> {
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

    public CompletableFuture<Void> addMember(Actor actor, UUID islandUuid, UUID playerUuid, String role) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        payload.put("role", role);
        return onIsland(islandUuid, ACTION_ISLAND_MEMBER_ADD, payload, () -> islandOperator.addMember(actor, islandUuid, playerUuid, role), VOID);
    }

    public CompletableFuture<Void> removeMember(Actor actor, UUID islandUuid, UUID playerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_MEMBER_REMOVE, payload, () -> islandOperator.removeMember(actor, islandUuid, playerUuid), VOID);
    }

    public CompletableFuture<Void> setOwner(Actor actor, UUID islandUuid, UUID newOwnerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("newOwnerUuid", newOwnerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_OWNER_SET, payload, () -> islandOperator.setOwner(actor, islandUuid, newOwnerUuid), VOID);
    }

    public CompletableFuture<Void> addBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_BAN_ADD, payload, () -> islandOperator.addBan(actor, islandUuid, playerUuid), VOID);
    }

    public CompletableFuture<Void> removeBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_BAN_REMOVE, payload, () -> islandOperator.removeBan(actor, islandUuid, playerUuid), VOID);
    }

    public CompletableFuture<Void> addCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_COOP_ADD, payload, () -> islandOperator.addCoop(actor, islandUuid, playerUuid), VOID);
    }

    public CompletableFuture<Void> removeCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_COOP_REMOVE, payload, () -> islandOperator.removeCoop(actor, islandUuid, playerUuid), VOID);
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return onIsland(islandUuid, ACTION_ISLAND_LOCK_TOGGLE, islandActorPayload(actor, islandUuid), () -> islandOperator.toggleIslandLock(actor, islandUuid), resp -> resp.getBoolean("locked"));
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid) {
        return onIsland(islandUuid, ACTION_ISLAND_PVP_TOGGLE, islandActorPayload(actor, islandUuid), () -> islandOperator.toggleIslandPvp(actor, islandUuid), resp -> resp.getBoolean("pvp"));
    }

    public CompletableFuture<Void> expelPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("playerUuid", playerUuid.toString());
        return onIsland(islandUuid, ACTION_ISLAND_EXPEL, payload, () -> islandOperator.expelPlayer(actor, islandUuid, playerUuid), VOID);
    }

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

    public CompletableFuture<Void> setWarp(Actor actor, UUID islandUuid, String warpName, String warpLocation) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("warpName", warpName);
        payload.put("warpLocation", warpLocation);
        return onIsland(islandUuid, ACTION_ISLAND_WARP_SET, payload, () -> islandOperator.setWarp(actor, islandUuid, warpName, warpLocation), VOID);
    }

    public CompletableFuture<Void> deleteWarp(Actor actor, UUID islandUuid, String warpName) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("warpName", warpName);
        return onIsland(islandUuid, ACTION_ISLAND_WARP_DELETE, payload, () -> islandOperator.deleteWarp(actor, islandUuid, warpName), VOID);
    }

    public CompletableFuture<Void> setUpgradeLevel(Actor actor, UUID islandUuid, String upgradeId, int expectedLevel, int newLevel) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("upgradeId", upgradeId);
        payload.put("expectedLevel", expectedLevel);
        payload.put("newLevel", newLevel);
        return onIsland(islandUuid, ACTION_ISLAND_UPGRADE_SET, payload, () -> islandOperator.setUpgradeLevel(actor, islandUuid, upgradeId, expectedLevel, newLevel), VOID);
    }

    public CompletableFuture<Void> buyUpgrade(Actor actor, UUID islandUuid, UUID payerUuid, String upgradeId, int expectedLevel, int newLevel, double price) {
        JSONObject payload = islandActorPayload(actor, islandUuid);
        payload.put("payerUuid", payerUuid.toString());
        payload.put("upgradeId", upgradeId);
        payload.put("expectedLevel", expectedLevel);
        payload.put("newLevel", newLevel);
        payload.put("price", price);
        return onIsland(islandUuid, ACTION_ISLAND_UPGRADE_BUY, payload, () -> islandOperator.buyUpgrade(actor, islandUuid, payerUuid, upgradeId, expectedLevel, newLevel, price), VOID);
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

    private static <T> CompletableFuture<T> supply(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
