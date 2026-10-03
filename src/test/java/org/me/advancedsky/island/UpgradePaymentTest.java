package org.me.advancedsky.island;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.InsufficientFundsException;
import org.me.advancedsky.exceptions.UpgradeLevelChangedException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpgradePaymentTest {
    private final UUID island = UUID.randomUUID();
    private final UUID buyer = UUID.randomUUID();
    private final Actor actor = new Actor.Player(buyer);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final Economy economy = mock(Economy.class);
    private final OfflinePlayer payer = mock(OfflinePlayer.class);
    private MockedStatic<Bukkit> bukkit;
    private UpgradeHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        GlobalRegionExecutor global = mock(GlobalRegionExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(global).execute(any());
        when(plugin.getGlobalRegionExecutor()).thenReturn(global);
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(economy);
        ServicesManager services = mock(ServicesManager.class);
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);
        bukkit.when(() -> Bukkit.getOfflinePlayer(buyer)).thenReturn(payer);

        when(economy.withdrawPlayer(payer, 25000.0)).thenReturn(response(EconomyResponse.ResponseType.SUCCESS));
        when(economy.depositPlayer(payer, 25000.0)).thenReturn(response(EconomyResponse.ResponseType.SUCCESS));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.isUpgrade("coop-limit")).thenReturn(true);
        when(config.getUpgradeLevels("coop-limit")).thenReturn(List.of(1, 2));
        when(config.getUpgradePrice("coop-limit", 2)).thenReturn(25000.0);
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(database.getIslandUpgradeLevel(island, "coop-limit")).thenReturn(1);
        handler = new UpgradeHandler(plugin, config, database, new LocalIslandTasks(plugin, database, cache, "local"), new EconomyHandler(plugin));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void buyWithdrawsThenWritesTheNextLevel() {
        handler.buyUpgrade(actor, island, buyer, "coop-limit").join();

        InOrder order = inOrder(economy, database);
        order.verify(economy).withdrawPlayer(payer, 25000.0);
        order.verify(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);
        verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void failedWriteRefundsAndReportsTheCause() {
        doThrow(new UpgradeLevelChangedException()).when(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);

        assertFailure(UpgradeLevelChangedException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));

        verify(economy).withdrawPlayer(payer, 25000.0);
        verify(economy).depositPlayer(payer, 25000.0);
    }

    @Test
    void failedRefundLogsThePurchaseContextAndKeepsTheWriteFailure() {
        doThrow(new UpgradeLevelChangedException()).when(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);
        when(economy.depositPlayer(payer, 25000.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));

        assertFailure(UpgradeLevelChangedException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));
        verify(plugin).severe("Could not refund 25000.0 to " + buyer + " after a failed coop-limit upgrade purchase.");
    }

    @Test
    void aRefundThatThrowsIsLoggedWithThePurchaseContext() {
        doThrow(new UpgradeLevelChangedException()).when(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);
        when(economy.depositPlayer(payer, 25000.0)).thenThrow(new IllegalStateException("economy down"));

        assertFailure(UpgradeLevelChangedException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));
        verify(plugin).severe(eq("Could not refund 25000.0 to " + buyer + " after a failed coop-limit upgrade purchase."), any(Throwable.class));
    }

    @Test
    void aWriteWhoseCommitAnswerWasLostIsPaidForWhenItLanded() {
        doThrow(new RuntimeException("connection reset during commit")).when(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);
        when(database.getIslandUpgradeLevel(island, "coop-limit")).thenReturn(1, 2);

        assertDoesNotThrow(() -> handler.buyUpgrade(actor, island, buyer, "coop-limit").join());
        verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    @Test
    void aWriteWhoseCommitAnswerWasLostIsRefundedWhenItDidNotLand() {
        doThrow(new RuntimeException("connection reset during commit")).when(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);
        when(database.getIslandUpgradeLevel(island, "coop-limit")).thenReturn(1);

        assertFailure(RuntimeException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));
        verify(economy).depositPlayer(payer, 25000.0);
    }

    @Test
    void insufficientFundsWritesNothing() {
        when(economy.withdrawPlayer(payer, 25000.0)).thenReturn(response(EconomyResponse.ResponseType.FAILURE));

        assertFailure(InsufficientFundsException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));

        verify(database, never()).updateIslandUpgradeLevel(any(), any(), anyString(), anyInt(), anyInt());
        verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
    }

    private static EconomyResponse response(EconomyResponse.ResponseType type) {
        return new EconomyResponse(25000.0, 0, type, null);
    }

    private static void assertFailure(Class<? extends Throwable> expected, CompletableFuture<?> future) {
        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(expected, error.getCause());
    }
}
