package org.me.advancedsky.world;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
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

    @Test
    @SuppressWarnings("unchecked")
    void deleteClearsTheAreaOnlyOnceTheEvacuatedPlayersHaveLeftIt() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getTemplateWorldName()).thenReturn("template");
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(plugin.getApi()).thenReturn(api);
        when(api.lobby(playerUuid)).thenReturn(CompletableFuture.completedFuture(null));

        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(playerUuid);
        Location onIsland = grid.center(world, SLOT);
        Location elsewhere = grid.center(world, SLOT + 1);
        // Still on the island when sent away and at the first look; gone by the second.
        when(player.getLocation()).thenReturn(onIsland, onIsland, elsewhere);
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(false))).thenReturn(CompletableFuture.completedFuture(null));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("islands")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("template")).thenReturn(mock(World.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(player));

            new WorldHandler(plugin, config, grid).deleteIsland(SLOT).join();
        }

        // Sent away, looked for twice, and only then is the first chunk touched.
        InOrder order = inOrder(api, player, world);
        order.verify(api).lobby(playerUuid);
        order.verify(player, times(2)).getLocation();
        order.verify(world, atLeastOnce()).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        verify(api, times(1)).lobby(playerUuid);
    }

    @Test
    void deleteRemovesABuiltUpChunkAFixedNumberOfBlocksPerTick() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getTemplateWorldName()).thenReturn("template");
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(64);

        // Two full sections and one block more, in the chunk under the island centre.
        boolean[][][] solid = new boolean[64][16][16];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    solid[y][x][z] = true;
                }
            }
        }
        solid[40][0][0] = true;
        Chunk chunk = builtUpChunk(solid);
        Location center = grid.center(world, SLOT);
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(false))).thenAnswer(call -> CompletableFuture.completedFuture(call.<Integer>getArgument(0) == center.getBlockX() >> 4 && call.<Integer>getArgument(1) == center.getBlockZ() >> 4 ? chunk : null));

        // Region tasks run at the next tick, each tick taking the tasks queued before it began.
        List<Consumer<ScheduledTask>> queued = new ArrayList<>();
        RegionScheduler regions = mock(RegionScheduler.class);
        doAnswer(call -> {
            queued.add(call.getArgument(4));
            return mock(ScheduledTask.class);
        }).when(regions).run(eq(plugin), any(World.class), anyInt(), anyInt(), any());

        int mostInOneTick = 0;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("islands")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("template")).thenReturn(mock(World.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of());
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regions);

            CompletableFuture<Void> deleted = new WorldHandler(plugin, config, grid).deleteIsland(SLOT);
            for (int tick = 0; !deleted.isDone(); tick++) {
                assertTrue(tick < 10, "the area is not cleared after " + tick + " ticks");
                List<Consumer<ScheduledTask>> due = new ArrayList<>(queued);
                queued.clear();
                int before = count(solid);
                due.forEach(task -> task.accept(mock(ScheduledTask.class)));
                mostInOneTick = Math.max(mostInOneTick, before - count(solid));
            }
            deleted.join();
        }

        assertEquals(0, count(solid));
        assertEquals(4096, mostInOneTick);
    }

    /**
     * A chunk whose blocks are the solid cells; setting one to air empties its cell.
     */
    private Chunk builtUpChunk(boolean[][][] solid) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getEntities()).thenReturn(new Entity[0]);
        // Material.isAir looks up the server's block registry, which tests do not have.
        Material stone = mock(Material.class);
        Material air = mock(Material.class);
        when(air.isAir()).thenReturn(true);
        when(chunk.getChunkSnapshot(false, false, false, false)).thenAnswer(call -> {
            ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
            when(snapshot.isSectionEmpty(anyInt())).thenAnswer(section -> {
                int base = section.<Integer>getArgument(0) << 4;
                for (int y = base; y < base + 16; y++) {
                    for (boolean[] row : solid[y]) {
                        for (boolean cell : row) {
                            if (cell) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            });
            when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenAnswer(block -> solid[block.<Integer>getArgument(1)][block.<Integer>getArgument(0)][block.<Integer>getArgument(2)] ? stone : air);
            return snapshot;
        });
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0);
            int y = call.getArgument(1);
            int z = call.getArgument(2);
            Block block = mock(Block.class);
            doAnswer(set -> solid[y][x][z] = false).when(block).setType(Material.AIR, false);
            return block;
        });
        return chunk;
    }

    private static int count(boolean[][][] solid) {
        int count = 0;
        for (boolean[][] layer : solid) {
            for (boolean[] row : layer) {
                for (boolean cell : row) {
                    if (cell) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Test
    void thePasteLooksUpNoWorldBlockInSectionsHoldingOnlyAir() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getTemplateWorldName()).thenReturn("template");
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

            new WorldHandler(plugin, config, grid).createIsland(SLOT).join();
        }

        verify(snapshot, never()).getBlockType(anyInt(), anyInt(), anyInt());
        verify(template, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
}
