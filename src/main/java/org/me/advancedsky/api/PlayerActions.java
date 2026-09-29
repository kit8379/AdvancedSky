package org.me.advancedsky.api;

import org.bukkit.Location;
import org.me.advancedsky.island.*;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Invitation;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Everything a player may do, scoped to that player. Obtained via {@code api.player(uuid)}.
 * <p>
 * Two guarantees are structural here, not runtime checks: a player can only act as themselves
 * (there is no parameter to name anyone else as the actor or the subject), and island-scoped
 * operations always target <b>their own island</b> - resolved internally, because a player has
 * exactly one. Operator-only operations simply do not exist on this handle.
 * <p>
 * Island roles (OWNER for delete/transfer, MEMBER for the rest) are still enforced where they
 * must be: inside the write transaction, under the island row lock.
 */
public final class PlayerActions {

    private final UUID playerUuid;
    private final Actor actor;
    private final CoreHandler coreHandler;
    private final PlayerHandler playerHandler;
    private final HomeHandler homeHandler;
    private final WarpHandler warpHandler;
    private final BanHandler banHandler;
    private final CoopHandler coopHandler;
    private final BiomeHandler biomeHandler;
    private final UpgradeHandler upgradeHandler;

    PlayerActions(UUID playerUuid, CoreHandler coreHandler, PlayerHandler playerHandler, HomeHandler homeHandler, WarpHandler warpHandler, BanHandler banHandler, CoopHandler coopHandler, BiomeHandler biomeHandler, UpgradeHandler upgradeHandler) {
        this.playerUuid = playerUuid;
        this.actor = new Actor.Player(playerUuid);
        this.coreHandler = coreHandler;
        this.playerHandler = playerHandler;
        this.homeHandler = homeHandler;
        this.warpHandler = warpHandler;
        this.banHandler = banHandler;
        this.coopHandler = coopHandler;
        this.biomeHandler = biomeHandler;
        this.upgradeHandler = upgradeHandler;
    }

    /**
     * The island every island-scoped write on this handle targets. Resolved here, right before
     * the write, rather than by the caller: fresher than a pre-resolved uuid, and it makes
     * "another island" unrepresentable. Fails with IslandDoesNotExistException when the player
     * has none.
     */
    private CompletableFuture<UUID> ownIsland() {
        return coreHandler.getIslandUuid(playerUuid);
    }

    // ---- island lifecycle -------------------------------------------------------------------

    public CompletableFuture<Void> createIsland() {
        return coreHandler.createIsland(playerUuid);
    }

    /**
     * OWNER, enforced in the delete transaction.
     */
    public CompletableFuture<Void> deleteIsland() {
        return ownIsland().thenCompose(islandUuid -> coreHandler.deleteIsland(actor, islandUuid));
    }

    /**
     * Leave the island. Owners cannot leave (CannotRemoveOwnerException); they transfer or delete.
     */
    public CompletableFuture<Void> leave() {
        return ownIsland().thenCompose(islandUuid -> playerHandler.removeMember(actor, islandUuid, playerUuid));
    }

    // ---- membership and trust ---------------------------------------------------------------

    public CompletableFuture<Void> invite(UUID inviteeUuid, int ttlSeconds) {
        return ownIsland().thenCompose(islandUuid -> {
            return playerHandler.addPendingInvite(inviteeUuid, islandUuid, playerUuid, ttlSeconds);
        });
    }

    /**
     * Redeems this player's pending invitation and joins the island it names. Empty when no
     * invitation was pending; the returned invitation carries the island and inviter for the
     * caller's messaging. The join is written with the inviter's authority, taken from the stored
     * invitation, so it fails once the inviter has left the island.
     */
    public CompletableFuture<Optional<Invitation>> acceptInvite() {
        return playerHandler.getPendingInvite(playerUuid).thenCompose(invite -> {
            if (invite.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            Invitation pending = invite.get();
            return playerHandler.removePendingInvite(playerUuid).thenCompose(v -> playerHandler.addMember(new Actor.Player(pending.getInviterUuid()), pending.getIslandUuid(), playerUuid, "member")).thenApply(v -> Optional.of(pending));
        });
    }

    public CompletableFuture<Void> rejectInvite() {
        return playerHandler.removePendingInvite(playerUuid);
    }

    /**
     * MEMBER, enforced in the delete transaction.
     */
    public CompletableFuture<Void> removeMember(UUID memberUuid) {
        return ownIsland().thenCompose(islandUuid -> playerHandler.removeMember(actor, islandUuid, memberUuid));
    }

    /**
     * OWNER, enforced in the transfer transaction.
     */
    public CompletableFuture<Void> setOwner(UUID newOwnerUuid) {
        return ownIsland().thenCompose(islandUuid -> playerHandler.setOwner(actor, islandUuid, newOwnerUuid));
    }

    /**
     * MEMBER, enforced in the ban transaction.
     */
    public CompletableFuture<Void> banPlayer(UUID targetUuid) {
        return ownIsland().thenCompose(islandUuid -> banHandler.banPlayer(actor, islandUuid, targetUuid));
    }

    /**
     * MEMBER, enforced in the unban transaction.
     */
    public CompletableFuture<Void> unbanPlayer(UUID targetUuid) {
        return ownIsland().thenCompose(islandUuid -> banHandler.unbanPlayer(actor, islandUuid, targetUuid));
    }

    /**
     * MEMBER, enforced in the coop transaction.
     */
    public CompletableFuture<Void> addCoop(UUID targetUuid) {
        return ownIsland().thenCompose(islandUuid -> coopHandler.coopPlayer(actor, islandUuid, targetUuid));
    }

    /**
     * MEMBER, enforced in the uncoop transaction.
     */
    public CompletableFuture<Void> removeCoop(UUID targetUuid) {
        return ownIsland().thenCompose(islandUuid -> coopHandler.unCoopPlayer(actor, islandUuid, targetUuid));
    }

    /**
     * MEMBER, re-checked on the island's host at the moment of the kick.
     */
    public CompletableFuture<Void> expelPlayer(UUID targetUuid) {
        return ownIsland().thenCompose(islandUuid -> playerHandler.expelPlayer(actor, islandUuid, targetUuid));
    }

    /**
     * MEMBER, enforced in the toggle transaction.
     */
    public CompletableFuture<Boolean> toggleLock() {
        return ownIsland().thenCompose(islandUuid -> coreHandler.toggleIslandLock(actor, islandUuid));
    }

    /**
     * MEMBER, enforced in the toggle transaction.
     */
    public CompletableFuture<Boolean> togglePvp() {
        return ownIsland().thenCompose(islandUuid -> coreHandler.toggleIslandPvp(actor, islandUuid));
    }

    // ---- homes and warps --------------------------------------------------------------------

    /**
     * At a location on this player's own island, on the server hosting it.
     */
    public CompletableFuture<Void> setHome(String homeName, Location location) {
        return homeHandler.setHome(actor, playerUuid, homeName, location);
    }

    public CompletableFuture<Void> deleteHome(String homeName) {
        return homeHandler.delHome(playerUuid, homeName);
    }

    public CompletableFuture<Void> home(String homeName) {
        return homeHandler.home(playerUuid, homeName, playerUuid);
    }

    /**
     * At a location on this player's own island, on the server hosting it.
     */
    public CompletableFuture<Void> setWarp(String warpName, Location location) {
        return ownIsland().thenCompose(islandUuid -> warpHandler.setWarp(actor, islandUuid, warpName, location));
    }

    public CompletableFuture<Void> deleteWarp(String warpName) {
        return ownIsland().thenCompose(islandUuid -> warpHandler.delWarp(actor, islandUuid, warpName));
    }

    /**
     * Visit the specified island's shared warp as this player.
     */
    public CompletableFuture<Void> warp(UUID islandUuid, String warpName) {
        return warpHandler.warp(islandUuid, warpName, playerUuid);
    }

    // ---- world ------------------------------------------------------------------------------

    /**
     * Re-biome the chunk holding the location, which must be on this player's own island, on the
     * server hosting it.
     */
    public CompletableFuture<Void> applyBiome(Location location, String biomeName) {
        return biomeHandler.applyPlayerChunkBiome(playerUuid, location, biomeName);
    }

    // ---- upgrades ---------------------------------------------------------------------------

    /**
     * MEMBER, enforced in the level write. Paid from this player's own balance; completes with
     * the level bought.
     */
    public CompletableFuture<Integer> buyUpgrade(String upgradeId) {
        return ownIsland().thenCompose(islandUuid -> upgradeHandler.buyUpgrade(actor, islandUuid, playerUuid, upgradeId));
    }
}
