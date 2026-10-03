package org.me.advancedsky.island;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreHandlerLocationTest {
    private final UUID playerId = UUID.randomUUID();
    private final UUID islandId = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final Location location = new Location(world, 0, 70, 0);
    private final EntityScheduler scheduler = mock(EntityScheduler.class);
    private final CoreHandler core = new CoreHandler(plugin, config, database, null, cache, null);

    @BeforeEach
    void setUp() {
        when(config.getIslandWorldName()).thenReturn("islands");
        when(world.getName()).thenReturn("islands");
        when(player.getLocation()).thenReturn(location);
        when(player.getScheduler()).thenReturn(scheduler);
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
    }

    @Test
    void ownersAndMembersCanManageTheIslandTheyStandOnWithoutDatabaseReads() {
        for (Island island : new Island[]{island(playerId, Set.of(), Set.of()), island(UUID.randomUUID(), Set.of(playerId), Set.of())}) {
            when(cache.getAt(location)).thenReturn(island);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
                assertEquals(islandId, core.getOwnIslandHere(playerId).join());
            }
        }
        verifyNoInteractions(database);
    }

    @Test
    void visitorsAndCoopsCannotTreatSomeoneElsesIslandAsTheirOwn() {
        for (Island island : new Island[]{island(UUID.randomUUID(), Set.of(), Set.of()), island(UUID.randomUUID(), Set.of(), Set.of(playerId))}) {
            when(cache.getAt(location)).thenReturn(island);
            assertNull(core.getOwnIslandAt(playerId, location));
        }
    }

    @Test
    void otherWorldsAndUnavailableOrDeletingIslandsAreRejected() {
        when(cache.getAt(location)).thenReturn(island(playerId, Set.of(), Set.of()));
        when(world.getName()).thenReturn("lobby");
        assertNull(core.getOwnIslandAt(playerId, location));
        when(world.getName()).thenReturn("islands");
        when(cache.getAt(location)).thenReturn(null);
        assertNull(core.getOwnIslandAt(playerId, location));
    }

    @Test
    void anOfflineOrRetiredPlayerCannotManageAnIsland() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertInstanceOf(PlayerNotOnOwnIslandException.class, assertThrows(CompletionException.class, () -> core.getOwnIslandHere(playerId).join()).getCause());
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            when(scheduler.run(eq(plugin), any(), any())).thenReturn(null);
            assertInstanceOf(PlayerNotOnOwnIslandException.class, assertThrows(CompletionException.class, () -> core.getOwnIslandHere(playerId).join()).getCause());
        }
        verifyNoInteractions(database);
    }

    private Island island(UUID owner, Set<UUID> members, Set<UUID> coops) {
        return new Island(islandId, 1, false, false, owner, members, coops, Set.of(), Map.of(), null, 1, 1);
    }
}
