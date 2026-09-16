package com.bedrock.core.ws.server;

import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.protocol.WebSocketException;
import com.bedrock.core.ws.protocol.WebSocketFrame;
import com.bedrock.core.ws.protocol.WebSocketFrameParser;
import com.bedrock.core.ws.protocol.WebSocketFrameWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BedrockWebSocketFeaturesTest {

    @Test
    @DisplayName("Verify session extracts query parameters and negotiated subprotocol")
    void shouldExtractQueryParamsAndSubprotocol() throws Exception {
        AtomicReference<String> nickReceived = new AtomicReference<>();
        AtomicReference<String> subprotocolReceived = new AtomicReference<>();
        CompletableFuture<Void> openFuture = new CompletableFuture<>();

        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            var binding = WebSocketEndpointBinding.builder()
                    .subprotocols("chat.v1", "chat.v2")
                    .onOpen(session -> {
                        nickReceived.set(session.getQueryParam("nick"));
                        subprotocolReceived.set(session.getSubprotocol());
                        openFuture.complete(null);
                    })
                    .build();

            server.registerEndpoint("/chat", binding);
            server.start();

            int port = server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .subprotocols("chat.v1")
                    .buildAsync(URI.create("ws://localhost:" + port + "/chat?nick=Bob"), new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS);

            openFuture.get(5, TimeUnit.SECONDS);

            assertEquals("Bob", nickReceived.get());
            assertEquals("chat.v1", subprotocolReceived.get());
            assertEquals("chat.v1", ws.getSubprotocol());

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Verify maxPayloadSize enforcement in frame parser (RFC 6455 1009)")
    void shouldEnforceMaxPayloadSize() {
        // Build an unmasked 16-bit payload frame exceeding 100 bytes
        byte[] payload = new byte[200];
        byte[] frameBytes = WebSocketFrameWriter.createTextFrame(new String(payload));
        
        // FrameWriter produces unmasked frame. Let's create masked frame for server parser:
        ByteBuffer buf = ByteBuffer.allocate(frameBytes.length + 4);
        buf.put((byte) 0x81); // FIN + TEXT
        buf.put((byte) (0x80 | 126)); // MASK + 126
        buf.putShort((short) 200);
        byte[] maskKey = new byte[]{1, 2, 3, 4};
        buf.put(maskKey);
        for (int i = 0; i < 200; i++) {
            buf.put((byte) (payload[i] ^ maskKey[i % 4]));
        }
        buf.flip();

        // Expect WebSocketException with 1009 MESSAGE_TOO_BIG when max is 100 bytes
        WebSocketException ex = assertThrows(WebSocketException.class, () -> {
            WebSocketFrameParser.parse(buf, true, 100);
        });

        assertEquals(WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE, ex.getStatusCode());
    }

    @Test
    @DisplayName("Verify heartbeat keep-alive sends ping frames periodically")
    void shouldSendHeartbeatPings() throws Exception {
        CompletableFuture<Void> pingReceivedFuture = new CompletableFuture<>();

        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.enableHeartbeat(Duration.ofMillis(100));
            server.registerEndpoint("/ping-test", WebSocketEndpointBinding.builder().build());
            server.start();

            int port = server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ping-test"), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                            pingReceivedFuture.complete(null);
                            return WebSocket.Listener.super.onPing(webSocket, message);
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            // Wait for periodic ping from server
            pingReceivedFuture.get(5, TimeUnit.SECONDS);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5, TimeUnit.SECONDS);
        }
    }
}
