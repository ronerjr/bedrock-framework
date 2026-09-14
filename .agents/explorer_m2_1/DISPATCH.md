## 2026-09-11T04:15:38Z

You are explorer_m2_1, an Explorer for Milestone 2: Virtual-Threaded NIO Server & Sessions.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).
Milestone 1 code is in: `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/` (WebSocketHandshake, WebSocketFrame, WebSocketFrameParser, WebSocketFrameWriter, etc.).

Your Mission:
Design:
1. `BedrockWebSocketServer.java`:
   - Non-blocking/blocking `ServerSocketChannel` configuration and binding to a specified port or ephemeral port (`port 0`).
   - `int getPort()` returning the bound local port via `((InetSocketAddress) serverChannel.getLocalAddress()).getPort()`.
   - Thread-safe endpoint route registration (`registerEndpoint(String path, WebSocketEndpointBinding binding)`).
   - Lifecycle management: `start()`, `stop()`, and `close()` implementing `AutoCloseable`.
   - Accept loop running on a thread/virtual thread: continuously accepts `SocketChannel` and dispatches each to `WebSocketClientHandler`.
2. `WebSocketClientHandler.java`:
   - Spawns connection on dedicated Virtual Thread: `Thread.ofVirtual().name("ws-client-" + clientId).start(...)`.
   - Reads HTTP handshake from `SocketChannel` using `WebSocketHandshake`, extracts path, checks route registry (returns HTTP 404 if no route matched, HTTP 426 if invalid version, HTTP 400 if bad handshake).
   - Computes `Sec-WebSocket-Accept` and writes HTTP 101 Switching Protocols response to `SocketChannel`.
   - Transitions channel to full-duplex WebSocket frame loop.
   - Synchronous blocking read loop: reads bytes into `ByteBuffer`, parses frames via `WebSocketFrameParser.parse(buffer)`.
   - Handles opcodes:
     * Opcode 0x1 (Text): invokes endpoint `@OnMessage`.
     * Opcode 0x9 (Ping): immediately replies with Pong (Opcode 0xA) echoing exact payload bytes via `WebSocketFrameWriter.createPongFrame()`.
     * Opcode 0xA (Pong): keepalive acknowledgment.
     * Opcode 0x8 (Close): extracts status code and reason, echoes Close frame, unregisters session, invokes `@OnClose`, cleanly closes channel.
     * On `WebSocketException`: sends Close frame with error status code, invokes `@OnError`, closes channel.

Deliverables:
- Write detailed design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_1\server_design.md`
- Write `handoff.md` in your working directory.
- Send completion message to parent orchestrator.
