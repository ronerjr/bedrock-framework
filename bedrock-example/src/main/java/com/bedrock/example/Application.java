package com.bedrock.example;

import com.bedrock.core.BedrockApp;
import com.bedrock.example.ws.ChatPlayground;
import com.bedrock.example.ws.ChatWebSocket;
import com.bedrock.jdbc.BedrockJdbc;

public class Application {

    /**
     * Factory method creating and configuring the Bedrock application instance.
     * Parameterized ports allow clean ephemeral port allocation (port 0) during automated testing.
     *
     * @param httpPort HTTP server port (e.g. 8080, or 0 for dynamic ephemeral port).
     * @param wsPort   WebSocket server port (e.g. 8081, or 0 for dynamic ephemeral port). Use -1 to disable WebSockets.
     * @return Fully configured BedrockApp instance.
     */
    public static BedrockApp createApp(int httpPort, int wsPort) {
        BedrockApp app = BedrockApp.create(httpPort);
        
        // 1. Before Middleware: Intercepts before the Handler
        app.before(ctx -> {
            System.out.println("[LOG] 🪵 Request intercepted at: " + ctx.path());
        });
        
        // 2. After Middleware: Intercepts before flushing to network
        app.after(ctx -> {
            ctx.setHeader("X-Powered-By", "Bedrock-Java-21");
            ctx.setHeader("Access-Control-Allow-Origin", "*");
        });

        // 3. Simple programmatic route
        app.get("/api/ping", ctx -> ctx.text("pong"));

        // 4. Persistence Setup (V1.3 - Zero-Dependency JDBC):
        // Configure BedrockJdbc with a local SQLite database file ("bedrock.db")
        BedrockJdbc db = BedrockJdbc.of("jdbc:sqlite:bedrock.db");
        app.registerInstance(BedrockJdbc.class, db);

        // Seed initial data if database is brand new
        seedInitialData(db);

        // 5. Interface Inversion (SOLID 'D'):
        // Decouple controllers from concrete service and repository implementations.
        app.bind(IUserRepository.class, SqliteUserRepository.class);
        app.bind(IUserService.class, UserService.class);

        // 6. Global Exception Handling:
        // Centralized domain exception handling eliminates boilerplate try/catch in controllers.
        app.onError(UserNotFoundException.class, (ctx, ex) -> {
            ctx.notFound(java.util.Map.of(
                "error", ex.getMessage(),
                "status", 404
            ));
        });

        // 7. Real-Time WebSockets Setup (V2.0 - RFC 6455 over Virtual Threads):
        // Enable dedicated NIO WebSocket server on configured port with zero external dependencies.
        if (wsPort >= 0) {
            app.enableWebSockets(wsPort);
        }

        // 8. Serve Interactive Real-Time Chat Playground:
        // Dark-themed HTML5/JS testing client communicating bidirectionally with ChatWebSocket.
        app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml(app.getWebSocketPort(), "/chat")));

        // 9. Explicit Component Registration & Dependency Injection:
        // Bedrock builds the dependency graph and registers both HTTP controllers and WebSocket endpoints:
        // UserController -> IUserService (UserService) -> IUserRepository (SqliteUserRepository) -> BedrockJdbc
        // ChatWebSocket  -> Registered as @BedrockSocket("/chat") on the WebSocket engine
        app.register(
                SqliteUserRepository.class,
                UserService.class,
                UserController.class,
                ChatWebSocket.class
        );

        return app;
    }

    public static void main(String[] args) {
        createApp(8080, 8081).start();
    }

    private static void seedInitialData(BedrockJdbc db) {
        db.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                level TEXT NOT NULL
            );
        """);

        Integer count = db.queryForObject("SELECT COUNT(*) AS total FROM users", rs -> rs.getInt("total")).orElse(0);
        if (count == 0) {
            db.update("INSERT INTO users (id, name, level) VALUES (?, ?, ?)", 1, "Ada Lovelace", "JVM Expert");
            db.update("INSERT INTO users (id, name, level) VALUES (?, ?, ?)", 2, "Alan Turing", "Algorithm Master");
        }
    }
}
