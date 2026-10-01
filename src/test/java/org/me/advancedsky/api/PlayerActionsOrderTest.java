package org.me.advancedsky.api;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.model.Actor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.*;

class PlayerActionsOrderTest {

    @Test
    void aHomeDeletedRightAfterItWasSetWaitsForTheSet() {
        UUID player = UUID.randomUUID();
        HomeHandler homes = mock(HomeHandler.class);
        CompletableFuture<Void> set = new CompletableFuture<>();
        Location location = mock(Location.class);
        when(homes.setHome(any(Actor.class), eq(player), eq("farm"), eq(location))).thenReturn(set);
        when(homes.delHome(player, "farm")).thenReturn(CompletableFuture.completedFuture(null));
        ConcurrentHashMap<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

        // Two commands, each with a handle of its own, as the commands get them.
        new PlayerActions(player, chains, null, null, homes, null, null, null, null, null).setHome("farm", location);
        CompletableFuture<Void> deleted = new PlayerActions(player, chains, null, null, homes, null, null, null, null, null).deleteHome("farm");
        verify(homes, never()).delHome(player, "farm");

        set.complete(null);
        verify(homes).delHome(player, "farm");
        deleted.join();
    }
}
