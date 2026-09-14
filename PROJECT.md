# Project: Bedrock Framework Version 2.0 (Real-Time WebSockets RFC 6455)

## Architecture
Bedrock Version 2.0 introduces native, zero-dependency RFC 6455 WebSockets to the Bedrock Java Framework on Java 21 LTS with Project Loom Virtual Threads.

The architecture comprises three distinct layers:
1. **Low-Level Protocol Layer (`com.bedrock.core.ws.protocol`)**:
   - Pure byte-level Java NIO & `java.security` implementation of RFC 6455.
   - HTTP 101 Switching Protocols upgrade handshake: SHA-1 hashing of `Sec-WebSocket-Key` + RFC 6455 GUID (`258EAFA5-E914-47DA-95CA-C5AB0DC85B11`), Base64 encoded into `Sec-WebSocket-Accept`.
   - Binary frame parsing and serialization: FIN, RSV1-3, Opcodes (Text 0x1, Binary 0x2, Close 0x8, Ping 0x9, Pong 0xA, Continuation 0x0).
   - In-place 4-byte XOR unmasking ($D_i = E_i \oplus M_{i \pmod 4}$) for client frames; client masking invariant enforcement (close code 1002 if unmasked).
   - Payload length decoding: 7-bit (0-125), 16-bit extended (126), 64-bit extended (127), with minimal-byte representation enforcement.
   - Control frames (FIN=1, length <= 125, automatic Pong reply echoing Ping payload, Close frame status codes and UTF-8 reason validation).

2. **NIO Virtual-Thread Server & Session Layer (`com.bedrock.core.ws.server`)**:
   - `BedrockWebSocketServer`: Dedicated `ServerSocketChannel` bound to configured or ephemeral port, clean lifecycle (`start()`, `stop()`, `AutoCloseable`).
   - Concurrency Model: Dedicated Virtual Thread per client (`Thread.ofVirtual().name("ws-client-", id).start(...)`).
   - `WebSocketClientHandler`: Executes synchronous blocking I/O loop on `SocketChannel`, reading frames sequentially, handling Pings/Closes inline, dispatching to endpoints.
   - `BedrockWebSocketSession`: Thread-safe session abstraction providing `send(String)`, `sendPing()`, `close()`, `close(int, String)`, `isOpen()`, `getId()`, `getRemoteAddress()`.
   - `WebSocketSessionRegistry`: Thread-safe session tracking and multi-client `broadcast(String)`.

3. **Declarative API, IoC & Pedagogical Layer (`com.bedrock.core.ws.annotation`, `com.bedrock.core`)**:
   - Annotations: `@BedrockSocket(path = "/...")`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`.
   - Integration with `BedrockApp`: `app.enableWebSockets(int port)` and `app.register(ChatSocket.class)`.
   - `BedrockContainer` integration: Resolves dependencies via constructor injection for WebSocket endpoint singletons.
   - Reflection dispatcher: Transparent invocation without dynamic runtime bytecode proxies.
   - `🎓 BEDROCK TUTORIAL` Standard: Exhaustive Javadocs explaining protocol physics, bitwise mechanics, and virtual threads vs event loops.

---

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Handshake Header Parsing | Parse HTTP GET upgrade headers (Upgrade, Connection, Sec-WebSocket-Key, Sec-WebSocket-Version: 13) | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Handshake SHA-1 & Base64 | Compute Sec-WebSocket-Accept via SHA-1(Key + GUID) -> Base64, verified against RFC 6455 §1.3 test vector | M1 | ORIGINAL_REQUEST §R1 |
| 3 | HTTP 101 Response | Generate compliant HTTP/1.1 101 Switching Protocols response byte sequence | M1 | ORIGINAL_REQUEST §R1 |
| 4 | Frame Bitmasking & Opcodes | Parse 32-bit frame header: FIN, RSV1-3 (must be 0), Opcode (0x0, 0x1, 0x2, 0x8, 0x9, 0xA) | M1 | ORIGINAL_REQUEST §R1 |
| 5 | Client Mask Enforcement | Validate MASK=1 for incoming client frames; reject unmasked frames with status 1002 | M1 | ORIGINAL_REQUEST §R1 |
| 6 | 4-Byte XOR Unmasking | Implement in-place unmasking: $D_i = E_i \oplus M_{i \pmod 4}$ | M1 | ORIGINAL_REQUEST §R1 |
| 7 | Multi-Length Decoding | Decode 7-bit, 16-bit extended (126), and 64-bit extended (127) payload lengths with minimal-length validation | M1 | ORIGINAL_REQUEST §R1 |
| 8 | Server Frame Serialization | Format and send server-to-client frames (MASK=0, unmasked, correct opcode and length headers) | M1 | ORIGINAL_REQUEST §R1 |
| 9 | Control Frame Ping/Pong | Validate control frame constraints (FIN=1, length <= 125); auto-reply with Pong echoing Ping payload | M1 | ORIGINAL_REQUEST §R1 |
| 10 | Close Handshake & Codes | Parse 2-byte close status code (1000-1011, wire-forbidden codes 1005/1006/1015) and echo Close frame | M1 | ORIGINAL_REQUEST §R1 |
| 11 | UTF-8 Validation | Validate text frames and close reason strings as UTF-8; terminate with status 1007 on malformed bytes | M1 | RFC 6455 §8.1 |
| 12 | BedrockWebSocketServer | NIO ServerSocketChannel server with configurable/ephemeral port binding and lifecycle management | M2 | ORIGINAL_REQUEST §R1 |
| 13 | Virtual Thread Architecture | Dedicated virtual thread per connection (`Thread.ofVirtual().name("ws-client-", ...).start(...)`) with synchronous read loop | M2 | ORIGINAL_REQUEST §R2 |
| 14 | BedrockWebSocketSession | Thread-safe session API with send(text), close(), close(code, reason), getId(), getRemoteAddress() | M2 | ORIGINAL_REQUEST §R3 |
| 15 | Session Registry & Broadcast | Concurrent session registry supporting thread-safe broadcast(text) across connected clients | M2 | ORIGINAL_REQUEST §R3 |
| 16 | Declarative Annotations | @BedrockSocket, @OnOpen, @OnMessage, @OnClose, @OnError route and lifecycle annotations | M3 | ORIGINAL_REQUEST §R3 |
| 17 | BedrockApp Integration | app.enableWebSockets(port) builder method and app.register(Socket.class) in IoC container | M3 | ORIGINAL_REQUEST §R3 |
| 18 | Server & App Teardown | Expose stop() / close() and AutoCloseable on BedrockWebSocketServer and BedrockApp for clean test lifecycle | M3 | explorer_tests_1 survey |
| 19 | 🎓 BEDROCK TUTORIAL Docs | Javadoc tutorials explaining Handshake 101, SHA-1/Base64, frame anatomy, XOR masking, and Virtual Threads | M1, M2, M3 | ORIGINAL_REQUEST §R4 |
| 20 | Zero Third-Party Dependencies | Strict adherence to JDK 21 standard library in bedrock-core/pom.xml | M1, M2, M3 | ORIGINAL_REQUEST §R4 |
| 21 | Practical Example Module | Implement ChatWebSocket in bedrock-example module with @BedrockSocket | M4 | ORIGINAL_REQUEST §R5 |
| 22 | Interactive Demo Playground | Embedded dark-theme HTML/JS test playground at /chat in bedrock-example | M4 | ORIGINAL_REQUEST §R5 |
| 23 | E2E Test Suite (Tiers 1-4) | Comprehensive opaque-box test suite (unit vectors, live HttpClient client, raw Socket frame tests) | M5 | ORIGINAL_REQUEST Acceptance |
| 24 | Adversarial Hardening (Tier 5) | White-box edge-case and protocol violation stress testing | M5 | Project Pattern Tier 5 |
| 25 | Baseline Regression Check | Guarantee all 81 existing tests continue passing (mvn clean verify) | M5 | ORIGINAL_REQUEST Acceptance |
| 26 | ROADMAP.md Update | Mark Version 2.0 checkboxes as completed [x] | M5 | ORIGINAL_REQUEST Acceptance |

---

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: RFC 6455 Protocol Engine | Handshake, frame parsing/serialization, XOR unmasking, control frames, unit tests, Javadocs | none | DONE |
| 2 | M2: Virtual-Threaded NIO Server & Sessions | ServerSocketChannel, virtual thread per client loop, BedrockWebSocketSession, SessionRegistry, broadcast, stop() | M1 | DONE |
| 3 | M3: BedrockApp Integration & Annotations | @BedrockSocket, @OnOpen, @OnMessage, @OnClose, @OnError, app.enableWebSockets(), app.register(), app.stop() | M1, M2 | DONE |
| 4 | M4: bedrock-example Real-Time Demo | ChatWebSocket, /chat HTML/JS playground, example tests | M1, M2, M3 | DONE |
| 5 | M5: Full E2E Test Pass & Hardening | Pass 100% E2E test suite (Tiers 1-4), Tier 5 adversarial hardening, 81 baseline tests pass, ROADMAP.md update | M1, M2, M3, M4, E2E-Track | DONE |

---

## Interface Contracts

### `com.bedrock.core.ws.protocol` ↔ `com.bedrock.core.ws.server`
- **`WebSocketHandshake`**:
  - `public static boolean isUpgradeRequest(String httpHeaders)`
  - `public static String extractKey(String httpHeaders)`
  - `public static String computeAccept(String clientKey)`
  - `public static byte[] createHandshakeResponse(String acceptKey)`
  - `public static String extractPath(String httpHeaders)`
- **`WebSocketFrame`**:
  - `public boolean isFin()`
  - `public WebSocketOpcode getOpcode()`
  - `public boolean isMasked()`
  - `public byte[] getPayload()`
  - `public String getPayloadAsText()`
  - `public int getCloseStatusCode()`
  - `public String getCloseReason()`
- **`WebSocketFrameParser`**:
  - `public static WebSocketFrame parse(ByteBuffer buffer) throws WebSocketException`
  - `public static void unmask(byte[] payload, byte[] maskingKey)`
- **`WebSocketFrameWriter`**:
  - `public static byte[] createTextFrame(String text)`
  - `public static byte[] createPingFrame(byte[] applicationData)`
  - `public static byte[] createPongFrame(byte[] applicationData)`
  - `public static byte[] createCloseFrame(int statusCode, String reason)`

### `com.bedrock.core.ws.server` ↔ `com.bedrock.core.ws.annotation`
- **`BedrockWebSocketSession`**:
  - `String getId()`
  - `SocketAddress getRemoteAddress()`
  - `boolean isOpen()`
  - `void send(String message)`
  - `void sendPing(byte[] data)`
  - `void close()`
  - `void close(int statusCode, String reason)`
- **`WebSocketEndpoint` / Dispatcher**:
  - `void onOpen(BedrockWebSocketSession session)`
  - `void onMessage(BedrockWebSocketSession session, String message)`
  - `void onClose(BedrockWebSocketSession session, int code, String reason)`
  - `void onError(BedrockWebSocketSession session, Throwable throwable)`
- **`BedrockWebSocketServer`**:
  - `void registerEndpoint(String path, Object endpointInstance, Method onOpen, Method onMessage, Method onClose, Method onError)`
  - `void start()`
  - `void stop()`
  - `int getPort()` (returns bound local port, crucial for ephemeral port 0)

### `com.bedrock.core` (BedrockApp) ↔ `com.bedrock.core.ws`
- **`BedrockApp`**:
  - `public BedrockApp enableWebSockets(int port)`
  - `public void stop()`
  - `public void close()` (implements AutoCloseable)
  - `register(Class<?>... classes)` detects `@BedrockSocket`, resolves via `BedrockContainer`, binds to `BedrockWebSocketServer`.

---

## Code Layout
```
bedrock-core/
├── src/main/java/com/bedrock/core/
│   ├── BedrockApp.java                      (enhanced with enableWebSockets, register, stop, AutoCloseable)
│   └── ws/
│       ├── annotation/
│       │   ├── BedrockSocket.java           (path route annotation)
│       │   ├── OnOpen.java                  (connection opened event)
│       │   ├── OnMessage.java               (message received event)
│       │   ├── OnClose.java                 (connection closed event)
│       │   └── OnError.java                 (error event)
│       ├── protocol/
│       │   ├── WebSocketOpcode.java         (RFC 6455 opcode enum)
│       │   ├── WebSocketCloseStatus.java    (RFC 6455 status codes)
│       │   ├── WebSocketException.java      (protocol exceptions with close code)
│       │   ├── WebSocketHandshake.java      (handshake parser & SHA-1/Base64 computation)
│       │   ├── WebSocketFrame.java          (immutable frame representation)
│       │   ├── WebSocketFrameParser.java    (NIO buffer frame decoder & XOR unmasker)
│       │   └── WebSocketFrameWriter.java    (server unmasked frame encoder)
│       └── server/
│           ├── BedrockWebSocketSession.java (session interface and implementation)
│           ├── WebSocketSessionRegistry.java(concurrent sessions and broadcasting)
│           ├── WebSocketEndpointBinding.java(reflected handler invoker)
│           ├── WebSocketClientHandler.java  (Virtual Thread connection loop)
│           └── BedrockWebSocketServer.java  (NIO ServerSocketChannel server)
├── src/test/java/com/bedrock/core/ws/
│   ├── protocol/
│   │   ├── WebSocketHandshakeTest.java      (RFC 6455 test vectors & handshake tests)
│   │   └── WebSocketFrameTest.java          (frame parsing, serialization, XOR, lengths)
│   └── server/
│       ├── BedrockWebSocketServerTest.java  (server startup, lifecycle, ephemeral port)
│       ├── BedrockWebSocketE2ETest.java     (HttpClient live WebSocket integration test)
│       └── BedrockWebSocketRawFrameTest.java(raw socket RFC 6455 violation tests)
bedrock-example/
├── src/main/java/com/bedrock/example/
│   ├── Application.java                     (enables WebSockets and registers ChatWebSocket)
│   └── ws/
│       ├── ChatWebSocket.java               (chat endpoint with @BedrockSocket, broadcast)
│       └── ChatPlayground.java              (dark-theme HTML/JS test client)
└── src/test/java/com/bedrock/example/
    └── ChatWebSocketTest.java               (example module integration test)
```
