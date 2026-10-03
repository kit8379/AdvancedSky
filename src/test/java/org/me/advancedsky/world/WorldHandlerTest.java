package org.me.advancedsky.world;

import io.papermc.paper.threadedregions.scheduler.*;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.me.advancedsky.world.SchematicFixtures.*;

class WorldHandlerTest {
    private static final int SLOT = 1;
    private final IslandGrid grid = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final RegionScheduler regions = mock(RegionScheduler.class);
    private final GlobalRegionScheduler timer = mock(GlobalRegionScheduler.class);
    private final ArrayDeque<Consumer<ScheduledTask>> ticks = new ArrayDeque<>();
    private final StructureManager structures = mock(StructureManager.class);
    private final Palette palette = mock(Palette.class);
    @TempDir Path folder;

    @BeforeEach
    void setUp() throws IOException {
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getTemplateSchematic()).thenReturn("schematics/starter.schem");
        when(config.getIslandHeight()).thenReturn(64);
        when(plugin.getDataFolder()).thenAnswer(call -> folder.toFile());
        write(folder.resolve("schematics/starter.schem"), schematic(1, 1, 1, new int[3], new byte[]{1}));
        Structure structure = mock(Structure.class);
        when(structures.loadStructure(any(InputStream.class))).thenReturn(structure);
        when(structure.getPalettes()).thenReturn(List.of(palette));
        BlockState stone = mock(BlockState.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(palette.getBlocks()).thenReturn(List.of(stone));
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getWorldPath()).thenAnswer(call -> folder.resolve("islands"));
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        BukkitAsyncExecutor io = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(io).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(io);
        doAnswer(call -> { call.<Runnable>getArgument(4).run(); return null; })
                .when(regions).execute(eq(plugin), eq(world), anyInt(), anyInt(), any());
        when(timer.runDelayed(eq(plugin), any(), eq(20L))).thenAnswer(call -> {
            ticks.add(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
    }

    @Test
    void deletionSendsPlayersAwayAndClearsOnlyItsRegionBeforeCompleting() throws IOException {
        when(plugin.getApi()).thenReturn(api);
        when(api.lobby(playerUuid)).thenReturn(CompletableFuture.completedFuture(null));
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getLocation()).thenReturn(grid.center(world, SLOT));
        try (MockedStatic<Bukkit> bukkit = bukkit(); MockedStatic<RegionStorageAccess> storage = mockStatic(RegionStorageAccess.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(player));
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            Path neighbour = Files.writeString(files.getFirst().resolveSibling("r.8.0.mca"), "neighbour");
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            assertFalse(cleared.isDone());
            files.forEach(file -> assertTrue(Files.exists(file)));
            tick();
            cleared.join();
            files.forEach(file -> assertFalse(Files.exists(file)));
            assertEquals("neighbour", Files.readString(neighbour));
            verify(api).lobby(playerUuid);
            verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
            verify(world, never()).save(anyBoolean());
        }
    }

    @Test
    void datapackDimensionUsesItsDimensionsFolder() {
        when(world.getKey()).thenReturn(new NamespacedKey("advancedsky", "islands"));
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            Path dimension = folder.resolve("islands/dimensions/advancedsky/islands");
            assertTrue(handler().regionFiles(SLOT).stream().allMatch(file -> file.startsWith(dimension)));
        }
    }

    @Test
    void deletionRetriesHeldChunksAndWaitsForPartiallyUnloadedHolders() throws IOException {
        AtomicBoolean loaded = new AtomicBoolean(true);
        Location center = grid.center(world, SLOT);
        int cx = Math.floorDiv(center.getBlockX(), 512) * 32;
        int cz = Math.floorDiv(center.getBlockZ(), 512) * 32;
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> loaded.get()
                && call.<Integer>getArgument(0) == cx && call.<Integer>getArgument(1) == cz);
        when(world.unloadChunk(cx, cz, true)).thenReturn(false);
        try (MockedStatic<Bukkit> bukkit = bukkit(); MockedStatic<RegionStorageAccess> storage = mockStatic(RegionStorageAccess.class)) {
            storage.when(() -> RegionStorageAccess.hasHolders(world, cx, cz)).thenReturn(true, false, false);
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            tick();
            assertFalse(cleared.isDone());
            loaded.set(false);
            tick();
            assertFalse(cleared.isDone(), "partially unloaded holders still block deletion");
            tick();
            cleared.join();
            files.forEach(file -> assertFalse(Files.exists(file)));
            storage.verify(() -> RegionStorageAccess.flushAndEvict(world, cx / 32, cz / 32));
        }
        verify(world, times(2)).unloadChunk(cx, cz, true);
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void storageBarrierFailureLeavesFilesIntact() throws IOException {
        try (MockedStatic<Bukkit> bukkit = bukkit(); MockedStatic<RegionStorageAccess> storage = mockStatic(RegionStorageAccess.class)) {
            storage.when(() -> RegionStorageAccess.flushAndEvict(world, 4, 0)).thenThrow(new IllegalStateException("storage unavailable"));
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            tick();
            assertThrows(CompletionException.class, cleared::join);
            files.forEach(file -> assertTrue(Files.exists(file)));
        }
    }

    @Test
    void failedFileDeletionDoesNotReportSuccessForAPartiallyClearedRegion() throws IOException {
        try (MockedStatic<Bukkit> bukkit = bukkit(); MockedStatic<RegionStorageAccess> storage = mockStatic(RegionStorageAccess.class)) {
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            Files.delete(files.get(1));
            Files.createDirectory(files.get(1));
            Files.writeString(files.get(1).resolve("occupied"), "x");
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            tick();
            assertThrows(CompletionException.class, cleared::join);
            assertFalse(Files.exists(files.getFirst()));
            assertTrue(Files.exists(files.get(1)));
            assertTrue(Files.exists(files.get(2)));
        }
    }

    @Test
    void anUnsupportedStorageApiFailsClosedWithoutDeletingFiles() throws IOException {
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            tick();
            assertThrows(CompletionException.class, cleared::join);
            files.forEach(file -> assertTrue(Files.exists(file)));
        }
    }

    @Test
    void heldChunksFailAfterBoundedRetriesWithoutTouchingFiles() throws IOException {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> call.<Integer>getArgument(0) == 128 && call.<Integer>getArgument(1) == 0);
        try (MockedStatic<Bukkit> bukkit = bukkit(); MockedStatic<RegionStorageAccess> storage = mockStatic(RegionStorageAccess.class)) {
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            for (int i = 0; i < 60; i++) tick();
            assertThrows(CompletionException.class, cleared::join);
            assertTrue(ticks.isEmpty());
            files.forEach(file -> assertTrue(Files.exists(file)));
            storage.verifyNoInteractions();
        }
    }

    @Test
    void stoppingPreventsAPendingClearFromDeletingFiles() throws IOException {
        try (MockedStatic<Bukkit> bukkit = bukkit(); MockedStatic<RegionStorageAccess> storage = mockStatic(RegionStorageAccess.class)) {
            WorldHandler handler = handler();
            List<Path> files = writeFiles(handler);
            CompletableFuture<Void> cleared = handler.deleteIsland(SLOT);
            handler.stop();
            tick();
            assertThrows(CompletionException.class, cleared::join);
            files.forEach(file -> assertTrue(Files.exists(file)));
            storage.verifyNoInteractions();
        }
    }

    @Test
    void thePasteUsesOnlyDestinationChunksAndWaitsForAllTheirRegionTasks() throws IOException {
        write(folder.resolve("schematics/starter.schem"), schematic(2, 1, 1, new int[]{-1, -1, 0}, new byte[]{1, 2}));
        BlockState stone = mock(BlockState.class);
        BlockState chest = mock(BlockState.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(chest.getX()).thenReturn(1);
        when(palette.getBlocks()).thenReturn(List.of(stone, chest));
        List<Runnable> pending = new ArrayList<>();
        List<BlockState> copies = new ArrayList<>();
        AtomicBoolean onRegion = new AtomicBoolean();
        for (BlockState state : List.of(stone, chest)) {
            when(state.copy(any(Location.class))).thenAnswer(call -> {
                assertTrue(onRegion.get(), "snapshot copying must run on the destination's region");
                BlockState copy = mock(BlockState.class);
                when(copy.update(true, false)).thenReturn(true);
                copies.add(copy);
                return copy;
            });
        }
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        doAnswer(call -> {
            pending.add(() -> {
                onRegion.set(true);
                try { call.<Consumer<ScheduledTask>>getArgument(4).accept(mock(ScheduledTask.class)); }
                finally { onRegion.set(false); }
            });
            return mock(ScheduledTask.class);
        }).when(regions).run(eq(plugin), eq(world), anyInt(), anyInt(), any());
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            WorldHandler handler = handler();
            CompletableFuture<Void> pasted = handler.createIsland(SLOT);
            assertFalse(pasted.isDone());
            assertEquals(2, pending.size());
            assertTrue(copies.isEmpty());
            pending.get(0).run();
            assertFalse(pasted.isDone());
            pending.get(1).run();
            pasted.join();
            Location center = grid.center(world, SLOT);
            verify(stone).copy(center.clone().add(-1, -1, 0));
            verify(chest).copy(center.clone().add(0, -1, 0));
            verify(regions).run(eq(plugin), eq(world), eq((center.getBlockX() - 1) >> 4), eq(center.getBlockZ() >> 4), any());
            verify(regions).run(eq(plugin), eq(world), eq(center.getBlockX() >> 4), eq(center.getBlockZ() >> 4), any());
            // Reusing the cached schematic must copy fresh tile snapshots for the next island.
            CompletableFuture<Void> again = handler.createIsland(SLOT + 1);
            pending.get(2).run();
            pending.get(3).run();
            again.join();
            assertEquals(4, copies.size());
        }
        copies.forEach(copy -> verify(copy).update(true, false));
        verify(stone, never()).update(anyBoolean(), anyBoolean());
        verify(chest, never()).update(anyBoolean(), anyBoolean());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void missingCustomSchematicFailsStartupWithItsPathAndExportInstructions() {
        when(config.getTemplateSchematic()).thenReturn("schematics/custom.schem");
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            IllegalStateException error = assertThrows(IllegalStateException.class, this::handler);
            assertTrue(error.getMessage().contains("custom.schem"));
            assertTrue(error.getMessage().contains("export the starter island"));
            verifyNoInteractions(structures);
            verify(plugin, never()).saveResource(anyString(), anyBoolean());
        }
    }

    @Test
    void missingDefaultIsExtractedFromTheJarAndExistingFilesAreKept() throws IOException {
        Path file = folder.resolve(SchematicTemplate.DEFAULT_PATH);
        Files.delete(file);
        doAnswer(call -> {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(call.getArgument(0))) {
                assertNotNull(input);
                Files.copy(input, file);
            }
            return null;
        }).when(plugin).saveResource(SchematicTemplate.DEFAULT_PATH, false);
        // One mocked snapshot per non-air block, as the real StructureManager would return.
        int blockCount;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(SchematicTemplate.DEFAULT_PATH)) {
            var bundled = net.kyori.adventure.nbt.BinaryTagIO.reader().read(input, net.kyori.adventure.nbt.BinaryTagIO.Compression.GZIP);
            blockCount = SchematicTemplate.decode(bundled.getCompound("Schematic"), DATA_VERSION).structure().getList("blocks").size();
        }
        BlockState example = palette.getBlocks().getFirst();
        when(palette.getBlocks()).thenReturn(java.util.Collections.nCopies(blockCount, example));
        try (MockedStatic<Bukkit> bukkit = bukkit()) {
            handler();
            assertTrue(Files.isRegularFile(file));
            byte[] saved = Files.readAllBytes(file);
            handler();
            assertArrayEquals(saved, Files.readAllBytes(file));
            verify(plugin, times(1)).saveResource(SchematicTemplate.DEFAULT_PATH, false);
        }
    }

    @Test
    void lobbyNeedsNeitherTheIslandWorldNorASchematic() {
        when(config.isLobbyOnly()).thenReturn(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            WorldHandler handler = handler();
            assertFalse(handler.isIslandLocation(new Location(world, 0, 64, 0)));
            bukkit.verifyNoInteractions();
            verify(plugin, never()).getDataFolder();
        }
    }

    private WorldHandler handler() { return new WorldHandler(plugin, config, grid); }
    private void tick() { ticks.remove().accept(mock(ScheduledTask.class)); }
    private List<Path> writeFiles(WorldHandler handler) throws IOException {
        List<Path> files = handler.regionFiles(SLOT);
        for (Path file : files) { Files.createDirectories(file.getParent()); Files.writeString(file, "old island"); }
        return files;
    }
    private MockedStatic<Bukkit> bukkit() {
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("islands")).thenReturn(world);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.getDataVersion()).thenReturn(DATA_VERSION);
        bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
        bukkit.when(Bukkit::getStructureManager).thenReturn(structures);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of());
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(timer);
        return bukkit;
    }
}
