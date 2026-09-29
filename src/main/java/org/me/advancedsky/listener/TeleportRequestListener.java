package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.teleport.TeleportHandler;

import java.util.UUID;

public class TeleportRequestListener implements Listener {

    private final AdvancedSky plugin;
    private final TeleportHandler teleportHandler;
    private final IslandAccessListener islandAccessListener;

    public TeleportRequestListener(AdvancedSky plugin, TeleportHandler teleportHandler, IslandAccessListener islandAccessListener) {
        this.plugin = plugin;
        this.teleportHandler = teleportHandler;
        this.islandAccessListener = islandAccessListener;
    }

    /**
     * The access check on join leaves a player with a pending teleport to the rule where it takes
     * them. A teleport that does not happen leaves them where they logged out, so that spot is
     * checked instead.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Location pendingLocation = teleportHandler.removePendingTeleport(playerUuid);
        if (pendingLocation == null) {
            return;
        }

        if (!pendingLocation.isWorldLoaded()) {
            plugin.warning("Dropping pending teleport for " + player.getName() + ": target world is no longer loaded.");
            islandAccessListener.checkAccess(player);
            return;
        }

        player.teleportAsync(pendingLocation).whenComplete((moved, error) -> {
            if (error == null && moved) {
                plugin.debug("TeleportRequestListener", "Teleported " + player.getName() + " to pending location on join.");
                return;
            }

            player.getScheduler().run(plugin, task -> islandAccessListener.checkAccess(player), null);
        });
    }
}