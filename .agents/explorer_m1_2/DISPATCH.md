## 2026-09-11T04:01:37Z
You are explorer_m1_2, an Explorer for Milestone 1 (RFC 6455 Protocol Engine).
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_2
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read this file for architecture and interface contracts).
You can also consult spec survey at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\spec_miner_survey_1\spec_survey.md

Your Task:
Design the exact byte-level parsing, serialization, and unmasking algorithms for:
1. `WebSocketFrame.java`:
   - Immutable representation: `fin` (boolean), `opcode` (WebSocketOpcode), `masked` (boolean), `payload` (byte[]), close status code (int, if close frame), close reason (String, if close frame).
   - Factory methods: `text(String)`, `ping(byte[])`, `pong(byte[])`, `close(int, String)`.
2. `WebSocketFrameParser.java`:
   - State or streaming/buffer decoder for frames from `ByteBuffer`:
     * Byte 0: FIN (bit 7), RSV1-3 (bits 6-4, must be 0 -> WebSocketException 1002 if non-zero), Opcode (bits 3-0).
     * Byte 1: MASK bit (bit 7, MUST be 1 for client frames -> WebSocketException 1002 if 0), Payload Length (bits 6-0).
     * Extended length: if 126, read next 2 bytes as unsigned 16-bit int (validate >= 126 -> minimal length rule); if 127, read next 8 bytes as unsigned 64-bit int (MSB must be 0, validate >= 65536 -> minimal length rule).
     * Masking key: next 4 bytes.
     * Payload: read remaining bytes.
     * In-place XOR unmasking: $D_i = E_i \oplus M_{i \pmod 4}$.
     * Control frame invariants: FIN must be 1, payload length <= 125, cannot be fragmented.
     * UTF-8 validation for text frames (Opcode 0x1) and Close reason string (throw WebSocketException 1007 if invalid).
     * Close frame validation: payload length 0 or >= 2 (throw 1002 if payload length == 1).
3. `WebSocketFrameWriter.java`:
   - Encodes server-to-client frames (MASK=0, unmasked):
     * Text frame: FIN=1, Opcode 0x1, appropriate 7/16/64 bit length encoding, UTF-8 payload bytes.
     * Ping frame: FIN=1, Opcode 0x9, payload.
     * Pong frame: FIN=1, Opcode 0xA, payload.
     * Close frame: FIN=1, Opcode 0x8, 2-byte status code in big-endian + UTF-8 reason string bytes.

Deliverables:
- Write design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_2\frame_design.md`
- Write `handoff.md` in your working directory.
- Send a completion message to the parent orchestrator.
