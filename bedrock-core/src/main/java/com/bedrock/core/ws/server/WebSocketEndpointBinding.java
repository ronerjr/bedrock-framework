package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 🎓 BEDROCK TUTORIAL: Explicit Invocation Contracts Without Runtime Proxies
 *
 * <p>In enterprise Java frameworks (such as Spring or Jakarta EE), developers annotate
 * a class with {@code @ServerEndpoint("/chat")} and methods with {@code @OnMessage}.
 * Under the hood, these frameworks generate deep dynamic proxy chains (CGLIB or ByteBuddy),
 * obscuring call stacks and degrading debuggability.</p>
 *
 * <p>In Bedrock, we uphold our core pedagogical tenet: <b>No Black Boxes</b>.
 * {@code WebSocketEndpointBinding} acts as an explicit, transparent bridge between low-level
 * network events and user application logic. It offers two clean execution modes:</p>
 * <ol>
 *   <li><b>Reflective Invocation ({@link ReflectiveEndpointBinding})</b>:
 *       Used by declarative annotations ({@code @BedrockSocket}, {@code @OnMessage}).
 *       Inspects target methods once during registration, caches parameter positions, and
 *       invokes directly via {@link Method#invoke(Object, Object...)} without runtime proxies.</li>
 *   <li><b>Functional Invocation ({@link FunctionalEndpointBinding})</b>:
 *       A fluent, lambda-driven builder allowing developers and unit tests to construct
 *       WebSocket endpoints using plain Java lambdas without reflection annotations.</li>
 * </ol>
 *
 * @see BedrockWebSocketServer
 * @see WebSocketClientHandler
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

    /**
     * Returns the array of supported application-level subprotocols for this endpoint.
     * Defaults to an empty array.
     *
     * @return Array of supported subprotocol strings.
     */
    default String[] getSubprotocols() {
        return new String[0];
    }

    /**
     * Negotiates a subprotocol given the comma-separated client requested subprotocols header.
     * Returns the first matching subprotocol, or null if no match or none supported.
     *
     * @param requestedSubprotocolsHeader The client's Sec-WebSocket-Protocol header.
     * @return The matched subprotocol, or null.
     */
    default String negotiateSubprotocol(String requestedSubprotocolsHeader) {
        if (requestedSubprotocolsHeader == null || requestedSubprotocolsHeader.isBlank()) {
            return null;
        }
        String[] supported = getSubprotocols();
        if (supported == null || supported.length == 0) {
            return null;
        }
        String[] requested = requestedSubprotocolsHeader.split(",");
        for (String req : requested) {
            String candidate = req.trim();
            for (String sup : supported) {
                if (sup != null && sup.equalsIgnoreCase(candidate)) {
                    return sup;
                }
            }
        }
        return null;
    }

    /**
     * Creates a new fluent builder for functional (lambda-based) endpoint bindings.
     *
     * @return A new {@link FunctionalEndpointBinding.Builder}.
     */
    static FunctionalEndpointBinding.Builder builder() {
        return FunctionalEndpointBinding.builder();
    }

    /**
     * Creates a reflective endpoint binding for an annotated instance and its lifecycle methods.
     *
     * @param targetInstance Target endpoint instance.
     * @param onOpenMethod   Method for OnOpen, or null.
     * @param onMessageMethod Method for OnMessage, or null.
     * @param onCloseMethod  Method for OnClose, or null.
     * @param onErrorMethod  Method for OnError, or null.
     * @return A new {@link ReflectiveEndpointBinding}.
     */
    static ReflectiveEndpointBinding reflective(Object targetInstance,
                                                Method onOpenMethod,
                                                Method onMessageMethod,
                                                Method onCloseMethod,
                                                Method onErrorMethod) {
        return new ReflectiveEndpointBinding(targetInstance, onOpenMethod, onMessageMethod, onCloseMethod, onErrorMethod, new String[0]);
    }

    /**
     * Creates a reflective endpoint binding with explicit subprotocols.
     */
    static ReflectiveEndpointBinding reflective(Object targetInstance,
                                                Method onOpenMethod,
                                                Method onMessageMethod,
                                                Method onCloseMethod,
                                                Method onErrorMethod,
                                                String[] subprotocols) {
        return new ReflectiveEndpointBinding(targetInstance, onOpenMethod, onMessageMethod, onCloseMethod, onErrorMethod, subprotocols);
    }

    // =========================================================================
    // Nested Implementation: ReflectiveEndpointBinding
    // =========================================================================

    /**
     * Transparent reflective invoker for annotated WebSocket endpoint classes.
     */
    class ReflectiveEndpointBinding implements WebSocketEndpointBinding {

        private final Object targetInstance;
        private final Method onOpenMethod;
        private final Method onMessageMethod;
        private final Method onCloseMethod;
        private final Method onErrorMethod;
        private final String[] subprotocols;

        public ReflectiveEndpointBinding(Object targetInstance,
                                        Method onOpenMethod,
                                        Method onMessageMethod,
                                        Method onCloseMethod,
                                        Method onErrorMethod) {
            this(targetInstance, onOpenMethod, onMessageMethod, onCloseMethod, onErrorMethod, new String[0]);
        }

        public ReflectiveEndpointBinding(Object targetInstance,
                                        Method onOpenMethod,
                                        Method onMessageMethod,
                                        Method onCloseMethod,
                                        Method onErrorMethod,
                                        String[] subprotocols) {
            this.targetInstance = Objects.requireNonNull(targetInstance, "Target instance cannot be null");
            this.onOpenMethod = makeAccessible(onOpenMethod);
            this.onMessageMethod = makeAccessible(onMessageMethod);
            this.onCloseMethod = makeAccessible(onCloseMethod);
            this.onErrorMethod = makeAccessible(onErrorMethod);
            this.subprotocols = subprotocols != null ? subprotocols.clone() : new String[0];
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
                    // If message is present, prioritize message; otherwise close reason
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

        @Override
        public String[] getSubprotocols() {
            return subprotocols.clone();
        }

        public Object getTargetInstance() {
            return targetInstance;
        }
    }

    // =========================================================================
    // Nested Implementation: FunctionalEndpointBinding
    // =========================================================================

    /**
     * Functional lambda-based builder for {@link WebSocketEndpointBinding}.
     */
    class FunctionalEndpointBinding implements WebSocketEndpointBinding {

        @FunctionalInterface
        public interface TriConsumer<T, U, V> {
            void accept(T t, U u, V v);
        }

        private final Consumer<BedrockWebSocketSession> onOpen;
        private final BiConsumer<BedrockWebSocketSession, String> onMessage;
        private final TriConsumer<BedrockWebSocketSession, Integer, String> onClose;
        private final BiConsumer<BedrockWebSocketSession, Throwable> onError;
        private final String[] subprotocols;

        private FunctionalEndpointBinding(Builder builder) {
            this.onOpen = builder.onOpen;
            this.onMessage = builder.onMessage;
            this.onClose = builder.onClose;
            this.onError = builder.onError;
            this.subprotocols = builder.subprotocols != null ? builder.subprotocols.clone() : new String[0];
        }

        public static Builder builder() {
            return new Builder();
        }

        @Override
        public String[] getSubprotocols() {
            return subprotocols.clone();
        }

        @Override
        public void invokeOnOpen(BedrockWebSocketSession session) throws Throwable {
            if (onOpen != null) onOpen.accept(session);
        }

        @Override
        public void invokeOnMessage(BedrockWebSocketSession session, String message) throws Throwable {
            if (onMessage != null) onMessage.accept(session, message);
        }

        @Override
        public void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) throws Throwable {
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
            private String[] subprotocols = new String[0];

            public Builder subprotocols(String... subprotocols) {
                this.subprotocols = subprotocols != null ? subprotocols : new String[0];
                return this;
            }

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
}
