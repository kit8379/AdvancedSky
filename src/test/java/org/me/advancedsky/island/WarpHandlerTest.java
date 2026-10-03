package org.me.advancedsky.island;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.thread.BukkitAsyncExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarpHandlerTest {
    private static final String WORLD = "islands";
    private final IslandGrid grid = new IslandGrid(64);
    private final World islandWorld = world(WORLD);
    private final World otherWorld = world("world");
    private final UUID island = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();
    private final Actor admin = new Actor.Bypass("test");
    private Connection connection;
    private DatabaseHandler database;
    private WarpHandler handler;
    private IslandDistributor distributor;
    private OnlinePlayerRegistry players;
    private IslandCache cache;
    private PlayerHandler members;
    private HomeHandler homes;
    private ConfigHandler config;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;LOCK_TIMEOUT=5000";
        connection = DriverManager.getConnection(url);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> DriverManager.getConnection(url));
        AdvancedSky plugin = mock(AdvancedSky.class);
        database = mock(DatabaseHandler.class, CALLS_REAL_METHODS);
        field("dataSource", dataSource);
        field("prefix", "test_");
        field("plugin", plugin);
        // Upgrade limits are covered by DatabaseHandlerUpgradeTest; here they stay out of the way.
        config = mock(ConfigHandler.class);
        when(config.getUpgradeLevels(anyString())).thenReturn(List.of(1));
        when(config.getUpgradeLimit(anyString(), anyInt())).thenReturn(Integer.MAX_VALUE);
        // This server hosts the island; points must lie in its area of the island world.
        when(config.getServerName()).thenReturn("test");
        when(config.getIslandWorldName()).thenReturn(WORLD);
        field("config", config);
        field("islandGrid", grid);
        // Use the production schema so membership and island cascade behavior is exercised.
        Method createTables = DatabaseHandler.class.getDeclaredMethod("createTables");
        createTables.setAccessible(true);
        createTables.invoke(database);
        // Slot 0 is centred at 256,256: its area holds every point used below.
        sql("INSERT INTO test_islands (island_uuid, server, slot) VALUES (?, 'test', 0)", island);
        sql("INSERT INTO test_island_players VALUES (?, ?, 'owner')", owner, island);
        sql("INSERT INTO test_island_players VALUES (?, ?, 'member')", member, island);

        BukkitAsyncExecutor executor = mock(BukkitAsyncExecutor.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(plugin.getBukkitAsyncExecutor()).thenReturn(executor);
        distributor = mock(IslandDistributor.class);
        players = mock(OnlinePlayerRegistry.class);
        cache = new IslandCache(plugin, database, grid);
        cache.load(island).join();
        LocalIslandTasks tasks = new LocalIslandTasks(plugin, database, cache, "test");
        members = new PlayerHandler(plugin, database, distributor, null, players, tasks, null);
        homes = new HomeHandler(plugin, config, database, distributor, players, grid, tasks);
        handler = new WarpHandler(plugin, config, database, distributor, players, grid, tasks);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void membersShareNamesUpdateAndDeleteTheSameWarp() {
        set(owner, "Shop", 1);
        assertEquals(Set.of("shop"), handler.getWarpNames(island).join());
        set(member, "SHOP", 2);
        assertEquals(Map.of("shop", "2.0,70.0,3.0,0.0,0.0"), database.getIslandWarps(island));
        assertEquals(Set.of("shop"), handler.getWarpNames(island).join());
        handler.delWarp(new Actor.Player(owner), island, "SHOP").join();
        assertTrue(handler.getWarpNames(island).join().isEmpty());
        verifyNoInteractions(distributor);
    }

    @Test
    void teleportUsesIslandUuidWithoutLookingUpPlayerMembership() {
        set(owner, "default", 1);
        when(players.isOnline(visitor)).thenReturn(true);
        when(distributor.teleportIsland(any(), any(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.warp(new Actor.Player(visitor), island, "default", visitor).join();
        handler.warp(new Actor.Player(visitor), island, "DEFAULT", visitor).join();
        verify(database, never()).getIslandUuid(any());
        verify(distributor, times(2)).teleportIsland(island, visitor, WORLD, "1.0,70.0,3.0,0.0,0.0");
    }

    @Test
    void warpsSurviveCreatorsDepartureButAreDeletedWithIsland() {
        set(member, "farm", 1);
        database.deleteIslandPlayer(new Actor.Player(owner), island, member);
        assertEquals(Set.of("farm"), handler.getWarpNames(island).join());
        assertThrows(IslandDoesNotExistException.class,
                () -> database.updateWarpPoint(new Actor.Player(member), island, "farm", "changed"));
        assertThrows(IslandDoesNotExistException.class,
                () -> database.deleteWarpPoint(new Actor.Player(member), island, "farm"));
        database.beginIslandDeletion(new Actor.Player(owner), island);
        assertTrue(database.getIslandWarps(island).isEmpty());
        database.finishIslandDeletion(island);
    }

    @Test
    void coopAndVisitorsCannotModifySharedWarpsButAdminCan() throws Exception {
        set(owner, "shop", 1);
        sql("INSERT INTO test_island_coops VALUES (?, ?)", island, visitor);
        assertThrows(IslandDoesNotExistException.class,
                () -> database.updateWarpPoint(new Actor.Player(visitor), island, "shop", "changed"));
        assertThrows(IslandDoesNotExistException.class,
                () -> database.deleteWarpPoint(new Actor.Player(visitor), island, "shop"));
        assertThrows(IslandDoesNotExistException.class,
                () -> database.updateWarpPoint(new Actor.Player(UUID.randomUUID()), island, "shop", "changed"));
        handler.setWarp(admin, island, "shop", new Location(islandWorld, 5, 70, 3)).join();
        assertEquals("5.0,70.0,3.0,0.0,0.0", database.getIslandWarps(island).get("shop"));
        handler.delWarp(admin, island, "shop").join();
        assertTrue(database.getIslandWarps(island).isEmpty());
    }

    @Test
    void wrongWorldAndInvalidNameDoNotWriteEvenForAdmin() {
        assertFailure(LocationNotInIslandException.class,
                handler.setWarp(admin, island, "shop", new Location(otherWorld, 1, 2, 3)));
        Location otherIsland = grid.center(null, 1);
        assertFailure(LocationNotInIslandException.class,
                handler.setWarp(admin, island, "shop", new Location(islandWorld, otherIsland.getX(), 2, otherIsland.getZ())));
        assertFailure(WarpNameNotLegalException.class,
                handler.setWarp(new Actor.Player(owner), island, "bad name", new Location(islandWorld, 1, 2, 3)));
        assertTrue(database.getIslandWarps(island).isEmpty());
    }

    @Test
    void pointsBeyondTheIslandBoundaryAreRefusedEvenForAdmin() {
        when(config.getUpgradeLimit("island-size", 1)).thenReturn(50);
        // 1,70,3 lies in slot 0's area but far outside a 50-block boundary around 256,256.
        Location beyond = new Location(islandWorld, 1, 70, 3);
        assertFailure(LocationNotInIslandException.class, handler.setWarp(admin, island, "shop", beyond));
        assertFailure(LocationNotInIslandException.class, homes.setHome(admin, owner, "far", beyond));
        assertTrue(database.getIslandWarps(island).isEmpty());

        handler.setWarp(admin, island, "shop", new Location(islandWorld, 256, 70, 256)).join();
        assertEquals(Set.of("shop"), handler.getWarpNames(island).join());
    }

    @Test
    void sameNameOnOtherIslandIsIndependentAndCannotBeEditedByMember() throws Exception {
        UUID otherIsland = UUID.randomUUID();
        sql("INSERT INTO test_islands (island_uuid, server) VALUES (?, 'test')", otherIsland);
        set(owner, "shop", 1);
        database.updateWarpPoint(admin, otherIsland, "shop", "other");
        assertThrows(IslandDoesNotExistException.class,
                () -> database.updateWarpPoint(new Actor.Player(member), otherIsland, "shop", "changed"));
        assertThrows(IslandDoesNotExistException.class,
                () -> database.deleteWarpPoint(new Actor.Player(member), otherIsland, "shop"));
        handler.delWarp(new Actor.Player(member), island, "shop").join();
        assertEquals(Map.of("shop", "other"), database.getIslandWarps(otherIsland));
        assertFailure(WarpDoesNotExistException.class, handler.delWarp(new Actor.Player(owner), island, "missing"));
    }

    @Test
    void lockedAndBannedIslandAccessRulesStillApply() throws Exception {
        set(owner, "shop", 1);
        when(players.isOnline(visitor)).thenReturn(true);
        sql("UPDATE test_islands SET `lock` = TRUE WHERE island_uuid = ?", island);
        assertFailure(IslandLockedException.class, handler.warp(new Actor.Player(visitor), island, "shop", visitor));
        sql("INSERT INTO test_island_coops VALUES (?, ?)", island, visitor);
        when(distributor.teleportIsland(any(), any(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        handler.warp(new Actor.Player(visitor), island, "shop", visitor).join();
        sql("INSERT INTO test_island_bans VALUES (?, ?)", island, visitor);
        assertFailure(PlayerBannedException.class, handler.warp(new Actor.Player(visitor), island, "shop", visitor));
        verify(distributor, times(1)).teleportIsland(any(), any(), anyString(), anyString());
    }

    @Test
    void anAdminWarpIsHeldToNeitherTheLockNorBans() throws Exception {
        set(owner, "shop", 1);
        when(players.isOnline(visitor)).thenReturn(true);
        sql("UPDATE test_islands SET `lock` = TRUE WHERE island_uuid = ?", island);
        sql("INSERT INTO test_island_bans VALUES (?, ?)", island, visitor);
        when(distributor.teleportIsland(any(), any(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        handler.warp(admin, island, "shop", visitor).join();
        verify(distributor).teleportIsland(eq(island), eq(visitor), anyString(), anyString());
    }

    @Test
    void writeWaitingOnMembershipRemovalRechecksRoleAfterLock() throws Exception {
        set(member, "shop", 1);
        connection.setAutoCommit(false);
        sql("SELECT 1 FROM test_islands WHERE island_uuid = ? FOR UPDATE", island);
        sql("DELETE FROM test_island_players WHERE player_uuid = ?", member);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            Future<?> write = executor.submit(() -> {
                started.countDown();
                database.updateWarpPoint(new Actor.Player(member), island, "shop", "changed");
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> write.get(150, TimeUnit.MILLISECONDS));
            } finally {
                connection.commit();
                connection.setAutoCommit(true);
            }
            ExecutionException error = assertThrows(ExecutionException.class, () -> write.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IslandDoesNotExistException.class, error.getCause());
        }
        assertEquals("1.0,70.0,3.0,0.0,0.0", database.getIslandWarps(island).get("shop"));
    }

    @Test
    void completedHomeAndWarpWritesRefreshOnlyDefaultRespawnPoints() {
        homes.setHome(new Actor.Player(owner), owner, "DEFAULT", new Location(islandWorld, 1, 70, 3, 45, 10)).join();
        homes.setHome(new Actor.Player(member), member, "default", new Location(islandWorld, 2, 70, 3)).join();
        homes.setHome(new Actor.Player(owner), owner, "mine", new Location(islandWorld, 99, 70, 3)).join();
        set(owner, "default", 4);
        set(member, "shop", 99);

        assertEquals(Map.of(owner, "1.0,70.0,3.0,45.0,10.0", member, "2.0,70.0,3.0,0.0,0.0"), cache.get(island).getDefaultHomes());
        assertEquals("4.0,70.0,3.0,0.0,0.0", cache.get(island).getDefaultWarp());
        assertThrows(UnsupportedOperationException.class, () -> cache.get(island).getDefaultHomes().clear());

        homes.setHome(new Actor.Player(member), member, "default", new Location(islandWorld, 5, 70, 3)).join();
        set(member, "default", 6);
        assertEquals("5.0,70.0,3.0,0.0,0.0", cache.get(island).getDefaultHomes().get(member));
        assertEquals("6.0,70.0,3.0,0.0,0.0", cache.get(island).getDefaultWarp());

        handler.delWarp(new Actor.Player(member), island, "default").join();
        assertNull(cache.get(island).getDefaultWarp());
        homes.delHome(owner, "mine").join();
        cache.unload(island);
        cache.load(island).join();
        assertEquals("5.0,70.0,3.0,0.0,0.0", cache.get(island).getDefaultHomes().get(member));
        assertNull(cache.get(island).getDefaultWarp());
    }

    @Test
    void newMembersDefaultHomeIsIncludedByMembershipCacheReload() {
        homes.setHome(new Actor.Player(owner), owner, "default", new Location(islandWorld, 1, 70, 3)).join();
        UUID newMember = UUID.randomUUID();
        members.addMember(new Actor.Player(newMember), island, newMember, "member").join();
        assertEquals("1.0,70.0,3.0,0.0,0.0", cache.get(island).getDefaultHomes().get(newMember));
        assertTrue(cache.get(island).getMembers().contains(newMember));
    }

    private void set(UUID player, String name, double x) {
        handler.setWarp(new Actor.Player(player), island, name, new Location(islandWorld, x, 70, 3)).join();
    }

    private void assertFailure(Class<? extends Throwable> type, CompletableFuture<?> result) {
        assertInstanceOf(type, assertThrows(CompletionException.class, result::join).getCause());
    }

    private void sql(String sql, UUID... args) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i].toString());
            statement.execute();
        }
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private void field(String name, Object value) throws Exception {
        Field field = DatabaseHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(database, value);
    }
}
