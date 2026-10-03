package org.me.advancedsky.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.TeleportCancelledException;
import org.me.advancedsky.util.LocationUtils;
import org.me.advancedsky.world.WorldHandler;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Teleports players already here, or keeps a pending destination for their next join to this server.
 * A pending teleport older than a minute is dropped:
 * the trip that stored it has ended, whether or not the player ever arrived.
 */
public class TeleportHandler {

    private static final long PENDING_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final AdvancedSky plugin;
    private final WorldHandler worldHandler;
    private final IslandCache islandCache;

    public TeleportHandler() {
        this(System::currentTimeMillis);
    }

    TeleportHandler(LongSupplier clock) {
        this(null, null, null, clock);
    }

    public TeleportHandler(AdvancedSky plugin, WorldHandler worldHandler, IslandCache islandCache) {
        this(plugin, worldHandler, islandCache, System::currentTimeMillis);
    }

    private TeleportHandler(AdvancedSky plugin, WorldHandler worldHandler, IslandCache islandCache, LongSupplier clock) {
        this.plugin = plugin;
        this.worldHandler = worldHandler;
        this.islandCache = islandCache;
        this.clock = clock;
    }

    public void addPendingTeleport(UUID playerUuid, Location location) {
        long now = clock.getAsLong();
        pendingTeleports.values().removeIf(pending -> isExpired(pending, now));
        pendingTeleports.put(playerUuid, new PendingTeleport(location, now));
    }

    public Location removePendingTeleport(UUID playerUuid) {
        PendingTeleport pending = pendingTeleports.remove(playerUuid);
        return pending == null || isExpired(pending, clock.getAsLong()) ? null : pending.location();
    }

    private static boolean isExpired(PendingTeleport pending, long now) {
        return now - pending.storedAt() > PENDING_TTL_MILLIS;
    }

    private record PendingTeleport(Location location, long storedAt) {
    }

    /** True when teleported here; false when stored for the player's next join. */
    public CompletableFuture<Boolean> prepareTeleport(UUID playerUuid, String teleportWorld, String teleportLocation) {
        return CompletableFuture.supplyAsync(() -> {
            Location location = LocationUtils.stringToLocation(teleportWorld, teleportLocation);
            if (worldHandler != null && worldHandler.isIslandLocation(location) && islandCache.isDeletingAt(location)) {
                throw new IslandDoesNotExistException();
            }
            Player player = Bukkit.getPlayer(playerUuid);

            // teleportAsync runs on the thread that owns the player; a player who left meanwhile
            // gets the teleport stored for their return, like one who was never here.
            CompletableFuture<Boolean> teleported = new CompletableFuture<>();
            if (player != null && player.getScheduler().run(plugin, task -> {
                try {
                    // Folia refuses to teleport a passenger; Paper dismounts one itself.
                    player.leaveVehicle();
                    player.teleportAsync(location).whenComplete((moved, error) -> {
                        if (error != null) {
                            teleported.completeExceptionally(error);
                        } else if (!moved) {
                            teleported.completeExceptionally(new TeleportCancelledException());
                        } else {
                            plugin.debug("TeleportHandler", "Teleported player " + playerUuid + " to location: " + teleportLocation + " in world: " + teleportWorld);
                            teleported.complete(true);
                        }
                    });
                } catch (Throwable error) {
                    teleported.completeExceptionally(error);
                }
            }, () -> teleported.complete(store(playerUuid, location))) != null) {
                return teleported;
            }

            return CompletableFuture.completedFuture(store(playerUuid, location));
        }, plugin.getGlobalRegionExecutor()).thenCompose(teleported -> teleported);
    }

    private boolean store(UUID playerUuid, Location location) {
        addPendingTeleport(playerUuid, location);
        plugin.debug("TeleportHandler", "Stored pending teleport for player " + playerUuid + " to " + location);
        return false;
    }
}
