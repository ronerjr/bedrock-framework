## 2026-09-11T04:50:44Z
You are worker_m3, responsible for implementing Milestone 3 (BedrockApp Integration & Annotations) for Bedrock Framework Version 2.0 (Real-Time WebSockets RFC 6455).

Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3
Your workspace root is: c:\Users\roner\Documents\repo\bedrock-framework

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Key Reference Documents:
- c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md
- c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md
- c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_1\annotations_design.md (Annotations & Scanner design)
- c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_2\bedrockapp_design.md (BedrockApp integration design)
- c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3\test_and_tutorial_design.md (12-scenario test plan & Javadoc tutorials)

Your Exclusive Write Ownership:
1. bedrock-core/src/main/java/com/bedrock/core/ws/annotation/BedrockSocket.java
2. bedrock-core/src/main/java/com/bedrock/core/ws/annotation/OnOpen.java
3. bedrock-core/src/main/java/com/bedrock/core/ws/annotation/OnMessage.java
4. bedrock-core/src/main/java/com/bedrock/core/ws/annotation/OnClose.java
5. bedrock-core/src/main/java/com/bedrock/core/ws/annotation/OnError.java
6. bedrock-core/src/main/java/com/bedrock/core/ws/server/WebSocketEndpointScanner.java (or inner package as designed in explorer_m3_1)
7. bedrock-core/src/main/java/com/bedrock/core/BedrockApp.java (enableWebSockets, getWebSocketPort, register with IoC resolution for @BedrockSocket, stop, implements AutoCloseable)
8. bedrock-core/src/main/java/com/bedrock/ioc/BedrockContainer.java (add public <T> T get(Class<T> clazz) alias to getBean if needed)
9. bedrock-core/src/test/java/com/bedrock/core/BedrockAppWebSocketTest.java

Key Technical Invariants:
- Zero external runtime dependencies in bedrock-core/pom.xml (strict JDK 21 LTS only).
- All 5 annotations in com.bedrock.core.ws.annotation with retention RUNTIME and target TYPE / METHOD.
- WebSocketEndpointScanner validates method signatures (single multiplicity per event, flexible parameter permutations: Session, String, int, Throwable), throwing descriptive BedrockException on violations.
- BedrockApp manages both HttpServer and BedrockWebSocketServer.
- BedrockApp.enableWebSockets(int port) supports ephemeral port 0 (discovered via webSocketServer.getPort()).
- BedrockApp.register(Class<?>... classes) supports registration order invariance (can register socket classes before or after enableWebSockets).
- BedrockApp implements AutoCloseable. stop() and close() cleanly shut down both HTTP and WebSocket servers.
- Full 🎓 BEDROCK TUTORIAL Javadocs across all created classes and methods.
- Compile and run tests:
  mvn clean test-compile -pl bedrock-core
  mvn test -Dtest=BedrockAppWebSocketTest -pl bedrock-core
  mvn test -pl bedrock-core (verify all 96+ tests pass with zero failures)

Deliver your completion report at c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3\handoff.md and send a completion message back.
