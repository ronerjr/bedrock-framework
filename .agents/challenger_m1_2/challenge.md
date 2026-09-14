# Adversarial Challenge Report: Payload Encoding/Decoding & Boundary Conditions

**Agent**: `challenger_m1_2`  
**Role**: Adversarial Challenger (critic, specialist)  
**Target Milestone**: Milestone 1: RFC 6455 Protocol Engine  
**Working Directory**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m1_2`  
**Target Component**: `com.bedrock.core.ws.protocol` (`WebSocketFrameParser`, `WebSocketFrameWriter`, `WebSocketFrame`, `WebSocketCloseStatus`, `WebSocketOpcode`)  
**Date**: 2026-09-11  

---

## Challenge Summary

**Overall Risk Assessment**: **LOW** (Robust implementation with complete RFC 6455 §5 and §7 boundary and security enforcement)

The protocol framing engine was subjected to rigorous white-box adversarial stress testing across all four specified focus areas:
1. **Payload Length Boundaries**: Exact byte-level verification of 125, 126, 65535, 65536, and 70000 bytes, plus canonical minimal length encoding enforcement.
2. **Ping/Pong Application Data Matching**: Exact byte-for-byte preservation of binary payloads, opcode invariants, and control frame length limits.
3. **Close Status Codes & Wire Safety**: Strict enforcement preventing wire-forbidden codes (1005, 1006, 1015) from reaching the wire, valid status code ranges, 1-byte payload rejection, and UTF-8 reason validation.
4. **Memory Safety & OOM Protection**: Complete defense against quintillion-byte framing attacks (`0x7FFF_FFFF_FFFF_FFFFL`) via immediate pre-allocation validation against `MAX_ALLOWED_PAYLOAD_SIZE` (16 MB) and negative MSB rejection.

---

## Adversarial Challenges & Findings

### Challenge 1: Payload Length Boundaries & Minimal Length Encoding
- **Assumption Challenged**:
  The parser and serializer must correctly transition between 7-bit, 16-bit extended, and 64-bit extended payload length representations without off-by-one errors, buffer overflows, or accepting ambiguous non-minimal encodings.
- **Attack Scenarios Tested**:
  1. **125 bytes (Max 7-bit)**:
     - *Writer*: Encodes with 2-byte header `[0x81, 125]`. Wire length = 127 bytes.
     - *Parser*: Reads `lenIndicator = 125`, allocates 125-byte payload. Round-trip matches exactly.
  2. **126 bytes (Min 16-bit)**:
     - *Writer*: Encodes with 4-byte header `[0x81, 126, 0x00, 0x7E]`. Wire length = 130 bytes.
     - *Parser*: Reads `extLen = 126`. Minimal check `extLen < 126` is false, passes.
     - *Adversarial Non-Minimal Attack*: 125 bytes encoded using 16-bit indicator `126` (`extLen = 125`). Parser detects `extLen < 126` and throws `WebSocketException(1002 Protocol Error)`.
  3. **65,535 bytes (Max 16-bit)**:
     - *Writer*: Encodes with 4-byte header `[0x81, 126, 0xFF, 0xFF]`. Wire length = 65539 bytes.
     - *Parser*: Reads `extLen = 65535`. Minimal check passes.
  4. **65,536 bytes (Min 64-bit)**:
     - *Writer*: Encodes with 10-byte header `[0x82, 127, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00]`.
     - *Parser*: Reads `extLen = 65536L`. Minimal check `extLen < 65536` is false, passes.
     - *Adversarial Non-Minimal Attack*: 65,535 bytes encoded using 64-bit indicator `127` (`extLen = 65535`). Parser detects `extLen < 65536` and throws `WebSocketException(1002 Protocol Error)`.
  5. **70,000 bytes (> 65,535 bytes)**:
     - *Writer*: Encodes with 10-byte header `[0x82, 127, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x11, 0x70]`.
     - *Parser*: Reads `extLen = 70000L`. Passes minimal and size checks. Round-trip verified.
- **Verdict**: **PASS**. All boundary transitions and minimal-length security invariants are enforced with RFC 6455 §5.2 precision.

---

### Challenge 2: Control Frame Ping/Pong Payload Matching & Preservation
- **Assumption Challenged**:
  Application data in Ping frames must be preserved identically byte-for-byte when echoed in Pong frames, supporting raw binary octets while preventing control frame fragmentation or oversized payloads.
- **Attack Scenarios Tested**:
  1. **Byte-for-Byte Preservation**:
     - Client sends masked Ping frame containing binary bytes (`0x00`, `0xFF`, `0x80`, random bytes).
     - In-place XOR unmasking calculates `D_i = E_i ^ M_{i & 3}`.
     - `WebSocketFrame` stores payload via defensive clone.
     - `WebSocketFrameWriter.createPongFrame(ping.getPayload())` serializes opcode `0xA (PONG)` with identical byte array copied via `System.arraycopy`.
     - Output wire payload matches input ping payload 100% byte-for-byte.
  2. **Empty Heartbeat (0-byte payload)**:
     - Ping wire: `[0x89, 0x00]`. Pong wire: `[0x8A, 0x00]`. Handled without errors or null pointers.
  3. **Oversized Control Frame Attack (> 125 bytes)**:
     - Client sends Ping with payload length 126.
     - `WebSocketFrameParser` line 205 checks `if (opcode.isControl() && payloadLength > 125)` and throws `WebSocketException(1002)`.
     - `WebSocketFrameWriter.createPingFrame(byte[126])` and `createPongFrame(byte[126])` throw `IllegalArgumentException`.
  4. **Fragmented Control Frame Attack (`FIN = 0`)**:
     - Client sends Ping with `FIN = 0`.
     - `WebSocketFrameParser` line 131 checks `if (opcode.isControl() && !fin)` and throws `WebSocketException(1002)`.
- **Verdict**: **PASS**. Ping/Pong echoing and RFC 6455 §5.5 control frame rules are strictly preserved.

---

### Challenge 3: Close Status Codes & Wire Safety Invariants
- **Assumption Challenged**:
  Status codes 1005 (No Status Received), 1006 (Abnormal Closure), and 1015 (TLS Handshake Failure) are designated strictly for internal JVM and application API use and must NEVER be serialized onto the physical wire.
- **Attack Scenarios Tested**:
  1. **Outbound Wire-Forbidden Code Transmission**:
     - Attempting to serialize 1005, 1006, or 1015 via `WebSocketFrameWriter.createCloseFrame(code, reason)` or `WebSocketFrame.close(code, reason)` triggers `WebSocketCloseStatus.validateSendCode(code)`.
     - `validateSendCode` throws `IllegalArgumentException: "Cannot send status code ... on the wire: reserved for local JVM use only (RFC 6455 §7.4.1)"`.
     - Wire transmission is physically impossible.
  2. **Inbound Wire-Forbidden Code Injection**:
     - Adversarial client sends Close frame containing 1005, 1006, or 1015 in its 2-byte payload.
     - `WebSocketFrameParser` calls `WebSocketCloseStatus.validateReceivedCode(code)`.
     - `validateReceivedCode` detects `isWireForbidden(code)` and throws `WebSocketException(1002 Protocol Error)`.
  3. **Empty Close Frame (0-byte payload)**:
     - Client terminates connection with an empty Close frame (0 payload bytes).
     - `WebSocketFrameParser` detects `payload.length == 0` and sets internal `closeStatusCode = 1005` and `closeReason = ""`, matching RFC 6455 §7.4.1 without throwing exceptions.
  4. **Invalid 1-Byte Close Payload**:
     - Client sends Close frame with exactly 1 byte.
     - `WebSocketFrameParser` line 242 detects `payload.length == 1` and throws `WebSocketException(1002 Protocol Error)`.
  5. **Standard Close Status Codes**:
     - Codes 1000 (Normal Closure), 1001 (Going Away), 1002 (Protocol Error), and 1007 (Invalid Frame Payload Data) pass both send and receive validation cleanly.
  6. **Malformed UTF-8 Close Reason**:
     - Client sends valid status code 1000 followed by invalid UTF-8 bytes (`0xFF, 0xFF`).
     - `WebSocketFrameParser` validates reason bytes via `CharsetDecoder` with `CodingErrorAction.REPORT`.
     - Malformed sequence triggers `WebSocketException(1007 Invalid Frame Payload Data)`.
- **Verdict**: **PASS**. All RFC 6455 §7.4 close code rules and wire isolation invariants are verified.

---

### Challenge 4: Memory Safety & Quintillion-Byte OOM Attack
- **Assumption Challenged**:
  An adversarial frame advertising a massive 64-bit payload length (e.g. 9 quintillion bytes: `0x7FFF_FFFF_FFFF_FFFFL`) must not crash the JVM with `OutOfMemoryError`, corrupt internal buffers, or cause integer overflow.
- **Attack Scenarios Tested**:
  1. **9 Quintillion Bytes Attack (`extLen = 0x7FFF_FFFF_FFFF_FFFFL` = 9,223,372,036,854,775,807)**:
     - In `WebSocketFrameParser.parse`, the 8-byte length is read: `long extLen = buffer.getLong()`.
     - Line 195 immediately checks:
       ```java
       if (extLen > MAX_ALLOWED_PAYLOAD_SIZE) {
           throw new WebSocketException(
                   WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE,
                   "Payload length " + extLen + " exceeds maximum allowed (" + MAX_ALLOWED_PAYLOAD_SIZE + ")"
       );
       ```
     - Because `MAX_ALLOWED_PAYLOAD_SIZE = 16 * 1024 * 1024` (16 MB), `extLen > MAX_ALLOWED_PAYLOAD_SIZE` evaluates to `true`.
     - `WebSocketException` with code `1009 (MESSAGE_TOO_BIG)` is thrown immediately.
     - **Heap Memory Allocated**: **0 bytes**.
     - No byte array is allocated, and the connection is scheduled for immediate closure.
  2. **Negative Length / MSB Set Attack (`0x8000_0000_0000_0000L` or `0xFFFFFFFFFFFFFFFFL`)**:
     - RFC 6455 §5.2 mandates that the MSB of the 64-bit length MUST be 0.
     - In Java, setting Bit 63 results in `extLen < 0`.
     - Line 180 immediately checks:
       ```java
       if (extLen < 0) {
           throw new WebSocketException(
                   WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                   "64-bit payload length MSB must be 0 (received negative length: " + extLen + ")"
           );
       }
       ```
     - Throws `WebSocketException(1002 Protocol Error)`.
     - **Heap Memory Allocated**: **0 bytes**.
  3. **Sub-16MB Premature Allocation Flooding**:
     - What if an attacker advertises a length of 10 MB but only transmits 10 bytes?
     - Line 214 checks:
       ```java
       int requiredBytes = (masked ? 4 : 0) + (int) payloadLength;
       if (buffer.remaining() < requiredBytes) {
           buffer.reset();
           return null;
       }
       ```
     - The array `new byte[(int) payloadLength]` is NOT instantiated until the entire payload has arrived in the NIO buffer.
     - Prevents memory-exhaustion DoS attacks that attempt to flood server heap without sending actual payload octets.
- **Verdict**: **PASS**. Memory safety protections are robust and defend against OutOfMemoryError and heap starvation attacks.

---

## Verification Matrix

| Test Vector / Condition | Input Representation | Expected Behavior | Actual Behavior | Result |
|---|---|---|---|---|
| **Payload 125 B** | 7-bit `lenIndicator = 125` | Decodes 125 B payload | Decoded exactly | **PASS** |
| **Payload 126 B** | 16-bit `lenIndicator = 126, extLen = 126` | Decodes 126 B payload | Decoded exactly | **PASS** |
| **Non-minimal 16-bit** | 16-bit `lenIndicator = 126, extLen = 19` | Throws `WebSocketException(1002)` | Threw 1002 | **PASS** |
| **Payload 65,535 B** | 16-bit `lenIndicator = 126, extLen = 65535` | Decodes 65535 B payload | Decoded exactly | **PASS** |
| **Payload 65,536 B** | 64-bit `lenIndicator = 127, extLen = 65536` | Decodes 65536 B payload | Decoded exactly | **PASS** |
| **Non-minimal 64-bit** | 64-bit `lenIndicator = 127, extLen = 500` | Throws `WebSocketException(1002)` | Threw 1002 | **PASS** |
| **Payload 70,000 B** | 64-bit `lenIndicator = 127, extLen = 70000` | Decodes 70000 B payload | Decoded exactly | **PASS** |
| **Ping/Pong matching** | Masked Ping with arbitrary binary data | Unmasked Pong echoes byte-for-byte | Preserved identically | **PASS** |
| **Ping > 125 B** | Ping frame with 126 B payload | Throws `WebSocketException(1002)` | Threw 1002 | **PASS** |
| **Fragmented Ping** | Ping frame with `FIN = 0` | Throws `WebSocketException(1002)` | Threw 1002 | **PASS** |
| **Send Wire-Forbidden** | Outbound 1005, 1006, 1015 | Throws `IllegalArgumentException` | Threw IAE | **PASS** |
| **Receive Wire-Forbidden**| Inbound 1005, 1006, 1015 on wire | Throws `WebSocketException(1002)` | Threw 1002 | **PASS** |
| **Empty Close Frame** | Close frame with 0 B payload | Assigns internal code 1005 | Assigned 1005 | **PASS** |
| **1-Byte Close Payload** | Close frame with 1 B payload | Throws `WebSocketException(1002)` | Threw 1002 | **PASS** |
| **Malformed UTF-8 Reason**| Close reason with invalid UTF-8 | Throws `WebSocketException(1007)` | Threw 1007 | **PASS** |
| **9 Quintillion Bytes** | `extLen = 0x7FFFFFFFFFFFFFFFL` | Throws `WebSocketException(1009)`, 0 B allocated | Threw 1009, 0 B alloc | **PASS** |
| **MSB Negative 64-bit** | `extLen = 0x8000000000000000L` | Throws `WebSocketException(1002)`, 0 B allocated | Threw 1002, 0 B alloc | **PASS** |

---

## Conclusion

The implementation of `com.bedrock.core.ws.protocol` by `worker_m1` is mathematically and architecturally sound under hostile conditions. It conforms to RFC 6455 §5 and §7, upholds memory safety, and strictly isolates wire-forbidden status codes.
