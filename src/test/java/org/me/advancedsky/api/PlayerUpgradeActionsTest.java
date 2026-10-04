package org.me.advancedsky.api;

import org.junit.jupiter.api.Test;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.UpgradeHandler;
import org.me.advancedsky.model.Actor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerUpgradeActionsTest {
    private final UUID player = UUID.randomUUID();
    private final UUID island = UUID.randomUUID();
    private final CoreHandler core = mock(CoreHandler.class);
    private final UpgradeHandler upgrades = mock(UpgradeHandler.class);
    private final PlayerActions actions = new PlayerActions(player, new ConcurrentHashMap<>(), core, null, null, null, null, null, null, upgrades);

    @Test
    void directApiPurchasesRejectPlayersElsewhereBeforeCharging() {
        when(core.getOwnIslandHere(player)).thenReturn(CompletableFuture.failedFuture(new PlayerNotOnOwnIslandException()));
        Throwable cause = assertThrows(CompletionException.class, () -> actions.buyUpgrade("team-limit").join()).getCause();
        assertInstanceOf(PlayerNotOnOwnIslandException.class, cause);
        verifyNoInteractions(upgrades);
        verify(core, never()).getIslandUuid(any());
    }

    @Test
    void directApiPurchasesUseTheIslandAtThePlayersCurrentLocation() {
        when(core.getOwnIslandHere(player)).thenReturn(CompletableFuture.completedFuture(island));
        when(upgrades.buyUpgrade(new Actor.Player(player), island, player, "team-limit")).thenReturn(CompletableFuture.completedFuture(2));
        assertEquals(2, actions.buyUpgrade("team-limit").join());
        verify(upgrades).buyUpgrade(new Actor.Player(player), island, player, "team-limit");
        verify(core, never()).getIslandUuid(any());
    }
}
