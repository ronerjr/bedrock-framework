# Handoff Report: Milestone 2 — Wire Protocol, Control Frames & Concurrency Adversarial Challenge

**Agent**: `challenger_m2_2`  
**Milestone**: Milestone 2: Virtual-Threaded NIO Server & Sessions  
**Date**: 2026-09-11  
**Verdict**: **APPROVE**

---

## 1. Observation

1. **Protocol Violation Handling**:
   - `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrameParser.java`:
     - Line 122: `if (!masked) { throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, "Client frame must be masked (RFC 6455 §5.1)"); }`
   - `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketClientHandler.java`:
     - Lines 248-251 & 388-424: Protocol violations caught by `handleProtocolException(...)` which sends an RFC 6455 Close frame with code 1002, unregisters the session, marks it closed, invokes `@OnError` and `@OnClose`, and closes the channel.
     - Lines 167-179 & 184-188:
       - Unsupported version (`Sec-WebSocket-Version != 13`) writes `HTTP/1.1 426 Upgrade Required` with `Sec-WebSocket-Version: 13` header and closes channel.
       - Missing Upgrade header, missing Connection header, or malformed key writes `HTTP/1.1 400 Bad Request` and closes channel.
       - Unregistered route writes `HTTP/1.1 404 Not Found` with path detail and closes channel.

2. **Control Frame Mechanics**:
   - `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketClientHandler.java`:
     - Lines 322-332: `case PING` creates a Pong frame echoing the exact `frame.getPayload()` bytes via `WebSocketFrameWriter.createPongFrame(...)` and sends it via `session.writeRaw(...)`.
     - Lines 340-370: `case CLOSE` inspects client close code and reason, defaults empty close code to 1000, transmits matching Close frame echo via `session.writeRaw(...)`, unregisters session, invokes `@OnClose`, and cleanly closes the TCP channel.

3. **Concurrency & Carrier-Unmounting Protection**:
   - `bedrock-core/src/main/java/com/bedrock/core/ws/server/StandardWebSocketSession.java`:
     - Line 71: `private final ReentrantLock writeLock = new ReentrantLock();`
     - Lines 182-192: `writeRaw(ByteBuffer buffer)` acquires `writeLock.lock()`, writes fully via `writeRawInternal`, and releases in `finally`.
     - Lines 232-245: `writeFrameUnderLock(byte[] frameBytes)` acquires `writeLock.lock()`, writes fully via `writeRawInternal`, and releases in `finally`.
     - Zero `synchronized` blocks are present in `StandardWebSocketSession`, guaranteeing that virtual threads under write contention yield OS carrier threads without pinning Loom's ForkJoinPool.

4. **Live Wire-Level Adversarial Test Suite**:
   - `bedrock-core/src/test/java/com/bedrock/core/ws/server/BedrockWebSocketRawFrameTest.java` defines 14 tests:
     - `testClientUnmaskedTextFrameRejection`: asserts Close frame 1002 (Protocol Error) and TCP EOF.
     - `testClientUnmaskedPingFrameRejection`: asserts Close frame 1002 (Protocol Error) and TCP EOF.
     - `testHandshakeMissingUpgradeHeaderReturns400`: asserts HTTP 400 Bad Request and TCP EOF.
     - `testHandshakeWithVersion8Returns426`: asserts HTTP 426 Upgrade Required with header `Sec-WebSocket-Version: 13`.
     - `testHandshakeToNonexistentRouteReturns404`: asserts HTTP 404 Not Found.
     - `testHandshakeMissingConnectionHeaderReturns400`: asserts HTTP 400 Bad Request.
     - `testHandshakeWithMalformedKeyReturns400`: asserts HTTP 400 Bad Request.
     - `testPingPongPayloadReflectionAcrossSizes`: asserts 16-byte, 125-byte, and 0-byte Ping payloads are reflected identically in unmasked Pong frames.
     - `testClientInitiatedCloseEchoAndTermination`: asserts Close status 1000 + reason echo and socket EOF.
     - `testClientEmptyCloseFrameEcho`: asserts 0-byte Close defaults to status 1000 and socket EOF.
     - `testMultiByteUtf8AndEmojiRoundtrip`: asserts exact unicode and astral emoji roundtrip integrity.
     - `testAdversarialInvalidUtf8PayloadRejection`: asserts invalid UTF-8 bytes trigger Close 1007 (Invalid Data).
     - `testPingInterleavedBetweenTextFrames`: asserts Ping control frames interleaved between text frames arrive uncorrupted.
     - `testSimultaneousBroadcastAndDirectSendWithoutFrameInterleaving`: asserts 50 virtual threads simultaneously writing 500-byte broadcast and direct frames over a live socket produce 50 cleanly framed, uncorrupted messages without byte interleaving.

---

## 2. Logic Chain

1. **Protocol Error 1002**:
   - RFC 6455 §5.1 mandates: *"A client MUST mask all frames that it sends to the server... The server MUST close the connection upon receiving a frame that is not masked. In this case, a server MAY send a Close frame with a status code of 1002 (protocol error)"*.
   - Observation 1 demonstrates `WebSocketFrameParser` detects unmasked client frames and throws `WebSocketException(1002)`.
   - Observation 1 demonstrates `WebSocketClientHandler.handleProtocolException` transmits the 1002 Close frame and closes the channel.
   - Observation 4 validates this on raw wire for both text and ping frames.

2. **HTTP Handshake Failures (400, 426, 404)**:
   - RFC 6455 §4.2.1 and §4.4 specify handshake validation rules.
   - Handshake missing `Upgrade: websocket` or `Connection: Upgrade` violates Section 4.2.1, resulting in HTTP 400 Bad Request.
   - `Sec-WebSocket-Version` not equal to 13 violates Section 4.4, requiring HTTP 426 Upgrade Required with `Sec-WebSocket-Version: 13`.
   - Requesting an unmapped URI path cannot be upgraded, returning HTTP 404 Not Found.
   - Observation 1 and Observation 4 confirm exact status codes, headers, and TCP closure for each condition.

3. **Control Frame Immediate Echo & Clean Closure**:
   - RFC 6455 §5.5.2 mandates: *"Upon receipt of a Ping frame, an endpoint MUST send a Pong frame in response... with identical 'Application Data'"*.
   - Observation 2 demonstrates `WebSocketClientHandler` immediately constructs a Pong frame echoing the exact payload bytes.
   - RFC 6455 §5.5.1 mandates a two-way close handshake where the recipient of a Close frame sends a Close frame in response and closes the socket.
   - Observation 2 and Observation 4 verify status 1000, reason phrase preservation, and TCP channel shutdown.

4. **Concurrency & Frame Interleaving Prevention**:
   - Java NIO `SocketChannel.write` is non-atomic. Multiple threads writing concurrently without synchronization produce corrupted byte streams.
   - In Java 21 Project Loom, using `synchronized` locks causes virtual threads to pin OS carrier threads during blocking.
   - Observation 3 shows `StandardWebSocketSession` protects all socket write operations with `ReentrantLock writeLock`. Virtual threads yielding under contention do not pin OS carrier threads.
   - Observation 4 shows `testSimultaneousBroadcastAndDirectSendWithoutFrameInterleaving` stresses 50 concurrent virtual threads executing simultaneous broadcasts and direct sends of 500-byte frames. All 50 frames arrive intact with valid RFC 6455 framing and intact payloads, proving zero byte interleaving.

---

## 3. Caveats

- **Binary Frame Application Routing**: incoming binary frames trigger Close 1003 (Unsupported Data) by design for text-only endpoints; binary endpoint dispatch is deferred to Milestone 3 / 4.
- **Classpath Annotation Discovery (`@BedrockSocket`)**: Scoped for Milestone 3 (Milestone 2 explicitly establishes programmatic binding via `WebSocketEndpointBinding`).

---

## 4. Conclusion

**Verdict: APPROVE**

The network and protocol implementation of `BedrockWebSocketServer`, `WebSocketClientHandler`, `StandardWebSocketSession`, and `WebSocketSessionRegistry` meets all requirements of Milestone 2 and RFC 6455 with zero identified defects or regressions:
- Wire protocol violations (unmasked client frames, missing Upgrade header, non-13 WebSocket version, unmapped routes) correctly return RFC 6455 code 1002, HTTP 400, HTTP 426, and HTTP 404 respectively.
- Control frames (Ping payload reflection in Pong, 1000 Close handshake echo and termination) operate strictly according to RFC 6455 §5.5.
- Concurrency serialization via `ReentrantLock` in `StandardWebSocketSession` successfully prevents frame interleaving during simultaneous broadcasts and direct writes without carrier thread pinning.

---

## 5. Verification Method

To independently verify the test suite:

1. **Execute Milestone 2 Raw Wire Adversarial Test Suite**:
   ```bash
   mvn test -Dtest=BedrockWebSocketRawFrameTest
   ```
   *Expected Outcome*: All 14 test scenarios pass with 100% success.

2. **Execute Full Milestone 2 Integration Test Suite**:
   ```bash
   mvn test -Dtest=BedrockWebSocketServerTest,BedrockWebSocketRawFrameTest
   ```
   *Expected Outcome*: All 35 tests pass with 100% success.

3. **Verify Lock Architecture**:
   - Inspect `StandardWebSocketSession.java` lines 71, 183, 233 to confirm `ReentrantLock writeLock` guards all writes without carrier thread pinning.
