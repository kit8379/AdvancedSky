package org.me.advancedsky;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONObject;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.command.AsyncTabCompleteListener;
import org.me.advancedsky.command.IslandPlayerCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.island.LobbyHandler;
import org.me.advancedsky.island.PlayerHandler;
import org.me.advancedsky.listener.OnlinePlayersListener;
import org.me.advancedsky.listener.TeleportRequestListener;
import org.me.advancedsky.message.PlayerMessageHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.redis.RedisHandler;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.me.advancedsky.util.ServerUtil;
import org.me.advancedsky.world.WorldHandler;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class AdvancedSky extends JavaPlugin {

    private ConfigHandler config;
    private RedisHandler redisHandler;
    private DatabaseHandler databaseHandler;
    private OnlinePlayerRegistry onlinePlayerRegistry;
    private CrossServerMessenger crossServerMessenger;
    private AdvancedSkyAPI api;
    private BukkitAsyncExecutor bukkitAsyncExecutor;
    private GlobalRegionExecutor globalRegionExecutor;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        info("Plugin enabling...");
        initialize();
        info("Plugin enabled!");
        long endTime = System.currentTimeMillis();
        info("Plugin initialization time: " + (endTime - startTime) + "ms");
    }

    private void initialize() {
        try {
            info("Start loading configuration now...");
            saveDefaultConfig();
            config = new ConfigHandler(this);
            info("Config load success!");

            info("Starting async executor");
            bukkitAsyncExecutor = new BukkitAsyncExecutor(this);
            globalRegionExecutor = new GlobalRegionExecutor(this);
            info("Async executor started");

            info("Start loading server ID now...");
            String serverID = config.getServerName();
            info("Server ID loaded success!");
            info("This Server ID: " + serverID);

            // Every server is either an island server or a lobby; anything else is a typo in the lists.
            if (config.getIslandServerNames().contains(serverID) == config.getLobbyServerNames().contains(serverID)) {
                throw new IllegalStateException("server.name '" + serverID + "' must be listed under exactly one of servers.island and servers.lobby");
            }
            info(config.isLobbyOnly() ? "Lobby server: island commands only, no islands hosted here" : "Island server: hosting islands in world '" + config.getIslandWorldName() + "'");

            info("Start connecting to Redis now...");
            redisHandler = new RedisHandler(this, config);
            info("Redis connection success!");

            IslandGrid islandGrid = new IslandGrid(config.getIslandHeight());

            info("Start connecting to Database now...");
            databaseHandler = new DatabaseHandler(this, config, islandGrid);
            info("Database connection success!");

            info("Starting Redis cache state handler");
            onlinePlayerRegistry = new OnlinePlayerRegistry(this, redisHandler);
            // No heartbeat: entries this server left behind (crash, reload) are replaced by the players online now.
            onlinePlayerRegistry.removeAllOnServer(serverID);
            Map<UUID, String> onlinePlayers = new HashMap<>();
            for (Player player : getServer().getOnlinePlayers()) {
                onlinePlayers.put(player.getUniqueId(), player.getName());
            }
            onlinePlayerRegistry.addAllOnlinePlayers(onlinePlayers, serverID);
            info("Redis cache state handler loaded");

            info("Starting world handler");
            WorldHandler worldHandler = new WorldHandler(this, config, islandGrid);
            info("World handler loaded");

            info("Starting teleport handler");
            TeleportHandler teleportHandler = new TeleportHandler();
            info("Teleport handler loaded");

            info("Starting handlers for island remote requests");
            crossServerMessenger = new CrossServerMessenger(this, redisHandler, serverID);
            IslandOperator islandOperator = new IslandOperator(this, databaseHandler, worldHandler, teleportHandler, serverID);
            IslandDistributor islandDistributor = new IslandDistributor(this, databaseHandler, islandOperator, onlinePlayerRegistry, crossServerMessenger, config.getIslandServerNames(), serverID);
            registerCrossServerHandlers(crossServerMessenger, islandOperator);
            info("All handlers for remote requests loaded");

            info("Starting player message handler");
            PlayerMessageHandler playerMessageHandler = new PlayerMessageHandler(this, crossServerMessenger, onlinePlayerRegistry, serverID);
            info("Player message handler loaded");

            info("Starting main handlers for the plugin");
            CoreHandler coreHandler = new CoreHandler(this, databaseHandler, islandDistributor);
            PlayerHandler playerHandler = new PlayerHandler(this, databaseHandler);
            HomeHandler homeHandler = new HomeHandler(this, config, databaseHandler, islandDistributor, onlinePlayerRegistry, islandGrid);
            LobbyHandler lobbyHandler = new LobbyHandler(this, config, islandDistributor);
            info("All main handlers loaded");

            info("Starting plugin messaging");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            info("Plugin messaging loaded");

            info("Starting API");
            api = new AdvancedSkyAPI(coreHandler, playerHandler, homeHandler, lobbyHandler, playerMessageHandler);
            info("API loaded");

            info("Starting listeners");
            getServer().getPluginManager().registerEvents(new OnlinePlayersListener(this, onlinePlayerRegistry, serverID), this);
            getServer().getPluginManager().registerEvents(new TeleportRequestListener(this, teleportHandler), this);
            info("All listeners loaded");

            info("Registering commands");
            PluginCommand playerCommand = createCommand("island");
            playerCommand.setAliases(Collections.singletonList("is"));
            IslandPlayerCommand islandPlayerCommand = new IslandPlayerCommand(this, api, config);
            playerCommand.setExecutor(islandPlayerCommand);
            Bukkit.getCommandMap().register("island", playerCommand);

            AsyncTabCompleteListener asyncTabCompleteListener = new AsyncTabCompleteListener(this);
            asyncTabCompleteListener.registerRoot("island", islandPlayerCommand);
            asyncTabCompleteListener.registerRoot("is", islandPlayerCommand);
            getServer().getPluginManager().registerEvents(asyncTabCompleteListener, this);
            info("All commands registered");

            crossServerMessenger.start();

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An error occurred during plugin initialization", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerCrossServerHandlers(CrossServerMessenger messenger, IslandOperator islandOperator) {
        messenger.register(IslandDistributor.ACTION_ISLAND_CREATE, payload -> emptyResponse(islandOperator.createIsland(uuid(payload, "islandUuid"), uuid(payload, "ownerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_DELETE, payload -> emptyResponse(islandOperator.deleteIsland(Actor.fromJson(payload), uuid(payload, "islandUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE, payload -> islandOperator.prepareTeleport(uuid(payload, "playerUuid"), payload.getString("teleportWorld"), payload.getString("teleportLocation")).thenApply(teleported -> new JSONObject().put("teleported", teleported)));
        messenger.register(IslandDistributor.ACTION_ISLAND_HOME_SET, payload -> emptyResponse(islandOperator.setHome(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"), payload.getString("homeName"), payload.getString("homeLocation"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_HOME_DELETE, payload -> emptyResponse(islandOperator.deleteHome(uuid(payload, "islandUuid"), uuid(payload, "playerUuid"), payload.getString("homeName"))));
        messenger.register(IslandDistributor.ACTION_PLAYER_CONNECT, payload -> emptyResponse(ServerUtil.connectToServer(this, uuid(payload, "playerUuid"), payload.getString("targetServer"))));
    }

    private CompletableFuture<JSONObject> emptyResponse(CompletableFuture<Void> future) {
        return future.thenApply(v -> new JSONObject());
    }

    private UUID uuid(JSONObject payload, String key) {
        return UUID.fromString(payload.getString(key));
    }

    private PluginCommand createCommand(String name) {
        try {
            Constructor<PluginCommand> c = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            c.setAccessible(true);
            return c.newInstance(name, this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create command: " + name, e);
        }
    }

    @Override
    public void onDisable() {
        info("Plugin disabling...");
        shutdown();
        info("Plugin disabled!");
    }

    public void shutdown() {
        if (crossServerMessenger != null) {
            crossServerMessenger.stop();
        }

        if (onlinePlayerRegistry != null) {
            onlinePlayerRegistry.removeAllOnServer(config.getServerName());
        }

        if (redisHandler != null) {
            redisHandler.disconnect();
        }

        if (databaseHandler != null) {
            databaseHandler.close();
        }
    }

    @SuppressWarnings("unused")
    public BukkitAsyncExecutor getBukkitAsyncExecutor() {
        return bukkitAsyncExecutor;
    }

    @SuppressWarnings("unused")
    public GlobalRegionExecutor getGlobalRegionExecutor() {
        return globalRegionExecutor;
    }

    @SuppressWarnings("unused")
    public AdvancedSkyAPI getApi() {
        return api;
    }

    @SuppressWarnings("unused")
    public void info(String message) {
        getLogger().log(Level.INFO, message);
    }

    @SuppressWarnings("unused")
    public void warning(String message) {
        getLogger().log(Level.WARNING, message);
    }

    @SuppressWarnings("unused")
    public void severe(String message) {
        getLogger().log(Level.SEVERE, message);
    }

    @SuppressWarnings("unused")
    public void severe(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message, throwable);
    }

    @SuppressWarnings("unused")
    public void debug(String source, String message) {
        if (config.isDebug()) {
            getLogger().log(Level.INFO, "[" + source + "] " + message);
        }
    }
}
