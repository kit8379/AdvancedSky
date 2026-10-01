package org.me.advancedsky.teleport;

import org.bukkit.Location;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Teleports waiting for a player's next join to this server. One older than a minute is dropped:
 * the trip that stored it has ended, whether or not the player ever arrived.
 */
public class TeleportHandler {

    private static final long PENDING_TTL_MILLIS = 60_000L;

    private final ConcurrentHashMap<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public TeleportHandler() {
        this(System::currentTimeMillis);
    }

    TeleportHandler(LongSupplier clock) {
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
}
