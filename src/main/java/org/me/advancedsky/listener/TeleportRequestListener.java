package org.me.advancedsky.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.teleport.TeleportHandler;

public class TeleportRequestListener implements Listener {

    private final AdvancedSky plugin;
    private final TeleportHandler teleportHandler;

    public TeleportRequestListener(AdvancedSky plugin, TeleportHandler teleportHandler) {
        this.plugin = plugin;
        this.teleportHandler = teleportHandler;
    }

    /**
     * A player arriving through a pending teleport spawns at its location instead of being
     * teleported from the join event, which Folia refuses: the player is not in the world yet.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        PlayerProfile profile = event.getConnection().getProfile();
        Location pendingLocation = teleportHandler.removePendingTeleport(profile.getId());
        if (pendingLocation == null) {
            return;
        }

        if (!pendingLocation.isWorldLoaded()) {
            plugin.warning("Dropping pending teleport for " + profile.getName() + ": target world is no longer loaded.");
            return;
        }

        event.setSpawnLocation(pendingLocation);
        plugin.debug("TeleportRequestListener", "Spawning " + profile.getName() + " at pending location.");
    }
}
