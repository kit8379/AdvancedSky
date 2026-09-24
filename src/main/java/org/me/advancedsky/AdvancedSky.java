package org.me.advancedsky;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.me.advancedsky.command.IslandCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.island.IslandGrid;
import org.me.advancedsky.island.IslandHandler;
import org.me.advancedsky.island.IslandWorldHandler;
import org.me.advancedsky.listener.PlayerJoinListener;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.redis.RedisHandler;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;

import java.util.List;
import java.util.logging.Level;

@SuppressWarnings("UnstableApiUsage")
public class AdvancedSky extends JavaPlugin {

    private ConfigHandler config;
    private RedisHandler redisHandler;
    private DatabaseHandler databaseHandler;
    private CrossServerMessenger crossServerMessenger;

    @Override
    public void onEnable() {
        try {
            initialize();
            info("Plugin enabled on server '" + config.getServerName() + "'");
        } catch (Exception e) {
            severe("An error occurred during plugin initialization", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void initialize() {
        config = new ConfigHandler(this);
        String serverName = config.getServerName();

        // Every server is either an island server or a lobby; anything else is a typo in the lists.
        boolean hostsIslands = config.getIslandServers().contains(serverName);
        if (!hostsIslands && !config.getLobbyServers().contains(serverName)) {
            throw new IllegalStateException("server.name '" + serverName + "' is listed neither under servers.island nor servers.lobby");
        }
        info(hostsIslands ? "Island server: hosting islands in world '" + config.getIslandWorldName() + "'" : "Lobby server: island commands only, no islands hosted here");

        BukkitAsyncExecutor asyncExecutor = new BukkitAsyncExecutor(this);

        redisHandler = new RedisHandler(this, config);
        databaseHandler = new DatabaseHandler(config);
        crossServerMessenger = new CrossServerMessenger(this, redisHandler, serverName);

        IslandGrid islandGrid = new IslandGrid(config.getIslandHeight());
        IslandWorldHandler islandWorldHandler = new IslandWorldHandler(this, config, islandGrid, hostsIslands);
        TeleportHandler teleportHandler = new TeleportHandler(this, redisHandler, islandWorldHandler);
        IslandHandler islandHandler = new IslandHandler(this, config, databaseHandler, crossServerMessenger, islandWorldHandler, teleportHandler, asyncExecutor);

        // Players move between servers through the proxy's "Connect" plugin message.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(islandHandler), this);

        IslandCommand islandCommand = new IslandCommand(this, config, islandHandler);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(islandCommand.build(), "Island commands", List.of("is")));

        crossServerMessenger.start();
    }

    @Override
    public void onDisable() {
        if (crossServerMessenger != null) {
            crossServerMessenger.stop();
        }

        if (redisHandler != null) {
            redisHandler.disconnect();
        }

        if (databaseHandler != null) {
            databaseHandler.close();
        }

        info("Plugin disabled");
    }

    public void info(String message) {
        getLogger().log(Level.INFO, message);
    }

    public void warning(String message) {
        getLogger().log(Level.WARNING, message);
    }

    public void severe(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message, throwable);
    }

    public void debug(String source, String message) {
        if (config.isDebug()) {
            getLogger().log(Level.INFO, "[" + source + "] " + message);
        }
    }
}
