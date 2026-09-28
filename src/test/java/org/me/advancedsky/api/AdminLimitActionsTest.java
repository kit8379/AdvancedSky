package org.me.advancedsky.api;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.island.PlayerHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Invitation;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.*;

class AdminLimitActionsTest {
    @Test
    void adminBypassAndPlayerIdentityReachHomeAndMembershipHandlers() {
        UUID player = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        World world = mock(World.class);
        Location location = new Location(world, 0, 70, 0);
        PlayerHandler members = mock(PlayerHandler.class);
        HomeHandler homes = mock(HomeHandler.class);
        when(members.addMember(any(), any(), any(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(homes.setHome(any(), any(), anyString(), any(Location.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(members.getPendingInvite(player)).thenReturn(CompletableFuture.completedFuture(Optional.of(new Invitation(island, UUID.randomUUID()))));
        when(members.removePendingInvite(player)).thenReturn(CompletableFuture.completedFuture(null));

        AdminActions admin = new AdminActions("console", null, members, homes, null, null, null, null, null);
        admin.addMember(island, player).join();
        admin.setHome(player, "farm", location).join();
        verify(members).addMember(new Actor.Bypass("console"), island, player, "member");
        verify(homes).setHome(new Actor.Bypass("console"), player, "farm", location);

        PlayerActions normal = new PlayerActions(player, null, members, homes, null, null, null, null, null);
        normal.acceptInvite().join();
        normal.setHome("farm", location).join();
        verify(members).addMember(new Actor.Player(player), island, player, "member");
        verify(homes).setHome(new Actor.Player(player), player, "farm", location);
    }
}
