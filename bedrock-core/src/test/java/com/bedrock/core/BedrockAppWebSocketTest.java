package com.bedrock.core;

import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.server.BedrockWebSocketServer;
import com.bedrock.core.ws.server.BedrockWebSocketSession;
import com.bedrock.core.ws.server.WebSocketEndpointScanner;
import com.bedrock.core.ws.server.WebSocketSessionRegistry;
import com.bedrock.exception.BedrockException;
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
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   <li>Registration order invariance between {@code register} and {@code enableWebSockets}.</li>
 *   <li>Fail-fast validation for invalid endpoint method declarations.</li>
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
        final GreetingService greetingService;
        final AtomicInteger openCount = new AtomicInteger(0);
        final AtomicInteger closeCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<CloseRecord> closeRecords = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        volatile BedrockWebSocketSession lastSession;

        // Constructor Injection verified by BedrockContainer
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

    // Invalid Endpoint Fixtures for Fail-Fast Validation Testing
    @BedrockSocket("/duplicate-onmessage")
    public static class DuplicateOnMessageSocket {
        @OnMessage
        public void handleA(String msg) {}

        @OnMessage
        public void handleB(String msg) {}
    }

    @BedrockSocket("/invalid-param")
    public static class InvalidParamSocket {
        @OnMessage
        public void handle(String msg, int invalidCount) {}
    }

    @BedrockSocket(value = "/chat", path = "/conflict")
    public static class PathConflictSocket {
        @OnOpen
        public void onOpen() {}
    }

    @BedrockSocket("/multi-annotation")
    public static class MultiAnnotationSocket {
        @OnOpen
        @OnClose
        public void mixed() {}
    }

    static class UnannotatedSocket {}

    @BedrockSocket("/abstract")
    abstract static class AbstractSocket {}


    // =========================================================================
    // Test Scenario 1: Ephemeral Port Discovery & Startup
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: App starts with ephemeral port 0, resolves getWebSocketPort() > 0, and reports isRunning()")
    void testAppStartupWithWebSocketsAndEphemeralPort() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            assertTrue(app.isRunning(), "App should report isRunning() == true");
            BedrockWebSocketServer wsServer = app.getWebSocketServer();
            assertNotNull(wsServer, "WebSocket server must be created");
            assertTrue(wsServer.isRunning(), "WebSocket server must be running");

            int wsPort = app.getWebSocketPort();
            assertTrue(wsPort > 0 && wsPort <= 65535, "Port must be dynamically bound in ephemeral range: " + wsPort);
            assertEquals(wsPort, app.getWsPort(), "getWsPort() alias must match getWebSocketPort()");
            assertEquals(wsPort, wsServer.getPort(), "Server getPort() must match app.getWebSocketPort()");
        }
    }

    // =========================================================================
    // Test Scenario 2: IoC Dependency Injection into @BedrockSocket Singleton
    // =========================================================================

    @Test
    @DisplayName("Scenario 2: BedrockContainer resolves constructor dependencies and injects GreetingService into socket")
    void testIoCDependencyInjectionIntoSocket() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            SampleChatSocket socketInstance = app.getContainer().getBean(SampleChatSocket.class);
            assertNotNull(socketInstance, "Socket singleton must be managed in IoC container");
            assertNotNull(socketInstance.greetingService, "GreetingService must be injected via constructor");

            // Also test get() alias on BedrockContainer
            SampleChatSocket socketViaGet = app.getContainer().get(SampleChatSocket.class);
            assertSame(socketInstance, socketViaGet, "get() alias must return the identical singleton instance");
        }
    }

    // =========================================================================
    // Test Scenario 3: @OnOpen Callback & Greeting Message Delivery
    // =========================================================================

    @Test
    @DisplayName("Scenario 3: Client connects and immediately receives greeting generated during @OnOpen")
    void testOnOpenGreetingReceived() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
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

            assertTrue(openLatch.await(5, TimeUnit.SECONDS), "Client onOpen must trigger");

            String greeting = clientMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(greeting, "Client should receive greeting message from @OnOpen");
            assertTrue(greeting.contains("Welcome, Client-"), "Greeting must contain client prefix: " + greeting);
            assertTrue(greeting.contains("[Bedrock IoC Active]"), "Greeting must prove IoC service injection: " + greeting);

            SampleChatSocket socketInstance = app.getContainer().getBean(SampleChatSocket.class);
            assertEquals(1, socketInstance.openCount.get(), "Server @OnOpen must be called once");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // Test Scenario 4: @OnMessage Text Frame Echo Roundtrip
    // =========================================================================

    @Test
    @DisplayName("Scenario 4: Client sends text message, server unmasks payload, invokes @OnMessage, and returns echo")
    void testOnMessageEchoRoundtrip() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
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
            assertNotNull(incoming.poll(5, TimeUnit.SECONDS), "Initial greeting should arrive");

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

    // =========================================================================
    // Test Scenario 5: Multi-Client Broadcast via Session Registry
    // =========================================================================

    @Test
    @DisplayName("Scenario 5: Multi-client session registry broadcast reaches all active clients concurrently")
    void testOnMessageMultiClientBroadcast() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
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

    // =========================================================================
    // Test Scenario 6: Client-Initiated Clean Close Handshake
    // =========================================================================

    @Test
    @DisplayName("Scenario 6: Client-initiated close invokes @OnClose with status code and reason")
    void testOnCloseClientInitiated() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }
                    }).get(5, TimeUnit.SECONDS);

            SampleChatSocket socket = app.getContainer().getBean(SampleChatSocket.class);

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

    // =========================================================================
    // Test Scenario 7: Application Exception Handling via @OnError
    // =========================================================================

    @Test
    @DisplayName("Scenario 7: Application exception inside @OnMessage triggers @OnError callback")
    void testOnErrorBusinessException() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
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

    // =========================================================================
    // Test Scenario 8: Protocol Violation RFC 6455 §5.1 (@OnError + Close 1002)
    // =========================================================================

    @Test
    @DisplayName("Scenario 8: Protocol violation (unmasked client frame) triggers @OnError and Close 1002")
    void testOnErrorProtocolViolation() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();

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

                // 2. Read HTTP 101 Response byte-by-byte so subsequent frame bytes are not consumed
                ByteArrayOutputStream respBaos = new ByteArrayOutputStream();
                byte[] window = new byte[4];
                int b;
                while ((b = in.read()) != -1) {
                    respBaos.write(b);
                    window[0] = window[1];
                    window[1] = window[2];
                    window[2] = window[3];
                    window[3] = (byte) b;
                    if (window[0] == '\r' && window[1] == '\n' && window[2] == '\r' && window[3] == '\n') {
                        break;
                    }
                }

                // 3. Send ILLEGAL Unmasked Frame: FIN=1, Opcode=1, MASK=0, Len=4, "FAIL"
                byte[] illegalFrame = new byte[]{(byte) 0x81, (byte) 0x04, 'F', 'A', 'I', 'L'};
                out.write(illegalFrame);
                out.flush();

                // 4. Server must reply with Close 1002 (Protocol Error), possibly after any initial greeting frame
                int b0;
                int b1;
                while (true) {
                    b0 = in.read();
                    b1 = in.read();
                    if ((b0 & 0x0F) == 0x08) {
                        break; // Close frame reached
                    }
                    int len = b1 & 0x7F;
                    if (len == 126) {
                        len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                    } else if (len == 127) {
                        byte[] lenBytes = in.readNBytes(8);
                        len = (int) ByteBuffer.wrap(lenBytes).getLong();
                    }
                    in.readNBytes(len);
                }

                assertEquals(0x88, b0 & 0xFF, "Opcode must be 0x8 (Close)");
                int length = b1 & 0x7F;
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

    // =========================================================================
    // Test Scenario 9: Graceful Server Teardown (app.stop())
    // =========================================================================

    @Test
    @DisplayName("Scenario 9: app.stop() sends 1001 Going Away to clients and terminates cleanly")
    void testAppStopCleanTeardown() throws Exception {
        CountDownLatch closeReceived = new CountDownLatch(1);
        AtomicInteger serverCloseCode = new AtomicInteger(0);

        BedrockApp app = BedrockApp.create(0).enableWebSockets(0);
        try {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();

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
        } finally {
            app.stop();
        }
    }


    // =========================================================================
    // Test Scenario 10: Automatic Teardown in Try-With-Resources (AutoCloseable)
    // =========================================================================

    @Test
    @DisplayName("Scenario 10: AutoCloseable frees TCP ports immediately upon exiting try-with-resources")
    void testAppTryWithResourcesAutoCloseable() throws Exception {
        int wsPort;
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.start();
            wsPort = app.getWebSocketPort();
            assertTrue(app.isRunning());
        } // app.close() invoked here

        // Verify port was cleanly released and can be rebound immediately
        try (ServerSocketChannel testChannel = ServerSocketChannel.open()) {
            testChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            assertDoesNotThrow(() -> testChannel.bind(new InetSocketAddress("127.0.0.1", wsPort)),
                    "Port " + wsPort + " must be freed immediately (no port leak)");
        }
    }

    // =========================================================================
    // Test Scenario 11: Configuration Order Invariance
    // =========================================================================

    @Test
    @DisplayName("Scenario 11: Registration order invariance (register before vs after enableWebSockets)")
    void testRegistrationOrderInvariance() throws Exception {
        // Register BEFORE enableWebSockets
        try (BedrockApp app = BedrockApp.create(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class);
            app.enableWebSockets(0);
            app.start();

            int wsPort = app.getWebSocketPort();
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

    // =========================================================================
    // Test Scenario 12: Dual HTTP REST and WebSocket Coexistence
    // =========================================================================

    @Test
    @DisplayName("Scenario 12: Dual HTTP REST and WebSocket co-existence on BedrockApp")
    void testDualHttpAndWebSocketEndpoints() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.bind(GreetingService.class, GreetingServiceImpl.class);
            app.register(GreetingServiceImpl.class, SampleChatSocket.class, StatusHttpController.class);
            app.start();

            int httpPort = app.getPort();
            int wsPort = app.getWebSocketPort();
            assertTrue(httpPort > 0, "HTTP port must be allocated");
            assertTrue(wsPort > 0, "WebSocket port must be allocated");
            assertNotNull(app.getHttpServer(), "getHttpServer() accessor must return active HttpServer");

            HttpClient httpClient = HttpClient.newHttpClient();

            // 1. Verify HTTP GET /api/health
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/api/health"))
                    .GET()
                    .build();
            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, httpResp.statusCode(), "HTTP endpoint must return 200 OK");
            assertTrue(httpResp.body().contains("HEALTHY"), "Response must contain controller data: " + httpResp.body());

            // 2. Verify WebSocket connection on /chat
            CountDownLatch wsLatch = new CountDownLatch(1);
            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            wsLatch.countDown();
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertTrue(wsLatch.await(5, TimeUnit.SECONDS), "WebSocket connection must succeed concurrently with HTTP server");
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown").get(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // Additional Validation Tests: Fail-Fast Invalid Endpoints & Configuration
    // =========================================================================

    @Test
    @DisplayName("Validation: Duplicate @OnMessage methods fail fast with BedrockException")
    void testDuplicateOnMessageFailsFast() {
        DuplicateOnMessageSocket socket = new DuplicateOnMessageSocket();
        BedrockException ex = assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(socket));
        assertTrue(ex.getMessage().contains("multiple methods annotated with @OnMessage"),
                "Exception message must identify duplicate @OnMessage: " + ex.getMessage());
        assertNotNull(ex.getAction(), "Actionable advice must be provided");
    }

    @Test
    @DisplayName("Validation: Unsupported parameter types fail fast with BedrockException")
    void testUnsupportedParameterFailsFast() {
        InvalidParamSocket socket = new InvalidParamSocket();
        BedrockException ex = assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(socket));
        assertTrue(ex.getMessage().contains("unsupported parameter type"),
                "Exception must describe invalid parameter type: " + ex.getMessage());
    }

    @Test
    @DisplayName("Validation: Conflicting route path values in @BedrockSocket fail fast")
    void testConflictingRoutePathsFailFast() {
        PathConflictSocket socket = new PathConflictSocket();
        BedrockException ex = assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(socket));
        assertTrue(ex.getMessage().contains("Conflicting route paths"),
                "Exception must note conflicting path values: " + ex.getMessage());
    }

    @Test
    @DisplayName("Validation: Method with multiple lifecycle annotations fails fast")
    void testMultipleAnnotationsOnSingleMethodFailsFast() {
        MultiAnnotationSocket socket = new MultiAnnotationSocket();
        BedrockException ex = assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(socket));
        assertTrue(ex.getMessage().contains("multiple WebSocket lifecycle annotations"),
                "Exception must catch multiple annotations on single method: " + ex.getMessage());
    }

    @Test
    @DisplayName("Validation: Invalid port number outside 0-65535 throws BedrockException")
    void testInvalidPortThrowsBedrockException() {
        BedrockApp app = BedrockApp.create(0);
        assertThrows(BedrockException.class, () -> app.enableWebSockets(-1));
        assertThrows(BedrockException.class, () -> app.enableWebSockets(70000));
    }

    @Test
    @DisplayName("Validation: Duplicate enableWebSockets call throws BedrockException")
    void testDuplicateEnableWebSocketsThrowsBedrockException() {
        BedrockApp app = BedrockApp.create(0).enableWebSockets(0);
        BedrockException ex = assertThrows(BedrockException.class, () -> app.enableWebSockets(0));
        assertTrue(ex.getMessage().contains("WebSockets already enabled"),
                "Exception must report duplicate enableWebSockets: " + ex.getMessage());
    }

    @Test
    @DisplayName("Validation: Missing @BedrockSocket annotation throws BedrockException")
    void testMissingBedrockSocketAnnotationFailsFast() {
        UnannotatedSocket socket = new UnannotatedSocket();
        BedrockException ex = assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(socket));
        assertTrue(ex.getMessage().contains("is missing the required @BedrockSocket annotation"));
    }

    @Test
    @DisplayName("Validation: Abstract class annotated with @BedrockSocket throws BedrockException")
    void testAbstractClassOrInterfaceFailsFast() {
        BedrockException ex = assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.extractPath(AbstractSocket.class));
        assertTrue(ex.getMessage().contains("Cannot bind abstract class or interface"));
    }
}

