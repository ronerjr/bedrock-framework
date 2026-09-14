## 2026-09-11T04:45:00Z
You are explorer_m3_3, an Explorer for Milestone 3: BedrockApp Integration & Annotations.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md.

Your Mission:
Design:
1. Complete Test Plan for `bedrock-core/src/test/java/com/bedrock/core/BedrockAppWebSocketTest.java`:
   - Testing `app.enableWebSockets(0)` (ephemeral port) + `app.register(SampleSocket.class)` + `app.start()`.
   - Testing IoC dependency injection: injecting a service bean (e.g. `GreetingService`) into `SampleSocket` constructor.
   - Testing live WebSocket connection via Java 21 `HttpClient` to the registered path (e.g. `/chat`).
   - Testing `@OnOpen` callback execution (session greeting received).
   - Testing `@OnMessage` callback execution (echo / broadcast).
   - Testing `@OnClose` callback execution.
   - Testing `@OnError` callback execution.
   - Testing `app.stop()` and `app.close()` in try-with-resources.
2. Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc):
   - Design didactic Javadocs for `@BedrockSocket`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`, and `BedrockApp.enableWebSockets()`:
     * Explaining Bedrock's core philosophy: "Combater a mágica de anotações (sem caixas pretas)".
     * Contrasting Spring `@ServerEndpoint` / `@EnableWebSocket` dynamic runtime bytecode proxies with Bedrock's explicit registration, IoC container instantiation, and transparent reflection.

Deliverables:
- Write detailed design to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3\test_and_tutorial_design.md`
- Write `handoff.md` in your working directory.
- Send completion message to parent orchestrator.
