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
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.snapshot.IslandSnapshot;

import java.util.UUID;

public class IslandAccessListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandSnapshot islandSnapshot;

    public IslandAccessListener(AdvancedSky plugin, ConfigHandler config, IslandSnapshot islandSnapshot) {
        this.plugin = plugin;
        this.config = config;
        this.islandSnapshot = islandSnapshot;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
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

        Island island = islandSnapshot.getAt(location);
        if (island == null) {
            // The gap and free slots belong to no island (a first join lands on the world spawn,
            // in the never-allocated slot 0); only an island whose snapshot is unavailable fails closed.
            if (islandSnapshot.getIslandUuidAt(location) != null) {
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
