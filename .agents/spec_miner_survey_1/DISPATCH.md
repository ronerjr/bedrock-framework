## 2026-09-11T03:55:57Z
You are spec_miner_survey_1, an RFC 6455 Spec Miner.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\spec_miner_survey_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).

Your Mission:
Mine and document the complete, authoritative technical specification of WebSocket RFC 6455 and exact mathematical and byte-level requirements needed for Bedrock v2.0:
1. HTTP 101 Handshake:
   - Client request headers format (Upgrade, Connection, Sec-WebSocket-Key, Sec-WebSocket-Version).
   - Sec-WebSocket-Accept calculation: exact algorithm (SHA-1 hashing of Sec-WebSocket-Key concatenated with RFC 6455 GUID "258EAFA5-E914-47DA-95CA-C5AB0DC85B11", followed by Base64 encoding).
   - Test vectors from RFC 6455 (e.g. key "dGhlIHNhbXBsZSBub25jZQ==" -> accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=").
   - HTTP 101 Switching Protocols response byte sequence and required headers.
2. RFC 6455 Frame Anatomy and Parsing/Serializing:
   - Bit layout: FIN (1 bit), RSV1-3 (3 bits), Opcode (4 bits: 0x0 Continuation, 0x1 Text, 0x2 Binary, 0x8 Close, 0x9 Ping, 0xA Pong).
   - MASK bit (1 bit): client-to-server MUST be masked (mask bit = 1), server-to-client MUST NOT be masked (mask bit = 0). What should server do if client sends unmasked frame? (RFC 6455 §5.1 close connection with status code 1002).
   - Payload length encoding:
     * 7-bit (0-125): direct value
     * 16-bit extended (126): next 2 bytes as unsigned 16-bit int
     * 64-bit extended (127): next 8 bytes as unsigned 64-bit int (MSB must be 0)
   - Masking Key (4 bytes) and XOR unmasking formula: transformed-octet-i = original-octet-i XOR masking-key-octet-(i mod 4).
   - Control frames rules (FIN must be 1, max payload 125 bytes, no fragmentation allowed).
   - Close frame anatomy (2-byte status code, optional UTF-8 reason string; standard status codes: 1000 normal, 1001 going away, 1002 protocol error, 1003 unsupported data, etc.).
   - Ping/Pong frame handling (server must reply with Pong carrying exact same application data as Ping).
3. Educational & Didactic Requirements (🎓 BEDROCK TUTORIAL):
   - Formulate didactic explanations and diagrams for the byte layout, handshake SHA-1/Base64, and XOR unmasking.

Deliverables:
- Write your detailed specification analysis to: c:\Users\roner\Documents\repo\bedrock-framework\.agents\spec_miner_survey_1\spec_survey.md
- Write handoff.md in your working directory summarizing key findings, constraints, and recommendations.
- Send a completion message to the parent orchestrator.
