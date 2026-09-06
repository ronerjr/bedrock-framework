package com.bedrock.example;

import com.bedrock.jdbc.BedrockJdbc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryDatabaseTest {

    private File tempDbFile;
    private BedrockJdbc db;
    private SqliteUserRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        tempDbFile = File.createTempFile("bedrock-test-", ".db");
        tempDbFile.deleteOnExit();
        db = BedrockJdbc.of("jdbc:sqlite:" + tempDbFile.getAbsolutePath());
        repository = new SqliteUserRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (tempDbFile != null && tempDbFile.exists()) {
            tempDbFile.delete();
        }
    }

    @Test
    void shouldPerformCompleteCrudLifecycle() {
        // 1. Initially empty
        List<UserResponse> initialList = repository.findAll();
        assertTrue(initialList.isEmpty());

        // 2. Create / Insert
        UserResponse created = repository.save(new CreateUserRequest("Grace Hopper", "Compiler Pioneer"));
        assertNotNull(created.id());
        assertEquals("Grace Hopper", created.name());
        assertEquals("Compiler Pioneer", created.level());

        // 3. Find by ID
        Optional<UserResponse> found = repository.findById(created.id());
        assertTrue(found.isPresent());
        assertEquals("Grace Hopper", found.get().name());

        // 4. Find all
        List<UserResponse> list = repository.findAll();
        assertEquals(1, list.size());
        assertEquals("Grace Hopper", list.get(0).name());

        // 5. Update
        Optional<UserResponse> updated = repository.update(created.id(), new UpdateUserRequest("Grace Brewster Hopper", "Rear Admiral"));
        assertTrue(updated.isPresent());
        assertEquals("Grace Brewster Hopper", updated.get().name());
        assertEquals("Rear Admiral", updated.get().level());

        // Verify updated in DB
        Optional<UserResponse> foundAfterUpdate = repository.findById(created.id());
        assertTrue(foundAfterUpdate.isPresent());
        assertEquals("Grace Brewster Hopper", foundAfterUpdate.get().name());

        // 6. Delete
        boolean deleted = repository.deleteById(created.id());
        assertTrue(deleted);

        // Verify deleted from DB
        Optional<UserResponse> foundAfterDelete = repository.findById(created.id());
        assertTrue(foundAfterDelete.isEmpty());
    }

    @Test
    void shouldSafelyHandleSqlInjectionPayloads() {
        // Attack payload attempting SQL Injection
        String maliciousName = "Robert'); DROP TABLE users; --";
        UserResponse created = repository.save(new CreateUserRequest(maliciousName, "Hacker"));

        assertNotNull(created.id());

        // Verify table still exists and data was stored verbatim, not executed
        Optional<UserResponse> found = repository.findById(created.id());
        assertTrue(found.isPresent());
        assertEquals("Robert'); DROP TABLE users; --", found.get().name());

        // Verify table was not dropped
        List<UserResponse> all = repository.findAll();
        assertEquals(1, all.size());
    }
}
