## 2026-09-11T04:27:17Z

You are challenger_m2_2, an Adversarial Challenger for Milestone 2: Virtual-Threaded NIO Server & Sessions.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m2_2
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md.
Worker's handoff is at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m2\handoff.md

Your Mission:
Adversarially challenge the protocol and network behavior of `BedrockWebSocketServer`:
1. Test raw wire-level protocol violations against the live server:
   - Send an unmasked frame from raw `Socket` (must receive Close frame with status 1002 Protocol Error).
   - Send invalid HTTP GET handshake without Upgrade header (must receive HTTP 400 Bad Request).
   - Send handshake with Sec-WebSocket-Version: 8 (must receive HTTP 426 Upgrade Required).
   - Send handshake to nonexistent route (must receive HTTP 404 Not Found).
2. Test control frame interactions:
   - Send raw Ping frame with arbitrary payload; verify server responds immediately with Pong frame echoing exact payload bytes.
   - Send Close frame with status 1000 and reason; verify server echoes Close frame and closes socket.
3. Test UTF-8 multi-byte / emoji transmission across live socket.

Deliverables:
- Write challenge report to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m2_2\challenge.md`
- Write `handoff.md` with explicit verdict: **APPROVE** (confirmed correct) or **REQUEST_CHANGES** (bugs found).
- Send completion message to parent orchestrator.
