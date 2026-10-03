package org.me.advancedsky.api;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerActionsOrderTest {

    @Test
    void aNewIslandCanBeCreatedWhileThePlayersOldIslandIsStillBeingCleared() {
        UUID player = UUID.randomUUID();
        UUID oldIsland = UUID.randomUUID();
        UUID newIsland = UUID.randomUUID();
        AdvancedSky plugin = mock(AdvancedSky.class);
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        DatabaseHandler database = mock(DatabaseHandler.class);
        WorldHandler world = mock(WorldHandler.class);
        IslandCache cache = mock(IslandCache.class);
        CompletableFuture<Void> oldCleanup = new CompletableFuture<>();
        when(database.getIslandSlot(oldIsland)).thenReturn(Optional.of(5));
        when(world.deleteIsland(5)).thenReturn(oldCleanup);
        when(database.addIslandData(newIsland, player, "host")).thenReturn(6);
        when(world.deleteIsland(6)).thenReturn(CompletableFuture.completedFuture(null));
        when(world.createIsland(6)).thenReturn(CompletableFuture.completedFuture(null));
        when(cache.load(newIsland)).thenReturn(CompletableFuture.completedFuture(null));
        when(database.getIslandServer(oldIsland)).thenReturn(Optional.of("host"));
        IslandOperator lifecycle = new IslandOperator(plugin, database, world, cache, null, null, null, "host");
        CoreHandler core = mock(CoreHandler.class);
        when(core.getOwnIslandHere(player)).thenReturn(CompletableFuture.completedFuture(oldIsland));
        when(core.deleteIsland(any(Actor.class), eq(oldIsland))).thenAnswer(call -> lifecycle.deleteIsland(call.getArgument(0), oldIsland));
        when(core.createIsland(player)).thenAnswer(call -> lifecycle.createIsland(newIsland, player));
        ConcurrentHashMap<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

        new PlayerActions(player, chains, core, null, null, null, null, null, null, null).deleteIsland().join();
        CompletableFuture<Void> created = new PlayerActions(player, chains, core, null, null, null, null, null, null, null).createIsland();

        assertTrue(created.isDone());
        created.join();
        assertFalse(oldCleanup.isDone());
        verify(database).finishIslandCreation(newIsland);
        verify(database, never()).finishIslandDeletion(oldIsland);
        oldCleanup.complete(null);
        verify(database).finishIslandDeletion(oldIsland);
        verify(cache).unload(oldIsland);
        verify(cache, times(1)).unload(newIsland); // The one reset during its own creation only.
    }

    @Test
    void aHomeDeletedRightAfterItWasSetWaitsForTheSet() {
        UUID player = UUID.randomUUID();
        CoreHandler core = mock(CoreHandler.class);
        when(core.getOwnIslandHere(player)).thenReturn(CompletableFuture.completedFuture(UUID.randomUUID()));
        HomeHandler homes = mock(HomeHandler.class);
        CompletableFuture<Void> set = new CompletableFuture<>();
        Location location = mock(Location.class);
        when(homes.setHome(any(Actor.class), eq(player), eq("farm"), eq(location))).thenReturn(set);
        when(homes.delHome(any(UUID.class), eq(player), eq("farm"))).thenReturn(CompletableFuture.completedFuture(null));
        ConcurrentHashMap<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

        // Two commands, each with a handle of its own, as the commands get them.
        new PlayerActions(player, chains, core, null, homes, null, null, null, null, null).setHome("farm", location);
        CompletableFuture<Void> deleted = new PlayerActions(player, chains, core, null, homes, null, null, null, null, null).deleteHome("farm");
        verify(homes, never()).delHome(any(UUID.class), eq(player), eq("farm"));

        set.complete(null);
        verify(homes).delHome(any(UUID.class), eq(player), eq("farm"));
        deleted.join();
    }
}
