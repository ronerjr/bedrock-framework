## 2026-09-11T04:15:39Z

<USER_REQUEST>
You are explorer_m2_2, an Explorer for Milestone 2: Virtual-Threaded NIO Server & Sessions.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_2
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).
Milestone 1 code is in: `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/`

Your Mission:
Design:
1. `BedrockWebSocketSession.java` (interface and implementation):
   - Thread-safe session abstraction representing an active connection:
     * `String getId()`: unique session ID (e.g. UUID).
     * `SocketAddress getRemoteAddress()`: client IP/port.
     * `boolean isOpen()`: checks if channel is connected and open.
     * `void send(String message)`: thread-safe text frame transmission (synchronize on write lock/channel to prevent frame interleaving from concurrent threads).
     * `void sendPing(byte[] applicationData)`: thread-safe Ping transmission.
     * `void close()`: sends standard 1000 Normal Closure frame and closes channel.
     * `void close(int statusCode, String reason)`: sends Close frame with custom status code and reason string.
2. `WebSocketSessionRegistry.java`:
   - Thread-safe session registry (`ConcurrentHashMap<String, BedrockWebSocketSession>`).
   - `register(BedrockWebSocketSession session)`, `unregister(String sessionId)`.
   - `get(String sessionId)`, `getAll()`.
   - `broadcast(String text)`: thread-safe broadcast across all active sessions, gracefully ignoring or removing already closed sessions.
3. `WebSocketEndpointBinding.java`:
   - Dispatcher interface or class wrapping reflected endpoint methods (`onOpen`, `onMessage`, `onClose`, `onError`) or functional consumers.

Deliverables:
- Write detailed design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m2_2\session_design.md`
- Write `handoff.md` in your working directory.
- Send completion message to parent orchestrator.
</USER_REQUEST>
