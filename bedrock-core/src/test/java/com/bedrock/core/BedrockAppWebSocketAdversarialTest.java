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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🥋 ADVERSARIAL STRESS & EDGE-CASE TEST SUITE FOR MILESTONE 3
 *
 * <p>Exhaustively probes failure modes, race conditions, parameter permutations,
 * inheritance hierarchies, malformed declarations, high-load concurrency,
 * and lifecycle resource leaks for BedrockApp and the WebSocket annotation subsystem.</p>
 */
public class BedrockAppWebSocketAdversarialTest {

    // =========================================================================
    // SECTION 1: Parameter Permutation Fixtures
    // =========================================================================

    // Permutation A: (String msg, BedrockWebSocketSession session) - Swapped order
    @BedrockSocket("/perm-swapped-msg")
    public static class SwappedMessageSocket {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        volatile String capturedSessionId;

        @OnMessage
        public void handleMessage(String text, BedrockWebSocketSession session) {
            this.capturedSessionId = session.getId();
            this.messages.offer(text);
            session.send("REPLY:" + text);
        }
    }

    // Permutation B: (String msg) - Only String parameter
    @BedrockSocket("/perm-string-only")
    public static class StringOnlyMessageSocket {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnMessage
        public void handleMessage(String text) {
            this.messages.offer(text);
        }
    }

    // Permutation C: (BedrockWebSocketSession session) - Only session parameter
    @BedrockSocket("/perm-session-only")
    public static class SessionOnlyMessageSocket {
        final AtomicInteger count = new AtomicInteger(0);

        @OnMessage
        public void handleMessage(BedrockWebSocketSession session) {
            count.incrementAndGet();
            session.send("ACK");
        }
    }

    // Permutation D: @OnClose with fully reversed arguments: (String reason, BedrockWebSocketSession session, int code)
    @BedrockSocket("/perm-close-swapped")
    public static class SwappedCloseSocket {
        final CompletableFuture<String> closeFuture = new CompletableFuture<>();

        @OnClose
        public void onClose(String reason, BedrockWebSocketSession session, int code) {
            closeFuture.complete("reason=" + reason + ";code=" + code + ";session=" + (session != null));
        }
    }

    // Permutation E: @OnClose with only (int code)
    @BedrockSocket("/perm-close-code-only")
    public static class CodeOnlyCloseSocket {
        final CompletableFuture<Integer> codeFuture = new CompletableFuture<>();

        @OnClose
        public void onClose(int code) {
            codeFuture.complete(code);
        }
    }

    // Permutation F: @OnClose with only ()
    @BedrockSocket("/perm-close-no-args")
    public static class NoArgsCloseSocket {
        final AtomicBoolean closed = new AtomicBoolean(false);

        @OnClose
        public void onClose() {
            closed.set(true);
        }
    }

    // Permutation G: @OnError with swapped arguments: (Throwable t, BedrockWebSocketSession session)
    @BedrockSocket("/perm-error-swapped")
    public static class SwappedErrorSocket {
        final CompletableFuture<String> errorFuture = new CompletableFuture<>();

        @OnMessage
        public void onMessage(String msg) {
            if ("FAIL".equals(msg)) {
                throw new IllegalStateException("Intentional adversarial explosion");
            }
        }

        @OnError
        public void onError(Throwable t, BedrockWebSocketSession session) {
            errorFuture.complete("err=" + t.getMessage() + ";hasSession=" + (session != null));
        }
    }

    // Permutation H: @OnError with (Exception e, BedrockWebSocketSession session) - Subclass of Throwable
    @BedrockSocket("/perm-error-subclass")
    public static class ExceptionSubclassErrorSocket {
        final CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();

        @OnMessage
        public void onMessage(String msg) {
            throw new RuntimeException("Subclass exception test");
        }

        @OnError
        public void onError(Exception e, BedrockWebSocketSession session) {
            errorFuture.complete(e);
        }
    }

    // Permutation I: @OnError with zero arguments: ()
    @BedrockSocket("/perm-error-no-args")
    public static class NoArgsErrorSocket {
        final AtomicBoolean errorTriggered = new AtomicBoolean(false);

        @OnMessage
        public void onMessage(String msg) {
            throw new RuntimeException("Crash");
        }

        @OnError
        public void onError() {
            errorTriggered.set(true);
        }
    }

    // Permutation J: @OnOpen with zero arguments: ()
    @BedrockSocket("/perm-open-no-args")
    public static class NoArgsOpenSocket {
        final AtomicBoolean openTriggered = new AtomicBoolean(false);

        @OnOpen
        public void onOpen() {
            openTriggered.set(true);
        }
    }

    // Permutation K: Endpoint with zero lifecycle methods
    @BedrockSocket("/perm-empty-lifecycle")
    public static class EmptyLifecycleSocket {
        // No @OnOpen, @OnMessage, @OnClose, @OnError
    }

    // Permutation L: Private lifecycle methods (testing makeAccessible on private methods)
    @BedrockSocket("/perm-private-methods")
    public static class PrivateMethodsSocket {
        final AtomicBoolean opened = new AtomicBoolean(false);
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnOpen
        private void onOpen(BedrockWebSocketSession session) {
            opened.set(true);
        }

        @OnMessage
        private void onMessage(BedrockWebSocketSession session, String text) {
            messages.offer(text);
            session.send("PRIVATE_ECHO:" + text);
        }
    }

    // Permutation M: Inheritance - Subclass inherits @OnMessage from BaseClass without re-declaring
    public static class BaseSocket {
        final BlockingQueue<String> baseMessages = new LinkedBlockingQueue<>();

        @OnMessage
        public void handle(String msg, BedrockWebSocketSession session) {
            baseMessages.offer(msg);
            session.send("BASE:" + msg);
        }
    }

    @BedrockSocket("/perm-subclass-inherit")
    public static class InheritingSocket extends BaseSocket {
        // Inherits handle() from BaseSocket
    }

    // Permutation N: Subclass overrides @OnMessage with @Override and re-annotates (valid override)
    public static class BaseOverrideSocket {
        @OnMessage
        public void handle(String msg) {
            throw new UnsupportedOperationException("Base should not be called");
        }
    }

    @BedrockSocket("/perm-subclass-override")
    public static class OverridingSocket extends BaseOverrideSocket {
        final BlockingQueue<String> overriddenMessages = new LinkedBlockingQueue<>();

        @Override
        @OnMessage
        public void handle(String msg) {
            overriddenMessages.offer(msg);
        }
    }

    // =========================================================================
    // SECTION 2: Malformed Class Fixtures for Fail-Fast Testing
    // =========================================================================

    @BedrockSocket("/malformed-onopen-2params")
    public static class MalformedOnOpenTwoParams {
        @OnOpen
        public void open(BedrockWebSocketSession s, String extra) {}
    }

    @BedrockSocket("/malformed-onopen-badtype")
    public static class MalformedOnOpenBadType {
        @OnOpen
        public void open(String invalid) {}
    }

    @BedrockSocket("/malformed-onmessage-0params")
    public static class MalformedOnMessageZeroParams {
        @OnMessage
        public void msg() {}
    }

    @BedrockSocket("/malformed-onmessage-3params")
    public static class MalformedOnMessageThreeParams {
        @OnMessage
        public void msg(BedrockWebSocketSession s, String text, String extra) {}
    }

    @BedrockSocket("/malformed-onmessage-dupe-strings")
    public static class MalformedOnMessageDuplicateStrings {
        @OnMessage
        public void msg(String a, String b) {}
    }

    @BedrockSocket("/malformed-onmessage-dupe-sessions")
    public static class MalformedOnMessageDuplicateSessions {
        @OnMessage
        public void msg(BedrockWebSocketSession a, BedrockWebSocketSession b) {}
    }

    @BedrockSocket("/malformed-onclose-4params")
    public static class MalformedOnCloseFourParams {
        @OnClose
        public void close(BedrockWebSocketSession s, int code, String reason, String extra) {}
    }

    @BedrockSocket("/malformed-onclose-dupe-code")
    public static class MalformedOnCloseDuplicateCode {
        @OnClose
        public void close(int code1, int code2) {}
    }

    @BedrockSocket("/malformed-onclose-badtype")
    public static class MalformedOnCloseBadType {
        @OnClose
        public void close(boolean invalid) {}
    }

    @BedrockSocket("/malformed-onerror-3params")
    public static class MalformedOnErrorThreeParams {
        @OnError
        public void error(BedrockWebSocketSession s, Throwable t, String extra) {}
    }

    @BedrockSocket("/malformed-onerror-dupe-throwable")
    public static class MalformedOnErrorDuplicateThrowable {
        @OnError
        public void error(Throwable t1, Throwable t2) {}
    }

    @BedrockSocket("/malformed-onerror-badtype")
    public static class MalformedOnErrorBadType {
        @OnError
        public void error(String notAThrowable) {}
    }

    // Subclass overload instead of override (different params, same name)
    public static class BaseOverloadSocket {
        @OnMessage
        public void handle(String msg) {}
    }

    @BedrockSocket("/malformed-subclass-overload")
    public static class SubclassOverloadSocket extends BaseOverloadSocket {
        @OnMessage
        public void handle(String msg, BedrockWebSocketSession session) {}
    }


    // =========================================================================
    // TESTS: Parameter Permutations & Signatures
    // =========================================================================

    @Test
    @DisplayName("Permutation: @OnMessage with (String, BedrockWebSocketSession) swapped parameter order")
    void testOnMessageSwappedParameters() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(SwappedMessageSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-swapped-msg"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            replies.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            ws.sendText("SwappedParamsTest", true).get(5, TimeUnit.SECONDS);
            String reply = replies.poll(5, TimeUnit.SECONDS);
            assertEquals("REPLY:SwappedParamsTest", reply);

            SwappedMessageSocket socket = app.getContainer().getBean(SwappedMessageSocket.class);
            assertEquals("SwappedParamsTest", socket.messages.poll(5, TimeUnit.SECONDS));
            assertNotNull(socket.capturedSessionId, "Session must be injected even when 2nd argument");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: @OnMessage with only (String text) parameter")
    void testOnMessageStringOnlyParameter() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(StringOnlyMessageSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-string-only"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendText("MessageOnly", true).get(5, TimeUnit.SECONDS);

            StringOnlyMessageSocket socket = app.getContainer().getBean(StringOnlyMessageSocket.class);
            assertEquals("MessageOnly", socket.messages.poll(5, TimeUnit.SECONDS));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: @OnMessage with only (BedrockWebSocketSession session) parameter")
    void testOnMessageSessionOnlyParameter() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(SessionOnlyMessageSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-session-only"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            replies.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            ws.sendText("IgnoreContent", true).get(5, TimeUnit.SECONDS);
            assertEquals("ACK", replies.poll(5, TimeUnit.SECONDS));

            SessionOnlyMessageSocket socket = app.getContainer().getBean(SessionOnlyMessageSocket.class);
            assertEquals(1, socket.count.get());

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: @OnClose with swapped arguments (String reason, BedrockWebSocketSession session, int code)")
    void testOnCloseSwappedArguments() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(SwappedCloseSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-close-swapped"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Departing").get(5, TimeUnit.SECONDS);

            SwappedCloseSocket socket = app.getContainer().getBean(SwappedCloseSocket.class);
            String closeRecord = socket.closeFuture.get(5, TimeUnit.SECONDS);
            assertEquals("reason=Departing;code=1000;session=true", closeRecord);
        }
    }

    @Test
    @DisplayName("Permutation: @OnClose with single argument (int code)")
    void testOnCloseCodeOnly() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(CodeOnlyCloseSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-close-code-only"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye").get(5, TimeUnit.SECONDS);

            CodeOnlyCloseSocket socket = app.getContainer().getBean(CodeOnlyCloseSocket.class);
            int code = socket.codeFuture.get(5, TimeUnit.SECONDS);
            assertEquals(1000, code);
        }
    }

    @Test
    @DisplayName("Permutation: @OnClose with zero arguments ()")
    void testOnCloseNoArgs() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(NoArgsCloseSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-close-no-args"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye").get(5, TimeUnit.SECONDS);

            NoArgsCloseSocket socket = app.getContainer().getBean(NoArgsCloseSocket.class);
            // Allow virtual thread to process close
            for (int i = 0; i < 50 && !socket.closed.get(); i++) {
                Thread.sleep(50);
            }
            assertTrue(socket.closed.get(), "@OnClose with zero arguments must be triggered");
        }
    }

    @Test
    @DisplayName("Permutation: @OnError with swapped arguments (Throwable t, BedrockWebSocketSession session)")
    void testOnErrorSwappedArguments() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(SwappedErrorSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-error-swapped"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendText("FAIL", true).get(5, TimeUnit.SECONDS);

            SwappedErrorSocket socket = app.getContainer().getBean(SwappedErrorSocket.class);
            String result = socket.errorFuture.get(5, TimeUnit.SECONDS);
            assertEquals("err=Intentional adversarial explosion;hasSession=true", result);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: @OnError with Exception subclass argument")
    void testOnErrorSubclassArgument() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ExceptionSubclassErrorSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-error-subclass"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendText("TRIGGER", true).get(5, TimeUnit.SECONDS);

            ExceptionSubclassErrorSocket socket = app.getContainer().getBean(ExceptionSubclassErrorSocket.class);
            Throwable t = socket.errorFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(t);
            assertEquals("Subclass exception test", t.getMessage());

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: @OnError with zero arguments ()")
    void testOnErrorNoArgs() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(NoArgsErrorSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-error-no-args"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendText("TRIGGER", true).get(5, TimeUnit.SECONDS);

            NoArgsErrorSocket socket = app.getContainer().getBean(NoArgsErrorSocket.class);
            for (int i = 0; i < 50 && !socket.errorTriggered.get(); i++) {
                Thread.sleep(50);
            }
            assertTrue(socket.errorTriggered.get(), "@OnError with 0 args must be triggered");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: @OnOpen with zero arguments ()")
    void testOnOpenNoArgs() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(NoArgsOpenSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-open-no-args"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            NoArgsOpenSocket socket = app.getContainer().getBean(NoArgsOpenSocket.class);
            for (int i = 0; i < 50 && !socket.openTriggered.get(); i++) {
                Thread.sleep(50);
            }
            assertTrue(socket.openTriggered.get(), "@OnOpen with 0 args must be triggered");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: Endpoint with empty lifecycle (zero annotated methods)")
    void testEmptyLifecycleSocket() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(EmptyLifecycleSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-empty-lifecycle"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            // Sending message should be dropped safely without exception
            assertDoesNotThrow(() -> ws.sendText("Hello", true).get(5, TimeUnit.SECONDS));
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: Private lifecycle methods made accessible reflectively")
    void testPrivateLifecycleMethods() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(PrivateMethodsSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-private-methods"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            replies.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            PrivateMethodsSocket socket = app.getContainer().getBean(PrivateMethodsSocket.class);
            for (int i = 0; i < 50 && !socket.opened.get(); i++) {
                Thread.sleep(50);
            }
            assertTrue(socket.opened.get(), "Private @OnOpen should be invoked");

            ws.sendText("SecretMessage", true).get(5, TimeUnit.SECONDS);
            assertEquals("PRIVATE_ECHO:SecretMessage", replies.poll(5, TimeUnit.SECONDS));
            assertEquals("SecretMessage", socket.messages.poll(5, TimeUnit.SECONDS));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: Subclass inherits @OnMessage from parent class without re-declaring")
    void testSubclassInheritsLifecycleMethod() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(InheritingSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-subclass-inherit"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            replies.offer(data.toString());
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            ws.sendText("InheritanceTest", true).get(5, TimeUnit.SECONDS);
            assertEquals("BASE:InheritanceTest", replies.poll(5, TimeUnit.SECONDS));

            InheritingSocket socket = app.getContainer().getBean(InheritingSocket.class);
            assertEquals("InheritanceTest", socket.baseMessages.poll(5, TimeUnit.SECONDS));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Permutation: Subclass overrides @OnMessage with @Override and re-annotates")
    void testSubclassOverridesLifecycleMethod() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(OverridingSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/perm-subclass-override"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendText("OverridePayload", true).get(5, TimeUnit.SECONDS);

            OverridingSocket socket = app.getContainer().getBean(OverridingSocket.class);
            assertEquals("OverridePayload", socket.overriddenMessages.poll(5, TimeUnit.SECONDS));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }


    // =========================================================================
    // TESTS: Malformed Endpoints & Fail-Fast Validation
    // =========================================================================

    @Test
    @DisplayName("Validation: @OnOpen with 2 parameters throws BedrockException")
    void testMalformedOnOpenTwoParamsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnOpenTwoParams()));
    }

    @Test
    @DisplayName("Validation: @OnOpen with invalid parameter type throws BedrockException")
    void testMalformedOnOpenBadTypeThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnOpenBadType()));
    }

    @Test
    @DisplayName("Validation: @OnMessage with 0 parameters throws BedrockException")
    void testMalformedOnMessageZeroParamsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnMessageZeroParams()));
    }

    @Test
    @DisplayName("Validation: @OnMessage with 3 parameters throws BedrockException")
    void testMalformedOnMessageThreeParamsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnMessageThreeParams()));
    }

    @Test
    @DisplayName("Validation: @OnMessage with duplicate String parameters throws BedrockException")
    void testMalformedOnMessageDuplicateStringsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnMessageDuplicateStrings()));
    }

    @Test
    @DisplayName("Validation: @OnMessage with duplicate Session parameters throws BedrockException")
    void testMalformedOnMessageDuplicateSessionsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnMessageDuplicateSessions()));
    }

    @Test
    @DisplayName("Validation: @OnClose with 4 parameters throws BedrockException")
    void testMalformedOnCloseFourParamsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnCloseFourParams()));
    }

    @Test
    @DisplayName("Validation: @OnClose with duplicate int status code parameters throws BedrockException")
    void testMalformedOnCloseDuplicateCodeThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnCloseDuplicateCode()));
    }

    @Test
    @DisplayName("Validation: @OnClose with unsupported parameter type throws BedrockException")
    void testMalformedOnCloseBadTypeThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnCloseBadType()));
    }

    @Test
    @DisplayName("Validation: @OnError with 3 parameters throws BedrockException")
    void testMalformedOnErrorThreeParamsThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnErrorThreeParams()));
    }

    @Test
    @DisplayName("Validation: @OnError with duplicate Throwable parameters throws BedrockException")
    void testMalformedOnErrorDuplicateThrowableThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnErrorDuplicateThrowable()));
    }

    @Test
    @DisplayName("Validation: @OnError with unsupported parameter type throws BedrockException")
    void testMalformedOnErrorBadTypeThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new MalformedOnErrorBadType()));
    }

    @Test
    @DisplayName("Validation: Subclass method overload with same annotation throws BedrockException")
    void testSubclassOverloadThrows() {
        assertThrows(BedrockException.class, () -> WebSocketEndpointScanner.scan(new SubclassOverloadSocket()));
    }


    // =========================================================================
    // TESTS: High Concurrency & Stress Scenarios
    // =========================================================================

    @BedrockSocket("/stress-echo")
    public static class StressEchoSocket {
        final AtomicInteger activeSessions = new AtomicInteger(0);
        final AtomicInteger totalMessagesReceived = new AtomicInteger(0);

        @OnOpen
        public void onOpen(BedrockWebSocketSession session) {
            activeSessions.incrementAndGet();
        }

        @OnMessage
        public void onMessage(BedrockWebSocketSession session, String text) {
            totalMessagesReceived.incrementAndGet();
            session.send("ACK:" + text);
        }

        @OnClose
        public void onClose(BedrockWebSocketSession session) {
            activeSessions.decrementAndGet();
        }
    }

    @Test
    @DisplayName("Stress: 25 concurrent clients connecting simultaneously, exchanging messages, and disconnecting cleanly")
    void testConcurrentClientsStress() throws Exception {
        int clientCount = 25;
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(StressEchoSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient httpClient = HttpClient.newHttpClient();
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(clientCount);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < clientCount; i++) {
                final int clientId = i;
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    BlockingQueue<String> replies = new LinkedBlockingQueue<>();
                    WebSocket ws = httpClient.newWebSocketBuilder()
                            .buildAsync(URI.create("ws://localhost:" + port + "/stress-echo"), new WebSocket.Listener() {
                                @Override
                                public void onOpen(WebSocket webSocket) {
                                    webSocket.request(Long.MAX_VALUE);
                                }
                                @Override
                                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                    replies.offer(data.toString());
                                    return null;
                                }
                            }).get(10, TimeUnit.SECONDS);

                    // Send 5 messages sequentially per client
                    for (int m = 0; m < 5; m++) {
                        String payload = "C" + clientId + "-M" + m;
                        ws.sendText(payload, true).get(5, TimeUnit.SECONDS);
                        String ack = replies.poll(5, TimeUnit.SECONDS);
                        if (!("ACK:" + payload).equals(ack)) {
                            return false;
                        }
                    }

                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
                    doneLatch.countDown();
                    return true;
                }));
            }

            // Fire all virtual threads at once
            startLatch.countDown();
            assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "All 25 clients must complete within 20s");

            for (Future<Boolean> f : futures) {
                assertTrue(f.get(), "Each client task must return true");
            }

            StressEchoSocket socket = app.getContainer().getBean(StressEchoSocket.class);
            assertEquals(clientCount * 5, socket.totalMessagesReceived.get(), "Total messages must equal clientCount * 5");

            // Verify registry cleans up sessions
            WebSocketSessionRegistry registry = app.getWebSocketServer().getSessionRegistry();
            for (int i = 0; i < 50 && registry.size() > 0; i++) {
                Thread.sleep(50);
            }
            assertEquals(0, registry.size(), "All sessions must be unregistered from session registry");
        }
    }

    @Test
    @DisplayName("Stress: Rapid connect-and-close sequences across 20 iterations")
    void testRapidConnectAndClose() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(StressEchoSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient httpClient = HttpClient.newHttpClient();

            for (int i = 0; i < 20; i++) {
                CountDownLatch openLatch = new CountDownLatch(1);
                CountDownLatch closeLatch = new CountDownLatch(1);

                WebSocket ws = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:" + port + "/stress-echo"), new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                openLatch.countDown();
                                webSocket.request(1);
                            }
                            @Override
                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                closeLatch.countDown();
                                return null;
                            }
                        }).get(5, TimeUnit.SECONDS);

                assertTrue(openLatch.await(5, TimeUnit.SECONDS));
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Quick").get(5, TimeUnit.SECONDS);
                assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
            }

            assertEquals(0, app.getWebSocketServer().getSessionRegistry().size(), "Session registry must be empty after rapid churn");
        }
    }

    @Test
    @DisplayName("Stress: Large payload transmission (64 KB text message)")
    void testLargePayloadTransfer() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(StressEchoSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient client = HttpClient.newHttpClient();
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();

            StringBuilder textAccumulator = new StringBuilder();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/stress-echo"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            textAccumulator.append(data);
                            if (last) {
                                replies.offer(textAccumulator.toString());
                                textAccumulator.setLength(0);
                            }
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);

            // Generate 64 KB payload
            String largePayload = "X".repeat(65536);
            ws.sendText(largePayload, true).get(10, TimeUnit.SECONDS);

            String reply = replies.poll(10, TimeUnit.SECONDS);
            assertNotNull(reply, "Reply must not be null");
            assertEquals("ACK:" + largePayload, reply, "Server must echo full 64 KB payload without corruption");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }


    // =========================================================================
    // TESTS: Lifecycle, Port Reclamation & Error Handling
    // =========================================================================

    @Test
    @DisplayName("Lifecycle: Stopping app with active clients notifies clients with 1001 Going Away")
    void testStopWithMultipleActiveClientsNotifiesAll() throws Exception {
        int clientCount = 5;
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(StressEchoSocket.class).start();
            int port = app.getWebSocketPort();

            HttpClient httpClient = HttpClient.newHttpClient();
            CountDownLatch allOpenLatch = new CountDownLatch(clientCount);
            CountDownLatch allCloseLatch = new CountDownLatch(clientCount);
            ConcurrentLinkedQueue<Integer> closeCodes = new ConcurrentLinkedQueue<>();

            List<WebSocket> sockets = new ArrayList<>();
            for (int i = 0; i < clientCount; i++) {
                WebSocket ws = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:" + port + "/stress-echo"), new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                allOpenLatch.countDown();
                                webSocket.request(1);
                            }
                            @Override
                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                closeCodes.add(statusCode);
                                allCloseLatch.countDown();
                                return null;
                            }
                        }).get(5, TimeUnit.SECONDS);
                sockets.add(ws);
            }

            assertTrue(allOpenLatch.await(5, TimeUnit.SECONDS), "All clients must connect");

            // Shut down server while all 5 clients are active
            app.stop();

            assertTrue(allCloseLatch.await(5, TimeUnit.SECONDS), "All clients must receive close notification from server");
            assertEquals(clientCount, closeCodes.size());
            for (int code : closeCodes) {
                assertEquals(WebSocketCloseStatus.GOING_AWAY_CODE, code, "Close code must be 1001 Going Away");
            }
        }
    }

    @Test
    @DisplayName("Lifecycle: ServerSocket port can be immediately rebound by another socket after app.stop()")
    void testImmediatePortRebindAfterStop() throws Exception {
        int wsPort;
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(StressEchoSocket.class).start();
            wsPort = app.getWebSocketPort();
            assertTrue(wsPort > 0);
            app.stop();
        }

        // Must be immediately re-bindable without BindException
        try (ServerSocketChannel testCh = ServerSocketChannel.open()) {
            testCh.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            assertDoesNotThrow(() -> testCh.bind(new InetSocketAddress("127.0.0.1", wsPort)),
                    "Port " + wsPort + " must be immediately available after stop()");
        }
    }

    @Test
    @DisplayName("Lifecycle: Dynamic enableWebSockets after app.start() starts WebSocket server immediately")
    void testDynamicEnableWebSocketsAfterStart() throws Exception {
        try (BedrockApp app = BedrockApp.create(0)) {
            app.register(StressEchoSocket.class);
            app.start(); // Started with only HTTP server

            assertTrue(app.isRunning());
            assertNull(app.getWebSocketServer(), "WebSocket server should be null before enableWebSockets");

            // Now enable WebSockets dynamically while running
            app.enableWebSockets(0);

            assertNotNull(app.getWebSocketServer(), "WebSocket server should be instantiated");
            assertTrue(app.getWebSocketServer().isRunning(), "WebSocket server should auto-start if app is running");

            int port = app.getWebSocketPort();
            assertTrue(port > 0);

            // Connect client to verify it works immediately
            HttpClient client = HttpClient.newHttpClient();
            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/stress-echo"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }
}
