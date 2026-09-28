package org.me.advancedsky.world;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
import static org.mockito.Mockito.*;

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
}
