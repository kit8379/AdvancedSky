package org.me.advancedsky.database;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.model.IslandTop;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseHandlerRankTest {
    private Connection connection;
    private DatabaseHandler database;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL";
        connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE test_island_players (island_uuid CHAR(36), player_uuid CHAR(36), role VARCHAR(16))");
            statement.execute("CREATE TABLE test_island_levels (island_uuid CHAR(36) PRIMARY KEY, level INT)");
        }
        HikariDataSource dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> DriverManager.getConnection(url));
        // Exercise the real read methods without starting AdvancedSky's production pool/schema.
        database = mock(DatabaseHandler.class, CALLS_REAL_METHODS);
        field("dataSource", dataSource);
        field("prefix", "test_");
        field("plugin", mock(AdvancedSky.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void rankingOrdersByLevelThenIslandWithMissingLevelsAtZero() throws Exception {
        island(2, 20);
        island(1, 20);
        island(3, 5);
        island(4, null);
        island(5, 0);
        island(6, -1);

        List<IslandTop> ranking = database.getIslandRanking();
        assertEquals(List.of(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6)), ranking.stream().map(IslandTop::getIslandUuid).toList());
        assertEquals(List.of(20, 20, 5, 0, 0, -1), ranking.stream().map(IslandTop::getLevel).toList());
    }

    @Test
    void membersAreReadForTheIslandsAskedForOnly() throws Exception {
        island(1, 20);
        island(2, 10);
        UUID member = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO test_island_players VALUES (?, ?, 'member')")) {
            statement.setString(1, uuid(1).toString());
            statement.setString(2, member.toString());
            statement.executeUpdate();
            statement.setString(1, uuid(2).toString());
            statement.setString(2, UUID.randomUUID().toString());
            statement.executeUpdate();
        }

        assertEquals(Map.of(uuid(1), Set.of(member)), database.getIslandMembers(List.of(uuid(1))));
        assertEquals(Map.of(), database.getIslandMembers(List.of()));
    }

    private void island(int id, Integer level) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO test_island_players VALUES (?, ?, 'owner')")) {
            statement.setString(1, uuid(id).toString());
            statement.setString(2, UUID.randomUUID().toString());
            statement.executeUpdate();
        }
        if (level != null) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO test_island_levels VALUES (?, ?)")) {
                statement.setString(1, uuid(id).toString());
                statement.setInt(2, level);
                statement.executeUpdate();
            }
        }
    }

    private UUID uuid(int value) {
        return new UUID(0, value);
    }

    private void field(String name, Object value) throws Exception {
        Field field = DatabaseHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(database, value);
    }
}
