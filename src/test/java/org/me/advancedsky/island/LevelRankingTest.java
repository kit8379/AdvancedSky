package org.me.advancedsky.island;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.IslandTop;
import org.me.advancedsky.thread.BukkitAsyncExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LevelRankingTest {
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final LevelHandler levels;

    LevelRankingTest() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(database.getIslandRanking()).thenReturn(List.of(top(first, 30), top(second, 20), top(third, 0)));
        when(database.getIslandMembers(List.of(first, second))).thenReturn(Map.of(first, Set.of(member)));
        levels = new LevelHandler(plugin, mock(ConfigHandler.class), database, null, null, null, null);
    }

    @Test
    void ranksAndTheTopListShareOneRead() {
        assertEquals(1L, levels.getIslandRank(first).join());
        assertEquals(3L, levels.getIslandRank(third).join());

        List<IslandTop> top = levels.getTopIslandLevels(2).join();
        assertEquals(List.of(first, second), top.stream().map(IslandTop::getIslandUuid).toList());
        assertEquals(Set.of(member), top.getFirst().getMembers());
        assertEquals(Set.of(), top.get(1).getMembers());

        verify(database, times(1)).getIslandRanking();
    }

    @Test
    void anIslandMissingFromTheRankingHasNoRank() {
        CompletionException error = assertThrows(CompletionException.class, () -> levels.getIslandRank(UUID.randomUUID()).join());
        assertInstanceOf(IslandDoesNotExistException.class, error.getCause());
    }

    private static IslandTop top(UUID island, int level) {
        return new IslandTop(island, UUID.randomUUID(), level, Set.of());
    }
}
