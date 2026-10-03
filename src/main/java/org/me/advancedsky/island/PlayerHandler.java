package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.InvitationStore;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Invitation;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.network.IslandOperator;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class PlayerHandler {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandOperator islandOperator;
    private final IslandDistributor islandDistributor;
    private final InvitationStore invitationStore;
    private final OnlinePlayerRegistry onlinePlayerRegistry;

    public PlayerHandler(AdvancedSky plugin, DatabaseHandler database, IslandDistributor islandDistributor, InvitationStore invitationStore, OnlinePlayerRegistry onlinePlayerRegistry, IslandOperator islandOperator) {
        this.plugin = plugin;
        this.database = database;
        this.islandOperator = islandOperator;
        this.islandDistributor = islandDistributor;
        this.invitationStore = invitationStore;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
    }

    public CompletableFuture<Void> addMember(Actor actor, UUID islandUuid, UUID playerUuid, String role) {
        return islandOperator.addMember(actor, islandUuid, playerUuid, role);
    }

    public CompletableFuture<Void> acceptInviteMember(UUID islandUuid, UUID playerUuid) {
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.acceptMember(islandUuid, playerUuid, () -> addMember(new Actor.Player(playerUuid), islandUuid, playerUuid, "member")), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Void> removeMember(Actor actor, UUID islandUuid, UUID playerUuid) {
        return islandOperator.removeMember(actor, islandUuid, playerUuid);
    }

    public CompletableFuture<Void> setOwner(Actor actor, UUID islandUuid, UUID newOwnerUuid) {
        return islandOperator.setOwner(actor, islandUuid, newOwnerUuid);
    }

    public CompletableFuture<Void> expelPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return islandOperator.expelPlayer(actor, islandUuid, playerUuid);
    }

    public CompletableFuture<Void> addPendingInvite(UUID inviteeUuid, UUID islandUuid, UUID inviterUuid, int ttlSeconds) {
        return CompletableFuture.runAsync(() -> {
            if (!onlinePlayerRegistry.isOnline(inviteeUuid)) {
                throw new PlayerNotOnlineException();
            }

            Set<UUID> members = database.getIslandPlayers(islandUuid).keySet();
            if (!members.contains(inviterUuid)) {
                throw new IslandDoesNotExistException();
            }
            if (members.contains(inviteeUuid)) {
                throw new IslandPlayerAlreadyExistsException();
            }

            Optional<UUID> existingIsland = database.getIslandUuid(inviteeUuid);
            if (existingIsland.isPresent() && !existingIsland.get().equals(islandUuid)) {
                throw new IslandAlreadyExistException();
            }

            if (database.getIslandBans(islandUuid).contains(inviteeUuid)) {
                throw new PlayerAlreadyBannedException();
            }

            if (!invitationStore.addIslandInvite(inviteeUuid, islandUuid, inviterUuid, ttlSeconds)) {
                throw new InvitedAlreadyException();
            }
        }, plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Void> removePendingInvite(UUID playerUuid) {
        return CompletableFuture.runAsync(() -> invitationStore.removeIslandInvite(playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Optional<Invitation>> getPendingInvite(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> invitationStore.getIslandInvite(playerUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<UUID> getIslandOwner(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandOwner(islandUuid).orElseThrow(IslandDoesNotExistException::new), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<UUID>> getIslandMembers(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandPlayers(islandUuid).entrySet().stream().filter(entry -> "member".equalsIgnoreCase(entry.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet()), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Set<UUID>> getIslandPlayers(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandPlayers(islandUuid).keySet(), plugin.getBukkitAsyncExecutor());
    }
}
