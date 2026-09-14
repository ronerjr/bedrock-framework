# Technical Design Specification: Test Suite & Pedagogical Standard
**Milestone**: Milestone 2 (M2) — Virtual-Threaded NIO Server & Sessions  
**Author**: `explorer_m2_3`  
**Target Components**:
- Unit & Integration Test Suite: `com.bedrock.core.ws.server.BedrockWebSocketServerTest`
- Pedagogical Javadoc Standards: `BedrockWebSocketServer.java` & `WebSocketClientHandler.java`  
**JDK Target**: Java 21 LTS (Zero Third-Party Dependencies)

---

## Table of Contents
1. [Executive Summary & Architectural Scope](#1-executive-summary--architectural-scope)
2. [Unit & Integration Test Plan (`BedrockWebSocketServerTest.java`)](#2-unit--integration-test-plan-bedrockwebsocketservertestjava)
   - [2.1 Test Infrastructure & Ephemeral Port Strategy](#21-test-infrastructure--ephemeral-port-strategy)
   - [2.2 Test Endpoint Fixtures](#22-test-endpoint-fixtures)
   - [2.3 Test Matrix & Execution Scenarios](#23-test-matrix--execution-scenarios)
   - [2.4 Detailed Test Implementations](#24-detailed-test-implementations)
     - [Scenario 1: Ephemeral Port & Server Startup](#scenario-1-ephemeral-port--server-startup)
     - [Scenario 2: HTTP 101 Handshake via Raw Socket](#scenario-2-http-101-handshake-via-raw-socket)
     - [Scenario 3: Client Connection via `java.net.http.HttpClient`](#scenario-3-client-connection-via-javanethttphttpclient)
     - [Scenario 4: Text Frame Echo via `@OnMessage`](#scenario-4-text-frame-echo-via-onmessage)
     - [Scenario 5: Multi-Byte UTF-8 & International Text Framing](#scenario-5-multi-byte-utf-8--international-text-framing)
     - [Scenario 6: Multi-Client Broadcast & Concurrent Frame Writes](#scenario-6-multi-client-broadcast--concurrent-frame-writes)
     - [Scenario 7: Automatic Ping/Pong Inline Processing](#scenario-7-automatic-pingpong-inline-processing)
     - [Scenario 8: Client-Initiated Clean Close Handshake](#scenario-8-client-initiated-clean-close-handshake)
     - [Scenario 9: Server `stop()` Lifecycle & Zero Port Leaks](#scenario-9-server-stop-lifecycle--zero-port-leaks)
     - [Scenario 10: AutoCloseable Integration (`try-with-resources`)](#scenario-10-autocloseable-integration-try-with-resources)
     - [Scenario 11: Route Not Found (HTTP 404)](#scenario-11-route-not-found-http-404)
     - [Scenario 12: Unsupported Version (HTTP 426) & Malformed Handshake (HTTP 400)](#scenario-12-unsupported-version-http-426--malformed-handshake-http-400)
     - [Scenario 13: Protocol Violation (Unmasked Client Frame Close 1002)](#scenario-13-protocol-violation-unmasked-client-frame-close-1002)
3. [Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc)](#3-pedagogical-standard--bedrock-tutorial-javadoc)
   - [3.1 Pedagogical Tenets for Milestone 2](#31-pedagogical-tenets-for-milestone-2)
   - [3.2 Publication-Ready Javadoc: `BedrockWebSocketServer.java`](#32-publication-ready-javadoc-bedrockwebsocketserverjava)
   - [3.3 Publication-Ready Javadoc: `WebSocketClientHandler.java`](#33-publication-ready-javadoc-websocketclienthandlerjava)
4. [Verification Protocol & Handoff Readiness](#4-verification-protocol--handoff-readiness)

---

## 1. Executive Summary & Architectural Scope

Milestone 2 operationalizes the Bedrock RFC 6455 engine by binding it to Java NIO `ServerSocketChannel` and `SocketChannel` driven by Java 21 Project Loom Virtual Threads (`Thread.ofVirtual()`). 

This document provides:
1. **The Comprehensive Test Specification for `BedrockWebSocketServerTest.java`**:
   - Covers 13 distinct integration and protocol verification scenarios.
   - Dual-client testing strategy: utilizes both high-level `java.net.http.HttpClient` (Java 21 standard WebSocket client) and low-level `java.net.Socket` (for exact raw-byte wire verification and protocol violation testing).
   - Strict ephemeral port isolation (`port 0`), preventing port conflicts during parallel execution.
   - Strict concurrency testing verifying thread-safe `session.send()` and `sessionRegistry.broadcast()` under multi-threaded contention.
   - Resource leak detection asserting clean teardown of channels, threads, and socket ports.
2. **The Pedagogical Standard (🎓 BEDROCK TUTORIAL)**:
   - Exhaustive, deep-dive didactic documentation for `BedrockWebSocketServer` and `WebSocketClientHandler`.
   - Explains the mechanical physics of Loom carrier unmounting vs. Reactive event loops (Netty/WebFlux).
   - Explains the `SocketChannel.write` non-thread-safe invariant and why `ReentrantLock` is mandated over `synchronized` to eliminate carrier thread pinning.

---

## 2. Unit & Integration Test Plan (`BedrockWebSocketServerTest.java`)

### 2.1 Test Infrastructure & Ephemeral Port Strategy

#### The Port Collision Problem in Automated CI/CD
Hardcoded ports (e.g., `8080`, `9001`) fail in CI/CD environments where tests run concurrently across multiple workers, or when previous runs leave dangling zombie sockets in `TIME_WAIT`.

#### The Ephemeral Port Solution (`port 0`)
In Bedrock, passing `0` as the port to `BedrockWebSocketServer(0)` delegates port selection to the operating system TCP stack. The OS assigns an available, unprivileged ephemeral port (typically in the range 49152–65535).
- Upon binding: `int boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();`
- `server.getPort()` immediately returns this active port number.
- Tests query `server.getPort()` to target `ws://localhost:<port>/path`.
- In `@AfterEach`, tests call `server.stop()` or `server.close()`, guaranteeing 100% port release.

---

### 2.2 Test Endpoint Fixtures

To test `BedrockWebSocketServer` in isolation from Milestone 3's reflection-based `@BedrockSocket` annotation scanner, we create a lightweight test fixture implementing `WebSocketEndpointBinding`:

```java
/**
 * Test endpoint fixture capturing lifecycle callbacks and incoming messages.
 */
static class TestEchoEndpoint implements WebSocketEndpointBinding {
    final AtomicInteger openCount = new AtomicInteger(0);
    final AtomicInteger closeCount = new AtomicInteger(0);
    final AtomicInteger errorCount = new AtomicInteger(0);
    final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    final BlockingQueue<CloseEvent> closeEvents = new LinkedBlockingQueue<>();
    final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
    
    volatile BedrockWebSocketSession lastSession;
    volatile boolean echoBack = true;

    record CloseEvent(BedrockWebSocketSession session, int statusCode, String reason) {}

    @Override
    public void invokeOnOpen(BedrockWebSocketSession session) {
        this.lastSession = session;
        openCount.incrementAndGet();
    }

    @Override
    public void invokeOnMessage(BedrockWebSocketSession session, String message) {
        receivedMessages.offer(message);
        if (echoBack) {
            session.send(message); // Echo back to client
        }
    }

    @Override
    public void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) {
        closeCount.incrementAndGet();
        closeEvents.offer(new CloseEvent(session, statusCode, reason));
    }

    @Override
    public void invokeOnError(BedrockWebSocketSession session, Throwable throwable) {
        errorCount.incrementAndGet();
        errors.offer(throwable);
    }
}
```

---

### 2.3 Test Matrix & Execution Scenarios

| # | Test Method Name | Client Mechanism | Verification Focus |
|---|------------------|------------------|---------------------|
| 1 | `testServerStartupOnEphemeralPort` | Direct API | Server binds port 0, `getPort() > 0`, `isRunning() == true` |
| 2 | `testServerStartupFailureOnClosedServer` | Direct API | Cannot start an already closed server (`IllegalStateException`) |
| 3 | `testClientConnectionAndHttp101HandshakeViaRawSocket` | `java.net.Socket` | Verifies raw HTTP 101 status line, `Upgrade`, `Connection`, and `Sec-WebSocket-Accept` |
| 4 | `testClientConnectionAndHttp101HandshakeViaHttpClient` | `java.net.http.WebSocket` | Standard Java 21 WebSocket client connects and triggers server `@OnOpen` |
| 5 | `testTextMessageEchoViaOnMessage` | `java.net.http.WebSocket` | Full roundtrip: client sends text frame, server receives, invokes `@OnMessage`, client receives echo |
| 6 | `testMultiByteUtf8TextMessage` | `java.net.http.WebSocket` | Validates UTF-8 encoding across frames: Portuguese accents, emojis ("🦖 Olá Mundo 🚀") |
| 7 | `testConcurrentClientsSessionSendAndBroadcast` | Multi-Threaded Clients | Spawns 10 concurrent clients; tests registry size, `sessionRegistry.broadcast()`, and concurrent `session.send()` |
| 8 | `testAutomaticPingPongReplyViaRawSocket` | `java.net.Socket` | Sends masked Ping frame; asserts server replies with Pong frame echoing exact payload bytes |
| 9 | `testAutomaticPingPongReplyViaHttpClient` | `java.net.http.WebSocket` | Standard client sends Ping; `Listener.onPong` is triggered with identical payload |
| 10 | `testClientInitiatedCleanClose` | `java.net.http.WebSocket` | Client sends Close 1000; server echoes Close 1000, unregisters session, fires `@OnClose`, closes socket |
| 11 | `testServerStopCleanLifecycleAndPortRelease` | Direct API + Clients | `server.stop()` sends Close 1001 to all clients, unregisters all, and immediately frees port |
| 12 | `testAutoCloseableTryWithResources` | Direct API | `try-with-resources` closes server, releases port without leak |
| 13 | `testRouteNotFoundHttp404` | `java.net.Socket` | GET `/unknown` returns `HTTP/1.1 404 Not Found` and closes connection |
| 14 | `testUnsupportedWebSocketVersionHttp426` | `java.net.Socket` | `Sec-WebSocket-Version: 8` returns `HTTP/1.1 426 Upgrade Required` |
| 15 | `testMalformedHandshakeHttp400` | `java.net.Socket` | Missing `Sec-WebSocket-Key` returns `HTTP/1.1 400 Bad Request` |
| 16 | `testClientUnmaskedFrameProtocolError1002` | `java.net.Socket` | Client sends unmasked text frame; server replies with Close frame 1002 and closes socket |

---

### 2.4 Detailed Test Implementations

Here are the complete, publication-ready implementations for `BedrockWebSocketServerTest.java`.

#### Scenario 1: Ephemeral Port & Server Startup
```java
@Test
@DisplayName("Server starts on ephemeral port 0, resolves getPort() > 0, and reports isRunning()")
void testServerStartupOnEphemeralPort() throws IOException {
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        assertEquals(0, server.getPort(), "Configured port should initially be 0");
        assertFalse(server.isRunning(), "Server should not be running before start()");

        server.start();

        assertTrue(server.isRunning(), "Server must be running after start()");
        int assignedPort = server.getPort();
        assertTrue(assignedPort > 0 && assignedPort <= 65535,
                "Ephemeral port must be allocated by the OS in valid port range: " + assignedPort);
        
        // Calling start() again when running should be safe / idempotent
        assertDoesNotThrow(server::start, "Subsequent start() call on running server should be idempotent");
    }
}
```

#### Scenario 2: HTTP 101 Handshake via Raw Socket
```java
@Test
@DisplayName("Raw TCP client connects and negotiates compliant RFC 6455 HTTP 101 Switching Protocols")
void testClientConnectionAndHttp101HandshakeViaRawSocket() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/chat", endpoint);
        server.start();
        int port = server.getPort();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
            String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

            String handshakeRequest = 
                    "GET /chat HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + port + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + clientKey + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n";

            out.write(handshakeRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read response headers
            ByteArrayOutputStream respBaos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            String responseString = "";
            while ((n = in.read(buf)) != -1) {
                respBaos.write(buf, 0, n);
                responseString = respBaos.toString(StandardCharsets.US_ASCII);
                if (responseString.contains("\r\n\r\n")) {
                    break;
                }
            }

            assertTrue(responseString.startsWith("HTTP/1.1 101 Switching Protocols\r\n"),
                    "Response status line must be HTTP/1.1 101 Switching Protocols");
            assertTrue(responseString.toLowerCase().contains("upgrade: websocket"),
                    "Response must contain 'Upgrade: websocket'");
            assertTrue(responseString.toLowerCase().contains("connection: upgrade"),
                    "Response must contain 'Connection: Upgrade'");
            assertTrue(responseString.contains("Sec-WebSocket-Accept: " + expectedAccept),
                    "Response must contain mathematically exact Sec-WebSocket-Accept header");
        }
    }
}
```

#### Scenario 3: Client Connection via `java.net.http.HttpClient`
```java
@Test
@DisplayName("Standard Java 21 HttpClient connects successfully to Bedrock WebSocket endpoint")
void testClientConnectionAndHttp101HandshakeViaHttpClient() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/chat", endpoint);
        server.start();

        CountDownLatch openLatch = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();

        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/chat"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        openLatch.countDown();
                        webSocket.request(1);
                    }
                });

        WebSocket ws = wsFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(ws, "WebSocket future must complete with active client instance");
        assertTrue(openLatch.await(3, TimeUnit.SECONDS), "Client Listener.onOpen must be triggered");

        // Wait for server-side endpoint onOpen invocation
        assertEquals(1, endpoint.openCount.get(), "Server endpoint invokeOnOpen must be called exactly once");
        assertNotNull(endpoint.lastSession, "Server session must be created");
        assertTrue(endpoint.lastSession.isOpen(), "Server session must be marked as open");
        assertEquals("/chat", endpoint.lastSession.getPath(), "Session path must match registered route");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Test done").get(3, TimeUnit.SECONDS);
    }
}
```

#### Scenario 4: Text Frame Echo via `@OnMessage`
```java
@Test
@DisplayName("Text frame transmission roundtrip: Client sends text, server invokes @OnMessage, client receives echo")
void testTextMessageEchoViaOnMessage() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/echo", endpoint);
        server.start();

        BlockingQueue<String> clientReceived = new LinkedBlockingQueue<>();
        HttpClient client = HttpClient.newHttpClient();

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/echo"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        clientReceived.offer(data.toString());
                        webSocket.request(1);
                        return null;
                    }
                }).get(3, TimeUnit.SECONDS);

        String testPayload = "Hello, Bedrock Virtual Threads!";
        ws.sendText(testPayload, true).get(3, TimeUnit.SECONDS);

        // Verify server received message
        String serverReceivedMsg = endpoint.receivedMessages.poll(3, TimeUnit.SECONDS);
        assertEquals(testPayload, serverReceivedMsg, "Server endpoint must receive exact unmasked payload text");

        // Verify client received echo reply
        String clientReceivedMsg = clientReceived.poll(3, TimeUnit.SECONDS);
        assertEquals(testPayload, clientReceivedMsg, "Client must receive exact echo text from server");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye").get(3, TimeUnit.SECONDS);
    }
}
```

#### Scenario 5: Multi-Byte UTF-8 & International Text Framing
```java
@Test
@DisplayName("Text frame transmission handles multi-byte UTF-8, accents, and emojis correctly")
void testMultiByteUtf8TextMessage() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/utf8", endpoint);
        server.start();

        BlockingQueue<String> clientReceived = new LinkedBlockingQueue<>();
        HttpClient client = HttpClient.newHttpClient();

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/utf8"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        clientReceived.offer(data.toString());
                        webSocket.request(1);
                        return null;
                    }
                }).get(3, TimeUnit.SECONDS);

        String complexText = "🦖 Bedrock Framework: Olá Mundo! João comprou maçãs & café na estação. 🚀✨ (日本語・中文・한국어)";
        ws.sendText(complexText, true).get(3, TimeUnit.SECONDS);

        String received = clientReceived.poll(3, TimeUnit.SECONDS);
        assertEquals(complexText, received, "Multi-byte UTF-8 characters and emojis must survive framing intact");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Close").get(3, TimeUnit.SECONDS);
    }
}
```

#### Scenario 6: Multi-Client Broadcast & Concurrent Frame Writes
```java
@Test
@DisplayName("SessionRegistry broadcast fans out messages to 10 concurrent clients without frame corruption")
void testConcurrentClientsSessionSendAndBroadcast() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    endpoint.echoBack = false; // Disable automatic echo; test explicit broadcast

    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/broadcast-room", endpoint);
        server.start();

        int clientCount = 10;
        List<WebSocket> clients = new CopyOnWriteArrayList<>();
        List<BlockingQueue<String>> clientQueues = new ArrayList<>();
        HttpClient httpClient = HttpClient.newHttpClient();
        CountDownLatch connectedLatch = new CountDownLatch(clientCount);

        for (int i = 0; i < clientCount; i++) {
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            clientQueues.add(queue);

            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/broadcast-room"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            connectedLatch.countDown();
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            queue.offer(data.toString());
                            return null;
                        }
                    }).get(3, TimeUnit.SECONDS);

            clients.add(ws);
        }

        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "All 10 clients must connect");
        
        // Assert registry tracks all 10 clients
        WebSocketSessionRegistry registry = server.getSessionRegistry();
        assertEquals(clientCount, registry.getAll().size(), "SessionRegistry must contain all 10 active sessions");

        // Execute Broadcast from server
        String broadcastMessage = "🚨 System Announcement: Broadcast test active across all Virtual Threads!";
        registry.broadcast(broadcastMessage);

        // Verify every client received the broadcast message
        for (int i = 0; i < clientCount; i++) {
            String received = clientQueues.get(i).poll(3, TimeUnit.SECONDS);
            assertEquals(broadcastMessage, received, "Client #" + i + " must receive broadcast message");
        }

        // Test concurrent session.send() to verify writeLock safety
        BedrockWebSocketSession session0 = registry.getAll().iterator().next();
        int concurrentWrites = 50;
        CountDownLatch writeLatch = new CountDownLatch(concurrentWrites);

        for (int i = 0; i < concurrentWrites; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    session0.send("ConcurrentMsg-" + index);
                } finally {
                    writeLatch.countDown();
                }
            });
        }

        assertTrue(writeLatch.await(5, TimeUnit.SECONDS), "All concurrent writes must finish");

        // Clean up clients
        for (WebSocket ws : clients) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown");
        }
    }
}
```

#### Scenario 7: Automatic Ping/Pong Inline Processing
```java
@Test
@DisplayName("Server automatically replies with Pong control frame echoing exact Ping payload bytes")
void testAutomaticPingPongReplyViaRawSocket() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/ping", endpoint);
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Perform handshake
            String handshake = "GET /ping HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Discard HTTP 101 response header
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                baos.write(b);
                if (baos.toString(StandardCharsets.US_ASCII).contains("\r\n\r\n")) {
                    break;
                }
            }

            // Send Masked Ping Frame: Opcode 0x9, Payload "TESTPING" (8 bytes)
            byte[] pingPayload = "TESTPING".getBytes(StandardCharsets.UTF_8);
            byte[] maskKey = new byte[]{(byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44};
            byte[] maskedPing = WebSocketFrameWriter.createMaskedFrame(
                    WebSocketOpcode.PING, pingPayload, true, maskKey
            );

            out.write(maskedPing);
            out.flush();

            // Read Server Pong Response Frame: Expected Opcode 0xA, Unmasked, exact payload
            byte[] header = in.readNBytes(2);
            int b0 = header[0] & 0xFF;
            int b1 = header[1] & 0xFF;

            assertEquals(0x8A, b0, "Pong frame must have FIN=1 and Opcode=0xA (0x8A)");
            assertEquals(0x08, b1, "Pong frame from server must be unmasked (bit 7 = 0) with length 8");

            byte[] receivedPongPayload = in.readNBytes(8);
            assertArrayEquals(pingPayload, receivedPongPayload, "Pong payload must identically match Ping payload");
        }
    }
}
```

#### Scenario 8: Client-Initiated Clean Close Handshake
```java
@Test
@DisplayName("Client-initiated Close: Server echoes Close frame, unregisters session, and fires @OnClose")
void testClientInitiatedCleanClose() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/close-test", endpoint);
        server.start();

        HttpClient client = HttpClient.newHttpClient();
        CountDownLatch serverCloseLatch = new CountDownLatch(1);

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/close-test"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }
                }).get(3, TimeUnit.SECONDS);

        assertEquals(1, server.getSessionRegistry().getAll().size(), "Session must be registered");

        // Client initiates close with code 1000 and reason "Test client closing"
        CompletableFuture<WebSocket> closeFuture = ws.sendClose(WebSocket.NORMAL_CLOSURE, "Test client closing");
        closeFuture.get(3, TimeUnit.SECONDS);

        // Wait for server endpoint onclose invocation
        TestEchoEndpoint.CloseEvent event = endpoint.closeEvents.poll(3, TimeUnit.SECONDS);
        assertNotNull(event, "Server endpoint invokeOnClose must be triggered");
        assertEquals(1000, event.statusCode(), "Close code must be 1000 (NORMAL_CLOSURE)");
        assertEquals("Test client closing", event.reason(), "Close reason must match client string");

        // Verify session is unregistered and closed
        assertEquals(0, server.getSessionRegistry().getAll().size(), "Session must be removed from registry");
        assertFalse(event.session().isOpen(), "Session isOpen() must return false");
    }
}
```

#### Scenario 9: Server `stop()` Lifecycle & Zero Port Leaks
```java
@Test
@DisplayName("Server stop() notifies clients with 1001 Going Away, shuts down virtual threads, and frees port")
void testServerStopCleanLifecycleAndPortRelease() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    BedrockWebSocketServer server = new BedrockWebSocketServer(0);
    server.registerEndpoint("/lifecycle", endpoint);
    server.start();

    int port = server.getPort();
    CountDownLatch clientCloseLatch = new CountDownLatch(1);
    AtomicInteger clientCloseCode = new AtomicInteger(0);
    AtomicReference<String> clientCloseReason = new AtomicReference<>("");

    HttpClient client = HttpClient.newHttpClient();
    WebSocket ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/lifecycle"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    clientCloseCode.set(statusCode);
                    clientCloseReason.set(reason);
                    clientCloseLatch.countDown();
                    return null;
                }
            }).get(3, TimeUnit.SECONDS);

    assertEquals(1, server.getSessionRegistry().getAll().size(), "Active session registered");

    // Execute server stop()
    server.stop();

    assertFalse(server.isRunning(), "Server must report isRunning() == false");
    assertEquals(0, server.getSessionRegistry().getAll().size(), "All sessions must be cleared");

    // Verify client received 1001 Going Away
    assertTrue(clientCloseLatch.await(3, TimeUnit.SECONDS), "Client must receive close notification from server");
    assertEquals(1001, clientCloseCode.get(), "Server stop must send status code 1001 (GOING_AWAY)");
    assertTrue(clientCloseReason.get().contains("Server shutting down"),
            "Reason must indicate server shutdown: " + clientCloseReason.get());

    // Verify Port is completely released and can be bound immediately by a new ServerSocketChannel
    try (ServerSocketChannel testBind = ServerSocketChannel.open()) {
        testBind.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        assertDoesNotThrow(() -> testBind.bind(new InetSocketAddress("127.0.0.1", port)),
                "Port " + port + " must be immediately available for re-binding (no port leak)");
    }
}
```

#### Scenario 10: AutoCloseable Integration (`try-with-resources`)
```java
@Test
@DisplayName("BedrockWebSocketServer implements AutoCloseable and safely stops inside try-with-resources")
void testAutoCloseableTryWithResources() throws Exception {
    int port;
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.start();
        port = server.getPort();
        assertTrue(server.isRunning());
    } // server.close() is automatically called here

    // Verify port was released
    try (ServerSocketChannel checkChannel = ServerSocketChannel.open()) {
        checkChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        assertDoesNotThrow(() -> checkChannel.bind(new InetSocketAddress("127.0.0.1", port)),
                "Port must be freed after try-with-resources block exits");
    }
}
```

#### Scenario 11: Route Not Found (HTTP 404)
```java
@Test
@DisplayName("Handshake targeting unregistered route returns HTTP 404 Not Found and closes socket")
void testRouteNotFoundHttp404() throws Exception {
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/valid-route", new TestEchoEndpoint());
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String handshake = "GET /unregistered-route HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n";

            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String response = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 404 Not Found\r\n"),
                    "Unmapped path must receive HTTP 404: " + response);
        }
    }
}
```

#### Scenario 12: Unsupported Version (HTTP 426) & Malformed Handshake (HTTP 400)
```java
@Test
@DisplayName("Handshake with unsupported version returns HTTP 426 Upgrade Required with Sec-WebSocket-Version: 13")
void testUnsupportedWebSocketVersionHttp426() throws Exception {
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/chat", new TestEchoEndpoint());
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String handshake = "GET /chat HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 8\r\n\r\n"; // Version 8 is rejected

            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String response = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 426 Upgrade Required\r\n"),
                    "Unsupported version must receive HTTP 426: " + response);
            assertTrue(response.contains("Sec-WebSocket-Version: 13"),
                    "Response must specify supported Sec-WebSocket-Version: 13");
        }
    }
}

@Test
@DisplayName("Handshake missing Sec-WebSocket-Key returns HTTP 400 Bad Request")
void testMalformedHandshakeHttp400() throws Exception {
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/chat", new TestEchoEndpoint());
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String handshake = "GET /chat HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    // Missing Sec-WebSocket-Key!
                    "Sec-WebSocket-Version: 13\r\n\r\n";

            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String response = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"),
                    "Missing key must receive HTTP 400: " + response);
        }
    }
}
```

#### Scenario 13: Protocol Violation (Unmasked Client Frame Close 1002)
```java
@Test
@DisplayName("RFC 6455 §5.1 violation: Unmasked client frame causes immediate Close 1002 Protocol Error")
void testClientUnmaskedFrameProtocolError1002() throws Exception {
    TestEchoEndpoint endpoint = new TestEchoEndpoint();
    try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
        server.registerEndpoint("/strict", endpoint);
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Handshake
            String handshake = "GET /strict HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Discard HTTP 101 header
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                baos.write(b);
                if (baos.toString(StandardCharsets.US_ASCII).contains("\r\n\r\n")) {
                    break;
                }
            }

            // Send Illegal UNMASKED Text Frame: FIN=1, Opcode=1, MASK=0, Len=5, "Hello"
            byte[] illegalUnmasked = new byte[]{
                    (byte) 0x81, (byte) 0x05,
                    (byte) 'H', (byte) 'e', (byte) 'l', (byte) 'l', (byte) 'o'
            };
            out.write(illegalUnmasked);
            out.flush();

            // Server must reply with Close Frame (Opcode 0x8) and status code 1002 (Protocol Error)
            byte[] closeHeader = in.readNBytes(2);
            int b0 = closeHeader[0] & 0xFF;
            int b1 = closeHeader[1] & 0xFF;

            assertEquals(0x88, b0, "Must receive Close frame (Opcode 0x8, FIN=1)");
            assertTrue(b1 >= 2, "Close frame payload must contain at least 2-byte status code");

            byte[] closePayload = in.readNBytes(b1);
            int statusCode = ((closePayload[0] & 0xFF) << 8) | (closePayload[1] & 0xFF);
            assertEquals(1002, statusCode, "Status code must be 1002 (PROTOCOL_ERROR)");

            // Socket should be closed by server immediately after
            int eof = in.read();
            assertEquals(-1, eof, "Server must close TCP connection after sending protocol error Close frame");
        }
    }
}
```

---

## 3. Pedagogical Standard (🎓 BEDROCK TUTORIAL Javadoc)

### 3.1 Pedagogical Tenets for Milestone 2

In enterprise Java, developers frequently believe that WebSockets require massive third-party infrastructure (Netty, Spring Reactive, Project Reactor). The Bedrock Javadoc tutorials demonstrate the fundamental JVM network physics:
1. **Loom Virtual Threads (`Thread.ofVirtual()`) eliminate reactive complexity**: Developers write direct, imperative, synchronous code with normal `try/catch` and sequential loops, while the JVM carrier pool yields non-blocking scalability.
2. **Carrier Thread Unmounting Mechanics**: Demystifies how `SocketChannel.read()` interacts with the JVM's `Continuation.yield()` and OS epoll/kqueue.
3. **Thread Safety & Frame Serialization**: Explains the raw race conditions of TCP socket writing and why `ReentrantLock` is strictly required to prevent carrier thread pinning.

---

### 3.2 Publication-Ready Javadoc: `BedrockWebSocketServer.java`

```java
/**
 * 🎓 BEDROCK TUTORIAL: The Physics of Virtual-Threaded Network Servers & Project Loom
 *
 * <p>In standard enterprise Java frameworks (such as Spring WebFlux or Quarkus), real-time
 * WebSocket servers rely heavily on Netty, event loops, reactive streams, and complex callback
 * chains. Developers are told that blocking I/O is "slow" and that non-blocking reactive
 * programming is the only way to handle 50,000+ concurrent connections.</p>
 *
 * <p>In Bedrock, we reject this false dichotomy. With <b>Java 21 Project Loom (Virtual Threads)</b>,
 * the JVM reconciles synchronous, imperative programming with ultra-high concurrency.</p>
 *
 * <h3>1. Why Virtual Threads Eliminate Reactive Frameworks:</h3>
 * <p>Historically, every Java thread was an OS-level kernel thread (a <i>pthread</i>).
 * OS threads are heavy: each reserves ~1 MB of off-heap C-stack memory. If a server attempted
 * to allocate 20,000 idle WebSocket connections on 20,000 OS threads, the JVM would consume
 * 20 GB of memory solely for thread stacks, triggering:
 * <pre>{@code java.lang.OutOfMemoryError: unable to create native thread}</pre>
 * </p>
 * <p>To avoid OS thread exhaustion, reactive frameworks (Netty) introduced the <b>Event Loop</b>:
 * a tiny pool of OS worker threads multiplexing thousands of non-blocking channels via {@code Selector}.
 * However, this introduced severe software engineering costs:
 * <ul>
 *   <li><b>Inverted Control Flow</b>: Logic is fragmented across callbacks, {@code Mono}, and {@code Flux}.</li>
 *   <li><b>Broken Stack Traces</b>: When an exception occurs, the call stack contains hundreds of
 *       framework trampoline frames without pointing to the business line of code that failed.</li>
 *   <li><b>Context Loss</b>: {@link ThreadLocal} storage (tracing, security, MDC logging) does not
 *       propagate across asynchronous event loop boundaries without cumbersome context wrappers.</li>
 * </ul>
 * </p>
 * <p><b>The Loom Paradigm in Bedrock</b>:
 * In {@link BedrockWebSocketServer}, every accepted client connection is granted its own dedicated
 * Virtual Thread:
 * <pre>{@code
 *   Thread.ofVirtual().name("ws-client-" + clientId).start(...)
 * }</pre>
 * Virtual threads are lightweight user-mode tasks managed entirely by the JVM runtime.
 * Their initial memory footprint is a mere ~300 bytes on the Java garbage-collected heap!
 * The JVM dynamically schedules millions of virtual threads onto a tiny pool of OS <i>carrier threads</i>
 * (matching the number of available CPU cores).
 * Developers write clean, readable, linear code with standard {@code try/catch} blocks,
 * preserving natural stack traces and zero third-party dependencies.
 * </p>
 *
 * <h3>2. Ephemeral Port Allocation & Test Isolation:</h3>
 * <p>In automated testing, hardcoded port numbers (such as 8080 or 9001) inevitably cause
 * flaky test failures when tests run concurrently or when previous runs leave zombie sockets in
 * OS {@code TIME_WAIT} state.</p>
 * <p>
 * By instantiating {@code new BedrockWebSocketServer(0)}, the port number {@code 0} instructs
 * the operating system TCP stack to dynamically allocate an unused ephemeral port.
 * The bound port can then be discovered via {@link #getPort()}:
 * <pre>{@code
 *   int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
 * }</pre>
 * This guarantees 100% collision-free, parallel test execution in CI/CD pipelines.
 * </p>
 *
 * <h3>3. Server Lifecycle & Zero Port Leaks:</h3>
 * <p>A production-grade server must guarantee clean teardown without resource leaks.
 * {@link BedrockWebSocketServer} implements {@link AutoCloseable}. When {@link #stop()} or
 * {@link #close()} is invoked:
 * <ol>
 *   <li>The listening {@link java.nio.channels.ServerSocketChannel} is closed, instantly unblocking
 *       the accept loop virtual thread with a {@link java.nio.channels.ClosedChannelException}.</li>
 *   <li>The accept loop terminates cleanly and joins its virtual thread.</li>
 *   <li>All active sessions in {@link WebSocketSessionRegistry} receive an RFC 6455 Close frame
 *       with status code {@code 1001 (Going Away)}, their channels are closed, and the local port
 *       is immediately released back to the operating system.</li>
 * </ol>
 * </p>
 *
 * @see WebSocketClientHandler
 * @see BedrockWebSocketSession
 * @see WebSocketSessionRegistry
 */
```

---

### 3.3 Publication-Ready Javadoc: `WebSocketClientHandler.java`

```java
/**
 * 🎓 BEDROCK TUTORIAL: The Mechanics of Carrier Unmounting & Frame Dispatching
 *
 * <p>This class manages the complete physical lifecycle of an individual client connection:
 * from the initial HTTP 101 upgrade handshake to full-duplex RFC 6455 binary framing.</p>
 *
 * <h3>1. The Two-Phase Connection Lifecycle:</h3>
 * <ul>
 *   <li><b>Phase 1: HTTP Upgrade Handshake</b>:
 *     <p>The socket begins life as an ordinary HTTP/1.1 TCP stream. Incoming bytes are read into
 *     an NIO {@link java.nio.ByteBuffer} and evaluated by {@link com.bedrock.core.ws.protocol.WebSocketHandshake}.
 *     If the request line, {@code Upgrade: websocket}, {@code Connection: Upgrade}, and
 *     {@code Sec-WebSocket-Version: 13} headers are valid, the server computes the SHA-1 Base64
 *     {@code Sec-WebSocket-Accept} token and transmits the HTTP 101 Switching Protocols response.</p>
 *     <p>Crucially, before switching to Phase 2, any remaining bytes in the buffer (pipelined
 *     frames sent by an eager client) are preserved via {@link java.nio.ByteBuffer#compact()}.</p>
 *   </li>
 *   <li><b>Phase 2: Full-Duplex RFC 6455 Frame Loop</b>:
 *     <p>The HTTP parser is permanently discarded. The connection enters an infinite synchronous
 *     read loop, parsing discrete binary frames, performing in-place 4-byte XOR unmasking,
 *     and dispatching opcodes to endpoint bindings.</p>
 *   </li>
 * </ul>
 *
 * <h3>2. The Physics of Carrier Thread Unmounting:</h3>
 * <p>How can a synchronous blocking call like:
 * <pre>{@code
 *   int bytesRead = channel.read(buffer); // Blocks waiting for network bytes
 * }</pre>
 * scale to 100,000 idle WebSocket clients without consuming 100,000 OS threads?</p>
 *
 * <p>Here is what physically occurs within the JVM runtime:
 * <ol>
 *   <li>When {@code channel.read(buffer)} is invoked inside a Virtual Thread, the JVM standard library
 *       checks if the socket has bytes immediately available in its OS TCP receive buffer.</li>
 *   <li>If no bytes are ready, the JVM does <b>NOT</b> suspend the underlying OS kernel thread.</li>
 *   <li>Instead, the JVM executes a <i>Continuation Yield</i>: the virtual thread's call stack,
 *       local variables, and instruction pointer are preserved as a compact object on the Java heap.</li>
 *   <li>The virtual thread <b>unmounts</b> from its OS carrier thread. The carrier thread (an OS thread
 *       in the carrier {@code ForkJoinPool}) is now completely free to execute other virtual threads!</li>
 *   <li>The JVM registers the socket's underlying file descriptor with an internal multiplexer
 *       (e.g., {@code epoll} on Linux, {@code kqueue} on macOS, or {@code IOCP} on Windows).</li>
 *   <li>When incoming TCP packets arrive on the physical network interface card, the OS network
 *       stack notifies the JVM multiplexer.</li>
 *   <li>The JVM wakes the continuation, selects an available carrier thread from the pool,
 *       <b>mounts</b> the virtual thread back onto the carrier, and resumes execution smoothly
 *       at the instruction following {@code channel.read()}!</li>
 * </ol>
 * To the Java developer, the code appears synchronous and simple. But to the operating system,
 * it achieves maximum event-driven, non-blocking efficiency with zero reactive framework baggage.
 * </p>
 *
 * <h3>3. The Thread Safety Asymmetry: Why SocketChannel Writes Require ReentrantLock:</h3>
 * <p>In Java NIO, {@link java.nio.channels.SocketChannel#read(java.nio.ByteBuffer)} and
 * {@link java.nio.channels.SocketChannel#write(java.nio.ByteBuffer)} are NOT thread-safe for
 * concurrent multi-threaded writes.</p>
 * <ul>
 *   <li><b>The Read Path is Single-Threaded</b>: Only the dedicated virtual thread assigned
 *       to this connection ever calls {@code read()}. Therefore, reads require no locking.</li>
 *   <li><b>The Write Path is Inherently Multi-Threaded</b>:
 *     <ul>
 *       <li>The connection's own virtual thread may write a Pong frame in response to an incoming Ping.</li>
 *       <li>An endpoint method may call {@code session.send("Hello")} from a worker thread.</li>
 *       <li>A background timer or another client's virtual thread may execute
 *           {@code sessionRegistry.broadcast("Alert")} to all active sessions simultaneously.</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * If two threads simultaneously write to the same {@link java.nio.channels.SocketChannel},
 * their raw frame bytes will interleave on the wire (e.g. half of a Text frame mixed with half of
 * a Pong frame). The client will fail to parse the corrupt header and terminate the connection
 * with RFC 6455 status code {@code 1002 (Protocol Error)}.
 * </p>
 * <p><b>Carrier Pinning Elimination</b>:
 * In Java 21, blocking inside a {@code synchronized} block pins the virtual thread to its OS
 * carrier thread, degrading scalability. To prevent pinning, Bedrock synchronizes frame writes
 * using {@link java.util.concurrent.locks.ReentrantLock}. When a virtual thread waits for a
 * {@code ReentrantLock}, it yields its carrier thread cleanly without pinning!
 * </p>
 *
 * <h3>4. RFC 6455 Control Frame Invariants:</h3>
 * <ul>
 *   <li><b>Ping (Opcode 0x9)</b>: The server MUST immediately reply with a Pong frame (Opcode 0xA)
 *       echoing the exact application data received in the Ping frame (RFC 6455 §5.5.2).</li>
 *   <li><b>Close (Opcode 0x8)</b>: Clean two-way handshake. If the client initiates closure,
 *       the server echoes the Close frame, unregisters the session from the registry, fires
 *       {@code @OnClose}, and terminates the underlying TCP channel (RFC 6455 §5.5.1 & §7.1).</li>
 *   <li><b>Mandatory Client Masking (RFC 6455 §5.1)</b>: If a client transmits an unmasked frame,
 *       the server terminates the connection immediately with status code {@code 1002 (Protocol Error)}.</li>
 * </ul>
 *
 * @see BedrockWebSocketServer
 * @see BedrockWebSocketSession
 * @see com.bedrock.core.ws.protocol.WebSocketFrameParser
 * @see com.bedrock.core.ws.protocol.WebSocketFrameWriter
 */
```

---

## 4. Verification Protocol & Handoff Readiness

### 4.1 Automated Verification Strategy
To independently verify this design during implementation (Milestone 2 implementation phase):
1. **Compilation & Packaging**:
   ```bash
   mvn clean test-compile
   ```
2. **Execute Unit & Integration Suite**:
   ```bash
   mvn test -Dtest=BedrockWebSocketServerTest
   ```
3. **Full Regression Check (Guarantee Zero Regressions)**:
   ```bash
   mvn test
   ```
   All 81 baseline framework tests plus all 16 new WebSocket server tests must pass with 100% success.
4. **Javadoc Verification (Zero Javadoc Warnings)**:
   ```bash
   mvn javadoc:javadoc
   ```
   Verifies that `@see`, code snippets, and tutorial annotations pass the `maven-javadoc-plugin` cleanly.

### 4.2 Handoff Alignment with Peer Agents
- **To `explorer_m2_1`**: Confirms that `BedrockWebSocketServer` and `WebSocketClientHandler` APIs (e.g. `getPort()`, `registerEndpoint()`, `stop()`, `dispatch()`) directly satisfy every test assertion.
- **To `explorer_m2_2`**: Confirms that `BedrockWebSocketSession` (`writeLock`, `send()`, `isOpen()`, `close()`) and `WebSocketSessionRegistry` (`broadcast()`, `closeAll()`) satisfy the concurrent multi-client and teardown test fixtures.
- **To Implementer / Worker**: The concrete code in Section 2 can be placed directly into `bedrock-core/src/test/java/com/bedrock/core/ws/server/BedrockWebSocketServerTest.java`.
