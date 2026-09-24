package org.me.advancedsky.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.me.advancedsky.island.IslandHandler;

public class PlayerJoinListener implements Listener {

    private final IslandHandler islandHandler;

    public PlayerJoinListener(IslandHandler islandHandler) {
        this.islandHandler = islandHandler;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        islandHandler.completePendingTeleport(event.getPlayer());
    }
}
