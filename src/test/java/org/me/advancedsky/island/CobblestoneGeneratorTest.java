package org.me.advancedsky.island;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockFormEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.listener.CobblestoneGeneratorListener;
import org.me.advancedsky.model.Island;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class CobblestoneGeneratorTest {
    private static final String ISLAND_WORLD = "islands";
    private static final int SLOT = 5;
    private static final IslandGrid GRID = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);

    private static ConfigHandler defaultUpgrades() throws Exception {
        ConfigHandler config = mock(ConfigHandler.class, CALLS_REAL_METHODS);
        try (var stream = CobblestoneGeneratorTest.class.getResourceAsStream("/upgrades.yml")) {
            assertNotNull(stream);
            Field field = ConfigHandler.class.getDeclaredField("upgrades");
            field.setAccessible(true);
            field.set(config, YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        // What reload() builds from the upgrades just set.
        for (String[] cache : new String[][]{{"upgradeLevels", "loadUpgradeLevels"}, {"upgradeLimits", "loadUpgradeLimits"}, {"upgradeValues", "loadUpgradeValues"}}) {
            Method load = ConfigHandler.class.getDeclaredMethod(cache[1]);
            load.setAccessible(true);
            Field field = ConfigHandler.class.getDeclaredField(cache[0]);
            field.setAccessible(true);
            field.set(config, load.invoke(config));
        }
        return config;
    }

    @Test
    void defaultConfigExposesTheRates() throws Exception {
        ConfigHandler config = defaultUpgrades();
        assertTrue(config.isUpgrade("generator-rates"));
        assertEquals(List.of(1, 2, 3, 4, 5), config.getUpgradeLevels("generator-rates"));
        assertEquals(Map.of("COBBLESTONE", 100.0), config.getUpgradeGeneratorRates(1));
        Map<String, Double> level3 = config.getUpgradeGeneratorRates(3);
        assertEquals(List.of("COBBLESTONE", "IRON_ORE", "DIAMOND_ORE"), List.copyOf(level3.keySet()));
        assertEquals(0.5, level3.get("DIAMOND_ORE"));
        assertEquals(250, config.getUpgradeRequireLevel("generator-rates", 2));
        assertEquals(80000.0, config.getUpgradePrice("generator-rates", 2));
        assertEquals("COBBLESTONE 91%, IRON_ORE 8.5%, DIAMOND_ORE 0.5%", config.getUpgradeValue("generator-rates", 3));
        assertEquals("COBBLESTONE 100%", config.getUpgradeValue("generator-rates", 1));
    }

    @Test
    void rollsFollowTheConfiguredWeights() throws Exception {
        CobblestoneGeneratorHandler generator = new CobblestoneGeneratorHandler(plugin, defaultUpgrades(), material -> true);
        for (double roll : new double[]{0.0, 0.5, 0.999}) {
            assertEquals(Material.COBBLESTONE, generator.roll(1, roll));
        }
        assertEquals(Material.COBBLESTONE, generator.roll(2, 0.9499));
        assertEquals(Material.IRON_ORE, generator.roll(2, 0.95));
        assertEquals(Material.IRON_ORE, generator.roll(2, 0.999));
        assertEquals(Material.COBBLESTONE, generator.roll(3, 0.9099));
        assertEquals(Material.IRON_ORE, generator.roll(3, 0.91));
        assertEquals(Material.IRON_ORE, generator.roll(3, 0.9949));
        assertEquals(Material.DIAMOND_ORE, generator.roll(3, 0.995));
        assertNotNull(generator.roll(5));
    }

    @Test
    void unknownLevelAndBadConfigFailLoudly() throws Exception {
        CobblestoneGeneratorHandler generator = new CobblestoneGeneratorHandler(plugin, defaultUpgrades(), material -> true);
        assertThrows(IllegalStateException.class, () -> generator.roll(6, 0.5));

        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getUpgradeLevels("generator-rates")).thenReturn(List.of(1));
        // An item cannot be formed: DIAMOND stands for one here, where no server tells them apart.
        for (Map<String, Double> rates : List.of(Map.of("NOT_A_MATERIAL", 1.0), Map.of("COBBLESTONE", 0.0), Map.<String, Double>of(), Map.of("DIAMOND", 1.0))) {
            when(config.getUpgradeGeneratorRates(1)).thenReturn(rates);
            assertThrows(IllegalStateException.class, () -> new CobblestoneGeneratorHandler(plugin, config, material -> material != Material.DIAMOND), rates.toString());
        }
    }

    @Test
    void formedCobblestoneBecomesTheRolledBlockOnlyOnIslands() {
        UUID islandUuid = UUID.randomUUID();
        Island island = new Island(islandUuid, SLOT, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(), Map.of(), null, 1, 3);
        // The cache holds only this island: every other slot, and the gap, has none.
        IslandCache cache = mock(IslandCache.class);
        when(cache.getAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return GRID.slotAt(location.getX(), location.getZ()) == SLOT ? island : null;
        });
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getIslandWorldName()).thenReturn(ISLAND_WORLD);
        CobblestoneGeneratorHandler generator = mock(CobblestoneGeneratorHandler.class);
        when(generator.roll(3)).thenReturn(Material.IRON_ORE);
        LimitHandler limits = mock(LimitHandler.class);
        when(limits.hasRoom(islandUuid, Material.IRON_ORE)).thenReturn(true);
        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(config, cache, generator, limits);
        Location onIsland = GRID.center(null, SLOT);
        Location otherIsland = GRID.center(null, SLOT + 1);

        verify(formEvent(listener, ISLAND_WORLD, onIsland, Material.COBBLESTONE)).setType(Material.IRON_ORE);
        verify(formEvent(listener, "world", onIsland, Material.COBBLESTONE), never()).setType(any());
        verify(formEvent(listener, ISLAND_WORLD, onIsland, Material.OBSIDIAN), never()).setType(any());
        verify(formEvent(listener, ISLAND_WORLD, otherIsland, Material.COBBLESTONE), never()).setType(any());

        when(generator.roll(3)).thenReturn(Material.COBBLESTONE);
        verify(formEvent(listener, ISLAND_WORLD, onIsland, Material.COBBLESTONE), never()).setType(any());

        // A capped block the island has no room for stays cobblestone.
        when(generator.roll(3)).thenReturn(Material.DIAMOND_ORE);
        verify(formEvent(listener, ISLAND_WORLD, onIsland, Material.COBBLESTONE), never()).setType(any());
    }

    @SuppressWarnings("UnstableApiUsage")
    private static BlockState formEvent(CobblestoneGeneratorListener listener, String worldName, Location at, Material type) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, at.getX(), at.getY(), at.getZ()));
        BlockState state = mock(BlockState.class);
        when(state.getType()).thenReturn(type);
        listener.onBlockForm(new BlockFormEvent(block, state));
        return state;
    }
}
