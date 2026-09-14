# Explorer M4-2 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_2`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\BedrockPlayground.java`

## Mission
Explore and design Milestone 4 (bedrock-example Real-Time Demo) frontend UI & integration tests:
1. Design `com.bedrock.example.ws.ChatPlayground`:
   - Returns a responsive, dark-themed HTML/CSS/JS page (using Java Text Blocks, matching Bedrock's sleek design system in `BedrockPlayground.java`).
   - Dynamic WebSocket connection URL detection (`ws://` or `wss://` based on `window.location`).
   - Interactive chat UI: connection status indicator (Connected/Disconnected/Connecting), username input, message history log with timestamps, message input box, and "Send" button.
   - Live bidirectional event handling: sending text frames, receiving incoming broadcasts, auto-scrolling message history.
2. Design `com.bedrock.example.ChatWebSocketTest`:
   - Integration test running in `bedrock-example/src/test/java/com/bedrock/example/`.
   - Uses JDK 21 `java.net.http.HttpClient` with `newWebSocketBuilder()` or raw socket client against ephemeral port 0.
   - Tests:
     - WebSocket connection establishment.
     - Sending a chat message and asserting broadcast delivery.
     - Multiple simultaneous clients receiving broadcasts.
     - Clean disconnection.
3. Document detailed design in `chat_playground_and_tests_design.md` and summarize in `handoff.md`.

## 2026-09-11T13:19:29Z
You are explorer_m4_2. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m4_2. Read the instructions in DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Explore and design Milestone 4 frontend UI and integration tests: ChatPlayground.java with dark-theme HTML/JS text block, and ChatWebSocketTest.java with HttpClient WebSocket integration tests. Write chat_playground_and_tests_design.md and handoff.md in your working directory. Send a message to parent when done.

