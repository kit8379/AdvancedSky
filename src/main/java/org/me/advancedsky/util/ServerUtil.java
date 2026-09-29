package org.me.advancedsky.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerUtil {

    /**
     * Connects a player to a specified server using BungeeCord plugin messaging.
     *
     * @param plugin     the AdvancedSky plugin instance
     * @param playerUuid the UUID of the player to connect
     * @param serverName the name of the server to connect to
     * @return a CompletableFuture that completes when the connection message is sent
     */
    public static CompletableFuture<Void> connectToServer(AdvancedSky plugin, UUID playerUuid, String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) {
                throw new IllegalStateException("Player not found: " + playerUuid);
            }
            return player;
        }, plugin.getGlobalRegionExecutor()).thenCompose(player -> {
            // Sent from the thread that owns the player, as Folia requires for player state.
            CompletableFuture<Void> sent = new CompletableFuture<>();
            boolean scheduled = player.getScheduler().run(plugin, task -> {
                try (ByteArrayOutputStream byteArray = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(byteArray)) {
                    out.writeUTF("Connect");
                    out.writeUTF(serverName);
                    player.sendPluginMessage(plugin, "BungeeCord", byteArray.toByteArray());
                    sent.complete(null);
                } catch (IOException | RuntimeException e) {
                    plugin.severe("Failed to send plugin message to player " + player.getName(), e);
                    sent.completeExceptionally(e);
                }
            }, () -> sent.completeExceptionally(new IllegalStateException("Player left before the connect: " + playerUuid))) != null;

            // A player whose scheduler is already retired gets neither the task nor the callback.
            if (!scheduled) {
                sent.completeExceptionally(new IllegalStateException("Player left before the connect: " + playerUuid));
            }
            return sent;
        });
    }
}
