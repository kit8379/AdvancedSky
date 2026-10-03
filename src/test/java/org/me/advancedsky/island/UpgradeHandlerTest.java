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
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Upgrade;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpgradeHandlerTest {
    private final UUID island = UUID.randomUUID();
    private final UUID buyer = UUID.randomUUID();
    private final Actor actor = new Actor.Player(buyer);
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
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

        when(config.isUpgrade("coop-limit")).thenReturn(true);
        when(config.getUpgradeLevels("coop-limit")).thenReturn(List.of(1, 2, 3));
        when(config.getUpgradeValue("coop-limit", 1)).thenReturn("1");
        when(config.getUpgradeValue("coop-limit", 2)).thenReturn("2");
        when(config.getUpgradeRequireLevel("coop-limit", 2)).thenReturn(100);
        when(config.getUpgradePrice("coop-limit", 2)).thenReturn(25000.0);
        when(database.getIslandUpgradeLevel(island, "coop-limit")).thenReturn(1);
        when(database.getIslandLevel(island)).thenReturn(150);
        when(economy.format(anyDouble())).thenAnswer(call -> "$" + call.<Double>getArgument(0));
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        when(economy.withdrawPlayer(payer, 25000.0)).thenReturn(new EconomyResponse(25000, 0, EconomyResponse.ResponseType.SUCCESS, null));
        EconomyHandler economyHandler = new EconomyHandler(plugin);
        IslandOperator operator = new IslandOperator(plugin, database, null, cache, null, economyHandler, null, "local");
        handler = new UpgradeHandler(plugin, config, database, operator, economyHandler);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void buyChargesAndWritesTheNextLevelLocally() {

        assertEquals(2, handler.buyUpgrade(actor, island, buyer, "coop-limit").join());

        verify(database).updateIslandUpgradeLevel(actor, island, "coop-limit", 1, 2);
        verify(economy).withdrawPlayer(payer, 25000.0);
        verify(cache).reload(island);
    }

    @Test
    void aFailedWithdrawalReportsItsCause() {
        when(economy.withdrawPlayer(payer, 25000.0)).thenReturn(new EconomyResponse(25000, 0, EconomyResponse.ResponseType.FAILURE, null));

        assertFailure(InsufficientFundsException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));
    }

    @Test
    void gatesFailBeforeAnyMoneyMoves() {
        when(database.getIslandLevel(island)).thenReturn(99);
        assertFailure(UpgradeIslandLevelTooLowException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));

        when(database.getIslandLevel(island)).thenReturn(150);
        when(database.getIslandUpgradeLevel(island, "coop-limit")).thenReturn(3);
        assertFailure(UpgradeMaxedException.class, handler.buyUpgrade(actor, island, buyer, "coop-limit"));

        assertFailure(UpgradeDoesNotExistException.class, handler.buyUpgrade(actor, island, buyer, "fly"));

        verifyNoInteractions(economy, cache);
    }

    @Test
    void adminSetValidatesTheLevelAndCompareAndSetsFromTheCurrentOne() {
        Actor admin = new Actor.Bypass("test");

        handler.setUpgradeLevel(admin, island, "coop-limit", 3).join();
        verify(database).updateIslandUpgradeLevel(admin, island, "coop-limit", 1, 3);

        assertFailure(UpgradeLevelDoesNotExistException.class, handler.setUpgradeLevel(admin, island, "coop-limit", 4));
        assertFailure(UpgradeDoesNotExistException.class, handler.setUpgradeLevel(admin, island, "fly", 1));
        verify(database, times(1)).updateIslandUpgradeLevel(any(), any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void detailsCombineIslandStateWithTheViewersBalance() {
        when(economy.getBalance(payer)).thenReturn(30000.0);
        Upgrade details = handler.getUpgradeDetails(island, buyer, "coop-limit").join();
        assertEquals(new Upgrade(1, "1", false, 2, "2", 100, "$25000.0", true, 150, "$30000.0"), details);

        when(economy.getBalance(payer)).thenReturn(10.0);
        assertFalse(handler.getUpgradeDetails(island, buyer, "coop-limit").join().available());

        when(database.getIslandUpgradeLevel(island, "coop-limit")).thenReturn(3);
        when(config.getUpgradeValue("coop-limit", 3)).thenReturn("3");
        assertTrue(handler.getUpgradeDetails(island, buyer, "coop-limit").join().maxed());
        verifyNoInteractions(cache);
    }

    private static void assertFailure(Class<? extends Throwable> expected, CompletableFuture<?> future) {
        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(expected, error.getCause());
    }
}
