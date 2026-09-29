package org.me.advancedsky.listener;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

class IslandBorderListenerTest {
    private static final int SLOT = 2;
    private final IslandGrid grid = new IslandGrid(64);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final WorldBorder border = mock(WorldBorder.class);
    private final Location center = grid.center(world, SLOT);
    private final IslandBorderListener listener = new IslandBorderListener(config, cache, grid);

    IslandBorderListenerTest() {
        when(world.getName()).thenReturn("islands");
        when(config.getIslandWorldName()).thenReturn("islands");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Island island = new Island(UUID.randomUUID(), SLOT, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(), Map.of(), null, 1, 1);
        when(cache.getAt(any(Location.class))).thenReturn(island);
    }

    @Test
    void anOddSizeIsCentredOnTheMiddleOfTheCentreBlock() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(75);
        withBorders(() -> listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center)));

        verify(border).setCenter(center.getBlockX() + 0.5, center.getBlockZ() + 0.5);
        verify(border).setSize(75);
    }

    @Test
    void anEvenSizeIsCentredOnTheCornerOfTheCentreBlock() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(100);
        withBorders(() -> listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center)));

        verify(border).setCenter(center.getBlockX(), center.getBlockZ());
    }

    @Test
    void aRespawnSendsTheSameBorderAgain() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(75);
        withBorders(() -> {
            listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center));
            listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center));
            listener.onPlayerPostRespawn(new PlayerPostRespawnEvent(player, center, false, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        });

        // The second teleport found it already shown; the respawn did not.
        verify(player, times(2)).setWorldBorder(border);
    }

    private void withBorders(Runnable run) {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::createWorldBorder).thenReturn(border);
            run.run();
        }
    }
}
