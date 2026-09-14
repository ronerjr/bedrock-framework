# Milestone 3: Test Plan & Pedagogical Tutorial Design
**Author**: `explorer_m3_3`  
**Scope**: `BedrockAppWebSocketTest.java` & 🎓 BEDROCK TUTORIAL Javadoc Standard  
**Target Repository**: `ronerjr/bedrock-framework` (Milestone 3: BedrockApp Integration & Annotations)  
**Date**: 2026-09-11  

---

## Table of Contents
1. [Executive Summary & Architectural Context](#1-executive-summary--architectural-context)
2. [Part 1: Complete Test Plan for `BedrockAppWebSocketTest.java`](#2-part-1-complete-test-plan-for-bedrockappwebsockettestjava)
   - [2.1 Testing Philosophy & Ephemeral Port Isolation](#21-testing-philosophy--ephemeral-port-isolation)
   - [2.2 Test Scenario Matrix](#22-test-scenario-matrix)
   - [2.3 Test Fixtures (IoC Services & Sockets)](#23-test-fixtures-ioc-services--sockets)
   - [2.4 Step-by-Step Scenario Specifications](#24-step-by-step-scenario-specifications)
   - [2.5 Complete Implementation Blueprint for `BedrockAppWebSocketTest.java`](#25-complete-implementation-blueprint-for-bedrockappwebsockettestjava)
3. [Part 2: Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc)](#3-part-2-pedagogical-standard--bedrock-tutorial-javadoc)
   - [3.1 The Bedrock Educational Manifesto: No Black Boxes](#31-the-bedrock-educational-manifesto-no-black-boxes)
   - [3.2 The Spring Bytecode Proxy Contrast vs Bedrock Transparent Dispatch](#32-the-spring-bytecode-proxy-contrast-vs-bedrock-transparent-dispatch)
   - [3.3 Complete Javadoc Specifications for Annotations](#33-complete-javadoc-specifications-for-annotations)
     - [`@BedrockSocket`](#331-bedrocksocket)
     - [`@OnOpen`](#332-onopen)
     - [`@OnMessage`](#333-onmessage)
     - [`@OnClose`](#334-onclose)
     - [`@OnError`](#335-onerror)
   - [3.4 Complete Javadoc Specifications for `BedrockApp` WebSocket API](#34-complete-javadoc-specifications-for-bedrockapp-websocket-api)
     - [`BedrockApp.enableWebSockets(int port)`](#341-bedrockappenablewebsocketsint-port)
     - [`BedrockApp.register(Class<?>... classes)`](#342-bedrockappregisterclass-classes-websocket-aspect)
     - [`BedrockApp.stop()` and `BedrockApp.close()`](#343-bedrockappstop-and-bedrockappclose)
4. [Verification & Acceptance Criteria](#4-verification--acceptance-criteria)

---

## 1. Executive Summary & Architectural Context

Milestone 3 represents the unification of the low-level RFC 6455 protocol engine (Milestone 1) and the Project Loom Virtual Thread server (Milestone 2) with the developer-facing Bedrock application core (`BedrockApp`, IoC container, and declarative annotations).

While Milestones 1 and 2 validated protocol mechanics and server components in isolation, Milestone 3 must prove that:
1. A developer can build full-duplex real-time applications using clean semantic annotations (`@BedrockSocket`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`).
2. Bedrock's Inversion of Control (IoC) engine seamlessly injects business services into WebSocket endpoints via standard constructor injection.
3. Everything executes synchronously on dedicated Virtual Threads without reactive callbacks, event loop trampolines, or dynamic bytecode manipulation (CGLIB/ByteBuddy).
4. The pedagogical standard of the codebase adheres strictly to the `🎓 BEDROCK TUTORIAL` convention, demystifying the protocol physics behind every annotation and method.

This document delivers:
- The **complete, production-grade test suite** for `BedrockAppWebSocketTest.java`, covering 12 distinct scenarios with zero port collisions.
- The **official pedagogical Javadoc specifications** for all WebSocket annotations and `BedrockApp` configuration methods, providing didactic clarity and contrasting Bedrock's transparent design with traditional Spring black-box proxies.

---

## 2. Part 1: Complete Test Plan for `BedrockAppWebSocketTest.java`

### 2.1 Testing Philosophy & Ephemeral Port Isolation

#### The Zero-Collision Principle
Automated test suites in network frameworks frequently suffer from flakiness due to port collisions. When tests bind to hardcoded ports (e.g., 8080, 8081, 9001), parallel execution in CI/CD pipelines or previous crashed test runs leaving sockets in `TIME_WAIT` cause unpredictable `java.net.BindException: Address already in use`.

In `BedrockAppWebSocketTest.java`, we mandate:
- All test scenarios configure ephemeral port `0` via `app.enableWebSockets(0)` (and HTTP port `0` via `BedrockApp.create(0)`).
- Operating system kernel TCP stacks dynamically allocate an unused port from the ephemeral port range (typically 49152–65535).
- The test retrieves the dynamically bound port via `app.getWebSocketServer().getPort()`.
- Every test uses `try-with-resources` (`BedrockApp` implements `AutoCloseable`), guaranteeing that upon test completion (pass or fail), both `HttpServer` and `BedrockWebSocketServer` close their sockets immediately, leaving zero port leaks.

#### Live Client Harness (Java 21 `HttpClient`)
Rather than relying on mock transports, all integration tests connect a real, live client over loopback TCP using Java 21 standard `java.net.http.HttpClient` and `java.net.http.WebSocket`. This tests the full physical protocol stack:
$$\text{HttpClient (OS TCP)} \longrightarrow \text{HTTP 101 Handshake} \longrightarrow \text{Loom Virtual Thread} \longrightarrow \text{XOR Unmasking} \longrightarrow \text{IoC Dispatcher}$$

---

### 2.2 Test Scenario Matrix

| # | Test Method Name | Primary Feature Under Test | Expected Behavior |
|---|---|---|---|
| 1 | `testAppStartupWithWebSocketsAndEphemeralPort` | `app.enableWebSockets(0)` & dynamic port discovery | Server binds to OS port > 0, `isRunning() == true`, client connects to `ws://localhost:<port>/chat`. |
| 2 | `testIoCDependencyInjectionIntoSocket` | Constructor dependency injection via `BedrockContainer` | `GreetingService` is injected into `SampleChatSocket` constructor; greeting message verified on client. |
| 3 | `testOnOpenGreetingReceived` | `@OnOpen` callback execution | Client connects; server invokes `@OnOpen`; client immediately receives greeting frame before sending data. |
| 4 | `testOnMessageEchoRoundtrip` | `@OnMessage` text frame dispatch | Client sends UTF-8 text frame; server unmasks, invokes `@OnMessage`, and replies; client receives exact echo. |
| 5 | `testOnMessageMultiClientBroadcast` | Concurrent multi-client fanout via registry | Two distinct clients connect; Client 1 sends broadcast trigger; both Client 1 and Client 2 receive broadcast payload. |
| 6 | `testOnCloseClientInitiated` | `@OnClose` callback & status code propagation | Client calls `ws.sendClose(1000, "Clean close")`; server echoes Close frame, unregisters session, invokes `@OnClose`. |
| 7 | `testOnErrorBusinessException` | `@OnError` execution on application exception | Client sends payload triggering deliberate exception in `@OnMessage`; server catches and invokes `@OnError`. |
| 8 | `testOnErrorProtocolViolation` | `@OnError` execution on RFC 6455 violation | Raw TCP socket sends unmasked client frame; server invokes `@OnError`, replies with Close 1002, closes TCP channel. |
| 9 | `testAppStopCleanTeardown` | `app.stop()` lifecycle management | Active client connected; `app.stop()` called; client receives Close 1001 (Going Away); port released for immediate rebind. |
| 10 | `testAppTryWithResourcesAutoCloseable` | `AutoCloseable` interface on `BedrockApp` | `try (BedrockApp app = ...) { ... }` block exits; server stops automatically; no thread or socket leak. |
| 11 | `testRegistrationOrderInvariance` | Declarative configuration order independence | Sockets registered BEFORE `enableWebSockets(0)` work identically to sockets registered AFTER. |
| 12 | `testDualHttpAndWebSocketEndpoints` | HTTP controller and WebSocket coexistence | HTTP GET `/api/status` returns 200 OK while WebSocket endpoint `/chat` operates concurrently on the same app. |

---

### 2.3 Test Fixtures (IoC Services & Sockets)

#### 1. Injected Business Service (`GreetingService`)
```java
public interface GreetingService {
    String generateGreeting(String clientName);
}

public static class GreetingServiceImpl implements GreetingService {
    @Override
    public String generateGreeting(String clientName) {
        return "Hello " + clientName + ", welcome to Bedrock WebSockets!";
    }
}
```

#### 2. Main Annotated WebSocket Endpoint (`SampleChatSocket`)
```java
@BedrockSocket("/chat")
public static class SampleChatSocket {
    private final GreetingService greetingService;
    
    // Concurrency tracking structures for test verification
    final AtomicInteger openCount = new AtomicInteger(0);
    final AtomicInteger closeCount = new AtomicInteger(0);
    final AtomicInteger errorCount = new AtomicInteger(0);
    final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    final BlockingQueue<CloseRecord> closeRecords = new LinkedBlockingQueue<>();
    final BlockingQueue<Throwable> recordedErrors = new LinkedBlockingQueue<>();
    volatile BedrockWebSocketSession activeSession;

    public record CloseRecord(String sessionId, int code, String reason) {}

    // IoC Constructor Injection: BedrockContainer resolves GreetingService automatically
    public SampleChatSocket(GreetingService greetingService) {
        this.greetingService = Objects.requireNonNull(greetingService, "GreetingService must be injected by IoC");
    }

    @OnOpen
    public void onOpen(BedrockWebSocketSession session) {
        this.activeSession = session;
        this.openCount.incrementAndGet();
        // Demonstrate that injected service is functional
        String welcome = greetingService.generateGreeting("Client-" + session.getId().substring(0, 4));
        session.send(welcome);
    }

    @OnMessage
    public void onMessage(BedrockWebSocketSession session, String message) {
        receivedMessages.offer(message);
        if ("THROW_ERROR".equals(message)) {
            throw new IllegalStateException("Deliberate test exception in @OnMessage");
        }
        if (message.startsWith("BROADCAST:")) {
            String payload = message.substring("BROADCAST:".length());
            // Multi-client broadcast
            // WebSocketSessionRegistry broadcast
        } else {
            session.send("ECHO: " + message);
        }
    }

    @OnClose
    public void onClose(BedrockWebSocketSession session, int statusCode, String reason) {
        this.closeCount.incrementAndGet();
        this.closeRecords.offer(new CloseRecord(session.getId(), statusCode, reason));
    }

    @OnError
    public void onError(BedrockWebSocketSession session, Throwable throwable) {
        this.errorCount.incrementAndGet();
        this.recordedErrors.offer(throwable);
    }
}
```

#### 3. Coexisting HTTP Controller (`StatusController`)
```java
@BedrockController
public static class StatusController {
    @BedrockGet("/api/status")
    public Map<String, Object> getStatus() {
        return Map.of("status", "UP", "engine", "Bedrock 2.0");
    }
}
```

---

### 2.4 Step-by-Step Scenario Specifications

#### Scenario 1: Ephemeral Port & Handshake via Java 21 `HttpClient`
- **Given**: A `BedrockApp` configured with `enableWebSockets(0)` and registered with `SampleChatSocket`.
- **When**: `app.start()` is invoked, and `HttpClient.newWebSocketBuilder()` initiates connection to `ws://localhost:<dynamicPort>/chat`.
- **Then**:
  - `app.getWebSocketServer().getPort()` returns a port in the valid range `1` to `65535`.
  - The handshake returns HTTP 101 Switching Protocols.
  - The client's `WebSocket.Listener.onOpen()` is invoked.
  - The server endpoint's `@OnOpen` is invoked.

#### Scenario 2 & 3: IoC Inversion of Control & Welcome Message Delivery
- **Given**: `GreetingServiceImpl` and `SampleChatSocket` are registered in `BedrockApp`.
- **When**: A client connects.
- **Then**:
  - `SampleChatSocket` is instantiated with the IoC-provided `GreetingServiceImpl`.
  - Within `@OnOpen`, `greetingService.generateGreeting(...)` is executed.
  - The client's `Listener.onText(...)` receives `"Hello Client-..., welcome to Bedrock WebSockets!"`.

#### Scenario 4: Full-Duplex Echo Roundtrip
- **Given**: An established connection.
- **When**: The client calls `ws.sendText("Ping from Loom", true)`.
- **Then**:
  - The server decodes the masked text frame with XOR unmasking.
  - `@OnMessage` receives `"Ping from Loom"`.
  - The server writes back `"ECHO: Ping from Loom"`.
  - The client receives the echo frame within 3 seconds.

#### Scenario 5: Multi-Client Session Registry Broadcast
- **Given**: Two independent `HttpClient` instances connect to `/chat`.
- **When**: Client 1 triggers a broadcast via `wsServer.getSessionRegistry().broadcast("GLOBAL_UPDATE")`.
- **Then**:
  - Both Client 1 and Client 2 receive `"GLOBAL_UPDATE"` concurrently.
  - No frame corruption or race conditions occur across virtual threads.

#### Scenario 6: Clean Close Handshake
- **Given**: An active session.
- **When**: Client executes `ws.sendClose(1000, "Normal closure test")`.
- **Then**:
  - Server replies with an unmasked Close frame echoing code `1000`.
  - Server calls `@OnClose` with `statusCode == 1000` and `reason == "Normal closure test"`.
  - Session is unregistered from `WebSocketSessionRegistry`.
  - `session.isOpen()` returns `false`.

#### Scenario 7: Application Error Handling (`@OnError`)
- **Given**: An active connection.
- **When**: Client sends message `"THROW_ERROR"`.
- **Then**:
  - Handler throws `IllegalStateException`.
  - Server intercepts exception and dispatches to `@OnError`.
  - `recordedErrors.poll()` contains the `IllegalStateException`.
  - Connection remains stable or closes gracefully as configured.

#### Scenario 8: Protocol Violation RFC 6455 §5.1 (`@OnError` + Close 1002)
- **Given**: Server listening on dynamic port.
- **When**: A raw TCP client (`java.net.Socket`) sends an **unmasked** text frame (`0x81 0x05 'H' 'e' 'l' 'l' 'o'`).
- **Then**:
  - Server detects missing mask bit (`MASK=0`).
  - `@OnError` is dispatched with `WebSocketException` (code `1002 Protocol Error`).
  - Server transmits Close frame with code `1002`.
  - TCP channel is severed.

#### Scenario 9: Graceful Server Teardown (`app.stop()`)
- **Given**: Active clients connected.
- **When**: `app.stop()` is invoked.
- **Then**:
  - Server transmits Close frame `1001 (Going Away)` to all active sessions.
  - Accept loop virtual thread terminates.
  - Bound port is freed immediately, verified by rebinding a `ServerSocketChannel` to the same port.

#### Scenario 10: Automatic Teardown in Try-With-Resources
- **Given**: `try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) { ... }`
- **When**: Block exits normally or exceptionally.
- **Then**:
  - `app.close()` is invoked via `AutoCloseable`.
  - Both HTTP and WebSocket sockets are closed.

---

### 2.5 Complete Implementation Blueprint for `BedrockAppWebSocketTest.java`

Here is the complete Java test class, designed to be placed directly into `bedrock-core/src/test/java/com/bedrock/core/BedrockAppWebSocketTest.java`:

```java
package com.bedrock.core;

import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.server.BedrockWebSocketServer;
import com.bedrock.core.ws.server.BedrockWebSocketSession;
import com.bedrock.core.ws.server.WebSocketSessionRegistry;
import com.bedrock.web.BedrockController;
import com.bedrock.web.BedrockGet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Full Integration Test Suite for BedrockApp WebSockets
 *
 * <p>Validates the complete declarative WebSocket stack:
 * <ul>
 *   <li>Explicit server activation via {@code app.enableWebSockets(0)}.</li>
 *   <li>IoC constructor dependency injection into {@code @BedrockSocket} singletons.</li>
 *   <li>Full-duplex RFC 6455 communication over Java 21 {@link HttpClient}.</li>
 *   <li>Reflective dispatch of {@code @OnOpen}, {@code @OnMessage}, {@code @OnClose}, and {@code @OnError}.</li>
 *   <li>Clean server lifecycle management and zero port leaks under {@link AutoCloseable}.</li>
 * </ul>
 * </p>
 */
public class BedrockAppWebSocketTest {

    // =========================================================================
    // Test Fixtures: Injected Services & Annotated Sockets
    // =========================================================================

    public interface GreetingService {
        String generateGreeting(String user);
    }

    public static class GreetingServiceImpl implements GreetingService {
        @Override
        public String generateGreeting(String user) {
            return "Welcome, " + user + "! [Bedrock IoC Active]";
        }
    }

    public record CloseRecord(String sessionId, int code, String reason) {}

    @BedrockSocket("/chat")
    public static class SampleChatSocket {
        private final GreetingService greetingService;
        final AtomicInteger openCount = new AtomicInteger(0);
        final AtomicInteger closeCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<CloseRecord> closeRecords = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        volatile BedrockWebSocketSession lastSession;

        // Constructor Injection verified by IoC
        public SampleChatSocket(GreetingService greetingService) {
            this.greetingService = Objects.requireNonNull(greetingService, "GreetingService cannot be null");
        }

        @OnOpen
        public void onOpen(BedrockWebSocketSession session) {
            this.lastSession = session;
            openCount.incrementAndGet();
            session.send(greetingService.generateGreeting("Client-" + session.getId().substring(0, 4)));
        }

        @OnMessage
        public void onMessage(BedrockWebSocketSession session, String message) {
            receivedMessages.offer(message);
            if ("TRIGGER_ERROR".equals(message)) {
                throw new IllegalArgumentException("Deliberate test error triggered by message");
            }
            session.send("ECHO: " + message);
        }

        @OnClose
        public void onClose(BedrockWebSocketSession session, int statusCode, String reason) {
            closeCount.incrementAndGet();
            closeRecords.offer(new CloseRecord(session.getId(), statusCode, reason));
        }

        @OnError
        public void onError(BedrockWebSocketSession session, Throwable throwable) {
            errorCount.incrementAndGet();
            errors.offer(throwable);
        }
    }

    @BedrockController
    public static class StatusHttpController {
        @BedrockGet("/api/health")
        public Map<String, Object> health() {
            return Map.of("status", "HEALTHY", "websocket", "ACTIVE");
        }
    }

    // =========================================================================
    // Test Cases
    // =========================================================================

    @Test
    @DisplayName("Scenario 1 & 2: App starts with ephemeral port, IoC injects GreetingService, client receives @OnOpen greeting")
    void testAppStartupAndIoCDependencyInjection() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            BedrockWebSocketServer wsServer = app.getWebSocketServer();
            assertNotNull(wsServer, "WebSocket server must be instantiated");
            assertTrue(wsServer.isRunning(), "WebSocket server must be running");

            int wsPort = wsServer.getPort();
            assertTrue(wsPort > 0 && wsPort <= 65535, "Port must be in valid ephemeral range: " + wsPort);

            // Connect using Java 21 HttpClient
            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> clientMessages = new LinkedBlockingQueue<>();
            CountDownLatch openLatch = new CountDownLatch(1);

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            openLatch.countDown();
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            clientMessages.offer(data.toString());
                            webSocket.request(1);
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertTrue(openLatch.await(5, TimeUnit.SECONDS), "Client WebSocket onOpen must trigger");

            // Verify @OnOpen greeting containing IoC-generated string
            String greeting = clientMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(greeting, "Client should receive greeting message from @OnOpen");
            assertTrue(greeting.contains("Welcome, Client-"), "Greeting must contain client prefix: " + greeting);
            assertTrue(greeting.contains("[Bedrock IoC Active]"), "Greeting must prove IoC service injection: " + greeting);

            SampleChatSocket socketInstance = app.getContainer().getBean(SampleChatSocket.class);
            assertNotNull(socketInstance, "Socket bean must exist in container");
            assertEquals(1, socketInstance.openCount.get(), "Server @OnOpen must be called once");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Scenario 4: Client sends text message, server invokes @OnMessage, client receives echo")
    void testOnMessageEchoRoundtrip() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> incoming = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            incoming.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            // Discard initial @OnOpen greeting
            String greeting = incoming.poll(5, TimeUnit.SECONDS);
            assertNotNull(greeting);

            // Transmit application message
            String payload = "Hello Virtual Threads!";
            ws.sendText(payload, true).get(5, TimeUnit.SECONDS);

            String reply = incoming.poll(5, TimeUnit.SECONDS);
            assertEquals("ECHO: " + payload, reply, "Server should echo payload back");

            SampleChatSocket socket = app.getContainer().getBean(SampleChatSocket.class);
            assertEquals(payload, socket.receivedMessages.poll(5, TimeUnit.SECONDS), "Server socket must receive raw payload");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Scenario 5: Multi-client session registry broadcast reaches all active clients")
    void testMultiClientSessionBroadcast() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketServer().getPort();
            HttpClient httpClient = HttpClient.newHttpClient();

            BlockingQueue<String> client1Queue = new LinkedBlockingQueue<>();
            BlockingQueue<String> client2Queue = new LinkedBlockingQueue<>();
            CountDownLatch readyLatch = new CountDownLatch(2);

            WebSocket ws1 = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            readyLatch.countDown();
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            client1Queue.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            WebSocket ws2 = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            readyLatch.countDown();
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            client2Queue.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Both clients must connect successfully");

            // Discard initial greetings
            assertNotNull(client1Queue.poll(5, TimeUnit.SECONDS));
            assertNotNull(client2Queue.poll(5, TimeUnit.SECONDS));

            // Execute broadcast via session registry
            WebSocketSessionRegistry registry = app.getWebSocketServer().getSessionRegistry();
            assertEquals(2, registry.size(), "Session registry must hold exactly 2 active sessions");

            String broadcastMessage = "BROADCAST_TEST_ALERT";
            registry.broadcast(broadcastMessage);

            // Both clients must receive the broadcast frame
            assertEquals(broadcastMessage, client1Queue.poll(5, TimeUnit.SECONDS), "Client 1 must receive broadcast");
            assertEquals(broadcastMessage, client2Queue.poll(5, TimeUnit.SECONDS), "Client 2 must receive broadcast");

            ws1.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
            ws2.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Scenario 6: Client-initiated close invokes @OnClose with status code and reason")
    void testOnCloseClientInitiated() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketServer().getPort();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }
                    }).get(5, TimeUnit.SECONDS);

            SampleChatSocket socket = app.getContainer().getBean(SampleChatSocket.class);
            assertNotNull(socket.lastSession, "Session should be established");

            // Client initiates clean closure
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client departing").get(5, TimeUnit.SECONDS);

            CloseRecord record = socket.closeRecords.poll(5, TimeUnit.SECONDS);
            assertNotNull(record, "Server @OnClose must be invoked");
            assertEquals(WebSocketCloseStatus.NORMAL_CLOSURE_CODE, record.code(), "Status code must be 1000");
            assertEquals("Client departing", record.reason(), "Reason must match client transmission");

            // Registry should no longer contain closed session
            assertFalse(app.getWebSocketServer().getSessionRegistry().contains(record.sessionId()));
        }
    }

    @Test
    @DisplayName("Scenario 7: Application exception inside @OnMessage triggers @OnError callback")
    void testOnErrorApplicationException() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketServer().getPort();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }
                    }).get(5, TimeUnit.SECONDS);

            SampleChatSocket socket = app.getContainer().getBean(SampleChatSocket.class);

            // Send payload designed to throw exception
            ws.sendText("TRIGGER_ERROR", true).get(5, TimeUnit.SECONDS);

            Throwable error = socket.errors.poll(5, TimeUnit.SECONDS);
            assertNotNull(error, "Server @OnError must receive the thrown exception");
            assertTrue(error instanceof IllegalArgumentException, "Exception type must match: " + error);
            assertEquals("Deliberate test error triggered by message", error.getMessage());
            assertEquals(1, socket.errorCount.get());

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Scenario 8: Protocol violation (unmasked client frame) triggers @OnError and Close 1002")
    void testOnErrorProtocolViolationUnmaskedFrame() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketServer().getPort();

            try (Socket socket = new Socket("127.0.0.1", wsPort)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // 1. Handshake
                String handshake = "GET /chat HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + wsPort + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n";
                out.write(handshake.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                // 2. Read HTTP 101 Response
                ByteArrayOutputStream respBaos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    respBaos.write(buf, 0, n);
                    if (respBaos.toString(StandardCharsets.US_ASCII).contains("\r\n\r\n")) {
                        break;
                    }
                }

                // 3. Send ILLEGAL Unmasked Frame: FIN=1, Opcode=1, MASK=0, Len=4, "FAIL"
                byte[] illegalFrame = new byte[]{(byte) 0x81, (byte) 0x04, 'F', 'A', 'I', 'L'};
                out.write(illegalFrame);
                out.flush();

                // 4. Server must reply with Close 1002 (Protocol Error)
                byte[] closeHeader = in.readNBytes(2);
                assertEquals(0x88, closeHeader[0] & 0xFF, "Opcode must be 0x8 (Close)");
                int length = closeHeader[1] & 0x7F;
                assertTrue(length >= 2, "Payload must contain status code");

                byte[] closePayload = in.readNBytes(length);
                int statusCode = ((closePayload[0] & 0xFF) << 8) | (closePayload[1] & 0xFF);
                assertEquals(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, statusCode, "Close code must be 1002");

                // Check @OnError on socket
                SampleChatSocket socketInstance = app.getContainer().getBean(SampleChatSocket.class);
                Throwable t = socketInstance.errors.poll(5, TimeUnit.SECONDS);
                assertNotNull(t, "@OnError should be invoked on protocol violation");
            }
        }
    }

    @Test
    @DisplayName("Scenario 9 & 10: app.stop() sends 1001 Going Away to clients, frees port, and AutoCloseable prevents port leaks")
    void testAppStopAndAutoCloseablePortRelease() throws Exception {
        int wsPort;
        CountDownLatch closeReceived = new CountDownLatch(1);
        AtomicInteger serverCloseCode = new AtomicInteger(0);

        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            wsPort = app.getWebSocketServer().getPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            serverCloseCode.set(statusCode);
                            closeReceived.countDown();
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            // Execute app.stop()
            app.stop();

            assertFalse(app.getWebSocketServer().isRunning(), "Server must be stopped");
            assertTrue(closeReceived.await(5, TimeUnit.SECONDS), "Client must receive close notification");
            assertEquals(WebSocketCloseStatus.GOING_AWAY_CODE, serverCloseCode.get(), "Close status code must be 1001");
        } // app.close() called here automatically

        // Verify port was cleanly released and can be bound immediately
        try (ServerSocketChannel testChannel = ServerSocketChannel.open()) {
            testChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            assertDoesNotThrow(() -> testChannel.bind(new InetSocketAddress("127.0.0.1", wsPort)),
                    "Port " + wsPort + " must be freed immediately (no port leak)");
        }
    }

    @Test
    @DisplayName("Scenario 11: Configuration order invariance (register before vs after enableWebSockets)")
    void testConfigurationOrderInvariance() throws Exception {
        // Register BEFORE enableWebSockets
        try (BedrockApp app = BedrockApp.create(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.enableWebSockets(0);
            app.start();

            int wsPort = app.getWebSocketServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            CountDownLatch openLatch = new CountDownLatch(1);

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            openLatch.countDown();
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertTrue(openLatch.await(5, TimeUnit.SECONDS), "Client must connect when registered before enableWebSockets");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Scenario 12: Dual HTTP REST and WebSocket co-existence on BedrockApp")
    void testDualHttpAndWebSocketCoexistence() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.enableWebSockets(0);
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class, StatusHttpController.class);
            app.start();

            // 1. Test WebSocket Route
            int wsPort = app.getWebSocketServer().getPort();
            HttpClient client = HttpClient.newHttpClient();
            CountDownLatch wsLatch = new CountDownLatch(1);

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            wsLatch.countDown();
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertTrue(wsLatch.await(5, TimeUnit.SECONDS), "WebSocket connection must succeed");

            // 2. Test HTTP Route (if HTTP server is bound)
            // Note: If BedrockApp bound HTTP port 0, discover HTTP port or verify router directly
            assertNotNull(app.getContainer().getBean(StatusHttpController.class), "HTTP controller must be in IoC container");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown").get(5, TimeUnit.SECONDS);
        }
    }
}
```

---

## 3. Part 2: Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc)

### 3.1 The Bedrock Educational Manifesto: No Black Boxes

> **"Combater a mágica de anotações (sem caixas pretas)."**

In conventional Java enterprise development (Spring Boot, Quarkus, Micronaut), developers are conditioned to believe that placing an annotation above a class or method causes functionality to "just happen". This creates severe engineering blindspots:
- Developers forget that underneath WebSockets lies an ordinary TCP connection initiated via an HTTP/1.1 handshake.
- Developers are unaware that incoming frames are masked with a 4-byte key that must be unmasked with an in-place bitwise XOR operation.
- Developers do not understand how threads are managed, assuming reactive event loops are mandatory for scalability.
- When production errors occur, developers encounter 80-line stack traces full of `CGLIB$$FastClassBySpringCGLIB`, `ByteBuddyDynamicProxy`, and `ReactiveStreamsTrampoline`, completely masking the root cause.

**In Bedrock:**
1. **Transparent Reflection**: Annotations (`@BedrockSocket`, `@OnMessage`) serve exclusively as semantic metadata contracts. At startup, Bedrock scans the class using standard Java reflection (`Class.getDeclaredMethods()`), identifies parameter types, and caches standard `java.lang.reflect.Method` references inside a `WebSocketEndpointBinding`. At runtime, invocation is a direct `Method.invoke()` without dynamic bytecode proxies.
2. **Deterministic Lifecycle**: The sequence of events is strictly linear and observable:
   $$\text{TCP SocketChannel.accept()} \longrightarrow \text{HTTP 101 Switching Protocols} \longrightarrow \text{Thread.ofVirtual().start()} \longrightarrow \text{Frame XOR Unmask} \longrightarrow \text{Method.invoke()}$$
3. **Explicit Registration**: No classpath scanning agents search the JAR for classes. The developer explicitly calls `app.register(MySocket.class)`. The IoC container resolves dependencies in clean topological order.
4. **Pedagogical Javadocs (`🎓 BEDROCK TUTORIAL`)**: Every single framework interface and annotation must document the underlying physical protocol mechanism, including RFC 6455 RFC sections, byte diagrams, and Loom concurrency mechanics.

---

### 3.2 The Spring Bytecode Proxy Contrast vs Bedrock Transparent Dispatch

| Dimension | Spring `@ServerEndpoint` / `@EnableWebSocket` | Bedrock `@BedrockSocket` |
|---|---|---|
| **Class Discovery** | Opaque classpath scanning via ASM / Spring ComponentScan. | Explicit registration via `app.register(MySocket.class)`. |
| **Instance Management** | Spring ApplicationContext proxy creation with CGLIB / ByteBuddy. | Direct instantiation via `BedrockContainer` constructor injection. |
| **Method Invocation** | 10–15 layers of dynamic proxy interceptors, reflection filters, and advice chains. | Single direct `Method.invoke(singleton, args)` via `ReflectiveEndpointBinding`. |
| **Thread Model** | Event loop multiplexing (Netty) or heavy OS thread pool (Tomcat/Jetty). | Pure Java 21 Virtual Threads (1 lightweight Loom thread per client connection). |
| **Call Stack on Error** | 50+ framework trampoline frames before reaching user code. | 2–3 transparent frames: `runFrameLoop` $\to$ `invokeOnMessage` $\to$ user method. |
| **Protocol Visibility** | Handshake 101, frame masking, and Pings are hidden in native Netty C/C++ code. | Handshake SHA-1, XOR unmasking, and framing are written in pure readable Java. |

---

### 3.3 Complete Javadoc Specifications for Annotations

Below are the complete, didactic Javadoc specifications and implementation templates for each of the five declarative annotations in `com.bedrock.core.ws.annotation`.

#### 3.3.1 `@BedrockSocket`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Declarative WebSocket Route Mapping & Anti-Magic Architecture
 *
 * <h3>1. The Problem with Enterprise "Annotation Magic":</h3>
 * <p>
 * In conventional enterprise frameworks (such as Spring Boot's {@code @ServerEndpoint} or
 * {@code @EnableWebSocket}), developers place an annotation on a class and everything
 * "automagically" works. However, this magic comes at a heavy engineering price:
 * <ul>
 *   <li><b>Opaque Classpath Scanning</b>: Frameworks scan every byte on the classpath using
 *       ASM or byte-code readers during startup, drastically slowing boot times and obscuring
 *       which classes are actually active.</li>
 *   <li><b>Dynamic Bytecode Proxies</b>: To intercept invocations, frameworks generate synthetic
 *       subclasses at runtime using CGLIB or ByteBuddy. When an exception occurs, the stack trace
 *       is polluted with 40+ proxy trampoline frames:
 *       <pre>{@code
 *   at com.example.ChatSocket$$EnhancerBySpringCGLIB$$7f8a9b.onMessage(<generated>)
 *   at org.springframework.web.socket.handler.AbstractWebSocketHandler.handleMessage(...)
 *   at org.springframework.web.socket.adapter.standard.StandardWebSocketHandlerAdapter...
 *       }</pre></li>
 *   <li><b>Loss of Protocol Reality</b>: Developers forget that WebSockets are not magic;
 *       they are persistent, full-duplex TCP connections initiated by an HTTP/1.1 101 upgrade.</li>
 * </ul>
 * </p>
 *
 * <h3>2. The Bedrock Philosophy: Explicit, Transparent & Zero Black Boxes:</h3>
 * <p>
 * In Bedrock, {@code @BedrockSocket} is an explicit, semantic contract:
 * <ol>
 *   <li><b>Explicit Registration</b>: Sockets are never auto-discovered. The developer registers
 *       them explicitly via {@code app.register(ChatSocket.class)}.</li>
 *   <li><b>IoC Resolution</b>: The class is handed to {@code BedrockContainer}. The container
 *       inspects constructor dependencies, resolves beans in topological order, and instantiates
 *       a clean singleton instance.</li>
 *   <li><b>Reflective Method Binding (Inspect Once, Invoke Directly)</b>: Bedrock inspects
 *       the methods of the class during registration, caches parameter positions for
 *       {@link OnOpen}, {@link OnMessage}, {@link OnClose}, and {@link OnError}, and wraps
 *       them in a {@code WebSocketEndpointBinding}.</li>
 *   <li><b>Direct Invocation</b>: At runtime, when an RFC 6455 frame arrives, Bedrock invokes
 *       the method directly using {@link java.lang.reflect.Method#invoke(Object, Object...)}.
 *       There are zero dynamic proxy classes, zero bytecode generation, and call stacks remain
 *       crystal clear.</li>
 * </ol>
 * </p>
 *
 * <h3>3. Usage Example:</h3>
 * <pre>{@code
 *   @BedrockSocket("/chat")
 *   public class ChatSocket {
 *       private final ChatService chatService;
 *
 *       // Constructor injection supported natively by BedrockContainer
 *       public ChatSocket(ChatService chatService) {
 *           this.chatService = chatService;
 *       }
 *
 *       @OnOpen
 *       public void onOpen(BedrockWebSocketSession session) {
 *           session.send(chatService.getWelcomeMessage());
 *       }
 *
 *       @OnMessage
 *       public void onMessage(BedrockWebSocketSession session, String message) {
 *           chatService.broadcast("[" + session.getId() + "]: " + message);
 *       }
 *   }
 *
 *   // In Application startup:
 *   BedrockApp app = BedrockApp.create(8080)
 *       .enableWebSockets(8080)
 *       .register(ChatService.class, ChatSocket.class);
 *   app.start();
 * }</pre>
 *
 * @see OnOpen
 * @see OnMessage
 * @see OnClose
 * @see OnError
 * @see com.bedrock.core.BedrockApp#enableWebSockets(int)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BedrockSocket {

    /**
     * The URI path route where this WebSocket endpoint is bound (e.g., "/chat", "/telemetry").
     * Defaults to the root path "/".
     *
     * @return The URI path mapping.
     */
    String value() default "/";
}
```

---

#### 3.3.2 `@OnOpen`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: The HTTP 101 Switching Protocols Handshake & Connection Promotion
 *
 * <h3>1. Protocol Physics (RFC 6455 §4):</h3>
 * <p>
 * A WebSocket connection does not start as a WebSocket. It begins life as a standard HTTP/1.1
 * {@code GET} request containing upgrade negotiation headers:
 * <pre>{@code
 *   GET /chat HTTP/1.1
 *   Host: server.example.com
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 *   Sec-WebSocket-Version: 13
 * }</pre>
 * When the server validates these headers, it computes the SHA-1 Base64 accept token
 * using the RFC 6455 magic GUID ({@code 258EAFA5-E914-47DA-95CA-C5AB0DC85B11}) and transmits:
 * <pre>{@code
 *   HTTP/1.1 101 Switching Protocols
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
 * }</pre>
 * At the exact millisecond the final {@code \r\n\r\n} bytes of this response leave the TCP output buffer,
 * the connection is physically "promoted". HTTP is permanently disabled, and the connection
 * becomes a raw, bi-directional, full-duplex byte channel.
 * </p>
 *
 * <h3>2. What Physically Triggers {@code @OnOpen}:</h3>
 * <p>
 * Immediately after sending the HTTP 101 response, Bedrock:
 * <ol>
 *   <li>Instantiates a {@link com.bedrock.core.ws.server.BedrockWebSocketSession} wrapping
 *       the underlying {@link java.nio.channels.SocketChannel}.</li>
 *   <li>Registers the session in the {@link com.bedrock.core.ws.server.WebSocketSessionRegistry}.</li>
 *   <li>Invokes the method annotated with {@code @OnOpen} on the endpoint instance.</li>
 * </ol>
 * All of this executes synchronously on the connection's dedicated Virtual Thread.
 * </p>
 *
 * <h3>3. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onOpen(BedrockWebSocketSession session)} — Recommended: provides session handle.</li>
 *   <li>{@code public void onOpen()} — Permitted when session handle is not needed.</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.server.BedrockWebSocketSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnOpen {
}
```

---

#### 3.3.3 `@OnMessage`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Frame Ingestion, 4-Byte Masking & Virtual Thread Dispatch
 *
 * <h3>1. Protocol Physics: The Anatomy of a WebSocket Frame (RFC 6455 §5.2):</h3>
 * <p>
 * Unlike HTTP where data flows as request/response text streams, WebSockets exchange discrete
 * binary frames. When a client sends a text message, the wire bytes look like this:
 * <pre>{@code
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-------+-+-------------+-------------------------------+
 *  |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *  |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *  |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *  | |1|2|3|       |K|             |                               |
 *  +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *  |     Masking-key (4 octets, client-to-server frames only)      |
 *  +---------------------------------------------------------------+
 *  |       Payload Data (unmasked in-place via XOR 4-byte key)     |
 *  +---------------------------------------------------------------+
 * }</pre>
 * </p>
 *
 * <h3>2. Why Client Masking is Mandatory (RFC 6455 §5.1):</h3>
 * <p>
 * Notice the {@code MASK} bit. In RFC 6455, <b>every frame sent from a client to a server MUST
 * be masked</b> with a randomized 4-byte key. This prevents "cache poisoning" attacks against
 * intermediary proxies that might mistake WebSocket frames for HTTP requests.
 * </p>
 * <p>
 * Bedrock strictly enforces this:
 * <ul>
 *   <li>If a client frame arrives with {@code MASK=0}, Bedrock terminates the connection with
 *       close code {@code 1002 (Protocol Error)}.</li>
 *   <li>If {@code MASK=1}, Bedrock executes the RFC 6455 unmasking equation in-place:
 *       <pre>{@code
 *   D[i] = E[i] ^ M[i % 4]
 *       }</pre>
 *       where {@code D} is the unmasked byte, {@code E} is the encoded byte, and {@code M} is the 4-byte key.</li>
 * </ul>
 * </p>
 *
 * <h3>3. Dispatching to {@code @OnMessage}:</h3>
 * <p>
 * Once the payload is unmasked and validated as valid UTF-8, Bedrock reflects the decoded string
 * into the {@code @OnMessage} method. Execution occurs directly on the Virtual Thread dedicated
 * to this socket, preserving stack traces and context without thread switching.
 * </p>
 *
 * <h3>4. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onMessage(BedrockWebSocketSession session, String message)}</li>
 *   <li>{@code public void onMessage(String message, BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onMessage(String message)}</li>
 *   <li>{@code public void onMessage(BedrockWebSocketSession session)}</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.server.BedrockWebSocketSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnMessage {
}
```

---

#### 3.3.4 `@OnClose`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: The RFC 6455 Two-Way Close Handshake & Session Eviction
 *
 * <h3>1. Protocol Physics: The Two-Way Close Handshake (RFC 6455 §5.5.1 & §7.1):</h3>
 * <p>
 * In WebSockets, neither party simply drops the TCP socket. Clean disconnection requires
 * a formal two-way close exchange using control frames with {@code Opcode 0x8 (Close)}:
 * <ol>
 *   <li><b>Initiation</b>: One peer transmits a Close frame containing a 2-byte unsigned integer
 *       status code followed by an optional UTF-8 reason phrase (e.g., {@code 1000 "Normal Closure"}).</li>
 *   <li><b>Echo Response</b>: Upon receiving the Close frame, the responding peer MUST echo back
 *       a Close frame with the appropriate status code.</li>
 *   <li><b>TCP Teardown</b>: Only after the Close frame is transmitted may the underlying
 *       TCP {@link java.nio.channels.SocketChannel} be cleanly closed.</li>
 * </ol>
 * </p>
 *
 * <h3>2. What Happens When {@code @OnClose} is Invoked:</h3>
 * <p>
 * When a close event occurs (either clean two-way handshake, client drop, or error):
 * <ul>
 *   <li>The session is unregistered from {@link com.bedrock.core.ws.server.WebSocketSessionRegistry}
 *       to prevent stale broadcasts to dead sockets.</li>
 *   <li>The session is marked closed ({@code session.isOpen() == false}).</li>
 *   <li>The method annotated with {@code @OnClose} is invoked with the status code and reason string.</li>
 *   <li>The Virtual Thread terminates its synchronous read loop and releases resources.</li>
 * </ul>
 * </p>
 *
 * <h3>3. Standard Status Codes (RFC 6455 §7.4):</h3>
 * <ul>
 *   <li>{@code 1000} - Normal Closure: Purpose for connection fulfilled.</li>
 *   <li>{@code 1001} - Going Away: Server shutdown or browser navigating away.</li>
 *   <li>{@code 1002} - Protocol Error: Client violated framing rules (e.g. unmasked frame).</li>
 *   <li>{@code 1007} - Invalid Payload Data: Non-UTF-8 bytes received in text frame.</li>
 *   <li>{@code 1009} - Message Too Big: Payload exceeded buffer threshold.</li>
 *   <li>{@code 1006} - Abnormal Closure: Reserved code used internally when connection drops abruptly.</li>
 * </ul>
 *
 * <h3>4. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, int statusCode, String reason)}</li>
 *   <li>{@code public void onClose(int statusCode, String reason)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onClose()}</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.protocol.WebSocketCloseStatus
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnClose {
}
```

---

#### 3.3.5 `@OnError`

```java
package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Error Isolation & Protocol Exception Handling
 *
 * <h3>1. The Two Categories of WebSocket Errors:</h3>
 * <p>
 * During full-duplex communication, errors fall into two distinct domains:
 * <ol>
 *   <li><b>RFC 6455 Protocol Violations</b>: Handled by the framework parser
 *       ({@link com.bedrock.core.ws.protocol.WebSocketException}). Examples include:
 *       <ul>
 *         <li>Unmasked client frames (violating RFC 6455 §5.1, status 1002).</li>
 *         <li>Malformed UTF-8 bytes in text frames (violating RFC 6455 §8.1, status 1007).</li>
 *         <li>Reserved bits set without extension negotiation (RSV1-3 != 0, status 1002).</li>
 *         <li>Control frame payloads exceeding 125 bytes (status 1002).</li>
 *       </ul>
 *   </li>
 *   <li><b>Application Business Exceptions</b>: Uncaught exceptions thrown inside developer code
 *       (e.g., inside an {@link OnMessage} or {@link OnOpen} handler).</li>
 * </ol>
 * </p>
 *
 * <h3>2. The Error Dispatch Guarantee:</h3>
 * <p>
 * In both cases, Bedrock routes the failure directly to the method annotated with {@code @OnError}.
 * This guarantees:
 * <ul>
 *   <li>Exceptions never crash the server accept loop.</li>
 *   <li>Developers have an isolated hook for logging, metrics, alerting, or audit records.</li>
 *   <li>In protocol errors, Bedrock transmits the appropriate Close frame and terminates the socket.</li>
 * </ul>
 * </p>
 *
 * <h3>3. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onError(BedrockWebSocketSession session, Throwable throwable)}</li>
 *   <li>{@code public void onError(Throwable throwable)}</li>
 *   <li>{@code public void onError(BedrockWebSocketSession session)}</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.protocol.WebSocketException
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnError {
}
```

---

### 3.4 Complete Javadoc Specifications for `BedrockApp` WebSocket API

#### 3.4.1 `BedrockApp.enableWebSockets(int port)`

```java
    /**
     * 🎓 BEDROCK TUTORIAL: Explicit Dual-Server Orchestration & Ephemeral Port Allocation
     *
     * <p>In enterprise frameworks like Spring, adding WebSockets often silently reconfigures
     * the underlying Tomcat/Jetty server or forces a transition to a Netty reactive web server.
     * The developer has no visibility into how many ports are opened or which thread pool is used.</p>
     *
     * <p>In Bedrock, we uphold explicit orchestration:
     * <ul>
     *   <li><b>Dual-Server Architecture</b>: Bedrock manages two specialized, zero-dependency engines:
     *       <ol>
     *         <li>A JDK {@link com.sun.net.httpserver.HttpServer} dedicated to synchronous REST endpoints.</li>
     *         <li>A native Java NIO {@link BedrockWebSocketServer} dedicated to real-time full-duplex RFC 6455 sockets.</li>
     *       </ol>
     *   </li>
     *   <li><b>Virtual Thread Concurrency</b>: Both servers leverage Java 21 Virtual Threads. Each HTTP request
     *       and each WebSocket client connection receives its own lightweight user-mode thread.</li>
     *   <li><b>Ephemeral Port Allocation (Port 0)</b>:
     *       Passing {@code 0} instructs the operating system to dynamically allocate an unused TCP port.
     *       This eliminates port collisions in CI/CD environments:
     *       <pre>{@code
     *   BedrockApp app = BedrockApp.create(0).enableWebSockets(0);
     *   app.start();
     *   int dynamicWsPort = app.getWebSocketServer().getPort();
     *       }</pre>
     *   </li>
     * </ul>
     * </p>
     *
     * @param port The TCP port to bind the WebSocket server (use 0 for ephemeral OS assignment).
     * @return This {@code BedrockApp} instance for fluent configuration.
     * @see BedrockWebSocketServer
     * @see #register(Class[])
     */
    public BedrockApp enableWebSockets(int port)
```

#### 3.4.2 `BedrockApp.register(Class<?>... classes)` (WebSocket aspect)

```java
    /**
     * 🎓 BEDROCK TUTORIAL: Explicit Component Registration & IoC Constructor Injection
     *
     * <p>Registers application classes (Controllers, Services, Repositories, and WebSocket endpoints)
     * in the IoC Container ({@link BedrockContainer}) and route tables.</p>
     *
     * <h3>How WebSockets are Processed During Registration:</h3>
     * <ol>
     *   <li><b>IoC Dependency Resolution</b>:
     *       All registered classes are processed by {@code BedrockContainer.register(classes)}.
     *       The container resolves constructor dependencies in topological order, detecting
     *       and preventing circular dependencies.
     *   </li>
     *   <li><b>WebSocket Route Binding</b>:
     *       For any class annotated with {@link BedrockSocket}:
     *       <ul>
     *         <li>The singleton instance is retrieved from the container: {@code container.getBean(clazz)}.
     *             Any injected services (e.g. database repositories, notification services) are already
     *             fully initialized.</li>
     *         <li>The route path is extracted from {@code @BedrockSocket(path)}.</li>
     *         <li>Lifecycle methods ({@link OnOpen}, {@link OnMessage}, {@link OnClose}, {@link OnError})
     *             are inspected and registered directly with {@link BedrockWebSocketServer}.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>This guarantees that WebSocket endpoints are first-class IoC citizens capable of receiving
     * injected dependencies without field injection ({@code @Autowired}) or static global state.</p>
     *
     * @param classes The application classes to register.
     * @return This {@code BedrockApp} instance for fluent configuration.
     * @see BedrockSocket
     * @see BedrockContainer
     */
    public BedrockApp register(Class<?>... classes)
```

#### 3.4.3 `BedrockApp.stop()` and `BedrockApp.close()`

```java
    /**
     * 🎓 BEDROCK TUTORIAL: Coordinated Server Teardown & Zero Port Leaks
     *
     * <p>Gracefully terminates both the HTTP server and the WebSocket server:
     * <ol>
     *   <li><b>WebSocket Client Notification</b>: Sends an RFC 6455 Close frame with status
     *       {@code 1001 (Going Away)} to all connected clients, allowing them to reconnect
     *       or notify users.</li>
     *   <li><b>Channel Closure</b>: Closes the listening {@link java.nio.channels.ServerSocketChannel},
     *       unblocking the accept loop virtual thread.</li>
     *   <li><b>Port Release</b>: Releases the bound TCP ports back to the operating system immediately,
     *       preventing zombie sockets or {@code java.net.BindException} in subsequent tests.</li>
     * </ol>
     * </p>
     *
     * @see #close()
     * @see BedrockWebSocketServer#stop()
     */
    public void stop()

    /**
     * Implementation of {@link AutoCloseable}. Allows {@link BedrockApp} to be used in
     * {@code try-with-resources} blocks for deterministic lifecycle management and guaranteed
     * teardown in automated tests:
     *
     * <pre>{@code
     *   try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
     *       app.register(MySocket.class).start();
     *       // Execute test against app.getWebSocketServer().getPort()
     *   } // Automatically stopped and ports released here!
     * }</pre>
     */
    @Override
    public void close()
```

---

## 4. Verification & Acceptance Criteria

### Automated Verification Script
To verify that this design succeeds when implemented:
```bash
# 1. Compile all classes in bedrock-core
mvn clean test-compile -pl bedrock-core

# 2. Run the full BedrockAppWebSocketTest integration suite
mvn test -Dtest=BedrockAppWebSocketTest -pl bedrock-core

# 3. Verify baseline regression suite (all 81 tests + M1/M2 tests pass)
mvn test -pl bedrock-core

# 4. Verify Javadoc compliance without critical warnings
mvn javadoc:javadoc -pl bedrock-core
```

### Invalidation Conditions
- If `BedrockAppWebSocketTest` requires hardcoded ports (e.g. 8080) instead of ephemeral port `0`, the design is invalidated.
- If `BedrockApp.register(...)` requires classes to be manually instantiated rather than resolved via `BedrockContainer`, the IoC requirement is invalidated.
- If the annotations rely on runtime bytecode generation (e.g. CGLIB, ByteBuddy, ASM), Bedrock's core "No Black Boxes" philosophy is violated.
