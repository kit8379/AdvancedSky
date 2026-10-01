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
 * Where a player who died in the island world comes back, decided at death: Folia fires no
 * respawn event, Paper and Folia both fire the death event.
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
     * A member goes to their default home and a visitor to the island's default warp once
     * respawned. A player barred from the island, a visitor without a warp to go to, and anyone
     * dying in the gap or on a free slot, where the world spawn lies too, go to the lobby.
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

            lobby(player);
            return;
        }

        boolean member = island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid);
        if (!player.isOp() && isBarred(island, playerUuid)) {
            lobby(player);
            return;
        }

        String location = member ? island.getDefaultHomes().get(playerUuid) : island.getDefaultWarp();
        if (location == null) {
            if (member) {
                plugin.severe("Cannot resolve respawn for " + playerUuid + ": default home missing on island " + island.getIslandUuid());
            } else {
                lobby(player);
            }
            return;
        }

        teleportAfterRespawn(player, LocationUtils.stringToLocation(worldName, location));
    }

    /**
     * Banned from the island, or locked out of it as neither a member nor a co-op.
     */
    private static boolean isBarred(Island island, UUID playerUuid) {
        boolean member = island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid);
        return island.getBans().contains(playerUuid) || (island.isLock() && !member && !island.getCoops().contains(playerUuid));
    }

    /**
     * Their own scheduler keeps running through the death and the respawn on both platforms, so
     * it looks every tick until they are alive again; a player who quits first retires it.
     */
    private void teleportAfterRespawn(Player player, Location target) {
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (player.isDead()) {
                return;
            }

            task.cancel();
            player.teleportAsync(target);
        }, null, 1L, 1L);
    }

    /**
     * Sent from the death screen: the move to another server replaces the respawn here.
     */
    private void lobby(Player player) {
        UUID playerUuid = player.getUniqueId();
        plugin.getApi().lobby(playerUuid).exceptionally(error -> {
            plugin.severe("Failed to send dead player " + playerUuid + " to lobby", error);
            return null;
        });
    }
}
