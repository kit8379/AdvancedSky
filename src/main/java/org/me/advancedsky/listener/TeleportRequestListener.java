package org.me.advancedsky.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.teleport.TeleportHandler;

import java.util.UUID;

public class TeleportRequestListener implements Listener {

    private final AdvancedSky plugin;
    private final TeleportHandler teleportHandler;

    public TeleportRequestListener(AdvancedSky plugin, TeleportHandler teleportHandler) {
        this.plugin = plugin;
        this.teleportHandler = teleportHandler;
    }

    /**
     * Use the pending destination as the spawn location; Folia cannot teleport during join.
     */
    @SuppressWarnings("UnstableApiUsage")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        PlayerProfile profile = event.getConnection().getProfile();
        UUID playerUuid = profile.getId();
        TeleportHandler.PendingTeleport pending = teleportHandler.takeSpawnTeleport(playerUuid);
        if (pending == null) {
            return;
        }
        Location pendingLocation = pending.location();

        if (!pendingLocation.isWorldLoaded()) {
            teleportHandler.clearSpawnArrival(playerUuid);
            plugin.warning("Dropping pending teleport for " + profile.getName() + ": target world is no longer loaded.");
            return;
        }

        event.setSpawnLocation(pendingLocation);
        plugin.debug("TeleportRequestListener", "Spawning " + profile.getName() + " at pending location.");
    }

    /**
     * Confirm the spawn arrival or run a late request one tick after join, on the player's thread.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        TeleportHandler.JoinTeleport arrival = teleportHandler.claimJoinTeleport(playerUuid);
        if (arrival == null) {
            return;
        }
        TeleportHandler.PendingTeleport pending = arrival.pendingTeleport();
        ScheduledTask task = player.getScheduler().runDelayed(plugin,
                scheduled -> finishJoinArrival(player, arrival),
                () -> teleportHandler.completeArrival(playerUuid, pending), 1L);
        if (task == null) {
            teleportHandler.completeArrival(playerUuid, pending);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teleportHandler.clearSpawnArrival(event.getPlayer().getUniqueId());
    }

    private void finishJoinArrival(Player player, TeleportHandler.JoinTeleport arrival) {
        TeleportHandler.PendingTeleport pending = arrival.pendingTeleport();
        UUID playerUuid = player.getUniqueId();
        if (!player.isOnline()) {
            teleportHandler.completeArrival(playerUuid, pending);
            return;
        }
        if (!pending.location().isWorldLoaded() || !teleportHandler.isArrivalCurrent(playerUuid, pending)) {
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
                // Teleporting keeps the fall distance, which kills a player teleported out of a fall on landing.
                player.setFallDistance(0);
                teleportHandler.confirmTeleport(player, pending);
            });
            plugin.debug("TeleportRequestListener", "Teleporting " + player.getName() + " to a pending location stored while logging in.");
        } catch (Throwable error) {
            teleportHandler.checkFailedArrival(player, pending);
            plugin.warning("Failed to teleport " + player.getName() + " to their pending location: " + error.getMessage());
        }
    }
}
