package com.bedrock;

import com.bedrock.exception.BedrockDatabaseException;
import com.bedrock.ioc.BedrockComponent;
import com.bedrock.ioc.BedrockContainer;
import com.bedrock.ioc.BedrockInject;
import com.bedrock.jdbc.BedrockJdbc;
import com.bedrock.jdbc.ConnectionProvider;
import com.bedrock.jdbc.RowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BedrockJdbcTest {

    private Connection connection;
    private PreparedStatement statement;
    private ResultSet resultSet;
    private ConnectionProvider provider;
    private BedrockJdbc db;

    record User(int id, String name) {}

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        provider = () -> connection;
        db = BedrockJdbc.of(provider);

        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
    }

    @Test
    void shouldExecuteQueryAndMapRows() throws Exception {
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getInt("id")).thenReturn(1, 2);
        when(resultSet.getString("name")).thenReturn("Ada", "Alan");

        RowMapper<User> mapper = rs -> new User(rs.getInt("id"), rs.getString("name"));
        List<User> users = db.query("SELECT id, name FROM users WHERE id > ?", mapper, 0);

        assertEquals(2, users.size());
        assertEquals(new User(1, "Ada"), users.get(0));
        assertEquals(new User(2, "Alan"), users.get(1));

        // Verify parameters set with 1-based indexing
        verify(statement).setObject(1, 0);
        // Verify try-with-resources closed resources
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void shouldQueryForObjectOptional() throws Exception {
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getInt("id")).thenReturn(42);
        when(resultSet.getString("name")).thenReturn("Grace");

        RowMapper<User> mapper = rs -> new User(rs.getInt("id"), rs.getString("name"));
        Optional<User> user = db.queryForObject("SELECT id, name FROM users WHERE id = ?", mapper, 42);

        assertTrue(user.isPresent());
        assertEquals(42, user.get().id());
        assertEquals("Grace", user.get().name());

        // Test empty query result
        when(resultSet.next()).thenReturn(false);
        Optional<User> empty = db.queryForObject("SELECT id, name FROM users WHERE id = ?", mapper, 999);
        assertTrue(empty.isEmpty());
    }

    @Test
    void shouldExecuteUpdateAndReturnRowCount() throws Exception {
        when(statement.executeUpdate()).thenReturn(1);

        int rows = db.update("UPDATE users SET name = ? WHERE id = ?", "New Name", 1);

        assertEquals(1, rows);
        verify(statement).setObject(1, "New Name");
        verify(statement).setObject(2, 1);
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void shouldInsertAndRetrieveGeneratedKey() throws Exception {
        ResultSet generatedKeys = mock(ResultSet.class);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true);
        when(generatedKeys.getLong(1)).thenReturn(105L);

        long key = db.insertAndGetGeneratedKey("INSERT INTO users (name) VALUES (?)", "Dennis");

        assertEquals(105L, key);
        verify(statement).setObject(1, "Dennis");
        verify(generatedKeys).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void shouldExecuteRawDdlStatement() throws Exception {
        Statement rawStmt = mock(Statement.class);
        when(connection.createStatement()).thenReturn(rawStmt);

        db.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)");

        verify(rawStmt).execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)");
        verify(rawStmt).close();
        verify(connection).close();
    }

    @Test
    void shouldWrapSqlExceptionInActionableBedrockDatabaseException() throws Exception {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("no such table: products", "HY000", 1));

        BedrockDatabaseException ex = assertThrows(BedrockDatabaseException.class, () -> {
            db.query("SELECT * FROM products", rs -> null);
        });

        assertTrue(ex.getReason().contains("no such table: products"));
        assertTrue(ex.getReason().contains("SELECT * FROM products"));
        assertTrue(ex.getAction().contains("target table does not exist"));
        assertEquals("HY000", ex.getSqlState());
        assertEquals(1, ex.getErrorCode());
    }

    @BedrockComponent
    static class UserRepositorySample {
        final BedrockJdbc db;

        @BedrockInject
        public UserRepositorySample(BedrockJdbc db) {
            this.db = db;
        }
    }

    @Test
    void shouldInjectRegisteredBedrockJdbcInstanceIntoComponents() {
        BedrockContainer container = new BedrockContainer();
        container.registerInstance(BedrockJdbc.class, db);
        container.register(UserRepositorySample.class);

        UserRepositorySample repo = container.getBean(UserRepositorySample.class);
        assertNotNull(repo);
        assertSame(db, repo.db);
    }
}
