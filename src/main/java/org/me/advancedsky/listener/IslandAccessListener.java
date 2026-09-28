package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.teleport.TeleportHandler;

import java.util.UUID;

public class IslandAccessListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final TeleportHandler teleportHandler;

    public IslandAccessListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache, TeleportHandler teleportHandler) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
        this.teleportHandler = teleportHandler;
    }

    /**
     * Runs before the pending teleport is taken at HIGHEST. A player arriving through one is
     * checked where it takes them, as a teleport, not where they last logged out here.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (teleportHandler.hasPendingTeleport(event.getPlayer().getUniqueId())) {
            return;
        }

        checkAccess(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        checkAccess(event.getPlayer());
    }

    private void checkAccess(Player player) {
        if (player.isOp()) {
            return;
        }

        Location location = player.getLocation();
        if (!isIslandWorld(location.getWorld())) {
            return;
        }
        UUID playerUuid = player.getUniqueId();

        Island island = islandCache.getAt(location);
        if (island == null) {
            // The gap and free slots belong to no island (a first join lands on the world spawn,
            // in the never-allocated slot 0); only an island whose cache entry is unavailable fails closed.
            if (islandCache.getIslandUuidAt(location) != null) {
                denyAccess(player, config.getIslandLockedMessage());
            }
            return;
        }

        boolean banned = island.getBans().contains(playerUuid);
        boolean locked = island.isLock() && !island.getOwner().equals(playerUuid) && !island.getMembers().contains(playerUuid) && !island.getCoops().contains(playerUuid);

        if (banned || locked) {
            denyAccess(player, banned ? config.getPlayerBannedMessage() : config.getIslandLockedMessage());
            plugin.debug("IslandAccessListener", "Player " + player.getName() + " attempted to access island " + island.getIslandUuid() + " but was denied access.");
        }
    }

    private boolean isIslandWorld(World world) {
        return world != null && world.getName().equals(config.getIslandWorldName());
    }

    private void denyAccess(Player player, net.kyori.adventure.text.Component message) {
        plugin.getApi().lobby(player.getUniqueId());
        plugin.getApi().sendPlayerMessage(player.getUniqueId(), message);
    }
}
