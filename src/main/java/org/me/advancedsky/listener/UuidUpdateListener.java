package org.me.advancedsky.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.uuid.UuidHandler;

import java.util.UUID;

public class UuidUpdateListener implements Listener {

    private final AdvancedSky plugin;
    private final UuidHandler uuidHandler;

    public UuidUpdateListener(AdvancedSky plugin, UuidHandler uuidHandler) {
        this.plugin = plugin;
        this.uuidHandler = uuidHandler;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        uuidHandler.updatePlayerUuid(playerUuid, playerName)
                .thenRun(() -> plugin.debug("UuidUpdateListener", "Updated UUID for player: " + playerName + " (" + playerUuid + ")"))
                .exceptionally(error -> {
                    plugin.severe("Error updating UUID for player " + playerName + " (" + playerUuid + ")", error);
                    return null;
                });
    }
}
