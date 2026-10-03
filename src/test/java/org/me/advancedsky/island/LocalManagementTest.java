package org.me.advancedsky.island;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.network.IslandOperator;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.IslandNotHostedHereException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;
import org.me.advancedsky.world.WorldHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalManagementTest {
    private final UUID island = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final AdvancedSky plugin = mock(AdvancedSky.class);
    private final ConfigHandler config = mock(ConfigHandler.class);
    private final DatabaseHandler database = mock(DatabaseHandler.class);
    private final IslandCache cache = mock(IslandCache.class);
    private final IslandDistributor distributor = mock(IslandDistributor.class);
    private final WorldHandler world = mock(WorldHandler.class);
    private final OnlinePlayerRegistry online = mock(OnlinePlayerRegistry.class);
    private final EconomyHandler economy = mock(EconomyHandler.class);
    private final IslandOperator islandOperator = new IslandOperator(plugin, database, world, cache, null, economy, online, "local");
    private final CoreHandler core = new CoreHandler(plugin, config, database, distributor, cache, islandOperator);
    private final PlayerHandler members = new PlayerHandler(plugin, database, distributor, null, online, islandOperator);
    private final BanHandler bans = new BanHandler(plugin, database, islandOperator);
    private final CoopHandler coops = new CoopHandler(plugin, database, distributor, islandOperator);
    private final HomeHandler homes = new HomeHandler(plugin, config, database, distributor, online, null, islandOperator);
    private final WarpHandler warps = new WarpHandler(plugin, config, database, distributor, online, null, islandOperator);
    private final UpgradeHandler upgrades = new UpgradeHandler(plugin, config, database, islandOperator, economy);

    @BeforeEach
    void setUp() {
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        when(config.isUpgrade("team-limit")).thenReturn(true);
        when(config.getUpgradeLevels("team-limit")).thenReturn(List.of(1, 2));
        when(database.getIslandUpgradeLevel(island, "team-limit")).thenReturn(1);
    }

    @Test
    void playerAndAdminManagementRejectOtherHostsBeforeWritingOrCharging() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("remote"));
        for (Actor actor : List.of(new Actor.Player(player), new Actor.Bypass("console"))) {
            List<Supplier<CompletableFuture<?>>> operations = List.of(
                    () -> core.deleteIsland(actor, island),
                    () -> core.toggleIslandLock(actor, island),
                    () -> core.toggleIslandPvp(actor, island),
                    () -> members.addMember(actor, island, player, "member"),
                    () -> members.removeMember(actor, island, player),
                    () -> members.setOwner(actor, island, player),
                    () -> members.expelPlayer(actor, island, player),
                    () -> bans.banPlayer(actor, island, player),
                    () -> bans.unbanPlayer(actor, island, player),
                    () -> coops.coopPlayer(actor, island, player),
                    () -> coops.unCoopPlayer(actor, island, player),
                    () -> homes.delHome(island, player, "farm"),
                    () -> warps.delWarp(actor, island, "shop"),
                    () -> upgrades.setUpgradeLevel(actor, island, "team-limit", 2),
                    () -> upgrades.buyUpgrade(actor, island, player, "team-limit"));
            for (Supplier<CompletableFuture<?>> operation : operations) {
                Throwable cause = assertThrows(CompletionException.class, () -> operation.get().join()).getCause();
                assertInstanceOf(IslandNotHostedHereException.class, cause);
                assertEquals("remote", ((IslandNotHostedHereException) cause).getServer());
            }
        }
        verifyNoInteractions(distributor, world, economy, cache, online);
        verify(database, never()).addIslandPlayer(any(), any(), any(), anyString());
        verify(database, never()).updateIslandUpgradeLevel(any(), any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void localHandlersWriteWithTheOriginalActorAndRefreshWithoutNetworkRouting() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        for (Actor actor : List.of(new Actor.Player(player), new Actor.Bypass("console"))) {
            members.addMember(actor, island, player, "member").join();
            members.setOwner(actor, island, player).join();
            bans.unbanPlayer(actor, island, player).join();
            core.toggleIslandPvp(actor, island).join();
            verify(database).addIslandPlayer(actor, island, player, "member");
            verify(database).updateIslandOwner(actor, island, player);
            verify(database).deleteBanPlayer(actor, island, player);
            verify(database).toggleIslandPvp(actor, island);
        }
        verify(cache, times(8)).reload(island);
        verifyNoInteractions(distributor, world, economy);
    }

    @Test
    void sameIslandTasksKeepCallOrderBeforeTheAsyncWorkersRun() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        BukkitAsyncExecutor async = mock(BukkitAsyncExecutor.class);
        List<Runnable> scheduled = new ArrayList<>();
        doAnswer(call -> { scheduled.add(call.getArgument(0)); return null; }).when(async).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(async);
        Actor actor = new Actor.Bypass("console");

        CompletableFuture<Void> added = members.addMember(actor, island, player, "member");
        CompletableFuture<Boolean> pvp = core.toggleIslandPvp(actor, island);

        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        added.join();
        verify(database, never()).toggleIslandPvp(any(), any());
        assertEquals(1, scheduled.size());
        scheduled.removeFirst().run();
        pvp.join();
        var order = inOrder(database);
        order.verify(database).addIslandPlayer(actor, island, player, "member");
        order.verify(database).toggleIslandPvp(actor, island);
    }

    @Test
    void handlersShareOneChainUntilWorldEffectsFinish() {
        when(database.getIslandServer(island)).thenReturn(Optional.of("local"));
        when(database.getIslandSlot(island)).thenReturn(Optional.of(4));
        when(cache.reload(island)).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Boolean> eviction = new CompletableFuture<>();
        when(world.removePlayerFromIsland(4, player)).thenReturn(eviction);
        Actor actor = new Actor.Bypass("console");

        CompletableFuture<Void> removed = members.removeMember(actor, island, player);
        CompletableFuture<Boolean> pvp = core.toggleIslandPvp(actor, island);

        assertFalse(removed.isDone());
        assertFalse(pvp.isDone());
        verify(database, never()).toggleIslandPvp(any(), any());
        eviction.complete(true);
        removed.join();
        pvp.join();
        verify(database).toggleIslandPvp(actor, island);
        verifyNoInteractions(distributor);
    }
}
