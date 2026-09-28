package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.network.IslandDistributor;

import java.util.List;
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
        return CompletableFuture.supplyAsync(() -> {
            List<String> lobbyServers = config.getLobbyServerNames();
            String lobbyWorld = config.getLobbyWorldName();
            String lobbyLocation = config.getLobbyX() + "," + config.getLobbyY() + "," + config.getLobbyZ() + "," + config.getLobbyYaw() + "," + config.getLobbyPitch();

            return islandDistributor.teleportLobby(playerUuid, lobbyServers, lobbyWorld, lobbyLocation);
        }, plugin.getBukkitAsyncExecutor()).thenCompose(f -> f);
    }
}
