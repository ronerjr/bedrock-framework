# Milestone 1 Code Review & Adversarial Challenge Report

**Reviewer**: `reviewer_m1_2` (Independent Reviewer & Adversarial Critic)  
**Milestone**: Milestone 1: RFC 6455 Protocol Engine  
**Working Directory**: `.agents/reviewer_m1_2`  
**Date**: 2026-09-11  

---

## 1. Executive Summary

**Verdict**: **APPROVE**  
**Integrity Assessment**: **NO INTEGRITY VIOLATIONS DETECTED**  
- No hardcoded test outputs or dummy facades.
- Pure JDK 21 implementation without third-party runtime dependencies.
- Streaming non-destructive `ByteBuffer` parsing (`mark()` / `reset()`).
- Strict RFC 6455 compliance on wire-forbidden codes and minimal length encoding.
- Rich didactic Javadoc explanations following the `🎓 BEDROCK TUTORIAL` standard.

---

## 2. Detailed Quality & Correctness Review

### 2.1 Code Robustness & Boundary Conditions
- **`WebSocketHandshake.java`**:
  - Request line parsing: Safely splits tokens; rejects missing or non-GET methods with HTTP 405; rejects HTTP versions prior to 1.1 with HTTP 400.
  - Header inspection: Case-insensitive map lookup via `TreeMap<>(String.CASE_INSENSITIVE_ORDER)`; handles comma-separated multi-token values (`containsToken`) for `Connection: keep-alive, Upgrade` and `Upgrade: websocket`.
  - Non-destructive header detection (`findHeaderEnd`): Employs absolute index lookups (`buffer.get(i)`) without mutating buffer position; returns -1 on partial headers. `parse(ByteBuffer)` advances position only when a full header ending in `\r\n\r\n` or `\n\n` is detected.
  - Key validation: Base64 decode check enforces exactly 16 bytes per RFC 6455 §4.2.1 item 5; malformed keys or non-16-byte keys are rejected with HTTP 400.
  - SHA-1 & Base64: Strictly implements RFC 6455 §1.3 calculation: `Base64(SHA-1(key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`. Validated against the official RFC 6455 test vector (`dGhlIHNhbXBsZSBub25jZQ==` $\to$ `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`).

- **`WebSocketFrameParser.java`**:
  - Buffer underflow prevention: Checks `buffer.remaining() < 2` before touching position; executes `buffer.mark()` immediately before reading frame octets.
  - Incomplete frame recovery: If 16-bit extended length bytes (`remaining < 2`), 64-bit extended length bytes (`remaining < 8`), or payload bytes (`remaining < requiredBytes`) are incomplete, executes `buffer.reset()` and returns `null`, restoring buffer position for future socket reads.
  - Mandatory client masking: Rejects unmasked client frames with RFC 6455 `1002 Protocol Error`.
  - RSV bit enforcement: RSV1-3 bits must be 0; non-zero throws `WebSocketException(1002)`.
  - Control frame constraints: Fragmented control frames (FIN=0) or control frames with payload $> 125$ bytes are rejected with `1002 Protocol Error`.
  - Close frame payload validation: 1-byte Close payloads rejected with `1002 Protocol Error`; 0-byte payload assigned internal status `1005 (No Status Received)`; $\ge 2$ byte payloads validated against wire-forbidden codes and legal RFC 6455 status ranges.

- **`WebSocketFrameWriter.java`**:
  - Server unmasked invariant: All server frames have `MASK = 0` (Bit 7 of Byte 1 is 0), eliminating masking keys and unnecessary CPU overhead on server nodes.
  - Minimal length encoding: Automatically chooses 2-byte header ($\le 125$ bytes), 4-byte header ($126 \le \text{len} \le 65535$), or 10-byte header ($\ge 65536$).
  - Control frame limits: Validates that Ping, Pong, and Close payloads do not exceed 125 bytes, throwing `IllegalArgumentException` on violation.

---

### 2.2 Wire-Forbidden Close Status Codes (RFC 6455 §7.4.1)
- **Codes 1005 (No Status Received), 1006 (Abnormal Closure), and 1015 (TLS Handshake Failure)**:
  - `WebSocketCloseStatus.isWireForbidden(int code)` correctly identifies codes `1005`, `1006`, and `1015`.
  - Outbound validation (`validateSendCode`): Throws `IllegalArgumentException` if application attempts to send wire-forbidden codes.
  - Inbound validation (`validateReceivedCode`): Throws `WebSocketException(PROTOCOL_ERROR_CODE)` (status 1002) if wire-forbidden codes appear on the wire.
  - Empty Close frames: Accurately handled by assigning internal code `1005` without parsing non-existent status bytes.

---

### 2.3 Minimal Length Encoding Rules (RFC 6455 §5.2)
- RFC 6455 §5.2 mandates that the shortest possible encoding must be used for frame payload length:
  - If length indicator is `126` (16-bit extended length), `extLen` must be $\ge 126$. `WebSocketFrameParser` explicitly checks `if (extLen < 126)` and throws `WebSocketException(1002)`.
  - If length indicator is `127` (64-bit extended length), `extLen` must be $\ge 65536$. `WebSocketFrameParser` explicitly checks `if (extLen < 65536)` and throws `WebSocketException(1002)`.
  - In addition, the MSB of 64-bit length must be 0. `WebSocketFrameParser` checks `if (extLen < 0)` and throws `WebSocketException(1002)`.
- Unit tests `shouldRejectNonMinimalLengthEncoding126WithCode1002` and `shouldRejectNonMinimalLengthEncoding127WithCode1002` directly verify this behavior.

---

### 2.4 Dependency & Layout Conformance
- **`bedrock-core/pom.xml`**:
  - Runtime/compile dependencies: **ZERO** (0).
  - Test dependencies: JUnit Jupiter API 5.10.1, Mockito Core 5.12.0, JUnit Jupiter Engine.
  - Strict compliance with `PROJECT.md` and `ORIGINAL_REQUEST.md`.
- **Package Layout**:
  - All protocol classes are situated in `com.bedrock.core.ws.protocol`.
  - No source code or tests exist in `.agents/`.

---

### 2.5 Educational Standard (`🎓 BEDROCK TUTORIAL`)
All 7 production classes feature exhaustive didactic Javadocs:
1. `WebSocketOpcode.java`: Frame header Byte 0 bit layout, data vs control opcode classification, control bit invariant (`(code & 0x8) != 0`).
2. `WebSocketCloseStatus.java`: Two-way close handshake, close payload layout, reasons why 1005/1006/1015 are wire-forbidden, RFC status code allocation ranges.
3. `WebSocketException.java`: Disappearance of HTTP status codes post-upgrade, error propagation via binary Close frames.
4. `WebSocketFrame.java`: Protocol frame immutability, thread-safety, defensive copying of payload arrays.
5. `WebSocketHandshake.java`: HTTP 101 Switching Protocols mechanics, caching proxy poisoning defense, cross-protocol LAN attack defenses with RFC GUID, SHA-1 and Base64 step-by-step digest.
6. `WebSocketFrameParser.java`: 32-bit frame layout diagram, bitwise extraction, transparent cache poisoning defense via masking, $O(1)$ allocation in-place XOR unmasking.
7. `WebSocketFrameWriter.java`: Protocol asymmetry, server unmasked framing invariant, minimal length encoding rules.

---

## 3. Adversarial Challenge & Stress-Testing

### Challenge 1: Incremental Network Framing (TCP Fragmentation)
- **Attack / Stress Scenario**: TCP does not preserve message boundaries; frames may arrive across multiple read operations (e.g. 1 byte of header, 2 bytes of extended length, partial payload).
- **Behavior**: `WebSocketFrameParser.parse(ByteBuffer)` executes `buffer.mark()` at entry. If `buffer.remaining()` is insufficient at any stage (header, extended length, or payload), it invokes `buffer.reset()` and returns `null`.
- **Result**: **PASS**. The buffer position remains untouched, allowing subsequent `SocketChannel.read()` calls to accumulate incoming data without state corruption.

### Challenge 2: Integer & Heap Overflow via 64-Bit Extended Lengths
- **Attack / Stress Scenario**: A malicious client transmits a 64-bit frame header specifying a payload of $2^{63}-1$ bytes or negative length (MSB=1).
- **Behavior**:
  - Negative values (`extLen < 0`) are caught and rejected with `1002 Protocol Error`.
  - Non-minimal values (`extLen < 65536`) are rejected with `1002 Protocol Error`.
  - Massive lengths are bounded by `MAX_ALLOWED_PAYLOAD_SIZE` (16 MB). Any length exceeding 16 MB immediately triggers `WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE` (1009), preventing JVM heap exhaustion.
- **Result**: **PASS**.

### Challenge 3: In-Place XOR Unmasking Correctness & Modulo Algebra
- **Stress Scenario**: Verify in-place unmasking: $D_i = E_i \oplus M_{i \pmod 4}$.
- **Behavior**: Implemented as `payload[i] = (byte) (payload[i] ^ maskingKey[i & 3])`. For all non-negative integers $i$, `i & 3` is strictly equal to $i \pmod 4$ because $3 = 2^2 - 1 = 0b11$. No array index out-of-bounds is possible since `maskingKey.length == 4`.
- **Result**: **PASS**.

### Challenge 4: Malformed UTF-8 Silent Conversion Bypass
- **Attack / Stress Scenario**: A client sends invalid UTF-8 byte sequences (e.g. lone surrogates, overlong encodings, invalid continuation bytes). A naive `new String(bytes, StandardCharsets.UTF_8)` would silently replace them with `\uFFFD`, bypassing security validation.
- **Behavior**: `WebSocketFrameParser.validateUtf8()` configures `CharsetDecoder` with `onMalformedInput(CodingErrorAction.REPORT)` and `onUnmappableCharacter(CodingErrorAction.REPORT)`. Malformed bytes immediately throw `WebSocketException(1007 Invalid Frame Payload Data)`.
- **Result**: **PASS**.

### Challenge 5: HTTP Header Smuggling / Request Splitting in Handshake
- **Attack / Stress Scenario**: Malicious input in `Sec-WebSocket-Key` containing CRLF sequences attempting HTTP response splitting.
- **Behavior**:
  - `computeAccept(String clientKey)` computes SHA-1 digest and encodes via `Base64`. Base64 output strictly contains `[A-Za-z0-9+/=]`, which cannot contain `\r`, `\n`, or whitespace.
  - In addition, `WebSocketHandshake.parse()` verifies that the client key Base64-decodes to exactly 16 bytes before acceptance.
- **Result**: **PASS**.

---

## 4. Verified Claims & Test Matrix

| Claim / Specification | Verification Method | Status |
|---|---|---|
| RFC 6455 §1.3 SHA-1/Base64 Test Vector | `WebSocketHandshakeTest.shouldComputeOfficialRfc6455SecWebSocketAccept` | VERIFIED |
| RFC 6455 §5.7 Masked "Hello" Vector | `WebSocketFrameTest.shouldDecodeOfficialRfc6455MaskedHelloTextFrame` | VERIFIED |
| RFC 6455 §5.7 Unmasked "Hello" Vector | `WebSocketFrameTest.shouldEncodeOfficialRfc6455UnmaskedHelloTextFrame` | VERIFIED |
| Wire-forbidden close codes (1005, 1006, 1015) rejected with 1002 | `WebSocketCloseStatus.validateReceivedCode` & `WebSocketFrameParser` | VERIFIED |
| Non-minimal length 126 rejected with 1002 | `WebSocketFrameTest.shouldRejectNonMinimalLengthEncoding126WithCode1002` | VERIFIED |
| Non-minimal length 127 rejected with 1002 | `WebSocketFrameTest.shouldRejectNonMinimalLengthEncoding127WithCode1002` | VERIFIED |
| Unmasked client frame rejected with 1002 | `WebSocketFrameTest.shouldRejectClientUnmaskedFrameWithCode1002` | VERIFIED |
| Non-zero RSV bits rejected with 1002 | `WebSocketFrameTest.shouldRejectNonZeroRsvBitsWithCode1002` | VERIFIED |
| Control frame FIN=0 or len > 125 rejected with 1002 | `WebSocketFrameTest.shouldRejectControlFrame*` | VERIFIED |
| 1-byte Close payload rejected with 1002 | `WebSocketFrameTest.shouldRejectCloseFrameWithPayloadLength1WithCode1002` | VERIFIED |
| Malformed UTF-8 rejected with 1007 | `WebSocketFrameTest.shouldRejectMalformedUtf8*` | VERIFIED |
| Non-destructive ByteBuffer inspection | Source code trace of `mark()`/`reset()` and `findHeaderEnd` | VERIFIED |
| Zero third-party runtime dependencies | `bedrock-core/pom.xml` inspection | VERIFIED |
| 🎓 BEDROCK TUTORIAL Javadocs | Headers of all 7 protocol production classes | VERIFIED |

---

## 5. Constructive Notes for Downstream Milestones (Milestones 2-5)
1. **ByteOrder Invariant**: `WebSocketFrameParser` reads 64-bit length using `buffer.getLong()`, which relies on `ByteBuffer` byte order (default `BIG_ENDIAN`). In Milestone 2 (`WebSocketClientHandler`), ensure that socket read buffers maintain `ByteOrder.BIG_ENDIAN`.
2. **Buffer Compaction**: When reading streaming TCP data into a persistent `ByteBuffer`, after parsing complete frames, the handler should call `buffer.compact()` to make room for subsequent incoming network packets.

---

## 6. Verdict

**FINAL VERDICT**: **APPROVE**  
Milestone 1 satisfies all functional, architectural, RFC 6455 protocol, and pedagogical requirements. The engine is ready for Milestone 2 integration.
