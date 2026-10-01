package org.me.advancedsky.island;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Upgrade;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.thread.GlobalRegionExecutor;
import org.mockito.MockedStatic;

import java.util.List;
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
    private final IslandDistributor distributor = mock(IslandDistributor.class);
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
        handler = new UpgradeHandler(plugin, config, database, distributor, new EconomyHandler(plugin));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void buyAsksTheHostToChargeAndWriteTheNextLevel() {
        when(distributor.buyUpgrade(actor, island, buyer, "coop-limit", 1, 2, 25000.0)).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(2, handler.buyUpgrade(actor, island, buyer, "coop-limit").join());

        verify(distributor).buyUpgrade(actor, island, buyer, "coop-limit", 1, 2, 25000.0);
        verifyNoInteractions(economy);
    }

    @Test
    void aFailedPurchaseReportsTheHostsCause() {
        when(distributor.buyUpgrade(actor, island, buyer, "coop-limit", 1, 2, 25000.0)).thenReturn(CompletableFuture.failedFuture(new InsufficientFundsException()));

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

        verifyNoInteractions(economy, distributor);
    }

    @Test
    void adminSetValidatesTheLevelAndCompareAndSetsFromTheCurrentOne() {
        when(distributor.setUpgradeLevel(any(), any(), anyString(), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        Actor admin = new Actor.Bypass("test");

        handler.setUpgradeLevel(admin, island, "coop-limit", 3).join();
        verify(distributor).setUpgradeLevel(admin, island, "coop-limit", 1, 3);

        assertFailure(UpgradeLevelDoesNotExistException.class, handler.setUpgradeLevel(admin, island, "coop-limit", 4));
        assertFailure(UpgradeDoesNotExistException.class, handler.setUpgradeLevel(admin, island, "fly", 1));
        verifyNoMoreInteractions(distributor);
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
        verifyNoInteractions(distributor);
    }

    private static void assertFailure(Class<? extends Throwable> expected, CompletableFuture<?> future) {
        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(expected, error.getCause());
    }
}
