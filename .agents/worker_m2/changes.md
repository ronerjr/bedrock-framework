# Milestone 2: Virtual-Threaded NIO Server & Sessions - Change Log

## Overview
Implemented Milestone 2 (M2) of the Bedrock WebSocket Framework:
- Zero external runtime dependencies in `bedrock-core` (strictly JDK 21 standard library: `java.nio`, `java.net`, `java.util.concurrent`, Project Loom Virtual Threads).
- Dedicated Virtual Thread per connection (`Thread.ofVirtual().name("ws-client-", clientId).start(...)`).
- Non-blocking/blocking `ServerSocketChannel` binding with ephemeral port (`port 0`) support and `getPort()` port discovery.
- Clean lifecycle management (`start()`, `stop()`, `AutoCloseable`) with zero port or thread leaks.
- Thread-safe session write lock via `ReentrantLock` avoiding carrier thread pinning during network I/O.
- Automatic Pong reply (Opcode 0xA) on incoming Ping (Opcode 0x9) echoing application data.
- Two-way Close handshake and graceful channel termination.
- Rich `🎓 BEDROCK TUTORIAL` Javadocs on all classes explaining Loom Virtual Threads, carrier thread unmounting, and socket channel mechanics.
- Comprehensive unit and integration test suite in `BedrockWebSocketServerTest.java` covering 21 scenarios.

---

## File Modification / Creation Details

### 1. `bedrock-core/src/main/java/com/bedrock/core/ws/server/BedrockWebSocketSession.java`
- Created public interface defining the full-duplex RFC 6455 connection handle:
  - `getId()`: Globally unique session identifier (UUID).
  - `getRemoteAddress()`: Remote client socket address.
  - `getPath()`: Normalized URI path.
  - `isOpen()`: Connection state query.
  - `send(String)`: Thread-safe UTF-8 text frame transmission (Opcode 0x1).
  - `send(byte[])`: Thread-safe binary frame transmission (Opcode 0x2).
  - `sendPing(byte[])`: Thread-safe Ping control frame transmission (Opcode 0x9).
  - `close()` / `close(int, String)`: Clean RFC 6455 close handshake (Opcode 0x8).
  - `setAttribute(String, Object)`, `getAttribute(String)`, `removeAttribute(String)`, `getAttributes()`: Session attribute management.
  - `writeRaw(ByteBuffer)`: Lock-guarded raw buffer transmission for server control frames (Pong/Close).
  - `markClosed()`: Internal state update for clean teardown.
- Included comprehensive `🎓 BEDROCK TUTORIAL` Javadoc explaining Virtual Threads, carrier-friendly locking, and the non-atomic nature of `SocketChannel.write`.

### 2. `bedrock-core/src/main/java/com/bedrock/core/ws/server/StandardWebSocketSession.java`
- Concrete implementation of `BedrockWebSocketSession`:
  - Uses `java.util.concurrent.locks.ReentrantLock` (`writeLock`) for all outbound frame writes.
  - Prevents carrier thread pinning under Project Loom: waiting on `ReentrantLock` unmounts the virtual thread, returning the carrier thread to the ForkJoinPool.
  - Enforces `WebSocketCloseStatus.validateSendCode(statusCode)` preventing wire-forbidden status codes (1005, 1006, 1015).
  - Idempotent close handling via `AtomicBoolean closed`.
  - Serializes unmasked server frames via `WebSocketFrameWriter`.

### 3. `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketSessionRegistry.java`
- Centralized thread-safe session tracking backed by `ConcurrentHashMap`:
  - `register(BedrockWebSocketSession)`: Adds open sessions.
  - `unregister(String)` / `unregister(BedrockWebSocketSession)`: Removes sessions.
  - `get(String)`: Retrieves session with self-healing dead-session pruning.
  - `getAll()`: Returns unmodifiable collection snapshot.
  - `broadcast(String)` / `broadcast(String, Predicate)`: Resilient broadcasting with per-session error isolation ensuring one broken TCP connection never halts the broadcast loop.
  - `broadcastExcept(String, String)`: Broadcasts to all connected peers except the specified sender.
  - `closeAll(int, String)`: Gracefully terminates all active sessions with RFC 6455 status 1001 (Going Away) on server shutdown.
  - `clear()`: Empties the registry.
- Included `🎓 BEDROCK TUTORIAL` Javadoc on resilient broadcasting and self-healing pruning.

### 4. `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketEndpointBinding.java`
- Transparent dispatcher interface isolating network I/O from application dispatching:
  - `invokeOnOpen(BedrockWebSocketSession)`
  - `invokeOnMessage(BedrockWebSocketSession, String)`
  - `invokeOnClose(BedrockWebSocketSession, int, String)`
  - `invokeOnError(BedrockWebSocketSession, Throwable)`
- Dual execution implementations:
  - `ReflectiveEndpointBinding`: Analyzes methods once at registration, maps parameters dynamically (supporting flexible argument signatures), and invokes directly via reflection without dynamic bytecode proxies (CGLIB/ByteBuddy).
  - `FunctionalEndpointBinding`: Fluent lambda builder (`Builder`) enabling concise programmatic endpoint configuration without annotations.
- Static factories: `builder()` and `reflective(...)`.
- Included `🎓 BEDROCK TUTORIAL` Javadoc explaining explicit reflection vs. black-box proxy generation.

### 5. `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketClientHandler.java`
- Manages complete physical connection lifecycle on a dedicated Virtual Thread:
  - Spawns Virtual Thread: `Thread.ofVirtual().name("ws-client-", clientId).start(...)`.
  - **Phase 1: HTTP Upgrade Handshake**:
    - Reads into `ByteBuffer`, parses with `WebSocketHandshake.parse(buffer)`.
    - Handles status 426 (unsupported version), status 400 (malformed headers).
    - Validates route path in `routes` map (returns HTTP 404 if unmapped).
    - Responds with `HTTP/1.1 101 Switching Protocols`.
    - Instantiates `StandardWebSocketSession`, registers in `sessionRegistry`.
    - Fires `@OnOpen`.
  - **Phase 2: Full-Duplex Frame Loop**:
    - Preserves pipelined frame bytes via `buffer.compact()`.
    - Synchronous blocking read (`channel.read(buffer)`) allowing the JVM to unmount the virtual thread while awaiting network bytes.
    - Decodes binary frames via `WebSocketFrameParser.parse(buffer)` with in-place 4-byte XOR unmasking.
    - Strict RFC 6455 client mask enforcement (rejects unmasked frames with Close code 1002 Protocol Error).
    - Opcode dispatch:
      - `TEXT (0x1)`: invokes `@OnMessage`.
      - `PING (0x9)`: automatically replies with unmasked Pong (Opcode 0xA) echoing exact payload data.
      - `PONG (0xA)`: keepalive ack.
      - `CLOSE (0x8)`: echoes Close frame, unregisters session, fires `@OnClose`, closes socket.
      - `BINARY (0x2)`: rejects with status 1003 (Unsupported Data).
    - Exception handling: protocol errors send Close frame (1002/1007/1009), fire `@OnError`, unregister, fire `@OnClose`, close channel.
    - Abnormal TCP closure (EOF / IOException) fires `@OnError` and `@OnClose` with code 1006.
- Included `🎓 BEDROCK TUTORIAL` Javadoc demystifying carrier unmounting physics (`channel.read()` -> Continuation Yield -> unmount to carrier ForkJoinPool -> OS epoll/IOCP multiplexer -> wakeup and remount).

### 6. `bedrock-core/src/main/java/com/bedrock/core/ws/server/BedrockWebSocketServer.java`
- Main server engine with dedicated `ServerSocketChannel`:
  - Configurable host and port, with dynamic ephemeral port binding (`port 0`).
  - `getPort()` dynamically discovers the bound port from `serverChannel.getLocalAddress()`.
  - `start()`: Binds socket with `SO_REUSEADDR`, starts accept loop on Virtual Thread `ws-accept-<port>`. Idempotent if already started; throws `IllegalStateException` if closed.
  - `stop()`: Closes `serverChannel` to unblock accept loop, waits for accept thread, calls `sessionRegistry.closeAll(1001, "Server shutting down")`, releasing port immediately.
  - `close()`: Implements `AutoCloseable` for `try-with-resources`.
  - `registerEndpoint(path, binding)` and `registerEndpoint(path, instance, onOpen, onMessage, onClose, onError)`.
  - `normalizePath(path)`: Guarantees consistent URI path matching.
- Included `🎓 BEDROCK TUTORIAL` Javadoc on Virtual Threads vs. reactive event loops, ephemeral ports, and server lifecycle.

### 7. `bedrock-core/src/test/java/com/bedrock/core/ws/server/BedrockWebSocketServerTest.java`
- Comprehensive test suite covering 21 scenarios:
  1. `testServerStartupOnEphemeralPort`: Port 0 allocation, `getPort() > 0`, `isRunning()`, idempotent `start()`.
  2. `testServerStartupFailureOnClosedServer`: `IllegalStateException` on restarted server.
  3. `testClientConnectionAndHttp101HandshakeViaRawSocket`: Low-level TCP verification of HTTP 101, Upgrade headers, and mathematical `Sec-WebSocket-Accept`.
  4. `testClientConnectionAndHttp101HandshakeViaHttpClient`: Java 21 `HttpClient` WebSocket connection and `@OnOpen`.
  5. `testTextMessageEchoViaOnMessage`: Roundtrip text frame sending and echo verification.
  6. `testMultiByteUtf8TextMessage`: Multi-byte UTF-8 characters, accents, and emojis ("🦖 Olá Mundo 🚀").
  7. `testConcurrentClientsSessionSendAndBroadcast`: 10 concurrent clients, `sessionRegistry.broadcast()`, 50 concurrent `session.send()` writes under write lock.
  8. `testAutomaticPingPongReplyViaRawSocket`: Raw masked Ping frame generates unmasked Pong with identical payload.
  9. `testAutomaticPingPongReplyViaHttpClient`: Java 21 `HttpClient` WebSocket ping/pong verification.
  10. `testClientInitiatedCleanClose`: Client Close 1000 handshake, Close echo, `@OnClose` dispatch, session unregistration.
  11. `testServerStopCleanLifecycleAndPortRelease`: Server `stop()` sends Close 1001 Going Away, unregisters all sessions, immediate port re-bind verification (zero leaks).
  12. `testAutoCloseableTryWithResources`: `try-with-resources` clean teardown.
  13. `testRouteNotFoundHttp404`: Handshake to unmapped route returns HTTP 404 Not Found.
  14. `testUnsupportedWebSocketVersionHttp426`: Version 8 returns HTTP 426 Upgrade Required.
  15. `testMalformedHandshakeHttp400`: Missing `Sec-WebSocket-Key` returns HTTP 400 Bad Request.
  16. `testClientUnmaskedFrameProtocolError1002`: Unmasked client frame triggers Close 1002 Protocol Error.
  17. `testSessionAttributesAndState`: Custom session attributes, remote address, path, open state.
  18. `testSessionIllegalStateOnClosedSession`: `send()` and `sendPing()` on closed session throw `IllegalStateException`.
  19. `testSessionWireForbiddenCloseCodes`: Sending 1005, 1006, 1015 throws `IllegalArgumentException`.
  20. `testSessionRegistryBroadcastExcept`: Filtered broadcast excluding sender session ID.
  21. `testReflectiveEndpointBinding`: Transparent method reflection without bytecode proxies.
