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

    // A player's registry writes run in the order their joins and quits happened here, so the
    // quit of a quick relog cannot land after the new join and erase it.
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

    /**
     * Until it is written, the rest of the network takes the player for offline, so a write that
     * fails is tried again for as long as they are here, from their own thread so it still comes
     * before their quit.
     */
    private void register(Player player, long retryTicks) {
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        write(playerUuid, () -> CompletableFuture.runAsync(() -> {
            onlinePlayerRegistry.addOnlinePlayer(playerUuid, playerName, serverID);
            plugin.debug("OnlinePlayersListener", "Player " + playerName + " joined on server " + serverID);
        }, plugin.getBukkitAsyncExecutor())).exceptionally(error -> {
            player.getScheduler().runDelayed(plugin, task -> register(player, Math.min(retryTicks * 2, MAX_RETRY_TICKS)), null, retryTicks);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        write(playerUuid, () -> unregister(playerUuid, playerName, FIRST_RETRY_TICKS));
    }

    /**
     * Until it is removed, the rest of the network takes the player for online here, so a removal
     * that fails is tried again until it succeeds. It stays the player's current write meanwhile,
     * so a join of theirs here comes after it.
     */
    private CompletableFuture<Void> unregister(UUID playerUuid, String playerName, long retryTicks) {
        // Retried after as many ticks as a join's, at 50 ms each.
        Executor retry = CompletableFuture.delayedExecutor(retryTicks * 50L, TimeUnit.MILLISECONDS, plugin.getBukkitAsyncExecutor());
        return CompletableFuture.runAsync(() -> {
            onlinePlayerRegistry.removeOnlinePlayer(playerUuid, serverID);
            plugin.debug("OnlinePlayersListener", "Player " + playerName + " quit from server " + serverID);
        }, plugin.getBukkitAsyncExecutor()).exceptionallyComposeAsync(error -> unregister(playerUuid, playerName, Math.min(retryTicks * 2, MAX_RETRY_TICKS)), retry);
    }

    /**
     * Runs the write off the event thread once the player's previous write has finished, failed
     * or not; a failure is already logged by the registry.
     */
    private CompletableFuture<Void> write(UUID playerUuid, Supplier<CompletableFuture<Void>> write) {
        CompletableFuture<Void> next = writes.compute(playerUuid, (uuid, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.exceptionally(error -> null)).thenCompose(v -> write.get()));
        next.whenComplete((v, error) -> writes.remove(playerUuid, next));
        return next;
    }
}
