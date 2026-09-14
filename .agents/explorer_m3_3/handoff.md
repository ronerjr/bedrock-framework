# Handoff Report: Milestone 3 Test Plan & Pedagogical Tutorial Design

**Agent**: `explorer_m3_3`  
**Working Directory**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3`  
**Primary Deliverable**: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3\test_and_tutorial_design.md`  
**Date**: 2026-09-11  

---

## 1. Observation

1. **`BedrockApp.java` (lines 20-400)**:
   - `BedrockApp.java:29-37`: `BedrockApp` currently only initializes an internal HTTP `Router` and `BedrockContainer`. It has no `enableWebSockets(int port)` method and does not maintain a reference to `BedrockWebSocketServer`.
   - `BedrockApp.java:215-284`: `register(Class<?>... classes)` registers classes into `container.register(classes)` and scans specifically for `clazz.isAnnotationPresent(BedrockController.class)`. It does not yet inspect `@BedrockSocket` or bind reflective WebSocket handlers.
   - `BedrockApp.java:317-383`: `start()` creates and starts a `com.sun.net.httpserver.HttpServer` on a local variable without retaining a field reference. It has no `stop()` method and does not implement `AutoCloseable`.

2. **`BedrockWebSocketServer.java` (lines 95-276)**:
   - `BedrockWebSocketServer.java:116-131`: `BedrockWebSocketServer(int port)` accepts port `0` for dynamic OS ephemeral port allocation.
   - `BedrockWebSocketServer.java:199-211`: `getPort()` dynamically discovers and returns the bound port from `((InetSocketAddress) serverChannel.getLocalAddress()).getPort()`.
   - `BedrockWebSocketServer.java:220-238`: `start()` starts the server on virtual thread `"ws-accept-" + boundPort`.
   - `BedrockWebSocketServer.java:243-269`: `stop()` cleanly closes `serverChannel`, interrupts the accept loop, broadcasts `1001 (Going Away)` close frames to active sessions, and releases the TCP port.
   - `BedrockWebSocketServer.java:271-276`: Implements `AutoCloseable.close()`, delegating to `stop()`.

3. **`WebSocketEndpointBinding.java` (lines 36-209)**:
   - `WebSocketEndpointBinding.java:107-209`: Defines `ReflectiveEndpointBinding`, which caches `Method` references for `onOpen`, `onMessage`, `onClose`, and `onError`.
   - `WebSocketEndpointBinding.java:178-204`: `matchArgs` dynamically binds method parameters based on parameter types (`BedrockWebSocketSession`, `String`, `int`/`Integer`, `Throwable`).

4. **`BedrockContainer.java` (lines 20-233)**:
   - `BedrockContainer.java:74-80`: `register(Class<?>... classes)` resolves constructor dependencies in topological order.
   - `BedrockContainer.java:215-232`: `getBean(Class<T> clazz)` returns the instantiated singleton, ready to be passed to `BedrockWebSocketServer.registerEndpoint(...)`.

5. **`ORIGINAL_REQUEST.md` (lines 10-18, 35-49, 50-58)**:
   - "🎯 Princípio Central: Combater a mágica de anotações (sem caixas-pretas)"
   - "R3. API e Abstrações Pedagógicas (@BedrockSocket)"
   - "R4. Padrão Educacional Rígido (🎓 BEDROCK TUTORIAL)"

---

## 2. Logic Chain

1. **Test Isolation via Ephemeral Ports**:
   - From Observation 2 (`BedrockWebSocketServer:116-131` and `:199-211`), `BedrockWebSocketServer(0)` dynamically binds to an unused OS port, and `getPort()` returns this port once started.
   - Therefore, integration tests in `BedrockAppWebSocketTest.java` must configure `app.enableWebSockets(0)` to prevent TCP port collisions during parallel test execution in CI/CD.

2. **IoC Dependency Injection Chain**:
   - From Observation 4 (`BedrockContainer:74-80`), `container.register(...)` constructs dependency graphs.
   - When `app.register(GreetingServiceImpl.class, SampleChatSocket.class)` is called, `BedrockContainer` instantiates `GreetingServiceImpl` and injects it into `SampleChatSocket(GreetingService)`.
   - Then, `container.getBean(SampleChatSocket.class)` yields the singleton.
   - Therefore, our test plan specifically validates that `@OnOpen` uses the injected service to generate dynamic greetings, proving end-to-end IoC constructor injection for WebSockets.

3. **End-to-End Live Protocol Verification**:
   - By using Java 21 standard `HttpClient` (`java.net.http.WebSocket`) to connect to `ws://localhost:<dynamicPort>/chat`, the test exercises the full RFC 6455 protocol stack:
     - Real TCP handshake with HTTP 101 Switching Protocols
     - Dedicated Virtual Thread (`Thread.ofVirtual().name("ws-client-", ...)`)
     - 4-byte client XOR unmasking
     - Reflective parameter matching and method invocation without CGLIB/ByteBuddy proxies.

4. **Clean Server Teardown**:
   - From Observation 1 (`BedrockApp` lacked `stop()`) and Observation 2 (`BedrockWebSocketServer.stop()` frees ports and notifies clients with 1001 Going Away), `BedrockApp` must implement `AutoCloseable` and delegate `stop()` to both `HttpServer` and `BedrockWebSocketServer`.
   - Our test plan verifies both explicit `app.stop()` and `try-with-resources` (`app.close()`), ensuring zero socket or thread leaks.

5. **Pedagogical Alignment**:
   - From Observation 5, the primary design requirement is to demystify annotation magic.
   - Designing comprehensive Javadoc specifications with `🎓 BEDROCK TUTORIAL` headers for all 5 annotations and `BedrockApp` methods bridges the conceptual gap between high-level declarative annotations and byte-level RFC 6455 framing and Loom concurrency.

---

## 3. Caveats

1. **Dual Server HTTP Ephemeral Port Handling**:
   - In `BedrockApp`, if `BedrockApp.create(0)` is called, the underlying `com.sun.net.httpserver.HttpServer` also binds to an ephemeral HTTP port. `explorer_m3_2` should ensure `BedrockApp` exposes a way to discover the HTTP port if HTTP endpoints need to be queried over network in dual-stack tests (or test them via internal `Router`).
2. **IoC Resolution Order**:
   - Sockets must be registered along with their dependencies in `app.register(...)`. If a dependency is missing, `BedrockContainer` will throw `BedrockException` as designed.
3. **Execution Responsibility**:
   - As an explorer agent in read-only mode, I have designed and drafted the complete test plan and pedagogical tutorials in `test_and_tutorial_design.md`. Implementation into `bedrock-core` source files will be performed by the implementation worker (`worker_m3`).

---

## 4. Conclusion

- A comprehensive 12-scenario test plan for `BedrockAppWebSocketTest.java` has been designed and fully written with production-ready Java code in `test_and_tutorial_design.md`.
- Complete didactic Javadoc specifications following the `🎓 BEDROCK TUTORIAL` standard were authored for:
  - `@BedrockSocket`
  - `@OnOpen`
  - `@OnMessage`
  - `@OnClose`
  - `@OnError`
  - `BedrockApp.enableWebSockets(int port)`
  - `BedrockApp.register(Class<?>... classes)`
  - `BedrockApp.stop()` and `BedrockApp.close()`
- The design strictly fulfills the central project philosophy: "Combater a mágica de anotações (sem caixas-pretas)", contrasting Spring's dynamic runtime bytecode proxies with Bedrock's transparent reflection and Loom Virtual Threads.

---

## 5. Verification Method

### 1. Document Inspection
Inspect `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m3_3\test_and_tutorial_design.md`:
- Verify Section 2.5 contains the complete, compilable `BedrockAppWebSocketTest.java` class with 12 distinct test methods covering all required scenarios.
- Verify Section 3 contains complete `🎓 BEDROCK TUTORIAL` Javadoc blocks for all 5 annotations and 3 `BedrockApp` methods.

### 2. Implementation & Test Execution (by Worker)
Once `worker_m3` implements the annotations and `BedrockApp` methods:
```bash
# Compile bedrock-core
mvn clean test-compile -pl bedrock-core

# Execute the new BedrockAppWebSocketTest suite
mvn test -Dtest=BedrockAppWebSocketTest -pl bedrock-core

# Verify all baseline tests remain green
mvn test -pl bedrock-core
```

### 3. Invalidation Conditions
- Any scenario requiring hardcoded port numbers (e.g. 8080) instead of ephemeral port 0 invalidates the test plan.
- Relying on dynamic runtime bytecode generation (CGLIB/ByteBuddy) invalidates the architectural manifesto.
