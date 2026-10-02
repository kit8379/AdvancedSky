package org.me.advancedsky.network;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class IslandDistributorTeleportTest {
    private final UUID playerUuid = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final OnlinePlayerRegistry registry = mock(OnlinePlayerRegistry.class);
    private final CrossServerMessenger messenger = mock(CrossServerMessenger.class);
    private final IslandDistributor distributor = new IslandDistributor(plugin, null, null, registry, messenger, null, List.of("sky1"), "sky1");

    IslandDistributorTeleportTest() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        when(messenger.request(eq("lobby"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), any())).thenReturn(CompletableFuture.completedFuture(new JSONObject().put("teleported", false)));
    }

    @Test
    void aPlayerHereIsSentOnThoughTheirRegistryEntryIsNotWrittenYet() {
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(scheduler);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);
            distributor.teleportLobby(playerUuid, List.of("lobby"), "world", "0,100,0,0,0").join();
        }

        verify(player).sendPluginMessage(eq(plugin), eq("BungeeCord"), any());
    }

    @Test
    void aPlayerGoneFromTheServerTheRegistryStillNamesKeepsTheTeleportForTheirJoin() {
        when(registry.getOnlinePlayerServer(playerUuid)).thenReturn("sky1");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(null);
            distributor.teleportLobby(playerUuid, List.of("lobby"), "world", "0,100,0,0,0").join();
        }

        verify(messenger, never()).requestVoid(any(), eq(IslandDistributor.ACTION_PLAYER_CONNECT), any());
    }

    @Test
    void aPlayerOnlineNowhereKeepsTheTeleportForTheirNextJoin() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(null);
            distributor.teleportLobby(playerUuid, List.of("lobby"), "world", "0,100,0,0,0").join();
        }

        verify(messenger, never()).requestVoid(any(), eq(IslandDistributor.ACTION_PLAYER_CONNECT), any());
    }
}
