package org.me.advancedsky.island;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IslandActivityHandlerTest {
    private final UUID island = UUID.randomUUID();
    private final IslandActivityHandler activity = new IslandActivityHandler(mock(AdvancedSky.class));

    @Test
    void playerEnterIsTrueOnlyForThePlayerWhoFindsTheIslandEmpty() {
        assertTrue(activity.playerEnter(island));
        assertFalse(activity.playerEnter(island));
        assertTrue(activity.playerEnter(UUID.randomUUID()));

        activity.playerLeave(island, 100);
        assertFalse(activity.playerEnter(island));

        activity.playerLeave(island, 200);
        activity.playerLeave(island, 300);
        assertTrue(activity.playerEnter(island));
    }

    @Test
    void wasActiveSinceMeansSomeoneIsOnItOrLeftAtOrAfterThatTime() {
        assertFalse(activity.wasActiveSince(island, 0));

        // Leaving an island nobody entered is not activity.
        activity.playerLeave(island, 100);
        assertFalse(activity.wasActiveSince(island, 0));

        activity.playerEnter(island);
        activity.playerEnter(island);
        assertTrue(activity.wasActiveSince(island, Long.MAX_VALUE));
        activity.playerLeave(island, 100);
        assertTrue(activity.wasActiveSince(island, Long.MAX_VALUE), "one player is still on it");

        activity.playerLeave(island, 200);
        assertTrue(activity.wasActiveSince(island, 150));
        assertTrue(activity.wasActiveSince(island, 200));
        assertFalse(activity.wasActiveSince(island, 201));
    }
}
