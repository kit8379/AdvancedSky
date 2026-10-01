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

        write(playerUuid, () -> {
            onlinePlayerRegistry.addOnlinePlayer(playerUuid, playerName, serverID);
            plugin.debug("OnlinePlayersListener", "Player " + playerName + " joined on server " + serverID);
        }).exceptionally(error -> {
            player.getScheduler().runDelayed(plugin, task -> register(player, Math.min(retryTicks * 2, MAX_RETRY_TICKS)), null, retryTicks);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        write(playerUuid, () -> {
            onlinePlayerRegistry.removeOnlinePlayer(playerUuid, serverID);
            plugin.debug("OnlinePlayersListener", "Player " + playerName + " quit from server " + serverID);
        });
    }

    /**
     * Runs the write off the event thread once the player's previous write has finished, failed
     * or not; a failure is already logged by the registry.
     */
    private CompletableFuture<Void> write(UUID playerUuid, Runnable write) {
        CompletableFuture<Void> next = writes.compute(playerUuid, (uuid, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.exceptionally(error -> null)).thenRunAsync(write, plugin.getBukkitAsyncExecutor()));
        next.whenComplete((v, error) -> writes.remove(playerUuid, next));
        return next;
    }
}
