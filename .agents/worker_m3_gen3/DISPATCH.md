# Worker M3 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3_gen3`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_1\annotations_design.md`
5. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_2\bedrockapp_design.md`
6. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3\test_and_tutorial_design.md`

## Mission
Implement Milestone 3 (BedrockApp Integration & Annotations):
1. Annotations in `com.bedrock.core.ws.annotation`:
   - `@BedrockSocket(value = "", path = "")`
   - `@OnOpen`
   - `@OnMessage`
   - `@OnClose`
   - `@OnError`
   with full `🎓 BEDROCK TUTORIAL` Javadocs.
2. `WebSocketEndpointScanner` in `com.bedrock.core.ws.server`:
   - Scans `@BedrockSocket` classes and validates signatures.
   - Extracts path, discovers `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError` methods, enforces parameter types, creates reflective bindings.
   - Fail-fast with `BedrockException` (Reason + Action) on invalid endpoints.
3. Update `BedrockContainer`:
   - Add alias `public <T> T get(Class<T> clazz)` delegating to `getBean(clazz)`.
4. Update `BedrockApp`:
   - Add `enableWebSockets(int port)`: validates port 0-65535, instantiates `BedrockWebSocketServer`, binds pending sockets, handles start if already running.
   - Add `stop()` implementing `AutoCloseable`: stops both HTTP and WebSocket servers cleanly.
   - Update `register(Class<?>... classes)`: detects `@BedrockSocket`, resolves via `BedrockContainer`, scans via `WebSocketEndpointScanner`, binds to WebSocket server if enabled or buffers in `pendingSocketClasses` for registration order invariance.
   - Accessors: `getWebSocketServer()`, `getWebSocketPort()`, `getPort()`, `getHttpServer()`.
5. Unit tests:
   - Implement `BedrockAppWebSocketTest` in `bedrock-core/src/test/java/com/bedrock/core/BedrockAppWebSocketTest.java` implementing the 12 scenarios from `test_and_tutorial_design.md`.
6. Run `mvn clean test` and verify that ALL existing tests (96+ tests) and all new tests pass 100%.

## Mandatory Integrity Warning
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## 2026-09-11T13:01:19Z
You are worker_m3_gen3, implementing Milestone 3 of Bedrock Framework Version 2.0.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3_gen3
Workspace root is: c:\Users\roner\Documents\repo\bedrock-framework

Implement Milestone 3 (BedrockApp Integration & Annotations):
1. Create annotations in bedrock-core/src/main/java/com/bedrock/core/ws/annotation/:
   - BedrockSocket.java (with String value() default "" and String path() default "")
   - OnOpen.java
   - OnMessage.java
   - OnClose.java
   - OnError.java
   Include comprehensive 🎓 BEDROCK TUTORIAL Javadoc explanations on all public classes and methods.
2. Create WebSocketEndpointScanner.java in bedrock-core/src/main/java/com/bedrock/core/ws/server/:
   - Scans and validates target instances annotated with @BedrockSocket.
   - Validates method signatures for @OnOpen, @OnMessage, @OnClose, @OnError.
   - Fails fast with BedrockException (descriptive reason and actionable suggestion) on invalid method declarations.
   - Binds to WebSocketEndpointBinding.ReflectiveEndpointBinding.
3. Update BedrockContainer.java in bedrock-core/src/main/java/com/bedrock/ioc/:
   - Add concise alias public <T> T get(Class<T> clazz) delegating to getBean(clazz).
4. Update BedrockApp.java in bedrock-core/src/main/java/com/bedrock/core/:
   - Implement AutoCloseable.
   - Add public synchronized BedrockApp enableWebSockets(int port).
   - Enhance public BedrockApp register(Class<?>... classes) to detect @BedrockSocket, resolve instances from BedrockContainer, scan with WebSocketEndpointScanner, and register to the WebSocket server (or queue for deferred binding if enableWebSockets is called later).
   - Add public synchronized void stop() to gracefully shut down both HttpServer and BedrockWebSocketServer.
   - Add accessors: getWebSocketServer(), getWebSocketPort(), getPort(), getHttpServer().
5. Create comprehensive tests in bedrock-core/src/test/java/com/bedrock/core/BedrockAppWebSocketTest.java:
   - Implement all 12 test scenarios described in explorer_m3_3/test_and_tutorial_design.md (basic lifecycle, DI injection, registration order invariance, invalid endpoints, stop lifecycle, ephemeral ports, etc.).
6. Build and verify:
   - Run mvn clean test and ensure 100% of tests pass (all existing tests + new tests).
7. Document your work:
   - Update progress.md in your working directory.
   - Write a detailed handoff.md in your working directory summarizing: what was implemented, files touched, test results with commands and output, and verification confirmation.
   - Send a message to parent when done.

