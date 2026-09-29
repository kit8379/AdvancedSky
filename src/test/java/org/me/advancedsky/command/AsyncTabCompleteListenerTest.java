package org.me.advancedsky.command;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncTabCompleteListenerTest {
    private final AsyncCommandTabRouter router = mock(AsyncCommandTabRouter.class);
    private final Player player = mock(Player.class);
    private final AsyncTabCompleteListener listener = new AsyncTabCompleteListener(mock(AdvancedSky.class));

    AsyncTabCompleteListenerTest() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        listener.registerRoot("is", router);
        when(router.completeAsync(eq(player), eq("is"), aryEq(new String[]{"ban", ""}))).thenReturn(CompletableFuture.completedFuture(List.of("Alice", "alex", "Bob")));
        when(router.completeAsync(eq(player), eq("is"), aryEq(new String[]{""}))).thenReturn(CompletableFuture.completedFuture(List.of("ban", "home")));
    }

    @Test
    void keystrokesInOneArgumentFilterTheCandidatesTheFirstOneLoaded() {
        assertEquals(List.of("Alice", "alex"), complete("/is ban a"));
        assertEquals(List.of("Alice"), complete("/is ban ali"));
        assertEquals(List.of("Bob"), complete("/is ban B"));

        verify(router, times(1)).completeAsync(any(), anyString(), any());
    }

    @Test
    void anotherArgumentLoadsItsOwnCandidates() {
        assertEquals(List.of("ban"), complete("/is b"));
        assertEquals(List.of("Alice", "alex", "Bob"), complete("/is ban "));

        verify(router, times(2)).completeAsync(any(), anyString(), any());
    }

    private List<String> complete(String buffer) {
        AsyncTabCompleteEvent event = new AsyncTabCompleteEvent(player, buffer, true, null);
        listener.onAsyncTabComplete(event);
        assertTrue(event.isHandled());
        return event.getCompletions();
    }
}
