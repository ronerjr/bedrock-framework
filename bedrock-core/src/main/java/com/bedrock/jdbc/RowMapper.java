package com.bedrock.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 🎓 BEDROCK TUTORIAL: The RowMapper Pattern
 * 
 * In relational databases, query results arrive as a tabular cursor called a 'ResultSet'.
 * A RowMapper is a functional interface that translates the current row of that cursor
 * into a strongly-typed Java object or Record:
 * 
 * <pre>{@code
 * RowMapper<UserResponse> userMapper = rs -> new UserResponse(
 *     String.valueOf(rs.getInt("id")),
 *     rs.getString("name"),
 *     rs.getString("level")
 * );
 * }</pre>
 * 
 * Every modern ORM and data library (like Spring JdbcTemplate, jOOQ, or Hibernate)
 * is built on top of this exact primitive concept!
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Maps the current row of the given ResultSet to a domain object.
     * 
     * @param rs the ResultSet positioned at the current row
     * @return the mapped object
     * @throws SQLException if a database access error occurs
     */
    T mapRow(ResultSet rs) throws SQLException;
}
