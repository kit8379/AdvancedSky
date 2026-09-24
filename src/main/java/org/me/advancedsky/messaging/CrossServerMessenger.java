package org.me.advancedsky.messaging;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.redis.RedisHandler;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fire-and-forget messaging between servers over Redis pub/sub. Every server listens on its own
 * channel; a message names an action and carries a JSON payload.
 * <p>
 * MySQL is the source of truth. A message only asks the receiving server to apply an already
 * committed change to its world, so losing one leaves the data consistent.
 * <p>
 * Handlers run on the subscriber thread: they must schedule work, never block on it.
 */
public final class CrossServerMessenger {

    private static final String CHANNEL_PREFIX = "server:";
    private static final String FIELD_ACTION = "action";
    private static final long RECONNECT_DELAY_MILLIS = 1000L;

    private final AdvancedSky plugin;
    private final RedisHandler redisHandler;
    private final String channel;
    private final Map<String, Consumer<JsonObject>> handlers = new ConcurrentHashMap<>();
    private final JedisPubSub subscription = new JedisPubSub() {
        @Override
        public void onMessage(String channel, String message) {
            dispatch(message);
        }
    };

    private volatile boolean running;
    private Thread subscriberThread;

    public CrossServerMessenger(AdvancedSky plugin, RedisHandler redisHandler, String serverName) {
        this.plugin = plugin;
        this.redisHandler = redisHandler;
        this.channel = channelOf(serverName);
    }

    public void register(String action, Consumer<JsonObject> handler) {
        handlers.put(action, handler);
    }

    public void send(String targetServer, String action, JsonObject payload) {
        payload.addProperty(FIELD_ACTION, action);
        redisHandler.getClient().publish(channelOf(targetServer), payload.toString());
        plugin.debug("CrossServerMessenger", "Sent to " + targetServer + ": " + payload);
    }

    public void start() {
        running = true;
        subscriberThread = new Thread(this::subscribeLoop, "AdvancedSky-Redis-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void stop() {
        running = false;
        if (subscription.isSubscribed()) {
            subscription.unsubscribe();
        }
        if (subscriberThread != null) {
            try {
                subscriberThread.join(RECONNECT_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void subscribeLoop() {
        while (running) {
            // subscribe() holds one pooled connection and blocks until unsubscribed or the connection drops.
            try {
                plugin.debug("CrossServerMessenger", "Subscribed to " + channel);
                redisHandler.getClient().subscribe(subscription, channel);
            } catch (Exception e) {
                if (running) {
                    plugin.severe("Lost Redis subscription on " + channel + ", retrying in " + RECONNECT_DELAY_MILLIS + "ms", e);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void dispatch(String raw) {
        try {
            JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
            String action = message.get(FIELD_ACTION).getAsString();

            Consumer<JsonObject> handler = handlers.get(action);
            if (handler == null) {
                plugin.warning("No handler registered for cross-server action '" + action + "'");
                return;
            }

            plugin.debug("CrossServerMessenger", "Received: " + raw);
            handler.accept(message);
        } catch (Exception e) {
            plugin.severe("Failed to handle cross-server message: " + raw, e);
        }
    }

    private String channelOf(String server) {
        return redisHandler.key(CHANNEL_PREFIX + server);
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
