# Milestone 3 Architecture & Design: BedrockApp Integration, IoC Resolution & Server Lifecycle

**Author**: `explorer_m3_2`  
**Milestone**: Milestone 3 — BedrockApp Integration & Annotations  
**Date**: 2026-09-11  
**Target Class**: `com.bedrock.core.BedrockApp.java` (with alias in `com.bedrock.ioc.BedrockContainer.java`)  
**Status**: DESIGN COMPLETE  

---

## 1. Executive Summary & Mission Overview

Milestone 3 bridges the low-level RFC 6455 protocol engine (Milestone 1) and the virtual-threaded NIO server engine (Milestone 2) with the developer-facing core of the Bedrock Java Framework (`BedrockApp`).

In conventional Java frameworks (such as Spring Boot), WebSocket support is introduced either by silently modifying the embedded servlet engine (Tomcat/Jetty) or by forcing reactive runtimes (Netty/WebFlux) with dynamic bytecode proxies (CGLIB/ByteBuddy). This produces obfuscated stack traces and hides the physical reality of TCP connections and HTTP 101 upgrades.

In accordance with **ORIGINAL_REQUEST §Princípio Central: Combater a "Mágica de Anotações" (Sem Caixas-Pretas)** and **PROJECT.md §Feature Inventory (#17, #18, #20)**, Bedrock upholds:
1. **Explicit Dual-Server Orchestration**:
   `BedrockApp` explicitly manages two dedicated, zero-dependency engines:
   - A JDK `com.sun.net.httpserver.HttpServer` for REST HTTP routing.
   - A native Java NIO `com.bedrock.core.ws.server.BedrockWebSocketServer` for RFC 6455 real-time WebSockets.
2. **IoC Dependency Injection via Constructor Injection**:
   Classes annotated with `@BedrockSocket` are first-class beans managed by `BedrockContainer`. All constructor dependencies (services, repositories, database clients) are resolved in topological order before endpoint binding.
3. **Registration Order Invariance**:
   Developers can invoke `app.register(ChatSocket.class)` before or after `app.enableWebSockets(port)`. Queued endpoint classes are bound immediately when `enableWebSockets()` is invoked.
4. **Deterministic Teardown & Ephemeral Port Testing**:
   `BedrockApp` implements `java.lang.AutoCloseable`. Calling `app.stop()` or using `try-with-resources` closes both servers, terminates active sessions with RFC 6455 status 1001 (Going Away), and releases OS TCP ports immediately, guaranteeing zero port leaks in test suites.

---

## 2. Architectural Analysis: Current State vs Target State

### 2.1 Current State of `BedrockApp.java` (v1.3 Baseline)
- **Local HTTP Server Scope**:
  `HttpServer server` is declared as a local variable inside `public void start()`. Once started, `BedrockApp` holds no reference to it, making it impossible to stop programmatically or in test fixtures.
- **No WebSocket Awareness**:
  `BedrockApp` has no knowledge of `BedrockWebSocketServer`, `@BedrockSocket`, or port configurations for WebSockets.
- **No Teardown / Lifecycle Management**:
  There is no `stop()` method and `BedrockApp` does not implement `AutoCloseable`.
- **Port Discovery Limitation**:
  There is no accessor `getPort()` to discover the actual OS-allocated port when binding to ephemeral port `0`.

### 2.2 Current State of `BedrockContainer.java`
- Manages singletons in `ConcurrentHashMap<Class<?>, Object> beans`.
- Resolves constructor parameters recursively via graph traversal, detecting circular dependencies (`resolving` set).
- Method to retrieve beans is `public <T> T getBean(Class<T> clazz)`.
- Does not currently have the concise alias `public <T> T get(Class<T> clazz)`.

### 2.3 Target State Architecture for Version 2.0
```
                               ┌────────────────────────────────────────────────────────┐
                               │                      BedrockApp                        │
                               │  - port: int                                           │
                               │  - wsPort: Integer                                     │
                               │  - httpServer: HttpServer                              │
                               │  - webSocketServer: BedrockWebSocketServer             │
                               │  - running: AtomicBoolean                              │
                               │  - pendingSocketClasses: Set<Class<?>>                 │
                               └───────────┬────────────────────────────────┬───────────┘
                                           │                                │
                     ┌─────────────────────┴──────┐           ┌─────────────┴────────────────┐
                     ▼                            ▼           ▼                              ▼
          ┌─────────────────────┐      ┌────────────────────┐ ┌───────────────────┐ ┌──────────────────────┐
          │  BedrockContainer   │      │       Router       │ │    HttpServer     │ │BedrockWebSocketServer│
          │  - IoC Singletons   │      │  - REST Routes     │ │  (REST API / DX)  │ │ (RFC 6455 Sockets)   │
          │  - Topo Resolution  │      │  - Before/After MW │ │  - Virtual Threads│ │  - Virtual Threads   │
          └─────────────────────┘      └────────────────────┘ └───────────────────┘ └──────────────────────┘
```

---

## 3. Core Design 1: `enableWebSockets(int port)`

### 3.1 Method Specification
```java
public synchronized BedrockApp enableWebSockets(int port)
```

### 3.2 Detailed Responsibilities & Execution Steps
1. **Port Validation**:
   Validate that `port >= 0 && port <= 65535`.
   If invalid, throw `BedrockException` with descriptive reason and action (e.g., port must be between 0 and 65535, with 0 denoting ephemeral port allocation).
2. **Idempotency & Re-configuration Guard**:
   Check if `this.webSocketServer != null`.
   If already instantiated, throw `BedrockException("WebSockets already enabled on port " + this.wsPort, "enableWebSockets(...) can only be called once per BedrockApp instance.")`.
3. **Server Instantiation**:
   Set `this.wsPort = port;`.
   Instantiate `this.webSocketServer = new BedrockWebSocketServer(port);`.
4. **Flush Queued `@BedrockSocket` Registrations (Order Invariance)**:
   If `pendingSocketClasses` contains classes registered via earlier `app.register(...)` calls, iterate through them, resolve their instances from `BedrockContainer`, scan lifecycle methods, and bind them to `this.webSocketServer`.
   Clear `pendingSocketClasses`.
5. **Runtime Deferred Start**:
   If the application is already running (`this.running.get() == true`), immediately invoke `this.webSocketServer.start()` to start listening.
6. **Logging & Fluent Return**:
   Log `BedrockLogger.info("SYSTEM", "WebSockets enabled on port " + port);`.
   Return `this` for method chaining.

---

## 4. Core Design 2: Enhanced `register(Class<?>... classes)`

### 4.1 Method Specification
```java
public BedrockApp register(Class<?>... classes)
```

### 4.2 IoC Resolution & Topological Ordering
`BedrockApp` delegates first to `container.register(classes)`:
1. `BedrockContainer.register(classes)` registers all classes into its internal set.
2. For each class, it performs topological dependency graph traversal via constructor inspection.
3. If `ChatSocket` depends on `ChatService`, and `ChatService` depends on `ChatRepository`:
   - `ChatRepository` is instantiated first.
   - `ChatService` is instantiated second, receiving `ChatRepository`.
   - `ChatSocket` is instantiated third, receiving `ChatService`.
4. Circular dependencies are detected and fail fast with detailed guidance.

### 4.3 Detection and Binding of `@BedrockSocket`
Following `container.register(classes)`, `BedrockApp` iterates over `classes`:
```java
for (Class<?> clazz : classes) {
    if (clazz.isAnnotationPresent(BedrockController.class)) {
        bindController(clazz);
    } else if (clazz.isAnnotationPresent(BedrockSocket.class)) {
        bindWebSocket(clazz);
    }
}
```

### 4.4 Detailed Logic for `bindWebSocket(Class<?> clazz)`
```java
private void bindWebSocket(Class<?> clazz) {
    if (webSocketServer != null) {
        bindWebSocketToServer(clazz);
    } else {
        // Deferred binding: register() was invoked before enableWebSockets()
        pendingSocketClasses.add(clazz);
        BedrockLogger.info("WS-APP", "Registered @" + BedrockSocket.class.getSimpleName() +
                " '" + clazz.getSimpleName() + "' (queued pending enableWebSockets)");
    }
}
```

### 4.5 Detailed Logic for `bindWebSocketToServer(Class<?> clazz)`
```java
private void bindWebSocketToServer(Class<?> clazz) {
    Object socketInstance = container.getBean(clazz);
    if (socketInstance == null) {
        throw new BedrockException(
            "Could not retrieve singleton instance of @" + BedrockSocket.class.getSimpleName() + " '" + clazz.getSimpleName() + "'.",
            "Ensure the class is managed by the IoC container and constructor dependencies are satisfied."
        );
    }

    // Direct binding via WebSocketEndpointScanner (or direct reflection fallback)
    WebSocketEndpointScanner.ScannedEndpoint scanned = WebSocketEndpointScanner.scan(socketInstance);
    webSocketServer.registerEndpoint(scanned.path(), scanned.binding());
    BedrockLogger.info("WS-APP", "Mapped WebSocket route: " + scanned.path() + " -> " + clazz.getSimpleName());
}
```

---

## 5. Core Design 3: Server Lifecycle, Teardown & `AutoCloseable`

### 5.1 Dual-Server Lifecycle State Machine
```
   [CREATED]
       │
       ▼  start()
   [RUNNING] ─── (HttpServer listening + BedrockWebSocketServer listening)
       │
       ▼  stop() / close()
   [STOPPED] ─── (HttpServer stopped + WS 1001 Going Away broadcast + channels closed)
```

### 5.2 Method: `public void stop()`
```java
public synchronized void stop() {
    if (running.compareAndSet(true, false) || httpServer != null || (webSocketServer != null && webSocketServer.isRunning())) {
        running.set(false);
        
        // 1. Stop HTTP Server
        if (httpServer != null) {
            try {
                httpServer.stop(0); // 0 delay: immediate unblock
                BedrockLogger.info("HTTP-SERVER", "Bedrock HTTP server stopped.");
            } catch (Exception e) {
                BedrockLogger.warn("HTTP-SERVER", "Error stopping HTTP server: " + e.getMessage());
            } finally {
                httpServer = null;
            }
        }

        // 2. Stop WebSocket Server
        if (webSocketServer != null) {
            try {
                webSocketServer.stop();
                BedrockLogger.info("WS-SERVER", "Bedrock WebSocket server stopped.");
            } catch (Exception e) {
                BedrockLogger.warn("WS-SERVER", "Error stopping WebSocket server: " + e.getMessage());
            }
        }
    }
}
```

### 5.3 Implementation of `AutoCloseable`
```java
public class BedrockApp implements AutoCloseable {
    ...
    @Override
    public void close() {
        stop();
    }
}
```
This enables the canonical, leak-free test pattern:
```java
try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
    app.register(SampleSocket.class).start();
    int wsPort = app.getWebSocketServer().getPort();
    // Connect live HttpClient client to ws://localhost:wsPort/...
} // Guaranteed cleanup of all OS TCP ports and Virtual Threads!
```

### 5.4 Accessors
1. `public BedrockWebSocketServer getWebSocketServer()`:
   Returns `this.webSocketServer`. Allows direct retrieval of the dynamically bound ephemeral port via `app.getWebSocketServer().getPort()`.
2. `public int getPort()`:
   Returns the bound HTTP port. If `httpServer != null`, returns `httpServer.getAddress().getPort()`; otherwise returns `this.port`.
3. `public int getWsPort()`:
   Returns `webSocketServer != null ? webSocketServer.getPort() : (wsPort != null ? wsPort : -1)`.
4. `public boolean isRunning()`:
   Returns `running.get()`.

### 5.5 Enhanced `start()` Method
```java
public synchronized void start() {
    if (running.get()) {
        BedrockLogger.warn("SYSTEM", "BedrockApp is already running.");
        return;
    }

    try {
        // 1. Start HTTP Server
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        
        httpServer.createContext("/", exchange -> {
            ...
        });
        httpServer.start();

        // 2. Start WebSocket Server if enabled
        if (webSocketServer != null && !webSocketServer.isRunning()) {
            try {
                webSocketServer.start();
            } catch (Exception wsEx) {
                // Prevent leaking HTTP port if WebSocket server fails to bind
                httpServer.stop(0);
                httpServer = null;
                throw wsEx;
            }
        }

        // 3. Warn if @BedrockSocket classes were registered without enableWebSockets()
        if (!pendingSocketClasses.isEmpty() && webSocketServer == null) {
            BedrockLogger.warn("WS-APP", "Found " + pendingSocketClasses.size() +
                    " @BedrockSocket class(es) registered, but enableWebSockets(port) was not called. WebSocket server will not start.");
        }

        running.set(true);
        printBanner();

    } catch (IOException e) {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        throw new BedrockException(
            "Could not start Bedrock HTTP server on port " + port,
            "Check if the port " + port + " is already in use by another application.",
            e
        );
    }
}
```

---

## 6. Complete Implementation Blueprint for `BedrockApp.java`

Below is the complete, drop-in replacement specification for `BedrockApp.java`:

```java
package com.bedrock.core;

import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.server.BedrockWebSocketServer;
import com.bedrock.core.ws.server.WebSocketEndpointScanner;
import com.bedrock.exception.BedrockException;
import com.bedrock.exception.BedrockValidationException;
import com.bedrock.ioc.BedrockContainer;
import com.bedrock.web.*;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🎓 BEDROCK TUTORIAL: Explicit Dual-Server Orchestration & Transparent Architecture
 *
 * <p>The main entry point, IoC orchestrator, and lifecycle manager of the Bedrock framework.
 * Unlike conventional frameworks that conceal server startup and dynamic proxy chains,
 * {@code BedrockApp} explicitly coordinates two specialized, zero-dependency engines:
 * <ul>
 *   <li>A JDK {@link HttpServer} for REST APIs and DX web tooling.</li>
 *   <li>A native NIO {@link BedrockWebSocketServer} for full-duplex RFC 6455 WebSockets.</li>
 * </ul>
 * Both engines execute exclusively on Java 21 Virtual Threads (Project Loom).</p>
 */
public class BedrockApp implements AutoCloseable {

    private final int port;
    private final Router router;
    private final BedrockContainer container;
    private final Map<Class<? extends Throwable>, ErrorHandler<? extends Throwable>> errorHandlers = new LinkedHashMap<>();

    // WebSocket Integration State
    private Integer wsPort = null;
    private BedrockWebSocketServer webSocketServer = null;
    private final Set<Class<?>> pendingSocketClasses = new LinkedHashSet<>();

    // Server Runtime State
    private HttpServer httpServer = null;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private BedrockApp(int port) {
        this.port = port;
        this.router = new Router();
        this.container = new BedrockContainer();

        // Auto-mapping for Developer Experience (DX) routes
        this.router.addRoute("GET", "/bedrock/ui", ctx -> ctx.html(BedrockPlayground.getHtml()));
        this.router.addRoute("GET", "/bedrock/api/routes", ctx -> ctx.ok(this.router.getRegisteredRoutes()));
    }

    /**
     * Creates a new server instance on the specified HTTP port.
     * Use port 0 for an ephemeral OS-allocated port.
     */
    public static BedrockApp create(int port) {
        return new BedrockApp(port);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Explicit Real-Time WebSockets Enabling
     *
     * <p>Enables RFC 6455 WebSockets on the specified port.
     * Use port 0 for an OS-allocated ephemeral port (ideal for collision-free tests).
     * Any {@code @BedrockSocket} classes previously registered via {@link #register(Class[])}
     * are bound immediately to this server.</p>
     *
     * @param port Port number (0 for ephemeral).
     * @return This BedrockApp instance for fluent chaining.
     */
    public synchronized BedrockApp enableWebSockets(int port) {
        if (port < 0 || port > 65535) {
            throw new BedrockException(
                "Invalid WebSocket port: " + port,
                "Port must be between 0 and 65535 (use 0 for dynamic ephemeral port allocation)."
            );
        }
        if (this.webSocketServer != null) {
            throw new BedrockException(
                "WebSockets already enabled on port " + this.wsPort,
                "enableWebSockets(...) can only be called once per BedrockApp instance."
            );
        }

        this.wsPort = port;
        this.webSocketServer = new BedrockWebSocketServer(port);
        BedrockLogger.info("SYSTEM", "WebSockets configured on port " + port);

        // If @BedrockSocket classes were already registered via register(), bind them now
        if (!pendingSocketClasses.isEmpty()) {
            for (Class<?> socketClass : pendingSocketClasses) {
                bindWebSocketToServer(socketClass);
            }
            pendingSocketClasses.clear();
        }

        // If the application is already running, start WebSocket server immediately
        if (running.get() && !this.webSocketServer.isRunning()) {
            try {
                this.webSocketServer.start();
            } catch (IOException e) {
                throw new BedrockException(
                    "Could not start Bedrock WebSocket server on port " + port,
                    "Check if port " + port + " is already in use.",
                    e
                );
            }
        }

        return this;
    }

    /**
     * Returns the underlying WebSocket server instance, or null if WebSockets are not enabled.
     */
    public BedrockWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    /**
     * Returns the bound HTTP port. If configured with 0, returns the actual OS-allocated port once started.
     */
    public int getPort() {
        if (httpServer != null) {
            return httpServer.getAddress().getPort();
        }
        return port;
    }

    /**
     * Returns the bound WebSocket port.
     */
    public int getWsPort() {
        if (webSocketServer != null) {
            return webSocketServer.getPort();
        }
        return wsPort != null ? wsPort : -1;
    }

    /**
     * Checks whether the application servers are running.
     */
    public boolean isRunning() {
        return running.get();
    }

    public BedrockApp get(String path, Handler handler) {
        router.addRoute("GET", path, handler);
        return this;
    }

    public BedrockApp post(String path, Handler handler) {
        router.addRoute("POST", path, handler);
        return this;
    }

    public BedrockApp put(String path, Handler handler) {
        router.addRoute("PUT", path, handler);
        return this;
    }

    public BedrockApp delete(String path, Handler handler) {
        router.addRoute("DELETE", path, handler);
        return this;
    }

    public BedrockApp patch(String path, Handler handler) {
        router.addRoute("PATCH", path, handler);
        return this;
    }

    public BedrockApp before(Middleware middleware) {
        router.before(middleware);
        return this;
    }

    public BedrockApp after(AfterMiddleware middleware) {
        router.after(middleware);
        return this;
    }

    public <T> BedrockApp bind(Class<T> interfaceClass, Class<? extends T> implementationClass) {
        container.bind(interfaceClass, implementationClass);
        return this;
    }

    public <T> BedrockApp registerInstance(Class<T> type, T instance) {
        container.registerInstance(type, instance);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <E extends Throwable> BedrockApp onError(Class<E> exceptionClass, ErrorHandler<E> handler) {
        errorHandlers.put(exceptionClass, handler);
        return this;
    }

    public BedrockContainer getContainer() {
        return container;
    }

    public void handleException(Context ctx, Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException ite && ite.getCause() != null) {
            cause = ite.getCause();
        }

        // 1. Check user-registered exception handlers
        for (Map.Entry<Class<? extends Throwable>, ErrorHandler<? extends Throwable>> entry : errorHandlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(cause.getClass())) {
                try {
                    @SuppressWarnings("unchecked")
                    ErrorHandler<Throwable> handler = (ErrorHandler<Throwable>) entry.getValue();
                    handler.handle(ctx, cause);
                    return;
                } catch (Exception handlerEx) {
                    BedrockLogger.error("EXCEPTION-HANDLER", "Error executing custom error handler for " +
                            cause.getClass().getSimpleName() + ": " + handlerEx.getMessage());
                    cause = handlerEx;
                    break;
                }
            }
        }

        // 2. Default BedrockValidationException handler -> 400 Bad Request
        if (cause instanceof BedrockValidationException bve) {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "https://bedrock.dev/errors/validation-error");
            problem.put("title", "Validation Error");
            problem.put("status", 400);
            problem.put("detail", bve.getMessage());
            problem.put("parameter", bve.getParameterName());
            if (bve.getInvalidValue() != null) {
                problem.put("invalidValue", bve.getInvalidValue());
            }
            if (bve.getAction() != null) {
                problem.put("action", bve.getAction());
            }
            ctx.status(400).json(problem);
            return;
        }

        // 3. Fallback: RFC 7807 Problem Details 500 Internal Server Error
        BedrockLogger.error("HTTP-SERVER", "Unhandled exception in request: " + cause.getMessage());
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://bedrock.dev/errors/internal-server-error");
        problem.put("title", "Internal Server Error");
        problem.put("status", 500);
        problem.put("detail", cause.getMessage() != null ? cause.getMessage() : "An unexpected error occurred");
        ctx.status(500).json(problem);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Explicit Component Registration & IoC Constructor Injection
     *
     * <p>Registers application classes (Services, Repositories, Controllers, and WebSockets)
     * in the IoC Container. Bedrock resolves constructor dependencies in topological order,
     * maps {@code @BedrockController} methods to HTTP routes, and binds {@code @BedrockSocket}
     * classes to the WebSocket server.</p>
     */
    public BedrockApp register(Class<?>... classes) {
        // 1. Register all dependencies in the IoC engine (resolves in topological order)
        container.register(classes);

        // 2. Scan Controllers and WebSockets
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(BedrockController.class)) {
                bindController(clazz);
            } else if (clazz.isAnnotationPresent(BedrockSocket.class)) {
                bindWebSocket(clazz);
            }
        }
        return this;
    }

    public BedrockApp bindControllers(Class<?>... classes) {
        return register(classes);
    }

    private void bindController(Class<?> clazz) {
        Object controllerInstance = container.getBean(clazz);

        for (Method method : clazz.getDeclaredMethods()) {
            RouteInfo routeInfo = extractRouteInfo(method);
            if (routeInfo == null) {
                continue;
            }

            String httpMethod = routeInfo.httpMethod();
            String path = routeInfo.path();

            Handler handler = ctx -> {
                try {
                    method.setAccessible(true);
                    Parameter[] parameters = method.getParameters();
                    Object[] args = new Object[parameters.length];

                    for (int i = 0; i < parameters.length; i++) {
                        Class<?> paramType = parameters[i].getType();
                        if (paramType.equals(Context.class)) {
                            args[i] = ctx;
                        } else {
                            String body = ctx.body();
                            if (body == null || body.trim().isEmpty()) {
                                args[i] = null;
                            } else {
                                args[i] = BedrockJson.fromJson(body, paramType);
                            }
                        }
                    }

                    Object result = method.invoke(controllerInstance, args);

                    if (result != null) {
                        if (ctx.getResponseBody() == null) {
                            if ("POST".equalsIgnoreCase(httpMethod)) {
                                ctx.created(result);
                            } else {
                                ctx.ok(result);
                            }
                        }
                    } else if (method.getReturnType().equals(void.class) && ctx.getResponseBody() == null) {
                        if ("DELETE".equalsIgnoreCase(httpMethod) || ctx.getStatusCode() == 200) {
                            ctx.noContent();
                        }
                    }
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof Exception ex) {
                        throw ex;
                    }
                    throw new RuntimeException(cause);
                }
            };

            router.addRoute(httpMethod, path, handler);
        }
    }

    private void bindWebSocket(Class<?> clazz) {
        if (webSocketServer != null) {
            bindWebSocketToServer(clazz);
        } else {
            pendingSocketClasses.add(clazz);
            BedrockLogger.info("WS-APP", "Registered @" + BedrockSocket.class.getSimpleName() +
                    " '" + clazz.getSimpleName() + "' (queued pending enableWebSockets)");
        }
    }

    private void bindWebSocketToServer(Class<?> clazz) {
        Object socketInstance = container.getBean(clazz);
        if (socketInstance == null) {
            throw new BedrockException(
                "Could not retrieve singleton instance of @" + BedrockSocket.class.getSimpleName() + " '" + clazz.getSimpleName() + "'.",
                "Ensure the class is managed by the IoC container and its constructor dependencies are satisfied."
            );
        }

        WebSocketEndpointScanner.ScannedEndpoint scanned = WebSocketEndpointScanner.scan(socketInstance);
        webSocketServer.registerEndpoint(scanned.path(), scanned.binding());
        BedrockLogger.info("WS-APP", "Mapped WebSocket route: " + scanned.path() + " -> " + clazz.getSimpleName());
    }

    private record RouteInfo(String httpMethod, String path) {}

    private RouteInfo extractRouteInfo(Method method) {
        if (method.isAnnotationPresent(BedrockGet.class)) {
            return new RouteInfo("GET", method.getAnnotation(BedrockGet.class).value());
        }
        if (method.isAnnotationPresent(BedrockPost.class)) {
            return new RouteInfo("POST", method.getAnnotation(BedrockPost.class).value());
        }
        if (method.isAnnotationPresent(BedrockPut.class)) {
            return new RouteInfo("PUT", method.getAnnotation(BedrockPut.class).value());
        }
        if (method.isAnnotationPresent(BedrockDelete.class)) {
            return new RouteInfo("DELETE", method.getAnnotation(BedrockDelete.class).value());
        }
        if (method.isAnnotationPresent(BedrockPatch.class)) {
            return new RouteInfo("PATCH", method.getAnnotation(BedrockPatch.class).value());
        }
        return null;
    }

    /**
     * Starts the native HTTP server coupled with Virtual Threads, as well as the WebSocket server (if enabled).
     */
    public synchronized void start() {
        if (running.get()) {
            BedrockLogger.warn("SYSTEM", "BedrockApp is already running.");
            return;
        }

        try {
            // 1. Start Native HTTP Server
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            httpServer.createContext("/", exchange -> {
                Map<String, String> pathParams = new HashMap<>();
                Context ctx = new Context(exchange, pathParams);

                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();

                try {
                    for (Middleware middleware : router.getBeforeMiddlewares()) {
                        middleware.handle(ctx);
                    }

                    Handler handler = router.findHandler(method, path, pathParams);
                    if (handler != null) {
                        handler.handle(ctx);
                    } else {
                        ctx.notFound("Oops! Route " + method + " " + path + " not mapped in Bedrock.");
                    }

                    for (AfterMiddleware middleware : router.getAfterMiddlewares()) {
                        middleware.handle(ctx);
                    }

                    ctx.flush();

                } catch (Throwable e) {
                    try {
                        handleException(ctx, e);

                        for (AfterMiddleware middleware : router.getAfterMiddlewares()) {
                            try {
                                middleware.handle(ctx);
                            } catch (Exception ignore) {}
                        }

                        ctx.flush();
                    } catch (Exception ex) {
                        BedrockLogger.error("HTTP-SERVER", "Fatal error flushing response: " + ex.getMessage());
                    }
                }
            });

            httpServer.start();

            // 2. Start WebSocket Server if enabled
            if (webSocketServer != null && !webSocketServer.isRunning()) {
                try {
                    webSocketServer.start();
                } catch (Exception wsEx) {
                    // Prevent leaking HTTP port if WebSocket server fails to bind
                    httpServer.stop(0);
                    httpServer = null;
                    throw wsEx;
                }
            }

            // 3. Warn if @BedrockSocket classes were registered without calling enableWebSockets()
            if (!pendingSocketClasses.isEmpty() && webSocketServer == null) {
                BedrockLogger.warn("WS-APP", "Found " + pendingSocketClasses.size() +
                        " @BedrockSocket class(es) registered, but enableWebSockets(port) was not called. WebSocket server will not start.");
            }

            running.set(true);
            printBanner();

        } catch (IOException e) {
            if (httpServer != null) {
                httpServer.stop(0);
                httpServer = null;
            }
            throw new BedrockException(
                "Could not start Bedrock HTTP server on port " + port,
                "Check if the port " + port + " is already in use by another application.",
                e
            );
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Coordinated Dual-Server Teardown & Zero Port Leaks
     *
     * <p>Stops the HTTP server and the WebSocket server cleanly:
     * <ol>
     *   <li>Sends an RFC 6455 Close frame (1001 Going Away) to all active WebSocket clients.</li>
     *   <li>Closes both ServerSocketChannels, unblocking accept loops.</li>
     *   <li>Releases TCP ports back to the operating system immediately.</li>
     * </ol>
     * </p>
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false) || httpServer != null || (webSocketServer != null && webSocketServer.isRunning())) {
            running.set(false);

            if (httpServer != null) {
                try {
                    httpServer.stop(0);
                    BedrockLogger.info("HTTP-SERVER", "Bedrock HTTP server stopped.");
                } catch (Exception e) {
                    BedrockLogger.warn("HTTP-SERVER", "Error stopping HTTP server: " + e.getMessage());
                } finally {
                    httpServer = null;
                }
            }

            if (webSocketServer != null) {
                try {
                    webSocketServer.stop();
                    BedrockLogger.info("WS-SERVER", "Bedrock WebSocket server stopped.");
                } catch (Exception e) {
                    BedrockLogger.warn("WS-SERVER", "Error stopping WebSocket server: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Implementation of {@link AutoCloseable}. Enables usage in {@code try-with-resources} blocks.
     */
    @Override
    public void close() {
        stop();
    }

    private void printBanner() {
        String banner = """
                 ____           _                _    
                |  _ \\         | |              | |   
                | |_) | ___  __| |_ __ ___   ___| | __
                |  _ < / _ \\/ _` | '__/ _ \\ / __| |/ /
                | |_) |  __/ (_| | | | (_) | (__|   < 
                |____/ \\___|\\__,_|_|  \\___/ \\___|_|\\_\\
                """;
        BedrockLogger.banner(banner);
        BedrockLogger.info("SYSTEM", "🦖 Bedrock Java Framework running at full speed on Virtual Threads!");
        BedrockLogger.info("SYSTEM", "🔗 REST Playground UI: http://localhost:" + getPort() + "/bedrock/ui");
        if (webSocketServer != null && webSocketServer.isRunning()) {
            BedrockLogger.info("SYSTEM", "⚡ WebSocket Server listening on ws://localhost:" + webSocketServer.getPort());
        }
        System.out.println("---------------------------------------------------------");
    }
}
```

---

## 7. Proposed Addition to `BedrockContainer.java`

To support both `container.get(clazz)` and `container.getBean(clazz)` natively:

```java
    /**
     * Retrieves a Singleton already managed by the container.
     * Alias for {@link #getBean(Class)}.
     *
     * @param clazz The class type to retrieve.
     * @param <T>   The bean type.
     * @return The managed singleton instance, or null if not registered.
     */
    public <T> T get(Class<T> clazz) {
        return getBean(clazz);
    }
```

---

## 8. Failure Modes, Concurrency & Invariant Analysis

| Failure Scenario | Detection Mechanism | Framework Behavior | Diagnostic Feedback |
| :--- | :--- | :--- | :--- |
| **Port Already In Use (HTTP)** | `HttpServer.bind()` throws `BindException` in `start()` | `start()` catches `IOException`, cleans up any partial socket state, and wraps in `BedrockException` | Suggests checking if port is already used by another application. |
| **Port Already In Use (WebSocket)** | `ServerSocketChannel.bind()` throws `IOException` in `start()` | Catches exception, shuts down `httpServer.stop(0)` to prevent orphan port leak, wraps in `BedrockException` | Suggests checking if WebSocket port is in use. |
| **Invalid WebSocket Port Number** | Checked at entry of `enableWebSockets(port)` | Throws `BedrockException` immediately | Informs developer that port must be 0 to 65535. |
| **Duplicate `enableWebSockets` call** | Checked against `this.webSocketServer != null` | Throws `BedrockException` | Explains `enableWebSockets` can only be invoked once per instance. |
| **Unregistered Dependency for Socket** | `BedrockContainer.resolveAndInstantiate` | Throws `BedrockException` with missing class name | Explains constructor dependency is missing and suggests passing to `app.register(...)`. |
| **Circular Dependency in Socket** | `BedrockContainer` graph traversal set | Throws `BedrockException` with cycle chain | Advises redesigning constructors to break cycle. |
| **Missing Lifecycle Annotations** | `WebSocketEndpointScanner` validation | Permitted; socket connects without event handlers or logs warning | Safe no-op without crash. |
| **Duplicate Lifecycle Annotations** | `WebSocketEndpointScanner` validation | Throws `BedrockException` at startup | Informs developer to consolidate duplicate `@OnMessage` etc. into a single method. |
| **`@BedrockSocket` registered without `enableWebSockets`** | Checked in `app.start()` | Server logs `BedrockLogger.warn` and boots HTTP only | Informs developer how many sockets are registered and reminds to call `app.enableWebSockets(port)`. |
| **Multiple `stop()` or `close()` calls** | `running.compareAndSet(true, false)` | Fully idempotent, safe to call concurrently or repeatedly | Silent clean exit. |

---

## 9. Alignment & Harmony with Peer Designs

### 9.1 Harmony with `explorer_m3_1` (`annotations_design.md`)
- `WebSocketEndpointScanner.scan(socketInstance)` produces `ScannedEndpoint(path, targetInstance, onOpen, onMessage, onClose, onError, binding)`.
- `BedrockApp.bindWebSocketToServer` feeds directly into `webSocketServer.registerEndpoint(scanned.path(), scanned.binding())`.
- Full alignment on parameter validation, exception messages, and single-pass reflection without bytecode proxies.

### 9.2 Harmony with `explorer_m3_3` (`test_and_tutorial_design.md`)
- `BedrockApp` satisfies all 12 test scenarios specified in `test_and_tutorial_design.md`:
  * Scenario 1: `app.enableWebSockets(0)` + `app.getWebSocketServer().getPort()` discovery.
  * Scenario 2: IoC constructor injection of `GreetingService` into `SampleChatSocket`.
  * Scenario 9 & 10: `app.stop()` and `try-with-resources` via `AutoCloseable`.
  * Scenario 11: Registration order invariance (`register` before `enableWebSockets` and vice-versa).
  * Scenario 12: Dual HTTP (`/api/status`) and WebSocket (`/chat`) endpoints running side-by-side.
- Javadoc tutorials match the `🎓 BEDROCK TUTORIAL` educational manifesto.
