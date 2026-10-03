package org.me.advancedsky.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.me.advancedsky.AdvancedSky;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConfigHandlerTest {

    @TempDir
    Path dataFolder;

    @Test
    void anEntryTakenOutOfAnOperatorsListStaysOut() throws IOException {
        write("limits.yml", bundled("limits.yml").replaceAll("(?m)^ {4}HOPPER: 50\\r?\\n", ""));
        write("upgrades.yml", bundled("upgrades.yml").replaceAll("(?m)^ {4}'5':\\r?\\n {6}require-level: 1400\\r?\\n {6}price: 1000000\\r?\\n {6}size: 175\\r?\\n", ""));

        ConfigHandler config = new ConfigHandler(plugin());

        assertFalse(config.getBlockLimits().containsKey("HOPPER"));
        assertEquals(List.of(1, 2, 3, 4), config.getUpgradeLevels("island-size"));
        assertFalse(read("limits.yml").contains("HOPPER"));
        // Upgrades themselves are not the operator's list: the code needs every one of them.
        assertEquals(List.of(1, 2, 3, 4, 5), config.getUpgradeLevels("coop-limit"));
    }

    @Test
    void aMissingSettingIsAddedAndCommentsAreKept() throws IOException {
        write("config.yml", bundled("config.yml").replaceAll("(?m)^debug: false\\r?\\n", "") + "# my own note\n");

        ConfigHandler config = new ConfigHandler(plugin());

        assertFalse(config.isDebug());
        String saved = read("config.yml");
        assertTrue(saved.contains("debug: false"));
        assertTrue(saved.contains("# Must match the server name in your BungeeCord / Velocity config."));
        assertTrue(saved.contains("# AdvancedSky Configuration"));
        assertTrue(read("limits.yml").contains("# that block or entity outright; anything not listed is unlimited."));

        // Each reload rewrites the files; one already complete comes back unchanged.
        config.reload();
        assertEquals(saved, read("config.yml"));
    }

    @Test
    void upgradeLevelsAndValuesFollowAReload() throws IOException {
        ConfigHandler config = new ConfigHandler(plugin());
        assertEquals(List.of(1, 2, 3, 4, 5), config.getUpgradeLevels("island-size"));
        assertEquals("175", config.getUpgradeValue("island-size", 5));
        assertTrue(config.isUpgrade("island-size"));
        assertFalse(config.isUpgrade("flight"));

        write("upgrades.yml", read("upgrades.yml").replaceAll("(?m)^ {4}'5':\\r?\\n {6}require-level: 1400\\r?\\n {6}price: 1000000\\r?\\n {6}size: 175\\r?\\n", ""));
        config.reload();

        assertEquals(List.of(1, 2, 3, 4), config.getUpgradeLevels("island-size"));
        // A level the config no longer has reads as no limit, as before.
        assertEquals("0", config.getUpgradeValue("island-size", 5));
        assertEquals("150", config.getUpgradeValue("island-size", 4));
    }

    @Test
    void replacesTheTemplateWorldSettingsWithASchematicPath() throws IOException {
        write("config.yml", bundled("config.yml").replace("schematic: \"schematics/starter.schem\"",
                "world: skyblock_template\n    anchor: {x: 0, y: 100, z: 0}\n    min: {x: -32, y: 64, z: -32}\n    max: {x: 32, y: 160, z: 32}"));
        ConfigHandler config = new ConfigHandler(plugin());
        assertEquals("schematics/starter.schem", config.getTemplateSchematic());
        String saved = read("config.yml");
        assertFalse(saved.contains("skyblock_template"));
        assertFalse(saved.contains("anchor:"));
        assertFalse(saved.contains("min:"));
        assertFalse(saved.contains("max:"));
    }

    @Test
    void aReloadLeavesTheStartupSettingsAndTheCommandsAsTheyWere() throws IOException {
        ConfigHandler config = new ConfigHandler(plugin());

        write("config.yml", read("config.yml").replace("name: sky1", "name: sky2").replace("lobby-only: false", "lobby-only: true").replace("debug: false", "debug: true"));
        write("commands.yml", read("commands.yml").replaceFirst("(?m)^( {4}invite:\\r?\\n {6}aliases: )\\[\\]", "$1[add]"));
        config.reload();
        assertTrue(read("config.yml").contains("name: sky2"));
        assertTrue(read("commands.yml").contains("aliases: [add]"));

        assertEquals("sky1", config.getServerName());
        assertEquals("sky1", config.getIslandWorldName());
        assertFalse(config.isLobbyOnly());
        assertArrayEquals(new String[0], config.getPlayerInviteAliases());
        assertTrue(config.isDebug());
    }

    @Test
    void migratesTheOldLobbyListAndRemovesTheSeparateIslandWorld() throws IOException {
        write("config.yml", "server: {name: lobby}\nservers: {island: [sky1, sky2], lobby: [lobby]}\nisland: {world: skyblock}\n");
        ConfigHandler config = new ConfigHandler(plugin());
        assertTrue(config.isLobbyOnly());
        assertEquals("lobby", config.getServerName());
        String saved = read("config.yml");
        assertTrue(saved.contains("lobby-only: true"));
        assertFalse(saved.contains("servers:"));
        assertFalse(saved.contains("skyblock"));
    }

    @Test
    void anExplicitRoleOverridesTheOldListsAndTheIslandWorldFollowsItsServer() throws IOException {
        write("config.yml", "server: {name: sky2}\nlobby-only: false\nservers: {lobby: [sky2]}\nisland: {world: skyblock}\n");
        ConfigHandler config = new ConfigHandler(plugin());
        assertFalse(config.isLobbyOnly());
        assertEquals("sky2", config.getIslandWorldName());
    }

    @Test
    void rejectsAServerNameThatCouldResolveOutsideTheWorldFolder() throws IOException {
        write("config.yml", "server: {name: '../sky1'}\n");
        assertThrows(IllegalStateException.class, () -> new ConfigHandler(plugin()));
    }

    private AdvancedSky plugin() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getResource(anyString())).thenAnswer(call -> resource(call.getArgument(0)));
        doAnswer(call -> {
            String name = call.getArgument(0);
            write(name, bundled(name));
            return null;
        }).when(plugin).saveResource(anyString(), anyBoolean());
        return plugin;
    }

    private static InputStream resource(String name) {
        return ConfigHandlerTest.class.getClassLoader().getResourceAsStream(name);
    }

    private static String bundled(String name) throws IOException {
        try (InputStream in = resource(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dataFolder.resolve(name), content, StandardCharsets.UTF_8);
    }

    private String read(String name) throws IOException {
        return Files.readString(dataFolder.resolve(name), StandardCharsets.UTF_8);
    }
}
