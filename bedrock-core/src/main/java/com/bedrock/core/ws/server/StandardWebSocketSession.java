package com.bedrock.core.ws.server;

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
 * 🎓 BEDROCK TUTORIAL: Carrier-Unmounting Standard WebSocket Session
 *
 * <p>This class provides the concrete implementation of {@link BedrockWebSocketSession}.
 * It coordinates with the Java 21 Project Loom runtime to deliver high-throughput,
 * non-blocking WebSocket communication while maintaining clean, imperative code style.</p>
 *
 * <h3>1. The Physics of SocketChannel Write Contention:</h3>
 * <p>
 * When 10,000 clients participate in a broadcast chat room or telemetry stream, multiple
 * Virtual Threads can invoke {@link #send(String)} on the same session concurrently.
 * A {@link SocketChannel} write operation is NOT atomic in Java NIO. If Thread 1 writes half
 * of an 8 KB Text frame and Thread 2 simultaneously writes a 6-byte Pong control frame,
 * the Pong frame bytes will be physically spliced directly into the payload of the Text frame.
 * The remote client's frame parser will fail with an RFC 6455 {@code 1002 (Protocol Error)}.
 * </p>
 *
 * <h3>2. Why ReentrantLock Over Synchronized:</h3>
 * <p>
 * In JDK 21 Project Loom, entering a {@code synchronized (this)} block can <b>pin</b> the
 * virtual thread to its operating system carrier thread if blocking occurs inside the block.
 * When carrier threads are pinned, the carrier {@link java.util.concurrent.ForkJoinPool}
 * cannot schedule other virtual threads on those OS threads, causing latency spikes and
 * thread starvation under high concurrency.
 * </p>
 * <p>
 * In contrast, {@link ReentrantLock} was completely refactored in Java 21 to integrate with
 * Loom's continuation scheduler. When a virtual thread encounters contention on a
 * {@link ReentrantLock}, it yields its OS carrier thread smoothly, allowing the JVM to
 * process other network events without starvation.
 * </p>
 *
 * <h3>3. Idempotent Close Handshake:</h3>
 * <p>
 * Connection termination is guarded by an {@link AtomicBoolean}. The first call to {@link #close(int, String)}
 * transmits a compliant RFC 6455 Close frame, unregisters the session from {@link WebSocketSessionRegistry},
 * and releases the underlying channel. Subsequent calls are safe no-ops.
 * </p>
 *
 * @see BedrockWebSocketSession
 * @see WebSocketSessionRegistry
 * @see WebSocketClientHandler
 */
public class StandardWebSocketSession implements BedrockWebSocketSession {

    private final String id;
    private final SocketChannel channel;
    private final String path;
    private final SocketAddress remoteAddress;
    private final WebSocketSessionRegistry registry;
    private final Map<String, String> queryParams;
    private final String subprotocol;

    /**
     * ReentrantLock guarantees thread safety without carrier thread pinning in Java 21 Loom.
     */
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * Constructs a new {@code StandardWebSocketSession}.
     *
     * @param id       Unique session identifier.
     * @param channel  Underlying Java NIO SocketChannel.
     * @param path     Normalized endpoint URI path.
     * @param registry Session registry tracking active connections.
     */
    public StandardWebSocketSession(String id,
                                   SocketChannel channel,
                                   String path,
                                   WebSocketSessionRegistry registry) {
        this(id, channel, path, registry, Collections.emptyMap(), null);
    }

    /**
     * Constructs a new {@code StandardWebSocketSession} with query parameters and subprotocol.
     *
     * @param id          Unique session identifier.
     * @param channel     Underlying Java NIO SocketChannel.
     * @param path        Normalized endpoint URI path.
     * @param registry    Session registry tracking active connections.
     * @param queryParams Parsed query parameters from the HTTP upgrade request URL.
     * @param subprotocol Negotiated subprotocol, or null.
     */
    public StandardWebSocketSession(String id,
                                   SocketChannel channel,
                                   String path,
                                   WebSocketSessionRegistry registry,
                                   Map<String, String> queryParams,
                                   String subprotocol) {
        this.id = Objects.requireNonNull(id, "Session id cannot be null");
        this.channel = Objects.requireNonNull(channel, "SocketChannel cannot be null");
        this.path = Objects.requireNonNull(path, "Path cannot be null");
        this.registry = registry;
        this.queryParams = queryParams != null ? Collections.unmodifiableMap(new ConcurrentHashMap<>(queryParams)) : Collections.emptyMap();
        this.subprotocol = subprotocol;

        SocketAddress addr = null;
        try {
            if (channel.isOpen()) {
                addr = channel.getRemoteAddress();
            }
        } catch (IOException ignore) {
        }
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
    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    @Override
    public String getQueryParam(String name) {
        if (name == null) return null;
        return queryParams.get(name);
    }

    @Override
    public String getSubprotocol() {
        return subprotocol;
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
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Ping payload cannot exceed 125 bytes");
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

        if (closed.compareAndSet(false, true)) {
            try {
                byte[] closeFrame = WebSocketFrameWriter.createCloseFrame(statusCode, reason != null ? reason : "");
                writeRaw(ByteBuffer.wrap(closeFrame));
            } catch (Exception ignore) {
                // If peer already reset the socket or closed channel, ignore write error
            } finally {
                if (registry != null) {
                    registry.unregister(id);
                }
                try {
                    if (channel.isOpen()) {
                        channel.close();
                    }
                } catch (IOException ignore) {
                }
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
            writeRawInternal(buffer);
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
        } catch (IOException ignore) {
        }
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
        return "StandardWebSocketSession{" +
                "id='" + id + '\'' +
                ", path='" + path + '\'' +
                ", remoteAddress=" + remoteAddress +
                ", open=" + isOpen() +
                '}';
    }
}
