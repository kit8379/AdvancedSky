package org.me.advancedsky.listener;

import net.kyori.adventure.text.Component;
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

import java.util.Objects;
import java.util.UUID;

/**
 * Checks players already at their arrival location. Movement and destination protection are
 * handled by {@link IslandProtectionListener}; pending login teleports by {@link TeleportRequestListener}.
 */
public final class IslandAccessListener implements Listener {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final TeleportHandler teleportHandler;

    public IslandAccessListener(AdvancedSky plugin, ConfigHandler config, IslandCache islandCache, TeleportHandler teleportHandler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.islandCache = Objects.requireNonNull(islandCache, "islandCache");
        this.teleportHandler = Objects.requireNonNull(teleportHandler, "teleportHandler");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!teleportHandler.hasPendingTeleport(player.getUniqueId())) {
            checkArrival(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        checkArrival(event.getPlayer());
    }

    /**
     * Runs on the player's entity thread. Returns false for every denied arrival, even when
     * the asynchronous lobby request fails, so the original trip cannot announce success.
     */
    public boolean checkArrival(Player player) {
        Objects.requireNonNull(player, "player");
        if (config.isLobbyOnly()) {
            return true;
        }

        Location location = Objects.requireNonNull(player.getLocation(), "Player arrival location is missing");
        World world = Objects.requireNonNull(location.getWorld(), "Player arrival world is not loaded");
        if (!world.getName().equals(config.getIslandWorldName())) {
            return true;
        }

        // Physical deletion bars everyone, including operators.
        if (islandCache.isDeletingAt(location)) {
            return denyAccess(player, location, AccessResult.DELETING);
        }
        if (player.isOp()) {
            return true;
        }

        Island island = islandCache.getAt(location);
        if (island == null) {
            AccessResult reason = islandCache.getIslandUuidAt(location) == null
                    ? AccessResult.NO_ISLAND : AccessResult.UNAVAILABLE;
            return denyAccess(player, location, reason);
        }

        UUID playerUuid = player.getUniqueId();
        if (island.getBans().contains(playerUuid)) {
            return denyAccess(player, location, AccessResult.BANNED);
        }
        boolean belongsToIsland = island.getOwner().equals(playerUuid)
                || island.getMembers().contains(playerUuid)
                || island.getCoops().contains(playerUuid);
        if (island.isLock() && !belongsToIsland) {
            return denyAccess(player, location, AccessResult.LOCKED);
        }
        return true;
    }

    private boolean denyAccess(Player player, Location location, AccessResult reason) {
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        Component message = switch (reason) {
            case NO_ISLAND -> null;
            case BANNED -> Objects.requireNonNull(config.getPlayerBannedMessage(), "Player banned message is missing");
            case DELETING, UNAVAILABLE, LOCKED ->
                    Objects.requireNonNull(config.getIslandLockedMessage(), "Island locked message is missing");
        };

        if (reason == AccessResult.UNAVAILABLE) {
            plugin.severe("Cannot check island access for player " + playerUuid + ": island cache entry unavailable at " + location);
        }
        plugin.debug("IslandAccessListener", "Denied island access for " + playerName + " (" + playerUuid + ") at " + location + ": " + reason);

        // Invalidate the original arrival before starting a new lobby teleport.
        teleportHandler.cancelArrival(playerUuid);
        String failureMessage = "Failed to send player " + playerName + " (" + playerUuid + ") to the lobby after denied island access";
        try {
            Objects.requireNonNull(plugin.getApi().lobby(playerUuid), "Lobby request returned no future").whenComplete((unused, error) -> {
                // Completion can run asynchronously; player state was captured above.
                if (error != null) {
                    plugin.severe(failureMessage, error);
                }
            });
        } catch (RuntimeException error) {
            plugin.severe(failureMessage, error);
        }
        if (message != null) {
            plugin.getApi().sendPlayerMessage(playerUuid, message);
        }
        return false;
    }

    private enum AccessResult {
        NO_ISLAND, DELETING, UNAVAILABLE, BANNED, LOCKED
    }
}
