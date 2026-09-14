# E2E Test Infra: Bedrock Framework Version 2.0 WebSockets

## Test Philosophy
- **Opaque-Box & Requirement-Driven**: Derived strictly from `ORIGINAL_REQUEST.md` and RFC 6455 specifications.
- **Zero-Pollution & Clean Teardown**: Uses dynamic/ephemeral ports (`port 0`) and automatic server `stop()` to eliminate port conflicts and thread leaks.
- **Methodology**: Category-Partition + Boundary Value Analysis (BVA) + Pairwise Interaction Testing + Real-World Workload Testing.

---

## Feature Inventory & Test Mapping
| # | Feature | Source | Tier 1 (Coverage) | Tier 2 (Boundary) | Tier 3 (Pairwise) |
|---|---------|--------|:-----------------:|:-----------------:|:-----------------:|
| 1 | Handshake Header Validation | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 2 | Sec-WebSocket-Accept Calculation | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | HTTP 101 Switching Protocols | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 4 | Frame Bitmasking & Opcodes | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 5 | Client Mask Invariant (RFC §5.1) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 6 | 4-Byte XOR Unmasking | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 7 | Multi-Length Decoding (7/16/64 bit) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 8 | Server Frame Serialization | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 9 | Control Frames: Ping/Pong Echo | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 10| Close Handshake & Status Codes | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 11| UTF-8 Encoding Validation | RFC 6455 §8.1 | 5 | 5 | ✓ |
| 12| BedrockWebSocketServer Lifecycle | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 13| Virtual Thread Allocation | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 14| BedrockWebSocketSession API | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 15| Multi-Client Broadcast | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 16| Annotations (@BedrockSocket, etc.) | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 17| BedrockApp Integration | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 18| Server & App Teardown Lifecycle | explorer_tests_1 | 5 | 5 | ✓ |

---

## Test Architecture
- **Framework**: JUnit 5 (`org.junit.jupiter.api.*`) via Maven Surefire Plugin (`mvn test`, `mvn clean verify`).
- **Pass/Fail Semantics**: All tests must complete with 0 failures, 0 errors, 0 flaky tests.
- **Client Approaches**:
  1. `java.net.http.HttpClient.newWebSocketBuilder()`: High-level RFC-compliant client for E2E bidirectional messaging, lifecycle, sessions, and broadcasting.
  2. `java.net.Socket` / `java.nio.channels.SocketChannel`: Wire-level protocol client capable of transmitting forged/invalid bytes (unmasked frames, forbidden status codes, malformed UTF-8, non-minimal lengths) to verify strict server rejection per RFC 6455.
  3. Direct Unit Tests: In-memory byte buffer validation of handshake calculations and frame encode/decode without network sockets.
- **Directory Layout**:
  - `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/` (Unit tests)
  - `bedrock-core/src/test/java/com/bedrock/core/ws/server/` (Integration & wire tests)
  - `bedrock-example/src/test/java/com/bedrock/example/` (Example module test)

---

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Multi-user Live Chat Room | Handshake, Virtual Threads, Sessions, Text Frames, Broadcast, OnClose | High |
| 2 | High-frequency Telemetry Stream | Handshake, 16-bit Payloads, Virtual Threads, Send, Non-blocking buffers | High |
| 3 | Heartbeat Keepalive & Auto-Pong | Handshake, Ping control frame, Pong echo, Background keepalive | Medium |
| 4 | Graceful Disconnect & Close Handshake | Handshake, Close frame with status 1000 and reason, session registry cleanup | Medium |
| 5 | Protocol Violation Rejection & Recovery | Wire unmasked frame, status 1002 close, socket cleanup, server remains healthy for new clients | High |
| 6 | Large Payload Data Transfer (64KB+) | 16-bit/64-bit length encoding, buffer reallocation, UTF-8 parsing, echo | High |

---

## Coverage Thresholds
- **Tier 1 (Feature Coverage)**: ≥5 test cases per feature (testing isolated functionality with representative inputs).
- **Tier 2 (Boundary & Corner Cases)**: ≥5 test cases per feature (zero-length payloads, 125/126/127 length boundary limits, invalid status codes, unmasked client frames, malformed UTF-8).
- **Tier 3 (Cross-Feature Combinations)**: Pairwise coverage testing interactions (e.g. Handshake + Text + Ping + Broadcast + Close).
- **Tier 4 (Real-World Scenarios)**: ≥5 application-level multi-client integration scenarios.
- **Total Suite Minimum**: Comprehensive coverage ensuring 100% verification across all 18 core features.
