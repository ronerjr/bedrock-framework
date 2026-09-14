## 2026-09-11T04:01:39Z

You are explorer_m1_3, an Explorer for Milestone 1 (RFC 6455 Protocol Engine).
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_3
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read this file for architecture and interface contracts).
You can also consult core survey at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1\core_survey.md

Your Task:
Design:
1. Complete Unit Test Plan and exact test cases for:
   - `WebSocketHandshakeTest.java`:
     * RFC 6455 §1.3 official test vector: key "dGhlIHNhbXBsZSBub25jZQ==" -> accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".
     * Valid handshake request headers parsing.
     * Missing or invalid headers (missing Upgrade, missing Sec-WebSocket-Key, invalid version != 13).
     * Path extraction (`/chat`, `/ws/telemetry`, `/`).
     * Response byte generation verification (status line, headers, CRLF CRLF).
   - `WebSocketFrameTest.java`:
     * Masked text frame decoding (XOR unmasking verification).
     * Unmasked text frame encoding (server to client).
     * 7-bit small payload (<126 bytes).
     * 16-bit medium payload (126 to 65535 bytes, e.g. 500 bytes, 1000 bytes, 65000 bytes).
     * 64-bit large payload (>65535 bytes, e.g. 70000 bytes).
     * Control frames: Ping encoding/decoding, Pong encoding/decoding, payload echo.
     * Close frame: 2-byte status code parsing, reason extraction, encoding.
     * Edge cases / Protocol violations:
       - Client unmasked frame (must throw WebSocketException with code 1002).
       - Non-zero RSV bits (must throw 1002).
       - Non-minimal length encoding (indicator 126 for length < 126, or indicator 127 for length < 65536 -> throw 1002).
       - Control frame with FIN=0 or payload > 125 (throw 1002).
       - Close frame with payload length 1 (throw 1002).
       - Invalid UTF-8 bytes in text payload (throw 1007).
2. Educational Standard (🎓 BEDROCK TUTORIAL Javadoc):
   - Design the exact didactic tutorials to be placed in each class Javadoc:
     * `WebSocketHandshake`: The physics of HTTP 101 Switching Protocols, why the magic GUID exists (security defense against caching cross-protocol attacks), SHA-1 + Base64 mechanics.
     * `WebSocketFrameParser`: The anatomy of a WebSocket frame (bit layout diagram), the physics of XOR unmasking, and why client masking was mandated by RFC 6455 (transparent cache poisoning prevention).
     * `WebSocketFrameWriter`: Server unmasked framing rules.

Deliverables:
- Write design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_3\test_and_tutorial_design.md`
- Write `handoff.md` in your working directory.
- Send a completion message to the parent orchestrator.
