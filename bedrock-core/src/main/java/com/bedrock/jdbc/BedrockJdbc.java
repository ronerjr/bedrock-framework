package com.bedrock.jdbc;

import com.bedrock.core.BedrockLogger;
import com.bedrock.exception.BedrockDatabaseException;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 🎓 BEDROCK TUTORIAL: The Transparent JDBC Helper (BedrockJdbc)
 * 
 * Many developers learn Spring's JdbcTemplate or Hibernate without knowing what
 * actually happens when sending SQL to a database.
 * 
 * BedrockJdbc provides a transparent, zero-magic abstraction over the standard
 * Java Database Connectivity (JDBC) API built into Java 21 standard library:
 * 
 * 1. Connection Lifecycle: Every query acquires a Connection and releases it using
 *    Java's 'try-with-resources', preventing connection leaks that crash servers in production.
 * 2. PreparedStatement (Defense against SQL Injection): Using parameterized queries with '?'
 *    guarantees that user input is never interpreted as executable SQL syntax.
 * 3. ResultSet Cursor: Traverses rows one by one with rs.next() and delegates mapping to RowMapper.
 */
public class BedrockJdbc {

    private final ConnectionProvider connectionProvider;

    public BedrockJdbc(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /**
     * Creates a BedrockJdbc instance using a custom ConnectionProvider.
     */
    public static BedrockJdbc of(ConnectionProvider provider) {
        return new BedrockJdbc(provider);
    }

    /**
     * Creates a BedrockJdbc instance using a standard javax.sql.DataSource.
     */
    public static BedrockJdbc of(DataSource dataSource) {
        return new BedrockJdbc(dataSource::getConnection);
    }

    /**
     * Creates a BedrockJdbc instance using a JDBC connection URL (via DriverManager).
     */
    public static BedrockJdbc of(String jdbcUrl) {
        return new BedrockJdbc(() -> DriverManager.getConnection(jdbcUrl));
    }

    /**
     * Creates a BedrockJdbc instance with a JDBC connection URL, username, and password.
     */
    public static BedrockJdbc of(String jdbcUrl, String user, String password) {
        return new BedrockJdbc(() -> DriverManager.getConnection(jdbcUrl, user, password));
    }

    /**
     * Obtains a raw connection from the provider.
     */
    public Connection getConnection() throws SQLException {
        return connectionProvider.getConnection();
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Parameterized Queries (Defense against SQL Injection)
     * 
     * Executes a SELECT query with parameters and transforms each row using the given RowMapper.
     * 
     * @param sql the SQL query containing '?' placeholders
     * @param mapper the RowMapper converting a ResultSet row to an object
     * @param params values to bind to the '?' placeholders in order
     * @param <T> the target domain type
     * @return a List of mapped items (empty list if no rows match)
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setParameters(stmt, params);

            try (ResultSet rs = stmt.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapper.mapRow(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            BedrockLogger.error("JDBC", "Query failed: " + e.getMessage());
            throw new BedrockDatabaseException(sql, e);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Single Object Queries and Null Safety
     * 
     * Executes a query expected to return at most one row.
     * 
     * @return Optional containing the mapped object if found, or Optional.empty()
     */
    public <T> Optional<T> queryForObject(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = query(sql, mapper, params);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Data Modification Statements (INSERT, UPDATE, DELETE)
     * 
     * Executes an INSERT, UPDATE, or DELETE statement.
     * 
     * @param sql the DML statement with '?' placeholders
     * @param params values to bind to the '?' placeholders
     * @return the number of rows affected
     */
    public int update(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setParameters(stmt, params);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            BedrockLogger.error("JDBC", "Update failed: " + e.getMessage());
            throw new BedrockDatabaseException(sql, e);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Generated Primary Keys (AUTOINCREMENT)
     * 
     * Executes an INSERT statement and retrieves the generated primary key ID.
     * 
     * @param sql the INSERT statement
     * @param params values to bind to the '?' placeholders
     * @return the generated primary key (e.g. auto-increment ID)
     */
    public long insertAndGetGeneratedKey(String sql, Object... params) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            setParameters(stmt, params);
            stmt.executeUpdate();

            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new BedrockDatabaseException(
                    "Database did not return a generated key for insert statement.",
                    "Ensure the table defines an AUTOINCREMENT or SERIAL primary key column.",
                    null
                );
            }
        } catch (SQLException e) {
            BedrockLogger.error("JDBC", "Insert failed: " + e.getMessage());
            throw new BedrockDatabaseException(sql, e);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: DDL Execution (Schema Initialization)
     * 
     * Executes a raw DDL statement such as 'CREATE TABLE IF NOT EXISTS'.
     */
    public void execute(String sql) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            BedrockLogger.error("JDBC", "DDL execution failed: " + e.getMessage());
            throw new BedrockDatabaseException(sql, e);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: The PreparedStatement Magic
     * 
     * Parameter indexing in JDBC is 1-based (not 0-based).
     * setObject delegates type mapping directly to the underlying JDBC driver.
     */
    private void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }
}
