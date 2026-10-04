package org.me.advancedsky.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.island.CoopHandler;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class IslandCoopListener implements Listener {

    private final AdvancedSky plugin;
    private final CoopHandler coopHandler;
    private final OnlinePlayerRegistry onlinePlayerRegistry;

    public IslandCoopListener(AdvancedSky plugin, CoopHandler coopHandler, OnlinePlayerRegistry onlinePlayerRegistry) {
        this.plugin = plugin;
        this.coopHandler = coopHandler;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> removeCoopIfOffline(playerUuid, playerName), 5L, TimeUnit.SECONDS);
    }

    private void removeCoopIfOffline(UUID playerUuid, String playerName) {
        try {
            if (onlinePlayerRegistry.isOnline(playerUuid)) {
                plugin.debug("IslandCoopListener", "Skipped coop cleanup for player " + playerName + " because they are still online.");
                return;
            }

            coopHandler.deleteAllCoopOfPlayer(playerUuid).thenRun(() -> plugin.debug("IslandCoopListener", "Removed all coop entries for player " + playerName + " on quit.")).exceptionally(error -> {
                plugin.severe("Error removing coop entries for player " + playerName + " on quit.", error);
                return null;
            });
        } catch (RuntimeException error) {
            plugin.severe("Error checking online state for player " + playerName + " on quit.", error);
        }
    }
}
