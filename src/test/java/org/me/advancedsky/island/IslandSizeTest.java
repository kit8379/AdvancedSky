package org.me.advancedsky.island;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.UpgradeLevelChangedException;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.listener.IslandProtectionListener;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandSizeTest {
    private static final int[] SIZES = {48, 80, 112, 144, 160};
    private static final int[] MIN_OFFSETS = {-32, -48, -64, -80, -80};
    private static final String ISLAND_WORLD = "islands";
    // Slot 7 lies on the negative z side of the origin, so boundaries cross negative block coordinates.
    private static final int SLOT = 7;
    private final UUID islandId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final IslandGrid grid = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn(ISLAND_WORLD);
        when(config.getIslandWorldName()).thenReturn(ISLAND_WORLD);
        when(database.getIslandServer(islandId)).thenReturn(java.util.Optional.of("local"));
        when(config.isUpgrade("island-size")).thenReturn(true);
        when(config.getUpgradeLevels("island-size")).thenReturn(List.of(1, 2, 3, 4, 5));
        when(database.getIslandUpgradeLevel(islandId, "island-size")).thenReturn(1);
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        for (int level = 1; level <= SIZES.length; level++) {
            when(config.getUpgradeLimit("island-size", level)).thenReturn(SIZES[level - 1]);
        }
    }

    @Test
    void defaultConfigExposesAllFiveSizesAndRequirements() throws Exception {
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
        assertTrue(realConfig.isUpgrade("island-size"));
        assertEquals(List.of(1, 2, 3, 4, 5), realConfig.getUpgradeLevels("island-size"));
        int[] sizes = {48, 80, 112, 144, 160};
        int[] requirements = {0, 200, 450, 850, 1400};
        double[] prices = {0, 60000, 180000, 450000, 1000000};
        for (int i = 0; i < sizes.length; i++) {
            assertEquals(sizes[i], realConfig.getUpgradeLimit("island-size", i + 1));
            assertEquals(requirements[i], realConfig.getUpgradeRequireLevel("island-size", i + 1));
            assertEquals(prices[i], realConfig.getUpgradePrice("island-size", i + 1));
        }
        assertEquals(5, realConfig.getUpgradeLimit("team-limit", 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void protectionAndMovementUseTheAlignedUpgradeBoundary(int level) {
        int size = SIZES[level - 1];
        Island island = island(level);
        when(cache.getAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return grid.slotAt(location.getX(), location.getZ()) == SLOT ? island : null;
        });

        IslandProtectionListener protection = new IslandProtectionListener(config, cache, grid);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        Location center = grid.center(world, SLOT);
        int minX = center.getBlockX() + MIN_OFFSETS[level - 1];
        int maxX = minX + size - 1;
        int minZ = center.getBlockZ() + MIN_OFFSETS[level - 1];
        int maxZ = minZ + size - 1;
        for (int x : new int[]{minX - 1, minX, maxX, maxX + 1}) {
            for (int z : new int[]{minZ - 1, minZ, maxZ, maxZ + 1}) {
                Block block = mock(Block.class);
                when(block.getLocation()).thenReturn(new Location(world, x, 70, z));
                BlockBreakEvent event = new BlockBreakEvent(block, player);
                protection.onBlockBreak(event);
                assertEquals(x < minX || x > maxX || z < minZ || z > maxZ, event.isCancelled(), x + "," + z);
                PlayerMoveEvent move = new PlayerMoveEvent(player, center, block.getLocation());
                protection.onPlayerMove(move);
                assertEquals(event.isCancelled(), move.isCancelled(), x + "," + z);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void entityLimitIncludesEntitiesAtTheAlignedWestEdge(int level) {
        int size = SIZES[level - 1];
        Location center = grid.center(world, SLOT);
        int minX = center.getBlockX() + MIN_OFFSETS[level - 1];
        int minZ = center.getBlockZ() + MIN_OFFSETS[level - 1];
        when(config.getEntityLimits()).thenReturn(Map.of("MINECART", 1));
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(128);
        Entity minecart = mock(Entity.class);
        when(minecart.getType()).thenReturn(EntityType.MINECART);
        when(minecart.getLocation()).thenReturn(new Location(world, minX + 0.5, 70, minZ + 0.5));
        when(world.getNearbyEntities(any(BoundingBox.class), any())).thenAnswer(call -> {
            BoundingBox boundary = call.getArgument(0);
            Predicate<Entity> filter = call.getArgument(1);
            return boundary.contains(minecart.getLocation().toVector()) && filter.test(minecart) ? List.of(minecart) : List.of();
        });

        LimitHandler limits = new LimitHandler(plugin, config, grid);
        assertFalse(limits.canSpawnEntity(island(level), world, EntityType.MINECART));
        ArgumentCaptor<BoundingBox> boundary = ArgumentCaptor.forClass(BoundingBox.class);
        verify(world).getNearbyEntities(boundary.capture(), any());
        assertEquals(minX, boundary.getValue().getMinX());
        assertEquals(minZ, boundary.getValue().getMinZ());
        assertEquals(minX + size, boundary.getValue().getMaxX());
        assertEquals(minZ + size, boundary.getValue().getMaxZ());
    }

    @Test
    void hostRefreshesTheCacheAfterPurchaseAndAdminDowngrade() {
        UpgradeHandler handler = new UpgradeHandler(plugin, config, database, new IslandOperator(plugin, database, null, cache, null, null, null, "local"), null);
        when(cache.reload(islandId)).thenReturn(CompletableFuture.completedFuture(null));
        Actor buyer = new Actor.Player(owner);
        Actor admin = new Actor.Bypass("test");

        handler.setUpgradeLevel(buyer, islandId, "island-size", 2).join();
        when(database.getIslandUpgradeLevel(islandId, "island-size")).thenReturn(2);
        handler.setUpgradeLevel(admin, islandId, "island-size", 1).join();

        InOrder order = inOrder(database, cache);
        order.verify(database).updateIslandUpgradeLevel(buyer, islandId, "island-size", 1, 2);
        order.verify(cache).reload(islandId);
        order.verify(database).updateIslandUpgradeLevel(admin, islandId, "island-size", 2, 1);
        order.verify(cache).reload(islandId);
    }

    @Test
    void rejectedWriteKeepsTheCauseAndLeavesTheCacheAlone() {
        UpgradeHandler handler = new UpgradeHandler(plugin, config, database, new IslandOperator(plugin, database, null, cache, null, null, null, "local"), null);
        Actor actor = new Actor.Player(owner);
        doThrow(new UpgradeLevelChangedException()).when(database).updateIslandUpgradeLevel(actor, islandId, "island-size", 1, 2);
        CompletionException error = assertThrows(CompletionException.class, () -> handler.setUpgradeLevel(actor, islandId, "island-size", 2).join());
        assertInstanceOf(UpgradeLevelChangedException.class, error.getCause());
        verify(cache, never()).reload(islandId);
    }

    @Test
    void committedUpgradeIsNotRefundedIfCacheRefreshFails() {
        UpgradeHandler handler = new UpgradeHandler(plugin, config, database, new IslandOperator(plugin, database, null, cache, null, null, null, "local"), null);
        when(cache.reload(islandId)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        assertDoesNotThrow(() -> handler.setUpgradeLevel(new Actor.Player(owner), islandId, "island-size", 2).join());
        verify(plugin).severe(eq("Island data saved but could not refresh the island cache: " + islandId), any(Throwable.class));
    }

    @Test
    void anIslandWhoseEntryLoadsAgainIsNotScanned() {
        when(cache.isUnavailable(islandId)).thenReturn(true);
        WorldHandler worlds = mock(WorldHandler.class);

        CompletionException error = assertThrows(CompletionException.class, () -> new LevelHandler(plugin, config, database, mock(LimitHandler.class), worlds, cache, grid).calIslandLevel(islandId).join());
        assertInstanceOf(IllegalStateException.class, error.getCause());
        verifyNoInteractions(worlds);
        verify(database, never()).getIslandLevel(islandId);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @SuppressWarnings("unchecked")
    void levelScanCountsOnlyBlocksWithinTheUpgradedSize(int level) {
        int size = SIZES[level - 1];
        when(cache.get(islandId)).thenReturn(island(level));
        WorldHandler worlds = mock(WorldHandler.class);
        when(worlds.getWorld()).thenReturn(world);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(1);
        when(config.getBlockLevel("STONE")).thenReturn(100);
        when(worlds.onChunk(eq(world), anyInt(), anyInt(), eq(false), any())).thenAnswer(call -> {
            Chunk chunk = mock(Chunk.class);
            ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
            when(snapshot.getX()).thenReturn(call.getArgument(1));
            when(snapshot.getZ()).thenReturn(call.getArgument(2));
            when(snapshot.getBlockType(anyInt(), eq(0), anyInt())).thenReturn(Material.STONE);
            when(chunk.getChunkSnapshot(false, false, false, false)).thenReturn(snapshot);
            return call.<Function<Chunk, CompletableFuture<Void>>>getArgument(4).apply(chunk);
        });
        LimitHandler limits = mock(LimitHandler.class);

        assertEquals(size * size, new LevelHandler(plugin, config, database, limits, worlds, cache, grid).calIslandLevel(islandId).join());
        verify(database).updateIslandLevel(islandId, size * size);
        ArgumentCaptor<int[]> counts = ArgumentCaptor.forClass(int[].class);
        verify(limits).finishScan(eq(islandId), any(), counts.capture());
        assertEquals(size * size, counts.getValue()[Material.STONE.ordinal()]);
        Location center = grid.center(world, SLOT);
        int minChunkX = Math.floorDiv(center.getBlockX() + MIN_OFFSETS[level - 1], 16);
        int minChunkZ = Math.floorDiv(center.getBlockZ() + MIN_OFFSETS[level - 1], 16);
        int width = size / 16;
        verify(worlds, times(width * width)).onChunk(eq(world), anyInt(), anyInt(), eq(false), any());
        for (int chunkX = minChunkX; chunkX < minChunkX + width; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ < minChunkZ + width; chunkZ++) {
                verify(worlds).onChunk(eq(world), eq(chunkX), eq(chunkZ), eq(false), any());
            }
        }
    }

    @Test
    void levelScanSkipsSectionsHoldingOnlyAir() {
        int size = SIZES[0];
        when(cache.get(islandId)).thenReturn(island(1));
        WorldHandler worlds = mock(WorldHandler.class);
        when(worlds.getWorld()).thenReturn(world);
        when(world.getMinHeight()).thenReturn(-16);
        when(world.getMaxHeight()).thenReturn(32);
        when(config.getBlockLevel("STONE")).thenReturn(100);
        when(worlds.onChunk(eq(world), anyInt(), anyInt(), eq(false), any())).thenAnswer(call -> {
            Chunk chunk = mock(Chunk.class);
            ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
            when(snapshot.getX()).thenReturn(call.getArgument(1));
            when(snapshot.getZ()).thenReturn(call.getArgument(2));
            // Were the empty middle section read, its stone would count too.
            when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
            when(snapshot.isSectionEmpty(1)).thenReturn(true);
            when(chunk.getChunkSnapshot(false, false, false, false)).thenReturn(snapshot);
            return call.<Function<Chunk, CompletableFuture<Void>>>getArgument(4).apply(chunk);
        });

        int level = new LevelHandler(plugin, config, database, mock(LimitHandler.class), worlds, cache, grid).calIslandLevel(islandId).join();
        assertEquals(size * size * 32, level);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCappedTwoBlockBlockIsCountedOnceForItsLimitAndTwiceForTheLevel() {
        int size = SIZES[0];
        when(cache.get(islandId)).thenReturn(island(1));
        WorldHandler worlds = mock(WorldHandler.class);
        when(worlds.getWorld()).thenReturn(world);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(2);
        when(config.getBlockLevel("OAK_DOOR")).thenReturn(100);
        Door lower = mock(Door.class);
        when(lower.getHalf()).thenReturn(Bisected.Half.BOTTOM);
        Door upper = mock(Door.class);
        when(upper.getHalf()).thenReturn(Bisected.Half.TOP);
        when(worlds.onChunk(eq(world), anyInt(), anyInt(), eq(false), any())).thenAnswer(call -> {
            Chunk chunk = mock(Chunk.class);
            ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
            when(snapshot.getX()).thenReturn(call.getArgument(1));
            when(snapshot.getZ()).thenReturn(call.getArgument(2));
            when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenReturn(Material.OAK_DOOR);
            when(snapshot.getBlockData(anyInt(), eq(0), anyInt())).thenReturn(lower);
            when(snapshot.getBlockData(anyInt(), eq(1), anyInt())).thenReturn(upper);
            when(chunk.getChunkSnapshot(false, false, false, false)).thenReturn(snapshot);
            return call.<Function<Chunk, CompletableFuture<Void>>>getArgument(4).apply(chunk);
        });
        LimitHandler limits = mock(LimitHandler.class);
        when(limits.getBlockLimit(any())).thenReturn(LimitHandler.UNLIMITED);
        when(limits.getBlockLimit(Material.OAK_DOOR)).thenReturn(10);

        assertEquals(2 * size * size, new LevelHandler(plugin, config, database, limits, worlds, cache, grid).calIslandLevel(islandId).join());
        ArgumentCaptor<int[]> counts = ArgumentCaptor.forClass(int[].class);
        verify(limits).finishScan(eq(islandId), any(), counts.capture());
        assertEquals(size * size, counts.getValue()[Material.OAK_DOOR.ordinal()]);
    }

    private Island island(int sizeLevel) {
        return new Island(islandId, SLOT, false, false, owner, Set.of(), Set.of(), Set.of(), Map.of(), null, sizeLevel, 1);
    }
}
