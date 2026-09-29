package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.util.LocationUtils;

import java.util.UUID;

public class IslandRespawnListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;

    public IslandRespawnListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }

        // Paper fires this before moving the player to the respawn world, so this is where they died.
        Location deathLocation = player.getLocation();
        String worldName = deathLocation.getWorld().getName();
        if (!worldName.equals(config.getIslandWorldName())) {
            return;
        }

        Island island = islandCache.getAt(deathLocation);
        if (island == null) {
            // Dying in the gap or on a free slot is dying on no island: vanilla respawn.
            if (islandCache.getIslandUuidAt(deathLocation) != null) {
                plugin.severe("Cannot resolve respawn for " + playerUuid + ": island cache entry unavailable at " + deathLocation);
                return;
            }

            // That respawn may be a bed or anchor on an island that has barred the player since.
            Location respawn = event.getRespawnLocation();
            if (!player.isOp() && respawn.getWorld() != null && respawn.getWorld().getName().equals(worldName) && islandCache.getIslandUuidAt(respawn) != null) {
                Island respawnIsland = islandCache.getAt(respawn);
                if (respawnIsland == null || isBarred(respawnIsland, playerUuid)) {
                    lobbyAfterRespawn(player);
                }
            }
            return;
        }
        UUID islandUuid = island.getIslandUuid();

        boolean member = island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid);
        if (!player.isOp() && isBarred(island, playerUuid)) {
            lobbyAfterRespawn(player);
            return;
        }

        String location = member ? island.getDefaultHomes().get(playerUuid) : island.getDefaultWarp();
        if (location == null) {
            if (member) {
                plugin.severe("Cannot resolve respawn for " + playerUuid + ": default home missing on island " + islandUuid);
            } else {
                lobbyAfterRespawn(player);
            }
            return;
        }

        // Only override this respawn; beds and anchors never replace the island defaults.
        event.setRespawnLocation(LocationUtils.stringToLocation(worldName, location));
    }

    /**
     * Banned from the island, or locked out of it as neither a member nor a co-op.
     */
    private static boolean isBarred(Island island, UUID playerUuid) {
        boolean member = island.getOwner().equals(playerUuid) || island.getMembers().contains(playerUuid);
        return island.getBans().contains(playerUuid) || (island.isLock() && !member && !island.getCoops().contains(playerUuid));
    }

    /**
     * Sends the player to the lobby once the respawn is over, a tick later on their own scheduler:
     * Folia fires no PlayerPostRespawnEvent.
     */
    private void lobbyAfterRespawn(Player player) {
        UUID playerUuid = player.getUniqueId();
        player.getScheduler().runDelayed(plugin, task -> plugin.getApi().lobby(playerUuid).exceptionally(error -> {
            plugin.severe("Failed to send respawned player " + playerUuid + " to lobby", error);
            return null;
        }), null, 1L);
    }
}
