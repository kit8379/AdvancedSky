package org.me.advancedsky.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

class IslandProtectionListenerTest {
    private static final int SLOT = 2;
    private static final int SIZE = 75;
    private final IslandGrid grid = new IslandGrid(64);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final World world = mock(World.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID banned = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final Location center = grid.center(world, SLOT);
    // Just outside the west edge of the boundary, and just inside it.
    private final Location outside = center.clone().add(-SIZE / 2 - 1, 0, 0);
    private final Location inside = center.clone().add(-SIZE / 2, 0, 0);
    private final IslandProtectionListener listener = new IslandProtectionListener(config, cache, grid);

    IslandProtectionListenerTest() {
        when(world.getName()).thenReturn("islands");
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(SIZE);
    }

    @Test
    void stepsOntoALockedIslandFromOutsideAreRefusedToOutsiders() {
        island(true, Set.of());

        assertTrue(move(visitor, false, outside, inside));
        assertFalse(move(member, false, outside, inside));
        assertFalse(move(owner, false, outside, inside));
        assertFalse(move(visitor, true, outside, inside));
    }

    @Test
    void bannedPlayersCannotStepOntoAnUnlockedIsland() {
        island(false, Set.of(banned));

        assertTrue(move(banned, false, outside, inside));
        assertFalse(move(visitor, false, outside, inside));
    }

    @Test
    void movingWithinOrOffTheBoundaryIsNotAnEntry() {
        island(true, Set.of(banned));

        assertFalse(move(banned, false, inside, inside.clone().add(1, 0, 0)));
        assertFalse(move(banned, false, inside, outside));
    }

    private void island(boolean locked, Set<UUID> bans) {
        Island island = new Island(UUID.randomUUID(), SLOT, locked, false, owner, Set.of(member), Set.of(), bans, Map.of(), null, 1, 1);
        when(cache.getAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return grid.slotAt(location.getX(), location.getZ()) == SLOT ? island : null;
        });
    }

    /**
     * Whether the move was cancelled.
     */
    private boolean move(UUID playerUuid, boolean op, Location from, Location to) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOp()).thenReturn(op);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        listener.onPlayerMove(event);
        return event.isCancelled();
    }
}
