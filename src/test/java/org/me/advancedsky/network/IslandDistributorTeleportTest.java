package org.me.advancedsky.network;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.ServerRegistry;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.me.advancedsky.util.ComponentUtils;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IslandDistributorTeleportTest {
    private final UUID playerUuid = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ServerRegistry servers = mock(ServerRegistry.class);
    private final OnlinePlayerRegistry registry = mock(OnlinePlayerRegistry.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final IslandDistributor distributor = new IslandDistributor(plugin, null, null, registry, messenger, servers, "sky1");

    IslandDistributorTeleportTest() {
        when(servers.getLobbyServers()).thenReturn(List.of("lobby"));
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        when(messenger.request(eq("lobby"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), any())).thenReturn(CompletableFuture.completedFuture(new JSONObject().put("teleported", false)));
    }

    @Test
    void aSameHostTeleportCallsTheTeleportHandlerWithoutProxyRouting() {
        TeleportHandler teleports = mock(TeleportHandler.class);
        IslandDistributor local = new IslandDistributor(plugin, null, teleports, registry, messenger, servers, "sky1");
        when(teleports.prepareTeleport(playerUuid, "world", "0,100,0,0,0")).thenReturn(CompletableFuture.completedFuture(true));

        when(servers.getLobbyServers()).thenReturn(List.of("sky1"));
        local.teleportLobby(playerUuid, "world", "0,100,0,0,0").join();

        verify(teleports).prepareTeleport(playerUuid, "world", "0,100,0,0,0");
        verifyNoInteractions(registry, messenger);
    }

    @Test
    void aSameHostTeleportGivesTheMessageToTheDestinationHandler() {
        TeleportHandler teleports = mock(TeleportHandler.class);
        IslandDistributor local = new IslandDistributor(plugin, null, teleports, registry, messenger, servers, "sky1");
        Component message = Component.text("Teleported to home ", NamedTextColor.GREEN)
                .append(Component.text("default", NamedTextColor.AQUA));
        when(teleports.prepareTeleport(playerUuid, "world", "0,100,0,0,0", message))
                .thenReturn(CompletableFuture.completedFuture(true));

        when(servers.getLobbyServers()).thenReturn(List.of("sky1"));
        local.teleportLobby(playerUuid, "world", "0,100,0,0,0", message).join();

        verify(teleports).prepareTeleport(playerUuid, "world", "0,100,0,0,0", message);
        verifyNoInteractions(registry, messenger);
    }

    @Test
    void aCrossServerTeleportCarriesTheStyledMessageWithItsDestination() {
        Component message = Component.text("Teleported to home ", NamedTextColor.GREEN)
                .append(Component.text("default", NamedTextColor.AQUA));
        when(registry.getOnlinePlayerServer(playerUuid)).thenReturn("lobby");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(null);
            distributor.teleportLobby(playerUuid, "world", "0,100,0,0,0", message).join();
        }

        ArgumentCaptor<JSONObject> request = ArgumentCaptor.forClass(JSONObject.class);
        verify(messenger).request(eq("lobby"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), request.capture());
        assertEquals(message, ComponentUtils.deserialize(request.getValue().getString("successMessage")));
        assertEquals(playerUuid.toString(), request.getValue().getString("playerUuid"));
        verify(messenger, never()).requestVoid(any(), eq("player.message.send"), any());
    }

    @Test
    void aMissingRegistryEntryFailsInsteadOfGuessingThePlayerServer() {
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(scheduler);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);
            var error = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> distributor.teleportLobby(playerUuid, "world", "0,100,0,0,0").join());
            assertInstanceOf(org.me.advancedsky.exceptions.PlayerNotOnlineException.class, error.getCause());
        }

        verifyNoInteractions(player);
    }

    @Test
    void aPlayerGoneFromTheServerTheRegistryStillNamesKeepsTheTeleportForTheirJoin() {
        when(registry.getOnlinePlayerServer(playerUuid)).thenReturn("sky1");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(null);
            distributor.teleportLobby(playerUuid, "world", "0,100,0,0,0").join();
        }

        verify(messenger, never()).requestVoid(any(), eq(IslandDistributor.ACTION_PLAYER_CONNECT), any());
    }

    @Test
    void aPlayerOnlineNowhereFailsWithAnExplicitOfflineError() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(null);
            var error = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> distributor.teleportLobby(playerUuid, "world", "0,100,0,0,0").join());
            assertInstanceOf(org.me.advancedsky.exceptions.PlayerNotOnlineException.class, error.getCause());
        }

        verify(messenger, never()).requestVoid(any(), eq(IslandDistributor.ACTION_PLAYER_CONNECT), any());
        ArgumentCaptor<JSONObject> request = ArgumentCaptor.forClass(JSONObject.class);
        verify(messenger).request(eq("lobby"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), request.capture());
        assertTrue(request.getValue().has("successMessage"));
        assertSame(JSONObject.NULL, request.getValue().get("successMessage"));
    }
}
