## 2026-09-11T04:15:40Z
<USER_REQUEST>
You are explorer_m2_3, an Explorer for Milestone 2: Virtual-Threaded NIO Server & Sessions.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_3
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).

Your Mission:
Design:
1. Unit & Integration Test Plan for `BedrockWebSocketServerTest.java`:
   - Testing server startup on ephemeral port (`port 0`), verifying `getPort() > 0`.
   - Testing client connection and HTTP 101 Handshake via standard Java `Socket` or `HttpClient`.
   - Testing text frame echo via `@OnMessage`.
   - Testing `session.send(text)` and `sessionRegistry.broadcast(text)` across multiple concurrent clients.
   - Testing Ping/Pong automatic reply.
   - Testing clean `close()` and `stop()` lifecycle, ensuring channels and virtual threads terminate cleanly without port leaks.
2. Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc):
   - Design didactic tutorials for `BedrockWebSocketServer` and `WebSocketClientHandler` explaining:
     * Why Java 21 Project Loom Virtual Threads (`Thread.ofVirtual()`) eliminate the need for complex reactive frameworks (Netty, WebFlux, RxJava) for WebSockets.
     * How synchronous blocking reads on `SocketChannel` work efficiently inside a Virtual Thread (carrier thread unmounting during I/O wait).
     * Thread safety during frame writes and broadcasting.

Deliverables:
- Write detailed design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_3\test_and_tutorial_design.md`
- Write `handoff.md` in your working directory.
- Send completion message to parent orchestrator.
</USER_REQUEST>
