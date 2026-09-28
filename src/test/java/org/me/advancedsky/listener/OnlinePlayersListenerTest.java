package org.me.advancedsky.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class OnlinePlayersListenerTest {

    @Test
    void aQuitIsWrittenOnlyOnceTheJoinBeforeItHasBeen() throws Exception {
        AdvancedSky plugin = mock(AdvancedSky.class);
        List<Runnable> queued = new ArrayList<>();
        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> queued.add(call.getArgument(0))).when(executor).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        OnlinePlayerRegistry registry = mock(OnlinePlayerRegistry.class);
        OnlinePlayersListener listener = new OnlinePlayersListener(plugin, registry, "sky1");
        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("Steve");

        // A relog here: the new join comes right behind the quit of the old session.
        invoke(listener, "onPlayerQuit", new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        invoke(listener, "onPlayerJoin", new PlayerJoinEvent(player, Component.empty()));
        assertEquals(1, queued.size());

        queued.removeFirst().run();
        assertEquals(1, queued.size());
        queued.removeFirst().run();

        InOrder order = inOrder(registry);
        order.verify(registry).removeOnlinePlayer(playerUuid, "sky1");
        order.verify(registry).addOnlinePlayer(playerUuid, "Steve", "sky1");
    }

    private static void invoke(OnlinePlayersListener listener, String handler, Object event) throws Exception {
        Method method = OnlinePlayersListener.class.getDeclaredMethod(handler, event.getClass());
        method.setAccessible(true);
        method.invoke(listener, event);
    }
}
