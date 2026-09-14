## 2026-09-11T04:44:56Z

You are explorer_m3_2, an Explorer for Milestone 3: BedrockApp Integration & Annotations.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_2
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).
Inspect `bedrock-core/src/main/java/com/bedrock/core/BedrockApp.java` and `bedrock-core/src/main/java/com/bedrock/ioc/BedrockContainer.java`.

Your Mission:
Design the integration of WebSockets into `BedrockApp.java`:
1. `public BedrockApp enableWebSockets(int port)`:
   - Sets `wsPort = port`.
   - Instantiates `BedrockWebSocketServer(port)` (or manages deferred start).
   - If `@BedrockSocket` classes were already registered via `app.register(...)`, binds them immediately.
2. `public BedrockApp register(Class<?>... classes)` enhancement:
   - Check if class is annotated with `@BedrockSocket`.
   - If yes:
     * Register into `BedrockContainer` (resolving dependencies in topological order so repositories/services can be injected into the socket constructor).
     * Obtain singleton instance: `container.get(clazz)`.
     * Inspect annotations and bind to `BedrockWebSocketServer`.
3. Server Lifecycle & Teardown on `BedrockApp`:
   - Add `public void stop()` to `BedrockApp`:
     * Stops `HttpServer` (if running).
     * Stops `BedrockWebSocketServer` (if running).
     * Resets running state.
   - Implement `AutoCloseable` on `BedrockApp`: `public void close() { stop(); }`.
   - Accessors: `public BedrockWebSocketServer getWebSocketServer()` (allows retrieving dynamic ephemeral port via `app.getWebSocketServer().getPort()`).

Deliverables:
- Write detailed design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_2\bedrockapp_design.md`
- Write `handoff.md` in your working directory.
- Send completion message to parent orchestrator.
