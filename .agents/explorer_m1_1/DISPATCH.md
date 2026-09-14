## 2026-09-11T04:01:36Z
You are explorer_m1_1, an Explorer for Milestone 1 (RFC 6455 Protocol Engine).
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read this file for architecture and interface contracts).
You can also consult spec survey at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\spec_miner_survey_1\spec_survey.md

Your Task:
Design the exact class structure, method signatures, byte-level logic, and error handling for:
1. `WebSocketHandshake.java`:
   - Reading HTTP request line and headers from a ByteBuffer / InputStream / String.
   - Validating GET method, `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Key` (16 bytes base64), and `Sec-WebSocket-Version: 13`.
   - Calculating `Sec-WebSocket-Accept`: SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11") -> Base64.
   - Generating standard `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ...\r\n\r\n` byte array.
   - Path extraction from request line (e.g. `GET /chat HTTP/1.1` -> `/chat`).
2. `WebSocketOpcode.java`:
   - Enum with opcode values: CONTINUATION(0x0), TEXT(0x1), BINARY(0x2), CLOSE(0x8), PING(0x9), PONG(0xA).
   - Helper methods: `isControl()`, `fromCode(int code)`.
3. `WebSocketCloseStatus.java`:
   - Standard RFC 6455 status codes (1000 Normal, 1001 Going Away, 1002 Protocol Error, 1003 Unsupported Data, 1007 Invalid UTF-8, 1008 Policy Violation, 1009 Message Too Big, 1011 Server Error).
   - Validation for wire-forbidden codes (1005, 1006, 1015) which cannot be sent on the wire.
4. `WebSocketException.java`:
   - RuntimeException carrying a close status code (e.g. 1002 for protocol error) and descriptive message.

Deliverables:
- Write design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_1\handshake_design.md`
- Write `handoff.md` in your working directory.
- Send a completion message to the parent orchestrator.
