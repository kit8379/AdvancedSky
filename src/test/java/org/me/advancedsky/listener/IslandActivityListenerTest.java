package org.me.advancedsky.listener;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.scheduler.LevelUpdateScheduler;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class IslandActivityListenerTest {

    @Test
    void aPlayerMovedWithoutAnEventIsFoundOnTheirNewIslandAtTheNextLook() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getIslandWorldName()).thenReturn("islands");
        World world = mock(World.class);
        when(world.getName()).thenReturn("islands");
        Location onA = new Location(world, 256, 100, 256);
        Location onB = new Location(world, 2304, 100, 256);
        UUID islandA = UUID.randomUUID();
        UUID islandB = UUID.randomUUID();
        IslandCache cache = mock(IslandCache.class);
        when(cache.getIslandUuidAt(onA)).thenReturn(islandA);
        when(cache.getIslandUuidAt(onB)).thenReturn(islandB);
        LevelUpdateScheduler levels = mock(LevelUpdateScheduler.class);
        IslandActivityListener listener = new IslandActivityListener(plugin, config, cache, levels);

        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getLocation()).thenReturn(onA);
        listener.onPlayerJoin(new PlayerJoinEvent(player, (Component) null));
        verify(levels).playerEnter(islandA);

        // Moved by Folia's teleportAsync, which fires no event.
        when(player.getLocation()).thenReturn(onB);
        EntityScheduler own = mock(EntityScheduler.class);
        when(own.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(own);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);
            listener.start();
            ArgumentCaptor<Consumer<ScheduledTask>> look = ArgumentCaptor.captor();
            verify(global).runAtFixedRate(eq(plugin), look.capture(), anyLong(), anyLong());
            look.getValue().accept(mock(ScheduledTask.class));
        }

        verify(levels).playerLeave(islandA);
        verify(levels).playerEnter(islandB);
    }
}
