package com.bedrock.ioc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockLifecycleTest {

    static final List<String> EVENTS = new ArrayList<>();

    @BedrockComponent
    static class DatabaseService {
        boolean initialized = false;
        boolean destroyed = false;

        @BedrockInit
        public void startDb() {
            initialized = true;
            EVENTS.add("DatabaseService:init");
        }

        @BedrockDestroy
        public void closeDb() {
            destroyed = true;
            EVENTS.add("DatabaseService:destroy");
        }
    }

    @BedrockComponent
    static class UserRepository {
        final DatabaseService db;
        boolean initialized = false;
        boolean destroyed = false;

        @BedrockInject
        public UserRepository(DatabaseService db) {
            this.db = db;
        }

        @BedrockInit
        public void initRepo() {
            initialized = true;
            EVENTS.add("UserRepository:init");
        }

        @BedrockDestroy
        public void closeRepo() {
            destroyed = true;
            EVENTS.add("UserRepository:destroy");
        }
    }

    @Test
    @DisplayName("Verify @BedrockInit runs in order and @BedrockDestroy runs in reverse topological order")
    void shouldExecuteLifecycleInCorrectTopologicalOrder() {
        EVENTS.clear();
        BedrockContainer container = new BedrockContainer();

        // Register together: IoC registers all classes into registeredClasses first, then instantiates
        container.register(UserRepository.class, DatabaseService.class);

        UserRepository repo = container.getBean(UserRepository.class);
        DatabaseService db = container.getBean(DatabaseService.class);

        assertNotNull(repo);
        assertNotNull(db);
        assertTrue(db.initialized, "DatabaseService must be initialized");
        assertTrue(repo.initialized, "UserRepository must be initialized");

        assertEquals(List.of("DatabaseService:init", "UserRepository:init"), EVENTS);

        container.destroy();

        assertTrue(repo.destroyed, "UserRepository must be destroyed");
        assertTrue(db.destroyed, "DatabaseService must be destroyed");

        assertEquals(
                List.of("DatabaseService:init", "UserRepository:init", "UserRepository:destroy", "DatabaseService:destroy"),
                EVENTS,
                "Teardown must occur in reverse topological order"
        );
    }
}
