# Handoff Report — WebSocket v2.0 Testing & Example Survey

**Agent**: `explorer_tests_1`  
**Working Directory**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_tests_1`  
**Handoff Type**: Hard (Task Complete)  
**Date**: 2026-09-11  

---

## 1. Observation

1. **Exact Test Count and Distribution**:
   - Grep search for `@Test` across the repository (`c:\Users\roner\Documents\repo\bedrock-framework`) located exactly **81 test methods** across 12 test classes:
     - `bedrock-core/src/test/java/com/bedrock/BedrockJsonEdgeCasesTest.java`: 27 tests (lines 26, 45, 63, 85, 95, 105, 118, 128, 140, 154, 162, 170, 181, 194, 202, 213, 224, 235, 244, 255, 266, 283, 301, 314, 323, 338, 355)
     - `bedrock-core/src/test/java/com/bedrock/BedrockJsonTest.java`: 8 tests (lines 32, 40, 46, 56, 65, 82, 102, 111)
     - `bedrock-core/src/test/java/com/bedrock/ContextValidationTest.java`: 9 tests (lines 19, 33, 50, 69, 83, 102, 114, 124, 144)
     - `bedrock-core/src/test/java/com/bedrock/BedrockJdbcTest.java`: 7 tests (lines 45, 66, 85, 98, 115, 127, 152)
     - `bedrock-core/src/test/java/com/bedrock/RouterTest.java`: 6 tests (lines 26, 39, 52, 76, 88, 97)
     - `bedrock-core/src/test/java/com/bedrock/GlobalExceptionHandlerTest.java`: 5 tests (lines 33, 54, 75, 97, 126)
     - `bedrock-core/src/test/java/com/bedrock/BedrockIoCInterfaceBindingTest.java`: 4 tests (lines 54, 70, 84, 95)
     - `bedrock-core/src/test/java/com/bedrock/ContextTest.java`: 4 tests (lines 22, 44, 58, 75)
     - `bedrock-core/src/test/java/com/bedrock/BedrockContainerTest.java`: 3 tests (lines 53, 67, 79)
     - `bedrock-core/src/test/java/com/bedrock/BedrockAppTest.java`: 1 test (line 51)
     - `bedrock-example/src/test/java/com/bedrock/example/UserControllerTest.java`: 5 tests (lines 30, 44, 63, 82, 97)
     - `bedrock-example/src/test/java/com/bedrock/example/UserRepositoryDatabaseTest.java`: 2 tests (lines 36, 78)
   - Subtotals: `bedrock-core` = 74 tests; `bedrock-example` = 7 tests. Total = 81 tests. This matches the 81 baseline tests mentioned in `ORIGINAL_REQUEST.md` line 70.

2. **Test Dependencies in `pom.xml`**:
   - `pom.xml` (root) lines 60–72 manages `junit-jupiter-api` (5.10.2) and `junit-jupiter-engine` (5.10.2).
   - `bedrock-core/pom.xml` lines 20–36 includes `junit-jupiter-api` (5.10.1), `mockito-core` (5.12.0), and `junit-jupiter-engine`.
   - `bedrock-example/pom.xml` lines 38–54 includes `junit-jupiter-api` (5.10.1), `junit-jupiter-engine`, `mockito-core` (5.12.0), and SQLite JDBC (`sqlite-jdbc` 3.45.3.0).
   - Grep search for `assertj` returned **0 results**. All assertions use `org.junit.jupiter.api.Assertions.*`.

3. **Existing HTTP Testing Approach**:
   - `BedrockAppTest.java` (lines 51–98), `ContextTest.java`, `ContextValidationTest.java`, and `UserControllerTest.java` all use `mock(HttpExchange.class)` to create synthetic requests.
   - None of the existing tests call `app.start()` or open real network ports.
   - Grep search for `HttpClient` across the repository returned **0 results**.
   - Inspection of `bedrock-core/src/main/java/com/bedrock/core/BedrockApp.java` (lines 317–383) shows `public void start()` creates and starts `HttpServer`, but `BedrockApp` has **no `stop()` or `close()` method**.

4. **`bedrock-example` Module**:
   - Contains an educational user management REST application (`Application.java`, `UserController.java`, `UserService.java`, `SqliteUserRepository.java`, etc.).
   - Configured with `BedrockJdbc` talking to `bedrock.db`.
   - Has interactive REST playground capabilities inspired by `BedrockPlayground.java` (which serves an HTML/CSS/JS dark-theme SPA via a Java 21 Text Block).

5. **`ROADMAP.md`**:
   - Lines 46–53 list Version 2.0 Real-Time WebSockets as incomplete:
     - `## ⚡ Versão 2.0 - *Comunicação em Tempo Real (WebSockets)*`
     - `- [ ] **Motor WebSockets (RFC 6455) do Zero:** ...`
     - `- [ ] **Anotação de Real-Time (@BedrockSocket):** ...`

---

## 2. Logic Chain

1. **Test Suite Baseline**:
   - Because the framework currently has 81 existing tests passing across both modules, any code changes for v2.0 must preserve 100% pass rate for all 81 tests (`mvn test`).
   - Because AssertJ is not on the classpath and zero external dependencies must be maintained in `bedrock-core`, all new tests must strictly use standard JUnit 5 assertions (`org.junit.jupiter.api.Assertions`) and Mockito if mocking is needed.

2. **Server Lifecycle Gap**:
   - Because integration tests will launch `BedrockWebSocketServer` (and optionally `BedrockApp`) on network ports, leaving servers running after test completion would cause port collisions and thread leaks.
   - Therefore, `BedrockWebSocketServer` and `BedrockApp` must expose a `stop()` method and/or implement `AutoCloseable` for clean `try-with-resources` or `@AfterEach` teardown in test suites.

3. **Multi-Tiered WebSocket Testing with JDK 21**:
   - `java.net.http.HttpClient` with `newWebSocketBuilder()` provides a clean, fully compliant client out of the box in the JDK standard library. It validates HTTP 101 status, `Sec-WebSocket-Accept` computation, frame transmission, and graceful closure.
   - However, standard HTTP clients cannot be forced to send unmasked client frames, bad RSV bits, unknown opcodes, or fragmented control frames.
   - RFC 6455 mandates strict server behavior for protocol violations (e.g. §5.1 requires closing the connection with status code 1002 upon receiving an unmasked client frame).
   - Therefore, a dual-client testing strategy is necessary:
     1. High-level integration tests via `java.net.http.HttpClient` for happy-path bidirectional messaging, broadcasting, and standard ping/pong.
     2. Low-level wire protocol tests via `java.net.Socket` / `SocketChannel` for RFC 6455 edge cases, malformed frames, masking enforcement, and custom status codes.
     3. Pure unit tests without networking for SHA-1/Base64 test vectors (RFC 6455 §1.3) and binary frame serialization/deserialization.

4. **Pedagogical Example & DX**:
   - `bedrock-example` already demonstrates REST, JDBC, IoC, and validation. Adding `ChatWebSocket` annotated with `@BedrockSocket(path = "/chat")` will complete the real-time showcase.
   - Exposing an interactive dark-themed HTML/JS client at `/chat` (mirroring `BedrockPlayground`) allows users to visually observe WebSocket frames and virtual thread processing without needing external tools.

---

## 3. Caveats

1. **Execution Restriction**: The tool `run_command` timed out waiting for user approval on `mvn test`. However, static analysis of all test files confirmed the exact test count (81 `@Test` annotations) and complete dependency tree.
2. **Ephemeral Port Assumption**: In integration tests, servers should bind to port 0 (ephemeral) or dedicated test ports (e.g., 9091) to prevent conflicts if port 8080/8081 is already bound on the host machine.
3. **No External Network Dependencies**: The test strategy relies strictly on `localhost` loopback sockets; no external internet access or external mock servers are required.

---

## 4. Conclusion

1. **Test Suite Status**: 81 existing tests across 12 files must be preserved with zero regression.
2. **Zero Dependency Compliance**: WebSocket v2.0 testing can be completely implemented using only JDK 21 standard library APIs (`java.net.http.*`, `java.net.*`, `java.nio.*`, `java.security.*`).
3. **Testing Strategy**: Implement three tiers of tests:
   - `WebSocketHandshakeTest` & `WebSocketFrameTest` (unit tests & RFC 6455 vectors)
   - `BedrockWebSocketE2ETest` (live integration client via `HttpClient.newWebSocketBuilder()`)
   - `BedrockWebSocketRawFrameTest` (wire-level protocol violation tests via `Socket`)
4. **Lifecycle Requirement**: Implement `stop()` on `BedrockWebSocketServer` and `BedrockApp`.
5. **Example Architecture**: Implement `ChatWebSocket` in `bedrock-example`, register it via `app.enableWebSockets(8081).register(ChatWebSocket.class)`, and provide an embedded `/chat` HTML playground.
6. **Roadmap**: Update `ROADMAP.md` marking Version 2.0 as complete (`[x]`).

---

## 5. Verification Method

1. **Survey File Inspection**:
   - Inspect `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_tests_1\test_survey.md`.
2. **Count Verification**:
   - Search for `@Test` across `bedrock-core/src/test` and `bedrock-example/src/test` to verify 81 test annotations.
3. **Test Execution Command** (once permission granted):
   ```bash
   mvn test
   ```
   Expect: 81 tests run, 0 failures, 0 errors.
4. **Invalidation Conditions**:
   - If any test in the current suite fails or is deleted.
   - If any external dependency (such as AssertJ, OkHttp, Tyrus) is added to `bedrock-core/pom.xml`.
