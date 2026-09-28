package org.me.advancedsky.api;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.WarpHandler;
import org.me.advancedsky.model.Actor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

class WarpActionsTest {
    private static final String WORLD = "islands";

    @Test
    void playerWritesResolveOwnIslandAndKeepActorIdentity() {
        UUID player = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        CoreHandler core = mock(CoreHandler.class);
        WarpHandler warps = mock(WarpHandler.class);
        when(core.getIslandUuid(player)).thenReturn(CompletableFuture.completedFuture(island));
        when(warps.setWarp(any(), any(), anyString(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(warps.delWarp(any(), any(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        PlayerActions actions = new PlayerActions(player, core, null, null, warps, null, null, null, null);

        actions.setWarp("shop", WORLD, 1, 2, 3, 0, 0).join();
        actions.deleteWarp("shop").join();

        verify(warps).setWarp(new Actor.Player(player), island, "shop", WORLD, 1, 2, 3, 0, 0);
        verify(warps).delWarp(new Actor.Player(player), island, "shop");
    }

    @Test
    void playerVisitUsesExplicitDestinationIsland() {
        UUID player = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        CoreHandler core = mock(CoreHandler.class);
        WarpHandler warps = mock(WarpHandler.class);
        when(warps.warp(island, "shop", player)).thenReturn(CompletableFuture.completedFuture(null));
        PlayerActions actions = new PlayerActions(player, core, null, null, warps, null, null, null, null);

        actions.warp(island, "shop").join();

        verify(warps).warp(island, "shop", player);
        verifyNoInteractions(core);
    }

    @Test
    void adminUsesExplicitIslandWithoutPlayerLookup() {
        UUID island = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        CoreHandler core = mock(CoreHandler.class);
        WarpHandler warps = mock(WarpHandler.class);
        when(warps.setWarp(any(), any(), anyString(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(warps.delWarp(any(), any(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(warps.warp(any(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        AdminActions actions = new AdminActions("test", core, null, null, warps, null, null, null, null);

        actions.setWarp(island, "shop", WORLD, 1, 2, 3, 0, 0).join();
        actions.deleteWarp(island, "shop").join();
        actions.warp(island, "shop", traveler).join();

        verify(warps).setWarp(new Actor.Bypass("test"), island, "shop", WORLD, 1, 2, 3, 0, 0);
        verify(warps).delWarp(new Actor.Bypass("test"), island, "shop");
        verify(warps).warp(island, "shop", traveler);
        verifyNoInteractions(core);
    }
}
