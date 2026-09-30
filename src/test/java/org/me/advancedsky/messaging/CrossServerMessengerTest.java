package org.me.advancedsky.messaging;

import org.bukkit.Bukkit;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.ClusterKeys;
import org.me.advancedsky.redis.RedisHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CrossServerMessengerTest {
    @Test
    void startupCleanupAndOutgoingRequestsUseTheConfiguredCluster() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        RedisHandler redis = mock(RedisHandler.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getJedis()).thenReturn(jedis);
        when(redis.getKeys()).thenReturn(new ClusterKeys("test"));
        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        CrossServerMessenger messenger = new CrossServerMessenger(plugin, redis, "server1");

        messenger.start();
        verify(jedis).del("advancedsky-test:messaging:inbox:server1");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            messenger.request("server2", "test", new JSONObject());
            verify(jedis).xadd(eq("advancedsky-test:messaging:inbox:server2"), any(XAddParams.class), anyMap());
        } finally {
            messenger.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStaleRequestIsAnsweredAsFailedInsteadOfDropped() throws Exception {
        AdvancedSky plugin = mock(AdvancedSky.class);
        RedisHandler redis = mock(RedisHandler.class);
        Jedis jedis = mock(Jedis.class);
        when(redis.getJedis()).thenReturn(jedis);
        when(redis.getKeys()).thenReturn(new ClusterKeys("test"));
        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        CrossServerMessenger messenger = new CrossServerMessenger(plugin, redis, "server1");

        JSONObject stale = new JSONObject(CrossServerMessage.request("server2", "server1", "island.upgrade.set", new JSONObject()).toJson());
        stale.put("timestamp", System.currentTimeMillis() - 20_000L);
        Method processEntry = CrossServerMessenger.class.getDeclaredMethod("processEntry", StreamEntry.class);
        processEntry.setAccessible(true);
        processEntry.invoke(messenger, new StreamEntry(new StreamEntryID(1, 0), Map.of("message", stale.toString())));

        ArgumentCaptor<Map<String, String>> sent = ArgumentCaptor.forClass(Map.class);
        verify(jedis).xadd(eq("advancedsky-test:messaging:inbox:server2"), any(XAddParams.class), sent.capture());
        JSONObject response = new JSONObject(sent.getValue().get("message"));
        assertEquals(CrossServerMessage.TYPE_RESPONSE, response.getString("type"));
        assertEquals(CrossServerMessage.STATUS_FAILED, response.getString("status"));
        assertEquals(stale.getString("messageId"), response.getString("correlationId"));
    }
}
