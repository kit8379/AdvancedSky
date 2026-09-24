package org.me.advancedsky.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.IslandAlreadyExistException;
import org.me.advancedsky.exceptions.IslandGridFullException;
import org.me.advancedsky.island.IslandGrid;
import org.me.advancedsky.model.Island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking MySQL access. Every method here is expected to run on an async thread.
 */
public class DatabaseHandler {

    private static final int SLOT_ALLOCATION_ATTEMPTS = 5;

    private final HikariDataSource dataSource;
    private final String islandsTable;

    public DatabaseHandler(ConfigHandler config) {
        this.islandsTable = config.getMySQLTablePrefix() + "islands";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getMySQLHost() + ":" + config.getMySQLPort() + "/" + config.getMySQLDatabase() + config.getMySQLProperties());
        hikariConfig.setUsername(config.getMySQLUsername());
        hikariConfig.setPassword(config.getMySQLPassword());
        this.dataSource = new HikariDataSource(hikariConfig);

        createTables();
    }

    public void close() {
        dataSource.close();
    }

    private void createTables() {
        // One island per owner. Slots are unique network-wide, not per server, so an island keeps the
        // same coordinates on every server and can later be moved by moving its region file.
        execute("CREATE TABLE IF NOT EXISTS " + islandsTable + " (" +
                "island_uuid CHAR(36) NOT NULL, " +
                "owner_uuid CHAR(36) NOT NULL, " +
                "server VARCHAR(64) NOT NULL, " +
                "slot INT NOT NULL, " +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (island_uuid), " +
                "UNIQUE KEY uk_owner (owner_uuid), " +
                "UNIQUE KEY uk_slot (slot))", statement -> {
        });
    }

    public Optional<Island> findIsland(UUID islandUuid) {
        return query("SELECT island_uuid, owner_uuid, server, slot FROM " + islandsTable + " WHERE island_uuid = ?", statement -> statement.setString(1, islandUuid.toString()), resultSet -> resultSet.next() ? Optional.of(mapIsland(resultSet)) : Optional.empty());
    }

    public Optional<Island> findIslandByOwner(UUID ownerUuid) {
        return query("SELECT island_uuid, owner_uuid, server, slot FROM " + islandsTable + " WHERE owner_uuid = ?", statement -> statement.setString(1, ownerUuid.toString()), resultSet -> resultSet.next() ? Optional.of(mapIsland(resultSet)) : Optional.empty());
    }

    /**
     * Number of islands per server. Servers without islands are absent from the map.
     */
    public Map<String, Integer> countIslandsByServer() {
        return query("SELECT server, COUNT(*) AS island_count FROM " + islandsTable + " GROUP BY server", statement -> {
        }, resultSet -> {
            Map<String, Integer> counts = new HashMap<>();
            while (resultSet.next()) {
                counts.put(resultSet.getString("server"), resultSet.getInt("island_count"));
            }
            return counts;
        });
    }

    /**
     * Creates the island record for the owner on the given server, taking the next slot after the
     * highest one in use anywhere in the network.
     *
     * @throws IslandAlreadyExistException  if the owner already has an island
     * @throws IslandGridFullException     if the grid has no slot left, see {@link IslandGrid#MAX_SLOT}
     */
    public Island insertIsland(UUID ownerUuid, String server) {
        String sql = "INSERT INTO " + islandsTable + " (island_uuid, owner_uuid, server, slot) VALUES (?, ?, ?, ?)";

        for (int attempt = 1; attempt <= SLOT_ALLOCATION_ATTEMPTS; attempt++) {
            int slot = nextSlot();
            if (slot > IslandGrid.MAX_SLOT) {
                throw new IslandGridFullException();
            }

            Island island = new Island(UUID.randomUUID(), ownerUuid, server, slot);

            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, island.islandUuid().toString());
                statement.setString(2, island.ownerUuid().toString());
                statement.setString(3, island.server());
                statement.setInt(4, island.slot());
                statement.executeUpdate();
                return island;
            } catch (SQLIntegrityConstraintViolationException e) {
                if (findIslandByOwner(ownerUuid).isPresent()) {
                    throw new IslandAlreadyExistException();
                }
                // Another server took the same slot at the same moment; try again with a fresh one.
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert island for owner " + ownerUuid, e);
            }
        }

        throw new IllegalStateException("Could not allocate an island slot on " + server + " after " + SLOT_ALLOCATION_ATTEMPTS + " attempts");
    }

    public void deleteIsland(UUID islandUuid) {
        execute("DELETE FROM " + islandsTable + " WHERE island_uuid = ?", statement -> statement.setString(1, islandUuid.toString()));
    }

    private int nextSlot() {
        return query("SELECT COALESCE(MAX(slot) + 1, 0) FROM " + islandsTable, statement -> {
        }, resultSet -> {
            resultSet.next();
            return resultSet.getInt(1);
        });
    }

    private void execute(String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Database update failed: " + sql, e);
        }
    }

    private <T> T query(String sql, StatementBinder binder, ResultMapper<T> mapper) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapper.map(resultSet);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database query failed: " + sql, e);
        }
    }

    private static Island mapIsland(ResultSet resultSet) throws SQLException {
        return new Island(UUID.fromString(resultSet.getString("island_uuid")), UUID.fromString(resultSet.getString("owner_uuid")), resultSet.getString("server"), resultSet.getInt("slot"));
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface ResultMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }
}
