# Technical Design Specification: WebSocket Sessions, Registry & Endpoint Binding
**Milestone**: Milestone 2 (M2) — Virtual-Threaded NIO Server & Sessions  
**Author**: `explorer_m2_2`  
**Target Package**: `com.bedrock.core.ws.server`  
**JDK Target**: Java 21 LTS (Zero Third-Party Dependencies)

---

## 1. Executive Summary & Architectural Overview

Milestone 2 bridges the low-level byte codecs developed in Milestone 1 (`com.bedrock.core.ws.protocol`) with high-level application ergonomics and scalable network concurrency. While `explorer_m2_1` details the `ServerSocketChannel` accept loop and `WebSocketClientHandler`, this document establishes the session abstraction, multi-client connection registry, and reflective/functional endpoint binding architecture.

### 1.1 The Role of the Three Core Components

```
+-----------------------------------------------------------------------------------------------------+
|                                      Bedrock WebSocket Architecture                                 |
+-----------------------------------------------------------------------------------------------------+
|                                                                                                     |
|  [ WebSocketClientHandler ]                                                                         |
|        |                                                                                            |
|        | 1. Instantiates & registers                                                                |
|        v                                                                                            |
|  +-------------------------------------+                +----------------------------------------+  |
|  |      BedrockWebSocketSession        |  aggregates    |       WebSocketSessionRegistry         |  |
|  | (Interface & StandardWebSocketSess) | <------------> | (ConcurrentHashMap + Resilient Bcast)  |  |
|  +-------------------------------------+                +----------------------------------------+  |
|        |                                                                    |                       |
|        | 2. Passes session to                                               | broadcast(text)       |
|        v                                                                    v                       |
|  +-----------------------------------------------------------------------------------------------+  |
|  |                                  WebSocketEndpointBinding                                     |  |
|  |                                                                                               |  |
|  |  [ReflectiveEndpointBinding] (M3 Annotations)       [FunctionalEndpointBinding] (M2 Testing)  |  |
|  |     @OnOpen    -> onOpen(session)                       onOpen(session -> ...)                |  |
|  |     @OnMessage -> onMessage(session, text)              onMessage((session, text) -> ...)     |  |
|  |     @OnClose   -> onClose(session, code, reason)        onClose((session, code, r) -> ...)    |  |
|  |     @OnError   -> onError(session, error)               onError((session, error) -> ...)      |  |
|  +-----------------------------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------------------------+
```

1. **`BedrockWebSocketSession`**:
   The thread-safe connection handle passed to user code in lifecycle methods. It encapsulates the underlying `SocketChannel`, manages session identification (UUID), attributes, and guarantees thread-safe, non-interleaving transmission of RFC 6455 text, binary, ping, and close frames.
2. **`WebSocketSessionRegistry`**:
   The concurrent connection directory maintaining active sessions per path or across the server. It powers real-time multi-client applications (such as chat rooms, multiplayer games, and telemetry feeds) via thread-safe, fail-safe broadcasting that automatically prunes stale or disconnected clients.
3. **`WebSocketEndpointBinding`**:
   The dispatcher interface isolating network I/O from application handler dispatching. It provides dual-mode support:
   - **Reflective Invocation**: Introspects user-defined classes declaring `@OnOpen`, `@OnMessage`, `@OnClose`, and `@OnError` with adaptable argument matching.
   - **Functional Invocation**: Provides a fluent lambda-based builder, enabling isolated unit and integration testing of server and session mechanics in Milestone 2 prior to Milestone 3 annotation development.

---

## 2. `BedrockWebSocketSession.java` Specification

### 2.1 Interface Definition (`BedrockWebSocketSession`)

The public interface defines the contract exposed to application developers:

```java
package com.bedrock.core.ws.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 🎓 BEDROCK TUTORIAL: Thread-Safe WebSocket Session Abstraction
 *
 * <p>A {@code BedrockWebSocketSession} represents a persistent, full-duplex RFC 6455
 * connection between a client and the Bedrock server. It decouples application code
 * from low-level NIO {@link java.nio.channels.SocketChannel} mechanics and byte buffers.</p>
 *
 * <h3>Key Guarantees:</h3>
 * <ul>
 *   <li><b>Thread Safety</b>: Multiple Virtual Threads can safely call {@link #send(String)},
 *       {@link #sendPing(byte[])}, or {@link #close()} simultaneously without corrupting
 *       the TCP byte stream or interleaving frame bytes.</li>
 *   <li><b>Carrier Thread Friendly</b>: In Java 21 Project Loom, thread synchronization
 *       is implemented using {@link java.util.concurrent.locks.ReentrantLock} rather than
 *       {@code synchronized} blocks to avoid carrier thread pinning during network I/O.</li>
 *   <li><b>Orderly Close Handshake</b>: Calling {@link #close()} sends a compliant RFC 6455
 *       Close control frame and terminates the socket cleanly.</li>
 * </ul>
 */
public interface BedrockWebSocketSession extends AutoCloseable {

    /**
     * Returns the globally unique identifier for this session (e.g. UUID string).
     */
    String getId();

    /**
     * Returns the remote client's socket address (IP and port).
     */
    SocketAddress getRemoteAddress();

    /**
     * Returns the normalized URI path on which this connection was established (e.g. "/chat").
     */
    String getPath();

    /**
     * Checks whether the underlying connection is active and ready for frame transmission.
     *
     * @return True if the session has not been closed and the network channel is open.
     */
    boolean isOpen();

    /**
     * Transmits a UTF-8 text message to the client inside an unmasked RFC 6455 text frame (Opcode 0x1).
     * This method is thread-safe and can be invoked concurrently from any number of Virtual Threads.
     *
     * @param message The text payload to transmit.
     * @throws IllegalStateException If the session is already closed.
     */
    void send(String message);

    /**
     * Transmits raw binary data to the client inside an unmasked RFC 6455 binary frame (Opcode 0x2).
     *
     * @param data The binary payload to transmit.
     * @throws IllegalStateException If the session is already closed.
     */
    void send(byte[] data);

    /**
     * Transmits an unmasked RFC 6455 Ping heartbeat control frame (Opcode 0x9) to probe client liveness.
     *
     * @param applicationData Optional probe payload (must be &lt;= 125 bytes per RFC 6455 §5.5).
     * @throws IllegalArgumentException If applicationData exceeds 125 bytes.
     * @throws IllegalStateException    If the session is already closed.
     */
    void sendPing(byte[] applicationData);

    /**
     * Initiates an orderly connection closure by sending an unmasked RFC 6455 Close frame
     * with status code 1000 (Normal Closure) and closing the underlying channel.
     */
    @Override
    void close();

    /**
     * Initiates connection closure by sending an unmasked RFC 6455 Close frame with a custom
     * status code and descriptive reason phrase.
     *
     * @param statusCode 16-bit status code (must be valid wire code; 1005, 1006, 1015 are forbidden).
     * @param reason     Optional human-readable UTF-8 reason string (payload total &lt;= 125 bytes).
     * @throws IllegalArgumentException If statusCode is wire-forbidden or payload exceeds 125 bytes.
     */
    void close(int statusCode, String reason);

    /**
     * Stores a custom user attribute associated with this session.
     *
     * @param name  Attribute key name.
     * @param value Attribute object value.
     */
    void setAttribute(String name, Object value);

    /**
     * Retrieves a stored user attribute by name.
     *
     * @param name Attribute key name.
     * @return The stored value, or null if absent.
     */
    Object getAttribute(String name);

    /**
     * Removes a stored user attribute.
     *
     * @param name Attribute key name.
     * @return The removed value, or null if absent.
     */
    Object removeAttribute(String name);

    /**
     * Returns an unmodifiable view of all stored session attributes.
     */
    Map<String, Object> getAttributes();

    // =========================================================================
    // Package-Private / Internal Server Engine Hooks
    // =========================================================================

    /**
     * Writes raw bytes directly to the underlying channel under the session write lock.
     * Used internally by {@code WebSocketClientHandler} for control frame responses (e.g. Pong replies).
     */
    void writeRaw(ByteBuffer buffer) throws IOException;

    /**
     * Marks the session as closed without re-sending a Close frame.
     * Called when the client initiates close or when an abnormal TCP termination occurs.
     */
    void markClosed();
}
```

---

### 2.2 Standard Implementation (`StandardWebSocketSession`)

The concrete session implementation resides in the same file or package as a package-private class:

```java
package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.protocol.WebSocketFrameWriter;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Standard implementation of {@link BedrockWebSocketSession}.
 */
class StandardWebSocketSession implements BedrockWebSocketSession {

    private final String id;
    private final SocketChannel channel;
    private final String path;
    private final SocketAddress remoteAddress;
    private final WebSocketSessionRegistry registry;
    
    // Concurrency control: ReentrantLock guarantees Virtual Thread friendliness (no carrier pinning)
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    StandardWebSocketSession(String id,
                             SocketChannel channel,
                             String path,
                             WebSocketSessionRegistry registry) {
        this.id = Objects.requireNonNull(id, "Session id cannot be null");
        this.channel = Objects.requireNonNull(channel, "SocketChannel cannot be null");
        this.path = Objects.requireNonNull(path, "Path cannot be null");
        this.registry = registry;
        
        SocketAddress addr = null;
        try {
            addr = channel.getRemoteAddress();
        } catch (IOException ignore) {}
        this.remoteAddress = addr;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && channel.isOpen();
    }

    @Override
    public void send(String message) {
        if (!isOpen()) {
            throw new IllegalStateException("Cannot send message: WebSocket session is closed (id=" + id + ")");
        }
        byte[] frameBytes = WebSocketFrameWriter.createTextFrame(message);
        writeFrameUnderLock(frameBytes);
    }

    @Override
    public void send(byte[] data) {
        if (!isOpen()) {
            throw new IllegalStateException("Cannot send data: WebSocket session is closed (id=" + id + ")");
        }
        byte[] frameBytes = WebSocketFrameWriter.createBinaryFrame(data);
        writeFrameUnderLock(frameBytes);
    }

    @Override
    public void sendPing(byte[] applicationData) {
        if (!isOpen()) {
            throw new IllegalStateException("Cannot send ping: WebSocket session is closed (id=" + id + ")");
        }
        byte[] frameBytes = WebSocketFrameWriter.createPingFrame(applicationData);
        writeFrameUnderLock(frameBytes);
    }

    @Override
    public void close() {
        close(WebSocketCloseStatus.NORMAL_CLOSURE_CODE, "Normal closure");
    }

    @Override
    public void close(int statusCode, String reason) {
        WebSocketCloseStatus.validateSendCode(statusCode);
        
        // Ensure close execution is atomic and idempotent
        if (closed.compareAndSet(false, true)) {
            try {
                byte[] closeFrame = WebSocketFrameWriter.createCloseFrame(statusCode, reason);
                writeRawInternal(ByteBuffer.wrap(closeFrame));
            } catch (Exception ignore) {
                // If the socket was already reset or closed by peer, ignore write error
            } finally {
                // Unregister session and close underlying socket channel
                if (registry != null) {
                    registry.unregister(id);
                }
                try {
                    channel.close();
                } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public void writeRaw(ByteBuffer buffer) throws IOException {
        writeLock.lock();
        try {
            if (!channel.isOpen()) {
                throw new IOException("Cannot write raw buffer: SocketChannel is closed");
            }
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void markClosed() {
        closed.set(true);
        if (registry != null) {
            registry.unregister(id);
        }
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException ignore) {}
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Object removeAttribute(String name) {
        return attributes.remove(name);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    private void writeFrameUnderLock(byte[] frameBytes) {
        writeLock.lock();
        try {
            if (!isOpen()) {
                throw new IllegalStateException("Session closed while waiting for write lock (id=" + id + ")");
            }
            writeRawInternal(ByteBuffer.wrap(frameBytes));
        } catch (IOException e) {
            markClosed();
            throw new IllegalStateException("I/O error transmitting frame on session " + id + ": " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    private void writeRawInternal(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    @Override
    public String toString() {
        return "BedrockWebSocketSession{" +
                "id='" + id + '\'' +
                ", path='" + path + '\'' +
                ", remoteAddress=" + remoteAddress +
                ", open=" + isOpen() +
                '}';
    }
}
```

---

### 2.3 🎓 BEDROCK TUTORIAL: Concurrency Mechanics & Carrier Pinning Elimination

#### The SocketChannel Interleaving Vulnerability
In a concurrent environment (e.g. 5,000 clients communicating in a broadcast room), multiple Virtual Threads may invoke `session.send(message)` on the same session concurrently. 
A `SocketChannel.write(ByteBuffer)` call is **not an atomic transaction**:
1. If Thread A attempts to write a 1,000-byte frame and Thread B attempts to write a 50-byte Ping frame, a partial write of Thread A followed by a write of Thread B results in the Ping frame header being physically injected *inside* the payload bytes of Thread A.
2. The remote client's frame parser will interpret these interleaved bytes as corrupted payload data or an illegal frame structure, immediately aborting the connection with RFC 6455 code `1002 (Protocol Error)`.
3. To eliminate frame interleaving, all outbound write operations must be enclosed in an exclusive write lock.

#### Why `ReentrantLock` Over `synchronized` in Java 21 Project Loom
In JDK 21, the Project Loom runtime mounts virtual threads onto a pool of carrier OS threads (the default `ForkJoinPool`). 
- When code enters a `synchronized (lock)` block and executes blocking operations (such as waiting on a lock or performing blocking socket I/O), the virtual thread is **pinned** to its underlying carrier OS thread. The carrier thread cannot be unmounted, disabling the scheduler from reusing that OS thread for other virtual threads. Under heavy load, carrier thread exhaustion occurs.
- Conversely, `java.util.concurrent.locks.ReentrantLock` was redesigned from the ground up in Project Loom. When a virtual thread waits on a `ReentrantLock`, it **yields gracefully without pinning the carrier thread**. The carrier thread returns immediately to the ForkJoinPool to process other active connections.
- For this reason, Bedrock explicitly standardizes on `ReentrantLock` across all session write paths.

---

## 3. `WebSocketSessionRegistry.java` Specification

### 3.1 Responsibilities & Thread Safety Model

The `WebSocketSessionRegistry` serves as the centralized directory of active client sessions.
1. **Thread-Safe Storage**: Backed by `ConcurrentHashMap<String, BedrockWebSocketSession>` providing lock-free reads and segmented concurrent writes.
2. **Fail-Safe Broadcasting**: Multi-client broadcasts must never fail or abort midway if one client abruptly terminates or encounters an I/O error. The registry catches per-session exceptions, logs warnings, unregisters the broken session, and proceeds to deliver the message to all remaining healthy clients.
3. **Automatic Dead-Session Pruning**: If a closed session is encountered during broadcast or lookup, it is proactively pruned from the map to prevent memory leaks.
4. **Targeted / Selective Broadcasting**: Beyond naive global broadcast, the registry provides predicate-filtered broadcast (e.g. broadcasting to specific chat rooms) and sender-excluded broadcast (`broadcastExcept`).
5. **Server Teardown Coordination**: Supports `closeAll(statusCode, reason)` to cleanly notify all connected clients with RFC 6455 code `1001 (Going Away)` when the server stops.

---

### 3.2 Class Declaration & Implementation

```java
package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

/**
 * 🎓 BEDROCK TUTORIAL: Thread-Safe Session Registry & Resilient Broadcasting
 *
 * <p>In real-time systems, the server must manage hundreds of thousands of concurrent
 * connections and broadcast messages without blocking or leaking memory.</p>
 *
 * <h3>Key Design Invariants:</h3>
 * <ul>
 *   <li><b>Zero Broadcast Interruption</b>: If client #42 abruptly resets their TCP connection,
 *       an exception during {@code send()} will NOT prevent clients #43 through #10,000
 *       from receiving the broadcast message.</li>
 *   <li><b>Self-Healing Cleanup</b>: Dead or closed sessions encountered during a broadcast
 *       are automatically evicted from the registry.</li>
 *   <li><b>Concurrent Aggregation</b>: Backed by {@link ConcurrentHashMap}, guaranteeing
 *       non-blocking multi-threaded registration and lookup.</li>
 * </ul>
 */
public class WebSocketSessionRegistry {

    private final ConcurrentMap<String, BedrockWebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registers an active WebSocket session.
     *
     * @param session The active session to track.
     */
    public void register(BedrockWebSocketSession session) {
        if (session != null && session.isOpen()) {
            sessions.put(session.getId(), session);
        }
    }

    /**
     * Unregisters a session by its unique identifier.
     *
     * @param sessionId The session identifier to remove.
     * @return The removed session, or null if not registered.
     */
    public BedrockWebSocketSession unregister(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessions.remove(sessionId);
    }

    /**
     * Retrieves an active session by its identifier.
     * If the session is found to be closed, it is automatically pruned.
     *
     * @param sessionId Unique session ID.
     * @return The active {@link BedrockWebSocketSession}, or null if absent or closed.
     */
    public BedrockWebSocketSession get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        BedrockWebSocketSession session = sessions.get(sessionId);
        if (session != null && !session.isOpen()) {
            sessions.remove(sessionId, session);
            return null;
        }
        return session;
    }

    /**
     * Returns an unmodifiable snapshot collection of all currently registered active sessions.
     */
    public Collection<BedrockWebSocketSession> getAll() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Returns the total count of currently registered sessions.
     */
    public int size() {
        return sessions.size();
    }

    /**
     * Returns true if no sessions are currently registered.
     */
    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    /**
     * Checks if a session with the specified identifier is actively registered.
     */
    public boolean contains(String sessionId) {
        return get(sessionId) != null;
    }

    /**
     * Broadcasts a UTF-8 text message across all active sessions registered in this registry.
     * Gracefully ignores or prunes already closed sessions, ensuring one broken connection
     * never halts the broadcast loop.
     *
     * @param text Message text to broadcast.
     */
    public void broadcast(String text) {
        broadcast(text, s -> true);
    }

    /**
     * Broadcasts a UTF-8 text message across all active sessions matching the given filter predicate.
     * Useful for room-based chat, tenant isolation, or role-based messaging.
     *
     * @param text   Message text to broadcast.
     * @param filter Predicate determining whether a session should receive the message.
     */
    public void broadcast(String text, Predicate<BedrockWebSocketSession> filter) {
        if (text == null || filter == null) {
            return;
        }

        for (BedrockWebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                sessions.remove(session.getId(), session);
                continue;
            }

            try {
                if (filter.test(session)) {
                    session.send(text);
                }
            } catch (Exception e) {
                BedrockLogger.warn("WS-REGISTRY", 
                        "Failed to broadcast to session " + session.getId() + ": " + e.getMessage());
                // Auto-prune failing session
                sessions.remove(session.getId(), session);
                try {
                    session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Broadcast transmission error");
                } catch (Exception ignore) {}
            }
        }
    }

    /**
     * Broadcasts a message to all active sessions EXCEPT the specified excluded session ID.
     * Standard utility pattern for chat servers ("echo to all peers except sender").
     *
     * @param text              Message to broadcast.
     * @param excludedSessionId Session ID to skip.
     */
    public void broadcastExcept(String text, String excludedSessionId) {
        broadcast(text, session -> !session.getId().equals(excludedSessionId));
    }

    /**
     * Closes all registered sessions with the given status code and reason, then clears the registry.
     * Used during server shutdown (e.g. {@code BedrockWebSocketServer.stop()}).
     *
     * @param statusCode Close status code (e.g. 1001 Going Away).
     * @param reason     Descriptive reason phrase.
     */
    public void closeAll(int statusCode, String reason) {
        List<BedrockWebSocketSession> snapshot = new ArrayList<>(sessions.values());
        sessions.clear();

        for (BedrockWebSocketSession session : snapshot) {
            try {
                session.close(statusCode, reason);
            } catch (Exception e) {
                BedrockLogger.warn("WS-REGISTRY", "Error closing session " + session.getId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Clears all session entries without sending close frames (for internal resets).
     */
    public void clear() {
        sessions.clear();
    }
}
```

---

## 4. `WebSocketEndpointBinding.java` Specification

### 4.1 Architecture & Design Motivation

A critical requirement of Bedrock Version 2.0 is **zero "annotation magic"** and **clean separation of concerns**:
- Low-level network I/O loops (`WebSocketClientHandler`) should not perform repetitive reflection scans on every incoming WebSocket frame.
- Application developers should write clean annotated classes (`@BedrockSocket("/chat")`, `@OnMessage`, `@OnOpen`), but tests and programmatic pipelines should also be able to run without reflection annotations.
- Therefore, `WebSocketEndpointBinding` defines the invocation contract, with two distinct implementations:
  1. `ReflectiveEndpointBinding`: Inspects annotated methods, pre-compiles argument mappings, and invokes them cleanly.
  2. `FunctionalEndpointBinding`: A fluent lambda-based builder ideal for testing and programmatic route mapping.

---

### 4.2 The Interface Contract

```java
package com.bedrock.core.ws.server;

/**
 * Dispatcher interface bridging network frame events to endpoint business logic.
 */
public interface WebSocketEndpointBinding {

    /**
     * Dispatched immediately after HTTP 101 Switching Protocols handshake completes.
     *
     * @param session The newly established session.
     * @throws Throwable If an error occurs during open handling.
     */
    void invokeOnOpen(BedrockWebSocketSession session) throws Throwable;

    /**
     * Dispatched when an unmasked text frame (Opcode 0x1) is received from the client.
     *
     * @param session The transmitting session.
     * @param message The UTF-8 text message payload.
     * @throws Throwable If an error occurs during message handling.
     */
    void invokeOnMessage(BedrockWebSocketSession session, String message) throws Throwable;

    /**
     * Dispatched when the connection is closed cleanly or abnormally.
     *
     * @param session    The session being closed.
     * @param statusCode RFC 6455 status code.
     * @param reason     Descriptive UTF-8 reason string.
     * @throws Throwable If an error occurs during close handling.
     */
    void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) throws Throwable;

    /**
     * Dispatched when an uncaught protocol or I/O exception occurs on the connection.
     *
     * @param session   The affected session (may be null if error occurred before session creation).
     * @param throwable The root cause exception.
     */
    void invokeOnError(BedrockWebSocketSession session, Throwable throwable);
}
```

---

### 4.3 Reflective Implementation (`ReflectiveEndpointBinding`)

The reflective invoker eliminates runtime parameter guesswork by analyzing target methods once at binding creation time. It supports flexible parameter orders and optional omissions:

| Event | Permitted Method Parameter Signatures |
| :--- | :--- |
| **`@OnOpen`** | `(BedrockWebSocketSession session)`, `()` |
| **`@OnMessage`** | `(BedrockWebSocketSession session, String message)`, `(String message, BedrockWebSocketSession session)`, `(String message)`, `(BedrockWebSocketSession session)` |
| **`@OnClose`** | `(BedrockWebSocketSession session, int code, String reason)`, `(BedrockWebSocketSession session, int code)`, `(BedrockWebSocketSession session)`, `(int code, String reason)`, `()` |
| **`@OnError`** | `(BedrockWebSocketSession session, Throwable throwable)`, `(Throwable throwable, BedrockWebSocketSession session)`, `(Throwable throwable)`, `()` |

```java
package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Objects;

/**
 * 🎓 BEDROCK TUTORIAL: Explicit Reflection Dispatching Without Bytecode Proxies
 *
 * <p>Enterprise frameworks often generate 15 layers of dynamic runtime CGLIB/ByteBuddy proxies
 * to invoke user methods. In Bedrock, we keep reflection transparent and inspectable:</p>
 * <ol>
 *   <li>The target class instance and methods are inspected during endpoint registration.</li>
 *   <li>Parameter positions are analyzed and cached once.</li>
 *   <li>Invocation executes directly via {@link Method#invoke(Object, Object...)} without proxies.</li>
 * </ol>
 */
public class ReflectiveEndpointBinding implements WebSocketEndpointBinding {

    private final Object targetInstance;
    private final Method onOpenMethod;
    private final Method onMessageMethod;
    private final Method onCloseMethod;
    private final Method onErrorMethod;

    public ReflectiveEndpointBinding(Object targetInstance,
                                    Method onOpenMethod,
                                    Method onMessageMethod,
                                    Method onCloseMethod,
                                    Method onErrorMethod) {
        this.targetInstance = Objects.requireNonNull(targetInstance, "Target instance cannot be null");
        this.onOpenMethod = makeAccessible(onOpenMethod);
        this.onMessageMethod = makeAccessible(onMessageMethod);
        this.onCloseMethod = makeAccessible(onCloseMethod);
        this.onErrorMethod = makeAccessible(onErrorMethod);
    }

    private static Method makeAccessible(Method m) {
        if (m != null) {
            m.setAccessible(true);
        }
        return m;
    }

    @Override
    public void invokeOnOpen(BedrockWebSocketSession session) throws Throwable {
        if (onOpenMethod == null) return;
        Object[] args = matchArgs(onOpenMethod, session, null, 0, null, null);
        invoke(onOpenMethod, args);
    }

    @Override
    public void invokeOnMessage(BedrockWebSocketSession session, String message) throws Throwable {
        if (onMessageMethod == null) return;
        Object[] args = matchArgs(onMessageMethod, session, message, 0, null, null);
        invoke(onMessageMethod, args);
    }

    @Override
    public void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) throws Throwable {
        if (onCloseMethod == null) return;
        Object[] args = matchArgs(onCloseMethod, session, null, statusCode, reason, null);
        invoke(onCloseMethod, args);
    }

    @Override
    public void invokeOnError(BedrockWebSocketSession session, Throwable throwable) {
        if (onErrorMethod == null) {
            BedrockLogger.error("WS-ENDPOINT", "Unhandled error in session " + 
                    (session != null ? session.getId() : "unknown") + ": " + throwable.getMessage());
            return;
        }
        try {
            Object[] args = matchArgs(onErrorMethod, session, null, 0, null, throwable);
            invoke(onErrorMethod, args);
        } catch (Throwable t) {
            BedrockLogger.error("WS-ENDPOINT", "Exception inside @OnError handler: " + t.getMessage());
        }
    }

    private void invoke(Method method, Object[] args) throws Throwable {
        try {
            method.invoke(targetInstance, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }

    private static Object[] matchArgs(Method method,
                                      BedrockWebSocketSession session,
                                      String message,
                                      int statusCode,
                                      String reason,
                                      Throwable throwable) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();

            if (BedrockWebSocketSession.class.isAssignableFrom(type)) {
                args[i] = session;
            } else if (type.equals(String.class)) {
                // If message is present, prioritize message; otherwise use close reason
                args[i] = message != null ? message : reason;
            } else if (type.equals(int.class) || type.equals(Integer.class)) {
                args[i] = statusCode;
            } else if (Throwable.class.isAssignableFrom(type)) {
                args[i] = throwable;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    public Object getTargetInstance() {
        return targetInstance;
    }
}
```

---

### 4.4 Functional Implementation (`FunctionalEndpointBinding`)

For test suites and programmatic architectures, `FunctionalEndpointBinding` allows building endpoints using standard Java lambdas without annotations:

```java
package com.bedrock.core.ws.server;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Functional builder for {@link WebSocketEndpointBinding}.
 * Enables concise lambda configuration for unit and integration testing.
 */
public class FunctionalEndpointBinding implements WebSocketEndpointBinding {

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    private final Consumer<BedrockWebSocketSession> onOpen;
    private final BiConsumer<BedrockWebSocketSession, String> onMessage;
    private final TriConsumer<BedrockWebSocketSession, Integer, String> onClose;
    private final BiConsumer<BedrockWebSocketSession, Throwable> onError;

    private FunctionalEndpointBinding(Builder builder) {
        this.onOpen = builder.onOpen;
        this.onMessage = builder.onMessage;
        this.onClose = builder.onClose;
        this.onError = builder.onError;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void invokeOnOpen(BedrockWebSocketSession session) {
        if (onOpen != null) onOpen.accept(session);
    }

    @Override
    public void invokeOnMessage(BedrockWebSocketSession session, String message) {
        if (onMessage != null) onMessage.accept(session, message);
    }

    @Override
    public void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) {
        if (onClose != null) onClose.accept(session, statusCode, reason);
    }

    @Override
    public void invokeOnError(BedrockWebSocketSession session, Throwable throwable) {
        if (onError != null) onError.accept(session, throwable);
    }

    public static class Builder {
        private Consumer<BedrockWebSocketSession> onOpen;
        private BiConsumer<BedrockWebSocketSession, String> onMessage;
        private TriConsumer<BedrockWebSocketSession, Integer, String> onClose;
        private BiConsumer<BedrockWebSocketSession, Throwable> onError;

        public Builder onOpen(Consumer<BedrockWebSocketSession> handler) {
            this.onOpen = handler;
            return this;
        }

        public Builder onMessage(BiConsumer<BedrockWebSocketSession, String> handler) {
            this.onMessage = handler;
            return this;
        }

        public Builder onClose(TriConsumer<BedrockWebSocketSession, Integer, String> handler) {
            this.onClose = handler;
            return this;
        }

        public Builder onError(BiConsumer<BedrockWebSocketSession, Throwable> handler) {
            this.onError = handler;
            return this;
        }

        public FunctionalEndpointBinding build() {
            return new FunctionalEndpointBinding(this);
        }
    }
}
```

---

## 5. Contract Harmony & Integration Matrix

### 5.1 Protocol to Server Mapping

| Caller Component | Target Method | Target Class | Purpose |
| :--- | :--- | :--- | :--- |
| `StandardWebSocketSession.send(text)` | `createTextFrame(text)` | `WebSocketFrameWriter` | Serializes unmasked UTF-8 text frame |
| `StandardWebSocketSession.send(data)` | `createBinaryFrame(data)` | `WebSocketFrameWriter` | Serializes unmasked binary frame |
| `StandardWebSocketSession.sendPing(appData)` | `createPingFrame(appData)` | `WebSocketFrameWriter` | Serializes unmasked Ping control frame |
| `StandardWebSocketSession.close(code, reason)` | `createCloseFrame(code, reason)` | `WebSocketFrameWriter` | Serializes unmasked Close control frame |
| `StandardWebSocketSession.close(code, reason)` | `validateSendCode(code)` | `WebSocketCloseStatus` | Guards against wire-forbidden codes (1005, 1006, 1015) |
| `WebSocketClientHandler` (Ping reply) | `session.writeRaw(pongBytes)` | `BedrockWebSocketSession` | Thread-safe atomic transmission of Pong |
| `WebSocketClientHandler` (Close echo) | `session.writeRaw(closeBytes)` | `BedrockWebSocketSession` | Thread-safe echo of Close frame |
| `WebSocketClientHandler` (Lifecycle) | `registry.register(session)` | `WebSocketSessionRegistry` | Tracks active session |
| `WebSocketClientHandler` (Teardown) | `registry.unregister(sessionId)` | `WebSocketSessionRegistry` | Removes dead session |
| `WebSocketClientHandler` (Events) | `binding.invokeOn*(...)` | `WebSocketEndpointBinding` | Dispatches lifecycle events to application |

---

### 5.2 Concurrency Scenarios & Resilience Matrix

| Scenario | Risk | Bedrock Architecture Defense |
| :--- | :--- | :--- |
| **Concurrent `session.send()`** | TCP frame byte interleaving; client parser failure (1002). | `ReentrantLock writeLock` serializes frame writes completely without pinning carrier threads. |
| **Concurrent broadcast & client disconnect** | `IOException` on broken socket aborts loop, dropping message for remaining clients. | Per-session `try/catch` in `registry.broadcast()`, logs warning, auto-prunes failing session, continues loop. |
| **Broadcast while sessions added/removed** | `ConcurrentModificationException` during iteration. | Backed by `ConcurrentHashMap.values()` which provides weakly-consistent, non-failing iterators. |
| **Double `session.close()` call** | Multiple Close frames sent; double channel close; race conditions. | `closed.compareAndSet(false, true)` ensures Close frame is transmitted exactly once; subsequent calls are no-ops. |
| **Send on closed session** | Broken pipe error, corrupt TCP state. | `isOpen()` check throws descriptive `IllegalStateException` immediately without attempting channel write. |
| **Malformed close code** | Sending status 1005/1006 on the wire violates RFC 6455 §7.4.1. | `WebSocketCloseStatus.validateSendCode()` throws `IllegalArgumentException` before serializing frame. |

---

## 6. Implementation Checklist for Worker

- [ ] Create `com.bedrock.core.ws.server.BedrockWebSocketSession` (interface and `StandardWebSocketSession` class).
- [ ] Create `com.bedrock.core.ws.server.WebSocketSessionRegistry`.
- [ ] Create `com.bedrock.core.ws.server.WebSocketEndpointBinding` (interface, `ReflectiveEndpointBinding`, and `FunctionalEndpointBinding`).
- [ ] Ensure 100% zero third-party dependencies in `bedrock-core`.
- [ ] Ensure all public classes include rich `🎓 BEDROCK TUTORIAL` Javadocs explaining Loom Virtual Threads, carrier unmounting, and ReentrantLock vs synchronized.
- [ ] Verify seamless compilation with `mvn test-compile`.

---
*End of Design Specification.*
