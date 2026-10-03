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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigHandler {

    /**
     * Sections whose entries are the operator's own list, by file: not completed from the
     * defaults. {@code *} stands for any one key, so each upgrade's levels are the operator's.
     */
    private static final Map<String, List<String>> OWN_SECTIONS = Map.of("limits.yml", List.of("limit.blocks", "limit.entities"), "levels.yml", List.of("blocks"), "upgrades.yml", List.of("upgrades.*"));

    private final AdvancedSky plugin;

    private volatile FileConfiguration config;
    private volatile FileConfiguration messages;
    private final FileConfiguration commands;
    private volatile FileConfiguration levels;
    private volatile FileConfiguration upgrades;
    private volatile FileConfiguration limits;

    // Read once at startup, as the other New plugins do: which server this is, the servers of the
    // network and the island world stay as they were for the whole run, like the commands.
    private final String serverName;
    private final List<String> islandServerNames;
    private final List<String> lobbyServerNames;
    private final String islandWorldName;

    // Read on every move, block change and hit, so kept from the last load rather than looked up
    // in the YAML tree each time.
    private volatile Map<String, Map<Integer, Integer>> upgradeLimits;
    // Read on every upgrade placeholder request and upgrade check, so kept the same way.
    private volatile Map<String, List<Integer>> upgradeLevels;
    private volatile Map<String, Map<Integer, String>> upgradeValues;

    public ConfigHandler(AdvancedSky plugin) {
        this.plugin = plugin;
        this.commands = load("commands.yml");
        reload();
        this.serverName = config.getString("server.name");
        this.islandServerNames = List.copyOf(config.getStringList("servers.island"));
        this.lobbyServerNames = List.copyOf(config.getStringList("servers.lobby"));
        this.islandWorldName = config.getString("island.world");
    }

    /**
     * Loads the files again, all but commands.yml, which like the settings read once at startup
     * stays as it was until a restart. One reload at a time: each rewrites the files it reads,
     * and one reading a file another is rewriting would find it empty and write the defaults over
     * it.
     */
    public synchronized void reload() {
        this.config = load("config.yml");
        this.messages = load("messages.yml");
        this.levels = load("levels.yml");
        this.upgrades = load("upgrades.yml");
        this.limits = load("limits.yml");
        this.upgradeLevels = loadUpgradeLevels();
        this.upgradeLimits = loadUpgradeLimits();
        this.upgradeValues = loadUpgradeValues();
    }

    /**
     * The levels of every upgrade, ascending, by upgrade id in config order.
     */
    private Map<String, List<Integer>> loadUpgradeLevels() {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (String upgradeId : Objects.requireNonNull(upgrades.getConfigurationSection("upgrades")).getKeys(false)) {
            result.put(upgradeId, Objects.requireNonNull(upgrades.getConfigurationSection("upgrades." + upgradeId)).getKeys(false).stream().map(Integer::parseInt).sorted().toList());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * The size or limit of every level of every upgrade, by upgrade id and level.
     */
    private Map<String, Map<Integer, Integer>> loadUpgradeLimits() {
        Map<String, Map<Integer, Integer>> result = new HashMap<>();
        for (String upgradeId : getUpgradeIds()) {
            String valueKey = upgradeId.equals("island-size") ? "size" : "limit";
            Map<Integer, Integer> byLevel = new HashMap<>();
            for (int level : getUpgradeLevels(upgradeId)) {
                byLevel.put(level, upgrades.getInt("upgrades." + upgradeId + "." + level + "." + valueKey));
            }
            result.put(upgradeId, Map.copyOf(byLevel));
        }
        return Map.copyOf(result);
    }

    /**
     * The value players see for every level of every upgrade, by upgrade id and level.
     */
    private Map<String, Map<Integer, String>> loadUpgradeValues() {
        Map<String, Map<Integer, String>> result = new HashMap<>();
        for (String upgradeId : getUpgradeIds()) {
            Map<Integer, String> byLevel = new HashMap<>();
            for (int level : getUpgradeLevels(upgradeId)) {
                byLevel.put(level, upgradeValue(upgradeId, level));
            }
            result.put(upgradeId, Map.copyOf(byLevel));
        }
        return Map.copyOf(result);
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
        rebuiltConfig.options().setHeader(userConfig.options().getHeader());
        rebuiltConfig.options().setFooter(userConfig.options().getFooter());
        copyDefaults(defaultConfig, userConfig, rebuiltConfig, "", OWN_SECTIONS.getOrDefault(fileName, List.of()));
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

    /**
     * Completes the user's file with what the defaults have and it lacks, keeping its values and
     * comments. A section listed in {@link #OWN_SECTIONS} that the user's file has is kept as it
     * is, so an entry taken out of it stays out.
     */
    private void copyDefaults(FileConfiguration defaultConfig, FileConfiguration userConfig, YamlConfiguration rebuiltConfig, String path, List<String> ownSections) {
        Set<String> keys = Objects.requireNonNull(defaultConfig.getConfigurationSection(path)).getKeys(false);
        for (String key : keys) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            FileConfiguration source = userConfig.contains(fullPath) ? userConfig : defaultConfig;
            if (defaultConfig.isConfigurationSection(fullPath) && !(userConfig.isConfigurationSection(fullPath) && isOwnSection(fullPath, ownSections))) {
                rebuiltConfig.createSection(fullPath);
                copyComments(source, rebuiltConfig, fullPath);
                copyDefaults(defaultConfig, userConfig, rebuiltConfig, fullPath, ownSections);
            } else {
                rebuiltConfig.set(fullPath, source.get(fullPath));
                copyComments(source, rebuiltConfig, fullPath);
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
                copyComments(userConfig, rebuiltConfig, fullPath);
            }
        }
    }

    /**
     * Whether the path is one of the sections, {@code *} matching any one key.
     */
    private static boolean isOwnSection(String path, List<String> ownSections) {
        String[] keys = path.split("\\.");
        for (String ownSection : ownSections) {
            String[] pattern = ownSection.split("\\.");
            if (pattern.length != keys.length) {
                continue;
            }

            boolean matches = true;
            for (int i = 0; i < keys.length && matches; i++) {
                matches = pattern[i].equals("*") || pattern[i].equals(keys[i]);
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static void copyComments(FileConfiguration from, YamlConfiguration to, String path) {
        to.setComments(path, from.getComments(path));
        to.setInlineComments(path, from.getInlineComments(path));
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
        return serverName;
    }

    public boolean isLobbyOnly() {
        return lobbyServerNames.contains(serverName);
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

    public String[] getAdminPurgeAliases() {
        return commands.getStringList("commands.admin.purge.aliases").toArray(new String[0]);
    }

    public String getAdminPurgePermission() {
        return commands.getString("commands.admin.purge.permission");
    }

    public String getAdminPurgeSyntax() {
        return commands.getString("commands.admin.purge.syntax");
    }

    public String getAdminPurgeDescription() {
        return commands.getString("commands.admin.purge.description");
    }

    public Component getRegionPurgeStartedMessage() {
        return ColorUtils.colorize(messages.getString("messages.region-purge-started"));
    }

    public Component getRegionPurgeFinishedMessage(int purged, int deferred) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.region-purge-finished"))
                .replace("{purged}", Integer.toString(purged)).replace("{deferred}", Integer.toString(deferred)));
    }

    public Component getRegionPurgeIslandServerMessage() {
        return ColorUtils.colorize(messages.getString("messages.region-purge-island-server"));
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
        return islandServerNames;
    }

    public List<String> getLobbyServerNames() {
        return lobbyServerNames;
    }

    public String getLobbyWorldName() {
        return config.getString("lobby.world-name");
    }

    public double getLobbyX() {
        return config.getDouble("lobby.location.x");
    }

    public double getLobbyY() {
        return config.getDouble("lobby.location.y");
    }

    public double getLobbyZ() {
        return config.getDouble("lobby.location.z");
    }

    public float getLobbyYaw() {
        return (float) config.getDouble("lobby.location.yaw");
    }

    public float getLobbyPitch() {
        return (float) config.getDouble("lobby.location.pitch");
    }

    public String getIslandWorldName() {
        return islandWorldName;
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

    public double getIslandSpawnX() {
        return config.getDouble("island.spawn.x");
    }

    public double getIslandSpawnY() {
        return config.getDouble("island.spawn.y");
    }

    public double getIslandSpawnZ() {
        return config.getDouble("island.spawn.z");
    }

    public float getIslandSpawnYaw() {
        return (float) config.getDouble("island.spawn.yaw");
    }

    public float getIslandSpawnPitch() {
        return (float) config.getDouble("island.spawn.pitch");
    }

    public List<String> getUpgradeIds() {
        return List.copyOf(upgradeLevels.keySet());
    }

    public boolean isUpgrade(String upgradeId) {
        return upgradeLevels.containsKey(upgradeId);
    }

    /**
     * Ascending. Level keys are consecutive integers by config contract, so the first is the
     * level every island starts at and the last is the maximum.
     */
    public List<Integer> getUpgradeLevels(String upgradeId) {
        return Objects.requireNonNull(upgradeLevels.get(upgradeId), "upgrades." + upgradeId);
    }

    /**
     * Zero for an upgrade or level the config does not have.
     */
    public int getUpgradeLimit(String upgradeId, int level) {
        return upgradeLimits.getOrDefault(upgradeId, Map.of()).getOrDefault(level, 0);
    }

    /**
     * Biome keys a member may set at that level of the {@code biomes} upgrade, lowercased.
     * Spell them as the biome command does ({@code PLAINS} or {@code plains}, not
     * {@code minecraft:plains}).
     */
    public List<String> getUpgradeAllowedBiomes(int level) {
        return upgrades.getStringList("upgrades.biomes." + level + ".allowed").stream().map(biome -> biome.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Block material weights of the {@code generator-rates} upgrade at that level, in config
     * order. Weights are relative to each other; they are shown to players as percentages.
     */
    public Map<String, Double> getUpgradeGeneratorRates(int level) {
        String path = "upgrades.generator-rates." + level + ".rates";
        ConfigurationSection rates = Objects.requireNonNull(upgrades.getConfigurationSection(path), path);

        Map<String, Double> result = new LinkedHashMap<>();
        for (String material : rates.getKeys(false)) {
            result.put(material, rates.getDouble(material));
        }

        return result;
    }

    /**
     * The level's value as players see it: the numeric limit or size, the allowed biome list,
     * or the generator rates as percentages.
     */
    public String getUpgradeValue(String upgradeId, int level) {
        String value = upgradeValues.getOrDefault(upgradeId, Map.of()).get(level);
        return value != null ? value : upgradeValue(upgradeId, level);
    }

    private String upgradeValue(String upgradeId, int level) {
        if (upgradeId.equals("biomes")) {
            return String.join(", ", getUpgradeAllowedBiomes(level));
        }

        if (upgradeId.equals("generator-rates")) {
            Map<String, Double> rates = getUpgradeGeneratorRates(level);
            double total = rates.values().stream().mapToDouble(Double::doubleValue).sum();
            StringJoiner value = new StringJoiner(", ");
            for (Map.Entry<String, Double> rate : rates.entrySet()) {
                String percent = BigDecimal.valueOf(rate.getValue() / total * 100).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                value.add(rate.getKey() + " " + percent + "%");
            }
            return value.toString();
        }

        return String.valueOf(getUpgradeLimit(upgradeId, level));
    }

    public int getUpgradeRequireLevel(String upgradeId, int level) {
        return upgrades.getInt("upgrades." + upgradeId + "." + level + ".require-level");
    }

    public double getUpgradePrice(String upgradeId, int level) {
        return upgrades.getDouble("upgrades." + upgradeId + "." + level + ".price");
    }

    public String getBaseCommandMode() {
        return config.getString("command.base-command-mode");
    }

    public boolean isDebug() {
        return config.getBoolean("debug");
    }

    // ================================================================================================================
    // Levels Section
    // ================================================================================================================

    public int getBlockLevel(String material) {
        return levels.getInt("blocks." + material, 0);
    }

    // ================================================================================================================
    // Limits Section
    // ================================================================================================================

    /** Block caps by material name, in config order; 0 leaves that block unlimited. */
    public Map<String, Integer> getBlockLimits() {
        return getLimits("limit.blocks");
    }

    /** Entity caps by entity type name, in config order; 0 leaves that entity unlimited. */
    public Map<String, Integer> getEntityLimits() {
        return getLimits("limit.entities");
    }

    private Map<String, Integer> getLimits(String path) {
        ConfigurationSection section = Objects.requireNonNull(limits.getConfigurationSection(path), path);

        Map<String, Integer> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            result.put(key, section.getInt(key));
        }

        return result;
    }

// ================================================================================================================
// Commands Section
// ================================================================================================================

    public List<String> getPlayerCommandOrder() {
        return Objects.requireNonNull(commands.getConfigurationSection("commands.player")).getKeys(false).stream().toList();
    }

    public String[] getPlayerInviteAliases() {
        return commands.getStringList("commands.player.invite.aliases").toArray(new String[0]);
    }

    public String getPlayerInvitePermission() {
        return commands.getString("commands.player.invite.permission");
    }

    public String getPlayerInviteSyntax() {
        return commands.getString("commands.player.invite.syntax");
    }

    public String getPlayerInviteDescription() {
        return commands.getString("commands.player.invite.description");
    }

    public String[] getPlayerAcceptAliases() {
        return commands.getStringList("commands.player.accept.aliases").toArray(new String[0]);
    }

    public String getPlayerAcceptPermission() {
        return commands.getString("commands.player.accept.permission");
    }

    public String getPlayerAcceptSyntax() {
        return commands.getString("commands.player.accept.syntax");
    }

    public String getPlayerAcceptDescription() {
        return commands.getString("commands.player.accept.description");
    }

    public String[] getPlayerRejectAliases() {
        return commands.getStringList("commands.player.reject.aliases").toArray(new String[0]);
    }

    public String getPlayerRejectPermission() {
        return commands.getString("commands.player.reject.permission");
    }

    public String getPlayerRejectSyntax() {
        return commands.getString("commands.player.reject.syntax");
    }

    public String getPlayerRejectDescription() {
        return commands.getString("commands.player.reject.description");
    }

    public String[] getPlayerBanAliases() {
        return commands.getStringList("commands.player.ban.aliases").toArray(new String[0]);
    }

    public String getPlayerBanPermission() {
        return commands.getString("commands.player.ban.permission");
    }

    public String getPlayerBanSyntax() {
        return commands.getString("commands.player.ban.syntax");
    }

    public String getPlayerBanDescription() {
        return commands.getString("commands.player.ban.description");
    }

    public String[] getPlayerBanListAliases() {
        return commands.getStringList("commands.player.banlist.aliases").toArray(new String[0]);
    }

    public String getPlayerBanListPermission() {
        return commands.getString("commands.player.banlist.permission");
    }

    public String getPlayerBanListSyntax() {
        return commands.getString("commands.player.banlist.syntax");
    }

    public String getPlayerBanListDescription() {
        return commands.getString("commands.player.banlist.description");
    }

    public String[] getPlayerCoopAliases() {
        return commands.getStringList("commands.player.coop.aliases").toArray(new String[0]);
    }

    public String getPlayerCoopPermission() {
        return commands.getString("commands.player.coop.permission");
    }

    public String getPlayerCoopSyntax() {
        return commands.getString("commands.player.coop.syntax");
    }

    public String getPlayerCoopDescription() {
        return commands.getString("commands.player.coop.description");
    }

    public String[] getPlayerCoopListAliases() {
        return commands.getStringList("commands.player.cooplist.aliases").toArray(new String[0]);
    }

    public String getPlayerCoopListPermission() {
        return commands.getString("commands.player.cooplist.permission");
    }

    public String getPlayerCoopListSyntax() {
        return commands.getString("commands.player.cooplist.syntax");
    }

    public String getPlayerCoopListDescription() {
        return commands.getString("commands.player.cooplist.description");
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

    public String[] getPlayerDelHomeAliases() {
        return commands.getStringList("commands.player.delhome.aliases").toArray(new String[0]);
    }

    public String getPlayerDelHomePermission() {
        return commands.getString("commands.player.delhome.permission");
    }

    public String getPlayerDelHomeSyntax() {
        return commands.getString("commands.player.delhome.syntax");
    }

    public String getPlayerDelHomeDescription() {
        return commands.getString("commands.player.delhome.description");
    }

    public String[] getPlayerDelWarpAliases() {
        return commands.getStringList("commands.player.delwarp.aliases").toArray(new String[0]);
    }

    public String getPlayerDelWarpPermission() {
        return commands.getString("commands.player.delwarp.permission");
    }

    public String getPlayerDelWarpSyntax() {
        return commands.getString("commands.player.delwarp.syntax");
    }

    public String getPlayerDelWarpDescription() {
        return commands.getString("commands.player.delwarp.description");
    }

    public String[] getPlayerExpelAliases() {
        return commands.getStringList("commands.player.expel.aliases").toArray(new String[0]);
    }

    public String getPlayerExpelPermission() {
        return commands.getString("commands.player.expel.permission");
    }

    public String getPlayerExpelSyntax() {
        return commands.getString("commands.player.expel.syntax");
    }

    public String getPlayerExpelDescription() {
        return commands.getString("commands.player.expel.description");
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

    public String[] getPlayerHomeAliases() {
        return commands.getStringList("commands.player.home.aliases").toArray(new String[0]);
    }

    public String getPlayerHomePermission() {
        return commands.getString("commands.player.home.permission");
    }

    public String getPlayerHomeSyntax() {
        return commands.getString("commands.player.home.syntax");
    }

    public String getPlayerHomeDescription() {
        return commands.getString("commands.player.home.description");
    }

    public String[] getPlayerInfoAliases() {
        return commands.getStringList("commands.player.info.aliases").toArray(new String[0]);
    }

    public String getPlayerInfoPermission() {
        return commands.getString("commands.player.info.permission");
    }

    public String getPlayerInfoSyntax() {
        return commands.getString("commands.player.info.syntax");
    }

    public String getPlayerInfoDescription() {
        return commands.getString("commands.player.info.description");
    }

    public String[] getPlayerLeaveAliases() {
        return commands.getStringList("commands.player.leave.aliases").toArray(new String[0]);
    }

    public String getPlayerLeavePermission() {
        return commands.getString("commands.player.leave.permission");
    }

    public String getPlayerLeaveSyntax() {
        return commands.getString("commands.player.leave.syntax");
    }

    public String getPlayerLeaveDescription() {
        return commands.getString("commands.player.leave.description");
    }

    public String[] getPlayerLevelAliases() {
        return commands.getStringList("commands.player.level.aliases").toArray(new String[0]);
    }

    public String getPlayerLevelPermission() {
        return commands.getString("commands.player.level.permission");
    }

    public String getPlayerLevelSyntax() {
        return commands.getString("commands.player.level.syntax");
    }

    public String getPlayerLevelDescription() {
        return commands.getString("commands.player.level.description");
    }

    public String[] getPlayerLockAliases() {
        return commands.getStringList("commands.player.lock.aliases").toArray(new String[0]);
    }

    public String getPlayerLockPermission() {
        return commands.getString("commands.player.lock.permission");
    }

    public String getPlayerLockSyntax() {
        return commands.getString("commands.player.lock.syntax");
    }

    public String getPlayerLockDescription() {
        return commands.getString("commands.player.lock.description");
    }

    public String[] getPlayerPvpAliases() {
        return commands.getStringList("commands.player.pvp.aliases").toArray(new String[0]);
    }

    public String getPlayerPvpPermission() {
        return commands.getString("commands.player.pvp.permission");
    }

    public String getPlayerPvpSyntax() {
        return commands.getString("commands.player.pvp.syntax");
    }

    public String getPlayerPvpDescription() {
        return commands.getString("commands.player.pvp.description");
    }

    public String[] getPlayerRemoveMemberAliases() {
        return commands.getStringList("commands.player.removemember.aliases").toArray(new String[0]);
    }

    public String getPlayerRemoveMemberPermission() {
        return commands.getString("commands.player.removemember.permission");
    }

    public String getPlayerRemoveMemberSyntax() {
        return commands.getString("commands.player.removemember.syntax");
    }

    public String getPlayerRemoveMemberDescription() {
        return commands.getString("commands.player.removemember.description");
    }

    public String[] getPlayerSetHomeAliases() {
        return commands.getStringList("commands.player.sethome.aliases").toArray(new String[0]);
    }

    public String getPlayerSetHomePermission() {
        return commands.getString("commands.player.sethome.permission");
    }

    public String getPlayerSetHomeSyntax() {
        return commands.getString("commands.player.sethome.syntax");
    }

    public String getPlayerSetHomeDescription() {
        return commands.getString("commands.player.sethome.description");
    }

    public String[] getPlayerSetOwnerAliases() {
        return commands.getStringList("commands.player.setowner.aliases").toArray(new String[0]);
    }

    public String getPlayerSetOwnerPermission() {
        return commands.getString("commands.player.setowner.permission");
    }

    public String getPlayerSetOwnerSyntax() {
        return commands.getString("commands.player.setowner.syntax");
    }

    public String getPlayerSetOwnerDescription() {
        return commands.getString("commands.player.setowner.description");
    }

    public String[] getPlayerSetWarpAliases() {
        return commands.getStringList("commands.player.setwarp.aliases").toArray(new String[0]);
    }

    public String getPlayerSetWarpPermission() {
        return commands.getString("commands.player.setwarp.permission");
    }

    public String getPlayerSetWarpSyntax() {
        return commands.getString("commands.player.setwarp.syntax");
    }

    public String getPlayerSetWarpDescription() {
        return commands.getString("commands.player.setwarp.description");
    }

    public String[] getPlayerTopAliases() {
        return commands.getStringList("commands.player.top.aliases").toArray(new String[0]);
    }

    public String getPlayerTopPermission() {
        return commands.getString("commands.player.top.permission");
    }

    public String getPlayerTopSyntax() {
        return commands.getString("commands.player.top.syntax");
    }

    public String getPlayerTopDescription() {
        return commands.getString("commands.player.top.description");
    }

    public String[] getPlayerUnbanAliases() {
        return commands.getStringList("commands.player.unban.aliases").toArray(new String[0]);
    }

    public String getPlayerUnbanPermission() {
        return commands.getString("commands.player.unban.permission");
    }

    public String getPlayerUnbanSyntax() {
        return commands.getString("commands.player.unban.syntax");
    }

    public String getPlayerUnbanDescription() {
        return commands.getString("commands.player.unban.description");
    }

    public String[] getPlayerUncoopAliases() {
        return commands.getStringList("commands.player.uncoop.aliases").toArray(new String[0]);
    }

    public String getPlayerUncoopPermission() {
        return commands.getString("commands.player.uncoop.permission");
    }

    public String getPlayerUncoopSyntax() {
        return commands.getString("commands.player.uncoop.syntax");
    }

    public String getPlayerUncoopDescription() {
        return commands.getString("commands.player.uncoop.description");
    }

    public String[] getPlayerValueAliases() {
        return commands.getStringList("commands.player.value.aliases").toArray(new String[0]);
    }

    public String getPlayerValuePermission() {
        return commands.getString("commands.player.value.permission");
    }

    public String getPlayerValueSyntax() {
        return commands.getString("commands.player.value.syntax");
    }

    public String getPlayerValueDescription() {
        return commands.getString("commands.player.value.description");
    }

    public String[] getPlayerWarpAliases() {
        return commands.getStringList("commands.player.warp.aliases").toArray(new String[0]);
    }

    public String getPlayerWarpPermission() {
        return commands.getString("commands.player.warp.permission");
    }

    public String getPlayerWarpSyntax() {
        return commands.getString("commands.player.warp.syntax");
    }

    public String getPlayerWarpDescription() {
        return commands.getString("commands.player.warp.description");
    }

    public String[] getPlayerLobbyAliases() {
        return commands.getStringList("commands.player.lobby.aliases").toArray(new String[0]);
    }

    public String getPlayerLobbyPermission() {
        return commands.getString("commands.player.lobby.permission");
    }

    public String getPlayerLobbySyntax() {
        return commands.getString("commands.player.lobby.syntax");
    }

    public String getPlayerLobbyDescription() {
        return commands.getString("commands.player.lobby.description");
    }

    public String[] getPlayerBiomeAliases() {
        return commands.getStringList("commands.player.biome.aliases").toArray(new String[0]);
    }

    public String getPlayerBiomePermission() {
        return commands.getString("commands.player.biome.permission");
    }

    public String getPlayerBiomeSyntax() {
        return commands.getString("commands.player.biome.syntax");
    }

    public String getPlayerBiomeDescription() {
        return commands.getString("commands.player.biome.description");
    }

    public String[] getPlayerUpgradeAliases() {
        return commands.getStringList("commands.player.upgrade.aliases").toArray(new String[0]);
    }

    public String getPlayerUpgradePermission() {
        return commands.getString("commands.player.upgrade.permission");
    }

    public String getPlayerUpgradeSyntax() {
        return commands.getString("commands.player.upgrade.syntax");
    }

    public String getPlayerUpgradeDescription() {
        return commands.getString("commands.player.upgrade.description");
    }

    public List<String> getAdminCommandOrder() {
        return Objects.requireNonNull(commands.getConfigurationSection("commands.admin")).getKeys(false).stream().toList();
    }

    public String[] getAdminAddMemberAliases() {
        return commands.getStringList("commands.admin.addmember.aliases").toArray(new String[0]);
    }

    public String getAdminAddMemberPermission() {
        return commands.getString("commands.admin.addmember.permission");
    }

    public String getAdminAddMemberSyntax() {
        return commands.getString("commands.admin.addmember.syntax");
    }

    public String getAdminAddMemberDescription() {
        return commands.getString("commands.admin.addmember.description");
    }

    public String[] getAdminBanAliases() {
        return commands.getStringList("commands.admin.ban.aliases").toArray(new String[0]);
    }

    public String getAdminBanPermission() {
        return commands.getString("commands.admin.ban.permission");
    }

    public String getAdminBanSyntax() {
        return commands.getString("commands.admin.ban.syntax");
    }

    public String getAdminBanDescription() {
        return commands.getString("commands.admin.ban.description");
    }

    public String[] getAdminCoopAliases() {
        return commands.getStringList("commands.admin.coop.aliases").toArray(new String[0]);
    }

    public String getAdminCoopPermission() {
        return commands.getString("commands.admin.coop.permission");
    }

    public String getAdminCoopSyntax() {
        return commands.getString("commands.admin.coop.syntax");
    }

    public String getAdminCoopDescription() {
        return commands.getString("commands.admin.coop.description");
    }

    public String[] getAdminCreateAliases() {
        return commands.getStringList("commands.admin.create.aliases").toArray(new String[0]);
    }

    public String getAdminCreatePermission() {
        return commands.getString("commands.admin.create.permission");
    }

    public String getAdminCreateSyntax() {
        return commands.getString("commands.admin.create.syntax");
    }

    public String getAdminCreateDescription() {
        return commands.getString("commands.admin.create.description");
    }

    public String[] getAdminDeleteAliases() {
        return commands.getStringList("commands.admin.delete.aliases").toArray(new String[0]);
    }

    public String getAdminDeletePermission() {
        return commands.getString("commands.admin.delete.permission");
    }

    public String getAdminDeleteSyntax() {
        return commands.getString("commands.admin.delete.syntax");
    }

    public String getAdminDeleteDescription() {
        return commands.getString("commands.admin.delete.description");
    }

    public String[] getAdminDelHomeAliases() {
        return commands.getStringList("commands.admin.delhome.aliases").toArray(new String[0]);
    }

    public String getAdminDelHomePermission() {
        return commands.getString("commands.admin.delhome.permission");
    }

    public String getAdminDelHomeSyntax() {
        return commands.getString("commands.admin.delhome.syntax");
    }

    public String getAdminDelHomeDescription() {
        return commands.getString("commands.admin.delhome.description");
    }

    public String[] getAdminDelWarpAliases() {
        return commands.getStringList("commands.admin.delwarp.aliases").toArray(new String[0]);
    }

    public String getAdminDelWarpPermission() {
        return commands.getString("commands.admin.delwarp.permission");
    }

    public String getAdminDelWarpSyntax() {
        return commands.getString("commands.admin.delwarp.syntax");
    }

    public String getAdminDelWarpDescription() {
        return commands.getString("commands.admin.delwarp.description");
    }

    public String[] getAdminHelpAliases() {
        return commands.getStringList("commands.admin.help.aliases").toArray(new String[0]);
    }

    public String getAdminHelpPermission() {
        return commands.getString("commands.admin.help.permission");
    }

    public String getAdminHelpSyntax() {
        return commands.getString("commands.admin.help.syntax");
    }

    public String getAdminHelpDescription() {
        return commands.getString("commands.admin.help.description");
    }

    public String[] getAdminHomeAliases() {
        return commands.getStringList("commands.admin.home.aliases").toArray(new String[0]);
    }

    public String getAdminHomePermission() {
        return commands.getString("commands.admin.home.permission");
    }

    public String getAdminHomeSyntax() {
        return commands.getString("commands.admin.home.syntax");
    }

    public String getAdminHomeDescription() {
        return commands.getString("commands.admin.home.description");
    }

    public String[] getAdminLockAliases() {
        return commands.getStringList("commands.admin.lock.aliases").toArray(new String[0]);
    }

    public String getAdminLockPermission() {
        return commands.getString("commands.admin.lock.permission");
    }

    public String getAdminLockSyntax() {
        return commands.getString("commands.admin.lock.syntax");
    }

    public String getAdminLockDescription() {
        return commands.getString("commands.admin.lock.description");
    }

    public String[] getAdminPvpAliases() {
        return commands.getStringList("commands.admin.pvp.aliases").toArray(new String[0]);
    }

    public String getAdminPvpPermission() {
        return commands.getString("commands.admin.pvp.permission");
    }

    public String getAdminPvpSyntax() {
        return commands.getString("commands.admin.pvp.syntax");
    }

    public String getAdminPvpDescription() {
        return commands.getString("commands.admin.pvp.description");
    }

    public String[] getAdminReloadAliases() {
        return commands.getStringList("commands.admin.reload.aliases").toArray(new String[0]);
    }

    public String getAdminReloadPermission() {
        return commands.getString("commands.admin.reload.permission");
    }

    public String getAdminReloadSyntax() {
        return commands.getString("commands.admin.reload.syntax");
    }

    public String getAdminReloadDescription() {
        return commands.getString("commands.admin.reload.description");
    }

    public String[] getAdminRemoveMemberAliases() {
        return commands.getStringList("commands.admin.removemember.aliases").toArray(new String[0]);
    }

    public String getAdminRemoveMemberPermission() {
        return commands.getString("commands.admin.removemember.permission");
    }

    public String getAdminRemoveMemberSyntax() {
        return commands.getString("commands.admin.removemember.syntax");
    }

    public String getAdminRemoveMemberDescription() {
        return commands.getString("commands.admin.removemember.description");
    }

    public String[] getAdminSetHomeAliases() {
        return commands.getStringList("commands.admin.sethome.aliases").toArray(new String[0]);
    }

    public String getAdminSetHomePermission() {
        return commands.getString("commands.admin.sethome.permission");
    }

    public String getAdminSetHomeSyntax() {
        return commands.getString("commands.admin.sethome.syntax");
    }

    public String getAdminSetHomeDescription() {
        return commands.getString("commands.admin.sethome.description");
    }

    public String[] getAdminSetWarpAliases() {
        return commands.getStringList("commands.admin.setwarp.aliases").toArray(new String[0]);
    }

    public String getAdminSetWarpPermission() {
        return commands.getString("commands.admin.setwarp.permission");
    }

    public String getAdminSetWarpSyntax() {
        return commands.getString("commands.admin.setwarp.syntax");
    }

    public String getAdminSetWarpDescription() {
        return commands.getString("commands.admin.setwarp.description");
    }

    public String[] getAdminUnbanAliases() {
        return commands.getStringList("commands.admin.unban.aliases").toArray(new String[0]);
    }

    public String getAdminUnbanPermission() {
        return commands.getString("commands.admin.unban.permission");
    }

    public String getAdminUnbanSyntax() {
        return commands.getString("commands.admin.unban.syntax");
    }

    public String getAdminUnbanDescription() {
        return commands.getString("commands.admin.unban.description");
    }

    public String[] getAdminUncoopAliases() {
        return commands.getStringList("commands.admin.uncoop.aliases").toArray(new String[0]);
    }

    public String getAdminUncoopPermission() {
        return commands.getString("commands.admin.uncoop.permission");
    }

    public String getAdminUncoopSyntax() {
        return commands.getString("commands.admin.uncoop.syntax");
    }

    public String getAdminUncoopDescription() {
        return commands.getString("commands.admin.uncoop.description");
    }

    public String[] getAdminWarpAliases() {
        return commands.getStringList("commands.admin.warp.aliases").toArray(new String[0]);
    }

    public String getAdminWarpPermission() {
        return commands.getString("commands.admin.warp.permission");
    }

    public String getAdminWarpSyntax() {
        return commands.getString("commands.admin.warp.syntax");
    }

    public String getAdminWarpDescription() {
        return commands.getString("commands.admin.warp.description");
    }

    public String[] getAdminLobbyAliases() {
        return commands.getStringList("commands.admin.lobby.aliases").toArray(new String[0]);
    }

    public String getAdminLobbyPermission() {
        return commands.getString("commands.admin.lobby.permission");
    }

    public String getAdminLobbySyntax() {
        return commands.getString("commands.admin.lobby.syntax");
    }

    public String getAdminLobbyDescription() {
        return commands.getString("commands.admin.lobby.description");
    }

    public String[] getAdminBiomeAliases() {
        return commands.getStringList("commands.admin.biome.aliases").toArray(new String[0]);
    }

    public String getAdminBiomePermission() {
        return commands.getString("commands.admin.biome.permission");
    }

    public String getAdminBiomeSyntax() {
        return commands.getString("commands.admin.biome.syntax");
    }

    public String getAdminBiomeDescription() {
        return commands.getString("commands.admin.biome.description");
    }

    public String[] getAdminUpgradeAliases() {
        return commands.getStringList("commands.admin.upgrade.aliases").toArray(new String[0]);
    }

    public String getAdminUpgradePermission() {
        return commands.getString("commands.admin.upgrade.permission");
    }

    public String getAdminUpgradeSyntax() {
        return commands.getString("commands.admin.upgrade.syntax");
    }

    public String getAdminUpgradeDescription() {
        return commands.getString("commands.admin.upgrade.description");
    }

// =========================================================
// Messages Section
// =========================================================

    // General Messages
    public Component getPluginReloadedMessage() {
        return ColorUtils.colorize(messages.getString("messages.plugin-reloaded"));
    }

    public Component getOnlyPlayerCanRunCommandMessage() {
        return ColorUtils.colorize(messages.getString("messages.only-player-can-run-command"));
    }

    public Component getNoPermissionCommandMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.no-permission-command")));
    }

    public Component getIslandLevelMessage(int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.island-level")).replace("{level}", String.valueOf(level)));
    }

    public Component getIslandLockedMessage() {
        return ColorUtils.colorize(messages.getString("messages.island-locked"));
    }

    public Component getPlayerBannedMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-banned"));
    }

    public Component getIslandPvpDisabledMessage() {
        return ColorUtils.colorize(messages.getString("messages.island-pvp-disabled"));
    }

    public Component getIslandMemberExistsMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.island-member-exists")).replace("{player}", player));
    }

    public Component getIslandMemberNotExistsMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.not-island-member")).replace("{player}", player));
    }

    public Component getCannotEditIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.cannot-edit-island"));
    }

    public Component getHomeNameNotLegalMessage() {
        return ColorUtils.colorize(messages.getString("messages.home-name-not-legal"));
    }

    public Component getWarpNameNotLegalMessage() {
        return ColorUtils.colorize(messages.getString("messages.warp-name-not-legal"));
    }

    public Component getWarpSuccessMessage(String player, String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.warp-success")).replace("{warp}", warp).replace("{player}", player));
    }

    public Component getNoWarpMessage(String player, String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.no-warp")).replace("{player}", player).replace("{warp}", warp));
    }

    public Component getNoIslandMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.no-island")).replace("{player}", player));
    }

    public Component getAlreadyHasIslandMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.already-has-island")).replace("{player}", player));
    }

    public Component getWasAddedToIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-added-to-island")).replace("{owner}", owner));
    }

    public Component getWasRemovedFromIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-removed-from-island")).replace("{owner}", owner));
    }

    public Component getWasBannedFromIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-banned-from-island")).replace("{owner}", owner));
    }

    public Component getWasUnbannedFromIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-unbanned-from-island")).replace("{owner}", owner));
    }

    public Component getWasCoopedToIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-cooped-from-island")).replace("{owner}", owner));
    }

    public Component getWasUncoopedFromIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-uncooped-from-island")).replace("{owner}", owner));
    }

    public Component getNewMemberNotificationMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.new-member-notification")).replace("{player}", player));
    }

    public Component getCannotRemoveOwnerMessage() {
        return ColorUtils.colorize(messages.getString("messages.cannot-remove-owner"));
    }

    public Component getIslandGridFullMessage() {
        return ColorUtils.colorize(messages.getString("messages.island-grid-full"));
    }

    public Component getUnknownPlayerMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.unknown-player")).replace("{player}", player));
    }

    public Component getUnknownExceptionMessage() {
        return ColorUtils.colorize(messages.getString("messages.unknown-exception"));
    }

    // Admin Command Messages
    public Component getAdminCommandUsageMessage(String command, String syntax) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-usage-command")).replace("{command}", command).replace("{syntax}", syntax));
    }

    public Component getAdminUnknownSubCommandMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-unknown-sub-command")));
    }

    public Component getAdminNoIslandMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-no-island")).replace("{player}", player));
    }

    public Component getAdminAddMemberSuccessMessage(String target, String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-add-member-success")).replace("{target}", target).replace("{owner}", owner));
    }

    public Component getAdminRemoveMemberSuccessMessage(String target, String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-remove-member-success")).replace("{target}", target).replace("{owner}", owner));
    }

    public Component getAdminCreateSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-create-island-success")).replace("{player}", player));
    }

    public Component getAdminDeleteWarningMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-delete-warning")).replace("{player}", player));
    }

    public Component getAdminDeleteSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-delete-island-success")).replace("{player}", player));
    }

    public Component getAdminCannotDeleteDefaultHomeMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-cannot-delete-default-home")).replace("{player}", player));
    }

    public Component getAdminNoHomeMessage(String player, String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-no-home")).replace("{player}", player).replace("{home}", home));
    }

    public Component getAdminHomeSuccessMessage(String player, String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-home-success")).replace("{player}", player).replace("{home}", home));
    }

    public Component getAdminDelHomeSuccessMessage(String player, String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-delete-home-success")).replace("{player}", player).replace("{home}", home));
    }

    public Component getAdminDelWarpSuccessMessage(String player, String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-delete-warp-success")).replace("{player}", player).replace("{warp}", warp));
    }

    public Component getAdminLockSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-lock-success")).replace("{player}", player));
    }

    public Component getAdminUnLockSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-unlock-success")).replace("{player}", player));
    }

    public Component getAdminMustInIslandSetHomeMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-must-in-island-set-home")).replace("{player}", player));
    }

    public Component getAdminMustInIslandSetWarpMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-must-in-island-set-warp")).replace("{player}", player));
    }

    public Component getAdminSetHomeSuccessMessage(String player, String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-set-home-success")).replace("{player}", player).replace("{home}", home));
    }

    public Component getAdminSetWarpSuccessMessage(String player, String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-set-warp-success")).replace("{player}", player).replace("{warp}", warp));
    }

    public Component getAdminPvpEnableSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-pvp-enable-success")).replace("{player}", player));
    }

    public Component getAdminPvpDisableSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-pvp-disable-success")).replace("{player}", player));
    }

    public Component getAdminBanSuccessMessage(String owner, String target) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-ban-success")).replace("{owner}", owner).replace("{target}", target));
    }

    public Component getAdminUnbanSuccessMessage(String owner, String target) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-unban-success")).replace("{owner}", owner).replace("{target}", target));
    }

    public Component getAdminCoopSuccessMessage(String owner, String target) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-coop-success")).replace("{owner}", owner).replace("{target}", target));
    }

    public Component getAdminUncoopSuccessMessage(String owner, String target) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-uncoop-success")).replace("{owner}", owner).replace("{target}", target));
    }

    public Component getAdminLobbySuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-lobby-success")).replace("{player}", player));
    }

    public Component getAdminTeleportCancelledMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-teleport-cancelled")).replace("{player}", player));
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

    public Component getPlayerMustOnOwnIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-must-on-own-island"));
    }

    public Component getIslandNotHostedHereMessage(String server) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.island-not-hosted-here")).replace("{server}", server));
    }

    public Component getPlayerAlreadyHasIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-already-has-island"));
    }

    public Component getPlayerMustInIslandSetHomeMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-must-in-island-set-home"));
    }

    public Component getPlayerMustInIslandSetWarpMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-must-in-island-set-warp"));
    }

    public Component getPlayerInviteSentMessage(String target) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-invite-sent")).replace("{player}", target));
    }

    public Component getPlayerInviteReceiveMessage(String inviter) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-invite-receive")).replace("{player}", inviter));
    }

    public Component getPlayerInviteAcceptedNotifyMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-invite-accepted-notify")).replace("{player}", player));
    }

    public Component getPlayerInviteRejectedNotifyMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-invite-rejected-notify")).replace("{player}", player));
    }

    public Component getPlayerInviteAcceptedMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-invite-accepted"));
    }

    public Component getPlayerInviteRejectedMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-invite-rejected"));
    }

    public Component getPlayerAlreadyInvitedMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-already-invited")).replace("{player}", player));
    }

    public Component getPlayerNoPendingInviteMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-no-pending-invite"));
    }

    public Component getPlayerRemoveMemberSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-remove-member-success")).replace("{player}", player));
    }

    public Component getPlayerCannotRemoveSelfMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-cannot-remove-self"));
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

    public Component getPlayerNotIslandOwnerMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-not-island-owner"));
    }

    public Component getPlayerSetHomeSuccessMessage(String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-set-home-success")).replace("{home}", home));
    }

    public Component getPlayerSetWarpSuccessMessage(String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-set-warp-success")).replace("{warp}", warp));
    }

    public Component getPlayerDelHomeSuccessMessage(String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-delete-home-success")).replace("{home}", home));
    }

    public Component getPlayerCannotDeleteDefaultHomeMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-cannot-delete-default-home"));
    }

    public Component getPlayerDelWarpSuccessMessage(String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-delete-warp-success")).replace("{warp}", warp));
    }

    public Component getPlayerLockSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-lock-success"));
    }

    public Component getPlayerUnLockSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-unlock-success"));
    }

    public Component getPlayerPvpEnableSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-pvp-enable-success"));
    }

    public Component getPlayerPvpDisableSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-pvp-disable-success"));
    }

    public Component getPlayerNoHomeMessage(String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-no-home")).replace("{home}", home));
    }

    public Component getPlayerNoWarpMessage(String warp) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-no-warp")).replace("{warp}", warp));
    }

    public Component getPlayerHomeSuccessMessage(String home) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-home-success")).replace("{home}", home));
    }

    public Component getPlayerSetOwnerSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-set-owner-success")).replace("{player}", player));
    }

    public Component getWasSetOwnerOfIslandMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.was-set-owner-of-island")).replace("{owner}", owner));
    }

    public Component getPlayerAlreadyOwnerMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-already-owner")).replace("{player}", player));
    }

    public Component getPlayerCannotLeaveAsOwnerMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-cannot-leave-as-owner"));
    }

    public Component getPlayerLeaveSuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-leave-success"));
    }

    public Component getPlayerExpelSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-expel-success")).replace("{player}", player));
    }

    public Component getPlayerExpelNotInIslandMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-expel-not-in-island")).replace("{player}", player));
    }

    public Component getPlayerCannotExpelIslandPlayerMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-cannot-expel-island-player"));
    }

    public Component getPlayerBanSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-ban-success")).replace("{player}", player));
    }

    public Component getPlayerUnbanSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-unban-success")).replace("{player}", player));
    }

    public Component getPlayerAlreadyBannedMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-already-banned")).replace("{player}", player));
    }

    public Component getPlayerNotBannedMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-not-banned")).replace("{player}", player));
    }

    public Component getPlayerCannotBanIslandPlayerMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-cannot-ban-island-player"));
    }

    public Component getPlayerCoopSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-coop-success")).replace("{player}", player));
    }

    public Component getPlayerUncoopSuccessMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-uncoop-success")).replace("{player}", player));
    }

    public Component getPlayerAlreadyCoopedMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-already-cooped")).replace("{player}", player));
    }

    public Component getPlayerCoopBannedMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-coop-banned")).replace("{player}", player));
    }

    public Component getPlayerNotCoopedMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-not-cooped")).replace("{player}", player));
    }

    public Component getPlayerCannotCoopIslandPlayerMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-cannot-coop-island-player"));
    }

    public Component getPlayerInfoNotInIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-info-not-in-island"));
    }

    public Component getPlayerBlockValueCommandMessage(String block, int value) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.block-value")).replace("{block}", block).replace("{value}", String.valueOf(value)));
    }

    public Component getPlayerNoItemInHandMessage() {
        return ColorUtils.colorize(messages.getString("messages.no-item-in-hand"));
    }

    public Component getBlockLimitReachedMessage(String block, int limit) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.block-limit-reached")).replace("{block}", block).replace("{limit}", String.valueOf(limit)));
    }

    public Component getBlockLimitCalculatingMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.block-limit-calculating")));
    }

    public Component getPlayerNotOnlineMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-not-online")).replace("{player}", player));
    }

    public Component getPlayerLobbySuccessMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-lobby-success"));
    }

    // Info
    public Component getIslandInfoHeaderMessage() {
        return ColorUtils.colorize(messages.getString("messages.island-info-header"));
    }

    public Component getIslandInfoUUIDMessage(UUID islandUuid) {
        return ColorUtils.colorize(Objects.requireNonNull(Objects.requireNonNull(messages.getString("messages.island-info-uuid")).replace("{island_uuid}", islandUuid.toString())));
    }

    public Component getIslandInfoLevelMessage(int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.island-info-level")).replace("{level}", String.valueOf(level)));
    }

    public Component getIslandInfoOwnerMessage(String owner) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.island-info-owner")).replace("{owner}", owner));
    }

    public Component getIslandInfoMembersMessage(String members) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.island-info-members")).replace("{members}", members));
    }

    public Component getIslandInfoNoMembersMessage() {
        return ColorUtils.colorize(messages.getString("messages.island-info-no-members"));
    }

    // Top
    public Component getTopIslandsHeaderMessage() {
        return ColorUtils.colorize(messages.getString("messages.top-islands-header"));
    }

    public Component getTopIslandMessage(int rank, String owner, String members, int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.top-islands-message")).replace("{rank}", String.valueOf(rank)).replace("{owner}", owner).replace("{members}", members).replace("{level}", String.valueOf(level)));
    }

    public Component getNoIslandsFoundMessage() {
        return ColorUtils.colorize(messages.getString("messages.top-islands-no-island"));
    }

    // Ban
    public Component getBannedPlayersHeaderMessage() {
        return ColorUtils.colorize(messages.getString("messages.banned-players-header"));
    }

    public Component getBannedPlayerMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.banned-player-message")).replace("{player}", player));
    }

    public Component getNoBannedPlayersMessage() {
        return ColorUtils.colorize(messages.getString("messages.banned-player-no-banned"));
    }

    // Coop
    public Component getCoopedPlayersHeaderMessage() {
        return ColorUtils.colorize(messages.getString("messages.cooped-players-header"));
    }

    public Component getCoopedPlayerMessage(String player) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.cooped-player-message")).replace("{player}", player));
    }

    public Component getNoCoopedPlayersMessage() {
        return ColorUtils.colorize(messages.getString("messages.cooped-player-no-cooped"));
    }

    // Biome

    public Component getPlayerBiomeInvalidMessage(String biome) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-biome-invalid")).replace("{biome}", biome));
    }

    public Component getPlayerBiomeNotUnlockedMessage(String biome) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-biome-not-unlocked")).replace("{biome}", biome));
    }

    public Component getPlayerBiomeAllowedListMessage(String biomes) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-biome-allowed-list")).replace("{biomes}", biomes));
    }

    public Component getPlayerBiomeChangeSuccessMessage(String biome) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-biome-change-success")).replace("{biome}", biome));
    }

    public Component getPlayerBiomeMustInOwnIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.player-biome-must-in-own-island"));
    }

    public Component getAdminBiomeMustInIslandMessage() {
        return ColorUtils.colorize(messages.getString("messages.admin-biome-must-in-island"));
    }

    public Component getAdminBiomeChangeSuccessMessage(String biome) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-biome-change-success")).replace("{biome}", biome));
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

    public Component getAdminHelpHeader() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-help-header")));
    }

    public Component getAdminHelpEntry(String command, String syntax, String description) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-help-entry")).replace("{command}", command).replace("{syntax}", syntax).replace("{description}", description));
    }

    public Component getAdminHelpFooter(int page, int total) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-help-footer")).replace("{prev}", String.valueOf(page - 1)).replace("{next}", String.valueOf(page + 1)).replace("{page}", String.valueOf(page)).replace("{total}", String.valueOf(total)));
    }

    // Limits
    public Component getPlayerHomeLimitReachedMessage(String limit) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-home-limit-reached")).replace("{limit}", limit));
    }

    public Component getPlayerWarpLimitReachedMessage(String limit) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-warp-limit-reached")).replace("{limit}", limit));
    }

    public Component getPlayerTeamLimitReachedMessage(String limit) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-team-limit-reached")).replace("{limit}", limit));
    }

    public Component getPlayerCoopLimitReachedMessage(String limit) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-coop-limit-reached")).replace("{limit}", limit));
    }

    // Upgrades

    /**
     * Raw (uncolorized) so it can stand in for a value inside another message.
     */
    public String getUpgradeUnknownValue() {
        return Objects.requireNonNull(messages.getString("messages.upgrade-unknown-value"));
    }

    public Component getPlayerUpgradeInvalidIdMessage(String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-invalid-id")).replace("{upgrade}", upgrade));
    }

    public Component getPlayerUpgradeDetailsHeaderMessage(String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-header")).replace("{upgrade}", upgrade));
    }

    public Component getPlayerUpgradeDetailsCurrentLevelMessage(int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-current-level")).replace("{level}", String.valueOf(level)));
    }

    public Component getPlayerUpgradeDetailsCurrentValueMessage(String value) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-current-value")).replace("{value}", value));
    }

    public Component getPlayerUpgradeDetailsNextLevelMessage(String level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-next-level")).replace("{level}", level));
    }

    public Component getPlayerUpgradeDetailsNextValueMessage(String value) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-next-value")).replace("{value}", value));
    }

    public Component getPlayerUpgradeDetailsRequireIslandLevelMessage(String level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-require-island-level")).replace("{level}", level));
    }

    public Component getPlayerUpgradeDetailsYourIslandLevelMessage(int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-your-island-level")).replace("{level}", String.valueOf(level)));
    }

    public Component getPlayerUpgradeDetailsPriceMessage(String price) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-price")).replace("{price}", price));
    }

    public Component getPlayerUpgradeDetailsYourBalanceMessage(String balance) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-your-balance")).replace("{balance}", balance));
    }

    public Component getPlayerUpgradeDetailsStatusLockedMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-status-locked")));
    }

    public Component getPlayerUpgradeDetailsStatusAvailableMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-details-status-available")));
    }

    public Component getPlayerUpgradeBuySuccessMessage(String upgrade, int oldLevel, int newLevel) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-buy-success")).replace("{upgrade}", upgrade).replace("{old_level}", String.valueOf(oldLevel)).replace("{new_level}", String.valueOf(newLevel)));
    }

    public Component getPlayerUpgradeMaxedMessage(String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-maxed")).replace("{upgrade}", upgrade));
    }

    public Component getPlayerUpgradeIslandLevelTooLowMessage(String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-island-level-too-low")).replace("{upgrade}", upgrade));
    }

    public Component getPlayerUpgradeNotEnoughMoneyMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-not-enough-money")));
    }

    public Component getPlayerUpgradeLevelChangedMessage(String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.player-upgrade-level-changed")).replace("{upgrade}", upgrade));
    }

    public Component getAdminUpgradeDetailsHeaderMessage(String player, String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-upgrade-details-header")).replace("{player}", player).replace("{upgrade}", upgrade));
    }

    public Component getAdminUpgradeDetailsCurrentLevelMessage(String upgrade, int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-upgrade-details-current-level")).replace("{upgrade}", upgrade).replace("{level}", String.valueOf(level)));
    }

    public Component getAdminUpgradeDetailsCurrentValueMessage(String value) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-upgrade-details-current-value")).replace("{value}", value));
    }

    public Component getAdminUpgradeInvalidLevelMessage() {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-upgrade-invalid-level")));
    }

    public Component getAdminUpgradeSetSuccessMessage(String upgrade, int level) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-upgrade-set-success")).replace("{upgrade}", upgrade).replace("{level}", String.valueOf(level)));
    }

    public Component getAdminUpgradeLevelChangedMessage(String upgrade) {
        return ColorUtils.colorize(Objects.requireNonNull(messages.getString("messages.admin-upgrade-level-changed")).replace("{upgrade}", upgrade));
    }
}
