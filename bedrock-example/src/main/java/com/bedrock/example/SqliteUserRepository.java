package com.bedrock.example;

import com.bedrock.ioc.BedrockComponent;
import com.bedrock.ioc.BedrockInject;
import com.bedrock.jdbc.BedrockJdbc;
import com.bedrock.jdbc.RowMapper;

import java.util.List;
import java.util.Optional;

/**
 * 🎓 BEDROCK TUTORIAL: Concrete SQL Repository Implementation
 * 
 * Notice how clean this class is:
 * 1. Constructor Injection of BedrockJdbc.
 * 2. Pure SQL queries using parameterized '?' placeholders to prevent SQL Injection.
 * 3. RowMapper lambda mapping tabular rows directly into the Java 21 UserResponse Record!
 */
@BedrockComponent
public class SqliteUserRepository implements IUserRepository {

    private final BedrockJdbc db;

    // RowMapper: translates each row of the ResultSet into our UserResponse Record
    private final RowMapper<UserResponse> userMapper = rs -> new UserResponse(
        String.valueOf(rs.getInt("id")),
        rs.getString("name"),
        rs.getString("level")
    );

    @BedrockInject
    public SqliteUserRepository(BedrockJdbc db) {
        this.db = db;
        initTable();
    }

    /**
     * Bootstraps the schema automatically if the table does not exist.
     */
    private void initTable() {
        db.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                level TEXT NOT NULL
            );
        """);
    }

    @Override
    public List<UserResponse> findAll() {
        return db.query("SELECT id, name, level FROM users ORDER BY id ASC", userMapper);
    }

    @Override
    public Optional<UserResponse> findById(String id) {
        return db.queryForObject("SELECT id, name, level FROM users WHERE id = ?", userMapper, id);
    }

    @Override
    public UserResponse save(CreateUserRequest request) {
        long generatedId = db.insertAndGetGeneratedKey(
            "INSERT INTO users (name, level) VALUES (?, ?)",
            request.name(),
            request.level()
        );
        return new UserResponse(String.valueOf(generatedId), request.name(), request.level());
    }

    @Override
    public Optional<UserResponse> update(String id, UpdateUserRequest request) {
        int rows = db.update(
            "UPDATE users SET name = ?, level = ? WHERE id = ?",
            request.name(),
            request.level(),
            id
        );
        if (rows == 0) {
            return Optional.empty();
        }
        return Optional.of(new UserResponse(id, request.name(), request.level()));
    }

    @Override
    public boolean deleteById(String id) {
        int rows = db.update("DELETE FROM users WHERE id = ?", id);
        return rows > 0;
    }
}
