package org.me.advancedsky.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.grid.IslandGrid;
import org.me.advancedsky.model.Actor;

import java.sql.*;
import java.util.*;

public class DatabaseHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final IslandGrid islandGrid;
    private final HikariDataSource dataSource;
    private final String prefix;

    public DatabaseHandler(AdvancedSky plugin, ConfigHandler config, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.config = config;
        this.islandGrid = islandGrid;
        this.prefix = config.getMySQLTablePrefix();

        String host = config.getMySQLHost();
        int port = config.getMySQLPort();
        String database = config.getMySQLDB();
        String username = config.getMySQLUsername();
        String password = config.getMySQLPassword();
        boolean useSsl = config.getMySQLUseSSL();
        String properties = config.getMySQLProperties();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSsl + "&" + properties);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);

        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
        hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

        this.dataSource = new HikariDataSource(hikariConfig);

        createTables();
    }

    public void close() {
        dataSource.close();
    }

    private <T> T withConnection(ConnectionFunction<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (SQLException e) {
            plugin.severe("Database connection operation failed.", e);
            throw new RuntimeException(e);
        }
    }

    private <T> T inTransaction(ConnectionFunction<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }

                if (e instanceof SQLIntegrityConstraintViolationException) {
                    throw new ConstraintViolationException(e);
                }

                if (e instanceof SQLException) {
                    plugin.severe("Database transaction failed.", e);
                    throw new RuntimeException(e);
                }

                throw (RuntimeException) e;
            }
        } catch (SQLException e) {
            plugin.severe("Database transaction connection failed.", e);
            throw new RuntimeException(e);
        }
    }

    private int executeUpdate(Connection connection, String query, PreparedStatementConsumer consumer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            consumer.use(statement);
            return statement.executeUpdate();
        }
    }

    private int executeUpdate(String query, PreparedStatementConsumer consumer) {
        try (Connection connection = dataSource.getConnection()) {
            return executeUpdate(connection, query, consumer);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new ConstraintViolationException(e);
        } catch (SQLException e) {
            plugin.severe("Database Update failed: " + query, e);
            throw new RuntimeException(e);
        }
    }

    private <T> T executeQuery(Connection connection, String query, PreparedStatementConsumer consumer, ResultFunction<T> function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            consumer.use(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return function.apply(resultSet);
            }
        }
    }

    private <T> T executeQuery(String query, PreparedStatementConsumer consumer, ResultFunction<T> function) {
        try (Connection connection = dataSource.getConnection()) {
            return executeQuery(connection, query, consumer, function);
        } catch (SQLException e) {
            plugin.severe("Database Query failed: " + query, e);
            throw new RuntimeException(e);
        }
    }

    // ================================================================================================================
    // Table creation
    // ================================================================================================================

    private void createTables() {
        // server: the island server hosting the island for its whole life.
        // slot: the island's position on the grid, the same on every server. AUTO_INCREMENT never hands
        // out a value twice, so a deleted island's area is never reused while it may still be clearing.
        executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "islands (" + "island_uuid CHAR(36) NOT NULL," + "server VARCHAR(64) NOT NULL," + "slot INT NOT NULL AUTO_INCREMENT," + "PRIMARY KEY (island_uuid)," + "UNIQUE KEY uq_islands_slot (slot)," + "KEY idx_islands_server (server)" + ") ENGINE=InnoDB;", stmt -> {
        });

        executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "island_players (" + "player_uuid CHAR(36) NOT NULL," + "island_uuid CHAR(36) NOT NULL," + "role VARCHAR(56) NOT NULL," + "PRIMARY KEY (player_uuid, island_uuid)," + "UNIQUE KEY uq_island_players_player_uuid (player_uuid)," + "KEY idx_island_players_island_uuid (island_uuid)," + "KEY idx_island_players_island_role (island_uuid, role)," + "CONSTRAINT fk_island_players_island " + "FOREIGN KEY (island_uuid) REFERENCES " + prefix + "islands(island_uuid) " + "ON DELETE CASCADE" + ") ENGINE=InnoDB;", stmt -> {
        });

        executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "island_homes (" + "player_uuid CHAR(36) NOT NULL," + "home_name VARCHAR(32) NOT NULL," + "home_location VARCHAR(256)," + "island_uuid CHAR(36) NOT NULL," + "PRIMARY KEY (player_uuid, island_uuid, home_name)," + "KEY idx_island_homes_island (island_uuid)," + "KEY idx_island_homes_island_player (island_uuid, player_uuid)," + "CONSTRAINT fk_island_homes_island " + "FOREIGN KEY (island_uuid) REFERENCES " + prefix + "islands(island_uuid) " + "ON DELETE CASCADE," + "CONSTRAINT fk_island_homes_player_membership " + "FOREIGN KEY (player_uuid, island_uuid) REFERENCES " + prefix + "island_players(player_uuid, island_uuid) " + "ON DELETE CASCADE" + ") ENGINE=InnoDB;", stmt -> {
        });
    }

    // ================================================================================================================
    // Targeted reads
    // ================================================================================================================

    public Optional<UUID> getIslandOwner(UUID islandUuid) {
        return withConnection(connection -> getIslandOwner(connection, islandUuid));
    }

    public Map<String, String> getIslandHomes(UUID islandUuid, UUID playerUuid) {
        String sql = "SELECT home_name, home_location FROM " + prefix + "island_homes WHERE island_uuid = ? AND player_uuid = ?";

        return executeQuery(sql, stmt -> {
            stmt.setString(1, islandUuid.toString());
            stmt.setString(2, playerUuid.toString());
        }, rs -> {
            Map<String, String> result = new LinkedHashMap<>();

            while (rs.next()) {
                String homeName = rs.getString("home_name");
                String homeLocation = rs.getString("home_location");

                if (homeName == null || homeName.isEmpty()) {
                    continue;
                }

                result.put(homeName, homeLocation == null ? "" : homeLocation);
            }

            return result.isEmpty() ? Collections.emptyMap() : Map.copyOf(result);
        });
    }

    public Optional<UUID> getIslandUuid(UUID playerUuid) {
        String sql = "SELECT island_uuid FROM " + prefix + "island_players WHERE player_uuid = ? LIMIT 1";

        return executeQuery(sql, stmt -> stmt.setString(1, playerUuid.toString()), rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }

            return Optional.of(parseRequiredUuid(rs.getString("island_uuid"), "island_players.island_uuid"));
        });
    }

    public Optional<String> getIslandServer(UUID islandUuid) {
        return executeQuery("SELECT server FROM " + prefix + "islands WHERE island_uuid = ? LIMIT 1", stmt -> stmt.setString(1, islandUuid.toString()), rs -> rs.next() ? Optional.of(rs.getString("server")) : Optional.empty());
    }

    public Optional<Integer> getIslandSlot(UUID islandUuid) {
        return executeQuery("SELECT slot FROM " + prefix + "islands WHERE island_uuid = ? LIMIT 1", stmt -> stmt.setString(1, islandUuid.toString()), rs -> rs.next() ? Optional.of(rs.getInt("slot")) : Optional.empty());
    }

    /**
     * Number of islands per server. Servers without islands are absent from the map.
     */
    public Map<String, Integer> getIslandCountByServer() {
        return executeQuery("SELECT server, COUNT(*) AS island_count FROM " + prefix + "islands GROUP BY server", stmt -> {
        }, rs -> {
            Map<String, Integer> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("server"), rs.getInt("island_count"));
            }
            return result;
        });
    }

    // ================================================================================================================
    // Writes (transaction-protected where multi-statement)
    // ================================================================================================================

    /**
     * Stores a new island hosted on the given server and returns the grid slot it was given.
     */
    public int addIslandData(UUID islandUuid, UUID ownerUuid, String server) {
        try {
            return inTransaction(connection -> {
                if (getPlayerIsland(connection, ownerUuid).isPresent()) {
                    throw new IslandAlreadyExistException();
                }

                executeUpdate(connection, "INSERT INTO " + prefix + "islands (island_uuid, server) VALUES (?, ?);", stmt -> {
                    stmt.setString(1, islandUuid.toString());
                    stmt.setString(2, server);
                });

                int slot = executeQuery(connection, "SELECT slot FROM " + prefix + "islands WHERE island_uuid = ?", stmt -> {
                    stmt.setString(1, islandUuid.toString());
                }, rs -> {
                    rs.next();
                    return rs.getInt("slot");
                });

                if (slot > IslandGrid.MAX_SLOT) {
                    throw new IslandGridFullException();
                }

                executeUpdate(connection, "INSERT INTO " + prefix + "island_players (player_uuid, island_uuid, role) VALUES (?, ?, ?);", stmt -> {
                    stmt.setString(1, ownerUuid.toString());
                    stmt.setString(2, islandUuid.toString());
                    stmt.setString(3, "owner");
                });

                // Only the coordinates of the centre are needed: homes are stored without a world.
                Location center = islandGrid.center(null, slot);
                String homePoint = (center.getX() + config.getIslandSpawnX()) + "," + (center.getY() + config.getIslandSpawnY()) + "," + (center.getZ() + config.getIslandSpawnZ()) + "," + config.getIslandSpawnYaw() + "," + config.getIslandSpawnPitch();

                executeUpdate(connection, "INSERT INTO " + prefix + "island_homes (player_uuid, island_uuid, home_name, home_location) VALUES (?, ?, ?, ?) " + "ON DUPLICATE KEY UPDATE island_uuid = VALUES(island_uuid), home_location = VALUES(home_location);", stmt -> {
                    stmt.setString(1, ownerUuid.toString());
                    stmt.setString(2, islandUuid.toString());
                    stmt.setString(3, "default");
                    stmt.setString(4, homePoint);
                });

                return slot;
            });
        } catch (ConstraintViolationException e) {
            throw new IslandAlreadyExistException();
        }
    }

    public void updateHomePoint(Actor actor, UUID islandUuid, UUID playerUuid, String homeName, String homeLocation) {
        try {
            inTransaction(connection -> {
                lockIsland(connection, islandUuid);

                executeUpdate(connection, "INSERT INTO " + prefix + "island_homes (player_uuid, island_uuid, home_name, home_location) VALUES (?, ?, ?, ?) " + "ON DUPLICATE KEY UPDATE island_uuid = VALUES(island_uuid), home_location = VALUES(home_location);", stmt -> {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setString(2, islandUuid.toString());
                    stmt.setString(3, homeName);
                    stmt.setString(4, homeLocation);
                });

                return null;
            });
        } catch (ConstraintViolationException e) {
            throw new IslandDoesNotExistException();
        }
    }

    public void deleteIsland(Actor actor, UUID islandUuid) {
        inTransaction(connection -> {
            lockIsland(connection, islandUuid);
            requireRole(actor, connection, islandUuid);

            executeUpdate(connection, "DELETE FROM " + prefix + "islands WHERE island_uuid = ?;", stmt -> {
                stmt.setString(1, islandUuid.toString());
            });

            return null;
        });
    }

    public void deleteHomePoint(UUID islandUuid, UUID playerUuid, String homeName) {
        int deleted = executeUpdate("DELETE FROM " + prefix + "island_homes WHERE island_uuid = ? AND player_uuid = ? AND home_name = ?;", stmt -> {
            stmt.setString(1, islandUuid.toString());
            stmt.setString(2, playerUuid.toString());
            stmt.setString(3, homeName);
        });

        if (deleted == 0) {
            throw new HomeDoesNotExistException();
        }
    }

    // ================================================================================================================
    // Helpers
    // ================================================================================================================

    private UUID parseRequiredUuid(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing UUID value in DatabaseHandler for field: " + fieldName);
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid UUID value in DatabaseHandler for field " + fieldName + ": " + value, e);
        }
    }

    private void lockIsland(Connection connection, UUID islandUuid) throws SQLException {
        boolean exists = executeQuery(connection, "SELECT 1 FROM " + prefix + "islands WHERE island_uuid = ? FOR UPDATE", stmt -> {
            stmt.setString(1, islandUuid.toString());
        }, ResultSet::next);

        if (!exists) {
            throw new IslandDoesNotExistException();
        }
    }

    /**
     * Enforces the island role an operation demands. Only players are subject to role rules;
     * Bypass actors skip them by construction. The required role is fixed by the calling
     * operation and never comes from outside, so a caller cannot lower its own bar.
     */
    private void requireRole(Actor actor, Connection connection, UUID islandUuid) throws SQLException {
        if (!(actor instanceof Actor.Player(UUID uuid))) {
            return;
        }

        String role = getIslandRole(connection, islandUuid, uuid).orElseThrow(IslandDoesNotExistException::new);

        if (!"owner".equalsIgnoreCase(role)) {
            throw new NotIslandOwnerException();
        }
    }

    private Optional<String> getIslandRole(Connection connection, UUID islandUuid, UUID playerUuid) throws SQLException {
        return executeQuery(connection, "SELECT role FROM " + prefix + "island_players WHERE island_uuid = ? AND player_uuid = ? LIMIT 1", stmt -> {
            stmt.setString(1, islandUuid.toString());
            stmt.setString(2, playerUuid.toString());
        }, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.ofNullable(rs.getString("role"));
        });
    }

    private Optional<UUID> getIslandOwner(Connection connection, UUID islandUuid) throws SQLException {
        return executeQuery(connection, "SELECT player_uuid FROM " + prefix + "island_players WHERE island_uuid = ? AND role = ? LIMIT 1", stmt -> {
            stmt.setString(1, islandUuid.toString());
            stmt.setString(2, "owner");
        }, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(parseRequiredUuid(rs.getString("player_uuid"), "island_players.player_uuid"));
        });
    }

    private Optional<UUID> getPlayerIsland(Connection connection, UUID playerUuid) throws SQLException {
        return executeQuery(connection, "SELECT island_uuid FROM " + prefix + "island_players WHERE player_uuid = ? LIMIT 1", stmt -> {
            stmt.setString(1, playerUuid.toString());
        }, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(parseRequiredUuid(rs.getString("island_uuid"), "island_players.island_uuid"));
        });
    }

    private static final class ConstraintViolationException extends RuntimeException {
        ConstraintViolationException(Throwable cause) {
            super(cause);
        }
    }

    // ================================================================================================================
    // Functional interfaces
    // ================================================================================================================

    @FunctionalInterface
    public interface ResultFunction<T> {
        T apply(ResultSet resultSet) throws SQLException;
    }

    @FunctionalInterface
    public interface PreparedStatementConsumer {
        void use(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    public interface ConnectionFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
