# Handoff Report: RFC 6455 Specification Survey for Bedrock v2.0

**Author**: `spec_miner_survey_1`  
**Date**: 2026-09-11T03:59:00Z  
**Type**: Hard Handoff (Task Complete)

---

## 1. Observation

1. **Directives & Requirements**:
   - `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md` (lines 19-76) explicitly requires:
     - WebSocket RFC 6455 engine built from scratch with zero external dependencies using JDK 21 (`java.nio.channels.ServerSocketChannel`, `java.nio.channels.SocketChannel`, `java.nio.ByteBuffer`, `java.security.MessageDigest`).
     - Handshake calculation: SHA-1 of `Sec-WebSocket-Key` + `"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"` encoded with Base64.
     - Validation against RFC 6455 §1.3 test vector (`"dGhlIHNhbXBsZSBub25jZQ=="` -> `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`).
     - Binary frame parsing: FIN bit, RSV1-3 bits, Opcode (0x0 Continuation, 0x1 Text, 0x2 Binary, 0x8 Close, 0x9 Ping, 0xA Pong), MASK bit, 4-byte XOR unmasking.
     - Payload length encoding: 7-bit (0-125), 16-bit extended (126), 64-bit extended (127 with MSB=0).
     - Strict control frame rules (FIN=1, max length 125, no fragmentation, interleaving support).
     - Dedicated Virtual Thread per connection (`Thread.ofVirtual().name("ws-client-", ...)`).
     - Educational Javadoc in the `🎓 BEDROCK TUTORIAL` pattern.
     - Declarative API: `@BedrockSocket`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`, `BedrockWebSocketSession`.

2. **Existing Codebase Patterns**:
   - `bedrock-core/src/main/java/com/bedrock/core/BedrockApp.java` (lines 209-285) demonstrates explicit component registration and annotation scanning without magic classpath scanning.
   - `bedrock-core/src/main/java/com/bedrock/core/Context.java` illustrates Bedrock's didactic documentation style (`🎓 BEDROCK TUTORIAL`) detailing underlying mechanics, architectural patterns, and design decisions.
   - Zero external runtime dependencies in `bedrock-core/pom.xml`.

3. **Authoritative Specification (RFC 6455)**:
   - Handshake: RFC 6455 §4.1, §4.2, §4.4 (version 13 required, HTTP 426 on mismatch).
   - Framing: RFC 6455 §5.1, §5.2, §5.3, §5.4, §5.5 (frame layout, client masking invariant, minimal byte length encoding, XOR formula).
   - Status codes: RFC 6455 §7.4 (1000-1011, wire-forbidden codes 1005, 1006, 1015).
   - UTF-8 validation: RFC 6455 §8.1 (status code 1007 on malformed UTF-8 in text or close reason).

---

## 2. Logic Chain

1. **Handshake Verification**:
   - From RFC 6455 §4.2.2 and §1.3, the client sends an HTTP GET with `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Version: 13`, and a Base64 16-byte nonce `Sec-WebSocket-Key`.
   - The server computes `Sec-WebSocket-Accept` via:
     $$\text{Accept} = \text{Base64}(\text{SHA-1}(\text{Key} \,\|\, \text{"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}))$$
   - Applying this to `"dGhlIHNhbXBsZSBub25jZQ=="` yields SHA-1 hash `b37a4f2cc0624f1690f64606cf385945b2bec4ea` and Base64 output `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`.
   - The server returns `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n`. After this sequence, the socket is full-duplex binary framing.

2. **Frame Parsing and Mask Invariant**:
   - From RFC 6455 §5.1, client-to-server frames **MUST** be masked (`MASK = 1`). If unmasked, server MUST terminate with status code 1002.
   - Server-to-client frames **MUST NOT** be masked (`MASK = 0`).
   - The masking key consists of 4 bytes immediately following the length fields.
   - Unmasking is an involutory bitwise operation: $D_i = E_i \oplus M_{i \pmod 4}$.

3. **Length Encoding & Invariants**:
   - Payload length field encodes 0-125 directly (2-byte header unmasked, 6-byte header masked).
   - Value 126 signals that the next 2 bytes represent a 16-bit unsigned big-endian integer ($126 \le L \le 65535$).
   - Value 127 signals that the next 8 bytes represent a 64-bit unsigned big-endian integer ($65536 \le L < 2^{63}$). MSB must be 0.
   - RFC 6455 §5.2 requires minimal encoding: encoding a length $< 126$ using indicator 126 or $< 65536$ using indicator 127 is an immediate protocol violation (code 1002).

4. **Control Frames & Close Handshake**:
   - Opcodes 0x8 (Close), 0x9 (Ping), and 0xA (Pong) MUST have `FIN = 1` and payload length $\le 125$.
   - Ping must be answered with Pong with identical payload.
   - Close payload has 0 bytes or $\ge 2$ bytes (first 2 bytes = status code, rest = UTF-8 reason). A 1-byte close payload is invalid (code 1002).
   - Text frames (0x1) and close reason strings must strictly validate as UTF-8; invalid bytes require closing with code 1007.

5. **Concurrency & Architecture**:
   - Virtual Threads in JDK 21 (`Thread.ofVirtual()`) allow synchronous channel reads (`SocketChannel`) without reactor/event-loop complexity.
   - Each client loop executes in a dedicated virtual thread, reading frames sequentially, handling Pings/Closes inline, and dispatching text messages to `@OnMessage`.

---

## 3. Caveats

- **No Caveats on RFC 6455 Core**: The complete RFC 6455 standard for framing, handshake, control frames, status codes, and error semantics is thoroughly documented in `spec_survey.md`.
- **Extensions**: WebSocket extensions (such as `permessage-deflate`) are out of scope for Bedrock v2.0 baseline; RSV bits must be strictly validated as 0.
- **Subprotocols**: `Sec-WebSocket-Protocol` header negotiation is documented but optional for application endpoints.

---

## 4. Conclusion

The specification mining phase for Bedrock v2.0 WebSockets (RFC 6455) is complete. The resulting technical survey at `.agents/spec_miner_survey_1/spec_survey.md` contains:
1. Exact byte-level wire formats, diagrams, bit masks, and bit shifts.
2. The complete mathematical formulation for the HTTP 101 upgrade handshake, SHA-1 hashing, and Base64 encoding with RFC test vectors.
3. Strict invariants for client masking, minimal length encoding, control frame payload limits, and two-way close handshakes.
4. Comprehensive status code registry and UTF-8 validation rules.
5. Rich didactic material in the `🎓 BEDROCK TUTORIAL` style explaining the underlying physics (cache poisoning defense, cross-protocol security, Virtual Threads vs Event Loops).
6. Tables of all 19 discovered features and 20 edge cases.

---

## 5. Verification Method

To independently verify the specification mining results:
1. **Inspect Survey File**:
   View `c:\Users\roner\Documents\repo\bedrock-framework\.agents\spec_miner_survey_1\spec_survey.md`.
2. **Verify RFC 6455 Test Vectors**:
   Check Section 2.4 against RFC 6455 Section 1.3:
   - Key: `"dGhlIHNhbXBsZSBub25jZQ=="`
   - GUID: `"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"`
   - SHA-1: `b3 7a 4f 2c c0 62 4f 16 90 f6 46 06 cf 38 59 45 b2 be c4 ea`
   - Accept: `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`
3. **Verify Frame Bit Layout**:
   Check Section 3.1 against RFC 6455 Section 5.2.
4. **Verify Status Codes**:
   Check Section 4.1 against RFC 6455 Section 7.4.1.
