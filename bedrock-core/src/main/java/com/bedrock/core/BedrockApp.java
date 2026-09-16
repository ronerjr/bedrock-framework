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
 * Unlike conventional enterprise frameworks that conceal server startup and dynamic proxy chains
 * behind annotation magic, {@code BedrockApp} explicitly coordinates two specialized,
 * zero-dependency engines:
 * <ul>
 *   <li>A JDK {@link HttpServer} for synchronous REST APIs and developer-tooling routes.</li>
 *   <li>A native Java NIO {@link BedrockWebSocketServer} for full-duplex RFC 6455 WebSockets.</li>
 * </ul>
 * Both engines execute exclusively on Java 21 Virtual Threads (Project Loom).</p>
 *
 * <h3>Core Architectural Guarantees:</h3>
 * <ol>
 *   <li><b>Explicit Dual-Server Configuration</b>: WebSockets are enabled explicitly via
 *       {@link #enableWebSockets(int)}. There are no silent port open actions or hidden Netty runtimes.</li>
 *   <li><b>IoC Constructor Injection</b>: Classes annotated with {@link BedrockSocket} are registered
 *       into {@link BedrockContainer}. Their constructor dependencies are topologically resolved
 *       before endpoint binding.</li>
 *   <li><b>Registration Order Invariance</b>: Sockets may be registered via {@link #register(Class[])}
 *       either before or after {@link #enableWebSockets(int)}.</li>
 *   <li><b>Deterministic Teardown & Port Release</b>: Implements {@link AutoCloseable}. Calling
 *       {@link #stop()} or {@link #close()} shuts down both servers, sends RFC 6455 Close 1001 (Going Away)
 *       to clients, and frees OS TCP ports immediately, ensuring zero port leaks in test suites.</li>
 * </ol>
 *
 * @see BedrockWebSocketServer
 * @see BedrockSocket
 * @see BedrockContainer
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
     *
     * @param port HTTP port number (0 for dynamic ephemeral port).
     * @return A new BedrockApp instance.
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
     * @param port Port number (0 for dynamic ephemeral port allocation).
     * @return This BedrockApp instance for fluent chaining.
     * @throws BedrockException If port is outside 0-65535 or if WebSockets are already enabled.
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

        // Bind any @BedrockSocket classes queued before enableWebSockets was called
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
                        "Check if port " + port + " is already in use by another application.",
                        e
                );
            }
        }

        return this;
    }

    /**
     * Returns the underlying WebSocket server instance, or null if WebSockets are not enabled.
     *
     * @return The BedrockWebSocketServer instance or null.
     */
    public BedrockWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    /**
     * Returns the bound WebSocket port. If configured with port 0, returns the actual
     * OS-allocated ephemeral port once the server is started.
     *
     * @return The bound WebSocket port, or configured port if not started, or -1 if WebSockets not enabled.
     */
    public int getWebSocketPort() {
        if (webSocketServer != null) {
            return webSocketServer.getPort();
        }
        return wsPort != null ? wsPort : -1;
    }

    /**
     * Concise alias for {@link #getWebSocketPort()}.
     *
     * @return The bound WebSocket port.
     */
    public int getWsPort() {
        return getWebSocketPort();
    }

    /**
     * Returns the bound HTTP port. If configured with 0, returns the actual OS-allocated port once started.
     *
     * @return The bound HTTP port.
     */
    public int getPort() {
        if (httpServer != null) {
            return httpServer.getAddress().getPort();
        }
        return port;
    }

    /**
     * Returns the underlying JDK HTTP server instance, or null if not yet started.
     *
     * @return The HttpServer instance or null.
     */
    public HttpServer getHttpServer() {
        return httpServer;
    }

    /**
     * Checks whether the application servers are running.
     *
     * @return True if running.
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
     *
     * @param classes The classes to register in the IoC container and route tables.
     * @return This BedrockApp instance for fluent chaining.
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
                    if (wsEx instanceof BedrockException be) {
                        throw be;
                    }
                    throw new BedrockException(
                            "Could not start Bedrock WebSocket server on port " + wsPort,
                            "Check if the port " + wsPort + " is already in use by another application.",
                            wsEx
                    );
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
     * 🎓 BEDROCK TUTORIAL: Coordinated Server Teardown & Zero Port Leaks
     *
     * <p>Gracefully terminates both the HTTP server and the WebSocket server:
     * <ol>
     *   <li><b>WebSocket Client Notification</b>: Sends an RFC 6455 Close frame with status
     *       {@code 1001 (Going Away)} to all connected clients, allowing them to reconnect
     *       or notify users.</li>
     *   <li><b>Channel Closure</b>: Closes the listening {@link java.nio.channels.ServerSocketChannel},
     *       unblocking the accept loop virtual thread.</li>
     *   <li><b>Port Release</b>: Releases the bound TCP ports back to the operating system immediately,
     *       preventing zombie sockets or {@code java.net.BindException} in subsequent tests.</li>
     * </ol>
     * </p>
     *
     * @see #close()
     * @see BedrockWebSocketServer#stop()
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

            // Teardown IoC managed beans with @BedrockDestroy in reverse order
            try {
                container.destroy();
            } catch (Exception e) {
                BedrockLogger.warn("BEDROCK-IOC", "Error during container teardown: " + e.getMessage());
            }
        }
    }

    /**
     * Implementation of {@link AutoCloseable}. Allows {@link BedrockApp} to be used in
     * {@code try-with-resources} blocks for deterministic lifecycle management and guaranteed
     * teardown in automated tests:
     *
     * <pre>{@code
     *   try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
     *       app.register(MySocket.class).start();
     *       // Execute test against app.getWebSocketServer().getPort()
     *   } // Automatically stopped and ports released here!
     * }</pre>
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
