package org.me.advancedsky.network;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandOperatorLockTest {
    private static final int SLOT = 4;
    private final UUID island = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID coop = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final Actor actor = new Actor.Player(owner);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final WorldHandler worlds = mock(WorldHandler.class);
    private final IslandOperator operator = new IslandOperator(plugin, database, worlds, cache, null, null, null, "local");

    IslandOperatorLockTest() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(database.toggleIslandLock(actor, island)).thenReturn(true);
        when(database.getIslandSlot(island)).thenReturn(Optional.of(SLOT));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        when(worlds.removePlayersFromIsland(eq(SLOT), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void lockingEvictsByTheReloadedCacheWithoutReadingTheDatabase() {
        when(cache.get(island)).thenReturn(new Island(island, SLOT, true, false, owner, Set.of(member), Set.of(coop), Set.of(), Map.of(), null, 1, 1));

        assertTrue(operator.toggleIslandLock(actor, island).join());

        Predicate<Player> evicts = capturedPredicate();
        assertFalse(evicts.test(player(owner)));
        assertFalse(evicts.test(player(member)));
        assertFalse(evicts.test(player(coop)));
        assertTrue(evicts.test(player(visitor)));
        verify(database, never()).getIslandPlayers(island);
        verify(database, never()).getIslandCoops(island);
    }

    @Test
    void lockingReportsAMissingCacheEntryBeforeEvictingPlayers() {
        when(cache.get(island)).thenReturn(null);
        CompletionException error = assertThrows(CompletionException.class, () -> operator.toggleIslandLock(actor, island).join());
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals("Island cache entry is missing after locking: " + island, error.getCause().getMessage());
        verifyNoInteractions(worlds);
        verify(database, never()).getIslandPlayers(island);
        verify(database, never()).getIslandCoops(island);
    }

    @Test
    void unlockingEvictsNobody() {
        when(database.toggleIslandLock(actor, island)).thenReturn(false);

        assertFalse(operator.toggleIslandLock(actor, island).join());

        verifyNoInteractions(worlds);
    }

    @SuppressWarnings("unchecked")
    private Predicate<Player> capturedPredicate() {
        ArgumentCaptor<Predicate<Player>> captor = ArgumentCaptor.forClass(Predicate.class);
        verify(worlds).removePlayersFromIsland(eq(SLOT), captor.capture());
        return captor.getValue();
    }

    private static Player player(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }
}
