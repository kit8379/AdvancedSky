package org.me.advancedsky.island;

import net.kyori.adventure.text.Component;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.network.IslandDistributor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LobbyHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandDistributor islandDistributor;

    public LobbyHandler(AdvancedSky plugin, ConfigHandler config, IslandDistributor islandDistributor) {
        this.plugin = plugin;
        this.config = config;
        this.islandDistributor = islandDistributor;
    }

    public CompletableFuture<Void> lobby(UUID playerUuid) {
        return lobby(playerUuid, null);
    }

    /** Sends the optional success message on the destination after the teleport succeeds. */
    public CompletableFuture<Void> lobby(UUID playerUuid, Component successMessage) {
        return CompletableFuture.supplyAsync(() -> {
            String lobbyWorld = config.getLobbyWorldName();
            String lobbyLocation = config.getLobbyX() + "," + config.getLobbyY() + "," + config.getLobbyZ() + "," + config.getLobbyYaw() + "," + config.getLobbyPitch();

            return successMessage == null
                    ? islandDistributor.teleportLobby(playerUuid, lobbyWorld, lobbyLocation)
                    : islandDistributor.teleportLobby(playerUuid, lobbyWorld, lobbyLocation, successMessage);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(f -> f);
    }
}
