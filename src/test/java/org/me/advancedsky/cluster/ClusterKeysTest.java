package org.me.advancedsky.cluster;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClusterKeysTest {
    private final UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void networksWithTheSameServerAndPlayerIdsHaveSeparateRedisKeys() {
        List<String> first = allKeys(new ClusterKeys("first"));
        List<String> second = allKeys(new ClusterKeys("second"));

        assertTrue(Collections.disjoint(first, second));
        assertTrue(first.stream().allMatch(key -> key.startsWith("advancedsky-first:")));
        assertTrue(second.stream().allMatch(key -> key.startsWith("advancedsky-second:")));
        assertEquals(first, allKeys(new ClusterKeys("first")));
    }

    private List<String> allKeys(ClusterKeys keys) {
        return List.of(keys.onlinePlayers(), keys.onlinePlayerServers(), keys.invitation(uuid), keys.messagingInbox("server1"));
    }
}
