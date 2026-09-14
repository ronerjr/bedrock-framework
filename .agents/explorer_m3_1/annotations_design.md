# Milestone 3 Architecture & Design: Declarative WebSocket Annotations & Reflective Binding Scanner

**Author**: `explorer_m3_1`  
**Milestone**: Milestone 3 — BedrockApp Integration & Annotations  
**Date**: 2026-09-11  
**Target Package**: `com.bedrock.core.ws.annotation` & `com.bedrock.core.ws.server`  
**Status**: DESIGN COMPLETE  

---

## 1. Executive Summary

Milestone 3 bridges the low-level RFC 6455 protocol engine (Milestone 1) and the virtual-threaded NIO server engine (Milestone 2) with the developer-facing declarative programming model of the Bedrock Java Framework.

In strict compliance with **ORIGINAL_REQUEST §Princípio Central: Combater a "Mágica de Anotações" (Sem Caixas-Pretas)** and **PROJECT.md §Feature Inventory (#16, #19, #20)**, this design specifies:
1. **Five Declarative Annotations** in `com.bedrock.core.ws.annotation`:
   - `@BedrockSocket`: Type-level route mapping annotation.
   - `@OnOpen`: Post-handshake lifecycle hook.
   - `@OnMessage`: Incoming text frame dispatch hook.
   - `@OnClose`: Disconnect & close frame hook.
   - `@OnError`: Protocol, network, and execution error hook.
2. **Reflective Endpoint Scanner (`WebSocketEndpointScanner`)**:
   - Single-pass reflection engine operating during explicit registration (`app.register(...)`).
   - Strict, fail-fast method signature validation throwing actionable `BedrockException` (Reason + Action) upon invalid signatures or ambiguous multiplicity.
   - Zero bytecode generation, zero dynamic runtime proxies (no CGLIB, ByteBuddy, ASM, or dynamic JDK proxies).
   - Generates immutable `WebSocketEndpointBinding.ReflectiveEndpointBinding` with pre-cached argument resolution for linear $O(1)$ dispatch performance.

---

## 2. Pedagogical Philosophy & Core Architectural Principles

```
  Spring / Jakarta EE (Black Box)          Bedrock Framework 2.0 (Transparent)
┌─────────────────────────────────────┐  ┌─────────────────────────────────────┐
│ @ServerEndpoint("/chat")            │  │ app.enableWebSockets(8081);         │
│   ├── Classpath Scanning (Slow)     │  │ app.register(ChatSocket.class);     │
│   ├── CGLIB / ByteBuddy Proxies     │  │   ├── BedrockContainer (IoC Di)     │
│   ├── 15 Layers of Interceptors     │  │   ├── WebSocketEndpointScanner      │
│   └── Obfuscated Stack Traces       │  │   │     └── Fast Fail Validation    │
│                                     │  │   ├── ReflectiveEndpointBinding     │
│                                     │  │   │     └── Direct Method.invoke    │
│                                     │  │   └── BedrockWebSocketServer        │
│                                     │  │         └── Loom Virtual Thread Loop│
└─────────────────────────────────────┘  └─────────────────────────────────────┘
```

1. **Explicit Registration Over Classpath Magic**:
   In enterprise frameworks, `@ServerEndpoint` relies on heavy classpath scanning at startup (e.g. scanning hundreds of JAR files). Bedrock requires explicit registration via `app.register(ChatSocket.class)`. The developer can clearly trace how the bean enters the framework.
2. **Dependency Injection via Standard IoC**:
   Before reflective scanning, `BedrockContainer` resolves and instantiates the socket class via constructor injection (supporting `@BedrockInject` or default constructor). Services, repositories, and JDBC instances can be injected into the socket effortlessly.
3. **Transparent Reflection Without Bytecode Manipulation**:
   Bedrock uses direct Java standard reflection (`java.lang.reflect.Method.invoke`). By setting `method.setAccessible(true)` once during scanner registration and caching parameter mapping indices, dispatch introduces negligible overhead (~microseconds) while preserving clean, intelligible Java stack traces.
4. **Actionable Fail-Fast Diagnostics (`BedrockException`)**:
   Reflective signature mistakes (such as invalid parameter types, ambiguous multiple `@OnMessage` methods, or conflicting route paths) are caught at application startup, providing formatted `[Reason]` and `[Action Required]` diagnostics so developers fix mistakes in seconds.

---

## 3. Declarative Annotations Specification

All annotations reside in package `com.bedrock.core.ws.annotation`.

### 3.1 `@BedrockSocket`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Explicit Semantic Route Mapping Without Black-Box Magic
 *
 * <p>In enterprise Java frameworks (such as Spring or Jakarta EE), developers annotate
 * a class with {@code @ServerEndpoint("/chat")} or {@code @EnableWebSocket}, expecting
 * the runtime to automatically scan the entire classpath, configure servlet containers,
 * and hide the mechanics of network sockets.</p>
 *
 * <p>In Bedrock, we uphold our core pedagogical tenet: <b>No Black Boxes</b>.
 * {@code @BedrockSocket} is an explicit semantic contract declaring that a class handles
 * real-time RFC 6455 WebSocket connections on a designated URI path.</p>
 *
 * <h3>Registration & Lifecycle:</h3>
 * <ol>
 *   <li><b>Explicit Registration</b>: Sockets are registered explicitly via
 *       {@code app.register(MySocket.class)}. No slow background classpath scanning occurs.</li>
 *   <li><b>IoC Dependency Injection</b>: The class is instantiated by {@code BedrockContainer}.
 *       Dependencies declared in its constructor (services, repositories, database clients)
 *       are resolved and injected cleanly before socket binding.</li>
 *   <li><b>Transparent Reflection</b>: Lifecycle methods ({@link OnOpen}, {@link OnMessage},
 *       {@link OnClose}, {@link OnError}) are inspected once at startup by
 *       {@code WebSocketEndpointScanner} and dispatched via direct reflective invocations
 *       without runtime proxies or bytecode enhancement.</li>
 * </ol>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 *   @BedrockSocket("/chat")
 *   public class ChatSocket {
 *       private final ChatService chatService;
 *
 *       public ChatSocket(ChatService chatService) {
 *           this.chatService = chatService;
 *       }
 *
 *       @OnOpen
 *       public void onOpen(BedrockWebSocketSession session) {
 *           session.send("Welcome to Bedrock Chat!");
 *       }
 *
 *       @OnMessage
 *       public void onMessage(BedrockWebSocketSession session, String text) {
 *           chatService.broadcast(text);
 *       }
 *   }
 * }</pre>
 *
 * @see OnOpen
 * @see OnMessage
 * @see OnClose
 * @see OnError
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BedrockSocket {

    /**
     * The WebSocket route path (e.g., "/chat", "/telemetry", or "/").
     * Alias for {@link #path()}.
     *
     * @return The URI route path.
     */
    String value() default "";

    /**
     * The WebSocket route path (e.g., "/chat", "/telemetry", or "/").
     * Alias for {@link #value()}.
     *
     * @return The URI route path.
     */
    String path() default "";
}
```

#### Element Design Justification:
- Supports both `@BedrockSocket("/chat")` (using `value()`) and `@BedrockSocket(path = "/chat")` (using `path()`).
- If neither is provided, defaults to `"/"`.
- If both are specified and conflict (e.g. `value = "/a"`, `path = "/b"`), the scanner throws an actionable `BedrockException`.

---

### 3.2 `@OnOpen`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Post-Handshake Lifecycle Notification
 *
 * <p>In RFC 6455, a WebSocket connection begins as an HTTP/1.1 GET request with an
 * {@code Upgrade: websocket} header. The server computes the {@code Sec-WebSocket-Accept}
 * SHA-1/Base64 hash and responds with {@code HTTP 101 Switching Protocols}.</p>
 *
 * <p>The method annotated with {@code @OnOpen} is executed <b>immediately after the
 * HTTP 101 handshake response has been flushed to the TCP socket</b>. At this precise moment,
 * the connection is promoted to full-duplex framing, registered in the
 * {@code WebSocketSessionRegistry}, and ready to send and receive frames.</p>
 *
 * <h3>Allowed Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onOpen(BedrockWebSocketSession session)} — Injects the active session.</li>
 *   <li>{@code public void onOpen()} — No-argument variation.</li>
 * </ul>
 *
 * @see BedrockSocket
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnOpen {
}
```

---

### 3.3 `@OnMessage`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Unmasked Text Frame Dispatch
 *
 * <p>In the RFC 6455 wire protocol, incoming client frames are masked with a 4-byte key.
 * The Bedrock Virtual Thread reader loop reads the raw frame, validates {@code MASK=1}
 * and {@code Opcode=0x1 (Text)}, unmasks the payload using in-place XOR arithmetic
 * ({@code Di = Ei ^ M[i % 4]}), and decodes the UTF-8 bytes into a {@link String}.</p>
 *
 * <p>The method annotated with {@code @OnMessage} receives this unmasked text payload.
 * In Bedrock, responses are sent explicitly via {@code session.send(...)}, adhering to our
 * rule of transparent control flow without hidden magic return handlers.</p>
 *
 * <h3>Allowed Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onMessage(BedrockWebSocketSession session, String message)}</li>
 *   <li>{@code public void onMessage(String message, BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onMessage(String message)}</li>
 *   <li>{@code public void onMessage(BedrockWebSocketSession session)}</li>
 * </ul>
 *
 * @see BedrockSocket
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnMessage {
}
```

---

### 3.4 `@OnClose`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Disconnect & Close Handshake
 *
 * <p>WebSocket termination in RFC 6455 requires a 2-way Close handshake (Opcode {@code 0x8}).
 * The closing party transmits a Close frame containing a 2-byte unsigned integer status code
 * (e.g. 1000 Normal Closure, 1001 Going Away) and an optional UTF-8 diagnostic reason string.</p>
 *
 * <p>The method annotated with {@code @OnClose} is invoked whenever a connection is closed cleanly
 * via client Close frame, when the server shuts down, or when an abrupt TCP connection reset occurs.
 * The session is removed from {@code WebSocketSessionRegistry} immediately following this call.</p>
 *
 * <h3>Allowed Method Signatures (any permutation):</h3>
 * <ul>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, int code, String reason)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, int code)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, String reason)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onClose(int code, String reason)}</li>
 *   <li>{@code public void onClose(int code)}</li>
 *   <li>{@code public void onClose(String reason)}</li>
 *   <li>{@code public void onClose()}</li>
 * </ul>
 *
 * @see BedrockSocket
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnClose {
}
```

---

### 3.5 `@OnError`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Uncaught Protocol & Network Exception Handling
 *
 * <p>During WebSocket communication, exceptions can arise at multiple protocol layers:
 * <ul>
 *   <li><b>Protocol Errors</b>: Client violates RFC 6455 (e.g., sends an unmasked frame,
 *       malformed UTF-8 in a text frame, or control frame with payload > 125 bytes).</li>
 *   <li><b>Network I/O Errors</b>: Abrupt socket disconnects, EOF, broken pipe.</li>
 *   <li><b>Application Errors</b>: Uncaught runtime exceptions thrown inside {@code @OnMessage}.</li>
 * </ul>
 *
 * <p>The method annotated with {@code @OnError} intercepts these exceptions, allowing the
 * application to log diagnostics, alert monitoring systems, or release associated resources
 * before the underlying socket channel is closed.</p>
 *
 * <h3>Allowed Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onError(BedrockWebSocketSession session, Throwable throwable)}</li>
 *   <li>{@code public void onError(Throwable throwable, BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onError(Throwable throwable)}</li>
 *   <li>{@code public void onError(BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onError()}</li>
 * </ul>
 * <p>Note: Any parameter type assignable to {@link Throwable} (such as {@link Exception} or
 * specific subclasses) is valid.</p>
 *
 * @see BedrockSocket
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnError {
}
```

---

## 4. Parameter Injection & Signature Validation Matrix

| Annotation | Parameter Count | Permitted Parameter Types | Ordering Rules | Default / Fallback Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **`@OnOpen`** | 0 or 1 | `BedrockWebSocketSession` | Any | If omitted on class, connection is accepted silently without notification. |
| **`@OnMessage`**| 1 or 2 | `BedrockWebSocketSession`, `String` | Any permutation (e.g. `(session, msg)` or `(msg, session)` or `(msg)`) | At least 1 parameter required. Unrecognized parameter types trigger `BedrockException`. |
| **`@OnClose`** | 0 to 3 | `BedrockWebSocketSession`, `int`/`Integer` (status code), `String` (reason) | Any permutation (e.g. `(session, code, reason)` or `(code, reason)` or `(session)`) | If omitted on class, disconnect completes silently without notification. |
| **`@OnError`** | 0 to 2 | `BedrockWebSocketSession`, `Throwable` (or any subclass: `Exception`, `IOException`, etc.) | Any permutation (e.g. `(session, ex)` or `(ex)`) | If omitted on class, error is logged via `BedrockLogger.error` to standard output. |

### Multiplicity and Scope Rules:
1. **At most ONE method per lifecycle event**:
   A class annotated with `@BedrockSocket` may declare at most one `@OnOpen`, at most one `@OnMessage`, at most one `@OnClose`, and at most one `@OnError`. Declaring multiple methods with the same annotation triggers a `BedrockException` at registration time.
2. **Annotation Mutual Exclusion**:
   A single method cannot be annotated with more than one lifecycle annotation (e.g. both `@OnOpen` and `@OnMessage`).
3. **Class Structure Constraints**:
   `@BedrockSocket` classes cannot be interfaces or abstract classes.
4. **Inheritance Support**:
   Superclasses up to `Object.class` are scanned. If a subclass overrides an annotated method with the exact same signature, the subclass method takes precedence.

---

## 5. Reflective Binding Scanner Design: `WebSocketEndpointScanner`

The scanner lives in `com.bedrock.core.ws.server.WebSocketEndpointScanner` (or can be referenced from `com.bedrock.core.ws.annotation`).

### 5.1 Architecture & Flow

```
   User Class (ChatSocket.class)
                 │
                 ▼
     [ BedrockContainer.register() ]
                 │
                 ▼
       Instantiate Singleton via
         Constructor Injection
                 │
                 ▼
   [ WebSocketEndpointScanner.scan(instance) ]
                 │
   ├── 1. Validate @BedrockSocket presence on class
   ├── 2. Extract & normalize route path
   ├── 3. Scan declared methods & validate mutual exclusion
   ├── 4. Validate signatures for @OnOpen, @OnMessage, @OnClose, @OnError
   ├── 5. Check multiplicity (<= 1 method per event)
   ├── 6. Set method.setAccessible(true)
   └── 7. Instantiate WebSocketEndpointBinding.ReflectiveEndpointBinding
                 │
                 ▼
   [ BedrockWebSocketServer.registerEndpoint(path, binding) ]
```

### 5.2 Complete Source Code: `WebSocketEndpointScanner.java`

```java
package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.exception.BedrockException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;

/**
 * 🎓 BEDROCK TUTORIAL: Explicit Reflective Endpoint Scanner Without Magic
 *
 * <p>In conventional frameworks, annotation scanning is performed by complex background
 * classpath walkers (e.g., ClassGraph or Spring's PathMatchingResourcePatternResolver).
 * These introduce significant startup latency, obscure classloading issues, and make
 * application initialization unpredictable.</p>
 *
 * <p>In Bedrock, {@link WebSocketEndpointScanner} executes <b>single-pass inspection</b>
 * directly on registered class instances during {@code app.register(...)}.
 * It enforces strict signature validation at startup, failing fast with actionable
 * {@link BedrockException} errors to guide developers before network ports open.</p>
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
     * Scans a target endpoint object, validates all lifecycle method signatures,
     * extracts the route path, and constructs a {@link ScannedEndpoint}.
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

        // 2. Discover and validate lifecycle methods
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
     * @throws BedrockException If the class is missing the annotation or specifies conflicting paths.
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
```

---

## 6. Integration Points with Server & BedrockApp

### 6.1 Integration with `WebSocketEndpointBinding`

Add convenient factory methods to `WebSocketEndpointBinding.java`:

```java
    /**
     * Creates a reflective endpoint binding by scanning an annotated {@literal @}BedrockSocket instance.
     *
     * @param targetInstance The endpoint instance.
     * @return A new {@link ReflectiveEndpointBinding}.
     */
    static ReflectiveEndpointBinding fromAnnotated(Object targetInstance) {
        return WebSocketEndpointScanner.scan(targetInstance).binding();
    }
```

### 6.2 Integration with `BedrockWebSocketServer`

Add convenience registration to `BedrockWebSocketServer.java`:

```java
    /**
     * Scans and registers an annotated {@literal @}BedrockSocket endpoint instance.
     * Automatically extracts route path and lifecycle callbacks.
     *
     * @param endpointInstance Target object instance annotated with {@literal @}BedrockSocket.
     * @return This server instance for fluent chaining.
     */
    public BedrockWebSocketServer registerEndpoint(Object endpointInstance) {
        WebSocketEndpointScanner.ScannedEndpoint scanned = WebSocketEndpointScanner.scan(endpointInstance);
        return registerEndpoint(scanned.path(), scanned.binding());
    }
```

### 6.3 Integration with `BedrockApp.register(...)` (Milestone 3 Core Workflow)

In `BedrockApp.java`:
```java
    public BedrockApp register(Class<?>... classes) {
        // 1. Register dependencies in IoC container
        container.register(classes);

        // 2. Scan Controllers to create HTTP routes
        // ... (existing BedrockController scanning)

        // 3. Scan WebSockets to create WebSocket routes
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(BedrockSocket.class)) {
                Object socketInstance = container.getBean(clazz);
                WebSocketEndpointScanner.ScannedEndpoint scanned = WebSocketEndpointScanner.scan(socketInstance);

                if (this.webSocketServer != null) {
                    this.webSocketServer.registerEndpoint(scanned.path(), scanned.binding());
                } else {
                    // Queue for deferred binding when enableWebSockets is called
                    this.pendingWebSocketEndpoints.add(scanned);
                }
            }
        }
        return this;
    }
```

This guarantees:
- Sockets receive full constructor dependency injection (IoC singletons).
- Order independence between `app.enableWebSockets(...)` and `app.register(...)`.
- Instant route activation and binding to the NIO Virtual Thread server.

---

## 7. Concrete Examples & Diagnostics Demonstration

### 7.1 Valid Production Example

```java
@BedrockSocket("/chat")
public class ChatSocket {

    private final ChatService chatService;

    // IoC Constructor Injection
    public ChatSocket(ChatService chatService) {
        this.chatService = chatService;
    }

    @OnOpen
    public void onOpen(BedrockWebSocketSession session) {
        chatService.register(session);
        session.send("Welcome to Bedrock Real-Time Chat!");
    }

    @OnMessage
    public void onMessage(BedrockWebSocketSession session, String message) {
        chatService.broadcast(session.getId() + ": " + message);
    }

    @OnClose
    public void onClose(BedrockWebSocketSession session, int statusCode, String reason) {
        chatService.unregister(session.getId());
    }

    @OnError
    public void onError(BedrockWebSocketSession session, Throwable ex) {
        BedrockLogger.error("CHAT", "Error on session " + session.getId() + ": " + ex.getMessage());
    }
}
```

### 7.2 Actionable Diagnostic Failure Examples

#### Failure Scenario 1: Multiple `@OnMessage` in Same Class
```java
@BedrockSocket("/conflict")
public class ConflictingSocket {
    @OnMessage
    public void handleA(String msg) {}

    @OnMessage
    public void handleB(String msg) {}
}
```
**Diagnostic Output Produced**:
```
**************************************************************
🦖 BEDROCK FATAL ERROR: Framework execution halted
**************************************************************
[Reason]: Class 'ConflictingSocket' has multiple methods annotated with @OnMessage: 'handleA' and 'handleB'.

[Action Required]: 
Consolidate your message reception logic into a single method annotated with @OnMessage.
**************************************************************
```

#### Failure Scenario 2: Invalid Parameter Type in `@OnMessage`
```java
@BedrockSocket("/invalid-param")
public class InvalidParamSocket {
    @OnMessage
    public void handle(String msg, int count) {}
}
```
**Diagnostic Output Produced**:
```
**************************************************************
🦖 BEDROCK FATAL ERROR: Framework execution halted
**************************************************************
[Reason]: Method 'handle' in class 'InvalidParamSocket' annotated with @OnMessage has unsupported parameter type 'int'.

[Action Required]: 
Allowed parameter types for @OnMessage: BedrockWebSocketSession, String.
**************************************************************
```

#### Failure Scenario 3: Conflicting Path Attributes
```java
@BedrockSocket(value = "/chat", path = "/room")
public class PathConflictSocket {
    @OnOpen
    public void onOpen() {}
}
```
**Diagnostic Output Produced**:
```
**************************************************************
🦖 BEDROCK FATAL ERROR: Framework execution halted
**************************************************************
[Reason]: Conflicting route paths specified in @BedrockSocket on 'PathConflictSocket': value = '/chat' and path = '/room'.

[Action Required]: 
Specify either 'value' or 'path', or ensure both attributes have identical values.
**************************************************************
```

---

## 8. Verification Strategy & Test Matrix

To independently verify the declarative annotations and reflective binding scanner, the following test suite will be implemented in `BedrockWebSocketAnnotationTest.java`:

| # | Test Scenario | Description | Expected Outcome |
|---|---------------|-------------|------------------|
| 1 | Full Endpoint Scan | Scan a class with valid `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`. | `ScannedEndpoint` populated with correct path and all 4 non-null method handles. |
| 2 | Minimal Endpoint Scan | Scan a class with only `@OnMessage(String)`. | Succeeds; `onOpen`, `onClose`, `onError` are null; binding dispatches message properly. |
| 3 | Parameter Permutations | Test all permutations of parameter ordering for `@OnMessage`, `@OnClose`, `@OnError`. | Dynamic `matchArgs` resolves parameters regardless of ordering. |
| 4 | No-arg Callbacks | `@OnOpen()`, `@OnClose()`, `@OnError()` with 0 arguments. | Succeeds; invokes methods without passing parameters. |
| 5 | Path Alias & Normalization | Test `@BedrockSocket("chat")`, `@BedrockSocket(path = "/chat/")`, `@BedrockSocket`. | Normalized to `"/chat"`, `"/chat"`, `"/"` respectively. |
| 6 | Subclass Inheritance | Subclass inherits `@OnError` from abstract parent. | Discovers inherited method and dispatches cleanly. |
| 7 | Method Override | Subclass overrides `@OnMessage` with identical signature. | Subclass method is selected without throwing duplicate method error. |
| 8 | Conflicting Path Error | Class with `@BedrockSocket(value = "/a", path = "/b")`. | Throws `BedrockException` with descriptive reason and action. |
| 9 | Duplicate Annotation Error | Class with two `@OnMessage` methods. | Throws `BedrockException` with descriptive reason and action. |
| 10 | Multiple Annotations on One Method | Method annotated with `@OnOpen` and `@OnClose`. | Throws `BedrockException` with descriptive reason and action. |
| 11 | Invalid Parameter Error | `@OnOpen(String)` or `@OnMessage(int)`. | Throws `BedrockException` indicating invalid type and allowed types. |
| 12 | Abstract Class Error | Scanning an abstract class or interface annotated with `@BedrockSocket`. | Throws `BedrockException` indicating abstract classes cannot be endpoints. |

---

## 9. Conclusion

This design delivers an elegant, zero-dependency declarative WebSocket API tailored to Bedrock's educational mission. By coupling strict compile/startup validation with direct reflection and Project Loom virtual threads, Bedrock offers enterprise-grade developer ergonomics without dynamic proxy black boxes or external dependencies.
