package com.bedrock.example;

import com.bedrock.core.BedrockApp;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.server.BedrockWebSocketSession;
import com.bedrock.example.ws.ChatWebSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Milestone 4 Adversarial Verification Suite
 *
 * <p>Stress tests and adversarially challenges the {@link ChatWebSocket} implementation in
 * {@code bedrock-example}, specifically verifying:
 * <ul>
 *   <li><b>Burst Concurrency & Virtual Threads</b>: Hundreds of rapid-fire messages without frame tearing.</li>
 *   <li><b>Unicode, UTF-8 & Edge Case Strings</b>: Emojis, RTL, diacritics, CJK, whitespace, and empty strings.</li>
 *   <li><b>Abrupt Disconnects</b>: TCP aborts during broadcast fanout without stalling healthy clients.</li>
 *   <li><b>Session Registry Thread Safety</b>: Concurrent access, mutations, and query invariants.</li>
 * </ul>
 * </p>
 */
public class ChatWebSocketAdversarialTest {

    private static final int TIMEOUT_SECONDS = 5;

    /**
     * Helper client wrapping standard JDK 21 WebSocket client.
     */
    private record TestClient(WebSocket webSocket, BlockingQueue<String> messages, CountDownLatch openLatch) {
        public void close() {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test Complete")
                         .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        public void abort() {
            try {
                webSocket.abort();
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
    // Category 1: Burst Concurrency & Stress Conditions (Virtual Threads)
    // =========================================================================

    @Test
    @DisplayName("Adv-1.1: 10 clients concurrently sending 10 burst messages (100 total) without tearing or dropped frames")
    void testBurstConcurrencyTenClientsSimultaneousBroadcast() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            int clientCount = 10;
            int messagesPerClient = 10;
            int totalBroadcasts = clientCount * messagesPerClient;

            List<TestClient> clients = new CopyOnWriteArrayList<>();
            try {
                for (int i = 0; i < clientCount; i++) {
                    clients.add(connectClient(wsPort, "/chat"));
                }

                // Drain initial welcomes and join notifications
                for (TestClient c : clients) {
                    while (c.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                }

                // Concurrently send messages from all clients using virtual threads
                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(clientCount);

                for (int c = 0; c < clientCount; c++) {
                    final int clientId = c;
                    final TestClient client = clients.get(c);
                    Thread.ofVirtual().name("burst-sender-", clientId).start(() -> {
                        try {
                            startGate.await();
                            for (int m = 0; m < messagesPerClient; m++) {
                                client.webSocket().sendText("[C" + clientId + "] Msg #" + m, true);
                            }
                        } catch (Exception e) {
                            fail("Burst sender encountered error: " + e.getMessage());
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                startGate.countDown();
                assertTrue(doneGate.await(10, TimeUnit.SECONDS), "All senders must complete sending");

                // Each client must receive all 100 broadcast messages
                for (int i = 0; i < clientCount; i++) {
                    TestClient c = clients.get(i);
                    int receivedCount = 0;
                    long deadline = System.currentTimeMillis() + 8000;
                    while (receivedCount < totalBroadcasts && System.currentTimeMillis() < deadline) {
                        String msg = c.messages().poll(200, TimeUnit.MILLISECONDS);
                        if (msg != null && msg.contains("[C") && msg.contains("] Msg #")) {
                            receivedCount++;
                        }
                    }
                    assertEquals(totalBroadcasts, receivedCount,
                            "Client #" + i + " must receive all " + totalBroadcasts + " broadcast messages");
                }
            } finally {
                for (TestClient c : clients) {
                    c.close();
                }
            }
        }
    }

    @Test
    @DisplayName("Adv-1.2: Single client 50 rapid-fire messages arrives at peer in strict FIFO order")
    void testSingleClientHighFrequencyBurstFiftyMessages() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                // Drain initial system messages
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                int burstCount = 50;
                for (int i = 0; i < burstCount; i++) {
                    sender.webSocket().sendText("[Seq] #" + i, true);
                }

                for (int i = 0; i < burstCount; i++) {
                    String msg = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(msg, "Must receive message #" + i);
                    assertTrue(msg.contains("[Seq] #" + i),
                            "Strict FIFO ordering check failed at index " + i + ": got '" + msg + "'");
                }
            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    // =========================================================================
    // Category 2: Unicode, Multi-Byte UTF-8, Emojis & Edge-Case Messages
    // =========================================================================

    @Test
    @DisplayName("Adv-2.1: Empty, blank, and whitespace messages are ignored without crashing or broadcasting")
    void testEmptyAndWhitespaceMessagesAreSilentlyIgnored() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Send various blank/whitespace payloads
                sender.webSocket().sendText("", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                sender.webSocket().sendText("   ", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                sender.webSocket().sendText("\t\n\r", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                sender.webSocket().sendText("  \n  \t  ", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Receiver should receive NOTHING
                String stray = receiver.messages().poll(500, TimeUnit.MILLISECONDS);
                assertNull(stray, "Blank messages must not be broadcast: received '" + stray + "'");

                // Normal message immediately afterwards still works
                sender.webSocket().sendText("[Test] Valid Payload", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String valid = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(valid, "Normal message after blank messages must be delivered");
                assertTrue(valid.contains("Valid Payload"));
            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    @Test
    @DisplayName("Adv-2.2: 4-byte emojis, CJK, Portuguese accents, Arabic RTL, and Math symbols survive framing intact")
    void testComplexUnicodeEmojisAndDiacriticsIntact() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                List<String> testPayloads = List.of(
                        "[Emojis] 🦖 🚀 💻 🌟 🎉 🔥 💖 👾 🤖 🍕 👨‍👩‍👧‍👦",
                        "[PT-BR] João comprou maçãs & café na praça com vovó em São Paulo!",
                        "[CJK] 日本語: こんにちは世界！ • 中文: 你好世界 • 한국어: 안녕하세요",
                        "[Arabic] مرحبا بالعالم - تجربة النص العربي المتقن",
                        "[Math] ∀x ∈ ℝ: x² ≥ 0 ∧ ∑(1/n) = ∞ ⇔ ∫ f(x)dx = C",
                        "[Formatting] %s %d %n %x %08x \\n \\t \\r"
                );

                for (String payload : testPayloads) {
                    sender.webSocket().sendText(payload, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    String received = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(received, "Must receive payload for: " + payload);
                    assertTrue(received.contains(payload),
                            "Payload corrupted in transmission. Expected: '" + payload + "', Got: '" + received + "'");
                }
            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    @Test
    @DisplayName("Adv-2.3: Dynamic /nick command updates username with Unicode emojis and subsequent messages reflect it")
    void testDynamicNicknameCommandWithUnicode() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Send /nick command
                sender.webSocket().sendText("/nick 🦖RexKing", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Both sender and receiver should receive the system notification
                String sysMsg = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(sysMsg);
                assertTrue(sysMsg.contains("[System]") && sysMsg.contains("is now known as 🦖RexKing"),
                        "System must announce nickname change: " + sysMsg);

                // Now sender sends a plain text message
                sender.webSocket().sendText("Hear me roar!", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String chatMsg = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(chatMsg);
                assertTrue(chatMsg.contains("[🦖RexKing] Hear me roar!"),
                        "Message must display updated nickname: " + chatMsg);
            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    @Test
    @DisplayName("Adv-2.4: /nick edge cases (empty nick, trailing spaces) fall through to normal broadcast")
    void testNickCommandEdgeCasesAndFallthrough() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient sender = connectClient(wsPort, "/chat");
            TestClient receiver = connectClient(wsPort, "/chat");

            try {
                while (sender.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (receiver.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Send /nick with only whitespace after
                sender.webSocket().sendText("/nick   ", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String received = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received);
                assertTrue(received.contains("/nick"), "Should broadcast verbatim if nick was blank: " + received);

                // Send just /nick without arguments
                sender.webSocket().sendText("/nick", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String received2 = receiver.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received2);
                assertTrue(received2.contains("/nick"), "Should broadcast verbatim: " + received2);
            } finally {
                sender.close();
                receiver.close();
            }
        }
    }

    // =========================================================================
    // Category 3: Abrupt Disconnects During Active Broadcast Fanout
    // =========================================================================

    @Test
    @DisplayName("Adv-3.1: Abrupt client disconnects (TCP socket aborts) during broadcast do not disrupt surviving clients")
    void testAbruptClientDisconnectsDuringActiveBroadcastFanout() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            int clientCount = 5;
            List<TestClient> clients = new ArrayList<>();

            for (int i = 0; i < clientCount; i++) {
                clients.add(connectClient(wsPort, "/chat"));
            }

            try {
                for (TestClient c : clients) {
                    while (c.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                }

                // Abruptly abort client 1 and client 2 (simulating hard TCP drop / RST)
                clients.get(1).abort();
                clients.get(2).abort();
                Thread.sleep(100);

                // Client 0 broadcasts a message
                String testBroadcast = "[Surviving] Can everyone hear me?";
                clients.get(0).webSocket().sendText(testBroadcast, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Surviving clients (0, 3, 4) must receive the broadcast without timeout
                for (int i : new int[]{0, 3, 4}) {
                    String msg = null;
                    while (true) {
                        String m = clients.get(i).messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        assertNotNull(m, "Surviving client #" + i + " must receive broadcast");
                        if (m.contains("Can everyone hear me?")) {
                            msg = m;
                            break;
                        }
                    }
                    assertNotNull(msg, "Client #" + i + " must receive proper content: " + msg);
                }
            } finally {
                for (TestClient c : clients) {
                    c.close();
                }
            }
        }
    }

    // =========================================================================
    // Category 4: Thread Safety & Unit Verification of ChatWebSocket
    // =========================================================================

    @Test
    @DisplayName("Adv-4.1: ChatWebSocket handles 20 concurrent virtual threads manipulating sessions and broadcasting")
    void testConcurrentRegistryOperationsUnderHighContention() throws Exception {
        ChatWebSocket chatWebSocket = new ChatWebSocket();
        int threadCount = 20;
        int operationsPerThread = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<MockSession> createdSessions = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread.ofVirtual().name("adv-concurrency-", threadId).start(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < operationsPerThread; op++) {
                        String sessionId = "thread-" + threadId + "-session-" + op;
                        MockSession session = new MockSession(sessionId);
                        createdSessions.add(session);

                        // 1. Open
                        chatWebSocket.onOpen(session);
                        assertTrue(chatWebSocket.hasSession(sessionId));

                        // 2. Message / Broadcast
                        chatWebSocket.onMessage(session, "Message from " + sessionId);
                        chatWebSocket.broadcast("[Broadcast] Heartbeat from " + sessionId);

                        // 3. Close
                        chatWebSocket.onClose(session, WebSocketCloseStatus.NORMAL_CLOSURE_CODE, "Done");
                        assertFalse(chatWebSocket.hasSession(sessionId));
                    }
                } catch (Exception e) {
                    fail("Concurrent stress failure: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All concurrent threads must finish without deadlocking");

        // After all sessions are closed, registry should be empty
        assertEquals(0, chatWebSocket.getConnectedCount(), "All sessions must be cleanly closed and removed");
        assertTrue(chatWebSocket.getSessions().isEmpty());
    }

    @Test
    @DisplayName("Adv-4.2: broadcast() handles faulty session throwing on send, prunes it, and continues to healthy sessions")
    void testBroadcastPrunesFaultySessionWithoutFailingRemainingSessions() {
        ChatWebSocket chatWebSocket = new ChatWebSocket();

        MockSession healthy1 = new MockSession("healthy-1");
        MockSession faulty = new MockSession("faulty-2") {
            @Override
            public void send(String message) {
                throw new IllegalStateException("Broken pipe connection simulation");
            }
        };
        MockSession healthy2 = new MockSession("healthy-3");

        chatWebSocket.onOpen(healthy1);
        chatWebSocket.onOpen(faulty);
        chatWebSocket.onOpen(healthy2);

        assertEquals(3, chatWebSocket.getConnectedCount());

        // Drain welcomes
        healthy1.sentMessages.clear();
        healthy2.sentMessages.clear();

        // Broadcast to all
        chatWebSocket.broadcast("[Alert] System update");

        // Healthy sessions must receive the alert
        assertTrue(healthy1.sentMessages.contains("[Alert] System update"));
        assertTrue(healthy2.sentMessages.contains("[Alert] System update"));

        // Faulty session must be automatically pruned from registry
        assertFalse(chatWebSocket.hasSession("faulty-2"), "Faulty session must be evicted from active registry");
        assertEquals(2, chatWebSocket.getConnectedCount(), "Only healthy sessions should remain");
        assertTrue(faulty.closed.get(), "Faulty session should have close() invoked");
    }

    @Test
    @DisplayName("Adv-4.3: broadcastExcept() reliably skips designated session ID and delivers to all others")
    void testBroadcastExceptExcludesSpecifiedSessionCleanly() {
        ChatWebSocket chatWebSocket = new ChatWebSocket();

        MockSession sessionA = new MockSession("session-A");
        MockSession sessionB = new MockSession("session-B");
        MockSession sessionC = new MockSession("session-C");

        chatWebSocket.onOpen(sessionA);
        chatWebSocket.onOpen(sessionB);
        chatWebSocket.onOpen(sessionC);

        sessionA.sentMessages.clear();
        sessionB.sentMessages.clear();
        sessionC.sentMessages.clear();

        chatWebSocket.broadcastExcept("[Echo] Hello others!", "session-B");

        assertTrue(sessionA.sentMessages.contains("[Echo] Hello others!"));
        assertTrue(sessionC.sentMessages.contains("[Echo] Hello others!"));
        assertFalse(sessionB.sentMessages.contains("[Echo] Hello others!"),
                "Excluded session-B must not receive the message");
    }

    @Test
    @DisplayName("Adv-4.4: onClose() is idempotent and subsequent calls do not cause underflow or error")
    void testIdempotentOnClose() {
        ChatWebSocket chatWebSocket = new ChatWebSocket();
        MockSession session = new MockSession("idempotent-test");

        chatWebSocket.onOpen(session);
        assertEquals(1, chatWebSocket.getConnectedCount());

        // First close
        chatWebSocket.onClose(session, WebSocketCloseStatus.NORMAL_CLOSURE_CODE, "First");
        assertEquals(0, chatWebSocket.getConnectedCount());

        // Duplicate close
        assertDoesNotThrow(() -> chatWebSocket.onClose(session, WebSocketCloseStatus.NORMAL_CLOSURE_CODE, "Duplicate"));
        assertEquals(0, chatWebSocket.getConnectedCount());
    }

    @Test
    @DisplayName("Adv-4.5: clear() resets registry safely")
    void testClearResetsRegistry() {
        ChatWebSocket chatWebSocket = new ChatWebSocket();
        chatWebSocket.onOpen(new MockSession("s1"));
        chatWebSocket.onOpen(new MockSession("s2"));
        assertEquals(2, chatWebSocket.getConnectedCount());

        chatWebSocket.clear();
        assertEquals(0, chatWebSocket.getConnectedCount());
        assertTrue(chatWebSocket.getSessions().isEmpty());
    }

    // =========================================================================
    // Mock Session Implementation for In-Memory Unit Stress Testing
    // =========================================================================

    private static class MockSession implements BedrockWebSocketSession {
        private final String id;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        public final List<String> sentMessages = new CopyOnWriteArrayList<>();

        public MockSession(String id) {
            this.id = id;
        }

        @Override public String getId() { return id; }
        @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public String getPath() { return "/chat"; }
        @Override public boolean isOpen() { return !closed.get(); }
        @Override public void send(String message) {
            if (!isOpen()) throw new IllegalStateException("Session closed");
            sentMessages.add(message);
        }
        @Override public void send(byte[] data) { if (!isOpen()) throw new IllegalStateException("Session closed"); }
        @Override public void sendPing(byte[] applicationData) { if (!isOpen()) throw new IllegalStateException("Session closed"); }
        @Override public void close() { closed.set(true); }
        @Override public void close(int statusCode, String reason) { closed.set(true); }
        @Override public void setAttribute(String name, Object value) {
            if (value == null) attributes.remove(name); else attributes.put(name, value);
        }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public Object removeAttribute(String name) { return attributes.remove(name); }
        @Override public Map<String, Object> getAttributes() { return Collections.unmodifiableMap(attributes); }
        @Override public void writeRaw(ByteBuffer buffer) throws IOException {}
        @Override public void markClosed() { closed.set(true); }
    }
}
