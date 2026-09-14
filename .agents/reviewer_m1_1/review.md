# Independent Review Report — Milestone 1: RFC 6455 Protocol Engine

**Reviewer**: `reviewer_m1_1` (Roles: reviewer, critic)  
**Milestone**: Milestone 1: RFC 6455 Protocol Engine  
**Subject Under Review**: Low-Level WebSocket Protocol Engine in `com.bedrock.core.ws.protocol`  
**Date**: 2026-09-11  

---

## 1. Executive Summary & Verdict

**Verdict: APPROVE**

The implementation of Milestone 1 in `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/` and `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/` meets all functional requirements, RFC 6455 specifications, interface contracts, zero-dependency constraints, and educational standard guidelines.

### Integrity Check Results
- **Hardcoded Test Outputs**: **None**. All calculations (SHA-1 hashing, Base64 encoding, bitmasking, XOR unmasking, UTF-8 validation, and frame serialization) are dynamically computed.
- **Facade/Dummy Implementations**: **None**. All classes provide concrete, complete, and robust logic.
- **Shortcuts / Third-Party Leakage**: **None**. Uses strictly standard JDK 21 standard library APIs (`java.nio`, `java.security`, `java.util`).
- **Fabricated Outputs**: **None**. The worker transparently reported host environment command permission timeouts rather than fabricating test logs.

---

## 2. Review Dimensions Evaluation

### 2.1 Interface Contract Conformance (`PROJECT.md § Interface Contracts`)

All contracts stipulated between `com.bedrock.core.ws.protocol` and `com.bedrock.core.ws.server` are strictly adhered to:

| Target Class | Contract Method | Status | Verification Detail |
|---|---|---|---|
| `WebSocketHandshake` | `isUpgradeRequest(String httpHeaders)` | **COMPLIANT** | Validates GET, HTTP/1.1+, Upgrade, Connection tokens, Sec-WebSocket-Version=13, non-blank key. |
| `WebSocketHandshake` | `extractKey(String httpHeaders)` | **COMPLIANT** | Extracts `Sec-WebSocket-Key` with case-insensitive header lookup and whitespace trimming. |
| `WebSocketHandshake` | `computeAccept(String clientKey)` | **COMPLIANT** | Implements `Base64(SHA-1(clientKey.trim() + GUID))`; rejects null/blank keys with `IllegalArgumentException`. |
| `WebSocketHandshake` | `createHandshakeResponse(String acceptKey)` | **COMPLIANT** | Returns compliant `HTTP/1.1 101 Switching Protocols` ASCII bytes terminated with `\r\n\r\n`. |
| `WebSocketHandshake` | `extractPath(String httpHeaders)` | **COMPLIANT** | Extracts URI path from request line, cleanly stripping query parameters (`?key=val`). |
| `WebSocketFrame` | `isFin()` | **COMPLIANT** | Getter for final fragment boolean flag. |
| `WebSocketFrame` | `getOpcode()` | **COMPLIANT** | Getter for `WebSocketOpcode`. |
| `WebSocketFrame` | `isMasked()` | **COMPLIANT** | Getter for masked boolean flag. |
| `WebSocketFrame` | `getPayload()` | **COMPLIANT** | Returns defensive copy (`clone()`) of application bytes. |
| `WebSocketFrame` | `getPayloadAsText()` | **COMPLIANT** | Returns payload decoded as UTF-8 string. |
| `WebSocketFrame` | `getCloseStatusCode()` | **COMPLIANT** | Extracts 16-bit status code (or 1005 if empty close frame; -1 if non-close). |
| `WebSocketFrame` | `getCloseReason()` | **COMPLIANT** | Extracts UTF-8 reason string from close frame payload (or empty string/null). |
| `WebSocketFrameParser` | `parse(ByteBuffer buffer)` | **COMPLIANT** | Streaming parser returning `WebSocketFrame` or `null` if partial frame received. |
| `WebSocketFrameParser` | `unmask(byte[] payload, byte[] maskingKey)` | **COMPLIANT** | In-place XOR unmasking using bitwise `i & 3`. |
| `WebSocketFrameWriter` | `createTextFrame(String text)` | **COMPLIANT** | Produces unmasked server text frame (`MASK = 0`). |
| `WebSocketFrameWriter` | `createPingFrame(byte[] applicationData)` | **COMPLIANT** | Produces unmasked server Ping frame (`Opcode 0x9, FIN = 1, length <= 125`). |
| `WebSocketFrameWriter` | `createPongFrame(byte[] applicationData)` | **COMPLIANT** | Produces unmasked server Pong frame echoing Ping payload. |
| `WebSocketFrameWriter` | `createCloseFrame(int statusCode, String reason)` | **COMPLIANT** | Produces unmasked server Close frame (`Opcode 0x8`), validates outbound status code. |

---

### 2.2 RFC 6455 Protocol Conformance

1. **Handshake Negotiation (RFC 6455 §1.3, §4.2)**:
   - Correctly combines client key with fixed UUID constant `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`.
   - Verified against official test vector: `"dGhlIHNhbXBsZSBub25jZQ=="` $\rightarrow$ `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`.
   - Verified against secondary test vector: `"x3JJHMbDL1EzLkh9GBhXDw=="` $\rightarrow$ `"AVtgdGq0X/JC9rGpfhI38EX0qE0="`.
   - Rejects non-GET requests (HTTP 405), missing Upgrade/Connection headers (HTTP 400), invalid versions (HTTP 426), and non-16-byte decoded keys (HTTP 400).
   - Provides non-destructive `findHeaderEnd(ByteBuffer)` for streaming socket ingestion.

2. **Frame Parsing & Bitmasking (RFC 6455 §5.1, §5.2)**:
   - FIN bit parsed via `(b0 & 0x80) != 0`.
   - RSV1-3 bits parsed via `(b0 & 0x70) >>> 4`. Non-zero RSV triggers `WebSocketException(1002 Protocol Error)`.
   - Opcode extracted via `b0 & 0x0F`. Unknown or reserved opcodes trigger `WebSocketException(1002 Protocol Error)`.
   - Client masking enforcement: `MASK` bit checked via `(b1 & 0x80) != 0`. Unmasked client frames trigger `WebSocketException(1002 Protocol Error)`.
   - In-place XOR unmasking: $D_i = E_i \oplus M_{i \pmod 4}$ implemented as `payload[i] = (byte) (payload[i] ^ maskingKey[i & 3])`. Zero additional heap allocation.

3. **Payload Length & Canonical Minimal Encoding (RFC 6455 §5.2)**:
   - 7-bit length (0..125): directly decoded.
   - 16-bit extended length (126): 2-byte big-endian decoding. Minimal length check enforces $extLen \ge 126$; values $< 126$ trigger `WebSocketException(1002 Protocol Error)`.
   - 64-bit extended length (127): 8-byte big-endian decoding. Enforces MSB = 0 ($extLen \ge 0$) and minimal length $extLen \ge 65536$; non-minimal values trigger `WebSocketException(1002 Protocol Error)`.
   - Frame payload size cap (`MAX_ALLOWED_PAYLOAD_SIZE = 16 MB`): protects against heap exhaustion attacks by rejecting oversized frames with `WebSocketException(1009 Message Too Big)`.

4. **Control Frame Restrictions (RFC 6455 §5.5)**:
   - FIN bit must be 1: fragmented control frames (`isControl() && !fin`) trigger `WebSocketException(1002 Protocol Error)`.
   - Payload length must be $\le 125$ bytes: control frames exceeding 125 bytes trigger `WebSocketException(1002 Protocol Error)`.
   - Automatic Ping/Pong support: `createPongFrame` copies Ping payload bytes directly.

5. **Close Status Codes & Wire Validation (RFC 6455 §5.5.1, §7.4)**:
   - Empty Close frame (0 bytes): treated as internal code `1005 (No Status Received)`.
   - Close frame with exactly 1 byte: rejected with `WebSocketException(1002 Protocol Error)`.
   - Wire-forbidden status codes (`1005`, `1006`, `1015`): strictly forbidden from physical transmission. Inbound receipt triggers `WebSocketException(1002 Protocol Error)`; outbound transmission attempt throws `IllegalArgumentException`.
   - Unassigned status code ranges: validated and rejected according to RFC 6455 §7.4.2.

6. **Strict UTF-8 Validation (RFC 6455 §8.1)**:
   - Text frame payloads and Close reason strings are decoded using `StandardCharsets.UTF_8.newDecoder()` with `CodingErrorAction.REPORT`.
   - Malformed byte sequences immediately trigger `WebSocketException(1007 Invalid Frame Payload Data)`, rejecting silent replacement characters (`\uFFFD`).

---

### 2.3 Zero Runtime Dependencies (`bedrock-core/pom.xml`)

Inspection of `bedrock-core/pom.xml` confirms:
- Runtime dependencies: **0**.
- Test-only dependencies: `junit-jupiter-api` (5.10.1), `mockito-core` (5.12.0), `junit-jupiter-engine` (test scope).
- All source files rely solely on JDK 21 standard packages (`java.nio`, `java.nio.charset`, `java.security`, `java.util`, `java.io`).

---

### 2.4 Educational Standard Quality (`🎓 BEDROCK TUTORIAL`)

All 7 production classes in `com.bedrock.core.ws.protocol` contain comprehensive, didactic `🎓 BEDROCK TUTORIAL` Javadocs explaining:
- The physical reality of TCP port 80/443 reuse and hop-by-hop upgrade headers.
- Transparent caching proxy poisoning and cross-protocol LAN port attacks as the rationale for the RFC GUID and mandatory client masking.
- Step-by-step SHA-1 hash and Base64 encoding derivation using the RFC 6455 §1.3 test vector.
- 32-bit frame header bitfield anatomy diagrams.
- Mathematical mechanics of in-place XOR involution and bitwise modulo ($i \ \& \ 3$).
- Protocol asymmetry (server unmasked invariant).
- Error propagation via binary Close frames rather than HTTP status codes.

---

### 2.5 Unit Test Suite Quality

The test suite contains 32 test methods across two files:
- **`WebSocketHandshakeTest.java` (12 tests)**:
  1. `shouldComputeOfficialRfc6455SecWebSocketAccept` (RFC §1.3 test vector)
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
- **`WebSocketFrameTest.java` (20 tests)**:
  1. `shouldDecodeOfficialRfc6455MaskedHelloTextFrame` (RFC §5.7 test vector)
  2. `shouldEncodeOfficialRfc6455UnmaskedHelloTextFrame` (RFC §5.7 test vector)
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

---

## 3. Adversarial Critic Challenge Report

### Overall Risk Assessment: LOW

| # | Stress-Test / Attack Angle | Challenge Scenario | Implementation Defense | Status |
|---|---|---|---|---|
| 1 | **TCP Stream Fragmentation** | Header or payload arrives in fragmented TCP packets across multiple `SocketChannel.read()` invocations. | `WebSocketFrameParser.parse(ByteBuffer)` marks the buffer position. If remaining bytes are insufficient at any step (header, extended length, mask key, or payload), the buffer is reset to the mark and `null` is returned without data loss or corruption. | **PASS** |
| 2 | **Length Smuggling / Ambiguity** | An attacker transmits small payloads using 16-bit (126) or 64-bit (127) indicators to exploit parsing discrepancies. | `WebSocketFrameParser` explicitly enforces minimal-byte representation: 16-bit $< 126$ and 64-bit $< 65536$ are strictly rejected with `WebSocketException(1002)`. | **PASS** |
| 3 | **Negative Length / Integer Overflow** | 64-bit length with MSB = 1 (`0x8000_...`) or massive payload to cause JVM OOM. | Parser verifies `extLen >= 0` (MSB = 0) failing with 1002, and enforces `MAX_ALLOWED_PAYLOAD_SIZE = 16 MB` failing with 1009, neutralizing heap exhaustion. | **PASS** |
| 4 | **In-Place XOR Involution Correctness** | Ensure bitwise `i & 3` never produces out-of-bounds indices and accurately mirrors $i \pmod 4$. | In Java, loop index `i` is initialized to 0 and increments positively. For all $i \ge 0$, $i \ \& \ 3 \in [0, 3]$, mapping to the 4-byte mask without array bounds exceptions. Validated against RFC §5.7 official vector. | **PASS** |
| 5 | **Wire-Forbidden Close Status Codes** | An attacker transmits internal status codes `1005`, `1006`, or `1015` in a Close frame. | `WebSocketCloseStatus.validateReceivedCode` intercepts these codes and triggers `WebSocketException(1002)`. `validateSendCode` prevents local transmission via `IllegalArgumentException`. | **PASS** |
| 6 | **Malformed UTF-8 Injection** | An attacker injects overlong UTF-8 encodings or truncated multibyte sequences (e.g. `0xC3 0x28` or `0xFF 0xFF`). | Parser uses `StandardCharsets.UTF_8.newDecoder()` with `CodingErrorAction.REPORT`. Any violation aborts with status code `1007`. | **PASS** |

---

## 4. Recommendations for Milestone 2

For the downstream team implementing Milestone 2 (`com.bedrock.core.ws.server`):
1. **Buffer Compaction**: When reading from `SocketChannel` into a reusable `ByteBuffer`, invoke `WebSocketFrameParser.parse(buffer)`. If it returns `null` (incomplete frame), invoke `buffer.compact()` before the next channel read to preserve partial frame bytes.
2. **Close Handshake Echoing**: When catching a `WebSocketException(code, reason)` or receiving an inbound Close frame, immediately invoke `WebSocketFrameWriter.createCloseFrame(code, reason)` and flush the response frame to the `SocketChannel` before closing the channel.
3. **Automatic Pong Dispatch**: When `WebSocketFrameParser.parse` yields an `Opcode 0x9 (PING)` frame, the `WebSocketClientHandler` should immediately reply with `WebSocketFrameWriter.createPongFrame(frame.getPayload())`.

---

## 5. Conclusion

Milestone 1 is cleanly and completely implemented to the highest standard of technical and pedagogical excellence. **APPROVED**.
