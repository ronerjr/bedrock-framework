# Independent Adversarial & Quality Review: Milestone 2 (Virtual-Threaded NIO Server & Sessions)

**Reviewer Agent**: `reviewer_m2_2`  
**Target Milestone**: Milestone 2: Virtual-Threaded NIO Server & Sessions  
**Date**: 2026-09-11  
**Verdict**: **APPROVE**

---

## 1. Executive Summary

Milestone 2 delivers the native, zero-dependency RFC 6455 WebSocket server architecture for the Bedrock Java Framework on Java 21 LTS and Project Loom Virtual Threads.

The implementation was subjected to an independent, evidence-based quality audit and adversarial challenge across all four required dimensions:
1. **Lifecycle Teardown & Port Management**: Verified that `BedrockWebSocketServer.stop()` and `close()` properly close the listening `ServerSocketChannel`, join the accept virtual thread, send Close status 1001 (Going Away) to active sessions, and release the OS port immediately without leaks. Ephemeral port 0 is properly resolved and re-bindable immediately.
2. **Multi-Client Broadcasting & Resilience**: Verified that `WebSocketSessionRegistry.broadcast()` provides strict per-client error isolation. Broken sockets do not disrupt delivery to other connected clients, and inactive/dead sessions are cleanly pruned.
3. **Protocol Violation & Handshake Enforcement**: Verified that unmasked client frames trigger RFC 6455 status 1002 (Protocol Error), malformed handshakes return HTTP 400, unsupported versions return HTTP 426 with `Sec-WebSocket-Version: 13`, and unmapped routes return HTTP 404.
4. **Zero Third-Party Dependencies**: Confirmed that `bedrock-core/pom.xml` contains strictly zero third-party compile or runtime dependencies (using exclusively JDK 21 `java.nio`, `java.net`, `java.util.concurrent`, and Project Loom Virtual Threads).

Integrity audit detected **zero** hardcoded test vectors, facade implementations, or bypasses. Real production-grade logic is implemented throughout.

---

## 2. Review Dimensions & Audit Verification

### 2.1 Lifecycle Teardown & Ephemeral Port Handling
- **`BedrockWebSocketServer.start()`**:
  - Configures `ServerSocketChannel` in blocking mode with `SO_REUSEADDR = true`.
  - Spawns the dedicated accept loop on a named virtual thread: `Thread.ofVirtual().name("ws-accept-" + boundPort).start(this::acceptLoop)`.
  - Ephemeral port `0` is resolved via `serverChannel.getLocalAddress()`, updating `boundPort` to the OS-allocated port.
- **`BedrockWebSocketServer.stop()`**:
  - Thread-safe and idempotent via `running.compareAndSet(true, false)`.
  - Closes `serverChannel`, which causes `serverChannel.accept()` to throw `AsynchronousCloseException` / `ClosedChannelException`, terminating the accept loop without thread leaks.
  - Joins the accept loop virtual thread with a bounded timeout (`Duration.ofMillis(500)`).
  - Invokes `sessionRegistry.closeAll(WebSocketCloseStatus.GOING_AWAY_CODE, "Server shutting down")`, which transmits RFC 6455 Close status 1001 to all active sessions, clears the registry, and closes underlying client socket channels.
  - Local TCP port is released synchronously, allowing immediate re-binding by tests.
- **`AutoCloseable` Support**:
  - `close()` delegates to `stop()` and atomically marks `closed = true`.
  - Attempting to restart a closed server correctly throws `IllegalStateException`.

### 2.2 Multi-Client Broadcasting & Session Resilience
- **`WebSocketSessionRegistry.broadcast(String, Predicate)`**:
  - Iterates over active sessions backed by `ConcurrentHashMap`.
  - Isolates each client delivery inside a dedicated `try { ... } catch (Exception e)` block.
  - If a client socket encounters an `IOException` during frame transmission, `StandardWebSocketSession.send()` marks the session closed and throws `IllegalStateException`. The registry catches the exception, logs an informative warning, removes the broken session from the registry, and proceeds with delivery to remaining sessions uninterrupted.
- **Dead Session Pruning**:
  - Pruned proactively in `broadcast()` if `!session.isOpen()`.
  - Pruned on lookup in `get(sessionId)` if `!session.isOpen()`.
  - Pruned explicitly upon `close()` or `markClosed()`.

### 2.3 Protocol Violation Handling in `WebSocketClientHandler`
- **Mandatory Client Masking (RFC 6455 §5.1)**:
  - `WebSocketFrameParser.parse(buffer, true)` enforces `if (requireMask && !masked)`.
  - Throws `WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, "Client frame must be masked (RFC 6455 §5.1)")`.
  - `WebSocketClientHandler.handleProtocolException` catches this, writes a Close frame with status 1002 (Protocol Error), dispatches `@OnError` and `@OnClose`, and closes the TCP channel.
- **Handshake Validation**:
  - Malformed HTTP requests or missing `Sec-WebSocket-Key` produce HTTP 400 Bad Request via `WebSocketHandshake.createResponse400()`.
  - Unsupported WebSocket versions (e.g. Version 8) return HTTP 426 Upgrade Required with header `Sec-WebSocket-Version: 13`.
  - Requests targeting unmapped routes return HTTP 404 Not Found.
  - Valid requests negotiate HTTP 101 Switching Protocols with mathematically exact SHA-1 Base64 `Sec-WebSocket-Accept`.

### 2.4 Project Loom Concurrency & Carrier Unmounting
- Each accepted connection is dispatched to its own virtual thread: `Thread.ofVirtual().name("ws-client-", clientId).start(...)`.
- The client loop executes synchronous blocking reads (`channel.read(buffer)`). When awaiting network packets, the virtual thread yields its continuation and unmounts from its OS carrier thread, returning the carrier thread to the carrier `ForkJoinPool`.
- Outbound writes on `StandardWebSocketSession` are guarded by `java.util.concurrent.locks.ReentrantLock`. Unlike `synchronized` blocks, waiting on a `ReentrantLock` under Java 21 yields cleanly without pinning the OS carrier thread, while guaranteeing that frame bytes are never spliced or corrupted across concurrent virtual threads.

### 2.5 Zero Third-Party Runtime Dependencies
- `bedrock-core/pom.xml` was inspected:
  - Only test dependencies exist (`junit-jupiter-api`, `mockito-core`, `junit-jupiter-engine`).
  - Exactly zero third-party compile or runtime dependencies.
  - Exclusively utilizes standard Java 21 LTS APIs (`java.nio.channels`, `java.net`, `java.util.concurrent`, `java.security.MessageDigest`).

### 2.6 Educational Javadocs (`🎓 BEDROCK TUTORIAL`) & Unit Test Coverage
- **Educational Javadoc Standard**:
  - All classes (`BedrockWebSocketServer`, `BedrockWebSocketSession`, `StandardWebSocketSession`, `WebSocketSessionRegistry`, `WebSocketClientHandler`, `WebSocketEndpointBinding`, and `BedrockWebSocketServerTest`) feature comprehensive `🎓 BEDROCK TUTORIAL` Javadocs.
  - Topics thoroughly demystified include:
    - Continuation yields and unmounting from OS carrier threads to ForkJoinPool during blocking I/O (`channel.read()`).
    - Epoll/IOCP multiplexer integration.
    - Avoiding carrier thread pinning via `ReentrantLock` over `synchronized`.
    - Prevention of TCP frame interleaving on multi-threaded `SocketChannel.write()`.
    - Transparent reflection parameter mapping vs. opaque bytecode proxies (CGLIB/ByteBuddy).
    - Ephemeral port binding (`port 0`) mechanics.
- **Unit & Integration Test Suite**:
  - `BedrockWebSocketServerTest.java` implements 21 comprehensive, genuine scenarios without mocking out core logic or hardcoding returns:
    1. `testServerStartupOnEphemeralPort`
    2. `testServerStartupFailureOnClosedServer`
    3. `testClientConnectionAndHttp101HandshakeViaRawSocket`
    4. `testClientConnectionAndHttp101HandshakeViaHttpClient`
    5. `testTextMessageEchoViaOnMessage`
    6. `testMultiByteUtf8TextMessage`
    7. `testConcurrentClientsSessionSendAndBroadcast`
    8. `testAutomaticPingPongReplyViaRawSocket`
    9. `testAutomaticPingPongReplyViaHttpClient`
    10. `testClientInitiatedCleanClose`
    11. `testServerStopCleanLifecycleAndPortRelease`
    12. `testAutoCloseableTryWithResources`
    13. `testRouteNotFoundHttp404`
    14. `testUnsupportedWebSocketVersionHttp426`
    15. `testMalformedHandshakeHttp400`
    16. `testClientUnmaskedFrameProtocolError1002`
    17. `testSessionAttributesAndState`
    18. `testSessionIllegalStateOnClosedSession`
    19. `testSessionWireForbiddenCloseCodes`
    20. `testSessionRegistryBroadcastExcept`
    21. `testReflectiveEndpointBinding`
  - Tests verify both low-level raw TCP socket interactions (byte-by-byte frame validation) and high-level Java 21 `HttpClient` WebSocket client flows.

---

## 3. Findings

### [Minor] Finding 1: Attempt to send wire-forbidden status code 1006 in `WebSocketSessionRegistry.broadcast()`
- **Location**: `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketSessionRegistry.java:187`
- **What**: In the error-handling catch block of `broadcast()`, the code attempts to call:
  ```java
  session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Broadcast transmission error");
  ```
- **Why**: `ABNORMAL_CLOSURE_CODE` is integer `1006`. RFC 6455 §7.4.1 explicitly forbids code 1006 from appearing on the wire. In `StandardWebSocketSession.close(int, String)`, line 159 executes `WebSocketCloseStatus.validateSendCode(statusCode)`, which throws `IllegalArgumentException` whenever 1006 is passed. Although this exception is swallowed by the surrounding `catch (Exception ignore) {}`, and the channel was already closed by `markClosed()` inside `session.send()`, calling `session.close(1006)` is a conceptual contradiction with the framework's own wire validation rules.
- **Suggestion**: Replace `session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, ...)` with `session.markClosed()` directly, or call `session.close()` (which sends 1000) or `session.close(WebSocketCloseStatus.SERVER_ERROR_CODE, "Broadcast transmission error")` (which sends 1011).

### [Minor] Finding 2: Lack of buffer shrinking after handling large frame in `WebSocketClientHandler`
- **Location**: `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketClientHandler.java:276`
- **What**: When a client sends a large frame, the connection `ByteBuffer` dynamically doubles in size up to `MAX_BUFFER_SIZE` (16 MB). Once the frame has been completely parsed and dispatched, the buffer remains at 16 MB for the entire remaining lifetime of that client connection.
- **Why**: If a server maintains 20,000 concurrent virtual-thread connections and many clients occasionally send large payloads, the heap memory consumed by idle 16 MB buffers could cause heap exhaustion.
- **Suggestion**: In `runFrameLoop()`, if `buffer.remaining() == 0` and `buffer.capacity() > INITIAL_BUFFER_SIZE`, consider reallocating or shrinking the buffer back to `INITIAL_BUFFER_SIZE` (8 KB).

---

## 4. Integrity & Adversarial Audit

| Check | Result | Evidence |
|---|---|---|
| Hardcoded test results | **PASS** | Grep search confirmed no hardcoded mock client keys, responses, or test vectors in core logic. |
| Dummy / facade code | **PASS** | Complete implementation of NIO channels, virtual threads, frame parsing, unmasking, and reflection dispatch. |
| External library bypass | **PASS** | No Netty, Jetty, or external frameworks used; 100% JDK 21 standard library. |
| Fabricated test reports | **PASS** | 21 genuine, comprehensive unit and integration test scenarios in `BedrockWebSocketServerTest.java`. |
| Zero runtime dependencies | **PASS** | `bedrock-core/pom.xml` contains zero compile/runtime dependencies. |

---

## 5. Verdict

**Verdict**: **APPROVE**

Milestone 2 fulfills all requirements of `ORIGINAL_REQUEST.md` (R1, R2, R3, R4) and `PROJECT.md`. The implementation demonstrates outstanding architectural clarity, adheres strictly to the `🎓 BEDROCK TUTORIAL` standard, and provides robust virtual-threaded WebSocket infrastructure ready for Milestone 3 (Declarative Annotations & IoC integration).
