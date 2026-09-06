package com.bedrock.exception;

import java.sql.SQLException;

/**
 * 🎓 BEDROCK TUTORIAL: Actionable Database Exceptions
 * 
 * In standard JDBC, database failures manifest as checked SQLExceptions containing
 * vendor-specific numeric error codes and cryptic messages.
 * 
 * BedrockDatabaseException transforms these into actionable developer feedback:
 * 1. Reason: What failed technically in the database query.
 * 2. Action: Concrete guidance on how to fix SQL syntax, table missing, or connection issues.
 */
public class BedrockDatabaseException extends BedrockException {

    private final String sql;
    private final String sqlState;
    private final int errorCode;

    public BedrockDatabaseException(String sql, SQLException cause) {
        super(formatReason(sql, cause), formatAction(cause), cause);
        this.sql = sql;
        this.sqlState = cause.getSQLState();
        this.errorCode = cause.getErrorCode();
    }

    public BedrockDatabaseException(String reason, String action, Throwable cause) {
        super(reason, action, cause);
        this.sql = null;
        this.sqlState = null;
        this.errorCode = 0;
    }

    public String getSql() {
        return sql;
    }

    public String getSqlState() {
        return sqlState;
    }

    public int getErrorCode() {
        return errorCode;
    }

    private static String formatReason(String sql, SQLException cause) {
        return "Database execution failed: " + cause.getMessage() + 
               (sql != null ? "\nFailed Query: " + sql : "");
    }

    private static String formatAction(SQLException cause) {
        String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
        if (msg.contains("syntax error") || msg.contains("near")) {
            return "Review the SQL statement syntax. Ensure column names and SQL keywords (SELECT, FROM, WHERE) are valid.";
        }
        if (msg.contains("no such table") || msg.contains("table not found") || msg.contains("doesn't exist")) {
            return "The target table does not exist. Ensure your schema bootstrap (e.g. CREATE TABLE IF NOT EXISTS) has run.";
        }
        if (msg.contains("unique constraint") || msg.contains("primary key")) {
            return "A uniqueness constraint was violated. Check if a record with the same unique key or ID already exists.";
        }
        return "Check database connection parameters, credentials, network connectivity, and SQL query structure.";
    }
}
