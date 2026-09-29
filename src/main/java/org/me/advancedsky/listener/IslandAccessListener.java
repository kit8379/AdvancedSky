package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.teleport.TeleportHandler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IslandAccessListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final TeleportHandler teleportHandler;

    // Players already sent to the lobby off an island, so the steps they take before the move
    // happens do not send them again.
    private final Set<UUID> sentAway = ConcurrentHashMap.newKeySet();

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

    /**
     * Paper refuses a teleport or portal trip onto an island the player may not be on, but Folia
     * fires no event for either, so there the first step they take on it is checked. An island
     * whose cache entry is unavailable is left alone here: its members walk on it too.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!event.hasChangedBlock() || player.isOp() || !isIslandWorld(event.getTo().getWorld())) {
            return;
        }

        Island island = islandCache.getAt(event.getTo());
        if (island != null) {
            denyIfBarred(player, island);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sentAway.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Sends the player to the lobby if they stand on an island they may not be on. Runs on the
     * player's thread.
     */
    void checkAccess(Player player) {
        if (player.isOp()) {
            return;
        }

        Location location = player.getLocation();
        if (!isIslandWorld(location.getWorld())) {
            return;
        }

        Island island = islandCache.getAt(location);
        if (island == null) {
            // The gap and free slots belong to no island (a first join lands on the world spawn,
            // in the never-allocated slot 0); only an island whose cache entry is unavailable fails closed.
            if (islandCache.getIslandUuidAt(location) != null) {
                denyAccess(player, config.getIslandLockedMessage());
            }
            return;
        }

        denyIfBarred(player, island);
    }

    private void denyIfBarred(Player player, Island island) {
        UUID playerUuid = player.getUniqueId();
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
        UUID playerUuid = player.getUniqueId();
        if (!sentAway.add(playerUuid)) {
            return;
        }

        plugin.getApi().lobby(playerUuid).exceptionally(error -> {
            // Sent again on their next step or join.
            sentAway.remove(playerUuid);
            plugin.severe("Failed to send player " + player.getName() + " to the lobby off an island they may not be on", error);
            return null;
        });
        plugin.getApi().sendPlayerMessage(playerUuid, message);
    }
}
