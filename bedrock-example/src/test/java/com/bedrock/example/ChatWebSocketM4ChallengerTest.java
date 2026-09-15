package com.bedrock.example;

import com.bedrock.core.BedrockApp;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.example.ws.ChatWebSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Challenger M4-2 Adversarial Test Suite
 *
 * <p>Empirically verifies the four core Milestone 4 challenge requirements:
 * <ol>
 *   <li><b>Dual-Server Coexistence</b>: HTTP and WebSocket servers bind to separate ports and run simultaneously without collision or cross-talk.</li>
 *   <li><b>HTML Page Port Resolution</b>: GET /chat dynamically resolves and injects the live WebSocket port into the client script.</li>
 *   <li><b>Edge Case Commands</b>: Handling of {@code /nick } with no name, {@code /nick with spaces}, and extra-long payloads (&gt;64 KB).</li>
 *   <li><b>Clean Teardown (Close 1001)</b>: {@code app.stop()} broadcasts RFC 6455 Close status 1001 (Going Away) to all active sessions and releases ports immediately.</li>
 * </ol>
 * </p>
 */
public class ChatWebSocketM4ChallengerTest {

    private static final int TIMEOUT_SECONDS = 5;

    private record TestClient(WebSocket webSocket, BlockingQueue<String> messages, CountDownLatch openLatch,
                              CountDownLatch closeLatch, AtomicInteger closeCode) {
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
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicInteger closeCode = new AtomicInteger(0);

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

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeCode.set(statusCode);
                        closeLatch.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        closeLatch.countDown();
                    }
                });

        WebSocket ws = wsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Client WebSocket must trigger onOpen");
        return new TestClient(ws, messageQueue, openLatch, closeLatch, closeCode);
    }

    // =========================================================================
    // Challenge 1: Dual Server Coexistence
    // =========================================================================

    @Test
    @DisplayName("Challenge 1: HTTP and WebSocket servers coexist on separate ports and handle concurrent traffic")
    void testDualServerCoexistenceConcurrentTraffic() throws Exception {
        try (BedrockApp app = Application.createApp(0, 0)) {
            app.start();

            int httpPort = app.getPort();
            int wsPort = app.getWebSocketPort();

            assertTrue(httpPort > 0, "HTTP port must be bound and positive");
            assertTrue(wsPort > 0, "WebSocket port must be bound and positive");
            assertNotEquals(httpPort, wsPort, "HTTP and WebSocket servers must occupy distinct ports");

            HttpClient httpClient = HttpClient.newHttpClient();

            // 1. Verify HTTP endpoint responds
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/api/ping"))
                    .GET()
                    .build();
            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, httpResp.statusCode());
            assertEquals("pong", httpResp.body());

            // 2. Verify WebSocket endpoint responds simultaneously
            TestClient wsClient = connectClient(wsPort, "/chat");
            try {
                // Drain any initial system join/welcome announcements
                while (wsClient.messages().poll(300, TimeUnit.MILLISECONDS) != null) {}

                // 3. Alternate between HTTP and WS traffic
                for (int i = 0; i < 5; i++) {
                    HttpResponse<String> r = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, r.statusCode());

                    wsClient.webSocket().sendText("[Coexist] Ping #" + i, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    String received = wsClient.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(received);
                    assertTrue(received.contains("Ping #" + i));
                }
            } finally {
                wsClient.close();
            }
        }
    }

    // =========================================================================
    // Challenge 2: HTML Page Port Resolution
    // =========================================================================

    @Test
    @DisplayName("Challenge 2: GET /chat dynamically resolves WebSocket port and client connects using injected port")
    void testHtmlChatPagePortResolutionAndConnection() throws Exception {
        try (BedrockApp app = Application.createApp(0, 0)) {
            app.start();

            int httpPort = app.getPort();
            int wsPort = app.getWebSocketPort();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/chat"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            String html = response.body();
            assertNotNull(html);

            // Verify HTML contains dynamic port placeholder replacement
            String expectedPortSnippet = "const injectedPort = \"" + wsPort + "\";";
            assertTrue(html.contains(expectedPortSnippet),
                    "HTML must contain injected WebSocket port snippet: " + expectedPortSnippet);
            assertTrue(html.contains("const injectedPath = \"/chat\";"),
                    "HTML must contain injected WebSocket path snippet");

            // Connect WebSocket using the port extracted from HTML body
            int portStartIndex = html.indexOf("const injectedPort = \"") + "const injectedPort = \"".length();
            int portEndIndex = html.indexOf("\";", portStartIndex);
            String extractedPortStr = html.substring(portStartIndex, portEndIndex);
            int resolvedPort = Integer.parseInt(extractedPortStr);
            assertEquals(wsPort, resolvedPort, "Resolved port from HTML must match actual server port");

            TestClient client = connectClient(resolvedPort, "/chat");
            try {
                String join = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(join, "Client connected via resolved port must receive announcement");
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Challenge 3: Edge Case Commands (/nick, /nick with spaces, extra long payloads)
    // =========================================================================

    @Test
    @DisplayName("Challenge 3a: /nick command with multiple words and spaces updates display name correctly")
    void testNickCommandWithSpacesInName() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Send /nick with spaces in the name
                String multiWordNick = "Sir Isaac Newton of Woolsthorpe";
                sender.webSocket().sendText("/nick " + multiWordNick, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Receiver gets system announcement
                String announce = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(announce);
                assertTrue(announce.contains("[System]") && announce.contains("is now known as " + multiWordNick),
                        "System announcement must preserve spaces in nickname: " + announce);

                // Subsequent standard message from sender must use the multi-word nickname
                sender.webSocket().sendText("Action equals reaction.", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String chatMsg = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(chatMsg);
                assertTrue(chatMsg.contains("[" + multiWordNick + "] Action equals reaction."),
                        "Sender tag must display full multi-word nickname: " + chatMsg);

            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    @Test
    @DisplayName("Challenge 3b: /nick with trailing spaces or no name falls through to normal broadcast safely")
    void testNickCommandEdgeCasesSafeFallthrough() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // 1. /nick with multiple trailing spaces and no name
                sender.webSocket().sendText("/nick     ", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String msg1 = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(msg1);
                assertTrue(msg1.contains("/nick"), "Should broadcast /nick verbatim: " + msg1);

                // 2. /nick without space
                sender.webSocket().sendText("/nick", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String msg2 = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(msg2);
                assertTrue(msg2.contains("/nick"), "Should broadcast /nick verbatim: " + msg2);

                // 3. Verify sender name was NOT corrupted to empty or blank
                sender.webSocket().sendText("Still here!", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String msg3 = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(msg3);
                assertTrue(msg3.contains("User ") && msg3.contains("Still here!"),
                        "Sender name should remain valid default User id: " + msg3);

            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    @Test
    @DisplayName("Challenge 3c: Extra long message (>65KB) crosses 16-bit extended length boundary and delivers intact")
    void testExtraLongMessageExtendedLengthSixtyFourKilobytes() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Generate 70,000-character payload (>65,535 bytes to trigger 64-bit frame header)
                int payloadSize = 70_000;
                StringBuilder sb = new StringBuilder(payloadSize);
                sb.append("[LargePayload] ");
                while (sb.length() < payloadSize) {
                    sb.append("Bedrock-Java21-Loom-WebSockets-");
                }
                String largeMessage = sb.substring(0, payloadSize);

                sender.webSocket().sendText(largeMessage, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String received = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received, "Receiver must receive 70KB message without truncation or timeout");
                assertTrue(received.contains("[LargePayload] "), "Must preserve payload prefix");
                assertEquals(largeMessage.length(), received.length() - received.indexOf("[LargePayload] "),
                        "Received payload length must match sent message length exactly");

            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    // =========================================================================
    // Challenge 4: Server Teardown (Close 1001 Going Away & Port Release)
    // =========================================================================

    @Test
    @DisplayName("Challenge 4: app.stop() cleanly closes multiple connected clients with status 1001 and releases ports")
    void testAppStopNotifiesAllClientsWithClose1001AndReleasesPort() throws Exception {
        BedrockApp app = Application.createApp(0, 0);
        app.start();

        int httpPort = app.getPort();
        int wsPort = app.getWebSocketPort();

        int clientCount = 4;
        List<TestClient> clients = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            clients.add(connectClient(wsPort, "/chat"));
        }

        // Drain welcomes
        for (TestClient c : clients) {
            while (c.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
        }

        // Trigger coordinated application stop
        app.stop();

        // All 4 clients must receive RFC 6455 Close status 1001 (GOING_AWAY)
        for (int i = 0; i < clientCount; i++) {
            TestClient c = clients.get(i);
            assertTrue(c.closeLatch().await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Client #" + i + " must receive close event on server stop");
            assertEquals(WebSocketCloseStatus.GOING_AWAY_CODE, c.closeCode().get(),
                    "Client #" + i + " must receive Close status 1001 (GOING_AWAY)");
        }

        assertFalse(app.isRunning(), "BedrockApp must report isRunning() == false");

        // Verify WebSocket port is released immediately and can be rebound without BindException
        try (ServerSocketChannel testChannel = ServerSocketChannel.open()) {
            testChannel.bind(new InetSocketAddress("127.0.0.1", wsPort));
            assertTrue(testChannel.isOpen(), "WebSocket port must be freed for immediate rebinding");
        }
    }
}
