## 2026-09-11T04:20:11Z

You are worker_m2, the Implementation Worker for Milestone 2: Virtual-Threaded NIO Server & Sessions.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m2
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Design Documents to Follow:
- Server & Channel design: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_1\server_design.md
- Session & Registry design: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_2\session_design.md
- Server Tests & Loom Tutorial design: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_3\test_and_tutorial_design.md

Exclusive File Write Ownership:
You own and will create/modify ONLY the following files:
1. `bedrock-core/src/main/java/com/bedrock/core/ws/server/BedrockWebSocketSession.java`
2. `bedrock-core/src/main/java/com/bedrock/core/ws/server/StandardWebSocketSession.java` (if needed, or implement inside package)
3. `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketSessionRegistry.java`
4. `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketEndpointBinding.java`
5. `bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketClientHandler.java`
6. `bedrock-core/src/main/java/com/bedrock/core/ws/server/BedrockWebSocketServer.java`
7. `bedrock-core/src/test/java/com/bedrock/core/ws/server/BedrockWebSocketServerTest.java`

Key Requirements:
- Zero external runtime dependencies in `bedrock-core/pom.xml`: use strictly JDK 21 standard library (`java.nio.channels.ServerSocketChannel`, `SocketChannel`, `ByteBuffer`, `ReentrantLock`, `Thread.ofVirtual()`, etc.). DO NOT modify `bedrock-core/pom.xml`.
- Dedicated Virtual Thread per connection: `Thread.ofVirtual().name("ws-client-", clientId).start(...)`.
- Non-blocking/blocking `ServerSocketChannel` binding with ephemeral port support (`port 0`), providing `getPort()`.
- Clean lifecycle management: `start()`, `stop()`, and `close()` implementing `AutoCloseable` with zero port or thread leaks.
- Thread-safe session write lock via `ReentrantLock` (avoiding carrier thread pinning) for atomic frame transmission and broadcast.
- Automatic Pong reply (Opcode 0xA) on incoming Ping (Opcode 0x9) echoing application data.
- Two-way Close handshake and graceful channel termination.
- Include rich `🎓 BEDROCK TUTORIAL` Javadocs on all classes explaining Loom Virtual Threads, carrier thread unmounting, and socket channel mechanics.
- Implement comprehensive unit and integration tests in `BedrockWebSocketServerTest.java` using ephemeral ports.
- Run `mvn test` and verify that all 81 baseline tests + Milestone 1 tests + Milestone 2 tests pass with 100% success.
- Report all build and test outputs in your handoff report.

Deliverables:
- Write `changes.md` and `handoff.md` in `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m2`
- Send completion message to parent orchestrator.
