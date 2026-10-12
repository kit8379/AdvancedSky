package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.util.LocationUtils;

import java.util.UUID;

/**
 * Chooses the respawn destination at death because Folia does not fire a respawn event.
 */
public class IslandRespawnListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;

    public IslandRespawnListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
    }

    /**
     * Members return home; visitors use the default warp. Denied visitors and free slots use the lobby.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        Location deathLocation = player.getLocation();
        String worldName = deathLocation.getWorld().getName();
        if (!worldName.equals(config.getIslandWorldName())) {
            return;
        }

        Island island = islandCache.getAt(deathLocation);
        if (island == null) {
            if (islandCache.getIslandUuidAt(deathLocation) != null) {
                plugin.severe("Cannot resolve respawn for " + playerUuid + ": island cache entry unavailable at " + deathLocation);
                return;
            }

            sendToLobby(playerUuid);
            return;
        }

        boolean member = island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid);
        boolean banned = island.getBans().contains(playerUuid);
        boolean locked = island.isLock() && !member && !island.getCoops().contains(playerUuid);
        if (!player.isOp() && (banned || locked)) {
            sendToLobby(playerUuid);
            return;
        }

        String respawnLocation = member ? island.getDefaultHomes().get(playerUuid) : island.getDefaultWarp();
        if (respawnLocation == null) {
            if (member) {
                plugin.severe("Cannot resolve respawn for " + playerUuid + ": default home missing on island " + island.getIslandUuid());
            } else {
                sendToLobby(playerUuid);
            }
            return;
        }

        teleportAfterRespawn(player, LocationUtils.stringToLocation(worldName, respawnLocation));
    }

    /**
     * Wait for the player to respawn. Paper removes a dead player from the world after 20 ticks,
     * which retires the task but not their scheduler, so it is scheduled again; once they quit
     * the scheduler accepts no more tasks.
     */
    private void teleportAfterRespawn(Player player, Location target) {
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (player.isDead()) {
                return;
            }

            task.cancel();
            player.teleportAsync(target);
        }, () -> teleportAfterRespawn(player, target), 1L, 1L);
    }

    // Switching servers from the death screen replaces the local respawn.
    private void sendToLobby(UUID playerUuid) {
        plugin.getApi().lobby(playerUuid).exceptionally(error -> {
            plugin.severe("Failed to send dead player " + playerUuid + " to lobby", error);
            return null;
        });
    }
}
