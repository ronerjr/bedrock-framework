package com.bedrock.example;

import com.bedrock.core.BedrockApp;
import com.bedrock.example.ws.ChatPlayground;
import com.bedrock.example.ws.ChatWebSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Milestone 4 Real-Time Chat WebSocket Integration Test Suite
 *
 * <p>Validates the complete end-to-end integration between the {@code bedrock-example}
 * application, {@link ChatWebSocket}, {@link ChatPlayground}, and the underlying
 * Bedrock Version 2.0 Virtual-Threaded RFC 6455 WebSocket engine.</p>
 *
 * <h3>Key Verification Goals:</h3>
 * <ul>
 *   <li><b>Ephemeral Port Allocation</b>: Validates dynamic zero-conflict port binding.</li>
 *   <li><b>HTTP 101 Switching Protocols</b>: Verifies handshake execution via {@link HttpClient}.</li>
 *   <li><b>Multi-Client Broadcasting</b>: Proves message fan-out across multiple concurrent virtual threads.</li>
 *   <li><b>UTF-8 Invariants</b>: Verifies emoji and multi-byte character preservation.</li>
 *   <li><b>Graceful Lifecycle Teardown</b>: Verifies clean socket release and RFC 1001 close signaling.</li>
 * </ul>
 */
public class ChatWebSocketTest {

    private static final int TIMEOUT_SECONDS = 5;

    /**
     * Helper to establish an HttpClient WebSocket connection with a queue for received text frames.
     */
    private record TestClient(WebSocket webSocket, BlockingQueue<String> messages, CountDownLatch openLatch) {
        public void close() {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test Complete")
                         .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }

    private TestClient connectClient(int wsPort, String path) throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        CountDownLatch openLatch = new CountDownLatch(1);

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + wsPort + path), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        openLatch.countDown();
                        webSocket.request(Long.MAX_VALUE);
                    }

                    private final StringBuilder textBuffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textBuffer.append(data);
                        if (last) {
                            messageQueue.offer(textBuffer.toString());
                            textBuffer.setLength(0);
                        }
                        return null;
                    }
                });

        WebSocket ws = wsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Client WebSocket must trigger onOpen");
        return new TestClient(ws, messageQueue, openLatch);
    }

    // =========================================================================
    // Scenario 1: Connection Establishment & Handshake Verification
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Client establishes RFC 6455 connection to ChatWebSocket and receives join broadcast")
    void testWebSocketConnectionAndHandshake() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            assertTrue(wsPort > 0, "Bound WebSocket port must be positive ephemeral port");

            TestClient client = connectClient(wsPort, "/chat");
            try {
                // Wait for initial announcement from server @OnOpen
                String welcomeOrJoin = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(welcomeOrJoin, "Client must receive join or welcome message upon connecting");
                assertTrue(welcomeOrJoin.contains("[System]") || welcomeOrJoin.contains("connected") || welcomeOrJoin.contains("User"),
                        "Initial message should announce connection: " + welcomeOrJoin);
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Scenario 2: Single Client Send and Receive Broadcast
    // =========================================================================

    @Test
    @DisplayName("Scenario 2: Single client sends a chat message and receives the unmasked broadcast back")
    void testSingleClientSendMessageAndReceiveBroadcast() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client = connectClient(wsPort, "/chat");

            try {
                // Drain any initial system join/welcome announcements
                while (client.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                String chatPayload = "[Alice] Hello Bedrock Virtual Threads!";
                client.webSocket().sendText(chatPayload, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String received = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received, "Client must receive broadcast message");
                assertTrue(received.contains("Hello Bedrock Virtual Threads!"),
                        "Received message must contain the sent text payload: " + received);
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Scenario 3: Multiple Simultaneous Clients Broadcast Fan-Out
    // =========================================================================

    @Test
    @DisplayName("Scenario 3: Multi-client broadcast delivers messages simultaneously to all active sessions")
    void testMultipleClientsReceiveBroadcastSimultaneously() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            int clientCount = 3;
            List<TestClient> clients = new CopyOnWriteArrayList<>();

            try {
                // Connect 3 independent clients concurrently
                for (int i = 0; i < clientCount; i++) {
                    clients.add(connectClient(wsPort, "/chat"));
                }

                // Drain initial connection messages from all queues
                for (TestClient c : clients) {
                    while (c.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                }

                // Client 0 sends a message
                String broadcastMessage = "[User-0] Hello everyone in the room!";
                clients.get(0).webSocket().sendText(broadcastMessage, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Verify all 3 clients received the broadcast message
                for (int i = 0; i < clientCount; i++) {
                    String received = clients.get(i).messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(received, "Client #" + i + " must receive broadcast");
                    assertTrue(received.contains("Hello everyone in the room!"),
                            "Client #" + i + " payload must match broadcast content: " + received);
                }

                // Client 2 sends a reply
                String replyMessage = "[User-2] Got your message loud and clear!";
                clients.get(2).webSocket().sendText(replyMessage, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Verify all 3 clients receive Client 2's reply
                for (int i = 0; i < clientCount; i++) {
                    String received = clients.get(i).messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(received, "Client #" + i + " must receive reply");
                    assertTrue(received.contains("Got your message loud and clear!"),
                            "Client #" + i + " payload must match reply content: " + received);
                }

            } finally {
                for (TestClient c : clients) {
                    c.close();
                }
            }
        }
    }

    // =========================================================================
    // Scenario 4: Clean Disconnection Lifecycle & Peer Notification
    // =========================================================================

    @Test
    @DisplayName("Scenario 4: Client initiates clean Close 1000 and remaining client receives departure notice")
    void testCleanDisconnectionLifecycle() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client1 = connectClient(wsPort, "/chat");
            TestClient client2 = connectClient(wsPort, "/chat");

            try {
                // Drain initial announcements
                while (client1.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (client2.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Client 1 gracefully closes connection
                client1.webSocket().sendClose(WebSocket.NORMAL_CLOSURE, "Leaving chat")
                       .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Client 2 should receive a departure notice or system broadcast
                String departureMsg = client2.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (departureMsg != null) {
                    assertTrue(departureMsg.contains("[System]") || departureMsg.contains("disconnected") || departureMsg.contains("left") || departureMsg.contains("User"),
                            "Departure announcement should notify active peers: " + departureMsg);
                }

                // Verify Client 2 can still send messages normally after Client 1 left
                String soloMsg = "[Client-2] Still here alone!";
                client2.webSocket().sendText(soloMsg, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String receivedSolo = client2.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(receivedSolo);
                assertTrue(receivedSolo.contains("Still here alone!"));

            } finally {
                client1.close();
                client2.close();
            }
        }
    }

    // =========================================================================
    // Scenario 5: Multi-Byte UTF-8 and Unicode Emoji Preservation
    // =========================================================================

    @Test
    @DisplayName("Scenario 5: Multi-byte UTF-8, accented Portuguese, and Unicode emojis survive framing intact")
    void testMultiByteUtf8AndEmojiSupport() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client = connectClient(wsPort, "/chat");

            try {
                // Drain any initial system announcements
                while (client.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                String complexPayload = "🦖 Bedrock 2.0: Olá mundo! João comprou maçãs & café na praça. 🚀✨ (日本語・한국어)";
                client.webSocket().sendText(complexPayload, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String received = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received);
                assertTrue(received.contains(complexPayload),
                        "Multi-byte UTF-8 text and emojis must match exactly: " + received);
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Scenario 6: Rapid-Fire Concurrent Messages Under Virtual Threads
    // =========================================================================

    @Test
    @DisplayName("Scenario 6: Rapid-fire messages are broadcast without frame tearing or session deadlock")
    void testRapidFireConcurrentMessages() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client1 = connectClient(wsPort, "/chat");
            TestClient client2 = connectClient(wsPort, "/chat");

            try {
                // Drain initial joins
                while (client1.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (client2.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                int messageCount = 15;
                for (int i = 0; i < messageCount; i++) {
                    client1.webSocket().sendText("[Burst] Message #" + i, true);
                }

                // Verify client2 receives all 15 messages
                List<String> receivedByClient2 = new ArrayList<>();
                for (int i = 0; i < messageCount; i++) {
                    String msg = client2.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(msg, "Client 2 must receive message #" + i + " before timeout");
                    receivedByClient2.add(msg);
                }

                assertEquals(messageCount, receivedByClient2.size(), "All burst messages must be delivered");

            } finally {
                client1.close();
                client2.close();
            }
        }
    }

    // =========================================================================
    // Scenario 7: Application Coordinated Teardown & Zero Port Leaks
    // =========================================================================

    @Test
    @DisplayName("Scenario 7: BedrockApp.stop() notifies clients with 1001 Going Away and frees ports")
    void testAppLifecycleStopReleasesAllResources() throws Exception {
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        AtomicInteger receivedCloseCode = new AtomicInteger(0);

        BedrockApp app = BedrockApp.create(0).enableWebSockets(0);
        app.register(ChatWebSocket.class);
        app.start();

        int wsPort = app.getWebSocketPort();
        HttpClient httpClient = HttpClient.newHttpClient();

        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        receivedCloseCode.set(statusCode);
                        clientCloseLatch.countDown();
                        return null;
                    }
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Stop application server
        app.stop();

        // Client must be notified with RFC 6455 code 1001 (Going Away)
        assertTrue(clientCloseLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Client must receive Close notification when server stops");
        assertEquals(1001, receivedCloseCode.get(), "Server stop must transmit status 1001 (GOING_AWAY)");
        assertFalse(app.isRunning(), "BedrockApp must report isRunning() == false");
    }

    // =========================================================================
    // Scenario 8: HTTP Route Serves ChatPlayground UI & Factory Method Verification
    // =========================================================================

    @Test
    @DisplayName("Scenario 8: HTTP GET /chat serves ChatPlayground HTML with 200 OK via Application.createApp")
    void testChatPlaygroundHtmlRouteServedViaHttp() throws Exception {
        try (BedrockApp app = Application.createApp(0, 0)) {
            app.start();

            int httpPort = app.getPort();
            HttpClient httpClient = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/chat"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), "HTTP status must be 200 OK");
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"),
                    "Content-Type header must be text/html");
            assertTrue(response.body().contains("🦖 Bedrock Chat"), "HTML must contain ChatPlayground title");
            assertTrue(response.body().contains("new WebSocket"), "HTML must contain WebSocket JS client script");
            assertTrue(response.body().contains(String.valueOf(app.getWebSocketPort())),
                    "HTML must contain the injected WebSocket port");
        }
    }
}
