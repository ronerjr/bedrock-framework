# RFC 6455 Protocol Engine Adversarial Challenge Report

**Agent**: `challenger_m1_1`  
**Role**: Adversarial Challenger (critic, specialist)  
**Milestone**: Milestone 1: RFC 6455 Protocol Engine  
**Target Classes**: `WebSocketHandshake`, `WebSocketFrameParser`, `WebSocketFrameWriter`, `WebSocketFrame`, `WebSocketCloseStatus`, `WebSocketOpcode`, `WebSocketException`  
**Overall Risk Assessment**: **LOW** (Robust, RFC 6455 compliant)  
**Final Verdict**: **APPROVE**  

---

## 1. Challenge Summary

This report evaluates the robustness, security invariants, and RFC 6455 protocol conformance of Bedrock's Version 2.0 Protocol Engine (`com.bedrock.core.ws.protocol`). 

An empirical challenge harness comprising **22 new adversarial test methods** (`WebSocketAdversarialTest.java`) was created in `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/` to stress-test:
1. Non-standard and exotic HTTP whitespace, tab delimiters, and mixed line breaks.
2. Case-insensitivity across HTTP headers, tokens, and values.
3. Multi-token `Connection` headers with varying ordering and whitespace.
4. Protocol negotiation failures (version != 13 returning HTTP 426 with `Sec-WebSocket-Version: 13`).
5. Truncated, invalid-length, and malformed Base64 `Sec-WebSocket-Key` headers returning HTTP 400.
6. Non-GET HTTP methods returning HTTP 405.
7. Denial-of-Service header size limits (exceeding 16 KB throwing `WebSocketException(1002)`).
8. Client unmasked frames (`MASK = 0`) strictly rejected with status `1002 Protocol Error`.
9. In-place 4-byte XOR unmasking correctness across non-trivial keys (`0xDEADBEEF`, `0xFF00AA55`, etc.) and payload lengths from 1 to 65,535 bytes.
10. Canonical minimal length encodings (16-bit extended length < 126 and 64-bit extended length < 65,536 rejected with status `1002`).
11. Negative 64-bit lengths (MSB = 1) rejected with status `1002`.
12. Non-zero extension bits (`RSV1`, `RSV2`, `RSV3`) rejected with status `1002`.
13. Strict UTF-8 validation rejecting malformed continuation bytes, truncated sequences, overlong representations, and UTF-16 surrogates with status `1007 Invalid Frame Payload Data`.
14. Control frame invariants (fragmentation `FIN = 0` or payload length > 125 bytes rejected with status `1002`).
15. Close frame payload length 1 rejected with status `1002`.
16. Inbound wire-forbidden Close status codes (1005, 1006, 1015, 1004, 0-999, 1012-2999, >4999) rejected with status `1002`.
17. Outbound server frame writer invariants (unmasked `MASK = 0`, forbidden codes blocked with `IllegalArgumentException`, control frame size cap enforced).

---

## 2. Adversarial Challenges & Empirical Results

### Challenge 1: `WebSocketHandshake` Protocol Stress Testing

#### 1.1 Exotic Whitespace & Delimiters
- **Assumption Challenged**: Request parser assumes single ASCII space delimiters between request-line tokens and standard CRLF line breaks.
- **Attack Scenario**: Client transmits horizontal tabs (`\t`) and multiple irregular spaces between HTTP method, path, and version (e.g. `GET\t\t   /chat/room1   \t\tHTTP/1.1\r\n`), leading/trailing whitespace in header values (`Upgrade:   \twebsocket\r\n`), and LF-only Unix line breaks.
- **Result**: **PASS**. 
  - `WebSocketHandshake.isUpgradeRequest` and `parse` use `split("\\s+")` for request-line extraction, successfully normalizing arbitrary whitespace.
  - `parseHeadersToMap` trims both header names and values.
  - `split("\r?\n")` and `findHeaderEnd` support both `\r\n\r\n` and `\n\n`.

#### 1.2 Lowercase & Mixed-Case Headers
- **Assumption Challenged**: Header matching relies on exact camel-case HTTP header names (`Upgrade`, `Sec-WebSocket-Key`).
- **Attack Scenario**: Client sends all-lowercase headers (`host: ...`, `upgrade: websocket`, `connection: upgrade`, `sec-websocket-key: ...`, `sec-websocket-version: 13`) or all-uppercase headers.
- **Result**: **PASS**. 
  - `parseHeadersToMap` populates a `TreeMap<>(String.CASE_INSENSITIVE_ORDER)`.
  - Header lookup and token matching (`containsToken`) operate with case-insensitivity (`equalsIgnoreCase`), recognizing all case variations.

#### 1.3 Multi-Token `Connection` Headers
- **Assumption Challenged**: Parser expects `Connection: Upgrade` as a single token.
- **Attack Scenario**: Reverse proxies or browsers inject multi-token hop-by-hop headers such as `Connection: keep-alive, Upgrade`, `Connection: Upgrade, keep-alive`, or `Connection:  keep-alive  ,   Upgrade  `.
- **Result**: **PASS**.
  - `containsToken(headerValue, "Upgrade")` splits by `,` and trims each constituent token, successfully matching regardless of token position or surrounding whitespace.

#### 1.4 Invalid WebSocket Versions
- **Assumption Challenged**: Parser might blindly accept version numbers other than 13 or omit the required response header.
- **Attack Scenario**: Client sends `Sec-WebSocket-Version: 8` (HyBi-10), `7`, `0`, `14`, or non-numeric tokens.
- **Result**: **PASS**.
  - `isUpgradeRequest` returns `false`.
  - `parse` returns `valid = false, statusCode = 426` with `createResponse426()`, which correctly formats `HTTP/1.1 426 Upgrade Required` containing `Sec-WebSocket-Version: 13`, complying with RFC 6455 §4.4.

#### 1.5 Truncated & Malformed `Sec-WebSocket-Key`
- **Assumption Challenged**: Parser accepts any non-empty string or crashes with unhandled `IllegalArgumentException` on bad Base64.
- **Attack Scenario**: Client provides truncated Base64 strings (e.g. `dGhl` decoding to 3 bytes instead of 16), invalid length keys (15 bytes, 17 bytes, 32 bytes), or malformed Base64 nonces (`????====!!!!`).
- **Result**: **PASS**.
  - `parse` decodes via `Base64.getDecoder().decode(clientKey.trim())`.
  - Catches `IllegalArgumentException` for malformed Base64 and validates `decodedKey.length == 16`.
  - Violations return `valid = false, statusCode = 400` with descriptive error messages, preventing invalid handshake completion.

---

### Challenge 2: `WebSocketFrameParser` Binary Framing Stress Testing

#### 2.1 Client Frames with `MASK = 0`
- **Assumption Challenged**: Server accepts unmasked frames from clients.
- **Attack Scenario**: Client sends unmasked frames to probe for transparent proxy cache poisoning vulnerabilities.
- **Result**: **PASS**.
  - In server mode (`requireMask = true`), `(b1 & 0x80) == 0` immediately triggers `WebSocketException(1002 Protocol Error)`.

#### 2.2 In-Place 4-Byte XOR Unmasking Permutations
- **Assumption Challenged**: Bitwise XOR logic fails on non-trivial keys or specific array length boundaries.
- **Attack Scenario**: Test unmasking against non-trivial keys (`0xDEADBEEF`, `0xFF00AA55`, `0x80808080`) across varying payload lengths: odd sizes (1, 3, 5, 7, 15 bytes), 4-byte multiples (4, 8, 16 bytes), 7-bit boundary (125 bytes), 16-bit min boundary (126 bytes), power of 2 (256, 1024 bytes), and max 16-bit boundary (65,535 bytes).
- **Result**: **PASS**.
  - `payload[i] = (byte) (payload[i] ^ maskingKey[i & 3])` correctly uses `i & 3` for zero-allocation modular indexing.
  - Double unmasking $(P \oplus K) \oplus K = P$ confirmed byte-for-byte identity across all tested sizes and non-trivial keys.

#### 2.3 Non-Minimal Payload Length Encodings
- **Assumption Challenged**: Parser blindly decodes extended length headers without verifying canonical representation.
- **Attack Scenario**: 
  - Length 10 encoded using 16-bit extended indicator (126) as `0x000A`.
  - Lengths 0, 10, 125, 126, 500, 65535 encoded using 64-bit extended indicator (127).
- **Result**: **PASS**.
  - 16-bit extended length validates `extLen >= 126`; throws `WebSocketException(1002)` otherwise.
  - 64-bit extended length validates `extLen >= 65536`; throws `WebSocketException(1002)` otherwise.

#### 2.4 Negative 64-Bit Payload Length (MSB = 1)
- **Assumption Challenged**: Parser treats 64-bit extended length as unsigned or allows negative lengths.
- **Attack Scenario**: Client transmits 64-bit extended length with MSB=1 (e.g. `0xFFFFFFFFFFFFFFFF`).
- **Result**: **PASS**.
  - Line 180 checks `extLen < 0` and throws `WebSocketException(1002)` per RFC 6455 §5.2 ("the most significant bit MUST be 0").

#### 2.5 RSV1, RSV2, RSV3 Bits
- **Assumption Challenged**: Parser ignores extension reservation bits when no extensions are negotiated.
- **Attack Scenario**: Inbound frames have RSV1 (0x40), RSV2 (0x20), RSV3 (0x10), or combinations (0x70) set.
- **Result**: **PASS**.
  - `(b0 & 0x70) >>> 4 != 0` immediately throws `WebSocketException(1002)`.

#### 2.6 Strict UTF-8 Validation on Text & Close Reason Payloads
- **Assumption Challenged**: Parser uses lenient charset decoding (e.g. replacing malformed bytes with `\uFFFD`).
- **Attack Scenario**: Client transmits invalid UTF-8 sequences:
  - Unexpected continuation bytes (`0x80`, `0xBF`).
  - Invalid continuation byte (`0xC3 0x28`).
  - Overlong ASCII representations (`0xC0 0xAF`, `0xC1 0xBF`).
  - Overlong 3-byte sequence (`0xE0 0x80 0xAF`).
  - UTF-16 surrogates (`0xED 0xA0 0x80` to `0xED 0xBF 0xBF`).
  - Out of range codepoint (`0xF4 0x90 0x80 0x80`).
  - Truncated multi-byte sequence (`0xC2` without continuation).
- **Result**: **PASS**.
  - `CharsetDecoder` configured with `CodingErrorAction.REPORT` on malformed and unmappable characters.
  - Catches `CharacterCodingException` and throws `WebSocketException(1007 Invalid Frame Payload Data)`.

#### 2.7 Control Frame Invariants
- **Assumption Challenged**: Control frames can be fragmented or exceed 125 bytes.
- **Attack Scenario**: Client sends Ping, Pong, or Close frame with `FIN = 0` or payload length 126.
- **Result**: **PASS**.
  - `opcode.isControl() && !fin` immediately throws `WebSocketException(1002)`.
  - `opcode.isControl() && payloadLength > 125` immediately throws `WebSocketException(1002)`.

#### 2.8 Close Frame Payload Constraints
- **Assumption Challenged**: Close frame accepts 1-byte payload or wire-forbidden codes.
- **Attack Scenario**:
  - Close frame with payload length = 1 byte.
  - Close frame containing wire-forbidden status codes (1005, 1006, 1015).
  - Close frame containing undefined or reserved codes (1004, 0-999, 1012-2999, >4999).
- **Result**: **PASS**.
  - Payload length == 1 byte throws `WebSocketException(1002)`.
  - Status codes 1005, 1006, 1015 throw `WebSocketException(1002)`.
  - Undefined/reserved status codes throw `WebSocketException(1002)`.
  - Empty Close payload (0 bytes) is permitted and resolves to internal status code 1005 without throwing.

---

## 3. Findings & Design Notes

### Design Note 1: Two-Tier Handshake Inspection Architecture
- `WebSocketHandshake.isUpgradeRequest(String httpHeaders)`: Performs fast-path protocol identification (checks method GET, HTTP/1.1+, Upgrade, Connection, Version 13, and presence of Sec-WebSocket-Key).
- `WebSocketHandshake.parse(String httpHeaders)`: Performs strict RFC 6455 validation (validates 16-byte Base64 key decode, emits 400 Bad Request if truncated/malformed, emits 426 Upgrade Required if version != 13, and computes 101 response).
- **Assessment**: Sound architectural separation of concerns. Server dispatchers use `isUpgradeRequest` to identify upgrade requests, while `parse` handles the handshake transaction and error responses.

### Design Note 2: Streaming Buffer Safety in Frame Parser
- `WebSocketFrameParser.parse(ByteBuffer buffer)` utilizes `buffer.mark()` and non-destructive `buffer.reset()`, returning `null` when a frame header or payload has not fully arrived.
- `extLen > MAX_ALLOWED_PAYLOAD_SIZE` (16 MB) check prevents memory exhaustion denial-of-service from malicious 64-bit frame headers.

---

## 4. Empirical Test Suite Summary

| Test Category | Suite File | Methods Added | Status |
|---------------|------------|---------------|--------|
| Handshake Adversarial | `WebSocketAdversarialTest.java` | 9 test methods | **PASS** |
| Frame Parser Adversarial | `WebSocketAdversarialTest.java` | 13 test methods | **PASS** |
| Total New Adversarial Tests | `WebSocketAdversarialTest.java` | **22 test methods** | **PASS** |
| Existing Protocol Tests | `WebSocketHandshakeTest.java` + `WebSocketFrameTest.java` | **32 test methods** | **PASS** |
| **Combined Protocol Verification Suite** | | **54 test methods** | **100% PASS** |

---

## 5. Conclusion & Recommendation

The Milestone 1 RFC 6455 Protocol Engine demonstrates exceptional protocol fidelity, defensive byte-level engineering, and strict compliance with RFC 6455 requirements and security invariants.

**Explicit Verdict**: **APPROVE**
