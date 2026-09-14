# Handoff Report: BedrockWebSocketServer & WebSocketClientHandler Design

**Agent**: `explorer_m2_1`  
**Working Directory**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_1`  
**Type**: Hard Handoff (Design Complete)  
**Target Milestone**: Milestone 2 — Virtual-Threaded NIO Server & Sessions

---

## 1. Observation

1. **Protocol Codebase Invariants (`com.bedrock.core.ws.protocol`)**:
   - `WebSocketHandshake.java`:
     - Lines 291-311: `public static HandshakeParseResult parse(ByteBuffer buffer)` non-destructively searches for `\r\n\r\n`, advances buffer position to header end when found, and parses headers. Returns `null` when header is incomplete.
     - Lines 442-477: Helper methods `createResponse400(String reason)`, `createResponse426()`, and `createResponse404(String path)` generate RFC-compliant HTTP error byte arrays with `Connection: close`.
     - Line 512: `HandshakeParseResult` record exposes `valid()`, `statusCode()`, `path()`, `acceptKey()`, `responseBytes()`, and `isSuccess()`.
   - `WebSocketFrameParser.java`:
     - Lines 108-267: `parse(ByteBuffer buffer, boolean requireMask)` marks position (`buffer.mark()`), resets (`buffer.reset()`) and returns `null` if full frame hasn't arrived, unmasks payload in-place using 4-byte XOR, and throws `WebSocketException` with RFC status codes on protocol violations (e.g. unmasked client frame -> 1002, malformed UTF-8 -> 1007, excessive payload -> 1009).
   - `WebSocketFrameWriter.java`:
     - Lines 62-125: Utility methods `createTextFrame(String)`, `createPongFrame(byte[])`, `createPingFrame(byte[])`, and `createCloseFrame(int, String)` emit unmasked server frames.
   - `WebSocketCloseStatus.java`:
     - Lines 112-125: Pre-defined status codes: `NORMAL_CLOSURE_CODE = 1000`, `GOING_AWAY_CODE = 1001`, `PROTOCOL_ERROR_CODE = 1002`, `UNSUPPORTED_DATA_CODE = 1003`, `NO_STATUS_CODE = 1005`, `ABNORMAL_CLOSURE_CODE = 1006`, `INVALID_DATA_CODE = 1007`, `SERVER_ERROR_CODE = 1011`.
   - `WebSocketException.java`:
     - Lines 27-96: Holds 16-bit status code (`getStatusCode()`) and diagnostic reason (`getReason()`).

2. **Server & Architectural Requirements (`PROJECT.md` & `ORIGINAL_REQUEST.md`)**:
   - `PROJECT.md` line 16: `BedrockWebSocketServer` requires dedicated `ServerSocketChannel` bound to configured or ephemeral port (`port 0`), clean lifecycle (`start()`, `stop()`, `AutoCloseable`).
   - `PROJECT.md` line 17 & `ORIGINAL_REQUEST.md` line 32: Concurrency model mandates dedicated Virtual Thread per client (`Thread.ofVirtual().name("ws-client-" + clientId).start(...)`) with synchronous blocking I/O loop.
   - `PROJECT.md` lines 109-119: Endpoint binding contract requires dispatching `onOpen`, `onMessage`, `onClose`, and `onError`.
   - `PROJECT.md` line 118: `int getPort()` must return the bound local port via `((InetSocketAddress) serverChannel.getLocalAddress()).getPort()`.

3. **Current Codebase State**:
   - Package `com.bedrock.core.ws.protocol` is 100% complete and verified by unit tests (`WebSocketHandshakeTest`, `WebSocketFrameTest`, `WebSocketAdversarialTest`).
   - Package `com.bedrock.core.ws.server` does not yet exist; classes `BedrockWebSocketServer`, `WebSocketClientHandler`, `WebSocketEndpointBinding`, `BedrockWebSocketSession`, and `WebSocketSessionRegistry` are slated for implementation in Milestone 2.

---

## 2. Logic Chain

1. **Ephemeral Port Binding Logic**:
   - Based on Observation 2, automated testing requires port collision avoidance.
   - When configured with `port = 0`, binding `serverChannel.bind(new InetSocketAddress(host, 0))` delegates port allocation to the OS kernel.
   - The bound port is immediately retrievable via `((InetSocketAddress) serverChannel.getLocalAddress()).getPort()`.
   - Therefore, `getPort()` returning this value allows dynamic, zero-collision test execution.

2. **Virtual Thread & NIO Synchronization Logic**:
   - Based on Observation 2, `Thread.ofVirtual().name("ws-client-" + clientId).start(...)` must run the synchronous client loop.
   - When a virtual thread invokes `SocketChannel.read(ByteBuffer)`, Project Loom unmounts the virtual thread from its OS carrier thread during network idle periods.
   - However, `SocketChannel.write(ByteBuffer)` is not thread-safe under concurrent invocation (e.g. concurrent `session.send()` and `sessionRegistry.broadcast()`).
   - Therefore, outbound frame writes must be serialized per session using `java.util.concurrent.locks.ReentrantLock`, which does not pin the virtual thread's carrier thread.

3. **Handshake Pipelining & HTTP Detachment Logic**:
   - Based on Observation 1, `WebSocketHandshake.parse(ByteBuffer)` advances the buffer position to the end of the HTTP headers delimiter (`\r\n\r\n`).
   - If the client sent WebSocket frames in the same TCP segment as the HTTP upgrade request, those bytes remain in the buffer between `position()` and `limit()`.
   - Calling `buffer.compact()` preserves these bytes at the start of the buffer when transitioning to the frame loop, preventing frame data loss.

4. **Opcode State Machine Logic**:
   - Based on Observation 1, incoming frames are parsed into `WebSocketFrame` with opcode and payload.
   - Opcode `0x1 (TEXT)`: Extracts payload text and dispatches to `binding.invokeOnMessage(session, text)`.
   - Opcode `0x9 (PING)`: Requires immediate RFC-compliant Pong reply. Emits `WebSocketFrameWriter.createPongFrame(frame.getPayload())` to the channel.
   - Opcode `0xA (PONG)`: Keepalive acknowledgment; maintains connection state.
   - Opcode `0x8 (CLOSE)`: Extracts status code and reason, echoes Close frame, unregisters session, invokes `binding.invokeOnClose(session, code, reason)`, and cleanly closes `SocketChannel`.
   - `WebSocketException`: Detected protocol error (e.g. unmasked client frame). Handler writes Close frame with `ex.getStatusCode()`, invokes `binding.invokeOnError(session, ex)`, unregisters, and closes channel.

---

## 3. Caveats

1. **Session Registry Coordination**:
   - `BedrockWebSocketSession` and `WebSocketSessionRegistry` are being concurrently designed by `explorer_m2_2`.
   - The design in `server_design.md` specifies the exact interface contracts required by `WebSocketClientHandler` (`getId()`, `getRemoteAddress()`, `getPath()`, `isOpen()`, `writeRaw(ByteBuffer)`, `markClosed()`, `close()`).
2. **Reflective vs Functional Endpoints**:
   - Milestone 2 focuses on core server mechanics; declarative `@BedrockSocket` annotation scanning will be fully wired in Milestone 3 (`com.bedrock.core.ws.annotation`).
   - `WebSocketEndpointBinding` interface abstracts the invocation layer so `BedrockWebSocketServer` can test both reflected and programmatic endpoints.
3. **No Caveats Beyond Above**: No additional assumptions or unexplored areas.

---

## 4. Conclusion

1. The architectural and technical design for `BedrockWebSocketServer.java` and `WebSocketClientHandler.java` is complete, robust, and documented in detail in:
   `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_1\server_design.md`.
2. The design fully satisfies all requirements:
   - Synchronous blocking NIO on `ServerSocketChannel` and `SocketChannel` executing on Java 21 Virtual Threads.
   - Dynamic ephemeral port resolution (`getPort()`).
   - Thread-safe route registry with HTTP 404 fallback for unmatched paths.
   - Full RFC 6455 handshake lifecycle (HTTP 101, 400, 426).
   - In-place XOR frame unmasking and opcode dispatch (Text, Ping/Pong, Close).
   - Thread-safe outbound frame writing via `ReentrantLock`.
   - Clean shutdown with `1001 Going Away` close frames and channel disposal implementing `AutoCloseable`.

---

## 5. Verification Method

To verify the design and implementation when coded:
1. **Compilation & Build**:
   ```powershell
   mvn clean test-compile
   ```
2. **Existing Baseline Regressions**:
   ```powershell
   mvn test -Dtest=WebSocketHandshakeTest,WebSocketFrameTest,WebSocketAdversarialTest
   ```
3. **Milestone 2 Server Test Suite** (to be executed by `worker_m2` / test suite):
   - Verify `BedrockWebSocketServer` binds on port 0 and returns `getPort() > 0`.
   - Verify `WebSocketHandshake` integration returns HTTP 101 for valid handshake and HTTP 404 for unknown path.
   - Verify `Thread.currentThread().isVirtual()` returns `true` inside `@OnOpen` and `@OnMessage`.
   - Verify Ping frames receive immediate Pong replies.
   - Verify unmasked frames trigger close status code `1002`.
   - Verify server `stop()` terminates all client sessions and frees the port cleanly.
