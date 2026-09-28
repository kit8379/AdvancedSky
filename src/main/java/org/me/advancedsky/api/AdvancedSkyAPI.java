package org.me.advancedsky.api;

import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.PlayerHandler;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The plugin's public surface. One rule, no exceptions:
 * <ul>
 *   <li><b>Writes go through {@link #player(UUID)}</b>. The player handle acts as that player
 *       only, on that player's own island only - anything else is unrepresentable, not merely
 *       checked.</li>
 *   <li><b>Reads live on this class</b> - island state is not secret, and a read changes
 *       nothing.</li>
 * </ul>
 * Internal machinery does not pass through this API: it calls its handler directly. What you see
 * here is exactly the surface commands and other plugins are meant to use.
 */
public class AdvancedSkyAPI {

    private final CoreHandler coreHandler;
    private final PlayerHandler playerHandler;

    public AdvancedSkyAPI(CoreHandler coreHandler, PlayerHandler playerHandler) {
        this.coreHandler = coreHandler;
        this.playerHandler = playerHandler;
    }

    // ================================================================================================================
    // Write entry points
    // ================================================================================================================

    /** Act as this player: own identity, own island, player rules apply. */
    @SuppressWarnings("unused")
    public PlayerActions player(UUID playerUuid) {
        return new PlayerActions(playerUuid, coreHandler);
    }

    // ================================================================================================================
    // Reads
    // ================================================================================================================

    @SuppressWarnings("unused")
    public CompletableFuture<UUID> getIslandUuid(UUID playerUuid) {
        return coreHandler.getIslandUuid(playerUuid);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<UUID> getIslandOwner(UUID islandUuid) {
        return playerHandler.getIslandOwner(islandUuid);
    }
}
