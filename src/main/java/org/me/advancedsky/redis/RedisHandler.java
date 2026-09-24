package org.me.advancedsky.redis;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.RedisClient;

public class RedisHandler {

    private static final int CONNECTION_TIMEOUT_MILLIS = 2000;
    private static final int SOCKET_TIMEOUT_MILLIS = 2000;

    private final AdvancedSky plugin;
    private final String keyPrefix;
    private final RedisClient client;

    public RedisHandler(AdvancedSky plugin, ConfigHandler config) {
        this.plugin = plugin;
        this.keyPrefix = "advancedsky:" + config.getRedisClusterId() + ":";

        HostAndPort address = new HostAndPort(config.getRedisHost(), config.getRedisPort());
        String password = config.getRedisPassword();

        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder().connectionTimeoutMillis(CONNECTION_TIMEOUT_MILLIS).socketTimeoutMillis(SOCKET_TIMEOUT_MILLIS);
        if (password != null && !password.isEmpty()) {
            clientConfig.password(password);
        }

        this.client = RedisClient.builder().hostAndPort(address).clientConfig(clientConfig.build()).build();

        client.ping();
        plugin.info("Connected to Redis at " + address);
    }

    /**
     * Thread-safe pooled client. Each command borrows and returns a connection by itself; a
     * blocking subscribe() keeps one connection (with the read timeout lifted) until unsubscribed.
     */
    public RedisClient getClient() {
        return client;
    }

    /**
     * Full key or channel name for this network: {@code advancedsky:<cluster-id>:<name>}.
     */
    public String key(String name) {
        return keyPrefix + name;
    }

    public void disconnect() {
        client.close();
        plugin.info("Disconnected from Redis");
    }
}
