package org.me.advancedsky.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Island;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseHandlerIslandsTest {
    private final UUID full = UUID.randomUUID();
    private final UUID bare = UUID.randomUUID();
    private final UUID elsewhere = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID otherMember = UUID.randomUUID();
    private final UUID coop = UUID.randomUUID();
    private final UUID banned = UUID.randomUUID();
    private final UUID bareOwner = UUID.randomUUID();
    private Connection connection;
    private DatabaseHandler database;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;LOCK_TIMEOUT=5000";
        connection = DriverManager.getConnection(url);
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> DriverManager.getConnection(url));
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getUpgradeLevels(anyString())).thenReturn(List.of(1, 2, 3));
        database = mock(DatabaseHandler.class, CALLS_REAL_METHODS);
        field("dataSource", dataSource);
        field("prefix", "test_");
        field("plugin", mock(AdvancedSky.class));
        field("config", config);
        field("islandGrid", new IslandGrid(64));
        Method createTables = DatabaseHandler.class.getDeclaredMethod("createTables");
        createTables.setAccessible(true);
        createTables.invoke(database);

        // Server a: one island with everything a cached island carries, one with nothing but its owner.
        sql("INSERT INTO test_islands (island_uuid, server) VALUES (?, 'a')", full);
        sql("UPDATE test_islands SET `lock` = TRUE WHERE island_uuid = ?", full);
        sql("INSERT INTO test_island_players (player_uuid, island_uuid, role) VALUES (?, ?, 'owner')", owner, full);
        sql("INSERT INTO test_island_players (player_uuid, island_uuid, role) VALUES (?, ?, 'member')", member, full);
        sql("INSERT INTO test_island_players (player_uuid, island_uuid, role) VALUES (?, ?, 'member')", otherMember, full);
        sql("INSERT INTO test_island_coops (island_uuid, cooped_player) VALUES (?, ?)", full, coop);
        sql("INSERT INTO test_island_bans (island_uuid, banned_player) VALUES (?, ?)", full, banned);
        sql("INSERT INTO test_island_homes (player_uuid, home_name, home_location, island_uuid) VALUES (?, 'default', '1,70,1,0,0', ?)", owner, full);
        sql("INSERT INTO test_island_homes (player_uuid, home_name, home_location, island_uuid) VALUES (?, 'farm', '9,70,9,0,0', ?)", owner, full);
        sql("INSERT INTO test_island_homes (player_uuid, home_name, home_location, island_uuid) VALUES (?, 'default', '2,70,2,0,0', ?)", member, full);
        sql("INSERT INTO test_island_warps (island_uuid, warp_name, warp_location) VALUES (?, 'default', '3,70,3,0,0')", full);
        sql("INSERT INTO test_island_warps (island_uuid, warp_name, warp_location) VALUES (?, 'shop', '4,70,4,0,0')", full);
        sql("INSERT INTO test_island_upgrades (island_uuid, upgrade_id, level) VALUES (?, 'island-size', 2)", full);
        sql("INSERT INTO test_island_upgrades (island_uuid, upgrade_id, level) VALUES (?, 'generator-rates', 3)", full);
        sql("INSERT INTO test_island_upgrades (island_uuid, upgrade_id, level) VALUES (?, 'coop-limit', 3)", full);

        sql("INSERT INTO test_islands (island_uuid, server) VALUES (?, 'a')", bare);
        sql("UPDATE test_islands SET pvp = TRUE WHERE island_uuid = ?", bare);
        sql("INSERT INTO test_island_players (player_uuid, island_uuid, role) VALUES (?, ?, 'owner')", bareOwner, bare);

        // Server b: its rows must not leak into server a's islands.
        UUID elsewhereOwner = UUID.randomUUID();
        sql("INSERT INTO test_islands (island_uuid, server) VALUES (?, 'b')", elsewhere);
        sql("INSERT INTO test_island_players (player_uuid, island_uuid, role) VALUES (?, ?, 'owner')", elsewhereOwner, elsewhere);
        sql("INSERT INTO test_island_players (player_uuid, island_uuid, role) VALUES (?, ?, 'member')", UUID.randomUUID(), elsewhere);
        sql("INSERT INTO test_island_coops (island_uuid, cooped_player) VALUES (?, ?)", elsewhere, UUID.randomUUID());
        sql("INSERT INTO test_island_bans (island_uuid, banned_player) VALUES (?, ?)", elsewhere, UUID.randomUUID());
        sql("INSERT INTO test_island_homes (player_uuid, home_name, home_location, island_uuid) VALUES (?, 'default', '5,70,5,0,0', ?)", elsewhereOwner, elsewhere);
        sql("INSERT INTO test_island_warps (island_uuid, warp_name, warp_location) VALUES (?, 'default', '6,70,6,0,0')", elsewhere);
        sql("INSERT INTO test_island_upgrades (island_uuid, upgrade_id, level) VALUES (?, 'island-size', 3)", elsewhere);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void serverReadHoldsExactlyThatServersIslandsWithEveryField() {
        Map<UUID, Island> hosted = database.getIslandsByServer("a");
        assertEquals(Set.of(full, bare), hosted.keySet());

        Island island = hosted.get(full);
        assertEquals(full, island.getIslandUuid());
        assertEquals(database.getIslandSlot(full).orElseThrow(), island.getSlot());
        assertTrue(island.isLock());
        assertFalse(island.isPvp());
        assertEquals(owner, island.getOwner());
        assertEquals(Set.of(member, otherMember), island.getMembers());
        assertEquals(Set.of(coop), island.getCoops());
        assertEquals(Set.of(banned), island.getBans());
        assertEquals(Map.of(owner, "1,70,1,0,0", member, "2,70,2,0,0"), island.getDefaultHomes());
        assertEquals("3,70,3,0,0", island.getDefaultWarp());
        assertEquals(2, island.getSizeLevel());
        assertEquals(3, island.getGeneratorLevel());

        // Nothing stored beyond the owner: empty sets, no default points, the first upgrade levels.
        Island ownerOnly = hosted.get(bare);
        assertEquals(database.getIslandSlot(bare).orElseThrow(), ownerOnly.getSlot());
        assertFalse(ownerOnly.isLock());
        assertTrue(ownerOnly.isPvp());
        assertEquals(bareOwner, ownerOnly.getOwner());
        assertEquals(Set.of(), ownerOnly.getMembers());
        assertEquals(Set.of(), ownerOnly.getCoops());
        assertEquals(Set.of(), ownerOnly.getBans());
        assertEquals(Map.of(), ownerOnly.getDefaultHomes());
        assertNull(ownerOnly.getDefaultWarp());
        assertEquals(1, ownerOnly.getSizeLevel());
        assertEquals(1, ownerOnly.getGeneratorLevel());

        assertEquals(Map.of(), database.getIslandsByServer("c"));
    }

    @Test
    void singleIslandReadMatchesTheServerRead() {
        Map<UUID, Island> hosted = database.getIslandsByServer("a");
        for (UUID islandUuid : List.of(full, bare)) {
            assertSameIsland(hosted.get(islandUuid), database.getIsland(islandUuid));
        }
        assertNull(database.getIsland(UUID.randomUUID()));
    }

    private static void assertSameIsland(Island expected, Island actual) {
        assertNotNull(actual);
        assertEquals(expected.getIslandUuid(), actual.getIslandUuid());
        assertEquals(expected.getSlot(), actual.getSlot());
        assertEquals(expected.isLock(), actual.isLock());
        assertEquals(expected.isPvp(), actual.isPvp());
        assertEquals(expected.getOwner(), actual.getOwner());
        assertEquals(expected.getMembers(), actual.getMembers());
        assertEquals(expected.getCoops(), actual.getCoops());
        assertEquals(expected.getBans(), actual.getBans());
        assertEquals(expected.getDefaultHomes(), actual.getDefaultHomes());
        assertEquals(expected.getDefaultWarp(), actual.getDefaultWarp());
        assertEquals(expected.getSizeLevel(), actual.getSizeLevel());
        assertEquals(expected.getGeneratorLevel(), actual.getGeneratorLevel());
    }

    private void sql(String statement, Object... values) throws Exception {
        try (PreparedStatement prepared = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                prepared.setString(i + 1, values[i].toString());
            }
            prepared.executeUpdate();
        }
    }

    private void field(String name, Object value) throws Exception {
        Field field = DatabaseHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(database, value);
    }
}
