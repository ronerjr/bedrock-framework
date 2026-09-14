package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.exception.BedrockException;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;

/**
 * 🎓 BEDROCK TUTORIAL: Explicit Reflective Endpoint Scanner Without Magic
 *
 * <p>In conventional enterprise frameworks, annotation scanning is performed by complex background
 * classpath walkers (e.g., ClassGraph, Spring's PathMatchingResourcePatternResolver, or ASM bytecode analyzers).
 * These introduce significant startup latency, obscure classloading issues, and make
 * application initialization unpredictable.</p>
 *
 * <p>In Bedrock, {@link WebSocketEndpointScanner} executes <b>single-pass inspection</b>
 * directly on registered class instances during {@code app.register(...)}.
 * It enforces strict signature validation at startup, failing fast with actionable
 * {@link BedrockException} errors to guide developers before network ports open.</p>
 *
 * <h3>Validation Rules:</h3>
 * <ul>
 *   <li><b>Class Level</b>: Target class must be annotated with {@link BedrockSocket} and must be a
 *       concrete instantiable class (not an abstract class or interface).</li>
 *   <li><b>Route Path</b>: Extracted from {@link BedrockSocket#path()} or {@link BedrockSocket#value()}.
 *       Conflicting non-empty paths cause fail-fast errors. Defaults to {@code "/"}.</li>
 *   <li><b>Single Annotation Invariant</b>: A method cannot be annotated with more than one
 *       lifecycle annotation (e.g. both {@code @OnOpen} and {@code @OnMessage}).</li>
 *   <li><b>Multiplicity Invariant</b>: At most one method per lifecycle event is permitted per class hierarchy.</li>
 *   <li><b>Parameter Types</b>:
 *     <ul>
 *       <li>{@link OnOpen}: 0 or 1 parameter ({@link BedrockWebSocketSession}).</li>
 *       <li>{@link OnMessage}: 1 or 2 parameters ({@link BedrockWebSocketSession}, {@link String}).</li>
 *       <li>{@link OnClose}: 0 to 3 parameters ({@link BedrockWebSocketSession}, {@code int}/{@link Integer}, {@link String}).</li>
 *       <li>{@link OnError}: 0 to 2 parameters ({@link BedrockWebSocketSession}, {@link Throwable} or subclass).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @see BedrockSocket
 * @see WebSocketEndpointBinding
 * @see BedrockWebSocketServer
 */
public final class WebSocketEndpointScanner {

    private WebSocketEndpointScanner() {
        // Utility class
    }

    /**
     * Immutable result of scanning an annotated WebSocket endpoint instance.
     *
     * @param path           The normalized URI route path (e.g., "/chat").
     * @param targetInstance The singleton object instance.
     * @param onOpen         The method annotated with {@literal @}OnOpen, or null.
     * @param onMessage      The method annotated with {@literal @}OnMessage, or null.
     * @param onClose        The method annotated with {@literal @}OnClose, or null.
     * @param onError        The method annotated with {@literal @}OnError, or null.
     * @param binding        The ready-to-use reflective endpoint binding.
     */
    public record ScannedEndpoint(
            String path,
            Object targetInstance,
            Method onOpen,
            Method onMessage,
            Method onClose,
            Method onError,
            WebSocketEndpointBinding.ReflectiveEndpointBinding binding
    ) {}

    /**
     * 🎓 BEDROCK TUTORIAL: Single-Pass Instance Scanning & Method Contract Enforcement
     *
     * <p>Scans a target endpoint object, validates all lifecycle method signatures,
     * extracts the route path, and constructs a {@link ScannedEndpoint}.</p>
     *
     * @param targetInstance The instantiated endpoint instance (must not be null).
     * @return The scanned endpoint record.
     * @throws BedrockException If the target class violates annotation contracts.
     */
    public static ScannedEndpoint scan(Object targetInstance) {
        Objects.requireNonNull(targetInstance, "Target instance cannot be null");
        Class<?> clazz = targetInstance.getClass();

        // 1. Validate @BedrockSocket and extract route path
        String path = extractPath(clazz);

        // 2. Discover and validate lifecycle methods across hierarchy
        Method onOpen = null;
        Method onMessage = null;
        Method onClose = null;
        Method onError = null;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                // Ignore synthetic / compiler-generated bridge methods
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }

                validateSingleAnnotation(method, clazz);

                if (method.isAnnotationPresent(OnOpen.class)) {
                    validateOnOpen(method, clazz);
                    if (onOpen != null && !isOverridden(onOpen, method)) {
                        throw new BedrockException(
                                "Class '" + clazz.getSimpleName() + "' has multiple methods annotated with @OnOpen: '" +
                                        onOpen.getName() + "' and '" + method.getName() + "'.",
                                "Consolidate your connection initialization logic into a single method annotated with @OnOpen."
                        );
                    }
                    if (onOpen == null) {
                        onOpen = method;
                    }
                } else if (method.isAnnotationPresent(OnMessage.class)) {
                    validateOnMessage(method, clazz);
                    if (onMessage != null && !isOverridden(onMessage, method)) {
                        throw new BedrockException(
                                "Class '" + clazz.getSimpleName() + "' has multiple methods annotated with @OnMessage: '" +
                                        onMessage.getName() + "' and '" + method.getName() + "'.",
                                "Consolidate your message reception logic into a single method annotated with @OnMessage."
                        );
                    }
                    if (onMessage == null) {
                        onMessage = method;
                    }
                } else if (method.isAnnotationPresent(OnClose.class)) {
                    validateOnClose(method, clazz);
                    if (onClose != null && !isOverridden(onClose, method)) {
                        throw new BedrockException(
                                "Class '" + clazz.getSimpleName() + "' has multiple methods annotated with @OnClose: '" +
                                        onClose.getName() + "' and '" + method.getName() + "'.",
                                "Consolidate your disconnect logic into a single method annotated with @OnClose."
                        );
                    }
                    if (onClose == null) {
                        onClose = method;
                    }
                } else if (method.isAnnotationPresent(OnError.class)) {
                    validateOnError(method, clazz);
                    if (onError != null && !isOverridden(onError, method)) {
                        throw new BedrockException(
                                "Class '" + clazz.getSimpleName() + "' has multiple methods annotated with @OnError: '" +
                                        onError.getName() + "' and '" + method.getName() + "'.",
                                "Consolidate your error handling logic into a single method annotated with @OnError."
                        );
                    }
                    if (onError == null) {
                        onError = method;
                    }
                }
            }
            current = current.getSuperclass();
        }

        WebSocketEndpointBinding.ReflectiveEndpointBinding binding =
                WebSocketEndpointBinding.reflective(targetInstance, onOpen, onMessage, onClose, onError);

        BedrockLogger.info("WS-SCANNER", "Scanned WebSocket endpoint '" + clazz.getSimpleName() + "' -> " + path +
                " [onOpen=" + (onOpen != null ? onOpen.getName() : "none") +
                ", onMessage=" + (onMessage != null ? onMessage.getName() : "none") +
                ", onClose=" + (onClose != null ? onClose.getName() : "none") +
                ", onError=" + (onError != null ? onError.getName() : "none") + "]");

        return new ScannedEndpoint(path, targetInstance, onOpen, onMessage, onClose, onError, binding);
    }

    /**
     * Extracts and validates the WebSocket route path from a class annotated with {@link BedrockSocket}.
     *
     * @param clazz The class to inspect.
     * @return The normalized URI path.
     * @throws BedrockException If the class is missing the annotation, is abstract/interface, or specifies conflicting paths.
     */
    public static String extractPath(Class<?> clazz) {
        BedrockSocket annotation = clazz.getAnnotation(BedrockSocket.class);
        if (annotation == null) {
            throw new BedrockException(
                    "Class '" + clazz.getName() + "' is missing the required @BedrockSocket annotation.",
                    "Add @BedrockSocket(\"/your-path\") to class '" + clazz.getSimpleName() + "'."
            );
        }

        if (Modifier.isAbstract(clazz.getModifiers()) || clazz.isInterface()) {
            throw new BedrockException(
                    "Cannot bind abstract class or interface '" + clazz.getSimpleName() + "' as a @BedrockSocket.",
                    "Annotate a concrete, instantiable class with @BedrockSocket."
            );
        }

        String pathVal = annotation.path() != null ? annotation.path().trim() : "";
        String valVal = annotation.value() != null ? annotation.value().trim() : "";

        if (!pathVal.isEmpty() && !valVal.isEmpty() && !pathVal.equals(valVal)) {
            throw new BedrockException(
                    "Conflicting route paths specified in @BedrockSocket on '" + clazz.getSimpleName() +
                            "': value = '" + valVal + "' and path = '" + pathVal + "'.",
                    "Specify either 'value' or 'path', or ensure both attributes have identical values."
            );
        }

        String rawPath = !pathVal.isEmpty() ? pathVal : valVal;
        if (rawPath.isEmpty()) {
            rawPath = "/";
        }

        return BedrockWebSocketServer.normalizePath(rawPath);
    }

    /**
     * Validates that a method is not annotated with more than one lifecycle annotation.
     */
    private static void validateSingleAnnotation(Method method, Class<?> clazz) {
        int count = 0;
        if (method.isAnnotationPresent(OnOpen.class)) count++;
        if (method.isAnnotationPresent(OnMessage.class)) count++;
        if (method.isAnnotationPresent(OnClose.class)) count++;
        if (method.isAnnotationPresent(OnError.class)) count++;

        if (count > 1) {
            throw new BedrockException(
                    "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                            "' has multiple WebSocket lifecycle annotations.",
                    "A method can only be annotated with at most ONE lifecycle annotation (@OnOpen, @OnMessage, @OnClose, or @OnError)."
            );
        }
    }

    /**
     * Validates signature for @OnOpen:
     * Allowed: (BedrockWebSocketSession) or ()
     */
    private static void validateOnOpen(Method method, Class<?> clazz) {
        Parameter[] params = method.getParameters();
        if (params.length > 1) {
            throw new BedrockException(
                    "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                            "' annotated with @OnOpen has " + params.length + " parameters.",
                    "Allowed signatures for @OnOpen: (BedrockWebSocketSession session) or ()."
            );
        }
        if (params.length == 1) {
            Class<?> pType = params[0].getType();
            if (!BedrockWebSocketSession.class.isAssignableFrom(pType)) {
                throw new BedrockException(
                        "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                "' annotated with @OnOpen has invalid parameter type '" + pType.getSimpleName() + "'.",
                        "Change parameter type to 'BedrockWebSocketSession' or remove the parameter."
                );
            }
        }
    }

    /**
     * Validates signature for @OnMessage:
     * Allowed: 1 or 2 parameters consisting only of BedrockWebSocketSession and String.
     */
    private static void validateOnMessage(Method method, Class<?> clazz) {
        Parameter[] params = method.getParameters();
        if (params.length == 0 || params.length > 2) {
            throw new BedrockException(
                    "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                            "' annotated with @OnMessage must declare 1 or 2 parameters.",
                    "Allowed signatures for @OnMessage: (BedrockWebSocketSession session, String message), (String message), or (BedrockWebSocketSession session)."
            );
        }

        boolean hasSession = false;
        boolean hasString = false;

        for (Parameter p : params) {
            Class<?> pType = p.getType();
            if (BedrockWebSocketSession.class.isAssignableFrom(pType)) {
                if (hasSession) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnMessage has duplicate BedrockWebSocketSession parameters.",
                            "Only one BedrockWebSocketSession parameter is allowed in @OnMessage."
                    );
                }
                hasSession = true;
            } else if (String.class.equals(pType)) {
                if (hasString) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnMessage has duplicate String parameters.",
                            "Only one String message parameter is allowed in @OnMessage."
                    );
                }
                hasString = true;
            } else {
                throw new BedrockException(
                        "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                "' annotated with @OnMessage has unsupported parameter type '" + pType.getSimpleName() + "'.",
                        "Allowed parameter types for @OnMessage: BedrockWebSocketSession, String."
                );
            }
        }
    }

    /**
     * Validates signature for @OnClose:
     * Allowed: 0 to 3 parameters consisting only of BedrockWebSocketSession, int/Integer (status code), String (reason).
     */
    private static void validateOnClose(Method method, Class<?> clazz) {
        Parameter[] params = method.getParameters();
        if (params.length > 3) {
            throw new BedrockException(
                    "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                            "' annotated with @OnClose has " + params.length + " parameters.",
                    "Allowed signatures for @OnClose: up to 3 parameters (BedrockWebSocketSession session, int code, String reason) or subsets thereof."
            );
        }

        boolean hasSession = false;
        boolean hasCode = false;
        boolean hasReason = false;

        for (Parameter p : params) {
            Class<?> pType = p.getType();
            if (BedrockWebSocketSession.class.isAssignableFrom(pType)) {
                if (hasSession) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnClose has duplicate BedrockWebSocketSession parameters.",
                            "Only one BedrockWebSocketSession parameter is permitted in @OnClose."
                    );
                }
                hasSession = true;
            } else if (pType.equals(int.class) || pType.equals(Integer.class)) {
                if (hasCode) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnClose has duplicate int/Integer status code parameters.",
                            "Only one status code parameter is permitted in @OnClose."
                    );
                }
                hasCode = true;
            } else if (pType.equals(String.class)) {
                if (hasReason) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnClose has duplicate String reason parameters.",
                            "Only one String reason parameter is permitted in @OnClose."
                    );
                }
                hasReason = true;
            } else {
                throw new BedrockException(
                        "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                "' annotated with @OnClose has unsupported parameter type '" + pType.getSimpleName() + "'.",
                        "Allowed parameter types for @OnClose: BedrockWebSocketSession, int/Integer (status code), String (reason)."
                );
            }
        }
    }

    /**
     * Validates signature for @OnError:
     * Allowed: 0 to 2 parameters consisting only of BedrockWebSocketSession, Throwable (or subclass).
     */
    private static void validateOnError(Method method, Class<?> clazz) {
        Parameter[] params = method.getParameters();
        if (params.length > 2) {
            throw new BedrockException(
                    "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                            "' annotated with @OnError has " + params.length + " parameters.",
                    "Allowed signatures for @OnError: up to 2 parameters (BedrockWebSocketSession session, Throwable throwable) or subsets thereof."
            );
        }

        boolean hasSession = false;
        boolean hasThrowable = false;

        for (Parameter p : params) {
            Class<?> pType = p.getType();
            if (BedrockWebSocketSession.class.isAssignableFrom(pType)) {
                if (hasSession) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnError has duplicate BedrockWebSocketSession parameters.",
                            "Only one BedrockWebSocketSession parameter is permitted in @OnError."
                    );
                }
                hasSession = true;
            } else if (Throwable.class.isAssignableFrom(pType)) {
                if (hasThrowable) {
                    throw new BedrockException(
                            "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                    "' annotated with @OnError has duplicate Throwable parameters.",
                            "Only one Throwable parameter is permitted in @OnError."
                    );
                }
                hasThrowable = true;
            } else {
                throw new BedrockException(
                        "Method '" + method.getName() + "' in class '" + clazz.getSimpleName() +
                                "' annotated with @OnError has unsupported parameter type '" + pType.getSimpleName() + "'.",
                        "Allowed parameter types for @OnError: BedrockWebSocketSession, Throwable (or subclass)."
                );
            }
        }
    }

    /**
     * Checks whether childMethod overrides parentMethod.
     */
    private static boolean isOverridden(Method childMethod, Method parentMethod) {
        return childMethod.getName().equals(parentMethod.getName()) &&
                Arrays.equals(childMethod.getParameterTypes(), parentMethod.getParameterTypes());
    }
}
