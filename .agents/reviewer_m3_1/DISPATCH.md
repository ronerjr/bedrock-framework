# Reviewer M3-1 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m3_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3_gen3\handoff.md`

## Mission
Review Milestone 3 (BedrockApp Integration & Annotations):
1. Verify all 5 annotations in `com.bedrock.core.ws.annotation` (`BedrockSocket`, `OnOpen`, `OnMessage`, `OnClose`, `OnError`).
2. Verify `WebSocketEndpointScanner`: validation logic, reflection bindings, `BedrockException` (Reason + Action) error formatting.
3. Verify `BedrockContainer` alias `get(Class<T>)`.
4. Verify `BedrockApp` integration: `enableWebSockets(int)`, `register(Class<?>...)`, registration order invariance, `stop()`, `AutoCloseable`, port discovery.
5. Verify Javadoc standard: all new public classes and methods must feature `🎓 BEDROCK TUTORIAL` headers.
6. Verify zero third-party runtime dependencies in `bedrock-core/pom.xml`.
7. Run build/test verification:
   `mvn test -Dtest=BedrockAppWebSocketTest -pl bedrock-core`
   and all core tests.
8. Produce a structured `handoff.md` with a clear verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:09:29Z
You are reviewer_m3_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m3_1. Read the instructions in DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Review Milestone 3 implementation thoroughly, run tests via maven, check code quality and Javadoc tutorial standard, and output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.
