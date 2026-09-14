# Handoff Report: RFC 6455 Handshake & Protocol Fundamentals Design

**Agent**: `explorer_m1_1`  
**Milestone**: Milestone 1 (RFC 6455 Protocol Engine)  
**Task**: Design class structure, method signatures, byte-level logic, and error handling for `WebSocketHandshake`, `WebSocketOpcode`, `WebSocketCloseStatus`, and `WebSocketException`.  
**Artifact Path**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_1\handshake_design.md`

---

## 1. Observation

Direct inspection of the project requirements, architecture specifications, and codebase revealed:
- **Interface Contract**: In `PROJECT.md` (lines 76–82), `WebSocketHandshake` is explicitly contracted to provide:
  - `public static boolean isUpgradeRequest(String httpHeaders)`
  - `public static String extractKey(String httpHeaders)`
  - `public static String computeAccept(String clientKey)`
  - `public static byte[] createHandshakeResponse(String acceptKey)`
  - `public static String extractPath(String httpHeaders)`
- **RFC 6455 Handshake Rules**:
  - Request method MUST be `GET`.
  - HTTP Version MUST be `HTTP/1.1` or higher.
  - `Upgrade` header MUST contain token `websocket` (case-insensitive).
  - `Connection` header MUST contain token `Upgrade` (case-insensitive; comma-separated token matching required for clients sending `keep-alive, Upgrade`).
  - `Sec-WebSocket-Version` MUST be `13`. If missing or invalid, RFC 6455 §4.4 mandates an HTTP `426 Upgrade Required` response with `Sec-WebSocket-Version: 13`.
  - `Sec-WebSocket-Key` MUST be a Base64 string that decodes to **exactly 16 bytes**.
  - `Sec-WebSocket-Accept` calculation is $\text{Base64}(\text{SHA-1}(\text{key.trim()} + \text{"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}))$.
  - Test vector (RFC 6455 §1.3): `"dGhlIHNhbXBsZSBub25jZQ=="` yields `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`.
- **Opcode Specification**:
  - 4-bit field in Byte 0 (bits 3–0).
  - Data opcodes: `CONTINUATION` (0x0), `TEXT` (0x1), `BINARY` (0x2).
  - Control opcodes: `CLOSE` (0x8), `PING` (0x9), `PONG` (0xA).
  - Bit 3 set indicates a control frame (`(code & 0x8) != 0`). Unrecognized/reserved opcodes MUST cause connection termination with status code `1002 Protocol Error`.
- **Close Status Codes**:
  - RFC 6455 §7.4 defines standard codes: 1000 (Normal), 1001 (Going Away), 1002 (Protocol Error), 1003 (Unsupported Data), 1007 (Invalid UTF-8), 1008 (Policy Violation), 1009 (Message Too Big), 1011 (Server Error).
  - Wire-forbidden codes: `1005` (No Status Received), `1006` (Abnormal Closure), `1015` (TLS Handshake Failure). These are internal status indicators and must NEVER be placed in a Close frame on the wire. If received on the wire, the receiver MUST terminate with status `1002`.
- **NIO Buffer Boundary Invariant**:
  - When parsing HTTP handshake headers from a `ByteBuffer` connected to a `SocketChannel`, the buffer position must be advanced past `\r\n\r\n` ($+4$ bytes). Any subsequent bytes in the buffer represent the start of the first RFC 6455 frame, preventing frame loss in pipelined TCP packets.

---

## 2. Logic Chain

1. **Interface Contract Alignment**: The 5 static methods defined in `PROJECT.md` (§76–82) are designed with exact matching signatures to ensure seamless integration with the server layer (`WebSocketClientHandler`).
2. **Buffer Safety**: By adding `findHeaderEnd(ByteBuffer)` and `parse(ByteBuffer)` to `WebSocketHandshake`, the server can detect whether a full header has arrived without copying partial streams. Advancing the buffer's position to the start of the frame payload guarantees zero data loss between HTTP upgrade and WebSocket framing.
3. **Resilient Token Matching**: RFC 6455 §4.2.1 specifies that `Connection` contains the token `Upgrade` and `Upgrade` contains `websocket`. Standard browsers and clients frequently send compound headers like `Connection: keep-alive, Upgrade`. Checking comma-separated tokens rather than simple exact string equality prevents false rejection of valid WebSocket clients.
4. **Wire-Forbidden Code Separation**: In `WebSocketCloseStatus`, separating validation into `validateSendCode` (throws `IllegalArgumentException` on local violations) and `validateReceivedCode` (throws `WebSocketException(1002)` when a peer violates the protocol) ensures strict adherence to RFC 6455 §7.4.
5. **Unified Protocol Error Handling**: `WebSocketException` extends `RuntimeException` and carries `statusCode`. This allows frame parsers and handshake handlers to fail-fast; the Virtual Thread client loop catches `WebSocketException`, extracts `getStatusCode()`, sends a Close frame with that code, and closes the channel cleanly.

---

## 3. Caveats

- **Permessage-Deflate Extension**: RFC 7692 extensions (compression) are out of scope for Milestone 1; RSV1–3 bits are validated to be strictly 0, and any non-zero RSV bit triggers status code 1002.
- **Header Size Protection**: A hard limit of 16 KB is enforced for HTTP handshake headers to prevent denial-of-service memory exhaustion before the connection upgrades.
- **URI Query Parameters**: `extractPath(String)` strips query parameters (e.g. `?token=...`) to return the normalized route path matching `@BedrockSocket(path = "...")`. The raw path is also made available in `HandshakeParseResult`.

---

## 4. Conclusion

The specification in `handshake_design.md` provides complete, ready-to-implement class structures, signatures, algorithms, error handling, and 🎓 BEDROCK TUTORIAL Javadoc blocks for:
- `com.bedrock.core.ws.protocol.WebSocketHandshake`
- `com.bedrock.core.ws.protocol.WebSocketOpcode`
- `com.bedrock.core.ws.protocol.WebSocketCloseStatus`
- `com.bedrock.core.ws.protocol.WebSocketException`

All requirements from `ORIGINAL_REQUEST.md`, `PROJECT.md`, and the user dispatch prompt have been addressed with zero external dependencies (JDK 21 standard library only).

---

## 5. Verification Method

To independently verify the design:
1. Inspect the full specification file:
   `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_1\handshake_design.md`
2. Validate against RFC 6455 §1.3 test vector:
   - Key: `dGhlIHNhbXBsZSBub25jZQ==`
   - Algorithm: SHA-1(key + `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`) -> Base64
   - Expected Result: `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`
3. Run the project's existing regression suite to confirm no regressions:
   `mvn test` (must pass all 81 existing baseline tests).
4. Verify layout compliance: no source code or test files placed in `.agents/`.
