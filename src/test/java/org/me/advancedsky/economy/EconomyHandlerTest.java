package org.me.advancedsky.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyHandlerTest {
    @Test
    @SuppressWarnings("unchecked")
    void allProviderCallsWaitForTheGlobalRegionExecutor() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        Queue<Runnable> globalTasks = new ArrayDeque<>();
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> globalTasks.add(call.getArgument(0))).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        Economy provider = mock(Economy.class);
        ServicesManager services = mock(ServicesManager.class);
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        when(registration.getProvider()).thenReturn(provider);
        UUID uuid = UUID.randomUUID();
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(provider.getBalance(player)).thenReturn(100.0);
        when(provider.format(25.0)).thenReturn("$25");
        when(provider.withdrawPlayer(player, 25.0)).thenReturn(new EconomyResponse(25, 75, EconomyResponse.ResponseType.SUCCESS, null));
        when(provider.depositPlayer(player, 25.0)).thenReturn(new EconomyResponse(0, 75, EconomyResponse.ResponseType.FAILURE, "rejected"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            bukkit.when(() -> Bukkit.getOfflinePlayer(uuid)).thenReturn(player);
            EconomyHandler handler = new EconomyHandler(plugin);
            CompletableFuture<Double> balance = handler.getBalance(uuid);
            CompletableFuture<String> formatted = handler.format(25);
            CompletableFuture<Void> withdrawn = handler.withdraw(uuid, 25);
            CompletableFuture<Boolean> deposited = handler.deposit(uuid, 25);

            assertFalse(balance.isDone());
            assertFalse(formatted.isDone());
            assertFalse(withdrawn.isDone());
            assertFalse(deposited.isDone());
            verifyNoInteractions(provider);
            bukkit.verify(Bukkit::getServicesManager, never());
            bukkit.verify(() -> Bukkit.getOfflinePlayer(uuid), never());
            while (!globalTasks.isEmpty()) globalTasks.remove().run();

            assertEquals(100.0, balance.join());
            assertEquals("$25", formatted.join());
            assertNull(withdrawn.join());
            assertFalse(deposited.join());
        }
    }

    @Test
    void missingProviderFailsTheReturnedFuture() {
        AdvancedSky plugin = mock(AdvancedSky.class);
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(mock(ServicesManager.class));
            CompletionException error = assertThrows(CompletionException.class, () -> new EconomyHandler(plugin).getBalance(UUID.randomUUID()).join());
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals("No Vault economy provider is registered", error.getCause().getMessage());
        }
    }
}
