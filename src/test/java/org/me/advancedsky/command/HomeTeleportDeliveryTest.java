package org.me.advancedsky.command;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.ServerRegistry;
import org.me.advancedsky.api.AdvancedSkyAPI;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.command.player.PlayerHomeCommand;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.listener.TeleportRequestListener;
import org.me.advancedsky.message.PlayerMessageHandler;
import org.me.advancedsky.messaging.CrossServerMessenger;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.me.advancedsky.util.ComponentUtils;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HomeTeleportDeliveryTest {
    @Test
    void homeConfirmationTravelsToTheDestinationDespiteAStaleSourceRegistry() {
        UUID playerId = UUID.randomUUID(), islandId = UUID.randomUUID(), worldId = UUID.randomUUID();
        AdvancedSky sourcePlugin = mock(AdvancedSky.class), destinationPlugin = mock(AdvancedSky.class);
        ConfigHandler config = mock(ConfigHandler.class);
        DatabaseHandler database = mock(DatabaseHandler.class);
        OnlinePlayerRegistry registry = mock(OnlinePlayerRegistry.class);
        CrossServerMessenger messenger = mock(CrossServerMessenger.class);
        PlayerMessageHandler sourceMessages = mock(PlayerMessageHandler.class);
        Player sourcePlayer = mock(Player.class), destinationPlayer = mock(Player.class);
        World world = mock(World.class);
        Component message = Component.text("Teleported to home ", NamedTextColor.GREEN)
                .append(Component.text("default", NamedTextColor.AQUA));
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(sourcePlugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(sourcePlugin.getGlobalRegionExecutor()).thenReturn(global);
        when(destinationPlugin.getGlobalRegionExecutor()).thenReturn(global);
        when(sourcePlayer.getUniqueId()).thenReturn(playerId);
        when(destinationPlayer.getUniqueId()).thenReturn(playerId);
        when(destinationPlayer.isOnline()).thenReturn(true);
        when(world.getUID()).thenReturn(worldId);
        when(config.getIslandWorldName()).thenReturn("islands");
        when(config.getPlayerHomeSuccessMessage("default")).thenReturn(message);
        when(database.getIslandUuid(playerId)).thenReturn(Optional.of(islandId));
        when(database.getIslandHomes(islandId, playerId)).thenReturn(Map.of("default", "1.5,70,1.5,0,0"));
        when(database.getIslandServer(islandId)).thenReturn(Optional.of("sky1"));
        when(registry.isOnline(playerId)).thenReturn(true);
        when(registry.getOnlinePlayerServer(playerId)).thenReturn("lobby");
        EntityScheduler sourceScheduler = mock(EntityScheduler.class), destinationScheduler = mock(EntityScheduler.class);
        when(sourcePlayer.getScheduler()).thenReturn(sourceScheduler);
        when(destinationPlayer.getScheduler()).thenReturn(destinationScheduler);
        when(sourceScheduler.run(eq(sourcePlugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        AtomicReference<Consumer<ScheduledTask>> joinTask = new AtomicReference<>();
        when(destinationScheduler.runDelayed(eq(destinationPlugin), any(), any(), eq(1L))).thenAnswer(call -> {
            joinTask.set(call.getArgument(1));
            return mock(ScheduledTask.class);
        });
        TeleportHandler destination = new TeleportHandler(destinationPlugin, null, null);
        AtomicReference<Player> visiblePlayer = new AtomicReference<>(sourcePlayer);
        when(messenger.request(eq("sky1"), eq(IslandDistributor.ACTION_ISLAND_TELEPORT_PREPARE), any())).thenAnswer(call -> {
            JSONObject payload = call.getArgument(2);
            visiblePlayer.set(null); // The player is still on the source while the destination prepares.
            try {
                return destination.prepareTeleport(UUID.fromString(payload.getString("playerUuid")),
                        payload.getString("teleportWorld"), payload.getString("teleportLocation"),
                        ComponentUtils.deserialize(payload.getString("successMessage")))
                        .thenApply(teleported -> new JSONObject().put("teleported", teleported));
            } finally {
                visiblePlayer.set(sourcePlayer);
            }
        });
        IslandDistributor distributor = new IslandDistributor(sourcePlugin, database, null, registry, messenger, mock(ServerRegistry.class), "lobby");
        HomeHandler homes = new HomeHandler(sourcePlugin, config, database, distributor, registry, null, null);
        AdvancedSkyAPI api = new AdvancedSkyAPI(sourcePlugin, null, null, homes, null, null, null, null, null, sourceMessages, null, null, null);
        TeleportRequestListener listener = new TeleportRequestListener(destinationPlugin, destination);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenAnswer(call -> visiblePlayer.get());
            bukkit.when(() -> Bukkit.getWorld("sky1")).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            assertTrue(new PlayerHomeCommand(sourcePlugin, api, config).execute(sourcePlayer, new String[]{"home"}));
            verify(sourcePlayer).sendPluginMessage(eq(sourcePlugin), eq("BungeeCord"), any(byte[].class));
            verify(sourcePlayer, never()).sendMessage(any(Component.class));
            verify(destinationPlayer, never()).sendMessage(any(Component.class));
            verifyNoInteractions(sourceMessages);

            var pending = destination.takeSpawnTeleport(playerId);
            assertNotNull(pending);
            assertEquals(message, pending.successMessage());
            when(destinationPlayer.getLocation()).thenReturn(pending.location());
            visiblePlayer.set(destinationPlayer);
            listener.onJoin(new PlayerJoinEvent(destinationPlayer, Component.empty()));
            verify(destinationPlayer, never()).sendMessage(any(Component.class));
            assertNotNull(joinTask.get());
            joinTask.get().accept(mock(ScheduledTask.class));
            joinTask.get().accept(mock(ScheduledTask.class));
            listener.onJoin(new PlayerJoinEvent(destinationPlayer, Component.empty()));

            verify(destinationPlayer, times(1)).sendMessage(message);
            verify(sourcePlayer, never()).sendMessage(any(Component.class));
            verifyNoInteractions(sourceMessages);
            verify(registry, times(1)).getOnlinePlayerServer(playerId);
            verify(messenger, never()).requestVoid(any(), eq(PlayerMessageHandler.ACTION_PLAYER_MESSAGE_SEND), any());
        }
    }
}
