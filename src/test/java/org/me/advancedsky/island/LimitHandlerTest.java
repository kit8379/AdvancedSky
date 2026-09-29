package org.me.advancedsky.island;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.listener.IslandLimitListener;
import org.me.advancedsky.model.Island;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class LimitHandlerTest {
    private static final String ISLAND_WORLD = "islands";
    private static final int SLOT = 2;
    private static final int SIZE = 75;
    private final IslandGrid grid = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final UUID island = UUID.randomUUID();
    private final Island cachedIsland = new Island(island, SLOT, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(), Map.of(), null, 1, 1);
    private final Location center = grid.center(null, SLOT);

    private ConfigHandler defaultLimits() throws Exception {
        ConfigHandler config = mock(ConfigHandler.class, CALLS_REAL_METHODS);
        try (var stream = LimitHandlerTest.class.getResourceAsStream("/limits.yml")) {
            assertNotNull(stream);
            Field field = ConfigHandler.class.getDeclaredField("limits");
            field.setAccessible(true);
            field.set(config, YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        doReturn(SIZE).when(config).getUpgradeLimit("island-size", 1);
        return config;
    }

    @Test
    void defaultConfigExposesTheLimits() throws Exception {
        ConfigHandler config = defaultLimits();
        assertEquals(Map.of("HOPPER", 50, "DISPENSER", 100, "DROPPER", 100, "OBSERVER", 100, "PISTON", 500), config.getBlockLimits());
        assertEquals(List.of("HOPPER", "DISPENSER", "DROPPER", "OBSERVER", "PISTON"), List.copyOf(config.getBlockLimits().keySet()));
        assertEquals(Map.of("MINECART", 20, "ITEM_FRAME", 100, "ARMOR_STAND", 50), config.getEntityLimits());
    }

    @Test
    void unknownNamesFailStartup() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getBlockLimits()).thenReturn(Map.of("NOT_A_MATERIAL", 1));
        when(config.getEntityLimits()).thenReturn(Map.of());
        assertThrows(IllegalStateException.class, () -> new LimitHandler(plugin, config, grid));

        when(config.getBlockLimits()).thenReturn(Map.of());
        when(config.getEntityLimits()).thenReturn(Map.of("BOAT", 1));
        assertThrows(IllegalStateException.class, () -> new LimitHandler(plugin, config, grid));

        when(config.getBlockLimits()).thenReturn(Map.of("HOPPER", -1));
        when(config.getEntityLimits()).thenReturn(Map.of());
        assertThrows(IllegalStateException.class, () -> new LimitHandler(plugin, config, grid));

        when(config.getBlockLimits()).thenReturn(Map.of());
        when(config.getEntityLimits()).thenReturn(Map.of("MINECART", -1));
        assertThrows(IllegalStateException.class, () -> new LimitHandler(plugin, config, grid));
    }

    @Test
    void zeroForbidsOutrightAndUnlistedIsUnlimited() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getBlockLimits()).thenReturn(Map.of("HOPPER", 0));
        when(config.getEntityLimits()).thenReturn(Map.of("MINECART", 0));
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(SIZE);
        LimitHandler limits = new LimitHandler(plugin, config, grid);

        assertEquals(0, limits.getBlockLimit(Material.HOPPER));
        assertEquals(LimitHandler.UNLIMITED, limits.getBlockLimit(Material.DISPENSER));
        counted(limits, new int[Material.values().length]);
        assertFalse(place(limits, Material.HOPPER, 0, 0));
        assertTrue(place(limits, Material.DISPENSER, 0, 0));

        World world = mock(World.class);
        assertEquals(0, limits.getEntityLimit(EntityType.MINECART));
        assertFalse(limits.canSpawnEntity(cachedIsland, world, EntityType.MINECART));
        assertTrue(limits.canSpawnEntity(cachedIsland, world, EntityType.ARMOR_STAND));
    }

    @Test
    void blocksAreDeniedUntilTheFirstScanAndAtTheCap() throws Exception {
        LimitHandler limits = new LimitHandler(plugin, defaultLimits(), grid);
        assertEquals(50, limits.getBlockLimit(Material.HOPPER));
        assertEquals(LimitHandler.UNLIMITED, limits.getBlockLimit(Material.STONE));

        assertFalse(limits.isReady(island));
        assertTrue(place(limits, Material.STONE, 0, 0));
        assertFalse(place(limits, Material.HOPPER, 0, 0));

        int[] counts = new int[Material.values().length];
        counts[Material.HOPPER.ordinal()] = 49;
        counted(limits, counts);
        assertTrue(limits.isReady(island));
        assertTrue(place(limits, Material.HOPPER, 0, 0));
        assertFalse(place(limits, Material.HOPPER, 0, 0));
        assertEquals(50, counts[Material.HOPPER.ordinal()]);

        limits.decrement(island, Material.HOPPER, 0, 0);
        assertTrue(place(limits, Material.HOPPER, 0, 0));
        assertFalse(place(limits, Material.HOPPER, 0, 0));

        limits.unload(island);
        assertFalse(limits.isReady(island));
        assertFalse(place(limits, Material.HOPPER, 0, 0));
    }

    @Test
    void decrementNeverGoesBelowZero() throws Exception {
        LimitHandler limits = new LimitHandler(plugin, defaultLimits(), grid);
        counted(limits, new int[Material.values().length]);
        limits.decrement(island, Material.HOPPER, 0, 0);
        for (int i = 0; i < 50; i++) {
            assertTrue(place(limits, Material.HOPPER, 0, 0), "hopper " + (i + 1));
        }
        assertFalse(place(limits, Material.HOPPER, 0, 0));
    }

    @Test
    void changesInChunksTheScanAlreadySnapshottedAreCarriedIntoItsCounts() throws Exception {
        LimitHandler limits = new LimitHandler(plugin, defaultLimits(), grid);
        counted(limits, new int[Material.values().length]);

        LimitHandler.Scan scan = limits.beginScan(island);
        limits.chunkScanned(island, scan, 0, 0);
        // Placed after chunk 0,0 was snapshotted, so the histogram misses it; the one in 1,0 is
        // placed before that chunk's snapshot, so the histogram has it.
        assertTrue(place(limits, Material.HOPPER, 0, 0));
        assertTrue(place(limits, Material.HOPPER, 1, 0));
        limits.chunkScanned(island, scan, 1, 0);
        int[] histogram = new int[Material.values().length];
        histogram[Material.HOPPER.ordinal()] = 1;
        limits.finishScan(island, scan, histogram);
        assertEquals(2, histogram[Material.HOPPER.ordinal()]);

        LimitHandler.Scan rescan = limits.beginScan(island);
        limits.chunkScanned(island, rescan, 0, 0);
        limits.decrement(island, Material.HOPPER, 0, 0);
        int[] rescanned = new int[Material.values().length];
        rescanned[Material.HOPPER.ordinal()] = 2;
        limits.finishScan(island, rescan, rescanned);
        assertEquals(1, rescanned[Material.HOPPER.ordinal()]);
    }

    @Test
    void aScanReplacedByANewerOneHandsNothingOver() throws Exception {
        LimitHandler limits = new LimitHandler(plugin, defaultLimits(), grid);
        LimitHandler.Scan stale = limits.beginScan(island);
        LimitHandler.Scan current = limits.beginScan(island);

        limits.finishScan(island, stale, new int[Material.values().length]);
        assertFalse(limits.isReady(island));

        limits.finishScan(island, current, new int[Material.values().length]);
        assertTrue(limits.isReady(island));
    }

    @Test
    void entityLimitCountsLiveEntitiesOfThatTypeOnTheIsland() throws Exception {
        LimitHandler limits = new LimitHandler(plugin, defaultLimits(), grid);
        World world = mock(World.class);
        Chunk centreChunk = loadedChunk(world, center.getBlockX() >> 4, center.getBlockZ() >> 4);
        // The chunk on the island's west edge also holds blocks beyond the boundary: minecarts
        // there are not the island's.
        int outsideX = center.getBlockX() - SIZE / 2 - 1;
        Chunk edgeChunk = loadedChunk(world, outsideX >> 4, center.getBlockZ() >> 4);
        List<Entity> outside = entities(world, EntityType.MINECART, 5, outsideX);
        when(edgeChunk.getEntities()).thenReturn(outside.toArray(Entity[]::new));

        List<Entity> entities = new ArrayList<>(entities(world, EntityType.MINECART, 19, center.getBlockX()));
        entities.addAll(entities(world, EntityType.ARMOR_STAND, 5, center.getBlockX()));
        when(centreChunk.getEntities()).thenReturn(entities.toArray(Entity[]::new));
        assertTrue(limits.canSpawnEntity(cachedIsland, world, EntityType.MINECART));

        List<Entity> full = entities(world, EntityType.MINECART, 20, center.getBlockX());
        when(centreChunk.getEntities()).thenReturn(full.toArray(Entity[]::new));
        assertFalse(limits.canSpawnEntity(cachedIsland, world, EntityType.MINECART));

        assertTrue(limits.canSpawnEntity(cachedIsland, world, EntityType.ZOMBIE));
        verify(centreChunk, times(2)).getEntities();
        verify(edgeChunk, times(2)).getEntities();
    }

    /**
     * A place as the listener handles it: checked first, counted once it stands.
     */
    private boolean place(LimitHandler limits, Material type, int chunkX, int chunkZ) {
        if (!limits.hasRoom(island, type)) {
            return false;
        }

        limits.increment(island, type, chunkX, chunkZ);
        return true;
    }

    private static Chunk loadedChunk(World world, int chunkX, int chunkZ) {
        Chunk chunk = mock(Chunk.class);
        when(world.isChunkLoaded(chunkX, chunkZ)).thenReturn(true);
        when(world.getChunkAt(chunkX, chunkZ)).thenReturn(chunk);
        return chunk;
    }

    @Test
    void placeIsDeniedWithAMessageOnlyOnIslands() {
        LimitHandler limits = mock(LimitHandler.class);
        when(limits.getBlockLimit(any())).thenReturn(LimitHandler.UNLIMITED);
        when(limits.getBlockLimit(Material.HOPPER)).thenReturn(50);
        ConfigHandler config = islandWorldConfig();
        Component full = Component.text("full");
        Component calculating = Component.text("calculating");
        when(config.getBlockLimitReachedMessage("HOPPER", 50)).thenReturn(full);
        when(config.getBlockLimitCalculatingMessage()).thenReturn(calculating);
        IslandLimitListener listener = new IslandLimitListener(plugin, config, limits, cache());
        Player player = mock(Player.class);

        BlockPlaceEvent notReady = placeEvent(ISLAND_WORLD, Material.HOPPER, player);
        listener.onBlockPlace(notReady);
        assertTrue(notReady.isCancelled());
        verify(player).sendMessage(calculating);
        verify(limits, never()).hasRoom(any(), any());

        when(limits.isReady(island)).thenReturn(true);
        when(limits.hasRoom(island, Material.HOPPER)).thenReturn(false);
        BlockPlaceEvent denied = placeEvent(ISLAND_WORLD, Material.HOPPER, player);
        listener.onBlockPlace(denied);
        assertTrue(denied.isCancelled());
        verify(player).sendMessage(full);

        when(limits.hasRoom(island, Material.HOPPER)).thenReturn(true);
        BlockPlaceEvent allowed = placeEvent(ISLAND_WORLD, Material.HOPPER, player);
        listener.onBlockPlace(allowed);
        assertFalse(allowed.isCancelled());

        BlockPlaceEvent offIsland = placeEvent("world", Material.HOPPER, player);
        listener.onBlockPlace(offIsland);
        assertFalse(offIsland.isCancelled());

        BlockPlaceEvent untracked = placeEvent(ISLAND_WORLD, Material.STONE, player);
        listener.onBlockPlace(untracked);
        assertFalse(untracked.isCancelled());

        verify(limits, times(2)).hasRoom(any(), any());
        verify(player, times(2)).sendMessage(any(Component.class));
        verify(limits, never()).increment(any(), any(), anyInt(), anyInt());
    }

    @Test
    void aPlaceIsCountedOnlyOnceItStands() {
        LimitHandler limits = mock(LimitHandler.class);
        when(limits.getBlockLimit(any())).thenReturn(LimitHandler.UNLIMITED);
        when(limits.getBlockLimit(Material.HOPPER)).thenReturn(50);
        IslandLimitListener listener = new IslandLimitListener(plugin, islandWorldConfig(), limits, cache());
        Player player = mock(Player.class);

        BlockPlaceEvent refused = placeEvent(ISLAND_WORLD, Material.HOPPER, player);
        refused.setBuild(false);
        listener.onBlockPlaced(refused);
        verify(limits, never()).increment(any(), any(), anyInt(), anyInt());

        listener.onBlockPlaced(placeEvent(ISLAND_WORLD, Material.STONE, player));
        listener.onBlockPlaced(placeEvent("world", Material.HOPPER, player));
        listener.onBlockPlaced(placeEvent(ISLAND_WORLD, Material.HOPPER, player));
        verify(limits).increment(island, Material.HOPPER, 0, 0);
        verify(limits, times(1)).increment(any(), any(), anyInt(), anyInt());
    }

    @Test
    void breakReleasesTrackedBlocksOnIslandsOnly() {
        LimitHandler limits = mock(LimitHandler.class);
        when(limits.getBlockLimit(any())).thenReturn(LimitHandler.UNLIMITED);
        when(limits.getBlockLimit(Material.HOPPER)).thenReturn(50);
        IslandLimitListener listener = new IslandLimitListener(plugin, islandWorldConfig(), limits, cache());
        Player player = mock(Player.class);

        listener.onBlockBreak(new BlockBreakEvent(block(ISLAND_WORLD, Material.HOPPER), player));
        listener.onBlockBreak(new BlockBreakEvent(block(ISLAND_WORLD, Material.STONE), player));
        listener.onBlockBreak(new BlockBreakEvent(block("world", Material.HOPPER), player));

        verify(limits).decrement(island, Material.HOPPER, 0, 0);
        verify(limits, times(1)).decrement(any(), any(), anyInt(), anyInt());
    }

    @Test
    void spawnsAreDeniedWhenTheIslandIsFull() {
        LimitHandler limits = mock(LimitHandler.class);
        when(limits.getEntityLimit(any())).thenReturn(LimitHandler.UNLIMITED);
        when(limits.getEntityLimit(EntityType.MINECART)).thenReturn(20);
        IslandLimitListener listener = new IslandLimitListener(plugin, islandWorldConfig(), limits, cache());
        World islandWorld = world(ISLAND_WORLD);
        World overworld = world("world");

        when(limits.canSpawnEntity(cachedIsland, islandWorld, EntityType.MINECART)).thenReturn(false);
        EntityPlaceEvent denied = new EntityPlaceEvent(entity(EntityType.MINECART), mock(Player.class), block(islandWorld, Material.RAIL), BlockFace.UP);
        listener.onEntityPlace(denied);
        assertTrue(denied.isCancelled());

        EntityPlaceEvent offIsland = new EntityPlaceEvent(entity(EntityType.MINECART), mock(Player.class), block(overworld, Material.RAIL), BlockFace.UP);
        listener.onEntityPlace(offIsland);
        assertFalse(offIsland.isCancelled());

        LivingEntity zombie = mock(LivingEntity.class);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        when(zombie.getLocation()).thenReturn(new Location(islandWorld, center.getX(), center.getY(), center.getZ()));
        CreatureSpawnEvent unlimited = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);
        listener.onCreatureSpawn(unlimited);
        assertFalse(unlimited.isCancelled());

        verify(limits, times(1)).canSpawnEntity(any(), any(), any());
    }

    private void counted(LimitHandler limits, int[] counts) {
        limits.finishScan(island, limits.beginScan(island), counts);
    }

    private ConfigHandler islandWorldConfig() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getIslandWorldName()).thenReturn(ISLAND_WORLD);
        return config;
    }

    /**
     * The island sits in its slot; nothing else is hosted.
     */
    private IslandCache cache() {
        IslandCache cache = mock(IslandCache.class);
        when(cache.getAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return grid.slotAt(location.getX(), location.getZ()) == SLOT ? cachedIsland : null;
        });
        when(cache.getIslandUuidAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return grid.slotAt(location.getX(), location.getZ()) == SLOT ? island : null;
        });
        return cache;
    }

    private List<Entity> entities(World world, EntityType type, int count, int blockX) {
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Entity entity = entity(type);
            when(entity.getLocation()).thenReturn(new Location(world, blockX, 70, center.getBlockZ()));
            entities.add(entity);
        }
        return entities;
    }

    private static Entity entity(EntityType type) {
        Entity entity = mock(Entity.class);
        when(entity.getType()).thenReturn(type);
        return entity;
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private Block block(String worldName, Material type) {
        return block(world(worldName), type);
    }

    private Block block(World world, Material type) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(type);
        when(block.getLocation()).thenReturn(new Location(world, center.getX(), center.getY(), center.getZ()));
        return block;
    }

    private BlockPlaceEvent placeEvent(String worldName, Material type, Player player) {
        return new BlockPlaceEvent(block(worldName, type), mock(BlockState.class), mock(Block.class), mock(ItemStack.class), player, true);
    }
}
