package org.me.advancedsky.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.island.IslandWorldHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.redis.RedisHandler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Gets a player onto an island. Local islands are a plain teleport; remote islands are a proxy
 * switch with the target island remembered in Redis until the player arrives on the other server.
 */
public class TeleportHandler {

    private static final String PENDING_TELEPORT_KEY_PREFIX = "pending_teleport:";
    private static final long PENDING_TELEPORT_TTL_SECONDS = 60L;
    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final AdvancedSky plugin;
    private final RedisHandler redisHandler;
    private final IslandWorldHandler worldHandler;

    public TeleportHandler(AdvancedSky plugin, RedisHandler redisHandler, IslandWorldHandler worldHandler) {
        this.plugin = plugin;
        this.redisHandler = redisHandler;
        this.worldHandler = worldHandler;
    }

    /**
     * Teleports the player to an island hosted on this server. Safe to call from any thread.
     */
    public CompletableFuture<Void> teleportToLocalIsland(Player player, Island island) {
        Location spawn = worldHandler.spawn(island.slot());
        CompletableFuture<Void> future = new CompletableFuture<>();

        // teleportAsync must run on the thread that owns the player.
        player.getScheduler().run(plugin, task -> player.teleportAsync(spawn).whenComplete((success, ex) -> {
            if (ex != null) {
                future.completeExceptionally(ex);
            } else {
                future.complete(null);
            }
        }), () -> future.completeExceptionally(new IllegalStateException("Player " + player.getUniqueId() + " left before the teleport")));

        return future;
    }

    /**
     * Sends the player to the island's server. That server calls {@link #consumePendingTeleport}
     * when the player joins and finishes the teleport.
     */
    public void sendToIslandServer(Player player, Island island) {
        redisHandler.getClient().setex(pendingTeleportKey(player.getUniqueId()), PENDING_TELEPORT_TTL_SECONDS, island.islandUuid().toString());

        byte[] message = connectMessage(island.server());
        player.getScheduler().run(plugin, task -> player.sendPluginMessage(plugin, BUNGEE_CHANNEL, message), null);
    }

    /**
     * Island the player was sent to this server for, if any. Removes the hand-off.
     */
    public Optional<UUID> consumePendingTeleport(UUID playerUuid) {
        String islandUuid = redisHandler.getClient().getDel(pendingTeleportKey(playerUuid));
        return islandUuid == null ? Optional.empty() : Optional.of(UUID.fromString(islandUuid));
    }

    private String pendingTeleportKey(UUID playerUuid) {
        return redisHandler.key(PENDING_TELEPORT_KEY_PREFIX + playerUuid);
    }

    private static byte[] connectMessage(String server) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Connect message", e);
        }
    }
}
