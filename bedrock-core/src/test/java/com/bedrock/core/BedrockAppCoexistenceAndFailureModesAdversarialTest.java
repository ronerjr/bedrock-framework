package com.bedrock.core;

import com.bedrock.core.ws.annotation.BedrockSocket;
import com.bedrock.core.ws.annotation.OnClose;
import com.bedrock.core.ws.annotation.OnError;
import com.bedrock.core.ws.annotation.OnMessage;
import com.bedrock.core.ws.annotation.OnOpen;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.server.BedrockWebSocketServer;
import com.bedrock.core.ws.server.BedrockWebSocketSession;
import com.bedrock.exception.BedrockException;
import com.bedrock.web.BedrockController;
import com.bedrock.web.BedrockGet;
import com.bedrock.web.BedrockPost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🥋 ADVERSARIAL VERIFICATION SUITE: Dual Server Coexistence, Error Propagation,
 * Registration Order Invariance, and Critical Failure Modes.
 *
 * <p>Author: challenger_m3_2
 * Scope: Milestone 3 (BedrockApp Integration & Annotations)</p>
 */
public class BedrockAppCoexistenceAndFailureModesAdversarialTest {

    // =========================================================================
    // Fixtures: Services, Controllers, and Annotated Sockets
    // =========================================================================

    public interface ServiceA { String msgA(); }
    public static class ServiceAImpl implements ServiceA {
        @Override public String msgA() { return "A_READY"; }
    }

    public interface ServiceB { String msgB(); }
    public static class ServiceBImpl implements ServiceB {
        private final ServiceA a;
        public ServiceBImpl(ServiceA a) { this.a = Objects.requireNonNull(a); }
        @Override public String msgB() { return a.msgA() + "->B_READY"; }
    }

    @BedrockController
    public static class EchoHttpController {
        @BedrockGet("/api/ping")
        public Map<String, Object> ping() {
            return Map.of("pong", true, "timestamp", System.currentTimeMillis());
        }

        @BedrockPost("/api/echo")
        public Map<String, String> echo(Context ctx) {
            String body = ctx.body();
            return Map.of("echo", body != null ? body : "EMPTY");
        }
    }

    @BedrockSocket("/coexist-stream")
    public static class CoexistSocket {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnMessage
        public void onMessage(BedrockWebSocketSession session, String text) {
            messages.offer(text);
            session.send("WS_ECHO:" + text);
        }
    }

    @BedrockSocket("/exploding-open")
    public static class ExplodingOpenSocket {
        final BlockingQueue<Throwable> capturedErrors = new LinkedBlockingQueue<>();
        final AtomicBoolean openCalled = new AtomicBoolean(false);

        @OnOpen
        public void onOpen(BedrockWebSocketSession session) {
            openCalled.set(true);
            throw new IllegalStateException("Deliberate explosion inside @OnOpen!");
        }

        @OnError
        public void onError(BedrockWebSocketSession session, Throwable t) {
            capturedErrors.offer(t);
        }
    }

    @BedrockSocket("/no-error-handler")
    public static class NoErrorHandlerSocket {
        final AtomicInteger messageCount = new AtomicInteger(0);

        @OnMessage
        public void onMessage(BedrockWebSocketSession session, String text) {
            messageCount.incrementAndGet();
            if ("FAIL".equals(text)) {
                throw new RuntimeException("Deliberate failure without @OnError");
            }
            session.send("REPLY:" + text);
        }
    }

    @BedrockSocket("/error-throws-exception")
    public static class ErrorThrowsExceptionSocket {
        final AtomicBoolean errorMethodEntered = new AtomicBoolean(false);

        @OnMessage
        public void onMessage(BedrockWebSocketSession session, String text) {
            throw new IllegalArgumentException("First error in message");
        }

        @OnError
        public void onError(Throwable t) {
            errorMethodEntered.set(true);
            throw new RuntimeException("Secondary error thrown inside @OnError!");
        }
    }

    @BedrockSocket("/order-inv-socket")
    public static class SocketWithMultiDeps {
        final ServiceB serviceB;
        public SocketWithMultiDeps(ServiceB serviceB) {
            this.serviceB = Objects.requireNonNull(serviceB);
        }

        @OnOpen
        public void onOpen(BedrockWebSocketSession session) {
            session.send("INIT:" + serviceB.msgB());
        }
    }

    @BedrockSocket("/late-socket")
    public static class LateBoundSocket {
        @OnOpen
        public void onOpen(BedrockWebSocketSession session) {
            session.send("LATE_BOUND_SUCCESS");
        }
    }

    @BedrockSocket("/pruning-broadcast")
    public static class PruningBroadcastSocket {
        @OnOpen
        public void onOpen(BedrockWebSocketSession session) {
            session.send("HELLO:" + session.getId());
        }
    }

    // =========================================================================
    // PILLAR 1: Dual Server Coexistence
    // =========================================================================

    @Test
    @DisplayName("Pillar 1: HTTP and WebSocket servers process concurrent high-throughput traffic without interference")
    void testDualServerCoexistenceConcurrentTraffic() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(EchoHttpController.class, CoexistSocket.class);
            app.start();

            int httpPort = app.getPort();
            int wsPort = app.getWebSocketPort();
            assertTrue(httpPort > 0, "HTTP port must be allocated");
            assertTrue(wsPort > 0, "WebSocket port must be allocated");
            assertNotEquals(httpPort, wsPort, "Ports must be distinct ephemeral ports");

            HttpClient httpClient = HttpClient.newHttpClient();

            // Connect WebSocket client
            BlockingQueue<String> wsReplies = new LinkedBlockingQueue<>();
            CountDownLatch wsOpenLatch = new CountDownLatch(1);

            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/coexist-stream"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            wsOpenLatch.countDown();
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            wsReplies.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            assertTrue(wsOpenLatch.await(5, TimeUnit.SECONDS));

            // Interleave HTTP requests and WebSocket frames in concurrent threads
            int iterations = 20;
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch doneLatch = new CountDownLatch(iterations * 2);

            // HTTP tasks
            for (int i = 0; i < iterations; i++) {
                final int id = i;
                pool.submit(() -> {
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + httpPort + "/api/echo"))
                                .POST(HttpRequest.BodyPublishers.ofString("PAYLOAD_" + id))
                                .build();
                        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201);
                        assertTrue(resp.body().contains("PAYLOAD_" + id));
                    } catch (Exception e) {
                        fail("HTTP request failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // WebSocket tasks
            for (int i = 0; i < iterations; i++) {
                final int id = i;
                pool.submit(() -> {
                    try {
                        synchronized (ws) {
                            ws.sendText("MSG_" + id, true).get(5, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        fail("WS send failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All concurrent tasks must complete");

            // Verify all WebSocket replies arrived
            for (int i = 0; i < iterations; i++) {
                String reply = wsReplies.poll(5, TimeUnit.SECONDS);
                assertNotNull(reply, "WebSocket reply must arrive");
                assertTrue(reply.startsWith("WS_ECHO:MSG_"), "Reply format mismatch: " + reply);
            }

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("Pillar 1 (Failure Mode): Port conflict between HTTP and WS fails fast and cleans up HTTP server without port leaks")
    void testDualServerSamePortConflictFailsFastAndReleasesHttpPort() throws Exception {
        // Allocate a dedicated port
        int targetPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            targetPort = ss.getLocalPort();
        }

        BedrockApp conflictingApp = BedrockApp.create(targetPort).enableWebSockets(targetPort);

        // app.start() should throw BedrockException because WS cannot bind to targetPort (already held by HTTP)
        BedrockException ex = assertThrows(BedrockException.class, conflictingApp::start,
                "Starting app with identical HTTP and WebSocket ports must throw BedrockException");
        assertTrue(ex.getMessage().contains("Could not start Bedrock WebSocket server"),
                "Exception must detail WebSocket bind failure: " + ex.getMessage());

        // CRITICAL ADVERSARIAL CHECK: Verify HTTP server was torn down and didn't leak targetPort
        try (ServerSocketChannel verifyChannel = ServerSocketChannel.open()) {
            verifyChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            assertDoesNotThrow(() -> verifyChannel.bind(new InetSocketAddress("127.0.0.1", targetPort)),
                    "Port " + targetPort + " must be cleanly released; zero port leaks allowed!");
        }
    }

    // =========================================================================
    // PILLAR 2: Error Propagation
    // =========================================================================

    @Test
    @DisplayName("Pillar 2: Exception in @OnOpen dispatches to @OnError and closes connection with Close 1011 (Server Error)")
    void testOnOpenExceptionPropagationAndClose1011() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ExplodingOpenSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            HttpClient client = HttpClient.newHttpClient();
            CompletableFuture<Integer> closeCodeFuture = new CompletableFuture<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/exploding-open"), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            closeCodeFuture.complete(statusCode);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            closeCodeFuture.complete(-1);
                        }
                    }).get(5, TimeUnit.SECONDS);

            // Client must receive Close frame with status 1011 (SERVER_ERROR_CODE) or close notification
            int closeCode = closeCodeFuture.get(5, TimeUnit.SECONDS);
            assertEquals(WebSocketCloseStatus.SERVER_ERROR_CODE, closeCode,
                    "Server must terminate connection with RFC 6455 status 1011 on @OnOpen exception");

            ExplodingOpenSocket socketInstance = app.getContainer().getBean(ExplodingOpenSocket.class);
            assertTrue(socketInstance.openCalled.get(), "@OnOpen must have been invoked");
            Throwable err = socketInstance.capturedErrors.poll(5, TimeUnit.SECONDS);
            assertNotNull(err, "@OnError must have received the exception from @OnOpen");
            assertTrue(err instanceof IllegalStateException, "Exception type must match: " + err);
            assertEquals("Deliberate explosion inside @OnOpen!", err.getMessage());
        }
    }

    @Test
    @DisplayName("Pillar 2: Unhandled exception when socket lacks @OnError does not crash frame loop or server")
    void testMissingOnErrorDoesNotCrashApp() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(NoErrorHandlerSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> incoming = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/no-error-handler"), new WebSocket.Listener() {
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

            // Send message that throws exception inside @OnMessage
            ws.sendText("FAIL", true).get(5, TimeUnit.SECONDS);

            // Send healthy message immediately after to prove frame loop survived and session is still intact
            ws.sendText("SURVIVED", true).get(5, TimeUnit.SECONDS);

            String reply = incoming.poll(5, TimeUnit.SECONDS);
            assertEquals("REPLY:SURVIVED", reply, "Connection must remain operational after unhandled @OnMessage exception");

            NoErrorHandlerSocket socket = app.getContainer().getBean(NoErrorHandlerSocket.class);
            assertEquals(2, socket.messageCount.get());

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Clean close").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Pillar 2: Exception thrown inside @OnError handler itself is safely caught without crashing the JVM thread")
    void testOnErrorThrowingExceptionDoesNotCrashApp() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ErrorThrowsExceptionSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/error-throws-exception"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }
                    }).get(5, TimeUnit.SECONDS);

            // Trigger error inside @OnMessage which then triggers error inside @OnError
            ws.sendText("TRIGGER_DOUBLE_FAULT", true).get(5, TimeUnit.SECONDS);

            // Wait briefly to allow Virtual Thread to process
            Thread.sleep(200);

            ErrorThrowsExceptionSocket socket = app.getContainer().getBean(ErrorThrowsExceptionSocket.class);
            assertTrue(socket.errorMethodEntered.get(), "@OnError method must have been entered");

            // Server must still be running healthy
            assertTrue(app.isRunning(), "Server must remain running despite exception thrown inside @OnError");
            assertTrue(app.getWebSocketServer().isRunning());

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Close").get(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // PILLAR 3: Registration Order Invariance
    // =========================================================================

    @Test
    @DisplayName("Pillar 3: Multi-level constructor dependency injection resolves identically regardless of registration order")
    void testRegistrationOrderInvarianceComplexTopology() throws Exception {
        // Order: Register all dependencies and socket BEFORE enableWebSockets
        try (BedrockApp app = BedrockApp.create(0)) {
            app.bind(ServiceA.class, ServiceAImpl.class);
            app.bind(ServiceB.class, ServiceBImpl.class);
            app.register(ServiceBImpl.class, SocketWithMultiDeps.class, ServiceAImpl.class);

            // Later enable WebSockets
            app.enableWebSockets(0);
            app.start();

            int wsPort = app.getWebSocketPort();
            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> clientMessages = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/order-inv-socket"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            clientMessages.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            String greeting = clientMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(greeting);
            assertEquals("INIT:A_READY->B_READY", greeting, "Multi-level IoC dependency graph must resolve cleanly");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Teardown").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Pillar 3: enableWebSockets(0) called after app.start() starts WebSocket server dynamically")
    void testEnableWebSocketsAfterAppStart() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.register(LateBoundSocket.class);
            app.start();

            assertTrue(app.isRunning());
            assertEquals(-1, app.getWebSocketPort(), "WebSocket port should be -1 prior to enableWebSockets");
            assertNull(app.getWebSocketServer());

            // Dynamically enable WebSockets after startup
            app.enableWebSockets(0);

            BedrockWebSocketServer wsServer = app.getWebSocketServer();
            assertNotNull(wsServer);
            assertTrue(wsServer.isRunning(), "WebSocket server must start dynamically when app is already running");
            assertTrue(app.getWebSocketPort() > 0);

            // Connect client to verify dynamic binding
            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> incoming = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + app.getWebSocketPort() + "/late-socket"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            incoming.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            String reply = incoming.poll(5, TimeUnit.SECONDS);
            assertEquals("LATE_BOUND_SUCCESS", reply);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // PILLAR 4: Critical Failure Modes & Edge Cases
    // =========================================================================

    @Test
    @DisplayName("Pillar 4: Requesting non-existent WebSocket route returns HTTP 404 and cleanly closes socket")
    void testNonExistentWebSocketRouteReturns404() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(CoexistSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();

            // Connect raw TCP socket and send handshake for unmapped route
            try (Socket socket = new Socket("127.0.0.1", wsPort)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                String handshake = "GET /missing-route HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + wsPort + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n";
                out.write(handshake.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n = in.read(buf);
                assertTrue(n > 0);
                baos.write(buf, 0, n);

                String response = baos.toString(StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 404"), "Unmapped WebSocket path must return HTTP 404: " + response);

                // Socket should reach EOF immediately
                int next = in.read();
                assertEquals(-1, next, "Server must close TCP channel after 404 response");
            }
        }
    }

    @Test
    @DisplayName("Pillar 4: Broadcast gracefully isolates broken client without aborting fanout to remaining clients")
    void testBroadcastWithBrokenClientMidStream() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(PruningBroadcastSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            HttpClient client = HttpClient.newHttpClient();

            BlockingQueue<String> client1Queue = new LinkedBlockingQueue<>();
            BlockingQueue<String> client3Queue = new LinkedBlockingQueue<>();
            CountDownLatch openLatch = new CountDownLatch(2);

            WebSocket ws1 = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/pruning-broadcast"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            openLatch.countDown();
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            client1Queue.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            WebSocket ws3 = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + wsPort + "/pruning-broadcast"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            openLatch.countDown();
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            client3Queue.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            // Connect a raw socket as Client 2 and abruptly abort it (TCP RST simulation)
            Socket rawSocket2 = new Socket("127.0.0.1", wsPort);
            rawSocket2.setSoLinger(true, 0); // Force TCP RST on close!
            OutputStream out2 = rawSocket2.getOutputStream();
            InputStream in2 = rawSocket2.getInputStream();

            String handshake2 = "GET /pruning-broadcast HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + wsPort + "\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n";
            out2.write(handshake2.getBytes(StandardCharsets.US_ASCII));
            out2.flush();

            // Wait for handshake
            byte[] b2 = new byte[256];
            int r2 = in2.read(b2);
            assertTrue(r2 > 0);

            assertTrue(openLatch.await(5, TimeUnit.SECONDS));

            // Discard initial greetings
            assertNotNull(client1Queue.poll(5, TimeUnit.SECONDS));
            assertNotNull(client3Queue.poll(5, TimeUnit.SECONDS));

            // Now abruptly kill rawSocket2 with RST
            rawSocket2.close();

            // Perform broadcast from session registry
            app.getWebSocketServer().getSessionRegistry().broadcast("SURVIVOR_BROADCAST");

            // Client 1 and Client 3 MUST receive the broadcast despite Client 2 having abruptly reset
            String msg1 = client1Queue.poll(5, TimeUnit.SECONDS);
            String msg3 = client3Queue.poll(5, TimeUnit.SECONDS);
            assertEquals("SURVIVOR_BROADCAST", msg1, "Client 1 must receive broadcast");
            assertEquals("SURVIVOR_BROADCAST", msg3, "Client 3 must receive broadcast");

            ws1.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
            ws3.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }
}
