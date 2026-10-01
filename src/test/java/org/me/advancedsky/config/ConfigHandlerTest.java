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
