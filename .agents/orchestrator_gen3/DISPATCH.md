## 2026-09-11T13:00:00Z

<USER_REQUEST>
You are the Successor Project Orchestrator (Generation 3) for implementing Bedrock Framework Version 2.0 (Real-Time WebSockets RFC 6455).

Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\orchestrator_gen3
The workspace root is: c:\Users\roner\Documents\repo\bedrock-framework
The authoritative user request is in: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md
The project plan is in: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md
The test plan is in: c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md
Predecessors' handoffs & briefings:
- c:\Users\roner\Documents\repo\bedrock-framework\.agents\orchestrator_1\handoff.md
- c:\Users\roner\Documents\repo\bedrock-framework\.agents\orchestrator_gen2\BRIEFING.md

State of the project:
- Phase 0 (Survey) and Phase 1 (Decomposition) are DONE.
- Milestone 1 (RFC 6455 Protocol Engine) is DONE & VERIFIED (Gate: PASS).
- Milestone 2 (Virtual-Threaded NIO Server & Sessions) is DONE & VERIFIED (Gate: PASS).
- Milestone 3 explorations were completed in .agents/explorer_m3_1, explorer_m3_2, explorer_m3_3.

Your immediate responsibilities:
1. Execute Milestone 3 (BedrockApp Integration & Annotations):
   - Annotations: @BedrockSocket(path), @OnOpen, @OnMessage, @OnClose, @OnError in com.bedrock.core.ws.annotation
   - BedrockApp: enableWebSockets(int port), app.register(...) IoC binding for socket classes, AutoCloseable stop()
   - Unit tests (BedrockAppWebSocketTest) & Gate verification
2. Execute Milestone 4 (bedrock-example Real-Time Demo):
   - ChatWebSocket endpoint and HTML/JS client page at /chat
3. Execute Milestone 5 (Full E2E Pass, Hardening & Final Verification):
   - All 81 baseline tests + new tests must pass (mvn clean verify) with 100% success
   - Update ROADMAP.md marking Version 2.0 as complete ([x])
4. When complete, send a final completion report back to the Sentinel so Victory Audit can be executed.
</USER_REQUEST>
