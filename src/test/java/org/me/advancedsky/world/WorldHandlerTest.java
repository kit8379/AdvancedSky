package org.me.advancedsky.world;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

class WorldHandlerTest {
    private static final int SLOT = 1;
    private final IslandGrid grid = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final AdvancedSkyAPI api = mock(AdvancedSkyAPI.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final UUID playerUuid = UUID.randomUUID();
    private final ConfigHandler config = mock(ConfigHandler.class);

    @TempDir
    Path folder;

    WorldHandlerTest() {
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getTemplateWorldName()).thenReturn("template");
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getWorldPath()).thenAnswer(call -> folder.resolve("islands"));
    }

    @Test
    void deleteSendsThePlayersAwayAndListsTheAreasRegionFilesWithoutTouchingABlock() throws IOException {
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
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

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("islands")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("template")).thenReturn(mock(World.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(player));

            new WorldHandler(plugin, config, grid, new RegionPurge(folder.resolve("plugin"))).deleteIsland(SLOT).join();
        }

        verify(api).lobby(playerUuid);
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
        Location center = grid.center(world, SLOT);
        String name = "r." + Math.floorDiv(center.getBlockX(), 512) + "." + Math.floorDiv(center.getBlockZ(), 512) + ".mca";
        Path islands = folder.resolve("islands").toAbsolutePath();
        assertEquals(List.of(islands.resolve("region").resolve(name).toString(), islands.resolve("entities").resolve(name).toString(), islands.resolve("poi").resolve(name).toString()), Files.readAllLines(folder.resolve("plugin").resolve("region-purge.txt")));
    }

    @Test
    void aDatapackDimensionKeepsItsRegionFilesInItsDimensionsFolder() throws IOException {
        when(world.getKey()).thenReturn(new NamespacedKey("advancedsky", "islands"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("islands")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("template")).thenReturn(mock(World.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of());
            GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
            doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
            when(plugin.getGlobalRegionExecutor()).thenReturn(global);

            new WorldHandler(plugin, config, grid, new RegionPurge(folder.resolve("plugin"))).deleteIsland(SLOT).join();
        }

        Path dimension = folder.resolve("islands").toAbsolutePath().resolve("dimensions").resolve("advancedsky").resolve("islands");
        assertTrue(Files.readAllLines(folder.resolve("plugin").resolve("region-purge.txt")).stream().allMatch(line -> line.startsWith(dimension.toString())));
    }

    @Test
    void thePasteLooksUpNoWorldBlockInSectionsHoldingOnlyAir() {
        when(config.getTemplateAnchorY()).thenReturn(64);
        when(config.getTemplateMinY()).thenReturn(64);
        when(config.getTemplateMaxY()).thenReturn(79);
        World template = mock(World.class);
        when(template.getMinHeight()).thenReturn(0);
        when(template.getMaxHeight()).thenReturn(128);
        Chunk templateChunk = mock(Chunk.class);
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        when(templateChunk.getChunkSnapshot(false, false, false, false)).thenReturn(snapshot);
        when(snapshot.isSectionEmpty(4)).thenReturn(true);
        when(template.getChunkAtAsync(0, 0, true)).thenReturn(CompletableFuture.completedFuture(templateChunk));
        RegionScheduler regions = mock(RegionScheduler.class);
        doAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(4).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        }).when(regions).run(eq(plugin), any(World.class), anyInt(), anyInt(), any());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("islands")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("template")).thenReturn(template);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);

            new WorldHandler(plugin, config, grid, new RegionPurge(folder.resolve("plugin"))).createIsland(SLOT).join();
        }

        verify(snapshot, never()).getBlockType(anyInt(), anyInt(), anyInt());
        verify(template, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
}
