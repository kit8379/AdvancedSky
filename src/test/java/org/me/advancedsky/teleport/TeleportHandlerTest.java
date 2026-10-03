package org.me.advancedsky.teleport;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
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

    @Test
    void spawnKeepsTheMessageUntilJoinAndClaimsItOnce() {
        Component message = Component.text("Teleported to home");
        teleports.addPendingTeleport(player, target, message);
        var pending = teleports.takeSpawnTeleport(player);

        assertSame(message, pending.successMessage());
        assertNull(teleports.removePendingTeleport(player));
        var join = teleports.claimJoinTeleport(player);
        assertTrue(join.spawned());
        assertSame(pending, join.pendingTeleport());
        assertNull(teleports.claimJoinTeleport(player));
        assertTrue(teleports.completeArrival(player, pending));
        assertFalse(teleports.completeArrival(player, pending));
    }

    @Test
    void waitingForJoinDoesNotExtendTheOriginalExpiry() {
        teleports.addPendingTeleport(player, target, Component.text("home"));
        now.addAndGet(59_999L);
        var pending = teleports.takeSpawnTeleport(player);
        now.addAndGet(2L);

        assertNull(teleports.claimJoinTeleport(player));
        assertFalse(teleports.completeArrival(player, pending));
    }

    @Test
    void aNewRequestDuringLoginSupersedesTheSpawnConfirmation() {
        teleports.addPendingTeleport(player, target, Component.text("old"));
        var previous = teleports.takeSpawnTeleport(player);
        Location newerTarget = new Location(null, 4, 5, 6);
        Component newerMessage = Component.text("new");
        teleports.addPendingTeleport(player, newerTarget, newerMessage);

        var join = teleports.claimJoinTeleport(player);
        assertFalse(join.spawned());
        assertSame(newerTarget, join.pendingTeleport().location());
        assertSame(newerMessage, join.pendingTeleport().successMessage());
        assertFalse(teleports.completeArrival(player, previous));
    }

    @Test
    void cancellingArrivalDropsBothPendingAndClaimedWork() {
        teleports.addPendingTeleport(player, target, Component.text("home"));
        var claimed = teleports.claimJoinTeleport(player).pendingTeleport();
        teleports.cancelArrival(player);
        assertFalse(teleports.isArrivalCurrent(player, claimed));
        assertNull(teleports.claimJoinTeleport(player));

        teleports.addPendingTeleport(player, target);
        teleports.cancelArrival(player);
        assertNull(teleports.removePendingTeleport(player));
    }

    @Test
    void anotherSpawnStartsANewSessionWithoutReusingTheOldConfirmation() {
        teleports.addPendingTeleport(player, target, Component.text("home"));
        teleports.takeSpawnTeleport(player);

        assertNull(teleports.takeSpawnTeleport(player));
        assertNull(teleports.claimJoinTeleport(player));
    }

    @Test
    void expiryDuringTheJoinTaskCannotConfirmTheArrival() {
        teleports.addPendingTeleport(player, target, Component.text("home"));
        teleports.takeSpawnTeleport(player);
        var join = teleports.claimJoinTeleport(player);
        now.addAndGet(60_001L);

        assertFalse(teleports.isArrivalCurrent(player, join.pendingTeleport()));
        assertFalse(teleports.completeArrival(player, join.pendingTeleport()));
    }
}
