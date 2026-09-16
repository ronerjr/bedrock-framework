package com.bedrock.core.ws.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * 🎓 BEDROCK TUTORIAL: Thread-Safe WebSocket Session Abstraction & Project Loom
 *
 * <p>A {@code BedrockWebSocketSession} represents an active, full-duplex RFC 6455
 * connection between a client and the Bedrock server. It decouples high-level application
 * logic from low-level Java NIO {@link java.nio.channels.SocketChannel} byte mechanics.</p>
 *
 * <h3>1. The Concurrency Paradigm: Virtual Threads & Carrier Friendly Locking:</h3>
 * <p>In Java 21 LTS Project Loom, hundreds of thousands of concurrent WebSocket connections
 * can be maintained with minimal memory overhead. Each connection executes its synchronous
 * read loop on its own dedicated Virtual Thread:</p>
 * <pre>{@code
 *   Thread.ofVirtual().name("ws-client-", clientId).start(...)
 * }</pre>
 * <p>
 * When code enters a traditional {@code synchronized (lock)} block and blocks, the virtual
 * thread is <b>pinned</b> to its underlying OS carrier thread, disabling the JVM scheduler
 * from reusing that OS thread for other virtual threads.
 * </p>
 * <p>
 * In contrast, Bedrock's session implementation exclusively uses
 * {@link java.util.concurrent.locks.ReentrantLock} for frame transmission. When a virtual thread
 * waits on a {@link java.util.concurrent.locks.ReentrantLock}, it <b>yields its carrier thread
 * cleanly without pinning</b>, allowing the ForkJoinPool carrier pool to process other connections.
 * </p>
 *
 * <h3>2. The SocketChannel Non-Thread-Safe Invariant:</h3>
 * <p>
 * In Java NIO, {@link java.nio.channels.SocketChannel#write(ByteBuffer)} is NOT thread-safe.
 * If Thread A calls {@code session.send("Hello")} and Thread B simultaneously transmits a
 * heartbeat Ping frame or a broadcast message, their raw frame bytes would interleave on the TCP stream.
 * The client's frame decoder would encounter corrupted frame headers and terminate the connection
 * with RFC 6455 code {@code 1002 (Protocol Error)}.
 * </p>
 * <p>
 * {@link BedrockWebSocketSession} guarantees that all write operations ({@link #send(String)},
 * {@link #send(byte[])}, {@link #sendPing(byte[])}, and {@link #writeRaw(ByteBuffer)}) acquire
 * an exclusive session write lock, ensuring frame serialization is 100% atomic.
 * </p>
 *
 * <h3>3. Clean Two-Way Close Handshake:</h3>
 * <p>
 * Calling {@link #close()} or {@link #close(int, String)} sends a compliant RFC 6455 Close frame
 * (Opcode 0x8) and terminates the channel without socket leaks.
 * </p>
 *
 * @see StandardWebSocketSession
 * @see WebSocketSessionRegistry
 * @see WebSocketClientHandler
 */
public interface BedrockWebSocketSession extends AutoCloseable {

    /**
     * Returns the globally unique identifier for this session (e.g. UUID string).
     *
     * @return Unique session identifier string.
     */
    String getId();

    /**
     * Returns the remote client's socket address (IP and port).
     *
     * @return Remote {@link SocketAddress}, or null if not yet connected.
     */
    SocketAddress getRemoteAddress();

    /**
     * Returns the normalized URI path on which this connection was established (e.g. "/chat").
     *
     * @return Endpoint route path string.
     */
    String getPath();

    /**
     * Returns an unmodifiable map of query parameters passed in the initial HTTP upgrade request URL.
     * (e.g. {@code /chat?token=xyz&room=dev} -&gt; {@code {"token": "xyz", "room": "dev"}}).
     *
     * @return Map of query parameter names and values.
     */
    default Map<String, String> getQueryParams() {
        return Collections.emptyMap();
    }

    /**
     * Retrieves a query parameter by name from the initial HTTP upgrade request URL.
     *
     * @param name The parameter name.
     * @return The parameter value, or null if absent.
     */
    default String getQueryParam(String name) {
        return getQueryParams().get(name);
    }

    /**
     * Returns the negotiated subprotocol confirmed during handshake via {@code Sec-WebSocket-Protocol},
     * or null if no subprotocol was negotiated.
     *
     * @return The negotiated subprotocol identifier, or null.
     */
    default String getSubprotocol() {
        return null;
    }

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
     * This method is thread-safe and can be invoked concurrently from any number of Virtual Threads.
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
     *
     * @return Unmodifiable map of session attributes.
     */
    Map<String, Object> getAttributes();

    // =========================================================================
    // Internal Server Engine Hooks (Used by WebSocketClientHandler)
    // =========================================================================

    /**
     * Writes raw bytes directly to the underlying channel under the session write lock.
     * Used internally by the server engine for control frame responses (e.g. Pong replies and Close frames).
     *
     * @param buffer Byte buffer to write completely.
     * @throws IOException If a socket write error occurs.
     */
    void writeRaw(ByteBuffer buffer) throws IOException;

    /**
     * Marks the session as closed without re-sending a Close frame.
     * Called when the client initiates close or when an abnormal TCP termination occurs.
     */
    void markClosed();
}
