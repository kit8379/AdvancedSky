package org.me.advancedsky.api;

import net.kyori.adventure.text.Component;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.island.LobbyHandler;
import org.me.advancedsky.island.PlayerHandler;
import org.me.advancedsky.message.PlayerMessageHandler;

import java.util.Set;
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
    private final HomeHandler homeHandler;
    private final LobbyHandler lobbyHandler;
    private final PlayerMessageHandler playerMessageHandler;

    public AdvancedSkyAPI(CoreHandler coreHandler, PlayerHandler playerHandler, HomeHandler homeHandler, LobbyHandler lobbyHandler, PlayerMessageHandler playerMessageHandler) {
        this.coreHandler = coreHandler;
        this.playerHandler = playerHandler;
        this.homeHandler = homeHandler;
        this.lobbyHandler = lobbyHandler;
        this.playerMessageHandler = playerMessageHandler;
    }

    // ================================================================================================================
    // Write entry points
    // ================================================================================================================

    /** Act as this player: own identity, own island, player rules apply. */
    @SuppressWarnings("unused")
    public PlayerActions player(UUID playerUuid) {
        return new PlayerActions(playerUuid, coreHandler, homeHandler);
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

    @SuppressWarnings("unused")
    public CompletableFuture<Set<String>> getHomeNames(UUID playerUuid) {
        return homeHandler.getHomeNames(playerUuid);
    }

    // ================================================================================================================
    // Live-player actions - no island state, so neither handle applies
    // ================================================================================================================

    /** Moves a player to a lobby server. Touches no island state; also the fail-closed bounce. */
    @SuppressWarnings("unused")
    public CompletableFuture<Void> lobby(UUID playerUuid) {
        return lobbyHandler.lobby(playerUuid);
    }

    @SuppressWarnings("unused")
    public void sendPlayerMessage(UUID playerUuid, Component message) {
        playerMessageHandler.sendPlayerMessage(playerUuid, message);
    }
}
