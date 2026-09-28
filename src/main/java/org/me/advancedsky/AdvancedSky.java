package org.me.advancedsky;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONObject;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.InvitationStore;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.command.AsyncTabCompleteListener;
import org.me.advancedsky.command.IslandAdminCommand;
import org.me.advancedsky.command.IslandPlayerCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.island.*;
import org.me.advancedsky.listener.*;
import org.me.advancedsky.message.PlayerMessageHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.placeholder.AdvancedSkyExpansion;
import org.me.advancedsky.redis.RedisHandler;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.me.advancedsky.util.ServerUtil;
import org.me.advancedsky.uuid.UuidHandler;
import org.me.advancedsky.world.WorldHandler;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class AdvancedSky extends JavaPlugin {

    private ConfigHandler config;
    private RedisHandler redisHandler;
    private DatabaseHandler databaseHandler;
    private OnlinePlayerRegistry onlinePlayerRegistry;
    private LevelUpdateScheduler levelUpdateScheduler;
    private CrossServerMessenger crossServerMessenger;
    private LimitHandler limitHandler;
    private LevelHandler levelHandler;
    private CobblestoneGeneratorHandler cobblestoneGeneratorHandler;
    private AdvancedSkyAPI api;
    private AdvancedSkyExpansion placeholderExpansion;
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
            InvitationStore invitationStore = new InvitationStore(this, redisHandler);
            // No heartbeat: entries this server left behind (crash, reload) are replaced by the players online now.
            onlinePlayerRegistry.removeAllOnServer(serverID);
            Map<UUID, String> onlinePlayers = new HashMap<>();
            for (Player player : getServer().getOnlinePlayers()) {
                onlinePlayers.put(player.getUniqueId(), player.getName());
            }
            onlinePlayerRegistry.addAllOnlinePlayers(onlinePlayers, serverID);
            info("Redis cache state handler loaded");

            info("Loading island cache");
            IslandCache islandCache = new IslandCache(this, databaseHandler, islandGrid);
            // Islands never leave their host, so its cache holds all of them for good, loaded before
            // any player can join or any request arrive.
            if (!config.isLobbyOnly()) {
                islandCache.loadAll(serverID);
            }
            info("Island cache loaded");

            info("Starting world handler");
            WorldHandler worldHandler = new WorldHandler(this, config, islandGrid);
            info("World handler loaded");

            info("Starting teleport handler");
            TeleportHandler teleportHandler = new TeleportHandler();
            info("Teleport handler loaded");

            info("Starting handlers for island remote requests");
            crossServerMessenger = new CrossServerMessenger(this, redisHandler, serverID);
            limitHandler = new LimitHandler(this, config, islandGrid);
            IslandOperator islandOperator = new IslandOperator(this, databaseHandler, worldHandler, teleportHandler, islandCache, limitHandler, serverID);
            IslandDistributor islandDistributor = new IslandDistributor(this, databaseHandler, islandOperator, onlinePlayerRegistry, crossServerMessenger, config.getIslandServerNames(), serverID);
            registerCrossServerHandlers(crossServerMessenger, islandOperator);
            info("All handlers for remote requests loaded");

            info("Starting player message handler");
            PlayerMessageHandler playerMessageHandler = new PlayerMessageHandler(this, crossServerMessenger, onlinePlayerRegistry, serverID);
            info("Player message handler loaded");

            info("Starting main handlers for the plugin");
            CoreHandler coreHandler = new CoreHandler(this, config, databaseHandler, islandDistributor, islandCache);
            PlayerHandler playerHandler = new PlayerHandler(this, databaseHandler, islandDistributor, invitationStore, onlinePlayerRegistry);
            HomeHandler homeHandler = new HomeHandler(this, config, databaseHandler, islandDistributor, onlinePlayerRegistry, islandGrid);
            WarpHandler warpHandler = new WarpHandler(this, config, databaseHandler, islandDistributor, onlinePlayerRegistry, islandGrid);
            levelHandler = new LevelHandler(this, config, databaseHandler, limitHandler, worldHandler, islandCache, islandGrid);
            cobblestoneGeneratorHandler = new CobblestoneGeneratorHandler(this, config);
            BanHandler banHandler = new BanHandler(this, databaseHandler, islandDistributor);
            CoopHandler coopHandler = new CoopHandler(this, databaseHandler, islandDistributor, onlinePlayerRegistry);
            BiomeHandler biomeHandler = new BiomeHandler(this, config, databaseHandler, islandGrid);
            EconomyHandler economyHandler = new EconomyHandler(this);
            UpgradeHandler upgradeHandler = new UpgradeHandler(this, config, databaseHandler, islandDistributor, economyHandler);
            LobbyHandler lobbyHandler = new LobbyHandler(this, config, islandDistributor);
            UuidHandler uuidHandler = new UuidHandler(this, databaseHandler);
            IslandActivityHandler islandActivityHandler = new IslandActivityHandler(this);
            info("All main handlers loaded");

            info("Starting plugin messaging");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            info("Plugin messaging loaded");

            info("Starting all schedulers for the plugin");
            levelUpdateScheduler = new LevelUpdateScheduler(this, levelHandler, islandActivityHandler);
            info("All schedulers loaded");

            info("Starting API");
            api = new AdvancedSkyAPI(this, coreHandler, playerHandler, homeHandler, warpHandler, levelHandler, banHandler, coopHandler, lobbyHandler, playerMessageHandler, uuidHandler, biomeHandler, upgradeHandler);
            info("API loaded");

            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                placeholderExpansion = new AdvancedSkyExpansion(this, config, api);
                if (placeholderExpansion.register()) {
                    info("PlaceholderAPI expansion registered");
                } else {
                    placeholderExpansion.stop();
                    placeholderExpansion = null;
                    warning("Could not register PlaceholderAPI expansion");
                }
            }

            info("Starting listeners");
            getServer().getPluginManager().registerEvents(new OnlinePlayersListener(this, onlinePlayerRegistry, serverID), this);
            getServer().getPluginManager().registerEvents(new IslandBorderListener(config, islandCache, islandGrid), this);
            getServer().getPluginManager().registerEvents(new IslandActivityListener(this, config, islandCache, islandActivityHandler, levelUpdateScheduler), this);
            getServer().getPluginManager().registerEvents(new TeleportRequestListener(this, teleportHandler), this);
            getServer().getPluginManager().registerEvents(new IslandProtectionListener(config, islandCache, islandGrid), this);
            getServer().getPluginManager().registerEvents(new IslandAccessListener(this, config, islandCache), this);
            getServer().getPluginManager().registerEvents(new IslandRespawnListener(this, config, islandCache), this);
            getServer().getPluginManager().registerEvents(new IslandPvPListener(this, config, islandCache), this);
            getServer().getPluginManager().registerEvents(new CobblestoneGeneratorListener(config, islandCache, cobblestoneGeneratorHandler), this);
            getServer().getPluginManager().registerEvents(new IslandLimitListener(this, config, limitHandler, islandCache), this);
            getServer().getPluginManager().registerEvents(new UuidUpdateListener(this, uuidHandler), this);
            getServer().getPluginManager().registerEvents(new IslandCoopListener(this, coopHandler, onlinePlayerRegistry), this);
            info("All listeners loaded");

            info("Registering commands");
            PluginCommand playerCommand = createCommand("island");
            playerCommand.setAliases(Collections.singletonList("is"));
            IslandPlayerCommand islandPlayerCommand = new IslandPlayerCommand(this, api, config);
            playerCommand.setExecutor(islandPlayerCommand);
            Bukkit.getCommandMap().register("island", playerCommand);

            PluginCommand adminCommand = createCommand("islandadmin");
            adminCommand.setAliases(Collections.singletonList("isadmin"));
            IslandAdminCommand islandAdminCommand = new IslandAdminCommand(this, api, config);
            adminCommand.setExecutor(islandAdminCommand);
            Bukkit.getCommandMap().register("islandadmin", adminCommand);

            AsyncTabCompleteListener asyncTabCompleteListener = new AsyncTabCompleteListener(this);
            asyncTabCompleteListener.registerRoot("island", islandPlayerCommand);
            asyncTabCompleteListener.registerRoot("is", islandPlayerCommand);
            asyncTabCompleteListener.registerRoot("islandadmin", islandAdminCommand);
            asyncTabCompleteListener.registerRoot("isadmin", islandAdminCommand);
            getServer().getPluginManager().registerEvents(asyncTabCompleteListener, this);
            info("All commands registered");


            crossServerMessenger.start();
            levelUpdateScheduler.start();

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An error occurred during plugin initialization", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerCrossServerHandlers(CrossServerMessenger messenger, IslandOperator islandOperator) {
        messenger.register(IslandDistributor.ACTION_ISLAND_CREATE, payload -> emptyResponse(islandOperator.createIsland(uuid(payload, "islandUuid"), uuid(payload, "ownerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_DELETE, payload -> emptyResponse(islandOperator.deleteIsland(Actor.fromJson(payload), uuid(payload, "islandUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE, payload -> islandOperator.prepareTeleport(uuid(payload, "playerUuid"), payload.getString("teleportWorld"), payload.getString("teleportLocation")).thenApply(teleported -> new JSONObject().put("teleported", teleported)));
        messenger.register(IslandDistributor.ACTION_ISLAND_MEMBER_ADD, payload -> emptyResponse(islandOperator.addMember(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"), payload.getString("role"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_MEMBER_REMOVE, payload -> emptyResponse(islandOperator.removeMember(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_OWNER_SET, payload -> emptyResponse(islandOperator.setOwner(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "newOwnerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_BAN_ADD, payload -> emptyResponse(islandOperator.addBan(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_BAN_REMOVE, payload -> emptyResponse(islandOperator.removeBan(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_COOP_ADD, payload -> emptyResponse(islandOperator.addCoop(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_COOP_REMOVE, payload -> emptyResponse(islandOperator.removeCoop(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_LOCK_TOGGLE, payload -> islandOperator.toggleIslandLock(Actor.fromJson(payload), uuid(payload, "islandUuid")).thenApply(locked -> new JSONObject().put("locked", locked)));
        messenger.register(IslandDistributor.ACTION_ISLAND_PVP_TOGGLE, payload -> islandOperator.toggleIslandPvp(Actor.fromJson(payload), uuid(payload, "islandUuid")).thenApply(pvp -> new JSONObject().put("pvp", pvp)));
        messenger.register(IslandDistributor.ACTION_ISLAND_EXPEL, payload -> emptyResponse(islandOperator.expelPlayer(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_HOME_SET, payload -> emptyResponse(islandOperator.setHome(Actor.fromJson(payload), uuid(payload, "islandUuid"), uuid(payload, "playerUuid"), payload.getString("homeName"), payload.getString("homeLocation"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_HOME_DELETE, payload -> emptyResponse(islandOperator.deleteHome(uuid(payload, "islandUuid"), uuid(payload, "playerUuid"), payload.getString("homeName"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_WARP_SET, payload -> emptyResponse(islandOperator.setWarp(Actor.fromJson(payload), uuid(payload, "islandUuid"), payload.getString("warpName"), payload.getString("warpLocation"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_WARP_DELETE, payload -> emptyResponse(islandOperator.deleteWarp(Actor.fromJson(payload), uuid(payload, "islandUuid"), payload.getString("warpName"))));
        messenger.register(IslandDistributor.ACTION_ISLAND_UPGRADE_SET, payload -> emptyResponse(islandOperator.setUpgradeLevel(Actor.fromJson(payload), uuid(payload, "islandUuid"), payload.getString("upgradeId"), payload.getInt("expectedLevel"), payload.getInt("newLevel"))));
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
        if (placeholderExpansion != null) {
            placeholderExpansion.stop();
            placeholderExpansion = null;
        }

        if (levelUpdateScheduler != null) {
            levelUpdateScheduler.stop();
        }

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

    public void reload() {
        info("Plugin configs reloading...");
        config.reload();
        limitHandler.startup();
        levelHandler.startup();
        cobblestoneGeneratorHandler.startup();
        info("Plugin configs reloaded!");
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Set<UUID>> getOnlinePlayersUUIDs() {
        return CompletableFuture.supplyAsync(() -> onlinePlayerRegistry.getOnlinePlayerUuids(), getBukkitAsyncExecutor());
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Set<String>> getOnlinePlayersNames() {
        return CompletableFuture.supplyAsync(() -> onlinePlayerRegistry.getOnlinePlayerNames(), getBukkitAsyncExecutor());
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
