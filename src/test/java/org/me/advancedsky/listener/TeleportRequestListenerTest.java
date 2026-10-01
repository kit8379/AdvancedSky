package org.me.advancedsky.listener;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.teleport.TeleportHandler;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

class TeleportRequestListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void aTeleportStoredWhileThePlayerWasLoggingInRunsATickAfterTheirJoin() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        TeleportHandler teleports = new TeleportHandler();
        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        Location target = mock(Location.class);
        when(target.isWorldLoaded()).thenReturn(true);
        teleports.addPendingTeleport(playerUuid, target);

        new TeleportRequestListener(plugin, teleports).onJoin(new PlayerJoinEvent(player, Component.empty()));

        ArgumentCaptor<Consumer<ScheduledTask>> task = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runDelayed(eq(plugin), task.capture(), isNull(), eq(1L));
        verify(player, never()).teleportAsync(any());
        task.getValue().accept(mock(ScheduledTask.class));
        verify(player).teleportAsync(target);
    }
}
