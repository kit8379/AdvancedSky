package org.me.advancedsky.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class OnlinePlayersListener implements Listener {

    private static final long FIRST_RETRY_TICKS = 40L;
    private static final long MAX_RETRY_TICKS = 600L;

    private final AdvancedSky plugin;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final String serverID;

    // Keep join and quit writes in order so a quick relog cannot erase the new session.
    private final Map<UUID, CompletableFuture<Void>> writes = new ConcurrentHashMap<>();

    public OnlinePlayersListener(AdvancedSky plugin, OnlinePlayerRegistry onlinePlayerRegistry, String serverID) {
        this.plugin = plugin;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.serverID = serverID;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerJoin(PlayerJoinEvent event) {
        register(event.getPlayer(), FIRST_RETRY_TICKS);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        write(playerUuid, () -> unregister(playerUuid, playerName, FIRST_RETRY_TICKS));
    }

    // Retry on the player's thread so registration stays ordered before their quit.
    private void register(Player player, long retryTicks) {
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        write(playerUuid, () -> CompletableFuture.runAsync(() -> {
            onlinePlayerRegistry.addOnlinePlayer(playerUuid, playerName, serverID);
            plugin.debug("OnlinePlayersListener", "Player " + playerName + " joined on server " + serverID);
        }, plugin.getBukkitAsyncExecutor())).exceptionally(error -> {
            long nextRetryTicks = Math.min(retryTicks * 2, MAX_RETRY_TICKS);
            player.getScheduler().runDelayed(plugin,
                    task -> register(player, nextRetryTicks), null, retryTicks);
            return null;
        });
    }

    // Finish removal, including retries, before allowing this player's next join write.
    private CompletableFuture<Void> unregister(UUID playerUuid, String playerName, long retryTicks) {
        Executor retry = CompletableFuture.delayedExecutor(retryTicks * 50L, TimeUnit.MILLISECONDS, plugin.getBukkitAsyncExecutor());
        long nextRetryTicks = Math.min(retryTicks * 2, MAX_RETRY_TICKS);
        return CompletableFuture.runAsync(() -> {
            onlinePlayerRegistry.removeOnlinePlayer(playerUuid, serverID);
            plugin.debug("OnlinePlayersListener", "Player " + playerName + " quit from server " + serverID);
        }, plugin.getBukkitAsyncExecutor())
                .exceptionallyComposeAsync(error -> unregister(playerUuid, playerName, nextRetryTicks), retry);
    }

    // The registry logs failures; a failed write must not prevent subsequent writes.
    private CompletableFuture<Void> write(UUID playerUuid, Supplier<CompletableFuture<Void>> operation) {
        CompletableFuture<Void> next = writes.compute(playerUuid, (uuid, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return ready.thenCompose(unused -> operation.get());
        });
        next.whenComplete((unused, error) -> writes.remove(playerUuid, next));
        return next;
    }
}
