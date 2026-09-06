package com.bedrock.example;

import java.util.List;
import java.util.Optional;

/**
 * 🎓 BEDROCK TUTORIAL: The Repository Pattern (Data Layer Abstraction)
 * 
 * A Repository isolates data access logic (SQL queries, schema mappings, database dialects)
 * from your business services (UserService).
 * 
 * WHY IS THIS CRITICAL?
 * 1. Single Responsibility Principle (SRP): Service handles business rules, Repository handles SQL.
 * 2. Swappability: You can replace SQLite with PostgreSQL, MySQL, or an in-memory mock
 *    without touching a single line of UserService code!
 */
public interface IUserRepository {
    List<UserResponse> findAll();
    Optional<UserResponse> findById(String id);
    UserResponse save(CreateUserRequest request);
    Optional<UserResponse> update(String id, UpdateUserRequest request);
    boolean deleteById(String id);
}
