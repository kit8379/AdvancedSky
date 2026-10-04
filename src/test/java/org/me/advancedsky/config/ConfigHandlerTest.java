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
        write("upgrades.yml", bundled("upgrades.yml").replaceAll("(?m)^ {4}'5':\\r?\\n {6}require-level: 1400\\r?\\n {6}price: 1000000\\r?\\n {6}size: 160\\r?\\n", ""));

        ConfigHandler config = new ConfigHandler(plugin());

        assertFalse(config.getBlockLimits().containsKey("HOPPER"));
        assertEquals(List.of(1, 2, 3, 4), config.getUpgradeLevels("island-size"));
        assertFalse(read("limits.yml").contains("HOPPER"));
        // Upgrades themselves are not the operator's list: the code needs every one of them.
        assertEquals(List.of(1, 2, 3, 4, 5), config.getUpgradeLevels("coop-limit"));
    }

    @Test
    void aMissingRequiredSettingFailsWithoutCompletingOrRewritingTheFile() throws IOException {
        String incomplete = bundled("config.yml").replaceAll("(?m)^debug: false\\r?\\n", "") + "# my own note\n";
        write("config.yml", incomplete);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ConfigHandler(plugin()));
        assertTrue(error.getMessage().contains("missing required setting 'debug'"));
        assertEquals(incomplete, read("config.yml"));
    }

    @Test
    void readingAndReloadingACompleteConfigKeepsItsCommentsAndFormattingExactly() throws IOException {
        String original = bundled("config.yml") + "# my own note\n";
        write("config.yml", original);
        ConfigHandler config = new ConfigHandler(plugin());
        assertEquals(original, read("config.yml"));
        config.reload();
        assertEquals(original, read("config.yml"));
    }

    @Test
    void upgradeLevelsAndValuesFollowAReload() throws IOException {
        ConfigHandler config = new ConfigHandler(plugin());
        assertEquals(List.of(1, 2, 3, 4, 5), config.getUpgradeLevels("island-size"));
        assertEquals("160", config.getUpgradeValue("island-size", 5));
        assertTrue(config.isUpgrade("island-size"));
        assertFalse(config.isUpgrade("flight"));

        write("upgrades.yml", read("upgrades.yml").replaceAll("(?m)^ {4}'5':\\r?\\n {6}require-level: 1400\\r?\\n {6}price: 1000000\\r?\\n {6}size: 160\\r?\\n", ""));
        config.reload();

        assertEquals(List.of(1, 2, 3, 4), config.getUpgradeLevels("island-size"));
        assertThrows(IllegalArgumentException.class, () -> config.getUpgradeValue("island-size", 5));
        assertThrows(IllegalArgumentException.class, () -> config.getUpgradeLimit("island-size", 5));
        assertEquals("144", config.getUpgradeValue("island-size", 4));
    }

    @Test
    void anUnknownSettingIsRejectedInsteadOfRemovedOrInterpreted() throws IOException {
        String invalid = bundled("config.yml") + "unknown-setting: true\n";
        write("config.yml", invalid);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ConfigHandler(plugin()));
        assertTrue(error.getMessage().contains("unknown setting 'unknown-setting'"));
        assertEquals(invalid, read("config.yml"));
    }

    @Test
    void aReloadLeavesTheStartupSettingsAndTheCommandsAsTheyWere() throws IOException {
        ConfigHandler config = new ConfigHandler(plugin());

        write("config.yml", read("config.yml").replace("name: \"sky1\"", "name: \"sky2\"").replace("lobby-only: false", "lobby-only: true").replace("debug: false", "debug: true"));
        write("commands.yml", read("commands.yml").replaceFirst("(?m)^( {4}invite:\\r?\\n {6}aliases: )\\[\\]", "$1[add]"));
        config.reload();
        assertTrue(read("config.yml").contains("name: \"sky2\""));
        assertTrue(read("commands.yml").contains("aliases: [add]"));

        assertEquals("sky1", config.getServerName());
        assertEquals("sky1", config.getIslandWorldName());
        assertFalse(config.isLobbyOnly());
        assertArrayEquals(new String[0], config.getPlayerInviteAliases());
        assertTrue(config.isDebug());
    }

    @Test
    void theLobbyRoleIsReadFromItsRequiredFlag() throws IOException {
        write("config.yml", bundled("config.yml").replace("name: \"sky1\"", "name: \"lobby\"").replace("lobby-only: false", "lobby-only: true"));
        ConfigHandler config = new ConfigHandler(plugin());
        assertTrue(config.isLobbyOnly());
        assertEquals("lobby", config.getServerName());
    }

    @Test
    void theIslandWorldFollowsItsServer() throws IOException {
        write("config.yml", bundled("config.yml").replace("name: \"sky1\"", "name: \"sky2\""));
        ConfigHandler config = new ConfigHandler(plugin());
        assertFalse(config.isLobbyOnly());
        assertEquals("sky2", config.getIslandWorldName());
    }

    @Test
    void rejectsAServerNameThatCouldResolveOutsideTheWorldFolder() throws IOException {
        write("config.yml", bundled("config.yml").replace("name: \"sky1\"", "name: \"../sky1\""));
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
