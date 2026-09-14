## 2026-09-11T04:44:55Z

You are explorer_m3_1, an Explorer for Milestone 3: BedrockApp Integration & Annotations.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).

Your Mission:
Design the declarative annotations in package `com.bedrock.core.ws.annotation`:
1. `@BedrockSocket`:
   - `@Retention(RetentionPolicy.RUNTIME)`, `@Target(ElementType.TYPE)`.
   - Element: `String path() default "/";` (or `value()`).
2. `@OnOpen`:
   - `@Retention(RetentionPolicy.RUNTIME)`, `@Target(ElementType.METHOD)`.
   - Dispatched when handshake succeeds, injecting `BedrockWebSocketSession`.
3. `@OnMessage`:
   - `@Retention(RetentionPolicy.RUNTIME)`, `@Target(ElementType.METHOD)`.
   - Dispatched on incoming text frame, injecting `(BedrockWebSocketSession session, String message)` or `(String message)` or `(BedrockWebSocketSession session)`.
4. `@OnClose`:
   - `@Retention(RetentionPolicy.RUNTIME)`, `@Target(ElementType.METHOD)`.
   - Dispatched on client disconnect, injecting `(BedrockWebSocketSession session, int code, String reason)` or variations.
5. `@OnError`:
   - `@Retention(RetentionPolicy.RUNTIME)`, `@Target(ElementType.METHOD)`.
   - Dispatched on error, injecting `(BedrockWebSocketSession session, Throwable throwable)` or `(Throwable throwable)`.

Design reflective binding scanner:
- How to scan methods of a `@BedrockSocket` annotated class, validate signatures, and create a `WebSocketEndpointBinding.ReflectiveEndpointBinding`.

Deliverables:
- Write detailed design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_1\annotations_design.md`
- Write `handoff.md` in your working directory.
- Send completion message to parent orchestrator.
