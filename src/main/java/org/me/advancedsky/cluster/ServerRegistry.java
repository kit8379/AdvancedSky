package org.me.advancedsky.cluster;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.redis.RedisHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Discovers ready servers using leases measured by Redis's clock, independent of node clocks. */
public final class ServerRegistry extends ClusterState {
    private static final int LEASE_SECONDS = 30;
    private static final int HEARTBEAT_SECONDS = 5;

    static final String RENEW_SCRIPT = """
            local now = tonumber(redis.call('TIME')[1])
            local expires = tonumber(redis.call('ZSCORE', KEYS[1], ARGV[1]) or '0')
            local session = redis.call('HGET', KEYS[3], ARGV[1])
            if expires > now and session and session ~= ARGV[2] then
                return 0
            end
            redis.call('ZADD', KEYS[1], now + tonumber(ARGV[4]), ARGV[1])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
            return 1
            """;

    static final String LIST_SCRIPT = """
            local now = tonumber(redis.call('TIME')[1])
            local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now)
            for _, server in ipairs(expired) do
                redis.call('ZREM', KEYS[1], server)
                redis.call('HDEL', KEYS[2], server)
                redis.call('HDEL', KEYS[3], server)
            end
            local result = {}
            local servers = redis.call('ZRANGE', KEYS[1], 0, -1)
            for _, server in ipairs(servers) do
                if redis.call('HGET', KEYS[2], server) == ARGV[1] then
                    table.insert(result, server)
                end
            end
            return result
            """;

    static final String REMOVE_SCRIPT = """
            if redis.call('HGET', KEYS[3], ARGV[1]) == ARGV[2] then
                redis.call('ZREM', KEYS[1], ARGV[1])
                redis.call('HDEL', KEYS[2], ARGV[1])
                redis.call('HDEL', KEYS[3], ARGV[1])
            end
            return 0
            """;

    private final String serverName;
    private final String role;
    private final String session = UUID.randomUUID().toString();
    private boolean running;
    private boolean ready;
    private ScheduledTask task;

    public ServerRegistry(AdvancedSky plugin, RedisHandler redisHandler, String serverName, boolean lobbyOnly) {
        super(plugin, redisHandler);
        this.serverName = serverName;
        this.role = lobbyOnly ? "lobby" : "island";
    }

    /** Reserve the name before opening inboxes or clearing player registrations. */
    public synchronized void start() {
        if (running) return;
        renew("starting");
        running = true;
        task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> heartbeat(),
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Called only after world setup, request handlers and the messenger are ready. */
    public synchronized void markReady() {
        if (!running) throw new IllegalStateException("Server registry is stopped");
        renew(role);
        ready = true;
        plugin.info("Registered online " + role + " server: " + serverName);
    }

    private synchronized void heartbeat() {
        if (!running) return;
        try {
            renew(ready ? role : "starting");
        } catch (IllegalStateException conflict) {
            running = false;
            task.cancel();
            plugin.severe(conflict.getMessage());
            plugin.getGlobalRegionExecutor().execute(() -> plugin.getServer().getPluginManager().disablePlugin(plugin));
        } catch (RuntimeException error) {
            // A connection failure is retried on the next tick. Until then the lease expires.
            plugin.warning("Could not renew server registration for " + serverName + ": " + error.getMessage());
        }
    }

    private void renew(String advertisedRole) {
        long renewed = execute(jedis -> (Long) jedis.eval(RENEW_SCRIPT, registryKeys(),
                List.of(serverName, session, advertisedRole, Integer.toString(LEASE_SECONDS))),
                "Could not register server: " + serverName);
        if (renewed != 1) {
            throw new IllegalStateException("Another online server already uses server.name '" + serverName + "'");
        }
    }

    /** Synchronization ensures an in-flight heartbeat cannot re-register after removal. */
    public synchronized void stop() {
        running = false;
        ready = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        run(jedis -> jedis.eval(REMOVE_SCRIPT, registryKeys(), List.of(serverName, session)),
                "Could not unregister server: " + serverName);
    }

    public List<String> getIslandServers() {
        return getServers("island");
    }

    public List<String> getLobbyServers() {
        return getServers("lobby");
    }

    private List<String> getServers(String wantedRole) {
        return execute(jedis -> {
            List<?> values = (List<?>) jedis.eval(LIST_SCRIPT, registryKeys(), List.of(wantedRole));
            List<String> names = new ArrayList<>(values.size());
            for (Object value : values) names.add((String) value);
            names.sort(String::compareTo);
            return List.copyOf(names);
        }, "Could not discover online " + wantedRole + " servers");
    }

    private List<String> registryKeys() {
        return List.of(keys.serverLeases(), keys.serverRoles(), keys.serverSessions());
    }
}
