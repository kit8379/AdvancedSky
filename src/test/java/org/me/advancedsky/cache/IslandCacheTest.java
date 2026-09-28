package org.me.advancedsky.cache;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

class IslandCacheTest {
    private static final int SLOT = 3;
    private final IslandGrid grid = new IslandGrid(64);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final UUID islandUuid = UUID.randomUUID();
    private final Island island = new Island(islandUuid, SLOT, false, false, UUID.randomUUID(), Set.of(), Set.of(), Set.of(), Map.of(), null, 1, 1);
    private final IslandCache cache;

    IslandCacheTest() {
        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        cache = new IslandCache(plugin, database, grid);
    }

    @Test
    void aFailedLoadIsRetriedUntilTheIslandIsAvailableAgain() throws Exception {
        when(database.getIslandCache(islandUuid)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(island);

        assertTrue(cache.load(islandUuid).isCompletedExceptionally());
        assertNull(cache.get(islandUuid));

        long deadline = System.currentTimeMillis() + 5_000L;
        while (cache.get(islandUuid) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertSame(island, cache.get(islandUuid));
        verify(database, times(2)).getIslandCache(islandUuid);
    }

    @Test
    void anIslandGoneFromTheDatabaseFreesItsSlot() {
        when(database.getIslandCachesByServer("sky1")).thenReturn(Map.of(islandUuid, island));
        cache.loadAll("sky1");
        assertEquals(islandUuid, cache.getIslandUuidAt(grid.center(null, SLOT)));

        when(database.getIslandCache(islandUuid)).thenReturn(null);
        assertTrue(cache.reload(islandUuid).isCompletedExceptionally());

        assertNull(cache.get(islandUuid));
        assertNull(cache.getIslandUuidAt(grid.center(null, SLOT)));
    }
}
