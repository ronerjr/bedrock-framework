# Reviewer M4-1 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m4_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3\handoff.md`
5. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-example\src\main\java\com\bedrock\example\ws\ChatWebSocket.java`
6. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-example\src\main\java\com\bedrock\example\ws\ChatPlayground.java`
7. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-example\src\main\java\com\bedrock\example\Application.java`
8. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-example\src\test\java\com\bedrock\example\ChatWebSocketTest.java`

## Mission
Review Milestone 4 (bedrock-example Real-Time Demo):
1. Review `ChatWebSocket.java`: `@BedrockSocket("/chat")`, session management, broadcast logic, error boundaries, `/nick` command handling, and `🎓 BEDROCK TUTORIAL` Javadocs.
2. Review `ChatPlayground.java`: zero external dependencies, dark-theme HTML/CSS/JS text block, dynamic URL resolution, UI controls, RFC 6455 educational inspector.
3. Review `Application.java`: dual-server configuration (HTTP 8080 / WS 8081), `createApp(httpPort, wsPort)` factory method, backward compatibility with REST endpoints and SQLite database.
4. Review `ChatWebSocketTest.java`: 8 integration tests with standard `HttpClient` on ephemeral port 0.
5. Check build/test integrity and ensure no regressions on existing example tests (`UserControllerTest`, `UserRepositoryDatabaseTest`).
6. Produce a structured `handoff.md` with explicit verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:31:35Z
You are reviewer_m4_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m4_1. Read DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Review Milestone 4 implementation (ChatWebSocket, ChatPlayground, Application, ChatWebSocketTest), check contracts, tutorial Javadocs, and output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.
