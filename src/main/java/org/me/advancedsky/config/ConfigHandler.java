package org.me.advancedsky.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;
import org.me.advancedsky.AdvancedSky;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class ConfigHandler {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final AdvancedSky plugin;

    private volatile FileConfiguration config;
    private volatile FileConfiguration messages;

    public ConfigHandler(AdvancedSky plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = load("config.yml");
        this.messages = load("messages.yml");
    }

    private FileConfiguration load(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Cannot parse " + fileName, e);
        }
        return yaml;
    }

    // ===================================================================
    // Server
    // ===================================================================

    public String getServerName() {
        return requireString("server.name");
    }

    public String getIslandWorldName() {
        return requireString("server.world");
    }

    // ===================================================================
    // Servers
    // ===================================================================

    public List<String> getIslandServers() {
        return config.getStringList("servers.island");
    }

    public List<String> getLobbyServers() {
        return config.getStringList("servers.lobby");
    }

    // ===================================================================
    // Island
    // ===================================================================

    public int getIslandHeight() {
        return config.getInt("island.height");
    }

    public Vector getIslandSpawnOffset() {
        return new Vector(config.getDouble("island.spawn.x"), config.getDouble("island.spawn.y"), config.getDouble("island.spawn.z"));
    }

    public float getIslandSpawnYaw() {
        return (float) config.getDouble("island.spawn.yaw");
    }

    public float getIslandSpawnPitch() {
        return (float) config.getDouble("island.spawn.pitch");
    }

    public String getTemplateWorldName() {
        return requireString("island.template.world");
    }

    public BlockVector getTemplateAnchor() {
        return blockVector("island.template.anchor");
    }

    public BlockVector getTemplateMin() {
        return blockVector("island.template.min");
    }

    public BlockVector getTemplateMax() {
        return blockVector("island.template.max");
    }

    // ===================================================================
    // MySQL
    // ===================================================================

    public String getMySQLHost() {
        return requireString("mysql.host");
    }

    public int getMySQLPort() {
        return config.getInt("mysql.port");
    }

    public String getMySQLDatabase() {
        return requireString("mysql.database");
    }

    public String getMySQLUsername() {
        return requireString("mysql.username");
    }

    public String getMySQLPassword() {
        return config.getString("mysql.password", "");
    }

    public String getMySQLProperties() {
        return config.getString("mysql.properties", "");
    }

    public String getMySQLTablePrefix() {
        return config.getString("mysql.prefix", "");
    }

    // ===================================================================
    // Redis
    // ===================================================================

    public String getRedisHost() {
        return requireString("redis.host");
    }

    public int getRedisPort() {
        return config.getInt("redis.port");
    }

    public String getRedisPassword() {
        return config.getString("redis.password", "");
    }

    public String getRedisClusterId() {
        return requireString("redis.cluster-id");
    }

    // ===================================================================
    // Debug
    // ===================================================================

    public boolean isDebug() {
        return config.getBoolean("debug");
    }

    // ===================================================================
    // Messages
    // ===================================================================

    public Component getOnlyPlayerCanRunCommandMessage() {
        return message("only-player-can-run-command");
    }

    public Component getUnknownExceptionMessage() {
        return message("unknown-exception");
    }

    public Component getIslandCreatedMessage() {
        return message("island-created");
    }

    public Component getIslandDeletedMessage() {
        return message("island-deleted");
    }

    public Component getAlreadyHasIslandMessage() {
        return message("already-has-island");
    }

    public Component getNoIslandMessage() {
        return message("no-island");
    }

    public Component getIslandGridFullMessage() {
        return message("island-grid-full");
    }

    public Component getSendingToServerMessage(String server) {
        return MINI.deserialize(rawMessage("sending-to-server").replace("{server}", server));
    }

    private Component message(String key) {
        return MINI.deserialize(rawMessage(key));
    }

    private String rawMessage(String key) {
        return Objects.requireNonNull(messages.getString("messages." + key), "Missing message: " + key);
    }

    private String requireString(String path) {
        return Objects.requireNonNull(config.getString(path), "Missing config value: " + path);
    }

    private BlockVector blockVector(String path) {
        return new BlockVector(config.getInt(path + ".x"), config.getInt(path + ".y"), config.getInt(path + ".z"));
    }
}
