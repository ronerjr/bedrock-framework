package com.bedrock.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 🎓 BEDROCK TUTORIAL: Connection Provider Abstraction
 * 
 * A Connection in JDBC is an active communication socket between the JVM
 * and the database server/engine.
 * 
 * This functional interface decouples BedrockJdbc from how connections are created:
 * 1. Simple standalone: () -> DriverManager.getConnection("jdbc:sqlite:app.db")
 * 2. Enterprise pool: dataSource::getConnection
 */
@FunctionalInterface
public interface ConnectionProvider {

    /**
     * Obtains a new or pooled database connection.
     * 
     * @return an open {@link Connection}
     * @throws SQLException if connection cannot be established
     */
    Connection getConnection() throws SQLException;
}
