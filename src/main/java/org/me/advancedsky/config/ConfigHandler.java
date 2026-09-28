package org.me.advancedsky.config;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.util.ColorUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigHandler {
    private final AdvancedSky plugin;

    private volatile FileConfiguration config;
    private volatile FileConfiguration messages;
    private volatile FileConfiguration commands;

    public ConfigHandler(AdvancedSky plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = load("config.yml");
        this.messages = load("messages.yml");
        this.commands = load("commands.yml");
    }

    private FileConfiguration load(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        FileConfiguration userConfig = new YamlConfiguration();
        try {
            userConfig.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Cannot parse " + fileName + ", refusing to rewrite it", e);
        }

        FileConfiguration defaultConfig = loadDefaultConfig(fileName);
        if (defaultConfig == null) {
            plugin.severe("Missing default config: " + fileName);
            return userConfig;
        }

        YamlConfiguration rebuiltConfig = new YamlConfiguration();
        copyDefaults(defaultConfig, userConfig, rebuiltConfig, "");
        saveConfig(rebuiltConfig, fileName);
        return rebuiltConfig;
    }

    private FileConfiguration loadDefaultConfig(String fileName) {
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.severe("Error loading default config: " + fileName, e);
            return null;
        }
    }

    private void copyDefaults(FileConfiguration defaultConfig, FileConfiguration userConfig, YamlConfiguration rebuiltConfig, String path) {
        Set<String> keys = Objects.requireNonNull(defaultConfig.getConfigurationSection(path)).getKeys(false);
        for (String key : keys) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            if (defaultConfig.isConfigurationSection(fullPath)) {
                rebuiltConfig.createSection(fullPath);
                copyDefaults(defaultConfig, userConfig, rebuiltConfig, fullPath);
            } else {
                Object value = userConfig.contains(fullPath) ? userConfig.get(fullPath) : defaultConfig.get(fullPath);
                rebuiltConfig.set(fullPath, value);
            }
        }

        ConfigurationSection userSection = userConfig.getConfigurationSection(path);
        if (userSection == null) {
            return;
        }

        for (String key : userSection.getKeys(false)) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            if (!rebuiltConfig.contains(fullPath)) {
                rebuiltConfig.set(fullPath, userConfig.get(fullPath));
            }
        }
    }

    private void saveConfig(FileConfiguration config, String fileName) {
        try {
            config.save(new File(plugin.getDataFolder(), fileName));
        } catch (IOException e) {
            plugin.severe("Could not save " + fileName, e);
        }
    }

    // ================================================================================================================
    // Config Section
    // ================================================================================================================

    public String getServerName() {
        return config.getString("server.name");
    }

    public String getIslandWorldName() {
        return config.getString("server.world");
    }

    public String getMySQLHost() {
        return config.getString("mysql.host");
    }

    public int getMySQLPort() {
        return config.getInt("mysql.port");
    }

    public String getMySQLDB() {
        return config.getString("mysql.database");
    }

    public String getMySQLUsername() {
        return config.getString("mysql.username");
    }

    public String getMySQLPassword() {
        return config.getString("mysql.password");
    }

    public boolean getMySQLUseSSL() {
        return config.getBoolean("mysql.use-ssl");
    }

    public String getMySQLProperties() {
        return config.getString("mysql.properties");
    }

    public String getMySQLTablePrefix() {
        return config.getString("mysql.prefix");
    }

    public String getRedisHost() {
        return config.getString("redis.host");
    }

    public int getRedisPort() {
        return config.getInt("redis.port");
    }

    public String getRedisPassword() {
        return config.getString("redis.password");
    }

    public String getClusterId() {
        return config.getString("cluster-id");
    }

    public List<String> getIslandServerNames() {
        return config.getStringList("servers.island");
    }

    public List<String> getLobbyServerNames() {
        return config.getStringList("servers.lobby");
    }

    public boolean isLobbyOnly() {
        return getLobbyServerNames().contains(getServerName());
    }

    public int getIslandHeight() {
        return config.getInt("island.height");
    }

    public String getTemplateWorldName() {
        return config.getString("island.template.world");
    }

    public int getTemplateAnchorX() {
        return config.getInt("island.template.anchor.x");
    }

    public int getTemplateAnchorY() {
        return config.getInt("island.template.anchor.y");
    }

    public int getTemplateAnchorZ() {
        return config.getInt("island.template.anchor.z");
    }

    public int getTemplateMinX() {
        return config.getInt("island.template.min.x");
    }

    public int getTemplateMinY() {
        return config.getInt("island.template.min.y");
    }

    public int getTemplateMinZ() {
        return config.getInt("island.template.min.z");
    }

    public int getTemplateMaxX() {
        return config.getInt("island.template.max.x");
    }

    public int getTemplateMaxY() {
        return config.getInt("island.template.max.y");
    }

    public int getTemplateMaxZ() {
        return config.getInt("island.template.max.z");
    }

    public boolean isDebug() {
        return config.getBoolean("debug");
    }

// ================================================================================================================
// Commands Section
// ================================================================================================================

    public List<String> getPlayerCommandOrder() {
        return Objects.requireNonNull(commands.getConfigurationSection("commands.player")).getKeys(false).stream().toList();
    }

    public String[] getPlayerCreateAliases() {
        return commands.getStringList("commands.player.create.aliases").toArray(new String[0]);
    }

    public String getPlayerCreatePermission() {
        return commands.getString("commands.player.create.permission");
    }

    public String getPlayerCreateSyntax() {
        return commands.getString("commands.player.create.syntax");
    }

    public String getPlayerCreateDescription() {
        return commands.getString("commands.player.create.description");
    }

    public String[] getPlayerDeleteAliases() {
        return commands.getStringList("commands.player.delete.aliases").toArray(new String[0]);
    }

    public String getPlayerDeletePermission() {
        return commands.getString("commands.player.delete.permission");
    }

    public String getPlayerDeleteSyntax() {
        return commands.getString("commands.player.delete.syntax");
    }

    public String getPlayerDeleteDescription() {
        return commands.getString("commands.player.delete.description");
    }

    public String[] getPlayerHelpAliases() {
        return commands.getStringList("commands.player.help.aliases").toArray(new String[0]);
    }

    public String getPlayerHelpPermission() {
        return commands.getString("commands.player.help.permission");
    }

    public String getPlayerHelpSyntax() {
        return commands.getString("commands.player.help.syntax");
    }

    public String getPlayerHelpDescription() {
        return commands.getString("commands.player.help.description");
    }

// =========================================================
// Messages Section
// =========================================================

    // General Messages
    public Component getOnlyPlayerCanRunCommandMessage() {
        return ColorUtils.colorize(messages.getString("messages.only-player-can-run-command"));
    }

    public Component getNoPermissionCommandMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.no-permission-command")));
    }

    public Component getIslandGridFullMessage() {
        return ColorUtils.colorize(messages.getString("messages.island-grid-full"));
    }

    public Component getUnknownExceptionMessage() {
        return ColorUtils.colorize(messages.getString("messages.unknown-exception"));
    }

    // Player Command Messages
    public Component getPlayerCommandUsageMessage(String command, String syntax) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-usage-command")).replace("{command}", command).replace("{syntax}", syntax));
    }

    public Component getPlayerUnknownSubCommandMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-unknown-sub-command")));
    }

    public Component getPlayerNoIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-no-island"));
    }

    public Component getPlayerAlreadyHasIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-already-has-island"));
    }

    public Component getPlayerCreateSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-create-island-success"));
    }

    public Component getPlayerDeleteFirstWarningMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-delete-first-warning"));
    }

    public Component getPlayerDeleteFinalWarningMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-delete-final-warning"));
    }

    public Component getPlayerDeleteSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-delete-island-success"));
    }

    public Component getPlayerDeleteNotOwnerMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-delete-not-owner"));
    }

    // Help
    public Component getPlayerHelpHeader() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-help-header")));
    }

    public Component getPlayerHelpEntry(String command, String syntax, String description) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-help-entry")).replace("{command}", command).replace("{syntax}", syntax).replace("{description}", description));
    }

    public Component getPlayerHelpFooter(int page, int total) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-help-footer")).replace("{prev}", String.valueOf(page - 1)).replace("{next}", String.valueOf(page + 1)).replace("{page}", String.valueOf(page)).replace("{total}", String.valueOf(total)));
    }
}
