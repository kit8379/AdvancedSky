package org.me.advancedsky.cluster;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.redis.RedisHandler;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the actual Lua protocol against an isolated cluster on an explicitly supplied Redis. */
@EnabledIfSystemProperty(named = "advancedsky.redis-test", matches = "true")
class ServerRegistryIntegrationTest {
    private final ClusterKeys keys = new ClusterKeys("registry-test-" + UUID.randomUUID());
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final RedisHandler redis = mock(RedisHandler.class);
    private final List<ServerRegistry> registries = new ArrayList<>();
    private final List<Consumer<ScheduledTask>> heartbeats = new ArrayList<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        when(redis.getKeys()).thenReturn(keys);
        when(redis.getJedis()).thenAnswer(call -> connection());
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(scheduler.runAtFixedRate(eq(plugin), any(), eq(5L), eq(5L), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> {
                    heartbeats.add(call.getArgument(1));
                    return mock(ScheduledTask.class);
                });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getAsyncScheduler).thenReturn(scheduler);
    }

    @AfterEach
    void cleanUp() {
        try {
            for (ServerRegistry registry : registries) registry.stop();
            try (Jedis jedis = connection()) {
                jedis.del(keys.serverLeases(), keys.serverRoles(), keys.serverSessions());
            }
        } finally {
            bukkit.close();
        }
    }

    @Test
    void onlyReadyServersAreDiscoverableAndRolesAndNamesAreSeparated() {
        ServerRegistry sky2 = node("sky2", false);
        ServerRegistry sky1 = node("sky1", false);
        ServerRegistry lobby = node("lobby", true);
        sky2.start();
        sky1.start();
        lobby.start();
        heartbeats.getFirst().accept(mock(ScheduledTask.class));
        assertEquals(List.of(), sky1.getIslandServers());
        assertEquals(List.of(), sky1.getLobbyServers());
        sky2.markReady();
        sky1.markReady();
        lobby.markReady();
        assertEquals(List.of("sky1", "sky2"), lobby.getIslandServers());
        assertEquals(List.of("lobby"), sky1.getLobbyServers());
    }

    @Test
    void stoppingImmediatelyRemovesTheHostAndLateHeartbeatsCannotRestoreIt() {
        ServerRegistry sky = node("sky1", false);
        sky.start();
        sky.markReady();
        sky.stop();
        heartbeats.getFirst().accept(mock(ScheduledTask.class));
        assertEquals(List.of(), sky.getIslandServers());
    }

    @Test
    void expiredCrashRegistrationsAreRemovedWithoutUsingTheNodeClock() {
        ServerRegistry sky = node("sky1", false);
        sky.start();
        sky.markReady();
        expire("sky1");
        assertEquals(List.of(), sky.getIslandServers());
        try (Jedis jedis = connection()) {
            assertNull(jedis.hget(keys.serverRoles(), "sky1"));
            assertNull(jedis.hget(keys.serverSessions(), "sky1"));
            assertNull(jedis.zscore(keys.serverLeases(), "sky1"));
        }
    }

    @Test
    void duplicateLiveNamesCannotTakeOverAndFailedStartupCannotRemoveTheOriginal() {
        ServerRegistry first = node("sky1", false);
        first.start();
        first.markReady();
        ServerRegistry duplicate = node("sky1", true);
        assertThrows(IllegalStateException.class, duplicate::start);
        duplicate.stop();
        assertEquals(List.of("sky1"), first.getIslandServers());
        assertEquals(List.of(), first.getLobbyServers());
    }

    @Test
    void anOldSessionCannotEraseAReplacementAfterItsLeaseExpired() {
        ServerRegistry old = node("sky1", false);
        old.start();
        old.markReady();
        expire("sky1");
        ServerRegistry replacement = node("sky1", false);
        replacement.start();
        replacement.markReady();
        old.stop();
        assertEquals(List.of("sky1"), replacement.getIslandServers());
    }

    @Test
    void aReadyServerCanRenewAfterItsExpiredRegistrationWasReaped() {
        ServerRegistry sky = node("sky1", false);
        sky.start();
        sky.markReady();
        expire("sky1");
        assertEquals(List.of(), sky.getIslandServers());
        heartbeats.getFirst().accept(mock(ScheduledTask.class));
        assertEquals(List.of("sky1"), sky.getIslandServers());
    }

    private ServerRegistry node(String name, boolean lobbyOnly) {
        ServerRegistry registry = new ServerRegistry(plugin, redis, name, lobbyOnly);
        registries.add(registry);
        return registry;
    }

    private void expire(String name) {
        try (Jedis jedis = connection()) {
            jedis.zadd(keys.serverLeases(), 0, name);
        }
    }

    private Jedis connection() {
        return new Jedis(System.getProperty("advancedsky.redis-test-host", "127.0.0.1"),
                Integer.getInteger("advancedsky.redis-test-port", 6379));
    }
}
