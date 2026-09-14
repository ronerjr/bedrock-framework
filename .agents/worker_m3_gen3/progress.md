# Progress - worker_m3_gen3

Last visited: 2026-09-11T13:08:00Z

## Status
- [x] Read mandatory documentation (ORIGINAL_REQUEST, PROJECT, TEST_INFRA, DISPATCH, design docs)
- [x] Setup BRIEFING.md and progress.md
- [x] Implement declarative annotations in `com.bedrock.core.ws.annotation`:
  - `BedrockSocket.java` (value default "", path default "")
  - `OnOpen.java`
  - `OnMessage.java`
  - `OnClose.java`
  - `OnError.java`
  - Complete with 🎓 BEDROCK TUTORIAL Javadoc specifications
- [x] Implement `WebSocketEndpointScanner.java` in `com.bedrock.core.ws.server`:
  - Scans and validates target instances annotated with `@BedrockSocket`
  - Validates method signatures for `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`
  - Enforces multiplicity and parameter type restrictions
  - Fails fast with `BedrockException` (reason + action)
  - Constructs `WebSocketEndpointBinding.ReflectiveEndpointBinding`
- [x] Update `BedrockContainer.java` in `com.bedrock.ioc`:
  - Added concise alias `public <T> T get(Class<T> clazz)` delegating to `getBean(clazz)`
- [x] Update `BedrockApp.java` in `com.bedrock.core`:
  - Implements `AutoCloseable`
  - Added `enableWebSockets(int port)`
  - Enhanced `register(Class<?>... classes)` to scan `@BedrockSocket` and support registration order invariance
  - Added `stop()` for coordinated HTTP and WebSocket server shutdown
  - Added accessors: `getWebSocketServer()`, `getWebSocketPort()`, `getPort()`, `getHttpServer()`, `getWsPort()`, `isRunning()`
- [x] Implement `BedrockAppWebSocketTest.java` in `bedrock-core/src/test/java/com/bedrock/core/`:
  - Covers all 12 scenarios from `test_and_tutorial_design.md`
  - Covers 8 additional fail-fast validation and edge-case scenarios
  - Uses ephemeral port 0 and clean try-with-resources / try-finally teardown
- [ ] Prepare handoff report and notify parent

