# Explorer M4-1 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-example\src\main\java\com\bedrock\example\Application.java`
5. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\ws\annotation\BedrockSocket.java`

## Mission
Explore and design Milestone 4 (bedrock-example Real-Time Demo) backend components:
1. Design `com.bedrock.example.ws.ChatWebSocket`:
   - Route path: `@BedrockSocket(path = "/ws/chat")` or `/chat`.
   - Managing connected sessions (thread-safe set/map of `BedrockWebSocketSession`).
   - `@OnOpen`: track session, broadcast join message (e.g. `[System] User connected. Total users: N`).
   - `@OnMessage`: broadcast user message to all connected sessions (e.g. `[User <id>] <message>`).
   - `@OnClose`: remove session, broadcast departure message.
   - `@OnError`: log errors safely.
   - Tutorial Javadocs (`🎓 BEDROCK TUTORIAL`) explaining multi-client broadcasting under Project Loom.
2. Design `Application.java` updates in `bedrock-example`:
   - Enable WebSockets: `app.enableWebSockets(8081)` or dual-port / ephemeral port in tests.
   - Register `ChatWebSocket.class`.
   - Serve `/chat` HTML client page via `app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml()));`.
3. Document detailed design in `chat_websocket_design.md` and summarize in `handoff.md`.

## 2026-09-11T13:19:28Z
You are explorer_m4_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_1. Read the instructions in DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Explore and design Milestone 4 backend components: ChatWebSocket.java with @BedrockSocket, @OnOpen, @OnMessage, @OnClose, @OnError, multi-client broadcast, and Application.java registration. Write chat_websocket_design.md and handoff.md in your working directory. Send a message to parent when done.

