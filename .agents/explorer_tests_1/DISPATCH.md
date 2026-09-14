## 2026-09-11T03:56:00Z
You are explorer_tests_1, a Tests and Example Explorer.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_tests_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).

Your Mission:
Investigate the existing test suite, `bedrock-example` module, and testing strategies for WebSocket v2.0:
1. Existing Test Suite:
   - How many tests currently exist across modules? (ORIGINAL_REQUEST mentions 81 existing tests). Run `mvn test-compile` or inspect test directories to understand the structure.
   - What testing libraries are in pom.xml? (JUnit 5, AssertJ, Mockito?)
   - How are existing HTTP tests written? (Do they use java.net.http.HttpClient, mock requests, or raw sockets?)
2. WebSocket Testing Capabilities:
   - How can we test WebSockets comprehensively using JDK 21 standard library?
   - Does `java.net.http.HttpClient.newWebSocketBuilder()` work as a test client against BedrockWebSocketServer?
   - Can we also test raw byte-level frame transmission with `SocketChannel` or `Socket` to test malformed frames, masking violations, fragmented frames, ping/pong, and close status codes?
3. `bedrock-example` Module:
   - What currently exists in `bedrock-example`?
   - How is the example configured and executed?
   - How should the WebSocket example (e.g. `ChatWebSocket` / telemetry) be designed to demonstrate `@BedrockSocket` and bidirectional real-time communication?
   - Can we include a clean HTML/JS client or simple interactive demo?
4. ROADMAP.md & Acceptance Criteria:
   - Check `ROADMAP.md`. Where is Version 2.0 mentioned? What checkboxes need updating?
   - What are the exact acceptance criteria and how will each be verified?

Deliverables:
- Write your detailed analysis to: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_tests_1\test_survey.md
- Write handoff.md in your working directory.
- Send a completion message to the parent orchestrator.
