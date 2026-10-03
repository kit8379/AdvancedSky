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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IslandAccessListener implements Listener {

    private static final long RESEND_AFTER_TICKS = 100L;

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;

    // Players already sent to the lobby off an island, so the steps they take before the move
    // happens do not send them again.
    private final Set<UUID> sentAway = ConcurrentHashMap.newKeySet();

    public IslandAccessListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache) {
        this.plugin = plugin;
        this.config = config;
        this.islandCache = islandCache;
    }

    /**
     * A player arriving through a pending teleport has already spawned where it takes them.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
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
        if (!event.hasChangedBlock() || !isIslandWorld(event.getTo().getWorld())) {
            return;
        }
        if (islandCache.isDeletingAt(event.getTo())) {
            denyAccess(player, config.getIslandLockedMessage());
            return;
        }
        if (player.isOp()) return;

        Island island = islandCache.getAt(event.getTo());
        if (island != null) {
            denyIfBarred(player, island);
        } else if (islandCache.getIslandUuidAt(event.getTo()) == null) {
            // The gap or a free slot, as on a join; an island whose entry is unavailable is left alone.
            denyAccess(player, null);
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
    private void checkAccess(Player player) {
        Location location = player.getLocation();
        if (isIslandWorld(location.getWorld()) && islandCache.isDeletingAt(location)) {
            denyAccess(player, config.getIslandLockedMessage());
            return;
        }
        if (player.isOp()) {
            return;
        }

        if (!isIslandWorld(location.getWorld())) {
            return;
        }

        Island island = islandCache.getAt(location);
        if (island == null) {
            // Nobody can stay in the gap or on a free slot, where a join without a teleport lands
            // (the world spawn is in the never-allocated slot 0), and an island whose cache entry
            // is unavailable fails closed.
            denyAccess(player, islandCache.getIslandUuidAt(location) != null ? config.getIslandLockedMessage() : null);
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

        plugin.getApi().lobby(playerUuid).whenComplete((v, error) -> {
            if (error != null) {
                // Sent again on their next step or join.
                sentAway.remove(playerUuid);
                plugin.severe("Failed to send player " + player.getName() + " to the lobby off an island they may not be on", error);
                return;
            }

            // Still here once the move should have happened, the proxy refused it: their next step sends them again.
            player.getScheduler().runDelayed(plugin, task -> sentAway.remove(playerUuid), null, RESEND_AFTER_TICKS);
        });
        if (message != null) {
            plugin.getApi().sendPlayerMessage(playerUuid, message);
        }
    }
}
