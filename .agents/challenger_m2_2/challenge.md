# Adversarial Challenge Report: BedrockWebSocketServer Protocol, Control Frames & Concurrency

**Agent**: `challenger_m2_2`  
**Milestone**: Milestone 2: Virtual-Threaded NIO Server & Sessions  
**Date**: 2026-09-11  
**Target Components**: `BedrockWebSocketServer`, `WebSocketClientHandler`, `StandardWebSocketSession`, `WebSocketSessionRegistry`  
**Artifact Test Suite**: `bedrock-core/src/test/java/com/bedrock/core/ws/server/BedrockWebSocketRawFrameTest.java` (14 live wire-level tests)

---

## Challenge Summary

**Overall risk assessment**: **LOW** (All wire-level protocol violations, control frame invariants, and concurrency interleaving defenses passed empirical testing with zero defects).

The server exhibits robust compliance with RFC 6455 and Project Loom virtual threading semantics:
1. Raw wire protocol violations (unmasked client frames, missing Upgrade header, non-13 WebSocket version, unmapped routes) are strictly rejected with exact RFC 6455 and HTTP specification status codes and prompt socket closure.
2. Control frame semantics (Ping payload reflection in Pong, clean 1000 Close handshake echo with reason) operate inline without thread suspension or frame corruption.
3. High-concurrency simultaneous broadcasts combined with direct session sends are fully serialized by `ReentrantLock` on `StandardWebSocketSession`, preventing frame interleaving or byte splicing across virtual threads.

---

## Challenges

### [Low Risk] Challenge 1: Carrier Pinning vs Frame Interleaving Under High Concurrency

- **Assumption challenged**: Multiple virtual threads broadcasting messages concurrently over a shared `StandardWebSocketSession` could interleave byte fragments into the underlying NIO `SocketChannel`, or conversely, using `synchronized` blocks would pin the underlying OS carrier thread in JDK 21.
- **Attack scenario**: 50 virtual threads simultaneously invoke `broadcast()` and `session.send()` transmitting 500-byte multi-byte frames over a live TCP connection to a raw client reader.
- **Blast radius**: If locking is omitted, frame headers and payloads interleave, causing RFC 6455 framing desynchronization and client parser failure (`1002 Protocol Error`). If `synchronized` is used, OS carrier threads are pinned, starving Loom's ForkJoinPool.
- **Verification & Mitigation**: Verified that `StandardWebSocketSession` utilizes `ReentrantLock writeLock = new ReentrantLock()`. All writes (`send`, `sendPing`, `writeRaw`) execute under `writeLock`. The empirical test `testSimultaneousBroadcastAndDirectSendWithoutFrameInterleaving` verified 50 concurrent virtual threads transmitting 500-byte frames with zero byte interleaving or framing corruption.

### [Low Risk] Challenge 2: Mandatory Client Masking Invariant (RFC 6455 §5.1)

- **Assumption challenged**: A malicious client transmitting unmasked text or ping frames might bypass validation and execute application logic or poison the server buffer.
- **Attack scenario**: A raw client sends an unmasked frame (`MASK=0`, `FIN=1`, `Opcode=1` or `Opcode=9`) immediately after HTTP 101 handshake completion.
- **Blast radius**: Vulnerability to cache poisoning or protocol smuggling.
- **Verification & Mitigation**: `WebSocketFrameParser` enforces `if (!masked) throw new WebSocketException(1002, ...)`. In `testClientUnmaskedTextFrameRejection` and `testClientUnmaskedPingFrameRejection`, the server immediately responds with an unmasked Close frame (`Opcode 0x8`, code `1002`), notifies `@OnError` / `@OnClose`, and shuts down the underlying TCP connection.

### [Low Risk] Challenge 3: HTTP Handshake Invariants (RFC 6455 §4.2 & §4.4)

- **Assumption challenged**: Handshake requests with missing `Upgrade` headers, missing `Connection` headers, malformed `Sec-WebSocket-Key` Base64 strings, or unsupported versions (e.g. HyBi-10 `Sec-WebSocket-Version: 8`) might cause unhandled exceptions, hang the socket, or erroneously upgrade the connection.
- **Attack scenario**: Inject malformed HTTP GET requests over raw TCP socket.
- **Blast radius**: Resource exhaustion from hanging half-open sockets or HTTP smuggling.
- **Verification & Mitigation**: `WebSocketHandshake` parser rigorously validates headers and Base64 format:
  - Missing `Upgrade` header -> Returns `HTTP/1.1 400 Bad Request` and closes socket.
  - Missing `Connection` header -> Returns `HTTP/1.1 400 Bad Request` and closes socket.
  - Malformed key -> Returns `HTTP/1.1 400 Bad Request` and closes socket.
  - Version != 13 -> Returns `HTTP/1.1 426 Upgrade Required` with `Sec-WebSocket-Version: 13` and closes socket.
  - Unmapped route -> Returns `HTTP/1.1 404 Not Found` and closes socket.

### [Low Risk] Challenge 4: Control Frame Immediate Response & Two-Way Close Handshake

- **Assumption challenged**: Inline control frames (Ping and Close) might be buffered, swallowed, delayed behind queuing, or fail to cleanly terminate TCP channels.
- **Attack scenario**: Transmit Ping frames with 0-byte, 16-byte, and maximum 125-byte arbitrary binary payloads. Send Close frames with code 1000 and arbitrary UTF-8 reason phrase, and empty 0-byte Close frames.
- **Blast radius**: Client keepalive heartbeat failures, hung connections, zombie sessions accumulating in registry.
- **Verification & Mitigation**:
  - `testPingPongPayloadReflectionAcrossSizes` verifies that Ping payloads of 0, 16, and 125 bytes are reflected byte-for-byte in Pong frames (`Opcode 0xA`, unmasked) immediately.
  - `testClientInitiatedCloseEchoAndTermination` verifies that a client Close frame with status 1000 and reason string triggers an immediate Close echo frame with status 1000 and identical reason, unregisters from session registry, invokes `@OnClose`, and closes the TCP channel (EOF).
  - `testClientEmptyCloseFrameEcho` verifies that an empty Close frame (0-byte payload) defaults echo to status 1000 Normal Closure.

### [Low Risk] Challenge 5: Multi-Byte UTF-8 & Emoji Wire-Level Integrity

- **Assumption challenged**: Multi-byte UTF-8 streams (CJK, Cyrillic, Portuguese accents, and 4-byte astral plane emojis) could be corrupted by single-byte truncation or character set mismatch across raw socket reads.
- **Attack scenario**: Transmit complex unicode strings including surrogate pairs and compound emojis (`🦖 Bedrock 2.0 WebSockets! 🚀 Olá Mundo! João comprou maçãs & café na estação. Привет мир! こんにちは世界! 안녕하세요! 🌍🎉🔥⚡️ (4-byte emojis: 👨‍👩‍👧‍👦, 🛸, 🦾)`), as well as adversarial invalid UTF-8 byte sequences (`0xC3 0x28`).
- **Blast radius**: Silent data corruption or application deserialization crash.
- **Verification & Mitigation**:
  - `testMultiByteUtf8AndEmojiRoundtrip` verified exact byte and string fidelity across live socket roundtrip.
  - `testAdversarialInvalidUtf8PayloadRejection` verified that invalid UTF-8 byte sequences trigger an immediate RFC 6455 Close status `1007 (Invalid Frame Payload Data)` and socket closure.

---

## Stress Test Results

| Test Scenario | Method | Expected Behavior | Actual Behavior | Result |
|---|---|---|---|---|
| Unmasked Text Frame | `testClientUnmaskedTextFrameRejection` | Close 1002 (Protocol Error) + TCP close | Close 1002 + EOF (-1) | **PASS** |
| Unmasked Ping Frame | `testClientUnmaskedPingFrameRejection` | Close 1002 (Protocol Error) + TCP close | Close 1002 + EOF (-1) | **PASS** |
| Missing Upgrade Header | `testHandshakeMissingUpgradeHeaderReturns400` | HTTP 400 Bad Request + TCP close | HTTP 400 + EOF (-1) | **PASS** |
| Unsupported Version (8) | `testHandshakeWithVersion8Returns426` | HTTP 426 Upgrade Required + Sec-WebSocket-Version: 13 | HTTP 426 + header + EOF (-1) | **PASS** |
| Unmapped Route | `testHandshakeToNonexistentRouteReturns404` | HTTP 404 Not Found + TCP close | HTTP 404 + EOF (-1) | **PASS** |
| Missing Connection Header | `testHandshakeMissingConnectionHeaderReturns400` | HTTP 400 Bad Request + TCP close | HTTP 400 + EOF (-1) | **PASS** |
| Malformed Key | `testHandshakeWithMalformedKeyReturns400` | HTTP 400 Bad Request + TCP close | HTTP 400 + EOF (-1) | **PASS** |
| Ping/Pong Reflection (16B, 125B, 0B) | `testPingPongPayloadReflectionAcrossSizes` | Pong echoing exact payload bytes | Pong echoing exact bytes | **PASS** |
| Close Handshake (1000 + Reason) | `testClientInitiatedCloseEchoAndTermination` | Close echo 1000 + reason + TCP close | Close 1000 + reason + EOF | **PASS** |
| Empty Close Frame | `testClientEmptyCloseFrameEcho` | Close echo defaulting to 1000 + TCP close | Close 1000 + EOF | **PASS** |
| Multi-Byte UTF-8 & Emoji Roundtrip | `testMultiByteUtf8AndEmojiRoundtrip` | Exact string preserved across wire | Exact string preserved | **PASS** |
| Adversarial Invalid UTF-8 Payload | `testAdversarialInvalidUtf8PayloadRejection` | Close 1007 (Invalid Data) + TCP close | Close 1007 + EOF | **PASS** |
| Interleaved Control & Text Frames | `testPingInterleavedBetweenTextFrames` | Text, Pong, Text received intact | All 3 frames intact | **PASS** |
| Concurrency & Frame Interleaving | `testSimultaneousBroadcastAndDirectSendWithoutFrameInterleaving` | 50 concurrent writes without byte interleaving | 50 frames intact and parsed cleanly | **PASS** |

---

## Unchallenged Areas

- **Binary Frame Application Routing**: incoming binary frames trigger Close 1003 (Unsupported Data) by design for text-only endpoints; binary endpoint dispatch is deferred to Milestone 3 / 4.
- **Classpath Annotation Discovery (`@BedrockSocket`)**: Scoped for Milestone 3 (Milestone 2 explicitly establishes programmatic binding via `WebSocketEndpointBinding`).
