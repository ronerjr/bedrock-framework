package com.bedrock.example.ws;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.server.BedrockWebSocketSession;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🎓 BEDROCK TUTORIAL: Real-Time Multi-Client Chat WebSocket & Project Loom Concurrency
 *
 * <p>This class implements an interactive, multi-client real-time chat room using
 * Bedrock Version 2.0 declarative WebSocket annotations. It demonstrates how Java 21
 * Virtual Threads (Project Loom) make building high-concurrency real-time systems
 * simple, transparent, and resilient without requiring complex reactive frameworks or Netty.</p>
 *
 * <h3>1. The Anti-Magic Declarative Model:</h3>
 * <p>In Spring Boot or Quarkus, WebSockets often require multiple layers of abstraction
 * (STOMP brokers, message templates, dynamic proxy handlers). In Bedrock:
 * <ul>
 *   <li>{@link BedrockSocket} explicitly maps this endpoint to the URI path {@code /chat}.</li>
 *   <li>The class is registered explicitly via {@code app.register(ChatWebSocket.class)}.</li>
 *   <li>Lifecycle events ({@link OnOpen}, {@link OnMessage}, {@link OnClose}, {@link OnError})
 *       are invoked directly via reflection with zero dynamic bytecode proxies.</li>
 * </ul>
 * </p>
 *
 * <h3>2. The Concurrency Paradigm: Virtual Threads & Carrier-Friendly Locks:</h3>
 * <p>Every client connection is assigned a dedicated Virtual Thread by Bedrock:
 * <pre>{@code
 *   Thread.ofVirtual().name("ws-client-", clientId).start(...)
 * }</pre>
 * Virtual threads are lightweight (~300 bytes of heap memory). The JVM dynamically
 * multiplexes millions of virtual threads onto a small pool of OS carrier threads.
 * </p>
 * <p><b>Why ReentrantLock is Essential:</b><br>
 * If connection writes were guarded by traditional {@code synchronized (lock)} blocks,
 * any virtual thread waiting for a lock would <b>pin</b> its underlying OS carrier thread,
 * preventing other virtual threads from executing. Bedrock's {@link BedrockWebSocketSession}
 * uses {@link java.util.concurrent.locks.ReentrantLock}, allowing waiting virtual threads
 * to unmount and yield their carrier threads cleanly.
 * </p>
 *
 * <h3>3. The Zero-Failure Resilient Broadcast Loop:</h3>
 * <p>When broadcasting a message to multiple connected clients, a common server bug is the
 * "brittle broadcast loop": if an individual client drops its connection abruptly, an uncaught
 * exception stops the loop, starving remaining clients.
 * In {@link #broadcast(String)}, each transmission is wrapped in an isolated error boundary.
 * If delivery to a client fails, that session is logged, pruned from memory, and the loop
 * continues delivering to all remaining healthy clients uninterrupted.</p>
 *
 * @see BedrockSocket
 * @see OnOpen
 * @see OnMessage
 * @see OnClose
 * @see OnError
 * @see BedrockWebSocketSession
 */
@BedrockSocket("/chat")
public class ChatWebSocket {

    private static final String LOG_TAG = "CHAT-WS";

    /**
     * Thread-safe registry of connected sessions.
     * Backed by ConcurrentHashMap to allow concurrent additions, removals,
     * and safe iterations without global mutex bottlenecks.
     */
    private final Map<String, BedrockWebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Connection Opened
     *
     * <p>Triggered immediately after the HTTP 101 Switching Protocols upgrade handshake
     * completes. The underlying TCP socket is now in raw full-duplex WebSocket mode.</p>
     *
     * @param session The newly established client session.
     */
    @OnOpen
    public void onOpen(BedrockWebSocketSession session) {
        sessions.put(session.getId(), session);
        String shortId = getShortId(session.getId());
        BedrockLogger.info(LOG_TAG, "Client connected: " + shortId + " [Total active: " + sessions.size() + "]");

        // 1. Send personalized welcome greeting to the connecting user
        try {
            session.send("[System] Welcome to Bedrock Real-Time Chat! Connected as User " + shortId + ".");
        } catch (Exception e) {
            BedrockLogger.warn(LOG_TAG, "Failed to send welcome greeting to session " + session.getId() + ": " + e.getMessage());
        }

        // 2. Broadcast join notification to all connected clients
        try {
            broadcast("[System] User " + shortId + " joined. Total users: " + sessions.size());
        } catch (Exception e) {
            BedrockLogger.warn(LOG_TAG, "Failed to broadcast join announcement: " + e.getMessage());
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Message Received
     *
     * <p>Triggered when an incoming RFC 6455 text frame arrives from the client.
     * The frame has already been validated, XOR-unmasked with the 4-byte client key,
     * and confirmed as valid UTF-8 by Bedrock's protocol engine.</p>
     *
     * @param session The session of the client sending the message.
     * @param message The unmasked UTF-8 text message payload.
     */
    @OnMessage
    public void onMessage(BedrockWebSocketSession session, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String text = message.trim();
        String shortId = getShortId(session.getId());

        // Support optional dynamic nickname change: /nick <name>
        if (text.startsWith("/nick ")) {
            String newNick = text.substring(6).trim();
            if (!newNick.isBlank()) {
                String oldName = getDisplayName(session);
                session.setAttribute("username", newNick);
                BedrockLogger.info(LOG_TAG, "User " + shortId + " changed name to: " + newNick);
                broadcast("[System] " + oldName + " is now known as " + newNick);
                return;
            }
        }

        // Check if message is already pre-formatted with sender tag (e.g. [Sender] message)
        if (text.startsWith("[") && text.contains("] ")) {
            BedrockLogger.info(LOG_TAG, "Broadcasting formatted message: " + text);
            broadcast(text);
        } else {
            // Standard user broadcast: [User <id>] <message> or [<nickname>] <message>
            String sender = getDisplayName(session);
            BedrockLogger.info(LOG_TAG, "Broadcasting from " + sender + ": " + text);
            broadcast("[" + sender + "] " + text);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Connection Closed
     *
     * <p>Triggered when a client initiates disconnection (RFC 6455 Close frame Opcode 0x8),
     * or when the connection terminates due to network loss.</p>
     *
     * @param session    The disconnecting session.
     * @param statusCode RFC 6455 status code (e.g. 1000 Normal, 1001 Going Away).
     * @param reason     Human-readable UTF-8 reason string.
     */
    @OnClose
    public void onClose(BedrockWebSocketSession session, int statusCode, String reason) {
        sessions.remove(session.getId(), session);
        String sender = getDisplayName(session);
        BedrockLogger.info(LOG_TAG, "Client disconnected: " + sender +
                " [Status: " + statusCode + ", Reason: '" + reason + "'] [Remaining: " + sessions.size() + "]");

        if (!sessions.isEmpty()) {
            broadcast("[System] " + sender + " left. Total users: " + sessions.size());
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Lifecycle Hook — Error Isolation Boundary
     *
     * <p>Catches protocol errors or unexpected exceptions during full-duplex operation.
     * Guarantees errors are safely logged without crashing the server accept loop.</p>
     *
     * @param session   The affected session, or null if error occurred pre-handshake.
     * @param throwable The caught exception or error.
     */
    @OnError
    public void onError(BedrockWebSocketSession session, Throwable throwable) {
        String id = (session != null) ? getShortId(session.getId()) : "unknown";
        String msg = (throwable != null) ? throwable.getMessage() : "Unknown error";
        BedrockLogger.warn(LOG_TAG, "WebSocket error on session [" + id + "]: " + msg);
    }

    // =========================================================================
    // Multi-Client Broadcast Engine & Error Boundaries
    // =========================================================================

    /**
     * Broadcasts a text message across all active connected sessions.
     * Employs isolated per-client error boundaries: if an individual client write fails,
     * the faulty session is pruned and delivery proceeds to all other clients.
     *
     * @param message The text message to broadcast.
     */
    public void broadcast(String message) {
        if (message == null) {
            return;
        }

        for (BedrockWebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                sessions.remove(session.getId(), session);
                continue;
            }

            try {
                session.send(message);
            } catch (Exception e) {
                BedrockLogger.warn(LOG_TAG, "Failed to send to session " + session.getId() + ": " + e.getMessage());
                sessions.remove(session.getId(), session);
                try {
                    session.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * Broadcasts a text message to all active sessions EXCEPT the specified excluded session.
     * Useful for peer echo patterns.
     *
     * @param message           The text message to broadcast.
     * @param excludedSessionId The session ID to skip.
     */
    public void broadcastExcept(String message, String excludedSessionId) {
        if (message == null) {
            return;
        }

        for (BedrockWebSocketSession session : sessions.values()) {
            if (session.getId().equals(excludedSessionId)) {
                continue;
            }
            if (!session.isOpen()) {
                sessions.remove(session.getId(), session);
                continue;
            }

            try {
                session.send(message);
            } catch (Exception e) {
                BedrockLogger.warn(LOG_TAG, "Failed to send to session " + session.getId() + ": " + e.getMessage());
                sessions.remove(session.getId(), session);
                try {
                    session.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // =========================================================================
    // Diagnostic & Inspection API (Used in Integration Tests)
    // =========================================================================

    /**
     * Returns the number of currently active sessions in the chat room.
     *
     * @return Active session count.
     */
    public int getConnectedCount() {
        return sessions.size();
    }

    /**
     * Checks whether a session ID is currently registered.
     *
     * @param sessionId Session identifier.
     * @return True if registered and tracked.
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Returns an unmodifiable snapshot of active sessions.
     *
     * @return Active sessions collection.
     */
    public Collection<BedrockWebSocketSession> getSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Clears all tracked sessions (used in test teardown).
     */
    public void clear() {
        sessions.clear();
    }

    // =========================================================================
    // Internal Helper Methods
    // =========================================================================

    private String getShortId(String sessionId) {
        if (sessionId == null) return "unknown";
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    private String getDisplayName(BedrockWebSocketSession session) {
        if (session == null) return "Unknown";
        Object custom = session.getAttribute("username");
        if (custom instanceof String nick && !nick.isBlank()) {
            return nick.trim();
        }
        return "User " + getShortId(session.getId());
    }
}
