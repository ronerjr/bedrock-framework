# Handoff Report: Milestone 2 Test Plan & Pedagogical Standards

**Agent**: `explorer_m2_3`  
**Working Directory**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_3`  
**Date**: 2026-09-11  
**Target Milestone**: Milestone 2 (Virtual-Threaded NIO Server & Sessions)  
**Deliverable Document**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_3\test_and_tutorial_design.md`

---

## 1. Observation

1. **Protocol Implementation Status (Milestone 1)**:
   - `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/` contains complete, working RFC 6455 codecs: `WebSocketHandshake.java`, `WebSocketFrame.java`, `WebSocketFrameParser.java`, `WebSocketFrameWriter.java`, `WebSocketOpcode.java`, `WebSocketCloseStatus.java`, and `WebSocketException.java`.
   - Lines 81-82 of `WebSocketHandshake.java`:
     > "Once the server writes the HTTP 101 response bytes ending with double CRLF (\r\n\r\n), the HTTP parser is completely detached from the socket channel. From that exact byte onward, the socket enters raw full-duplex RFC 6455 framing mode on its dedicated Virtual Thread."
   - Existing unit tests (`WebSocketHandshakeTest.java`, `WebSocketFrameTest.java`, `WebSocketAdversarialTest.java`) all reside in `protocol/`.

2. **Absence of Server-Level Integration Tests**:
   - Directory listing of `bedrock-core/src/test/java/com/bedrock/core/ws/` returned only `protocol/` subfolder.
   - The server test directory `bedrock-core/src/test/java/com/bedrock/core/ws/server/` does not yet exist.
   - Existing HTTP tests (`BedrockAppTest.java`, `ContextTest.java`, etc.) exclusively mock `com.sun.net.httpserver.HttpExchange` in-memory; no existing test opens a real TCP listening socket.

3. **Peer Exploration Alignment (`explorer_m2_1` & `explorer_m2_2`)**:
   - `explorer_m2_1/server_design.md` specifies `BedrockWebSocketServer` and `WebSocketClientHandler`:
     - Lines 117-130: `new BedrockWebSocketServer(0)` binds to ephemeral port.
     - Lines 172-185: `public int getPort()` resolves local port via `((InetSocketAddress) serverChannel.getLocalAddress()).getPort()`.
     - Lines 219-245: `stop()` method interrupts accept loop, closes `serverChannel`, and invokes `sessionRegistry.closeAll(1001, "Server shutting down")`.
     - Lines 548-622: `dispatchFrame()` handles Text, Ping (immediate Pong reply), Pong, Close (echo Close frame + channel close), and unmasked frame protocol violations (Close 1002).
   - Lines 753-773 of `server_design.md`:
     - Explains that `SocketChannel.write` is non-thread-safe and requires synchronization.
     - Mandates `ReentrantLock` over `synchronized` to eliminate carrier thread pinning on Java 21 Virtual Threads.

4. **Zero Dependency & Java 21 Standard Tooling**:
   - `bedrock-core/pom.xml` contains zero third-party runtime dependencies.
   - Test dependencies in `pom.xml` are `org.junit.jupiter:junit-jupiter-api` (5.10.1) and `org.mockito:mockito-core` (5.12.0).
   - Java 21 standard library includes `java.net.http.HttpClient` and `java.net.http.WebSocket`, enabling 100% native client-side E2E testing without Netty or external WebSocket client libraries.

---

## 2. Logic Chain

1. **Step 1 (Ephemeral Port Invariant)**:  
   Because tests will execute concurrently in multi-core environments, static ports (e.g. 8080) create port conflicts or fail due to sockets lingering in OS `TIME_WAIT`.  
   *Inference*: Tests must configure `BedrockWebSocketServer(0)` and query `server.getPort()` dynamically. The test suite must assert `getPort() > 0` and verify that `stop()` immediately releases the port so that a subsequent `bind()` succeeds without error.

2. **Step 2 (Dual Testing Strategy: High-Level Client vs. Wire-Level Socket)**:  
   Standard WebSocket clients like `java.net.http.HttpClient` strictly follow RFC 6455 and cannot be coerced into sending unmasked client frames, illegal RSV bits, or malformed handshakes.  
   *Inference*: The test suite requires two distinct client mechanisms:
   - High-level `HttpClient` tests end-to-end user workflows: HTTP 101 handshake, `@OnOpen`, text message roundtrip, international UTF-8/emoji payloads, multi-client broadcast, and graceful close.
   - Low-level `java.net.Socket` tests byte-exact protocol invariants: raw HTTP 101 header validation, route 404 responses, version 426 responses, malformed 400 responses, inline Ping/Pong byte-matching, and unmasked frame protocol error 1002.

3. **Step 3 (Thread Safety & Concurrency Stress Testing)**:  
   Under high concurrency, multiple virtual threads writing to the same `SocketChannel` (e.g., broadcast + ping reply + individual session send) can interleave bytes if not protected by a `writeLock`.  
   *Inference*: Test Scenario 6 spawns 10 concurrent clients, asserts registry tracking, broadcasts across all sessions, and fires 50 concurrent `session.send()` operations on virtual threads to verify that no frame interleaving or 1002 protocol errors occur.

4. **Step 4 (Pedagogical Value & Loom Unmounting Demystification)**:  
   Spring and Netty have conditioned developers to view reactive event loops as the only solution for real-time WebSockets.  
   *Inference*: Javadoc tutorials for `BedrockWebSocketServer` and `WebSocketClientHandler` must explain the low-level mechanical reality: how virtual thread blocking reads invoke `Continuation.yield()`, unmount from carrier threads in the `ForkJoinPool`, register with OS epoll/kqueue, and re-mount upon network interrupts. This provides unmatched pedagogical clarity while reinforcing zero-dependency Java 21 best practices.

---

## 3. Caveats

1. **Annotation Scanning Scope**:  
   This design uses `WebSocketEndpointBinding` and `TestEchoEndpoint` for Milestone 2 testing. Full declarative `@BedrockSocket(path = "/chat")` annotation scanning with IoC reflection will be implemented in Milestone 3.
2. **Platform Multiplexer Differences**:  
   The underlying OS kernel multiplexer varies by operating system (`epoll` on Linux, `kqueue` on macOS, `IOCP` on Windows). The JVM Virtual Thread implementation abstracts this, but timing characteristics under stress may vary slightly between platforms.
3. **No External Libraries**:  
   No external WebSocket clients (e.g. Tyrus, nv-websocket-client, OkHttp) are used. All tests rely strictly on JDK 21 `java.net.http.*` and `java.net.Socket`.

---

## 4. Conclusion

The Unit & Integration Test Plan for `BedrockWebSocketServerTest.java` and Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc) are completely designed and documented in `test_and_tutorial_design.md`.

Key deliverables:
1. **16 Test Scenarios** covering ephemeral port binding, raw and standard HTTP 101 handshakes, text message echo, multi-byte UTF-8, 10-client broadcast, concurrent write lock safety, inline Ping/Pong, clean close handshake, server stop & port release, `AutoCloseable`, HTTP 404, HTTP 426, HTTP 400, and RFC 6455 unmasked frame 1002 enforcement.
2. **Two Complete 🎓 BEDROCK TUTORIAL Javadocs** for `BedrockWebSocketServer` and `WebSocketClientHandler` explaining Loom Virtual Threads vs. Reactive Netty, carrier unmounting mechanics, and `ReentrantLock` thread safety.

The design is ready for immediate code generation in the Milestone 2 implementation phase.

---

## 5. Verification Method

To independently verify this test suite design during the implementation phase:
1. Create directory:
   `bedrock-core/src/test/java/com/bedrock/core/ws/server/`
2. Write test class `BedrockWebSocketServerTest.java` containing the test scenarios specified in `test_and_tutorial_design.md` Section 2.
3. Run the specific test class:
   ```bash
   mvn test -Dtest=BedrockWebSocketServerTest
   ```
   Expected result: All 16 tests pass with 100% success.
4. Run full repository verification:
   ```bash
   mvn clean verify
   ```
   Expected result: All 81 baseline tests + Milestone 1 tests + Milestone 2 tests pass with zero regressions.
5. Invalidation Condition: Any test failure regarding port conflict, frame corruption during broadcast, or failure to echo Ping/Pong payload invalidates the implementation against this specification.
