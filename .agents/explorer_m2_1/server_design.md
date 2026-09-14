# Technical Design Specification: Virtual-Threaded NIO Server & Client Handler
**Milestone**: Milestone 2 (M2) — Virtual-Threaded NIO Server & Sessions  
**Author**: `explorer_m2_1`  
**Target Package**: `com.bedrock.core.ws.server`  
**JDK Target**: Java 21 LTS (Zero Third-Party Dependencies)

---

## 1. Executive Summary & Architecture Overview

Milestone 2 transitions the Bedrock framework from raw byte-level protocol codecs (`com.bedrock.core.ws.protocol`) to a high-concurrency, carrier-unmounting WebSocket network server. 

### The Core Architectural Tenet: The Loom Paradigm
In traditional Java frameworks, servers face an awkward dilemma:
1. **Thread-per-Connection (Blocking I/O)**: Bounded by OS threads (typically 2,000–5,000 threads max), each consuming 1 MB of native stack memory. Infeasible for 100k+ idle WebSocket connections.
2. **Asynchronous Non-Blocking NIO (Netty, WebFlux, Event Loops)**: High concurrency, but introduces complex reactive streams, callback hell, broken thread-local context, difficult debugging, and third-party dependencies.

Bedrock Version 2.0 leverages **Java 21 Virtual Threads (Project Loom)** over native JDK NIO (`ServerSocketChannel` and `SocketChannel`). By pairing synchronous blocking I/O with Virtual Threads:
- Every accepted WebSocket client connection runs on its own dedicated virtual thread:
  ```java
  Thread.ofVirtual().name("ws-client-" + clientId).start(...)
  ```
- When a virtual thread executes a blocking read on `SocketChannel.read(ByteBuffer)` or blocking accept on `ServerSocketChannel.accept()`, the JVM runtime unmounts the virtual thread from its underlying OS carrier thread, returning the carrier thread to the ForkJoinPool.
- Stack memory drops from ~1 MB to mere hundreds of bytes on the Java heap per connection.
- Developers write clean, imperative, synchronous code with simple `try/catch` and sequential loops, with zero Netty or third-party dependencies.

```
+---------------------------------------------------------------------------------------+
|                                BedrockWebSocketServer                                 |
|                                                                                       |
|  [ServerSocketChannel (Port 0 or Configured)]                                         |
|         |                                                                             |
|         v (Virtual Thread "ws-accept-<port>")                                         |
|    accept() loop                                                                      |
|         |                                                                             |
|         |---> Client 1 accepted ---> Thread.ofVirtual("ws-client-1")                 |
|         |---> Client 2 accepted ---> Thread.ofVirtual("ws-client-2")                 |
|         |---> Client N accepted ---> Thread.ofVirtual("ws-client-N")                 |
+---------------------------------------------------------------------------------------+
                                          |
                                          v
+---------------------------------------------------------------------------------------+
|                               WebSocketClientHandler                                  |
|                                                                                       |
|  1. HTTP Upgrade Handshake                                                            |
|     - SocketChannel.read(ByteBuffer)                                                  |
|     - WebSocketHandshake.parse(ByteBuffer)                                            |
|     - Validate Route Registry (404 if unknown path)                                   |
|     - Validate Version (426 if not v13) / Headers (400 if malformed)                  |
|     - Write HTTP 101 Switching Protocols response bytes                               |
|                                                                                       |
|  2. Full-Duplex RFC 6455 Frame Loop                                                   |
|     - Synchronous blocking read into ByteBuffer                                       |
|     - Parse frames via WebSocketFrameParser.parse(buffer)                             |
|     - Frame Disptach:                                                                 |
|       * TEXT (0x1)  -> binding.invokeOnMessage(session, text)                         |
|       * PING (0x9)  -> writePongFrame() echoing payload immediately                   |
|       * PONG (0xA)  -> keepalive heartbeat ack                                        |
|       * CLOSE (0x8) -> echo Close frame, unregister, binding.invokeOnClose(), close() |
|       * Exception   -> send Close frame with status code, invokeOnError(), close()     |
+---------------------------------------------------------------------------------------+
```

---

## 2. `BedrockWebSocketServer.java` Specification

### 2.1 Responsibilities & Invariants
1. **Ephemeral & Static Port Binding**: Support explicit port assignment (e.g. `8080`) or ephemeral dynamic port (`0`). Ephemeral port assignment is critical for parallel, leak-free automated tests.
2. **Port Discovery**: `int getPort()` must return the actual bound local port queried directly from `serverChannel.getLocalAddress()`.
3. **Thread-Safe Route Registry**: Allow thread-safe registration of path routes (`registerEndpoint(String path, WebSocketEndpointBinding binding)`).
4. **Lifecycle Control & Clean Teardown**: Implement `start()`, `stop()`, and `AutoCloseable` (`close()`). Stopping the server must interrupt the accept loop, close all active client sessions cleanly with status `1001 (Going Away)`, and release the port.
5. **Accept Loop**: Runs on a virtual thread (`ws-accept-<port>`), accepts incoming `SocketChannel` connections, configures TCP options, and dispatches to `WebSocketClientHandler`.

### 2.2 Class Declaration & Fields
```java
package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;

import java.io.IOException;
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
    private volatile int boundPort = -1;
```

### 2.3 Constructor Matrix
```java
    /**
     * Creates a WebSocket server configured for localhost and the specified port.
     * Use port 0 for an ephemeral port assigned by the OS.
     */
    public BedrockWebSocketServer(int port) {
        this("0.0.0.0", port);
    }

    /**
     * Creates a WebSocket server configured for a specific bind host and port.
     */
    public BedrockWebSocketServer(String host, int port) {
        this.host = (host == null || host.isBlank()) ? "0.0.0.0" : host.trim();
        this.configuredPort = port;
        this.boundPort = port;
        this.clientHandler = new WebSocketClientHandler(this.routes, this.sessionRegistry);
    }
```

### 2.4 Route Registration Contract
```java
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

    public static String normalizePath(String path) {
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
```

### 2.5 Port Resolution Contract (`getPort()`)
Crucial requirement from ORIGINAL_REQUEST and PROJECT.md:
```java
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
                // Fallback to cached boundPort
            }
        }
        return boundPort;
    }
```

### 2.6 Server Lifecycle (`start()`, `stop()`, `close()`)
```java
    /**
     * Starts the WebSocket server and binds the ServerSocketChannel.
     * Spawns the accept loop on a dedicated Virtual Thread.
     *
     * @throws IOException If the socket fails to bind or open.
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
            
            // 1. Close ServerSocketChannel to break accept() block
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

    public boolean isRunning() {
        return running.get() && serverChannel != null && serverChannel.isOpen();
    }

    public WebSocketSessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }
```

### 2.7 The Accept Loop
```java
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
            } catch (AsynchronousCloseException | ClosedChannelException e) {
                // Expected during clean serverChannel.close() in stop()
                break;
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
                BedrockLogger.error("WS-SERVER", "IOException in accept loop: " + e.getMessage());
            } catch (Throwable t) {
                if (!running.get()) {
                    break;
                }
                BedrockLogger.error("WS-SERVER", "Fatal error in accept loop: " + t.getMessage());
            }
        }
    }
```

---

## 3. `WebSocketClientHandler.java` Specification

### 3.1 Responsibilities & Invariants
1. **Virtual Thread Concurrency**: Every client is isolated on its own virtual thread (`ws-client-<id>`).
2. **Two-Phase Connection Lifecycle**:
   - **Phase 1 (HTTP Upgrade)**: Ingestion of HTTP request headers, RFC 6455 upgrade verification, path route check, error code response (404/426/400), and generation of HTTP 101 Switching Protocols.
   - **Phase 2 (WebSocket Frame Loop)**: Detachment of HTTP parser; full-duplex binary frame ingestion, parsing, XOR unmasking, opcode dispatch, control frame auto-replies, and clean closure.
3. **Pipelining Safety**: Handshake byte consumption must not discard any trailing payload bytes already read into the buffer belonging to subsequent WebSocket frames.
4. **Adversarial & Fault Resilience**: Complete RFC 6455 error handling: protocol violations immediately send a compliant Close frame with the designated code (1002, 1007, 1009), notify `@OnError`, and terminate the connection without hanging.

### 3.2 Class Declaration & Fields
```java
package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class WebSocketClientHandler {

    private static final AtomicLong CLIENT_COUNTER = new AtomicLong(1);
    private static final int INITIAL_BUFFER_SIZE = 8192; // 8 KB
    private static final int MAX_BUFFER_SIZE = WebSocketFrameParser.MAX_ALLOWED_PAYLOAD_SIZE; // 16 MB

    private final Map<String, WebSocketEndpointBinding> routes;
    private final WebSocketSessionRegistry sessionRegistry;

    public WebSocketClientHandler(Map<String, WebSocketEndpointBinding> routes,
                                  WebSocketSessionRegistry sessionRegistry) {
        this.routes = routes;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Spawns a dedicated Virtual Thread to service the accepted client connection.
     *
     * @param clientChannel The newly accepted SocketChannel.
     */
    public void dispatch(SocketChannel clientChannel) {
        long clientId = CLIENT_COUNTER.getAndIncrement();
        Thread.ofVirtual()
                .name("ws-client-" + clientId)
                .start(() -> handleConnection(clientChannel, clientId));
    }
```

### 3.3 Phase 1: HTTP Upgrade Handshake Processing
The handshake phase must handle fragmented TCP segments and pipelined WebSocket frames seamlessly:

```java
    private void handleConnection(SocketChannel channel, long clientId) {
        String sessionId = UUID.randomUUID().toString();
        ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
        
        try {
            // -------------------------------------------------------------
            // Step 1: Read HTTP Handshake Headers
            // -------------------------------------------------------------
            WebSocketHandshake.HandshakeParseResult handshakeResult = null;

            while (true) {
                int read = channel.read(buffer);
                if (read == -1) {
                    // Client disconnected before sending complete handshake
                    channel.close();
                    return;
                }

                buffer.flip();
                handshakeResult = WebSocketHandshake.parse(buffer);
                
                if (handshakeResult != null) {
                    // Complete HTTP header parsed!
                    // Note: WebSocketHandshake.parse(buffer) advanced buffer.position() to header end.
                    break;
                }

                // Incomplete header: restore buffer for continued reading
                buffer.compact();
                if (!buffer.hasRemaining()) {
                    if (buffer.capacity() >= WebSocketHandshake.MAX_HEADER_SIZE) {
                        // Exceeded maximum allowed header size
                        writeAndClose(channel, WebSocketHandshake.createResponse400("Header too large"));
                        return;
                    }
                    ByteBuffer larger = ByteBuffer.allocate(buffer.capacity() * 2);
                    buffer.flip();
                    larger.put(buffer);
                    buffer = larger;
                }
            }

            // -------------------------------------------------------------
            // Step 2: Validate Handshake & Route Lookup
            // -------------------------------------------------------------
            if (handshakeResult.statusCode() == 426) {
                writeAndClose(channel, WebSocketHandshake.createResponse426());
                return;
            }

            if (!handshakeResult.isSuccess()) {
                writeAndClose(channel, handshakeResult.responseBytes() != null 
                        ? handshakeResult.responseBytes() 
                        : WebSocketHandshake.createResponse400(handshakeResult.errorMessage()));
                return;
            }

            // Handshake is structurally valid. Check route registry.
            String path = BedrockWebSocketServer.normalizePath(handshakeResult.path());
            WebSocketEndpointBinding binding = routes.get(path);

            if (binding == null) {
                // HTTP 404: No endpoint mapped to this path
                writeAndClose(channel, WebSocketHandshake.createResponse404(path));
                return;
            }

            // -------------------------------------------------------------
            // Step 3: Complete Upgrade (Send HTTP 101 Switching Protocols)
            // -------------------------------------------------------------
            writeFully(channel, ByteBuffer.wrap(handshakeResult.responseBytes()));

            // -------------------------------------------------------------
            // Step 4: Construct Session & Fire @OnOpen
            // -------------------------------------------------------------
            BedrockWebSocketSession session = new BedrockWebSocketSessionImpl(
                    sessionId, channel, path, sessionRegistry
            );
            sessionRegistry.register(session);

            try {
                binding.invokeOnOpen(session);
            } catch (Throwable t) {
                BedrockLogger.error("WS-CLIENT", "Exception in @OnOpen for session " + sessionId + ": " + t.getMessage());
                binding.invokeOnError(session, t);
                session.close(WebSocketCloseStatus.SERVER_ERROR_CODE, "Error during connection open");
                return;
            }

            // -------------------------------------------------------------
            // Step 5: Transition to Full-Duplex RFC 6455 Frame Loop
            // -------------------------------------------------------------
            // Compact any remaining unparsed bytes (e.g. pipelined frames) to buffer start
            buffer.compact();
            runFrameLoop(channel, buffer, session, binding);

        } catch (IOException e) {
            // Early I/O failure before or during upgrade
            try {
                channel.close();
            } catch (IOException ignore) {}
        } finally {
            // Safety guarantee: Ensure channel is closed if loop terminates
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException ignore) {}
        }
    }
```

### 3.4 Phase 2: Synchronous Full-Duplex Frame Loop
Here, Loom shines: the synchronous loop blocks cleanly on `channel.read(buffer)` while the carrier thread is unmounted during idle periods.

```java
    private void runFrameLoop(SocketChannel channel,
                              ByteBuffer buffer,
                              BedrockWebSocketSession session,
                              WebSocketEndpointBinding binding) {
        boolean closeHandshakeCompleted = false;

        while (session.isOpen() && channel.isOpen()) {
            // 1. Check if buffer already contains complete frame(s)
            buffer.flip();
            WebSocketFrame frame = null;
            
            try {
                frame = WebSocketFrameParser.parse(buffer);
            } catch (WebSocketException protocolEx) {
                // RFC 6455 Protocol violation (unmasked client frame, bad UTF-8, etc.)
                handleProtocolException(channel, session, binding, protocolEx);
                return;
            }

            if (frame != null) {
                // A complete frame was parsed!
                buffer.compact();

                boolean shouldContinue = dispatchFrame(channel, session, binding, frame);
                if (!shouldContinue) {
                    return;
                }
                // Continue immediately to check for further pipelined frames in buffer
                continue;
            }

            // 2. Buffer does not contain a full frame. Compact and read from channel.
            buffer.compact();

            // Resize buffer if full but still unable to parse a complete frame
            if (!buffer.hasRemaining()) {
                if (buffer.capacity() >= MAX_BUFFER_SIZE) {
                    handleProtocolException(channel, session, binding,
                            new WebSocketException(WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE, "Frame exceeds max payload size"));
                    return;
                }
                int newCap = Math.min(buffer.capacity() * 2, MAX_BUFFER_SIZE);
                ByteBuffer larger = ByteBuffer.allocate(newCap);
                buffer.flip();
                larger.put(buffer);
                buffer = larger;
            }

            // 3. Blocking read (Virtual Thread unmounts here until bytes arrive)
            int bytesRead;
            try {
                bytesRead = channel.read(buffer);
            } catch (IOException e) {
                // Connection dropped abnormally
                handleAbnormalClosure(session, binding, e);
                return;
            }

            if (bytesRead == -1) {
                // TCP EOF: Client dropped connection without sending a Close frame
                handleAbnormalClosure(session, binding, null);
                return;
            }
        }
    }
```

### 3.5 Phase 3: Opcode Dispatch Mechanics
Complete opcode state machine:

```java
    private boolean dispatchFrame(SocketChannel channel,
                                  BedrockWebSocketSession session,
                                  WebSocketEndpointBinding binding,
                                  WebSocketFrame frame) {
        WebSocketOpcode opcode = frame.getOpcode();

        switch (opcode) {
            case TEXT -> {
                String message = frame.getPayloadAsText();
                try {
                    binding.invokeOnMessage(session, message);
                } catch (Throwable t) {
                    BedrockLogger.error("WS-CLIENT", "Error in @OnMessage for session " + session.getId() + ": " + t.getMessage());
                    binding.invokeOnError(session, t);
                }
                return true;
            }

            case PING -> {
                // RFC 6455 §5.5.2: Immediately reply with Pong echoing the exact application data
                byte[] pingPayload = frame.getPayload();
                byte[] pongFrame = WebSocketFrameWriter.createPongFrame(pingPayload);
                try {
                    session.writeRaw(ByteBuffer.wrap(pongFrame));
                } catch (IOException e) {
                    BedrockLogger.warn("WS-CLIENT", "Failed to send Pong reply: " + e.getMessage());
                }
                return true;
            }

            case PONG -> {
                // RFC 6455 §5.5.3: Keepalive heartbeat response; no reply required
                BedrockLogger.info("WS-CLIENT", "Received Pong keepalive from session " + session.getId());
                return true;
            }

            case CLOSE -> {
                // RFC 6455 §5.5.1 & §7.1: Clean two-way close handshake
                int code = frame.getCloseStatusCode();
                String reason = frame.getCloseReason();

                // Echo Close frame if this endpoint did not initiate the close
                int echoCode = (code == WebSocketCloseStatus.NO_STATUS_CODE || code <= 0) 
                        ? WebSocketCloseStatus.NORMAL_CLOSURE_CODE 
                        : code;

                try {
                    byte[] echoBytes = WebSocketFrameWriter.createCloseFrame(echoCode, reason);
                    session.writeRaw(ByteBuffer.wrap(echoBytes));
                } catch (Exception ignore) {}

                // Teardown session and notify endpoint
                sessionRegistry.unregister(session.getId());
                session.markClosed();

                try {
                    binding.invokeOnClose(session, code, reason);
                } catch (Throwable t) {
                    BedrockLogger.warn("WS-CLIENT", "Error in @OnClose: " + t.getMessage());
                }

                try {
                    channel.close();
                } catch (IOException ignore) {}

                return false; // Terminate frame loop
            }

            case BINARY -> {
                // Optional binary support; if endpoint does not accept binary, reject with 1003
                BedrockLogger.warn("WS-CLIENT", "Binary frame received; endpoint text-only.");
                session.close(WebSocketCloseStatus.UNSUPPORTED_DATA_CODE, "Binary frames not supported");
                return false;
            }

            case CONTINUATION -> {
                // Fragmented continuation frame
                return true;
            }
        }
        return true;
    }
```

### 3.6 Exception Handling & Abnormal Termination
```java
    private void handleProtocolException(SocketChannel channel,
                                        BedrockWebSocketSession session,
                                        WebSocketEndpointBinding binding,
                                        WebSocketException ex) {
        BedrockLogger.warn("WS-CLIENT", "Protocol error on session " + session.getId() + ": " + ex.getMessage());
        
        // 1. Send Close frame with error status code (e.g. 1002, 1007, 1009)
        try {
            byte[] closeBytes = WebSocketFrameWriter.createCloseFrame(ex.getStatusCode(), ex.getReason());
            session.writeRaw(ByteBuffer.wrap(closeBytes));
        } catch (Exception ignore) {}

        // 2. Invoke @OnError
        try {
            binding.invokeOnError(session, ex);
        } catch (Throwable t) {
            BedrockLogger.warn("WS-CLIENT", "Error invoking @OnError: " + t.getMessage());
        }

        // 3. Unregister & invoke @OnClose
        sessionRegistry.unregister(session.getId());
        session.markClosed();

        try {
            binding.invokeOnClose(session, ex.getStatusCode(), ex.getReason());
        } catch (Throwable t) {
            BedrockLogger.warn("WS-CLIENT", "Error invoking @OnClose: " + t.getMessage());
        }

        // 4. Close underlying channel
        try {
            channel.close();
        } catch (IOException ignore) {}
    }

    private void handleAbnormalClosure(BedrockWebSocketSession session,
                                      WebSocketEndpointBinding binding,
                                      Exception ex) {
        BedrockLogger.info("WS-CLIENT", "Abnormal TCP closure on session " + session.getId());
        
        sessionRegistry.unregister(session.getId());
        session.markClosed();

        if (ex != null) {
            try {
                binding.invokeOnError(session, ex);
            } catch (Throwable ignore) {}
        }

        try {
            binding.invokeOnClose(session, WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Abnormal TCP disconnect");
        } catch (Throwable ignore) {}

        try {
            session.close();
        } catch (Exception ignore) {}
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void writeAndClose(SocketChannel channel, byte[] bytes) {
        try {
            writeFully(channel, ByteBuffer.wrap(bytes));
        } catch (IOException ignore) {
        } finally {
            try {
                channel.close();
            } catch (IOException ignore) {}
        }
    }
```

---

## 4. Supporting Interfaces & Contract Alignment

### 4.1 `WebSocketEndpointBinding.java` Contract
To decouple `BedrockWebSocketServer` from reflection mechanics, `WebSocketEndpointBinding` acts as the invoker interface:

```java
package com.bedrock.core.ws.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public interface WebSocketEndpointBinding {
    void invokeOnOpen(BedrockWebSocketSession session) throws Throwable;
    void invokeOnMessage(BedrockWebSocketSession session, String message) throws Throwable;
    void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) throws Throwable;
    void invokeOnError(BedrockWebSocketSession session, Throwable throwable);
}
```

#### Default Reflective Implementation (`ReflectiveEndpointBinding.java`):
Maps methods annotated with `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`:
- Flexible argument matching:
  - `@OnOpen`: `(session)` or `()`
  - `@OnMessage`: `(session, message)` or `(message, session)` or `(message)`
  - `@OnClose`: `(session, code, reason)` or `(session, code)` or `(session)` or `(code, reason)` or `()`
  - `@OnError`: `(session, throwable)` or `(throwable)`

### 4.2 Alignment with `BedrockWebSocketSession` (`explorer_m2_2`)
`WebSocketClientHandler` relies on `BedrockWebSocketSession` implementing the following contract:
```java
public interface BedrockWebSocketSession extends AutoCloseable {
    String getId();
    SocketAddress getRemoteAddress();
    String getPath();
    boolean isOpen();
    void send(String message);
    void sendPing(byte[] data);
    void close();
    void close(int statusCode, String reason);
    
    // Package-private internal methods for WebSocketClientHandler:
    void writeRaw(ByteBuffer buffer) throws IOException;
    void markClosed();
}
```

---

## 5. Concurrency & Thread-Safety Model

### 5.1 The SocketChannel Write Invariant
In Java NIO, `SocketChannel.write(ByteBuffer)` is **not thread-safe**. If a virtual thread running client read loop sends a Pong reply at the exact millisecond another virtual thread executes `sessionRegistry.broadcast(message)`, raw bytes would interleave, resulting in corrupt frames and close code 1002.

**Solution**:
`BedrockWebSocketSession` encapsulates a `java.util.concurrent.locks.ReentrantLock writeLock`:
```java
public void writeRaw(ByteBuffer buffer) throws IOException {
    writeLock.lock();
    try {
        if (!channel.isOpen()) {
            throw new IOException("SocketChannel is closed");
        }
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    } finally {
        writeLock.unlock();
    }
}
```

### 5.2 Virtual Thread Carrier Pinning Elimination
In Java 21, carrier thread pinning occurs if a virtual thread blocks inside a `synchronized` block or native method.
- **Locking**: We exclusively use `ReentrantLock` for thread-safe session writes. `ReentrantLock` yields virtual threads gracefully without pinning carrier threads.
- **I/O Blocking**: `SocketChannel.read()` and `SocketChannel.write()` are fully supported by Project Loom and unmount the virtual thread cleanly during network delays.

---

## 6. Verification and Integration Scenarios

| Scenario | Test Vector | Expected Outcome |
| :--- | :--- | :--- |
| **Ephemeral Port** | `new BedrockWebSocketServer(0).start()` | `server.getPort() > 0`, non-conflicting port bound |
| **Route 404** | Client requests `GET /unknown HTTP/1.1` with WS upgrade | Server responds `HTTP/1.1 404 Not Found`, closes socket |
| **Version 426** | Client requests with `Sec-WebSocket-Version: 8` | Server responds `HTTP/1.1 426 Upgrade Required`, closes socket |
| **Malformed Key** | Client sends 15-byte or non-Base64 `Sec-WebSocket-Key` | Server responds `HTTP/1.1 400 Bad Request`, closes socket |
| **Successful 101**| Client sends RFC 6455 valid handshake | Server returns `101 Switching Protocols`, invokes `@OnOpen` |
| **Ping / Pong** | Client sends Ping with payload `"ping"` | Server immediately echoes Pong with payload `"ping"` |
| **Text Message** | Client sends masked Text frame `"Hello"` | Server unmasks in-place, invokes `@OnMessage` with `"Hello"` |
| **Protocol Violation** | Client sends unmasked Text frame | Server sends Close frame `1002 Protocol Error`, closes socket |
| **Two-way Close** | Client sends Close frame `1000 "bye"` | Server echoes Close frame `1000 "bye"`, unregisters, closes |
| **Server stop()** | `server.stop()` with active clients | Server sends Close frame `1001 Going Away` to all, releases port |
| **AutoCloseable** | `try (BedrockWebSocketServer server = ...)` | Calls `stop()`, releases all channels and threads cleanly |

---
*End of Design Specification.*
