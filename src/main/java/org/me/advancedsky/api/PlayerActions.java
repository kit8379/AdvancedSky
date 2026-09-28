package org.me.advancedsky.api;

import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.model.Actor;

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
 * Island roles (OWNER for delete) are still enforced where they must be: inside the write
 * transaction, under the island row lock.
 */
public final class PlayerActions {

    private final UUID playerUuid;
    private final Actor actor;
    private final CoreHandler coreHandler;

    PlayerActions(UUID playerUuid, CoreHandler coreHandler) {
        this.playerUuid = playerUuid;
        this.actor = new Actor.Player(playerUuid);
        this.coreHandler = coreHandler;
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
}
