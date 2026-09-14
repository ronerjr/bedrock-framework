package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🎓 BEDROCK TUTORIAL: The Physics of Virtual-Threaded Network Servers & Project Loom
 *
 * <p>In standard enterprise Java frameworks (such as Spring WebFlux or Quarkus), real-time
 * WebSocket servers rely heavily on Netty, event loops, reactive streams, and complex callback
 * chains. Developers are told that blocking I/O is "slow" and that non-blocking reactive
 * programming is the only way to handle 50,000+ concurrent connections.</p>
 *
 * <p>In Bedrock, we reject this false dichotomy. With <b>Java 21 Project Loom (Virtual Threads)</b>,
 * the JVM reconciles synchronous, imperative programming with ultra-high concurrency.</p>
 *
 * <h3>1. Why Virtual Threads Eliminate Reactive Frameworks:</h3>
 * <p>Historically, every Java thread was an OS-level kernel thread (a <i>pthread</i>).
 * OS threads are heavy: each reserves ~1 MB of off-heap C-stack memory. If a server attempted
 * to allocate 20,000 idle WebSocket connections on 20,000 OS threads, the JVM would consume
 * 20 GB of memory solely for thread stacks, triggering:
 * <pre>{@code java.lang.OutOfMemoryError: unable to create native thread}</pre>
 * </p>
 * <p>To avoid OS thread exhaustion, reactive frameworks (Netty) introduced the <b>Event Loop</b>:
 * a tiny pool of OS worker threads multiplexing thousands of non-blocking channels via {@code Selector}.
 * However, this introduced severe software engineering costs:
 * <ul>
 *   <li><b>Inverted Control Flow</b>: Logic is fragmented across callbacks, {@code Mono}, and {@code Flux}.</li>
 *   <li><b>Broken Stack Traces</b>: When an exception occurs, the call stack contains hundreds of
 *       framework trampoline frames without pointing to the business line of code that failed.</li>
 *   <li><b>Context Loss</b>: {@link ThreadLocal} storage (tracing, security, MDC logging) does not
 *       propagate across asynchronous event loop boundaries without cumbersome context wrappers.</li>
 * </ul>
 * </p>
 * <p><b>The Loom Paradigm in Bedrock</b>:
 * In {@link BedrockWebSocketServer}, every accepted client connection is granted its own dedicated
 * Virtual Thread:
 * <pre>{@code
 *   Thread.ofVirtual().name("ws-client-", clientId).start(...)
 * }</pre>
 * Virtual threads are lightweight user-mode tasks managed entirely by the JVM runtime.
 * Their initial memory footprint is a mere ~300 bytes on the Java garbage-collected heap!
 * The JVM dynamically schedules millions of virtual threads onto a tiny pool of OS <i>carrier threads</i>
 * (matching the number of available CPU cores).
 * Developers write clean, readable, linear code with standard {@code try/catch} blocks,
 * preserving natural stack traces and zero third-party dependencies.
 * </p>
 *
 * <h3>2. Ephemeral Port Allocation & Test Isolation:</h3>
 * <p>In automated testing, hardcoded port numbers (such as 8080 or 9001) inevitably cause
 * flaky test failures when tests run concurrently or when previous runs leave zombie sockets in
 * OS {@code TIME_WAIT} state.</p>
 * <p>
 * By instantiating {@code new BedrockWebSocketServer(0)}, the port number {@code 0} instructs
 * the operating system TCP stack to dynamically allocate an unused ephemeral port.
 * The bound port can then be discovered via {@link #getPort()}:
 * <pre>{@code
 *   int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
 * }</pre>
 * This guarantees 100% collision-free, parallel test execution in CI/CD pipelines.
 * </p>
 *
 * <h3>3. Server Lifecycle & Zero Port Leaks:</h3>
 * <p>A production-grade server must guarantee clean teardown without resource leaks.
 * {@link BedrockWebSocketServer} implements {@link AutoCloseable}. When {@link #stop()} or
 * {@link #close()} is invoked:
 * <ol>
 *   <li>The listening {@link ServerSocketChannel} is closed, instantly unblocking
 *       the accept loop virtual thread with a {@link ClosedChannelException}.</li>
 *   <li>The accept loop terminates cleanly and joins its virtual thread.</li>
 *   <li>All active sessions in {@link WebSocketSessionRegistry} receive an RFC 6455 Close frame
 *       with status code {@code 1001 (Going Away)}, their channels are closed, and the local port
 *       is immediately released back to the operating system.</li>
 * </ol>
 * </p>
 *
 * @see WebSocketClientHandler
 * @see BedrockWebSocketSession
 * @see WebSocketSessionRegistry
 */
public class BedrockWebSocketServer implements AutoCloseable {

    private final String host;
    private final int configuredPort;
    private final Map<String, WebSocketEndpointBinding> routes = new ConcurrentHashMap<>();
    private final WebSocketSessionRegistry sessionRegistry = new WebSocketSessionRegistry();
    private final WebSocketClientHandler clientHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ServerSocketChannel serverChannel;
    private volatile Thread acceptThread;
    private volatile int boundPort;

    /**
     * Creates a WebSocket server configured for localhost and the specified port.
     * Use port 0 for an ephemeral port dynamically assigned by the OS.
     *
     * @param port Port number (0 for ephemeral).
     */
    public BedrockWebSocketServer(int port) {
        this("0.0.0.0", port);
    }

    /**
     * Creates a WebSocket server configured for a specific bind host and port.
     *
     * @param host Bind host (e.g. "0.0.0.0" or "127.0.0.1").
     * @param port Port number (0 for ephemeral).
     */
    public BedrockWebSocketServer(String host, int port) {
        this.host = (host == null || host.isBlank()) ? "0.0.0.0" : host.trim();
        this.configuredPort = port;
        this.boundPort = port;
        this.clientHandler = new WebSocketClientHandler(this.routes, this.sessionRegistry);
    }

    /**
     * Registers an endpoint binding for a specific URI path.
     * Paths are normalized to ensure a leading slash and no trailing slash (except root "/").
     *
     * @param path    Route path (e.g., "/chat", "/telemetry", or "/").
     * @param binding The endpoint binding instance.
     * @return This server instance for fluent chaining.
     */
    public BedrockWebSocketServer registerEndpoint(String path, WebSocketEndpointBinding binding) {
        Objects.requireNonNull(path, "Endpoint path cannot be null");
        Objects.requireNonNull(binding, "Endpoint binding cannot be null");
        String normalizedPath = normalizePath(path);
        routes.put(normalizedPath, binding);
        BedrockLogger.info("WS-SERVER", "Registered WebSocket endpoint at " + normalizedPath);
        return this;
    }

    /**
     * Registers an annotated endpoint instance with explicit lifecycle methods.
     *
     * @param path             Route path (e.g. "/chat").
     * @param endpointInstance Target object instance.
     * @param onOpen           Method for @OnOpen callback, or null.
     * @param onMessage        Method for @OnMessage callback, or null.
     * @param onClose          Method for @OnClose callback, or null.
     * @param onError          Method for @OnError callback, or null.
     * @return This server instance for fluent chaining.
     */
    public BedrockWebSocketServer registerEndpoint(String path,
                                                  Object endpointInstance,
                                                  Method onOpen,
                                                  Method onMessage,
                                                  Method onClose,
                                                  Method onError) {
        return registerEndpoint(path, WebSocketEndpointBinding.reflective(
                endpointInstance, onOpen, onMessage, onClose, onError
        ));
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Explicit Declarative Endpoint Registration
     *
     * <p>Scans and registers an annotated {@link com.bedrock.core.ws.annotation.BedrockSocket}
     * endpoint instance. Route path and lifecycle callbacks are discovered via
     * {@link WebSocketEndpointScanner#scan(Object)}.</p>
     *
     * @param endpointInstance Target object instance annotated with {@literal @}BedrockSocket.
     * @return This server instance for fluent chaining.
     */
    public BedrockWebSocketServer registerEndpoint(Object endpointInstance) {
        WebSocketEndpointScanner.ScannedEndpoint scanned = WebSocketEndpointScanner.scan(endpointInstance);
        return registerEndpoint(scanned.path(), scanned.binding());
    }


    /**
     * Normalizes a URI path string: ensures a leading slash and strips any trailing slash
     * (unless the path is the root "/").
     *
     * @param path Raw path string.
     * @return Normalized path string.
     */
    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Returns the bound local TCP port.
     * If configured with port 0, returns the actual ephemeral port allocated by the operating system.
     *
     * @return Bound port number, or configured port if not yet started.
     */
    public int getPort() {
        ServerSocketChannel ch = this.serverChannel;
        if (ch != null && ch.isOpen()) {
            try {
                SocketAddress local = ch.getLocalAddress();
                if (local instanceof InetSocketAddress inet) {
                    return inet.getPort();
                }
            } catch (IOException ignore) {
            }
        }
        return boundPort;
    }

    /**
     * Starts the WebSocket server and binds the ServerSocketChannel.
     * Spawns the accept loop on a dedicated Virtual Thread.
     *
     * @throws IOException If the socket fails to bind or open.
     * @throws IllegalStateException If the server has already been closed.
     */
    public synchronized void start() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("Cannot restart a closed BedrockWebSocketServer");
        }
        if (running.compareAndSet(false, true)) {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(host, configuredPort));

            this.boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

            acceptThread = Thread.ofVirtual()
                    .name("ws-accept-" + boundPort)
                    .start(this::acceptLoop);

            BedrockLogger.info("WS-SERVER", "🦖 Bedrock WebSocket Server listening on " + host + ":" + boundPort);
        }
    }

    /**
     * Stops the WebSocket server, closes the ServerSocketChannel, and terminates active client sessions.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            BedrockLogger.info("WS-SERVER", "Stopping WebSocket server on port " + boundPort + "...");

            // 1. Close ServerSocketChannel to unblock accept()
            if (serverChannel != null) {
                try {
                    serverChannel.close();
                } catch (IOException e) {
                    BedrockLogger.warn("WS-SERVER", "Error closing ServerSocketChannel: " + e.getMessage());
                }
            }

            // 2. Wait briefly for accept loop thread to exit
            if (acceptThread != null) {
                try {
                    acceptThread.join(java.time.Duration.ofMillis(500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 3. Gracefully close all active sessions with 1001 Going Away
            sessionRegistry.closeAll(WebSocketCloseStatus.GOING_AWAY_CODE, "Server shutting down");
            BedrockLogger.info("WS-SERVER", "WebSocket server stopped cleanly.");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
        }
    }

    /**
     * Checks whether the server is currently running and its ServerSocketChannel is open.
     *
     * @return True if running and socket channel is open.
     */
    public boolean isRunning() {
        return running.get() && serverChannel != null && serverChannel.isOpen();
    }

    /**
     * Returns the centralized session registry managing active client sessions.
     *
     * @return The active {@link WebSocketSessionRegistry}.
     */
    public WebSocketSessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    /**
     * The accept loop executing on a dedicated Virtual Thread.
     */
    private void acceptLoop() {
        while (running.get() && serverChannel != null && serverChannel.isOpen()) {
            try {
                SocketChannel clientChannel = serverChannel.accept();
                if (clientChannel != null) {
                    // Configure client channel for real-time WebSocket interaction
                    clientChannel.configureBlocking(true);
                    clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);

                    // Dispatch to client handler
                    clientHandler.dispatch(clientChannel);
                }
            } catch (ClosedChannelException e) {
                // Expected during clean serverChannel.close() in stop()
                break;
            } catch (IOException e) {
                if (!running.get() || serverChannel == null || !serverChannel.isOpen()) {
                    break;
                }
                BedrockLogger.error("WS-SERVER", "IOException in accept loop: " + e.getMessage());
            } catch (Throwable t) {
                if (!running.get() || serverChannel == null || !serverChannel.isOpen()) {
                    break;
                }
                BedrockLogger.error("WS-SERVER", "Fatal error in accept loop: " + t.getMessage());
            }
        }
    }
}
