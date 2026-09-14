# Handoff Report: Milestone 2 Review & Adversarial Audit

**Agent**: `reviewer_m2_2`  
**Target Milestone**: Milestone 2: Virtual-Threaded NIO Server & Sessions  
**Date**: 2026-09-11  
**Verdict**: **APPROVE**  

---

## 1. Observation

1. **Lifecycle Teardown & Port Management**:
   - `BedrockWebSocketServer.java:220-238`: `start()` binds `ServerSocketChannel` to `InetSocketAddress(host, configuredPort)` with `StandardSocketOptions.SO_REUSEADDR = true`. Port 0 is dynamically resolved:
     ```java
     this.boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
     ```
     `getPort()` at line 200 checks `serverChannel.getLocalAddress()`.
   - `BedrockWebSocketServer.java:243-269`: `stop()` atomically transitions state via `running.compareAndSet(true, false)`. It closes `serverChannel`, which causes the blocking `serverChannel.accept()` in `acceptLoop()` to throw `AsynchronousCloseException` / `ClosedChannelException` (lines 311-313), cleanly breaking the loop. It joins `acceptThread` (lines 257-263) and closes all active sessions via `sessionRegistry.closeAll(WebSocketCloseStatus.GOING_AWAY_CODE, "Server shutting down")` (line 266).
   - `BedrockWebSocketServer.java:272-276`: Implements `AutoCloseable.close()`, which calls `stop()` inside `closed.compareAndSet(false, true)`. `start()` checks `if (closed.get()) throw new IllegalStateException("Cannot restart a closed BedrockWebSocketServer");` (lines 221-223).
   - `BedrockWebSocketServerTest.java:581-587`: Verifies that after `server.stop()`, the TCP port is released synchronously and can be rebound immediately by a new `ServerSocketChannel` without port leaks.

2. **Multi-Client Broadcasting & Resilience**:
   - `WebSocketSessionRegistry.java:167-192`: `broadcast(String text, Predicate filter)` loops over `sessions.values()`. If a session is closed, it is pruned immediately (lines 173-176). The message delivery `session.send(text)` is wrapped in an individual `try-catch` block (lines 178-190).
   - If an `IOException` or `IllegalStateException` occurs, the failing session is logged, removed via `sessions.remove(session.getId(), session)` (line 185), and closed. The loop continues to remaining sessions without interrupting delivery.
   - Dead/closed sessions are also pruned on `get(sessionId)` at lines 105-108 if `!session.isOpen()`.

3. **Protocol Violation Handling**:
   - `WebSocketFrameParser.java:143-148`:
     ```java
     if (requireMask && !masked) {
         throw new WebSocketException(
                 WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                 "Client frame must be masked (RFC 6455 §5.1)"
         );
     }
     ```
     `WebSocketCloseStatus.PROTOCOL_ERROR_CODE` equals `1002`.
   - `WebSocketClientHandler.java:248-251` catches `WebSocketException` during `WebSocketFrameParser.parse(buffer)` and invokes `handleProtocolException(channel, session, binding, protocolEx)` (lines 388-424), which writes a Close frame with status 1002, calls `binding.invokeOnError()`, unregisters the session, marks it closed, calls `binding.invokeOnClose()`, and closes the socket channel.
   - `WebSocketClientHandler.java:167-189` and `WebSocketHandshake.java:388-424`:
     - Unsupported version returns HTTP 426 Upgrade Required with header `Sec-WebSocket-Version: 13`.
     - Malformed HTTP request or missing `Sec-WebSocket-Key` returns HTTP 400 Bad Request.
     - Unregistered route returns HTTP 404 Not Found.

4. **Project Loom Concurrency & Locking**:
   - `WebSocketClientHandler.java:114-118`: Connection accepted is dispatched to:
     ```java
     Thread.ofVirtual().name("ws-client-", clientId).start(() -> handleConnection(clientChannel, clientId));
     ```
   - `StandardWebSocketSession.java:71, 233-244`: Frame serialization is guarded by `ReentrantLock writeLock`. Waiting on `ReentrantLock` under Java 21 yields the virtual thread continuation, unmounting from the carrier thread to the carrier `ForkJoinPool` without carrier thread pinning.

5. **Zero Third-Party Runtime Dependencies**:
   - `bedrock-core/pom.xml:18-38`: Only test dependencies exist (`junit-jupiter-api`, `mockito-core`, `junit-jupiter-engine`). Zero compile/runtime dependencies.

6. **Pedagogical Standard & Integrity**:
   - Rich `🎓 BEDROCK TUTORIAL` Javadocs exist on all 6 core classes and test class.
   - No hardcoded mocks, facade implementations, or bypasses.

---

## 2. Logic Chain

1. **Teardown & Port Release**:
   - Based on Observation 1, `BedrockWebSocketServer.stop()` terminates the accept virtual thread, releases the OS port by closing `serverChannel`, closes all active sessions with RFC 6455 code 1001, and prevents restarts via `closed` flag. `AutoCloseable` delegates cleanly to `stop()`. Thus, lifecycle teardown is correct, clean, and leak-free.

2. **Broadcasting Isolation**:
   - Based on Observation 2, `broadcast()` places each individual `session.send(text)` within its own `try-catch` boundary. A broken client TCP connection throws an exception during `writeRawInternal()`, marks itself closed, and is caught by the registry's error handler. The registry unregisters the broken session and continues its loop. Other clients receive the broadcast uninterrupted. Pruning of closed sessions is active in `broadcast()` and lazy in `get()`. Thus, broadcasting is resilient.

3. **Protocol Violation & Handshake Enforcement**:
   - Based on Observation 3, unmasked frames trigger `WebSocketException` with code 1002, resulting in an outbound Close frame with code 1002, callback dispatches, and socket closure. Handshake validation enforces HTTP 400 for malformed/missing headers, HTTP 426 with `Sec-WebSocket-Version: 13` for unsupported versions, and HTTP 404 for unmapped routes. Thus, protocol violation and handshake handling strictly adhere to RFC 6455.

4. **Zero Dependencies & Loom Physics**:
   - Based on Observations 4 and 5, only JDK 21 standard library APIs (`java.nio`, `java.net`, `java.util.concurrent`, `Thread.ofVirtual()`, `ReentrantLock`) are used. Zero runtime dependencies are in `pom.xml`. Carrier unmounting occurs during blocking `channel.read()`, and `ReentrantLock` prevents carrier pinning.

5. **Findings**:
   - *Minor Finding 1* (`WebSocketSessionRegistry.java:187`): In `broadcast()` error handler, calling `session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, ...)` attempts to send code 1006 on the wire, which `validateSendCode(1006)` rejects with `IllegalArgumentException`. The exception is swallowed and the channel was already closed by `markClosed()`, but this should be cleaned up in a future refactor to directly invoke `session.markClosed()` or send 1011.
   - *Minor Finding 2* (`WebSocketClientHandler.java:276`): The read buffer doubles up to 16 MB on large frames and does not shrink back to 8 KB after dispatch. For long-lived idle connections with many clients, shrinking buffers would reduce heap footprint.
   - Neither finding impairs correctness or violates requirements.

---

## 3. Caveats

- Milestone 3 will introduce classpath scanning for `@BedrockSocket` and integration with `BedrockApp`. Current endpoint registration is done explicitly via `registerEndpoint()` with `WebSocketEndpointBinding` (both reflective and functional modes).
- Incoming binary frames currently trigger status 1003 (Unsupported Data) by design, as the current server handles text-oriented chat/message endpoints.

---

## 4. Conclusion

**Verdict: APPROVE**

Milestone 2 satisfies all architectural, protocol, concurrency, and educational requirements established in `ORIGINAL_REQUEST.md` (R1, R2, R3, R4) and `PROJECT.md`. The implementation is robust, adheres to zero runtime dependencies, correctly implements Loom virtual threading, and provides complete RFC 6455 lifecycle and error handling.

---

## 5. Verification Method

To verify this review independently:
1. Inspect `bedrock-core/pom.xml` lines 18-38 to confirm zero compile/runtime dependencies.
2. Inspect `BedrockWebSocketServer.java` lines 220-276 to verify `start()`, `stop()`, and `close()` lifecycle.
3. Inspect `WebSocketSessionRegistry.java` lines 167-192 to verify per-session error isolation in `broadcast()`.
4. Inspect `WebSocketClientHandler.java` lines 167-189 and `WebSocketFrameParser.java` lines 143-148 to verify protocol error 1002 and HTTP 400/426/404 handling.
5. Inspect `StandardWebSocketSession.java` lines 68-72 and 233-245 to verify `ReentrantLock` carrier-friendly write synchronization.
6. Execute the test suite:
   ```bash
   mvn test -Dtest=BedrockWebSocketServerTest
   ```
   Confirm all 21 test scenarios pass.
