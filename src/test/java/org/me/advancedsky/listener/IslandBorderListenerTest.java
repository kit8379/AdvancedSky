package org.me.advancedsky.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void aDeathClearsTheBorderSoTheNextTeleportSendsItAgain() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(75);
        withBorders(() -> {
            listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center));
            listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center));
            listener.onPlayerDeath(new PlayerDeathEvent(player, mock(DamageSource.class), List.of(), 0, Component.empty(), false));
            listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center));
        });

        // The second teleport found it already shown; the one after the death did not.
        verify(player).setWorldBorder(null);
        verify(player, times(2)).setWorldBorder(border);
    }

    @Test
    void aWorldChangeOffTheIslandsClearsTheBorderLastSent() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(75);
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("nether");
        when(player.getLocation()).thenReturn(new Location(nether, 0, 70, 0));
        withBorders(() -> {
            listener.onPlayerTeleport(new PlayerTeleportEvent(player, center, center));
            listener.onWorldChange(new PlayerChangedWorldEvent(player, world));
        });

        verify(player).setWorldBorder(border);
        verify(player).setWorldBorder(null);
    }

    @Test
    void aRiderSteeringOutOfTheBoundaryIsPutBackWithTheMount() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(75);
        Vehicle mount = mock(Vehicle.class);
        when(player.getVehicle()).thenReturn(mount);
        when(mount.getPassengers()).thenReturn(List.of(player));
        Location inside = center.clone().add(-37, 0, 0);
        Location outside = center.clone().add(-38, 0, 0);

        PlayerMoveEvent event = new PlayerMoveEvent(player, inside, outside);
        listener.onPlayerMove(event);

        assertTrue(event.isCancelled());
        verify(mount).teleportAsync(inside, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void withBorders(Runnable run) {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::createWorldBorder).thenReturn(border);
            run.run();
        }
    }
}
