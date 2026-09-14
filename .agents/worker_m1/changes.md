# Changes Summary — Milestone 1: RFC 6455 Protocol Engine

## Overview
Implemented the foundational low-level RFC 6455 protocol engine for the Bedrock Framework in `com.bedrock.core.ws.protocol`.
Strict adherence to zero third-party runtime dependencies (100% JDK 21 standard library), full RFC 6455 compliance, in-place 4-byte XOR unmasking, minimal length validation, and extensive educational `🎓 BEDROCK TUTORIAL` Javadocs.

## File Modifications & Additions

### 1. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketOpcode.java`
- **Purpose**: RFC 6455 4-bit frame opcode representation and classification.
- **Key Details**:
  - Enum constants: `CONTINUATION(0x0)`, `TEXT(0x1)`, `BINARY(0x2)`, `CLOSE(0x8)`, `PING(0x9)`, `PONG(0xA)`.
  - Helpers: `isControl()`, `isData()`, `getCode()`, `getCodeAsByte()`.
  - Wire validation: `fromCode(int)` verifies valid opcodes and fails unknown or reserved codes ($0x3-0x7$, $0xB-0xF$) with RFC 6455 `1002 Protocol Error`.
  - `🎓 BEDROCK TUTORIAL` Javadoc on 32-bit frame header Byte 0 layout and control bit invariant.

### 2. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketCloseStatus.java`
- **Purpose**: RFC 6455 §7.4 status code registry, wire-forbidden status enforcement, and status descriptions.
- **Key Details**:
  - Registered status codes: `NORMAL_CLOSURE(1000)`, `GOING_AWAY(1001)`, `PROTOCOL_ERROR(1002)`, `UNSUPPORTED_DATA(1003)`, `NO_STATUS_RECEIVED(1005)`, `ABNORMAL_CLOSURE(1006)`, `INVALID_FRAME_PAYLOAD_DATA(1007)`, `POLICY_VIOLATION(1008)`, `MESSAGE_TOO_BIG(1009)`, `MANDATORY_EXTENSION(1010)`, `INTERNAL_SERVER_ERROR(1011)`, `TLS_HANDSHAKE_FAILURE(1015)`.
  - Wire enforcement: `isWireForbidden(int)` detects 1005, 1006, 1015 (reserved strictly for local JVM use).
  - Outbound validation: `validateSendCode(int)` throws `IllegalArgumentException` on wire-forbidden or unassigned codes.
  - Inbound validation: `validateReceivedCode(int)` throws `WebSocketException(1002)` when wire-forbidden or invalid status codes appear on the wire.
  - `🎓 BEDROCK TUTORIAL` Javadoc on the two-way close handshake and why 1005/1006/1015 cannot appear on the wire.

### 3. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketException.java`
- **Purpose**: Runtime protocol exception encapsulating an RFC 6455 close status code.
- **Key Details**:
  - Contains `statusCode` and descriptive message.
  - Constructors defaulting to status code `1002 (Protocol Error)` or `1011 (Internal Server Error)`.
  - Allows connection loop to immediately format and serialize a compliant RFC 6455 Close frame upon catching protocol errors.
  - `🎓 BEDROCK TUTORIAL` Javadoc explaining error propagation in binary framing.

### 4. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrame.java`
- **Purpose**: Immutable value representation of an RFC 6455 WebSocket frame.
- **Key Details**:
  - Immutable fields: `fin`, `opcode`, `masked`, `payload`, `closeStatusCode`, `closeReason`.
  - Defensive copying on constructor and `getPayload()`.
  - Close frame inspection: automatically parses 2-byte big-endian status code and UTF-8 reason string; handles empty close frame as 1005.
  - Factory methods: `text(String)`, `binary(byte[])`, `ping(byte[])`, `pong(byte[])`, `close(int, String)`.
  - `🎓 BEDROCK TUTORIAL` Javadoc explaining frame immutability and thread safety.

### 5. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketHandshake.java`
- **Purpose**: HTTP 101 Switching Protocols negotiation, cryptographic validation, and response generation.
- **Key Details**:
  - Constant: `GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"`.
  - `isUpgradeRequest(String)`: verifies GET method, HTTP >= 1.1, `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Version: 13`, non-blank `Sec-WebSocket-Key`.
  - `computeAccept(String)`: computes `Base64(SHA-1(clientKey.trim() + GUID))` verified against RFC 6455 §1.3 test vector.
  - `createHandshakeResponse(String)`: generates compliant HTTP 101 response bytes.
  - `extractPath(String)`: cleans path by stripping query parameters.
  - `findHeaderEnd(ByteBuffer)` / `parse(ByteBuffer)`: non-destructive header boundary scan and parsing for NIO `ByteBuffer`.
  - `🎓 BEDROCK TUTORIAL` Javadoc explaining why WebSockets use HTTP 101 and the security physics of the RFC GUID against cache poisoning and cross-protocol attacks.

### 6. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrameParser.java`
- **Purpose**: High-performance, streaming NIO `ByteBuffer` frame decoder and in-place XOR unmasker.
- **Key Details**:
  - Mark/reset non-destructive parser pattern: returns `null` if header or payload is incomplete, allowing virtual threads to buffer incoming TCP fragments cleanly.
  - Client masking enforcement: rejects unmasked incoming frames with `1002 Protocol Error`.
  - Bitwise decoding of FIN, RSV1-3 (rejects non-zero with 1002), and Opcode.
  - Minimal length encoding validation: verifies 16-bit extended length $\ge 126$ and 64-bit extended length $\ge 65536$ with MSB=0 (rejects non-minimal with 1002).
  - Control frame constraints: FIN=1 and payload $\le 125$ bytes (rejects violations with 1002).
  - In-place XOR unmasking: $D_i = E_i \oplus M_{i \pmod 4}$ optimized using bitwise `i & 3`. Zero extra heap allocation.
  - UTF-8 validation: strict decoding using `CharsetDecoder` with `CodingErrorAction.REPORT`; throws `WebSocketException(1007)` on malformed UTF-8 in text frames and close reasons.
  - Close frame payload validation: rejects 1-byte payloads and wire-forbidden codes with 1002.
  - `🎓 BEDROCK TUTORIAL` Javadoc explaining frame anatomy and the transparent cache poisoning vulnerability that led to mandatory client masking.

### 7. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrameWriter.java`
- **Purpose**: Server-to-client frame serializer.
- **Key Details**:
  - Server unmasked invariant: server frames are strictly unmasked (`MASK = 0`), saving CPU cycles on server nodes.
  - Encodes 7-bit, 16-bit extended, or 64-bit extended lengths using canonical minimal-byte rules.
  - Serializers: `createTextFrame(String)`, `createBinaryFrame(byte[])`, `createPingFrame(byte[])`, `createPongFrame(byte[])`, `createCloseFrame(int, String)`.
  - Low-allocation serialization: allocates single contiguous byte array for header + payload.
  - Test helper `createMaskedFrame` for client frame emulation.
  - `🎓 BEDROCK TUTORIAL` Javadoc explaining server unmasked framing and protocol asymmetry.

### 8. `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/WebSocketHandshakeTest.java`
- **Purpose**: Complete unit test suite for handshake negotiation.
- **Coverage**: 12 comprehensive test methods:
  1. `shouldComputeOfficialRfc6455SecWebSocketAccept` (RFC 6455 §1.3 test vector)
  2. `shouldComputeSecWebSocketAcceptForSecondaryVector`
  3. `shouldThrowOnNullOrBlankClientKey`
  4. `shouldRecognizeValidUpgradeRequest`
  5. `shouldRecognizeValidUpgradeRequestWithMixedCaseAndExtraHeaders`
  6. `shouldRejectRequestWhenUpgradeHeaderMissingOrNotWebSocket`
  7. `shouldRejectRequestWhenConnectionHeaderMissingOrNotUpgrade`
  8. `shouldRejectRequestWhenSecWebSocketVersionMissingOrNot13`
  9. `shouldExtractSecWebSocketKeyWithWhitespaceTrimming`
  10. `shouldReturnNullWhenSecWebSocketKeyMissing`
  11. `shouldExtractRequestPath`
  12. `shouldGenerateCompliantHandshakeResponseBytes`

### 9. `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/WebSocketFrameTest.java`
- **Purpose**: Complete unit test suite for binary frame parsing and serialization.
- **Coverage**: 20 comprehensive test methods:
  1. `shouldDecodeOfficialRfc6455MaskedHelloTextFrame` (RFC 6455 §5.7 test vector)
  2. `shouldEncodeOfficialRfc6455UnmaskedHelloTextFrame` (RFC 6455 §5.7 test vector)
  3. `shouldEncodeAndDecodeEmptyTextFrame`
  4. `shouldEncodeAndDecode7BitBoundaryTextFrame` (125 bytes)
  5. `shouldEncodeAndDecode16BitMinBoundaryPayload` (126 bytes)
  6. `shouldEncodeAndDecode16BitMediumPayloads` (500, 1000, 65000 bytes)
  7. `shouldEncodeAndDecode16BitMaxBoundaryPayload` (65535 bytes)
  8. `shouldEncodeAndDecode64BitLargePayload` (70000 bytes)
  9. `shouldEncodeAndDecodePingAndPongControlFrames`
  10. `shouldEncodeAndDecodeCloseFrameWithStatusCodeAndReason`
  11. `shouldEncodeAndDecodeEmptyCloseFrame`
  12. `shouldRejectClientUnmaskedFrameWithCode1002`
  13. `shouldRejectNonZeroRsvBitsWithCode1002`
  14. `shouldRejectNonMinimalLengthEncoding126WithCode1002`
  15. `shouldRejectNonMinimalLengthEncoding127WithCode1002`
  16. `shouldRejectControlFrameWithFinZeroWithCode1002`
  17. `shouldRejectControlFrameWithPayloadGreaterThan125WithCode1002`
  18. `shouldRejectCloseFrameWithPayloadLength1WithCode1002`
  19. `shouldRejectMalformedUtf8InTextPayloadWithCode1007`
  20. `shouldRejectMalformedUtf8InCloseReasonWithCode1007`
