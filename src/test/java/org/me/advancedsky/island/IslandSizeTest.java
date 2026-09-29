package org.me.advancedsky.island;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.UpgradeLevelChangedException;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.listener.IslandProtectionListener;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

class IslandSizeTest {
    private static final int[] SIZES = {75, 100, 125, 150, 175};
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
        assertTrue(realConfig.isUpgrade("island-size"));
        assertEquals(List.of(1, 2, 3, 4, 5), realConfig.getUpgradeLevels("island-size"));
        int[] sizes = {75, 100, 125, 150, 175};
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
    void protectionUsesConfiguredWidthAroundTheIslandCentre(int level) {
        int size = SIZES[level - 1];
        Island island = island(level);
        when(cache.getAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return grid.slotAt(location.getX(), location.getZ()) == SLOT ? island : null;
        });

        IslandProtectionListener protection = new IslandProtectionListener(config, cache, grid);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        Location center = grid.center(null, SLOT);
        int minX = center.getBlockX() - size / 2;
        int maxX = minX + size - 1;
        int minZ = center.getBlockZ() - size / 2;
        int maxZ = minZ + size - 1;
        for (int x : new int[]{minX - 1, minX, maxX, maxX + 1}) {
            for (int z : new int[]{minZ - 1, minZ, maxZ, maxZ + 1}) {
                Block block = mock(Block.class);
                when(block.getLocation()).thenReturn(new Location(world, x, 70, z));
                BlockBreakEvent event = new BlockBreakEvent(block, player);
                protection.onBlockBreak(event);
                assertEquals(x < minX || x > maxX || z < minZ || z > maxZ, event.isCancelled(), x + "," + z);
            }
        }
    }

    @Test
    void hostRefreshesTheCacheAfterPurchaseAndAdminDowngrade() {
        IslandOperator operator = new IslandOperator(plugin, database, null, null, cache, null, "local");
        when(cache.reload(islandId)).thenReturn(CompletableFuture.completedFuture(null));
        Actor buyer = new Actor.Player(owner);
        Actor admin = new Actor.Bypass("test");

        operator.setUpgradeLevel(buyer, islandId, "island-size", 1, 2, Long.MAX_VALUE).join();
        operator.setUpgradeLevel(admin, islandId, "island-size", 2, 1, Long.MAX_VALUE).join();

        InOrder order = inOrder(database, cache);
        order.verify(database).updateIslandUpgradeLevel(buyer, islandId, "island-size", 1, 2, Long.MAX_VALUE);
        order.verify(cache).reload(islandId);
        order.verify(database).updateIslandUpgradeLevel(admin, islandId, "island-size", 2, 1, Long.MAX_VALUE);
        order.verify(cache).reload(islandId);
    }

    @Test
    void rejectedWriteKeepsTheCauseAndLeavesTheCacheAlone() {
        IslandOperator operator = new IslandOperator(plugin, database, null, null, cache, null, "local");
        Actor actor = new Actor.Player(owner);
        doThrow(new UpgradeLevelChangedException()).when(database).updateIslandUpgradeLevel(actor, islandId, "island-size", 1, 2, Long.MAX_VALUE);
        CompletionException error = assertThrows(CompletionException.class, () -> operator.setUpgradeLevel(actor, islandId, "island-size", 1, 2, Long.MAX_VALUE).join());
        assertInstanceOf(UpgradeLevelChangedException.class, error.getCause());
        verify(cache, never()).reload(islandId);
    }

    @Test
    void committedUpgradeIsNotRefundedIfCacheRefreshFails() {
        IslandOperator operator = new IslandOperator(plugin, database, null, null, cache, null, "local");
        when(cache.reload(islandId)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        assertDoesNotThrow(() -> operator.setUpgradeLevel(new Actor.Player(owner), islandId, "island-size", 1, 2, Long.MAX_VALUE).join());
        verify(plugin).severe(eq("Island data saved but could not refresh the island cache: " + islandId), any(Throwable.class));
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
            when(chunk.getChunkSnapshot(false, false, false)).thenReturn(snapshot);
            return call.<Function<Chunk, CompletableFuture<Void>>>getArgument(4).apply(chunk);
        });
        LimitHandler limits = mock(LimitHandler.class);

        assertEquals(size * size, new LevelHandler(plugin, config, database, limits, worlds, cache, grid).calIslandLevel(islandId).join());
        verify(database).updateIslandLevel(islandId, size * size);
        ArgumentCaptor<int[]> counts = ArgumentCaptor.forClass(int[].class);
        verify(limits).finishScan(eq(islandId), any(), counts.capture());
        assertEquals(size * size, counts.getValue()[Material.STONE.ordinal()]);
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
            when(chunk.getChunkSnapshot(false, false, false)).thenReturn(snapshot);
            return call.<Function<Chunk, CompletableFuture<Void>>>getArgument(4).apply(chunk);
        });

        int level = new LevelHandler(plugin, config, database, mock(LimitHandler.class), worlds, cache, grid).calIslandLevel(islandId).join();
        assertEquals(size * size * 32, level);
    }

    private Island island(int sizeLevel) {
        return new Island(islandId, SLOT, false, false, owner, Set.of(), Set.of(), Set.of(), Map.of(), null, sizeLevel, 1);
    }
}
