package org.me.advancedsky.redis;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.cluster.ClusterKeys;
import redis.clients.jedis.*;

import java.time.Duration;

public class RedisHandler {

    private final AdvancedSky plugin;
    private final ConnectionPool pool;
    private final ClusterKeys keys;

    public RedisHandler(AdvancedSky plugin, ConfigHandler config) {
        this.plugin = plugin;

        String host = config.getRedisHost();
        int port = config.getRedisPort();
        String password = config.getRedisPassword();
        this.keys = new ClusterKeys(config.getClusterId());

        // The default of 8 connections, one of them held by the stream consumer's blocking read,
        // queues every other Redis call behind a burst of joins, quits and tab completions. A
        // bounded wait turns an exhausted pool into an error instead of a thread parked forever.
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(32);
        poolConfig.setMaxWait(Duration.ofSeconds(5));

        // A blocking command (the stream consumer's XREAD blocks for a second) waits on its own
        // timeout, unlimited by default; bounded, a connection that died silently fails it
        // instead of holding the consumer forever.
        DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000).blockingSocketTimeoutMillis(5000);

        if (password != null && !password.isEmpty()) {
            clientConfigBuilder.password(password);
            plugin.info("Connected to Redis at " + host + ":" + port + " with password.");
        } else {
            plugin.info("Connected to Redis at " + host + ":" + port + " without password.");
        }

        JedisClientConfig clientConfig = clientConfigBuilder.build();

        // Correct constructor
        this.pool = new ConnectionPool(new HostAndPort(host, port), clientConfig, poolConfig);
    }

    public ClusterKeys getKeys() {
        return keys;
    }

    public Jedis getJedis() {
        Connection connection = pool.getResource();
        return new Jedis(connection);
    }

    /**
     * Closes the idle connections: when one died silently the others most likely did too, and
     * each would otherwise fail only after its own timeout.
     */
    public void clearIdleConnections() {
        pool.clear();
    }

    public void disconnect() {
        if (pool != null) {
            pool.close();
            plugin.info("Disconnected from Redis.");
        }
    }
}
