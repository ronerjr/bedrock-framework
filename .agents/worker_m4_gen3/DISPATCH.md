# Worker M4 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_1\chat_websocket_design.md`
5. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_1\handoff.md`
6. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_2\chat_playground_and_tests_design.md`
7. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_2\handoff.md`

## Mission
Implement Milestone 4 (bedrock-example Real-Time Demo):
1. Create `bedrock-example/src/main/java/com/bedrock/example/ws/ChatWebSocket.java`:
   - Annotated with `@BedrockSocket("/chat")`.
   - Uses `ConcurrentHashMap<String, BedrockWebSocketSession>` to track connected clients.
   - Lifecycle annotations: `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`.
   - Broadcast logic: broadcasts messages to all connected sessions safely, isolating dead sessions.
   - Support `/nick <name>` command or default user naming.
   - Rich `🎓 BEDROCK TUTORIAL` Javadoc explaining real-time WebSockets and virtual thread concurrency.
2. Create `bedrock-example/src/main/java/com/bedrock/example/ws/ChatPlayground.java`:
   - Dark-theme HTML/CSS/JS page using Java 21 Text Block.
   - Dynamic WebSocket port/URL resolution.
   - Interactive chat UI with connection status, message log with timestamps, username input, message field, send button, ping probe, and educational RFC 6455 inspector.
3. Update `bedrock-example/src/main/java/com/bedrock/example/Application.java`:
   - Add `createApp(int httpPort, int wsPort)` factory method for easy testing with ephemeral port 0.
   - Enable WebSockets via `app.enableWebSockets(wsPort)`.
   - Register `ChatWebSocket.class` in `app.register(...)`.
   - Serve `/chat` HTML client page via `app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml()))`.
4. Create `bedrock-example/src/test/java/com/bedrock/example/ChatWebSocketTest.java`:
   - Comprehensive integration tests using JDK 21 `HttpClient` against ephemeral port 0 in try-with-resources.
   - Test handshake, send/receive, multi-client broadcast, clean close, UTF-8/emojis, rapid-fire virtual thread bursts, app teardown, and `/chat` HTTP serving.
5. Run `mvn clean test` across the whole repository and confirm that ALL tests (including baseline tests, core tests, and example tests) pass 100%.

## Mandatory Integrity Warning
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## 2026-09-11T13:24:41Z
You are worker_m4_gen3, implementing Milestone 4 of Bedrock Framework Version 2.0 (Real-Time Demo in bedrock-example).
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3
Workspace root is: c:\Users\roner\Documents\repo\bedrock-framework

