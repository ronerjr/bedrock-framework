# Bedrock Framework Milestone 4: Backend Architecture & Design
## Real-Time Chat Demo (`ChatWebSocket.java`) & Application Integration (`Application.java`)

**Author**: `explorer_m4_1`  
**Target Module**: `bedrock-example` (`com.bedrock.example.ws`, `com.bedrock.example`)  
**Date**: 2026-09-11  
**Status**: DESIGN COMPLETE — Ready for Implementation  

---

## 1. Executive Summary & Architecture Overview

Milestone 4 delivers the user-facing demonstration of the Bedrock Version 2.0 Real-Time WebSocket engine within the `bedrock-example` module. In accordance with the central design philosophy outlined in `ORIGINAL_REQUEST.md`:

1. **Anti-Magic Architecture**:
   No classpath scanning, no bytecode weaving, no runtime proxies, and no hidden background threads. Everything is explicitly registered via `app.enableWebSockets(port)` and `app.register(ChatWebSocket.class)`.
2. **Project Loom Concurrency**:
   Every incoming WebSocket connection is executed on its own dedicated Java 21 Virtual Thread (`Thread.ofVirtual().name("ws-client-", ...)`). Message broadcasting across multiple clients is safe, simple, and linear, leveraging synchronous blocking I/O without the complexity of reactive streams or Netty event loops.
3. **Dual-Server Orchestration**:
   `BedrockApp` explicitly manages two zero-dependency engines:
   - JDK `HttpServer` bound to port 8080 for REST endpoints, middleware, and the `/chat` HTML5 testing client.
   - Java NIO `BedrockWebSocketServer` bound to port 8081 for full-duplex RFC 6455 WebSockets.
4. **Pedagogical Standard (🎓 BEDROCK TUTORIAL)**:
   All code features in-depth educational documentation explaining the protocol mechanics: frame dissection, XOR unmasking, carrier thread unpinning with `ReentrantLock`, and resilient broadcast loops.

---

## 2. Component Design: `ChatWebSocket.java`

### 2.1 File Location
```
bedrock-example/
└── src/main/java/com/bedrock/example/ws/ChatWebSocket.java
```

### 2.2 Core Responsibilities & Behavioral Contracts
- **Route Binding**: Annotated with `@BedrockSocket("/chat")`. Normalized by the server to `/chat`. (Compatible with `@BedrockSocket(path = "/chat")` or `/ws/chat`).
- **Session Tracking**: Maintains active client connections using a thread-safe `ConcurrentMap<String, BedrockWebSocketSession>`.
- **Lifecycle Events**:
  - `@OnOpen`: Invoked upon successful HTTP 101 upgrade handshake. Tracks the session, assigns a display identifier, greets the connecting client, and broadcasts a join notification to all connected clients.
  - `@OnMessage`: Invoked when an RFC 6455 unmasked text frame arrives. Parses commands (such as `/nick <name>`) or formats and broadcasts user chat messages as `[User <id>] <message>` to all active clients.
  - `@OnClose`: Invoked on client disconnect or clean two-way close handshake. Prunes the session and broadcasts a departure message `[System] User <id> left. Total users: N`.
  - `@OnError`: Invoked on connection error or protocol exception. Safely logs the error via `BedrockLogger` without rethrowing or affecting the server accept loop.
- **Resilient Multi-Client Broadcast**:
  - `broadcast(String message)` iterates over the snapshot of active sessions.
  - Wraps each individual client transmission in an isolated `try / catch` error boundary: if Client A drops its connection unexpectedly, Client A is pruned, and delivery continues uninterrupted to Clients B, C, and D.
- **Testing Inspection API**:
  - `getConnectedCount()`: Returns active session count for assertions in unit/integration tests.
  - `hasSession(String id)`: Checks if a session ID is present and active.
  - `getSessions()`: Returns an unmodifiable collection of active sessions.
  - `clear()`: Clears tracked sessions for test teardown.

### 2.3 Concurrency Model & Loom Thread Safety
1. **Virtual Thread Friendly Locking**:
   Each client executes its frame read loop on a dedicated Virtual Thread. While Java's `SocketChannel.write()` is not thread-safe for concurrent writes, `BedrockWebSocketSession` guards its channel using `java.util.concurrent.locks.ReentrantLock`. Unlike `synchronized(lock)`, `ReentrantLock` **does not pin the virtual thread to its OS carrier thread** during contention or blocking I/O, ensuring zero carrier starvation.
2. **Concurrent Iteration**:
   `ConcurrentHashMap` guarantees a weakly consistent iterator during broadcast. If clients join or disconnect while a broadcast is underway, no `ConcurrentModificationException` is ever thrown.

---

## 3. Complete Source Code: `ChatWebSocket.java`

```java
package com.bedrock.example.ws;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.server.BedrockWebSocketSession;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🎓 BEDROCK TUTORIAL: Real-Time Multi-Client Chat WebSocket & Project Loom Concurrency
 *
 * <p>This class implements an interactive, multi-client real-time chat room using
 * Bedrock Version 2.0 declarative WebSocket annotations. It demonstrates how Java 21
 * Virtual Threads (Project Loom) make building high-concurrency real-time systems
 * simple, transparent, and resilient without requiring complex reactive frameworks or Netty.</p>
 *
 * <h3>1. The Anti-Magic Declarative Model:</h3>
 * <p>In Spring Boot or Quarkus, WebSockets often require multiple layers of abstraction
 * (STOMP brokers, message templates, dynamic proxy handlers). In Bedrock:
 * <ul>
 *   <li>{@link BedrockSocket} explicitly maps this endpoint to the URI path {@code /chat}.</li>
 *   <li>The class is registered explicitly via {@code app.register(ChatWebSocket.class)}.</li>
 *   <li>Lifecycle events ({@link OnOpen}, {@link OnMessage}, {@link OnClose}, {@link OnError})
 *       are invoked directly via reflection with zero dynamic bytecode proxies.</li>
 * </ul>
 * </p>
 *
 * <h3>2. The Concurrency Paradigm: Virtual Threads & Carrier-Friendly Locks:</h3>
 * <p>Every client connection is assigned a dedicated Virtual Thread by Bedrock:
 * <pre>{@code
 *   Thread.ofVirtual().name("ws-client-", clientId).start(...)
 * }</pre>
 * Virtual threads are lightweight (~300 bytes of heap memory). The JVM dynamically
 * multiplexes millions of virtual threads onto a small pool of OS carrier threads.
 * </p>
 * <p><b>Why ReentrantLock is Essential:</b><br>
 * If connection writes were guarded by traditional {@code synchronized (lock)} blocks,
 * any virtual thread waiting for a lock would <b>pin</b> its underlying OS carrier thread,
 * preventing other virtual threads from executing. Bedrock's {@link BedrockWebSocketSession}
 * uses {@link java.util.concurrent.locks.ReentrantLock}, allowing waiting virtual threads
 * to unmount and yield their carrier threads cleanly.
 * </p>
 *
 * <h3>3. The Zero-Failure Resilient Broadcast Loop:</h3>
 * <p>When broadcasting a message to 1,000 connected clients, a common server bug is the
 * "brittle broadcast loop": if Client #42 drops its connection abruptly, an uncaught
 * exception stops the loop, starving Clients #43 through #1,000.
 * In {@link #broadcast(String)}, each transmission is wrapped in an isolated error boundary.
 * If delivery to a client fails, that session is logged, pruned from memory, and the loop
 * continues delivering to all remaining healthy clients uninterrupted.</p>
 *
 * @see BedrockSocket
 * @see OnOpen
 * @see OnMessage
 * @see OnClose
 * @see OnError
 * @see BedrockWebSocketSession
 */
@BedrockSocket("/chat")
public class ChatWebSocket {

    private static final String LOG_TAG = "CHAT-WS";

    /**
     * Thread-safe registry of connected sessions.
     * Backed by ConcurrentHashMap to allow concurrent additions, removals,
     * and safe iterations without global mutex bottlenecks.
     */
    private final Map<String, BedrockWebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Connection Opened
     *
     * <p>Triggered immediately after the HTTP 101 Switching Protocols upgrade handshake
     * completes. The underlying TCP socket is now in raw full-duplex WebSocket mode.</p>
     *
     * @param session The newly established client session.
     */
    @OnOpen
    public void onOpen(BedrockWebSocketSession session) {
        sessions.put(session.getId(), session);
        String shortId = getShortId(session.getId());
        BedrockLogger.info(LOG_TAG, "Client connected: " + shortId + " [Total active: " + sessions.size() + "]");

        // 1. Send personalized welcome greeting to the connecting user
        session.send("[System] Welcome to Bedrock Real-Time Chat! Connected as User " + shortId + ".");

        // 2. Broadcast join notification to all connected clients
        broadcast("[System] User " + shortId + " joined. Total users: " + sessions.size());
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Message Received
     *
     * <p>Triggered when an incoming RFC 6455 text frame arrives from the client.
     * The frame has already been validated, XOR-unmasked with the 4-byte client key,
     * and confirmed as valid UTF-8 by Bedrock's protocol engine.</p>
     *
     * @param session The session of the client sending the message.
     * @param message The unmasked UTF-8 text message payload.
     */
    @OnMessage
    public void onMessage(BedrockWebSocketSession session, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String text = message.trim();
        String shortId = getShortId(session.getId());

        // Support optional dynamic nickname change: /nick <name>
        if (text.startsWith("/nick ")) {
            String newNick = text.substring(6).trim();
            if (!newNick.isBlank()) {
                String oldName = getDisplayName(session);
                session.setAttribute("username", newNick);
                BedrockLogger.info(LOG_TAG, "User " + shortId + " changed name to: " + newNick);
                broadcast("[System] " + oldName + " is now known as " + newNick);
                return;
            }
        }

        // Standard user broadcast: [User <id>] <message> or [<nickname>] <message>
        String sender = getDisplayName(session);
        BedrockLogger.info(LOG_TAG, "Broadcasting from " + sender + ": " + text);
        broadcast("[" + sender + "] " + text);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Connection Closed
     *
     * <p>Triggered when a client initiates disconnection (RFC 6455 Close frame Opcode 0x8),
     * or when the connection terminates due to network loss.</p>
     *
     * @param session    The disconnecting session.
     * @param statusCode RFC 6455 status code (e.g. 1000 Normal, 1001 Going Away).
     * @param reason     Human-readable UTF-8 reason string.
     */
    @OnClose
    public void onClose(BedrockWebSocketSession session, int statusCode, String reason) {
        sessions.remove(session.getId(), session);
        String sender = getDisplayName(session);
        BedrockLogger.info(LOG_TAG, "Client disconnected: " + sender +
                " [Status: " + statusCode + ", Reason: '" + reason + "'] [Remaining: " + sessions.size() + "]");

        if (!sessions.isEmpty()) {
            broadcast("[System] " + sender + " left. Total users: " + sessions.size());
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Error Isolation Boundary
     *
     * <p>Catches protocol errors or unexpected exceptions during full-duplex operation.
     * Guarantees errors are safely logged without crashing the server accept loop.</p>
     *
     * @param session   The affected session, or null if error occurred pre-handshake.
     * @param throwable The caught exception or error.
     */
    @OnError
    public void onError(BedrockWebSocketSession session, Throwable throwable) {
        String id = (session != null) ? getShortId(session.getId()) : "unknown";
        String msg = (throwable != null) ? throwable.getMessage() : "Unknown error";
        BedrockLogger.warn(LOG_TAG, "WebSocket error on session [" + id + "]: " + msg);
    }

    // =========================================================================
    // Multi-Client Broadcast Engine & Error Boundaries
    // =========================================================================

    /**
     * Broadcasts a text message across all active connected sessions.
     * Employs isolated per-client error boundaries: if an individual client write fails,
     * the faulty session is pruned and delivery proceeds to all other clients.
     *
     * @param message The text message to broadcast.
     */
    public void broadcast(String message) {
        if (message == null) {
            return;
        }

        for (BedrockWebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                sessions.remove(session.getId(), session);
                continue;
            }

            try {
                session.send(message);
            } catch (Exception e) {
                BedrockLogger.warn(LOG_TAG, "Failed to send to session " + session.getId() + ": " + e.getMessage());
                sessions.remove(session.getId(), session);
                try {
                    session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Broadcast delivery failure");
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * Broadcasts a text message to all active sessions EXCEPT the specified excluded session.
     * Useful for peer echo patterns.
     *
     * @param message           The text message to broadcast.
     * @param excludedSessionId The session ID to skip.
     */
    public void broadcastExcept(String message, String excludedSessionId) {
        if (message == null) {
            return;
        }

        for (BedrockWebSocketSession session : sessions.values()) {
            if (session.getId().equals(excludedSessionId)) {
                continue;
            }
            if (!session.isOpen()) {
                sessions.remove(session.getId(), session);
                continue;
            }

            try {
                session.send(message);
            } catch (Exception e) {
                BedrockLogger.warn(LOG_TAG, "Failed to send to session " + session.getId() + ": " + e.getMessage());
                sessions.remove(session.getId(), session);
                try {
                    session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Broadcast delivery failure");
                } catch (Exception ignore) {
                }
            }
        }
    }

    // =========================================================================
    // Diagnostic & Inspection API (Used in Integration Tests)
    // =========================================================================

    /**
     * Returns the number of currently active sessions in the chat room.
     *
     * @return Active session count.
     */
    public int getConnectedCount() {
        return sessions.size();
    }

    /**
     * Checks whether a session ID is currently registered.
     *
     * @param sessionId Session identifier.
     * @return True if registered and tracked.
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Returns an unmodifiable snapshot of active sessions.
     *
     * @return Active sessions collection.
     */
    public Collection<BedrockWebSocketSession> getSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Clears all tracked sessions (used in test teardown).
     */
    public void clear() {
        sessions.clear();
    }

    // =========================================================================
    // Internal Helper Methods
    // =========================================================================

    private String getShortId(String sessionId) {
        if (sessionId == null) return "unknown";
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    private String getDisplayName(BedrockWebSocketSession session) {
        if (session == null) return "Unknown";
        Object custom = session.getAttribute("username");
        if (custom instanceof String nick && !nick.isBlank()) {
            return nick.trim();
        }
        return "User " + getShortId(session.getId());
    }
}
```

---

## 4. Component Design: `Application.java` Updates

### 4.1 File Location
```
bedrock-example/
└── src/main/java/com/bedrock/example/Application.java
```

### 4.2 Changes & Architectural Enhancements
1. **Enable WebSockets on Port 8081**:
   `app.enableWebSockets(8081)` initializes the native NIO `BedrockWebSocketServer`.
2. **Serve the Interactive `/chat` Testing Client**:
   Maps an HTTP GET route `/chat`:
   `app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml()));`
   which serves the dark-themed HTML/JS client designed by `explorer_m4_2`.
3. **Register `ChatWebSocket.class`**:
   Passed into `app.register(...)` alongside existing repositories, services, and controllers:
   `app.register(SqliteUserRepository.class, UserService.class, UserController.class, ChatWebSocket.class);`
4. **Testable Factory Method (`createApp`)**:
   Refactor server initialization into `createApp(int httpPort, int wsPort)`. This allows automated tests in `bedrock-example` (such as `ChatWebSocketTest`) to pass `createApp(0, 0)` for collision-free testing on ephemeral OS ports, while `main(args)` continues to run on standard ports 8080 and 8081.
5. **100% Backward Compatibility Guarantee**:
   All previous REST routes (`/api/ping`, `/bedrock/ui`, `/api/users`), middlewares (`before`, `after`), SQLite database initialization, and exception handlers remain completely unchanged.

### 4.3 Proposed Code for `Application.java`

```java
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
        app.get("/api/ping", ctx -> ctx.ok("pong"));

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
        app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml()));

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
```

---

## 5. Peer Integration Contract with `explorer_m4_2`

To ensure seamless coordination between backend and frontend/tests:

### 5.1 Wire Protocol & Message Formatting Contract
| Event | Direction | Wire Format / Payload Example | Description |
|---|---|---|---|
| Handshake Upgrade | Client → Server | `GET /chat HTTP/1.1`, `Upgrade: websocket`, etc. | Standard RFC 6455 handshake to WS port |
| Handshake Response | Server → Client | `HTTP/1.1 101 Switching Protocols`, etc. | Established connection promoted to full-duplex |
| Welcome Greeting | Server → Client | `[System] Welcome to Bedrock Real-Time Chat! Connected as User a1b2c3d4.` | Direct message to connected client |
| User Joined Event | Server → Client(s) | `[System] User a1b2c3d4 joined. Total users: 1` | Broadcast notification on connection open |
| Chat Message In | Client → Server | `Hello World!` (unmasked client text frame) | Raw text frame sent by user |
| Chat Message Out | Server → Client(s) | `[User a1b2c3d4] Hello World!` | Broadcast to all active chat clients |
| Nickname Change In | Client → Server | `/nick Ada` | Optional command to change display name |
| Nickname Broadcast | Server → Client(s) | `[System] User a1b2c3d4 is now known as Ada` | Broadcast notification of name change |
| User Left Event | Server → Client(s) | `[System] User a1b2c3d4 left. Total users: 0` | Broadcast notification on disconnect |
| Clean Close | Either direction | Opcode `0x8` with Code `1000 (Normal Closure)` | Two-way close handshake |

### 5.2 Ports & Endpoints
- **HTTP UI Route**: `http://localhost:8080/chat`
- **WebSocket Endpoint**: `ws://localhost:8081/chat`
- **Dynamic Port Detection in Playground**:
  The `ChatPlayground.java` client developed by `explorer_m4_2` provides an input field for the WebSocket URL that defaults to `ws://` + `window.location.hostname` + `:8081/chat`. This allows manual overrides when running against ephemeral ports in local development or CI.

---

## 6. Verification & Test Strategy

### 6.1 Unit & Integration Test Scenarios (`ChatWebSocketTest`)
1. **Connection & Welcome**: Connect via JDK `HttpClient.newWebSocketBuilder()`. Assert receipt of `[System] Welcome...` and `[System] User ... joined...`.
2. **Bidirectional Broadcast**: Send `"Hello from Test Client"`. Assert receiving `[User ...] Hello from Test Client`.
3. **Multi-Client Concurrency**: Open 3 parallel WebSocket connections on Virtual Threads. Send message from Client 1, assert arrival on Clients 2 and 3 within 2 seconds.
4. **Nickname Command**: Send `"/nick TestBot"`. Assert receiving `[System] User ... is now known as TestBot`. Send `"Bot reporting in"`. Assert receiving `[TestBot] Bot reporting in`.
5. **Clean Disconnect & Eviction**: Close Client 1 with status code 1000. Assert remaining clients receive `[System] ... left. Total users: 2`. Assert `chatSocket.getConnectedCount() == 2`.
6. **Zero Port Leaks**: Wrap `Application.createApp(0, 0)` in a `try-with-resources` block (`app.close()`). Verify HTTP and WebSocket ports are immediately released back to the OS.

### 6.2 Regression Safety
- 81 baseline framework tests in `bedrock-core` and `bedrock-example` will continue passing with 100% green status.
- Zero dependencies added to `bedrock-example/pom.xml` or `bedrock-core/pom.xml`.
