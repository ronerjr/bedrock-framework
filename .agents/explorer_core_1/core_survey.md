# Bedrock Core Survey & Architectural Analysis (v2.0 WebSockets Extension)

**Author:** `explorer_core_1` (Codebase Explorer)  
**Target Module:** `bedrock-core` (Root: `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core`)  
**Mission:** Comprehensive architectural investigation of the existing codebase to guide the implementation of Versão 2.0 (Real-Time WebSockets RFC 6455) with Zero Dependencies, Java 21 Virtual Threads, and the strict pedagogical standard `🎓 BEDROCK TUTORIAL`.

---

## 1. Executive Summary & Verification Findings

| Architectural Dimension | Current State (v1.3.0) | Target State (v2.0 WebSockets) | Compliance / Risk |
|---|---|---|---|
| **Language & Runtime** | Java 21 LTS (`maven.compiler.source/target: 21`) | Java 21 LTS (Virtual Threads, Pattern Matching, Records) | Full compliance; zero version drift. |
| **Dependencies in Core** | 0 compile/runtime dependencies. Only JUnit 5 & Mockito in test scope. | 0 compile/runtime dependencies. Pure JDK 21 standard library (`java.nio`, `java.net`, `java.security`). | Full compliance with zero-dependency mandate. |
| **HTTP Server Engine** | `com.sun.net.httpserver.HttpServer` with Virtual Thread Executor (`Executors.newVirtualThreadPerTaskExecutor()`). | Retain `HttpServer` for REST/HTTP. Add independent `BedrockWebSocketServer` on `ServerSocketChannel`. | Decoupled architecture: cannot hijack `HttpExchange` sockets; independent port avoids port conflicts. |
| **Routing Architecture** | `Router` with static exact match $O(1)$ and dynamic path segments $O(N)$ with `{param}` extraction. | Dedicated WebSocket routing table in `BedrockWebSocketServer` mapping paths (e.g. `/chat`) to endpoint handlers. | Fully compatible; no interference with HTTP routes. |
| **IoC Engine** | `BedrockContainer`: constructor injection, singleton management, interface binding (`app.bind`), instance registration (`app.registerInstance`). | WebSocket endpoint classes registered in container via `app.register(ChatSocket.class)`; dependencies injected via constructors. | Endpoints participate natively in IoC container as managed singletons. |
| **Concurrency Model** | HTTP uses `Executors.newVirtualThreadPerTaskExecutor()`. | WebSockets: Dedicated Virtual Thread per connected client (`Thread.ofVirtual().name("ws-client-", ...)`). | Pure Project Loom, synchronous blocking I/O on `SocketChannel`, zero reactive framework overhead. |
| **Pedagogical Standard** | Ubiquitous `🎓 BEDROCK TUTORIAL` Javadocs detailing low-level mechanics and comparing them against Spring/Hibernate. | Add `🎓 BEDROCK TUTORIAL` headers covering Handshake 101, SHA-1/Base64, Frame bitmasking, XOR unmasking, and Loom concurrency. | Preserves the project's educational mission. |
| **Regression Guard** | 81 tests passing across 12 test classes (v1.1, v1.2, v1.3). | All 81 existing tests must remain 100% green without modification. | Non-breaking additive extension. |

---

## 2. Architecture & Build Configuration (`pom.xml`)

### 2.1 Root `pom.xml` Inspection
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\pom.xml`
- **GAV**: `io.github.ronerjr:bedrock-parent:1.3.0` (Packaging: `pom`)
- **Modules declared**:
  - `bedrock-core`
  - `bedrock-example`
- **Compiler Properties** (lines 43-47):
  ```xml
  <properties>
      <maven.compiler.source>21</maven.compiler.source>
      <maven.compiler.target>21</maven.compiler.target>
      <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  ```
- **Plugins**:
  - `maven-compiler-plugin:3.13.0` targeting Java 21.
  - `maven-surefire-plugin:3.2.5` for test execution.
  - `maven-source-plugin:3.3.0` attaching sources.
  - `maven-javadoc-plugin:3.6.3` with `<failOnError>false</failOnError>` and `<doclint>none</doclint>`.
  - Release profile with GPG signing and `central-publishing-maven-plugin:0.11.0`.

### 2.2 Core Module `bedrock-core/pom.xml` Inspection
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\pom.xml`
- **GAV**: `io.github.ronerjr:bedrock-core:1.3.0` (Packaging: `jar`)
- **Dependencies** (lines 18-37):
  ```xml
  <dependencies>
      <!-- Test Dependencies ONLY -->
      <dependency>
          <groupId>org.junit.jupiter</groupId>
          <artifactId>junit-jupiter-api</artifactId>
          <version>5.10.1</version>
          <scope>test</scope>
      </dependency>
      <dependency>
          <groupId>org.mockito</groupId>
          <artifactId>mockito-core</artifactId>
          <version>5.12.0</version>
          <scope>test</scope>
      </dependency>
      <dependency>
          <groupId>org.junit.jupiter</groupId>
          <artifactId>junit-jupiter-engine</artifactId>
          <scope>test</scope>
      </dependency>
  </dependencies>
  ```
- **Zero-Dependency Constraint**: There are **zero** runtime or compile dependencies in `bedrock-core`. No Jackson, no Netty, no SLF4J, no Spring.
- **Verdict for v2.0**: No new dependencies should be added to `bedrock-core/pom.xml`. All WebSocket protocol mechanics (SHA-1 hashing, Base64 encoding, NIO channels, byte buffers, bitwise manipulation) are available out of the box in `java.base` (JDK 21).

### 2.3 Example Module `bedrock-example/pom.xml` Inspection
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-example\pom.xml`
- Contains `sqlite-jdbc:3.45.3.0` for disk-based SQLite demonstrations.
- Will house the practical WebSocket chat demonstration (`ChatWebSocket`) and interactive HTML/JS testing page.

---

## 3. Server Startup & HTTP Engine Mechanics

### 3.1 BedrockApp Lifecycle (`BedrockApp.java`)
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\BedrockApp.java`

1. **Factory Method & Instantiation** (lines 29-44):
   ```java
   private BedrockApp(int port) {
       this.port = port;
       this.router = new Router();
       this.container = new BedrockContainer();
       
       // Built-in Developer Experience (DX) endpoints
       this.router.addRoute("GET", "/bedrock/ui", ctx -> ctx.html(BedrockPlayground.getHtml()));
       this.router.addRoute("GET", "/bedrock/api/routes", ctx -> ctx.ok(this.router.getRegisteredRoutes()));
   }

   public static BedrockApp create(int port) {
       return new BedrockApp(port);
   }
   ```
2. **Server Execution & Loom Dispatch** (lines 317-376):
   ```java
   public void start() {
       try {
           HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
           
           // PERFORMANCE MAGIC: Natively injected Virtual Threads!
           server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
           
           server.createContext("/", exchange -> {
               Map<String, String> pathParams = new HashMap<>();
               Context ctx = new Context(exchange, pathParams);
               ...
           });
           
           server.start();
           printBanner();
       } catch (IOException e) { ... }
   }
   ```

### 3.2 Key Technical Finding: Why WebSockets Require an Independent Server
- The HTTP server in `bedrock-core` is built on JDK's built-in `com.sun.net.httpserver.HttpServer`.
- `HttpServer` exposes only `HttpExchange`. Crucially, `HttpExchange`:
  1. Does **not** expose the underlying `SocketChannel` or `Socket`.
  2. Does **not** permit connection hijacking to keep a raw full-duplex TCP stream open after writing response headers.
  3. Enforces request/response lifecycle semantics: once `sendResponseHeaders` and body write/close occur, the exchange terminates.
- **Architectural Conclusion**:
  WebSockets **cannot** piggyback on `com.sun.net.httpserver.HttpServer` on the same port without modifying or bypassing JDK internal classes.
  Therefore, building an independent `BedrockWebSocketServer` listening on a dedicated port (`app.enableWebSockets(int port)`) using `java.nio.channels.ServerSocketChannel` is not only the cleanest architectural solution, but also strictly adheres to requirement R1:
  > *"Construir um servidor WebSocket independente (BedrockWebSocketServer) usando estritamente as APIs padrão do JDK 21 (java.nio.channels.ServerSocketChannel, java.nio.channels.SocketChannel, java.nio.ByteBuffer e java.security.MessageDigest)."*

---

## 4. Routing, Request Pipeline & Middleware Architecture

### 4.1 Route Matching Engine (`Router.java`)
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\Router.java`
- Routes are stored in a `ConcurrentHashMap<String, Handler>` using the key composite pattern `"METHOD:PATH"` (e.g., `"GET:/api/users"`).
- **Matching Algorithm**:
  1. $O(1)$ Exact static lookup via `routes.get(exactKey)`.
  2. $O(N)$ Linear fallback for dynamic routes containing `{param}` placeholders:
     Splits the route and request into segments by `/`, compares length, and captures variable segments into `extractedParams` (lines 65-101).
- Middlewares:
  - `beforeMiddlewares`: `CopyOnWriteArrayList<Middleware>`
  - `afterMiddlewares`: `CopyOnWriteArrayList<AfterMiddleware>`

### 4.2 Context Abstraction & State Buffering (`Context.java`)
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\Context.java`
- Wraps `HttpExchange` in a Facade.
- Uses the **State / Buffer** pattern: methods like `ok()`, `created()`, `notFound()`, `badRequest()` do not write to the network socket immediately. Instead, they populate:
  - `int statusCode`
  - `Object responseBody`
  - `String contentType`
  - `Map<String, String> responseHeaders`
- `flush()` writes the buffered state to `exchange.getResponseBody()` using `BedrockJson.toJson(...)` at the end of the pipeline.

---

## 5. Inversion of Control (IoC) & Component Registration

### 5.1 Container Lifecycle & Graph Traversal (`BedrockContainer.java`)
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\ioc\BedrockContainer.java`
- **Singleton Management**: Singletons stored in `beans = new ConcurrentHashMap<Class<?>, Object>()`.
- **Constructor Injection**:
  - Finds constructor annotated with `@BedrockInject` or falls back to `constructors[0]`.
  - Recursively resolves parameter types through `resolveAndInstantiate(paramType, resolving)`.
  - Circular dependency detection via `Set<Class<?>> resolving`. If a cycle is detected, throws a descriptive `BedrockException`.
- **Interface Inversion (`bind`)**:
  - `container.bind(IUserService.class, UserService.class)` registers interface mappings in `interfaceBindings = new ConcurrentHashMap<>()`.
  - When resolving an interface parameter, it redirects to the bound implementation class.
- **Instance Registration (`registerInstance`)**:
  - Allows injecting pre-configured instances (such as `BedrockJdbc`) directly into `beans`.

### 5.2 Registration Pipeline in `BedrockApp.java`
*File:* `c:\Users\roner\Documents\repo\bedrock-framework\bedrock-core\src\main\java\com\bedrock\core\BedrockApp.java` (lines 215-284):
```java
public BedrockApp register(Class<?>... classes) {
    // 1. Register all dependencies in the IoC engine
    container.register(classes);
    
    // 2. Scan Controllers to create routes
    for (Class<?> clazz : classes) {
        if (clazz.isAnnotationPresent(BedrockController.class)) {
            Object controllerInstance = container.getBean(clazz);
            for (Method method : clazz.getDeclaredMethods()) {
                RouteInfo routeInfo = extractRouteInfo(method);
                if (routeInfo == null) continue;
                ...
                router.addRoute(httpMethod, path, handler);
            }
        }
    }
    return this;
}
```

---

## 6. WebSocket Extension Blueprint (RFC 6455 & Bedrock v2.0)

### 6.1 Integration with `BedrockApp`
To support WebSockets seamlessly and fluently, `BedrockApp` should be extended with:

1. **Fluent Activation Method**:
   ```java
   public BedrockApp enableWebSockets(int port) {
       this.webSocketPort = port;
       this.webSocketServer = new BedrockWebSocketServer(port);
       // Register any WebSocket endpoints that were registered prior to enableWebSockets
       for (Class<?> endpointClass : pendingSocketClasses) {
           webSocketServer.registerEndpoint(endpointClass, container.getBean(endpointClass));
       }
       return this;
   }
   ```
2. **Augmenting `app.register(Class<?>... classes)`**:
   In the registration loop:
   ```java
   if (clazz.isAnnotationPresent(BedrockSocket.class)) {
       if (webSocketServer != null) {
           Object socketInstance = container.getBean(clazz);
           webSocketServer.registerEndpoint(clazz, socketInstance);
       } else {
           pendingSocketClasses.add(clazz);
       }
   }
   ```
   *Rationale*: By supporting delayed binding through `pendingSocketClasses`, users can invoke `app.register(ChatSocket.class).enableWebSockets(8081)` OR `app.enableWebSockets(8081).register(ChatSocket.class)` in any order.
3. **Augmenting `app.start()`**:
   ```java
   if (webSocketServer != null) {
       webSocketServer.start();
       BedrockLogger.info("SYSTEM", "⚡ Bedrock WebSocket Server running on ws://localhost:" + webSocketPort + " with Virtual Threads!");
   }
   ```
4. **Adding `app.stop()`**:
   ```java
   public void stop() {
       if (server != null) {
           server.stop(0);
       }
       if (webSocketServer != null) {
           webSocketServer.stop();
       }
   }
   ```
   *Rationale*: Essential for testing and clean teardown in integration suites.

### 6.2 Annotations & Semantic Contracts
The new annotations should live in a clean package, e.g., `com.bedrock.websocket.annotation` (or `com.bedrock.websocket`):

1. **`@BedrockSocket` (Class-level)**:
   ```java
   @Target(ElementType.TYPE)
   @Retention(RetentionPolicy.RUNTIME)
   public @interface BedrockSocket {
       String value() default "/";
       String path() default "";
   }
   ```
   Allows both `@BedrockSocket("/chat")` and `@BedrockSocket(path = "/chat")`.
2. **Lifecycle Method Annotations**:
   - `@OnOpen`: Invoked when HTTP 101 Switching Protocols succeeds. Parameter: `(BedrockWebSocketSession session)` or `()`.
   - `@OnMessage`: Invoked on incoming Text Frame (Opcode 0x1). Parameters: `(BedrockWebSocketSession session, String message)` or `(String message)`.
   - `@OnClose`: Invoked when connection closes (Opcode 0x8 or socket disconnect). Parameters: `(BedrockWebSocketSession session, int statusCode, String reason)` or `(BedrockWebSocketSession session)` or `()`.
   - `@OnError`: Invoked on I/O or protocol exception. Parameters: `(BedrockWebSocketSession session, Throwable error)` or `(Throwable error)`.

### 6.3 Thread Management & Virtual Threads (Project Loom)
1. **Server Accept Loop**:
   A dedicated virtual thread runs the server socket accept loop:
   ```java
   ServerSocketChannel serverChannel = ServerSocketChannel.open();
   serverChannel.bind(new InetSocketAddress(port));
   
   Thread.ofVirtual().name("ws-acceptor-", port).start(() -> {
       while (running) {
           SocketChannel clientChannel = serverChannel.accept();
           long clientId = clientCounter.incrementAndGet();
           Thread.ofVirtual()
                 .name("ws-client-", clientId)
                 .start(() -> handleClient(clientChannel));
       }
   });
   ```
2. **Client Virtual Thread Lifecycle**:
   - Executes synchronous blocking I/O on `clientChannel` (`SocketChannel`).
   - Parses HTTP handshake:
     Reads until `\r\n\r\n`. Extracts `Sec-WebSocket-Key`, `Upgrade`, `Connection`, `Sec-WebSocket-Version`.
   - Computes Handshake response:
     $\text{Accept} = \text{Base64}(\text{SHA1}(\text{Key} + \text{"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}))$.
   - Sends `HTTP/1.1 101 Switching Protocols` response.
   - Instantiates `BedrockWebSocketSession`.
   - Dispatches `@OnOpen`.
   - Enters frame reading loop:
     Reads 2-byte header $\to$ extracts FIN, Opcode, MASK, Payload length $\to$ reads extended length (16-bit or 64-bit) if applicable $\to$ reads 4-byte mask $\to$ reads payload bytes $\to$ XOR unmasks $\to$ dispatches according to Opcode.
   - Handles Ping (0x9) by immediately replying with Pong (0xA).
   - Handles Close (0x8) by echoing Close frame and closing `SocketChannel`.
3. **Thread-Safe Writing**:
   Multiple virtual threads (e.g. broadcasting sessions) may call `session.send(text)` concurrently.
   - To prevent interleaving of frame bytes on the network, `session.send()` must synchronize on a write lock or write monitor per `SocketChannel`.

### 6.4 Proposed Package Organization
```
com.bedrock.websocket/
├── BedrockSocket.java           (Endpoint class annotation)
├── OnOpen.java                  (Lifecycle annotation)
├── OnMessage.java               (Lifecycle annotation)
├── OnClose.java                 (Lifecycle annotation)
├── OnError.java                 (Lifecycle annotation)
├── BedrockWebSocketServer.java  (ServerSocketChannel manager & accept loop)
├── BedrockWebSocketSession.java (Session abstraction: send, broadcast, close, getPath, getId)
├── WebSocketFrame.java          (Frame model, Opcode enum, encoding & decoding)
├── WebSocketHandshake.java      (HTTP 101 handshake parser and SHA-1/Base64 computation)
└── WebSocketEndpointHandler.java (Reflective invoker for @OnOpen, @OnMessage, etc.)
```

---

## 7. Pedagogical Standard (🎓 BEDROCK TUTORIAL Analysis)

### 7.1 Existing Convention Breakdown
Inspection of 32 occurrences of `🎓 BEDROCK TUTORIAL` across `bedrock-core` and `bedrock-example` reveals an exact, consistent instructional template:

1. **Title Header**:
   ```java
   /**
    * 🎓 BEDROCK TUTORIAL: [Concept Title]
    * 
   ```
2. **The "Why" / Industrial Reality Check**:
   Contrasts high-level black box frameworks (Spring `@EnableWebSocket`, Hibernate, etc.) with the raw physical reality of the JVM and OS network stack:
   > *"No Spring, um @EnableWebSocket faz o desenvolvedor esquecer que existe TCP, handshake HTTP 101 e frames binários mascarados. No Bedrock..."*
3. **The Physics / Under-the-Hood Breakdown**:
   Numbered mechanical steps explaining the exact data flow:
   - What bytes enter the socket.
   - How bits are shifted or masked.
   - How the JVM coordinates Virtual Threads with OS carrier threads.
4. **Actionable Code Snippets**:
   Uses `<pre>{@code ... }</pre>` blocks illustrating clean, idiomatic usage.
5. **Tone**:
   Encouraging, transparent, intellectually rigorous, empowering the developer to demystify complex systems.

### 7.2 Required Tutorial Javadocs for v2.0
The new WebSocket classes must contain detailed `🎓 BEDROCK TUTORIAL` blocks covering:
- **`WebSocketHandshake`**:
  - Why HTTP 101 Switching Protocols exists (TCP connection upgrade without closing socket).
  - The RFC 6455 Magic GUID (`258EAFA5-E914-47DA-95CA-C5AB0DC85B11`) and SHA-1/Base64 hashing to prevent caching intermediaries from misinterpreting the stream.
- **`WebSocketFrame`**:
  - Anatomy of a WebSocket binary frame (FIN bit, RSV1-3, Opcode 0x1-0xA).
  - Extended length encoding (7-bit, 16-bit, 64-bit).
  - The MASK bit and the 4-byte XOR unmasking mechanism (`unmasked[i] = masked[i] ^ key[i % 4]`). Why clients must mask and servers must not mask.
- **`BedrockWebSocketServer`**:
  - Low-level `ServerSocketChannel` and `SocketChannel` in Java NIO.
  - Project Loom Virtual Threads vs. Thread-per-Client OS exhaustion vs. Netty non-blocking event loops.
- **`BedrockWebSocketSession`**:
  - Full-duplex persistent connections, thread-safe frame serialization, broadcast mechanics.

---

## 8. Zero-Dependency Constraint & Standard Library Sufficiency

Verification confirms that standard JDK 21 provides 100% of the primitives needed:

| Functionality | Standard JDK 21 API | External Dependency Avoided |
|---|---|---|
| Handshake SHA-1 Digest | `java.security.MessageDigest.getInstance("SHA-1")` | Apache Commons Codec |
| Handshake Base64 Encoding | `java.util.Base64.getEncoder().encodeToString(...)` | Apache Commons Codec |
| TCP Server Socket & Channels | `java.nio.channels.ServerSocketChannel`, `SocketChannel` | Netty / Undertow |
| Byte Buffering & Bitwise Ops | `java.nio.ByteBuffer`, `java.nio.ByteOrder` | Netty ByteBuf |
| String / UTF-8 Conversion | `java.nio.charset.StandardCharsets.UTF_8` | Guava |
| Virtual Threads | `Thread.ofVirtual().name(...).start(...)` | RxJava / Project Reactor |
| Concurrency & State | `ConcurrentHashMap`, `CopyOnWriteArraySet`, `AtomicLong` | Guava Collections |
| Reflection & Annotations | `java.lang.reflect.Method`, `Parameter`, `Constructor` | Spring Core / Objenesis |

**Verdict**: The zero-dependency mandate is completely achievable without compromise.

---

## 9. Next Steps for Implementation Team

1. **Phase 2 (Design & Specification)**:
   - Formalize frame parser bitmasks (`0x80`, `0x0F`, `0x7F`, `0x7E`, `0x7F`).
   - Define exact method signatures for `BedrockWebSocketSession` and annotations.
2. **Phase 3 (Core Implementation)**:
   - Implement `com.bedrock.websocket.*` in `bedrock-core`.
   - Integrate `app.enableWebSockets(int port)` and socket routing in `BedrockApp`.
   - Add unit tests for RFC 6455 test vectors (handshake accept key and masked frame decoding).
3. **Phase 4 (Example & Integration)**:
   - Implement `ChatWebSocket` in `bedrock-example`.
   - Implement end-to-end integration test (`WebSocketIntegrationTest`) validating handshake, text frames, ping/pong, and graceful close.
