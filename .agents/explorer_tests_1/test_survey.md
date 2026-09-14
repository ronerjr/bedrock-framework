# Bedrock Java Framework — WebSocket v2.0 Testing & Example Survey

**Target Component**: Test Suites, `bedrock-example` Module, and RFC 6455 WebSocket Testing Strategy  
**Milestone**: Version 2.0 Real-Time WebSockets  
**Author**: `explorer_tests_1`  
**Date**: 2026-09-11  

---

## Executive Summary

Bedrock Java currently contains **81 automated test methods** across 12 test classes in two Maven modules (`bedrock-core` and `bedrock-example`). All current HTTP tests are isolated unit tests that mock the Sun `HttpExchange` object; no existing test starts a live socket server or connects over the wire. Furthermore, `BedrockApp` lacks a `stop()` method, which has historically prevented running live integration servers during tests.

For Version 2.0 (RFC 6455 Real-Time WebSockets), we have designed a **three-tier zero-dependency testing strategy** built entirely on the standard JDK 21 library:
1. **Tier 1 (Unit & Vectors)**: RFC 6455 Section 1.3 official SHA-1/Base64 handshake vector tests and binary buffer frame codec tests.
2. **Tier 2 (High-Level E2E Client)**: Full lifecycle testing using `java.net.http.HttpClient` / `java.net.http.WebSocket` for real-time text exchange, broadcast, ping/pong, and graceful close.
3. **Tier 3 (Raw Byte-Level Wire Protocol)**: Low-level frame forgery using `java.net.Socket` and `SocketChannel` to verify strict RFC 6455 protocol enforcement (mandatory client masking, 1002 protocol errors on unmasked frames or bad RSV bits, extended payload lengths 126/127, control frame restrictions, and UTF-8 validation 1007).

In `bedrock-example`, we propose a real-time `ChatWebSocket` endpoint registered via `app.enableWebSockets(8081).register(ChatWebSocket.class)` paired with an educational dark-themed browser client (`/chat`), allowing developers to observe virtual thread execution and frame transmission in real time.

---

## 1. Existing Test Suite Analysis

### 1.1 Test Count & Distribution Breakdown

The codebase contains exactly **81 `@Test` methods** distributed across 12 classes:

| Module | Test Class | Methods | Focus Area |
|---|---|:---:|---|
| **bedrock-core** | `BedrockJsonEdgeCasesTest.java` | 27 | Recursive descent parser limits, scientific notation, escapes, max depth 128 |
| **bedrock-core** | `BedrockJsonTest.java` | 8 | Core JSON serialization/deserialization for records, primitives, POJOs, lists |
| **bedrock-core** | `ContextValidationTest.java` | 9 | `paramAsInt`, `paramAsLong`, `queryParamAsInt`, URL decoding, RFC 7807 400 |
| **bedrock-core** | `BedrockJdbcTest.java` | 7 | JDBC query mapping, update, key generation, DDL, error translation, IoC injection |
| **bedrock-core** | `RouterTest.java` | 6 | Static/dynamic routing (`{id}`), multi-verbs, 404, before/after middlewares |
| **bedrock-core** | `GlobalExceptionHandlerTest.java` | 5 | Custom exception handlers, hierarchy matching, `InvocationTargetException` unwrapping |
| **bedrock-core** | `BedrockIoCInterfaceBindingTest.java` | 4 | SOLID 'D' `app.bind()`, container resolution, runtime implementation switching |
| **bedrock-core** | `ContextTest.java` | 4 | Path/query params, buffered response status, `bodyAs(Record.class)`, `noContent()` 204 |
| **bedrock-core** | `BedrockContainerTest.java` | 3 | IoC constructor injection, missing dependency errors, circular dependency detection |
| **bedrock-core** | `BedrockAppTest.java` | 1 | Controller route binding, reflection-based router dispatch, GET/POST/DELETE |
| **bedrock-example** | `UserControllerTest.java` | 5 | Controller unit tests with mocked service/exchange, full Bedrock container wiring |
| **bedrock-example** | `UserRepositoryDatabaseTest.java` | 2 | End-to-end SQLite CRUD lifecycle on disk, SQL injection payload resilience |
| **TOTAL** | **12 classes** | **81** | **100% pass baseline required across all 81 tests** |

### 1.2 Testing Libraries in `pom.xml`

Inspection of `pom.xml`, `bedrock-core/pom.xml`, and `bedrock-example/pom.xml` reveals:
- **JUnit 5 Jupiter**:
  - `org.junit.jupiter:junit-jupiter-api` (`5.10.1` in modules, managed `5.10.2` in parent)
  - `org.junit.jupiter:junit-jupiter-engine` (`5.10.2`)
- **Mockito**:
  - `org.mockito:mockito-core:5.12.0`
- **AssertJ**:
  - **NOT PRESENT** in any POM file. Assertions throughout the repository exclusively use standard `org.junit.jupiter.api.Assertions.*` (`assertEquals`, `assertTrue`, `assertFalse`, `assertNotNull`, `assertNull`, `assertThrows`, `assertDoesNotThrow`, `assertSame`).
- **SQLite JDBC**:
  - `org.xerial:sqlite-jdbc:3.45.3.0` (scoped exclusively to `bedrock-example`).

### 1.3 Analysis of Existing HTTP Test Patterns

All existing HTTP test cases in `BedrockAppTest`, `ContextTest`, `ContextValidationTest`, `GlobalExceptionHandlerTest`, and `UserControllerTest` share the following pattern:
1. `HttpExchange` is mocked using Mockito: `HttpExchange exchange = mock(HttpExchange.class);`.
2. Mock stubs return request URI, method, and a `ByteArrayInputStream` for the request body.
3. A `Context` instance is manually instantiated: `Context ctx = new Context(exchange, params);`.
4. Route handlers or controllers are invoked directly in-memory, or `Router` is accessed via reflection.
5. Assertions check `ctx.getStatusCode()` and `ctx.getResponseBody()`.

**Critical Architectural Finding**:
- **No test ever invokes `app.start()` or binds to a TCP socket.**
- `BedrockApp` currently provides `public void start()`, but **does NOT provide a `stop()` or `close()` method**.
- Once started, the internal `com.sun.net.httpserver.HttpServer` cannot be cleanly shut down from outside.
- **Requirement for WebSocket v2.0**: Both `BedrockWebSocketServer` and `BedrockApp` must implement `AutoCloseable` (or provide explicit `stop()` methods) so integration tests can spin up ephemeral servers and cleanly terminate them without port exhaustion or thread leaks.

---

## 2. WebSocket Testing Capabilities (JDK 21 Native)

One of Bedrock's core tenets is **Zero External Dependencies**. We can test WebSockets thoroughly using strictly the standard JDK 21 runtime.

### 2.1 Tier 1: Unit Tests & RFC 6455 Test Vectors (Zero Network Overhead)

Unit tests run in microseconds without opening TCP ports:
1. **RFC 6455 §1.3 Handshake Test Vector**:
   - Official Key: `"dGhlIHNhbXBsZSBub25jZQ=="`
   - Magic GUID: `"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"`
   - SHA-1 Digest: `0xb3 0x7a 0x4f 0x2c 0xc0 0x62 0x4f 0x16 0x90 0xf6 0x46 0x06 0xcf 0x38 0x59 0x45 0xb2 0xbe 0xc4 0xea`
   - Expected `Sec-WebSocket-Accept`: `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`
   - Test class `WebSocketHandshakeTest` can assert this exact equality, plus validation of invalid keys, missing headers, and HTTP 101 status formatting.
2. **Frame Parsing & Serialization (`WebSocketFrameTest`)**:
   - Frame header encoding: FIN bit, Opcode (0x1 Text, 0x2 Binary, 0x8 Close, 0x9 Ping, 0xA Pong).
   - Masking XOR algorithm: verify roundtrip `payload ^ mask ^ mask == payload`.
   - Length encoding:
     - Short payload: length `<= 125` bytes (single length byte).
     - Medium payload: length `126..65535` bytes (length byte `126` + 2-byte unsigned short).
     - Large payload: length `> 65535` bytes (length byte `127` + 8-byte unsigned long).

### 2.2 Tier 2: End-to-End Testing with `java.net.http.HttpClient`

Java 11+ introduced `java.net.http.WebSocket`, part of the standard library in Java 21 (`java.net.http.*`). This provides an authentic RFC 6455 client.

```java
HttpClient client = HttpClient.newHttpClient();
CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            receivedQueue.offer(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pongReceived.countDown();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReceived.countDown();
            return null;
        }
    });
```

**Capabilities verified with `HttpClient`**:
- Successful HTTP 101 Upgrade and Handshake negotiation.
- Bidirectional text messaging: `ws.sendText("Hello Bedrock", true)` -> server receives and triggers `@OnMessage`.
- Server-to-client broadcast: multi-client fan-out.
- Server Ping / Pong: `ws.sendPing(...)` -> server responds with Opcode 0xA Pong.
- Graceful close handshake: `ws.sendClose(1000, "Bye")` -> server echoes close and terminates virtual thread.

### 2.3 Tier 3: Raw Wire Protocol Testing with `java.net.Socket` / `SocketChannel`

Standard clients like `HttpClient` or browsers are designed to follow the specification and will **never deliberately violate RFC 6455**. To build an enterprise-grade, pedagogical engine, we must test how the server responds when clients misbehave.

Using raw `java.net.Socket` or `SocketChannel`, tests can construct explicit byte arrays and inspect raw server responses:

| Test Scenario | Wire Byte Sequence / Attack | RFC 6455 Requirement | Expected Server Behavior |
|---|---|---|---|
| **Unmasked Client Frame** | Send text frame with Mask bit `0x00` | §5.1: "The server MUST close the connection upon receiving a frame that is not masked." | Server sends Close frame with code `1002` (Protocol Error) and closes socket. |
| **XOR Masking Accuracy** | Send 4-byte key `[0xAA, 0xBB, 0xCC, 0xDD]` with masked bytes | §5.3: Server must XOR each payload byte with `mask[i % 4]` | Server correctly unmasks string and passes text to `@OnMessage`. |
| **Server Frame Unmasked** | Receive server frame | §5.1: "A server MUST NOT mask any frames that it sends to the client." | Assert client receives frame where byte 1 has bit 7 = 0 (`(byte1 & 0x80) == 0`). |
| **Unknown / Reserved Opcode** | Send frame with Opcode `0x3` or `0xB` | §5.2: Reserved opcodes must result in connection failure. | Server sends Close frame with code `1002` (Protocol Error). |
| **Non-zero RSV Bits** | Send frame with `RSV1 = 1` without extension | §5.2: "MUST be 0 unless an extension is negotiated." | Server sends Close frame with code `1002` (Protocol Error). |
| **Oversized Control Frame** | Send Ping (Opcode `0x9`) with 126 bytes of payload | §5.5: "All control frames MUST have a payload length of 125 bytes or less." | Server sends Close frame with code `1002`. |
| **Fragmented Control Frame** | Send Ping with `FIN = 0` | §5.5: "Control frames MUST NOT be fragmented." | Server sends Close frame with code `1002`. |
| **Invalid UTF-8 Text** | Send Text frame with invalid byte sequence `[0xC3, 0x28]` | §5.6, §8.1: Text frames must contain valid UTF-8. | Server sends Close frame with code `1007` (Invalid frame payload data). |
| **Malformed Handshake** | Send HTTP GET without `Sec-WebSocket-Key` or wrong Upgrade header | §4.2.1: Server must validate mandatory headers. | Server responds with `HTTP/1.1 400 Bad Request` and closes socket. |
| **16-bit Extended Length** | Send frame with payload length 250 bytes | §5.2: Payload len byte = 126, followed by 2-byte big-endian uint16. | Server decodes entire 250 bytes correctly. |

---

## 4. `bedrock-example` Module Integration

### 4.1 What Currently Exists in `bedrock-example`

The example module demonstrates Bedrock's v1.1, v1.2, and v1.3 capabilities:
- **`Application.java`**:
  - Binds logging before-middleware and CORS after-middleware.
  - Registers `BedrockJdbc` with SQLite file `bedrock.db` and auto-seeds initial data.
  - Binds interface `IUserRepository` to `SqliteUserRepository` and `IUserService` to `UserService`.
  - Configures global exception handling for `UserNotFoundException` (404).
  - Registers components and starts HTTP on port 8080.
- **REST Endpoints**: CRUD `/api/users` via `UserController`.
- **Tests**: `UserControllerTest` (5 tests), `UserRepositoryDatabaseTest` (2 tests).

### 4.2 Designing the Real-Time WebSocket Example (`ChatWebSocket`)

In accordance with R3 and R5, we should introduce `ChatWebSocket` in `bedrock-example`:

```java
package com.bedrock.example;

import com.bedrock.websocket.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🎓 BEDROCK TUTORIAL: Real-Time Bidirectional WebSocket Endpoint
 * 
 * Demonstrates real-time communication using @BedrockSocket and Virtual Threads.
 * Each connected client receives a dedicated Virtual Thread for frame processing.
 */
@BedrockSocket(path = "/chat")
public class ChatWebSocket {

    private static final Set<BedrockWebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(BedrockWebSocketSession session) {
        sessions.add(session);
        session.send("🦖 Connected to Bedrock Real-Time Chat (Session #" + session.getId() + ")");
        broadcast("System: User #" + session.getId() + " joined the chat. Active users: " + sessions.size(), session);
    }

    @OnMessage
    public void onMessage(BedrockWebSocketSession session, String message) {
        broadcast("[User #" + session.getId() + "]: " + message, null);
    }

    @OnClose
    public void onClose(BedrockWebSocketSession session, int code, String reason) {
        sessions.remove(session);
        broadcast("System: User #" + session.getId() + " left (" + reason + "). Active users: " + sessions.size(), null);
    }

    @OnError
    public void onError(BedrockWebSocketSession session, Throwable error) {
        System.err.println("[WS-ERROR] Session #" + session.getId() + ": " + error.getMessage());
    }

    private void broadcast(String message, BedrockWebSocketSession exclude) {
        for (BedrockWebSocketSession s : sessions) {
            if (exclude == null || !s.equals(exclude)) {
                s.send(message);
            }
        }
    }
}
```

### 4.3 Fluent Configuration in `Application.java`

Following Bedrock's explicit registration philosophy (no hidden scanning):

```java
public class Application {
    public static void main(String[] args) {
        BedrockApp.create(8080)
            // Existing middlewares, routes, JDBC, and IoC...
            .enableWebSockets(8081)
            .register(
                SqliteUserRepository.class,
                UserService.class,
                UserController.class,
                ChatWebSocket.class // Explicit registration of WebSocket endpoint
            )
            .get("/chat", ctx -> ctx.html(ChatPlayground.getHtml())) // Interactive Browser UI
            .start();
    }
}
```

### 4.4 Interactive Browser Demo (`ChatPlayground`)

Just as Bedrock provides `BedrockPlayground.java` with a dark-themed Swagger-like UI, we can provide `ChatPlayground.java` with an embedded HTML/CSS/JS text block:
- **Connection Indicator**: Visual badge showing WebSocket status (`CONNECTED` / `DISCONNECTED`) to `ws://localhost:8081/chat`.
- **Educational Inspector Panel**: Displays the underlying RFC 6455 mechanics:
  - Last frame received (Opcode, Payload Length, FIN bit, Mask status).
  - Virtual Thread ID processing the message.
  - Handshake roundtrip time.
- **Interactive Chat Panel**: Message feed with timestamps and an input field supporting the Enter key.
- **Zero External Assets**: Pure vanilla CSS/JS embedded in a single Java 21 Text Block.

---

## 5. ROADMAP.md & Acceptance Criteria Verification

### 5.1 ROADMAP.md Current State & Required Updates

In `ROADMAP.md` (lines 46–53):
```markdown
## ⚡ Versão 2.0 - *Comunicação em Tempo Real (WebSockets)*
*Foco: Entender como o WhatsApp Web e chats funcionam por baixo dos panos.*

- [ ] **Motor WebSockets (RFC 6455) do Zero:** Descer o nível para o `ServerSocketChannel` do Java NIO para manipular o Handshake TCP e o mascaramento de bits (Framing) dos WebSockets.
  - *Conceito Ensinado:* Protocolos de Rede TCP/IP, Handshake HTTP 101 Switching Protocols e manipulação de fluxos binários.
- [ ] **Anotação de Real-Time (`@BedrockSocket`):** Criar canais bidirecionais persistentes sobre Virtual Threads com consumo mínimo de memória.
  - *Conceito Ensinado:* Concorrência leve com Project Loom para conexões de longa duração.
```

**Required Update upon completion of Version 2.0**:
1. Change header to: `## ✅ Versão 2.0 - *Comunicação em Tempo Real (WebSockets)* (Concluído)`
2. Check both boxes:
   - `- [x] **Motor WebSockets (RFC 6455) do Zero:** ...`
   - `- [x] **Anotação de Real-Time (`@BedrockSocket`):** ...`

### 5.2 Acceptance Criteria Verification Matrix

| ID | Acceptance Criterion | Test Class / Verification Artifact | Verification Method & Assertion |
|---|---|---|---|
| **AC-1** | Handshake unit tests with official RFC 6455 vectors | `WebSocketHandshakeTest` | Validate SHA-1 calculation, GUID concatenation, and Base64 output against Section 1.3 vector (`dGhlIHNhbXBsZSBub25jZQ==` -> `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`). Test invalid/missing header rejection. |
| **AC-2** | Frame encoding/decoding unit tests | `WebSocketFrameTest` | Encode and decode frames: 7-bit (<=125 bytes), 16-bit (126..65535 bytes), 64-bit (>65535 bytes), masked/unmasked, FIN flags, and all opcodes (Text, Binary, Ping, Pong, Close). |
| **AC-3** | End-to-end integration test with live client | `BedrockWebSocketE2ETest` | Connect using `java.net.http.HttpClient.newWebSocketBuilder()`. Send text message, receive broadcast echo, verify Ping/Pong exchange, execute normal Close handshake (1000). |
| **AC-4** | Wire-level protocol & error enforcement | `BedrockWebSocketRawFrameTest` | Use raw `java.net.Socket` to send unmasked client frame (expect 1002 close), oversized control frame (expect 1002), bad opcode (expect 1002), non-zero RSV (expect 1002), and invalid UTF-8 (expect 1007). |
| **AC-5** | Regression: All 81 existing tests pass | Full suite (`mvn test`) | Run test suite and confirm all 81 existing tests continue to pass with 0 failures, 0 errors. Total test count will increase from 81 to >= 105+. |
| **AC-6** | Zero external dependencies in `bedrock-core` | `bedrock-core/pom.xml` | Confirm only JDK 21 standard library APIs (`java.nio`, `java.net`, `java.security`, `java.util`) are used in main source. No new dependencies added to pom. |
| **AC-7** | Javadoc compliance (`🎓 BEDROCK TUTORIAL`) | `mvn javadoc:javadoc` | Verify all new classes and methods contain educational Javadocs explaining network physics (TCP, HTTP 101, SHA-1, XOR masking, Virtual Threads). |
| **AC-8** | ROADMAP.md updated | `ROADMAP.md` | Confirm Version 2.0 marked as completed (`[x]`). |

---

## 6. Architectural Recommendations for Implementers

1. **Server Lifecycle Management**:
   - Provide `BedrockWebSocketServer.start()` and `BedrockWebSocketServer.stop()` (implementing `AutoCloseable`).
   - Allow dynamic port selection (e.g., port `0` in tests to find an ephemeral free port: `new InetSocketAddress(0).getPort()`).
   - Add `stop()` to `BedrockApp` to gracefully shut down both the HTTP server and the WebSocket server.
2. **Virtual Thread Naming & Thread Safety**:
   - Spawn virtual threads with descriptive names: `Thread.ofVirtual().name("ws-client-" + sessionId).start(...)`.
   - Frame writing to `SocketChannel` must be synchronized or queued per session, as concurrent writes to a non-blocking or blocking channel from multiple virtual threads (e.g. during a broadcast) can corrupt WebSocket frames on the wire.
3. **Buffer Management**:
   - Use `ByteBuffer.allocate(...)` cleanly without buffer leaks. Avoid keeping huge buffers alive in idle sessions.
4. **Clean Session Registry**:
   - Use thread-safe data structures (`ConcurrentHashMap`, `CopyOnWriteArraySet`) for tracking active sessions and dispatching broadcasts.
