package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

/**
 * 🎓 BEDROCK TUTORIAL: Resilient Multi-Client Session Registry & Concurrent Broadcast
 *
 * <p>In real-time server architectures (chat rooms, multiplayer state synchronization,
 * financial tickers), the server must fan out messages to thousands of connected clients
 * concurrently without latency amplification or thread blocking.</p>
 *
 * <h3>1. Zero-Failure Broadcast Guarantee:</h3>
 * <p>
 * A common defect in naive WebSocket servers is the "brittle broadcast loop":
 * if Client #42 experiences an abrupt TCP network drop, an uncaught {@code IOException}
 * during {@code session.send()} terminates the broadcast loop prematurely, preventing
 * Clients #43 through #10,000 from ever receiving the message.
 * </p>
 * <p>
 * In Bedrock, {@link #broadcast(String, Predicate)} wraps each individual client delivery
 * in an isolated error boundary. If a socket write fails, the registry catches the exception,
 * logs an educational warning, evicts the broken session from the registry, and continues
 * delivering to all remaining healthy clients uninterrupted.
 * </p>
 *
 * <h3>2. Self-Healing Dead Session Pruning:</h3>
 * <p>
 * Mobile and web clients frequently disconnect uncleanly (airplane mode, Wi-Fi drop, browser close).
 * Rather than allowing zombie sessions to accumulate in memory, the registry proactively prunes
 * inactive connections during {@link #get(String)} and {@link #broadcast(String)} passes.
 * </p>
 *
 * <h3>3. High-Concurrency Storage:</h3>
 * <p>
 * Backed by {@link ConcurrentHashMap}, guaranteeing non-blocking reads and segmented
 * concurrent writes without global mutex bottlenecks.
 * </p>
 *
 * @see BedrockWebSocketSession
 * @see BedrockWebSocketServer
 */
public class WebSocketSessionRegistry {

    private final ConcurrentMap<String, BedrockWebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Registers an active WebSocket session in the registry.
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
     * Unregisters a session instance.
     *
     * @param session The session to remove.
     * @return True if removed, false otherwise.
     */
    public boolean unregister(BedrockWebSocketSession session) {
        if (session == null || session.getId() == null) {
            return false;
        }
        return sessions.remove(session.getId(), session);
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
     * Returns an unmodifiable snapshot collection of all currently registered sessions.
     *
     * @return Unmodifiable view of active sessions.
     */
    public Collection<BedrockWebSocketSession> getAll() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Returns the total count of currently registered sessions.
     *
     * @return Current session count.
     */
    public int size() {
        return sessions.size();
    }

    /**
     * Returns true if no sessions are currently registered.
     *
     * @return True if registry is empty.
     */
    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    /**
     * Checks if a session with the specified identifier is actively registered and open.
     *
     * @param sessionId Unique session identifier.
     * @return True if registered and open.
     */
    public boolean contains(String sessionId) {
        return get(sessionId) != null;
    }

    /**
     * Broadcasts a UTF-8 text message across all active sessions in the registry.
     * Broken or closed connections are gracefully caught, logged, and pruned without
     * halting delivery to remaining clients.
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
                sessions.remove(session.getId(), session);
                try {
                    session.close(WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Broadcast transmission error");
                } catch (Exception ignore) {
                }
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
        if (excludedSessionId == null) {
            broadcast(text);
            return;
        }
        broadcast(text, session -> !Objects.equals(session.getId(), excludedSessionId));
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
                BedrockLogger.warn("WS-REGISTRY",
                        "Error closing session " + session.getId() + ": " + e.getMessage());
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
