package org.me.advancedsky.teleport;

import net.kyori.adventure.text.Component;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Teleports local players and keeps a destination and its confirmation for arriving players. */
public class TeleportHandler {

    private static final long PENDING_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Arrival> arrivals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RequestToken> currentRequests = new ConcurrentHashMap<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final LongSupplier clock;
    private final AdvancedSky plugin;
    private final WorldHandler worldHandler;
    private final IslandCache islandCache;
    private volatile Predicate<Player> arrivalCheck = player -> true;

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

    /** Runs on the player's entity thread after arrival, including teleports without a message. */
    public void setArrivalCheck(Predicate<Player> arrivalCheck) {
        this.arrivalCheck = java.util.Objects.requireNonNull(arrivalCheck);
    }

    public boolean checkArrival(Player player) {
        return arrivalCheck.test(player);
    }

    public void addPendingTeleport(UUID playerUuid, Location location) {
        addPendingTeleport(playerUuid, location, null);
    }

    public synchronized void addPendingTeleport(UUID playerUuid, Location location, Component successMessage) {
        RequestToken request = beginRequest(playerUuid);
        pendingTeleports.put(playerUuid, new PendingTeleport(location, successMessage, request.startedAt(), request.id()));
    }

    public Location removePendingTeleport(UUID playerUuid) {
        PendingTeleport pending = takePendingTeleport(playerUuid);
        if (pending != null) {
            finishRequest(playerUuid, pending.requestId());
        }
        return pending == null ? null : pending.location();
    }

    public synchronized PendingTeleport takePendingTeleport(UUID playerUuid) {
        PendingTeleport pending = pendingTeleports.remove(playerUuid);
        if (pending == null) {
            return null;
        }
        if (isExpired(pending) || !isRequestCurrent(playerUuid, pending.requestId())) {
            finishRequest(playerUuid, pending.requestId());
            return null;
        }
        return pending;
    }

    public synchronized boolean hasPendingTeleport(UUID playerUuid) {
        PendingTeleport pending = pendingTeleports.get(playerUuid);
        if (pending == null) {
            return false;
        }
        if (isExpired(pending) || !isRequestCurrent(playerUuid, pending.requestId())) {
            pendingTeleports.remove(playerUuid, pending);
            finishRequest(playerUuid, pending.requestId());
            return false;
        }
        return true;
    }

    /** Atomically consumes the spawn destination and keeps its confirmation for this login. */
    public synchronized PendingTeleport takeSpawnTeleport(UUID playerUuid) {
        clearSpawnArrival(playerUuid);
        PendingTeleport pending = takePendingTeleport(playerUuid);
        if (pending != null) {
            arrivals.put(playerUuid, new Arrival(pending, true, false));
        }
        return pending;
    }

    /** Claims one join task; a request received during login supersedes the spawn destination. */
    public synchronized JoinTeleport claimJoinTeleport(UUID playerUuid) {
        PendingTeleport pending = takePendingTeleport(playerUuid);
        if (pending != null) {
            arrivals.put(playerUuid, new Arrival(pending, false, false));
        }
        Arrival arrival = arrivals.get(playerUuid);
        if (arrival == null || arrival.claimed() || !isArrivalCurrent(playerUuid, arrival.pendingTeleport())) {
            return null;
        }
        arrivals.put(playerUuid, new Arrival(arrival.pendingTeleport(), arrival.spawned(), true));
        return new JoinTeleport(arrival.pendingTeleport(), arrival.spawned());
    }

    public synchronized boolean isArrivalCurrent(UUID playerUuid, PendingTeleport pending) {
        Arrival arrival = arrivals.get(playerUuid);
        if (arrival == null || arrival.pendingTeleport() != pending || !isRequestCurrent(playerUuid, pending.requestId())) {
            return false;
        }
        if (isExpired(pending)) {
            arrivals.remove(playerUuid, arrival);
            finishRequest(playerUuid, pending.requestId());
            return false;
        }
        return true;
    }

    /** Removes only this request; stale callbacks cannot consume or confirm a newer request. */
    public synchronized boolean completeArrival(UUID playerUuid, PendingTeleport pending) {
        if (!isArrivalCurrent(playerUuid, pending)) {
            return false;
        }
        arrivals.remove(playerUuid);
        finishRequest(playerUuid, pending.requestId());
        return true;
    }

    public synchronized void clearSpawnArrival(UUID playerUuid) {
        Arrival removed = arrivals.remove(playerUuid);
        if (removed != null) {
            finishRequest(playerUuid, removed.pendingTeleport().requestId());
        }
    }

    /** Cancels pending and scheduled arrival work before access checks redirect the player away. */
    public synchronized void cancelArrival(UUID playerUuid) {
        pendingTeleports.remove(playerUuid);
        arrivals.remove(playerUuid);
        currentRequests.remove(playerUuid);
    }

    public boolean isExpired(PendingTeleport pending) {
        return clock.getAsLong() - pending.storedAt() > PENDING_TTL_MILLIS;
    }

    public record PendingTeleport(Location location, Component successMessage, long storedAt, long requestId) {
    }

    public record JoinTeleport(PendingTeleport pendingTeleport, boolean spawned) {
    }

    private record Arrival(PendingTeleport pendingTeleport, boolean spawned, boolean claimed) {
    }

    private record RequestToken(long id, long startedAt) {
    }

    private synchronized RequestToken beginRequest(UUID playerUuid) {
        long now = clock.getAsLong();
        pendingTeleports.values().removeIf(pending -> now - pending.storedAt() > PENDING_TTL_MILLIS);
        arrivals.values().removeIf(arrival -> now - arrival.pendingTeleport().storedAt() > PENDING_TTL_MILLIS);
        currentRequests.values().removeIf(request -> now - request.startedAt() > PENDING_TTL_MILLIS);
        cancelArrival(playerUuid);
        RequestToken request = new RequestToken(requestSequence.incrementAndGet(), now);
        currentRequests.put(playerUuid, request);
        return request;
    }

    private synchronized boolean isRequestCurrent(UUID playerUuid, long requestId) {
        RequestToken current = currentRequests.get(playerUuid);
        return current != null && current.id() == requestId;
    }

    private synchronized void finishRequest(UUID playerUuid, long requestId) {
        RequestToken current = currentRequests.get(playerUuid);
        if (current != null && current.id() == requestId) {
            currentRequests.remove(playerUuid, current);
        }
    }

    private synchronized boolean registerArrival(UUID playerUuid, PendingTeleport pending) {
        if (!isRequestCurrent(playerUuid, pending.requestId())) {
            return false;
        }
        arrivals.put(playerUuid, new Arrival(pending, false, true));
        return true;
    }

    /** True when teleported here; false when stored for the player's next join. */
    public CompletableFuture<Boolean> prepareTeleport(UUID playerUuid, String teleportWorld, String teleportLocation) {
        return prepareTeleport(playerUuid, teleportWorld, teleportLocation, null);
    }

    public CompletableFuture<Boolean> prepareTeleport(UUID playerUuid, String teleportWorld, String teleportLocation, Component successMessage) {
        RequestToken request = beginRequest(playerUuid);
        return CompletableFuture.supplyAsync(() -> {
            Location location = LocationUtils.stringToLocation(teleportWorld, teleportLocation);
            if (worldHandler != null && worldHandler.isIslandLocation(location) && islandCache.isDeletingAt(location)) {
                throw new IslandDoesNotExistException();
            }
            PendingTeleport pending = new PendingTeleport(location, successMessage, request.startedAt(), request.id());
            if (!registerArrival(playerUuid, pending)) {
                throw new TeleportCancelledException();
            }
            Player player = Bukkit.getPlayer(playerUuid);
            CompletableFuture<Boolean> teleported = new CompletableFuture<>();
            if (player != null && player.getScheduler().run(plugin, task -> {
                try {
                    if (!isArrivalCurrent(playerUuid, pending)) {
                        teleported.completeExceptionally(new TeleportCancelledException());
                        return;
                    }
                    // Folia refuses to teleport a passenger; Paper dismounts one itself.
                    player.leaveVehicle();
                    player.teleportAsync(location).whenComplete((moved, error) -> {
                        if (error != null) {
                            teleported.completeExceptionally(error);
                        } else if (!Boolean.TRUE.equals(moved)) {
                            teleported.completeExceptionally(new TeleportCancelledException());
                        } else {
                            // Teleporting keeps the fall distance, which kills a player teleported out of a fall on landing.
                            player.setFallDistance(0);
                            plugin.debug("TeleportHandler", "Teleported player " + playerUuid + " to location: " + teleportLocation + " in world: " + teleportWorld);
                            confirmTeleport(player, pending, false);
                            teleported.complete(true);
                        }
                    });
                } catch (Throwable error) {
                    teleported.completeExceptionally(error);
                }
            }, () -> retireTeleport(playerUuid, pending, teleported)) != null) {
                return teleported;
            }
            return CompletableFuture.completedFuture(store(playerUuid, pending));
        }, plugin.getGlobalRegionExecutor()).thenCompose(teleported -> teleported).whenComplete((moved, error) -> {
            if (error != null) {
                synchronized (this) {
                    Arrival arrival = arrivals.get(playerUuid);
                    if (arrival != null && arrival.pendingTeleport().requestId() == request.id()) {
                        arrivals.remove(playerUuid, arrival);
                    }
                    finishRequest(playerUuid, request.id());
                }
            }
        });
    }

    private synchronized boolean store(UUID playerUuid, PendingTeleport pending) {
        if (!isRequestCurrent(playerUuid, pending.requestId()) || isExpired(pending)) {
            throw new TeleportCancelledException();
        }
        arrivals.remove(playerUuid);
        pendingTeleports.put(playerUuid, pending);
        plugin.debug("TeleportHandler", "Stored pending teleport for player " + playerUuid + " to " + pending.location());
        return false;
    }

    private void retireTeleport(UUID playerUuid, PendingTeleport pending, CompletableFuture<Boolean> teleported) {
        try {
            teleported.complete(store(playerUuid, pending));
        } catch (Throwable error) {
            teleported.completeExceptionally(error);
        }
    }

    /** A teleport future can finish off-thread; only the entity task checks access and messages. */
    public void confirmTeleport(Player player, PendingTeleport pending) {
        confirmTeleport(player, pending, true);
    }

    private void confirmTeleport(Player player, PendingTeleport pending, boolean checkAccess) {
        if (player.getScheduler().run(plugin, task -> confirmArrival(player, pending, false, checkAccess),
                () -> completeArrival(player.getUniqueId(), pending)) == null) {
            completeArrival(player.getUniqueId(), pending);
        }
    }

    /** A failed late-login teleport still needs access checked at the original spawn. */
    public void checkFailedArrival(Player player, PendingTeleport pending) {
        if (player.getScheduler().run(plugin, task -> {
            RequestToken current = currentRequests.get(player.getUniqueId());
            if (player.isOnline() && (current == null || current.id() == pending.requestId())) {
                completeArrival(player.getUniqueId(), pending);
                checkArrival(player);
            }
        }, () -> completeArrival(player.getUniqueId(), pending)) == null) {
            completeArrival(player.getUniqueId(), pending);
        }
    }

    /** Called from the player's entity scheduler after the chosen spawn has actually joined. */
    public void confirmArrival(Player player, PendingTeleport pending) {
        confirmArrival(player, pending, true, true);
    }

    private void confirmArrival(Player player, PendingTeleport pending, boolean verifySpawnLocation, boolean checkAccess) {
        UUID playerUuid = player.getUniqueId();
        try {
            if (isArrivalCurrent(playerUuid, pending) && player.isOnline()
                    && (verifySpawnLocation ? isAtDestination(player, pending.location())
                    : isInDestinationWorld(player, pending.location()))
                    && (!checkAccess || checkArrival(player))
                    && completeArrival(playerUuid, pending) && pending.successMessage() != null) {
                player.sendMessage(pending.successMessage());
            }
        } finally {
            completeArrival(playerUuid, pending);
        }
    }

    public boolean isAtDestination(Player player, Location location) {
        Location actual = player.getLocation();
        return actual != null && actual.getWorld() == location.getWorld()
                && actual.distanceSquared(location) <= 1.0D;
    }

    private boolean isInDestinationWorld(Player player, Location location) {
        Location actual = player.getLocation();
        return actual != null && actual.getWorld() == location.getWorld();
    }
}
