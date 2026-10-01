package org.me.advancedsky.island;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.BiomeNotUnlockedException;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.mockito.MockedStatic;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiomeUpgradeTest {
    private static final int SLOT = 3;
    private final UUID island = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final World world = mock(World.class);
    private final IslandGrid grid = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final BiomeHandler handler = new BiomeHandler(plugin, config, database, grid);
    // The middle of the island's area.
    private final Location location = grid.center(world, SLOT);

    BiomeUpgradeTest() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(world.getName()).thenReturn("islands");
        when(config.getServerName()).thenReturn("local");
        when(config.getIslandWorldName()).thenReturn("islands");
        when(database.getIslandUuid(player)).thenReturn(Optional.of(island));
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(SLOT));
        when(database.getIslandUpgradeLevel(island, "biomes")).thenReturn(1);
        when(config.getUpgradeAllowedBiomes(1)).thenReturn(List.of("plains"));
    }

    @Test
    void defaultConfigExposesTheBiomeLevels() throws Exception {
        ConfigHandler realConfig = mock(ConfigHandler.class, CALLS_REAL_METHODS);
        try (var stream = getClass().getResourceAsStream("/upgrades.yml")) {
            assertNotNull(stream);
            Field field = ConfigHandler.class.getDeclaredField("upgrades");
            field.setAccessible(true);
            field.set(realConfig, YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        // What reload() builds from the upgrades just set.
        for (String[] cache : new String[][]{{"upgradeLevels", "loadUpgradeLevels"}, {"upgradeLimits", "loadUpgradeLimits"}, {"upgradeValues", "loadUpgradeValues"}}) {
            Method load = ConfigHandler.class.getDeclaredMethod(cache[1]);
            load.setAccessible(true);
            Field field = ConfigHandler.class.getDeclaredField(cache[0]);
            field.setAccessible(true);
            field.set(realConfig, load.invoke(realConfig));
        }
        assertTrue(realConfig.isUpgrade("biomes"));
        assertEquals(List.of(1, 2, 3, 4, 5), realConfig.getUpgradeLevels("biomes"));
        assertEquals(List.of("plains"), realConfig.getUpgradeAllowedBiomes(1));
        assertEquals(List.of("plains", "forest", "birch_forest", "cherry_grove"), realConfig.getUpgradeAllowedBiomes(2));
        assertEquals(16, realConfig.getUpgradeAllowedBiomes(5).size());
        assertEquals(125, realConfig.getUpgradeRequireLevel("biomes", 2));
        assertEquals(30000.0, realConfig.getUpgradePrice("biomes", 2));
        assertEquals("plains, forest, birch_forest, cherry_grove", realConfig.getUpgradeValue("biomes", 2));
        assertEquals("1", realConfig.getUpgradeValue("coop-limit", 1));
    }

    @Test
    void lockedBiomeIsRefusedWithTheAllowedList() {
        CompletionException error = assertThrows(CompletionException.class, () -> handler.applyPlayerChunkBiome(player, location, "forest").join());
        assertInstanceOf(BiomeNotUnlockedException.class, error.getCause());
        assertEquals("plains", error.getCause().getMessage());
    }

    @Test
    void unlockedBiomePassesTheGateInEitherSpelling() {
        // Off-server there is no biome registry: reaching for it is the step after the gate.
        IllegalStateException lookup = new IllegalStateException("biome registry reached");
        RegistryAccess access = mock(RegistryAccess.class);
        when(access.getRegistry(RegistryKey.BIOME)).thenThrow(lookup);
        try (MockedStatic<RegistryAccess> registryAccess = mockStatic(RegistryAccess.class)) {
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            for (String spelling : new String[]{"plains", "minecraft:plains", "PLAINS"}) {
                // The gate passed: the next step, the biome lookup, is what fails here.
                CompletionException error = assertThrows(CompletionException.class, () -> handler.applyPlayerChunkBiome(player, location, spelling).join());
                assertSame(lookup, error.getCause(), spelling);
            }
        }
    }
}
