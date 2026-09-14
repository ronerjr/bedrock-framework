# Handoff Report: Bedrock Core Investigation for WebSockets v2.0

**Agent:** `explorer_core_1` (Codebase Explorer)  
**Date:** 2026-09-11  
**Target:** `parent` (Orchestrator: `77cf56d8-3c86-4c29-988f-a89e17f229f1`)  
**Deliverable File:** `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1\core_survey.md`

---

## 1. Observation

1. **Root POM & Compiler Version**:
   - In `c:\Users\roner\Documents\repo\bedrock-framework\pom.xml` (lines 43-47):
     ```xml
     <properties>
         <maven.compiler.source>21</maven.compiler.source>
         <maven.compiler.target>21</maven.compiler.target>
         <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
     </properties>
     ```
   - Maven compiler plugin is version `3.13.0` targeting Java 21.

2. **Core Dependencies (Zero-Dependency Constraint)**:
   - In `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\pom.xml` (lines 18-37):
     Dependencies are strictly `test` scope (`junit-jupiter-api:5.10.1`, `mockito-core:5.12.0`, `junit-jupiter-engine`).
     There are zero compile-time or runtime third-party dependencies.

3. **HTTP Server Engine Mechanics**:
   - In `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\BedrockApp.java` (lines 317-325):
     ```java
     HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
     server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
     ```
   - The existing HTTP server utilizes `com.sun.net.httpserver.HttpServer`.
   - `HttpServer` and `HttpExchange` do not provide access to the raw underlying `SocketChannel` or permit socket hijacking / persistent duplex byte streaming required for RFC 6455 WebSockets.

4. **Component Registration & IoC Container**:
   - In `BedrockApp.java` (lines 215-284):
     `app.register(Class<?>... classes)` registers classes into `BedrockContainer` (resolving constructor dependencies in topological order), scans for `@BedrockController`, inspects methods for `@BedrockGet`, `@BedrockPost`, etc., and binds handlers to `Router`.
   - In `BedrockContainer.java` (lines 20-234):
     Manages singletons in `ConcurrentHashMap<Class<?>, Object> beans`. Resolves constructor parameters recursively, detects circular dependencies via `Set<Class<?>> resolving`, and supports `container.bind(Interface.class, Implementation.class)` and `container.registerInstance(Class<T>, T instance)`.

5. **Existing Thread Management**:
   - Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) are already used for dispatching HTTP requests in `BedrockApp.java` line 324.

6. **Pedagogical Javadoc Standard**:
   - 32 occurrences of `🎓 BEDROCK TUTORIAL` were found across `bedrock-core` and `bedrock-example` (e.g. `BedrockJdbc.java` lines 12-26, `BedrockApp.java` lines 103, 118, 130, 209, `UserController.java` lines 9, 20, 30, 41, 61, 73, 88).
   - Standard structure:
     - Header: `/** 🎓 BEDROCK TUTORIAL: [Title]`
     - Contrasting modern framework abstractions (Spring/Hibernate) against low-level physical reality.
     - Step-by-step mechanical explanation (bytes, sockets, bitmasking, thread allocation).
     - Code snippets inside `<pre>{@code ... }</pre>`.

7. **Test Suite Baseline**:
   - 12 test classes containing 81 test methods across `bedrock-core` and `bedrock-example` covering v1.1, v1.2, and v1.3 functionality.

---

## 2. Logic Chain

1. **Port Coexistence vs. Independent Port**:
   - *From Observation 3*: `BedrockApp` uses `com.sun.net.httpserver.HttpServer`. `HttpExchange` does not allow upgrading or taking over the underlying TCP socket channel for persistent bi-directional binary framing.
   - *Therefore*: A WebSocket server cannot run inside the existing `HttpServer` instance on the same HTTP port without third-party libraries or internal hacks.
   - *Furthermore*: Running an independent `BedrockWebSocketServer` on a dedicated port via `app.enableWebSockets(int wsPort)` allows using pure `java.nio.channels.ServerSocketChannel`, directly meeting requirement R1 and R2 while leaving existing HTTP functionality intact.

2. **Integration into `BedrockApp` and IoC**:
   - *From Observation 4*: `app.register(...)` is already the unified entry point for application components.
   - *Therefore*: `app.register(...)` should be extended to inspect `clazz.isAnnotationPresent(BedrockSocket.class)`. When found, `BedrockContainer` instantiates the endpoint as a managed singleton (enabling dependency injection of repositories/services into sockets), and registers it with `BedrockWebSocketServer`.
   - *To prevent ordering issues*: `BedrockApp` should store pending socket classes if `register(...)` is called before `enableWebSockets(port)`, binding them when `enableWebSockets` is invoked.

3. **Concurrency with Virtual Threads (Project Loom)**:
   - *From Observation 3 and 5*: The framework is strictly built on Java 21 and leverages Virtual Threads for HTTP.
   - *Therefore*: For WebSockets, `BedrockWebSocketServer`'s accept loop should launch each accepted `SocketChannel` into a dedicated virtual thread (`Thread.ofVirtual().name("ws-client-", id).start(...)`).
   - *Benefit*: This enables synchronous blocking reads on the `SocketChannel` without blocking OS threads and without the complexity of NIO Selectors or Netty event loops.

4. **Zero-Dependency Constraint Compliance**:
   - *From Observation 2*: `bedrock-core/pom.xml` has zero external runtime dependencies.
   - *Verification*: Handshake SHA-1 hashing (`MessageDigest`), Base64 encoding (`Base64.getEncoder()`), byte manipulation (`ByteBuffer`), network channels (`ServerSocketChannel`, `SocketChannel`), and concurrency (`ConcurrentHashMap`, `Thread.ofVirtual`) are 100% available in `java.base` in JDK 21.
   - *Conclusion*: Zero external dependencies are required.

---

## 3. Caveats

- **Network Port Selection in Tests**: Integration tests that spin up `BedrockApp` and `BedrockWebSocketServer` must avoid hardcoded port collisions. They should use dynamic/ephemeral ports (e.g. port `0` or test-specific ports like `8085`) and ensure `server.stop()` is called in `@AfterEach`.
- **Large Payloads (> 65,535 bytes)**: RFC 6455 64-bit extended payload lengths require handling 8-byte length fields. The implementation must guard against memory exhaustion if clients claim multi-gigabyte payloads.
- **Fragmentation (FIN = 0)**: In initial v2.0, focus should be on unfragmented frames (FIN = 1), with clear pedagogical documentation explaining frame continuation (Opcode 0x0).

---

## 4. Conclusion

The existing `bedrock-core` codebase is exceptionally clean, well-tested, and perfectly positioned for the Versão 2.0 WebSockets RFC 6455 implementation.
- `BedrockWebSocketServer` can be cleanly integrated via `app.enableWebSockets(int port)` and `app.register(ChatSocket.class)`.
- All protocol mechanics (Handshake 101, SHA-1 + GUID, Base64, Frame binary masks, XOR unmasking, Ping/Pong, Close) can be implemented using pure JDK 21 standard libraries.
- The `🎓 BEDROCK TUTORIAL` pedagogical format is well-established and ready to be adopted for WebSocket documentation.
- The detailed survey has been documented in `c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1\core_survey.md`.

---

## 5. Verification Method

To independently verify these findings:
1. **Inspect POM Files**:
   - `view_file` on `pom.xml` and `bedrock-core/pom.xml` to verify Java 21 properties and dependency-free runtime.
2. **Inspect Server & IoC Mechanics**:
   - `view_file` on `bedrock-core/src/main/java/com/bedrock/core/BedrockApp.java` (lines 215-284, lines 317-376) to verify `HttpServer` usage and `register()` mechanics.
   - `view_file` on `bedrock-core/src/main/java/com/bedrock/ioc/BedrockContainer.java` to verify constructor injection and bean resolution.
3. **Inspect Pedagogical Convention**:
   - `grep_search` for `🎓 BEDROCK TUTORIAL` across `bedrock-core` and `bedrock-example` to verify Javadoc headers and structure.
4. **Invalidation Conditions**:
   - If `com.sun.net.httpserver.HttpExchange` provided an official, portable mechanism to hijack the underlying raw TCP socket channel, a single-port model would be viable without `ServerSocketChannel`. (Observation 3 proves it does not).
