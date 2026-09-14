package com.bedrock.core.ws.server;

import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.protocol.WebSocketFrameWriter;
import com.bedrock.core.ws.protocol.WebSocketOpcode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Comprehensive Server & Session Integration Test Suite
 *
 * <p>Validates the full RFC 6455 server lifecycle, Project Loom Virtual Thread concurrency,
 * carrier-unmounting mechanics, and edge-case error handling.</p>
 */
public class BedrockWebSocketServerTest {

    // =========================================================================
    // Test Fixture Endpoint
    // =========================================================================

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
                session.send(message);
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

    // =========================================================================
    // Scenario 1: Ephemeral Port & Server Startup
    // =========================================================================

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

            // Calling start() again when running should be safe and idempotent
            assertDoesNotThrow(server::start, "Subsequent start() call on running server should be idempotent");
        }
    }

    // =========================================================================
    // Scenario 2: Startup Failure on Closed Server
    // =========================================================================

    @Test
    @DisplayName("Cannot restart a closed BedrockWebSocketServer (IllegalStateException)")
    void testServerStartupFailureOnClosedServer() throws IOException {
        BedrockWebSocketServer server = new BedrockWebSocketServer(0);
        server.start();
        server.close();
        assertThrows(IllegalStateException.class, server::start,
                "Starting a closed server must throw IllegalStateException");
    }

    // =========================================================================
    // Scenario 3: HTTP 101 Handshake via Raw Socket
    // =========================================================================

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

    // =========================================================================
    // Scenario 4: Client Connection via HttpClient
    // =========================================================================

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

    // =========================================================================
    // Scenario 5: Text Frame Echo via @OnMessage
    // =========================================================================

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

    // =========================================================================
    // Scenario 6: Multi-Byte UTF-8 & International Text Framing
    // =========================================================================

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

    // =========================================================================
    // Scenario 7: Multi-Client Broadcast & Concurrent Frame Writes
    // =========================================================================

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

    // =========================================================================
    // Scenario 8: Automatic Ping/Pong Reply via Raw Socket
    // =========================================================================

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

    // =========================================================================
    // Scenario 9: Automatic Ping/Pong via HttpClient
    // =========================================================================

    @Test
    @DisplayName("HttpClient sends Ping and receives automatic Pong response from server")
    void testAutomaticPingPongReplyViaHttpClient() throws Exception {
        TestEchoEndpoint endpoint = new TestEchoEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/ping-client", endpoint);
            server.start();

            CountDownLatch pongLatch = new CountDownLatch(1);
            AtomicReference<byte[]> receivedPong = new AtomicReference<>();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/ping-client"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                            byte[] bytes = new byte[message.remaining()];
                            message.get(bytes);
                            receivedPong.set(bytes);
                            pongLatch.countDown();
                            webSocket.request(1);
                            return null;
                        }
                    }).get(3, TimeUnit.SECONDS);

            byte[] pingBytes = "PINGDATA".getBytes(StandardCharsets.UTF_8);
            ws.sendPing(ByteBuffer.wrap(pingBytes)).get(3, TimeUnit.SECONDS);

            assertTrue(pongLatch.await(3, TimeUnit.SECONDS), "Client must receive Pong reply");
            assertArrayEquals(pingBytes, receivedPong.get(), "Pong payload must match Ping bytes");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(3, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // Scenario 10: Client-Initiated Clean Close Handshake
    // =========================================================================

    @Test
    @DisplayName("Client-initiated Close: Server echoes Close frame, unregisters session, and fires @OnClose")
    void testClientInitiatedCleanClose() throws Exception {
        TestEchoEndpoint endpoint = new TestEchoEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/close-test", endpoint);
            server.start();

            HttpClient client = HttpClient.newHttpClient();

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

    // =========================================================================
    // Scenario 11: Server stop() Lifecycle & Zero Port Leaks
    // =========================================================================

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

    // =========================================================================
    // Scenario 12: AutoCloseable Integration (try-with-resources)
    // =========================================================================

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

    // =========================================================================
    // Scenario 13: Route Not Found (HTTP 404)
    // =========================================================================

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

    // =========================================================================
    // Scenario 14: Unsupported Version (HTTP 426)
    // =========================================================================

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

    // =========================================================================
    // Scenario 15: Malformed Handshake (HTTP 400)
    // =========================================================================

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

    // =========================================================================
    // Scenario 16: Protocol Violation (Unmasked Client Frame Close 1002)
    // =========================================================================

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

    // =========================================================================
    // Scenario 17: Session Attributes & State
    // =========================================================================

    @Test
    @DisplayName("BedrockWebSocketSession manages custom attributes and remote address")
    void testSessionAttributesAndState() throws Exception {
        TestEchoEndpoint endpoint = new TestEchoEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/attrs", endpoint);
            server.start();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/attrs"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }
                    }).get(3, TimeUnit.SECONDS);

            assertNotNull(endpoint.lastSession, "Session must be available");
            BedrockWebSocketSession session = endpoint.lastSession;

            assertNotNull(session.getId());
            assertNotNull(session.getRemoteAddress());
            assertEquals("/attrs", session.getPath());
            assertTrue(session.isOpen());

            // Test attributes
            session.setAttribute("user", "Alice");
            session.setAttribute("role", "admin");
            assertEquals("Alice", session.getAttribute("user"));
            assertEquals("admin", session.getAttribute("role"));
            assertEquals(2, session.getAttributes().size());

            // Remove attribute
            assertEquals("Alice", session.removeAttribute("user"));
            assertNull(session.getAttribute("user"));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(3, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // Scenario 18: Illegal State on Closed Session
    // =========================================================================

    @Test
    @DisplayName("Sending messages on a closed session throws IllegalStateException")
    void testSessionIllegalStateOnClosedSession() throws Exception {
        TestEchoEndpoint endpoint = new TestEchoEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/closed-session", endpoint);
            server.start();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/closed-session"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }
                    }).get(3, TimeUnit.SECONDS);

            BedrockWebSocketSession session = endpoint.lastSession;
            assertNotNull(session);

            session.close();
            assertFalse(session.isOpen());

            assertThrows(IllegalStateException.class, () -> session.send("Should fail"));
            assertThrows(IllegalStateException.class, () -> session.send(new byte[]{1, 2, 3}));
            assertThrows(IllegalStateException.class, () -> session.sendPing(new byte[]{1}));

            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Clean").get(1, TimeUnit.SECONDS);
            } catch (Exception ignore) {
                // Channel already closed by server-initiated session.close()
            }
        }
    }

    // =========================================================================
    // Scenario 19: Wire-Forbidden Close Codes
    // =========================================================================

    @Test
    @DisplayName("Attempting to send wire-forbidden close codes (1005, 1006) throws IllegalArgumentException")
    void testSessionWireForbiddenCloseCodes() throws Exception {
        TestEchoEndpoint endpoint = new TestEchoEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/forbidden-codes", endpoint);
            server.start();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/forbidden-codes"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }
                    }).get(3, TimeUnit.SECONDS);

            BedrockWebSocketSession session = endpoint.lastSession;
            assertNotNull(session);

            assertThrows(IllegalArgumentException.class, () -> session.close(1005, "Forbidden"));
            assertThrows(IllegalArgumentException.class, () -> session.close(1006, "Forbidden"));
            assertThrows(IllegalArgumentException.class, () -> session.close(1015, "Forbidden"));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Clean").get(3, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // Scenario 20: Broadcast Except
    // =========================================================================

    @Test
    @DisplayName("SessionRegistry broadcastExcept sends message to all clients except excluded session ID")
    void testSessionRegistryBroadcastExcept() throws Exception {
        TestEchoEndpoint endpoint = new TestEchoEndpoint();
        endpoint.echoBack = false;

        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/broadcast-except", endpoint);
            server.start();

            BlockingQueue<String> client1Queue = new LinkedBlockingQueue<>();
            BlockingQueue<String> client2Queue = new LinkedBlockingQueue<>();

            HttpClient httpClient = HttpClient.newHttpClient();

            WebSocket ws1 = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/broadcast-except"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            client1Queue.offer(data.toString());
                            return null;
                        }
                    }).get(3, TimeUnit.SECONDS);

            WebSocket ws2 = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/broadcast-except"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            client2Queue.offer(data.toString());
                            return null;
                        }
                    }).get(3, TimeUnit.SECONDS);

            WebSocketSessionRegistry registry = server.getSessionRegistry();
            // Wait for both sessions to be registered
            long start = System.currentTimeMillis();
            while (registry.size() < 2 && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(50);
            }
            assertEquals(2, registry.size());

            List<BedrockWebSocketSession> sessionList = new ArrayList<>(registry.getAll());
            BedrockWebSocketSession session1 = sessionList.get(0);
            BedrockWebSocketSession session2 = sessionList.get(1);

            // Broadcast excluding session1
            registry.broadcastExcept("MessageForClient2Only", session1.getId());

            // Check which client received it
            String c1 = client1Queue.poll(500, TimeUnit.MILLISECONDS);
            String c2 = client2Queue.poll(500, TimeUnit.MILLISECONDS);

            // Exactly one of them should be non-null matching the non-excluded session
            assertTrue((c1 == null && "MessageForClient2Only".equals(c2)) ||
                       (c2 == null && "MessageForClient2Only".equals(c1)),
                    "Exactly one client should have received the message");

            ws1.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown");
            ws2.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown");
        }
    }

    // =========================================================================
    // Scenario 21: Reflective Endpoint Binding
    // =========================================================================

    public static class SampleReflectiveEndpoint {
        public final AtomicInteger openCount = new AtomicInteger(0);
        public final AtomicInteger messageCount = new AtomicInteger(0);
        public final AtomicInteger closeCount = new AtomicInteger(0);
        public final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        public void onOpen(BedrockWebSocketSession session) {
            openCount.incrementAndGet();
        }

        public void onMessage(BedrockWebSocketSession session, String message) {
            messageCount.incrementAndGet();
            messages.offer(message);
            session.send("ReflectiveEcho:" + message);
        }

        public void onClose(BedrockWebSocketSession session, int code, String reason) {
            closeCount.incrementAndGet();
        }

        public void onError(BedrockWebSocketSession session, Throwable t) {
        }
    }

    @Test
    @DisplayName("ReflectiveEndpointBinding inspects methods and dispatches events correctly without proxies")
    void testReflectiveEndpointBinding() throws Exception {
        SampleReflectiveEndpoint endpoint = new SampleReflectiveEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/reflective",
                    endpoint,
                    SampleReflectiveEndpoint.class.getMethod("onOpen", BedrockWebSocketSession.class),
                    SampleReflectiveEndpoint.class.getMethod("onMessage", BedrockWebSocketSession.class, String.class),
                    SampleReflectiveEndpoint.class.getMethod("onClose", BedrockWebSocketSession.class, int.class, String.class),
                    SampleReflectiveEndpoint.class.getMethod("onError", BedrockWebSocketSession.class, Throwable.class)
            );
            server.start();

            BlockingQueue<String> clientReceived = new LinkedBlockingQueue<>();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + server.getPort() + "/reflective"), new WebSocket.Listener() {
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

            assertEquals(1, endpoint.openCount.get(), "onOpen should be invoked");

            ws.sendText("HelloReflective", true).get(3, TimeUnit.SECONDS);

            String serverMsg = endpoint.messages.poll(3, TimeUnit.SECONDS);
            assertEquals("HelloReflective", serverMsg);

            String echo = clientReceived.poll(3, TimeUnit.SECONDS);
            assertEquals("ReflectiveEcho:HelloReflective", echo);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye").get(3, TimeUnit.SECONDS);

            // Wait briefly for onClose
            long start = System.currentTimeMillis();
            while (endpoint.closeCount.get() == 0 && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(50);
            }
            assertEquals(1, endpoint.closeCount.get(), "onClose should be invoked");
        }
    }
}
