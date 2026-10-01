package org.me.advancedsky.teleport;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

class TeleportHandlerTest {
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final TeleportHandler teleports = new TeleportHandler(now::get);
    private final UUID player = UUID.randomUUID();
    private final Location target = new Location(null, 1, 2, 3);

    @Test
    void aPendingTeleportIsTakenOnceWithinItsMinute() {
        teleports.addPendingTeleport(player, target);
        now.addAndGet(60_000L);

        assertSame(target, teleports.removePendingTeleport(player));
        assertNull(teleports.removePendingTeleport(player));
    }

    @Test
    void aPendingTeleportOlderThanAMinuteIsDropped() {
        teleports.addPendingTeleport(player, target);
        now.addAndGet(60_001L);

        assertNull(teleports.removePendingTeleport(player));
    }
}
