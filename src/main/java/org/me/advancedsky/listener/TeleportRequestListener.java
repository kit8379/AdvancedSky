package org.me.advancedsky.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    @SuppressWarnings("UnstableApiUsage")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        PlayerProfile profile = event.getConnection().getProfile();
        TeleportHandler.PendingTeleport pending = teleportHandler.takeSpawnTeleport(profile.getId());
        if (pending == null) {
            return;
        }
        Location pendingLocation = pending.location();

        if (!pendingLocation.isWorldLoaded()) {
            teleportHandler.clearSpawnArrival(profile.getId());
            plugin.warning("Dropping pending teleport for " + profile.getName() + ": target world is no longer loaded.");
            return;
        }

        event.setSpawnLocation(pendingLocation);
        plugin.debug("TeleportRequestListener", "Spawning " + profile.getName() + " at pending location.");
    }

    /**
     * A teleport stored after the player's spawn location was chosen, while they were still
     * logging in, runs once they are in: a tick after the join, as Folia teleports nobody during it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        TeleportHandler.JoinTeleport arrival = teleportHandler.claimJoinTeleport(player.getUniqueId());
        if (arrival == null) {
            return;
        }
        TeleportHandler.PendingTeleport pending = arrival.pendingTeleport();
        if (player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) {
                teleportHandler.completeArrival(player.getUniqueId(), pending);
                return;
            }
            if (!pending.location().isWorldLoaded() || !teleportHandler.isArrivalCurrent(player.getUniqueId(), pending)) {
                teleportHandler.checkFailedArrival(player, pending);
                return;
            }
            if (arrival.spawned()) {
                teleportHandler.confirmArrival(player, pending);
                return;
            }
            try {
                player.leaveVehicle();
                player.teleportAsync(pending.location()).whenComplete((moved, error) -> {
                    if (error != null || !Boolean.TRUE.equals(moved)) {
                        teleportHandler.checkFailedArrival(player, pending);
                        return;
                    }
                    teleportHandler.confirmTeleport(player, pending);
                });
                plugin.debug("TeleportRequestListener", "Teleporting " + player.getName() + " to a pending location stored while logging in.");
            } catch (Throwable error) {
                teleportHandler.checkFailedArrival(player, pending);
                plugin.warning("Failed to teleport " + player.getName() + " to their pending location: " + error.getMessage());
            }
        }, () -> teleportHandler.completeArrival(player.getUniqueId(), pending), 1L) == null) {
            teleportHandler.completeArrival(player.getUniqueId(), pending);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teleportHandler.clearSpawnArrival(event.getPlayer().getUniqueId());
    }
}
