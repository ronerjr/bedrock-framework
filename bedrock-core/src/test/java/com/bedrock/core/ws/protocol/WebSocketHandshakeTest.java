package com.bedrock.core.ws.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite for {@link WebSocketHandshake}.
 *
 * Verifies RFC 6455 §1.3 and §4.2 specifications:
 * - Official test vectors for Sec-WebSocket-Accept computation.
 * - Robust HTTP header parsing and upgrade request verification.
 * - HTTP 101 Switching Protocols response byte formatting.
 */
class WebSocketHandshakeTest {

    private static final String VALID_HANDSHAKE_REQUEST =
            "GET /chat HTTP/1.1\r\n" +
            "Host: server.example.com\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Origin: http://example.com\r\n" +
            "Sec-WebSocket-Protocol: chat, superchat\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n";

    @Test
    @DisplayName("RFC 6455 §1.3: Compute Sec-WebSocket-Accept for official test vector")
    void shouldComputeOfficialRfc6455SecWebSocketAccept() {
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

        String actualAccept = WebSocketHandshake.computeAccept(clientKey);

        assertEquals(expectedAccept, actualAccept,
                "Sec-WebSocket-Accept must match the official RFC 6455 §1.3 test vector");
    }

    @Test
    @DisplayName("Compute Sec-WebSocket-Accept for secondary RFC 6455 test vector")
    void shouldComputeSecWebSocketAcceptForSecondaryVector() {
        String clientKey = "x3JJHMbDL1EzLkh9GBhXDw==";
        String expectedAccept = "HSmrc0sMlYUkAGmm5OPpG2HaGWk=";

        String actualAccept = WebSocketHandshake.computeAccept(clientKey);

        assertEquals(expectedAccept, actualAccept,
                "Sec-WebSocket-Accept must match secondary test vector");
    }

    @Test
    @DisplayName("Reject null or blank Sec-WebSocket-Key")
    void shouldThrowOnNullOrBlankClientKey() {
        for (String invalidKey : new String[]{null, "", "   ", "\t", "\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WebSocketHandshake.computeAccept(invalidKey),
                    "computeAccept must reject null or whitespace-only keys: '" + invalidKey + "'");
        }
    }

    @Test
    @DisplayName("Recognize valid HTTP 101 upgrade request headers")
    void shouldRecognizeValidUpgradeRequest() {
        assertTrue(WebSocketHandshake.isUpgradeRequest(VALID_HANDSHAKE_REQUEST),
                "Handshake request meeting all RFC 6455 §4.2.1 criteria must be recognized");
    }

    @Test
    @DisplayName("Recognize upgrade request with case-insensitive headers and multi-value Connection")
    void shouldRecognizeValidUpgradeRequestWithMixedCaseAndExtraHeaders() {
        String request =
                "GET /ws/telemetry HTTP/1.1\r\n" +
                "host: localhost:8080\r\n" +
                "connection: keep-alive, Upgrade\r\n" +
                "upgrade: WebSocket\r\n" +
                "sec-websocket-key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "sec-websocket-version: 13\r\n" +
                "\r\n";

        assertTrue(WebSocketHandshake.isUpgradeRequest(request),
                "Upgrade header check must be case-insensitive and allow comma-separated Connection tokens");
    }

    @Test
    @DisplayName("Reject request when Upgrade header is missing or not 'websocket'")
    void shouldRejectRequestWhenUpgradeHeaderMissingOrNotWebSocket() {
        String missingUpgrade =
                "GET /chat HTTP/1.1\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";

        String wrongUpgrade =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: h2c\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";

        assertFalse(WebSocketHandshake.isUpgradeRequest(missingUpgrade));
        assertFalse(WebSocketHandshake.isUpgradeRequest(wrongUpgrade));
    }

    @Test
    @DisplayName("Reject request when Connection header is missing or does not include 'Upgrade'")
    void shouldRejectRequestWhenConnectionHeaderMissingOrNotUpgrade() {
        String missingConnection =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: websocket\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";

        String wrongConnection =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: keep-alive\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";

        assertFalse(WebSocketHandshake.isUpgradeRequest(missingConnection));
        assertFalse(WebSocketHandshake.isUpgradeRequest(wrongConnection));
    }

    @Test
    @DisplayName("Reject request when Sec-WebSocket-Version is missing or != 13")
    void shouldRejectRequestWhenSecWebSocketVersionMissingOrNot13() {
        String missingVersion =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";

        String oldVersion =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 8\r\n\r\n";

        assertFalse(WebSocketHandshake.isUpgradeRequest(missingVersion));
        assertFalse(WebSocketHandshake.isUpgradeRequest(oldVersion));
    }

    @Test
    @DisplayName("Extract Sec-WebSocket-Key with leading and trailing whitespace trimmed")
    void shouldExtractSecWebSocketKeyWithWhitespaceTrimming() {
        String request =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key:   dGhlIHNhbXBsZSBub25jZQ==  \r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";

        String key = WebSocketHandshake.extractKey(request);
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", key);
    }

    @Test
    @DisplayName("Return null when Sec-WebSocket-Key is absent")
    void shouldReturnNullWhenSecWebSocketKeyMissing() {
        String request =
                "GET /chat HTTP/1.1\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n\r\n";

        assertNull(WebSocketHandshake.extractKey(request));
    }

    @Test
    @DisplayName("Extract request paths (/chat, /ws/telemetry, /) and ignore query parameters")
    void shouldExtractRequestPath() {
        assertEquals("/chat", WebSocketHandshake.extractPath(VALID_HANDSHAKE_REQUEST));

        String queryPathRequest =
                "GET /ws/telemetry?token=secret123&client=42 HTTP/1.1\r\n" +
                "Host: localhost\r\n\r\n";
        assertEquals("/ws/telemetry", WebSocketHandshake.extractPath(queryPathRequest));

        String rootPathRequest =
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n\r\n";
        assertEquals("/", WebSocketHandshake.extractPath(rootPathRequest));
    }

    @Test
    @DisplayName("Generate compliant HTTP 101 Switching Protocols response byte sequence")
    void shouldGenerateCompliantHandshakeResponseBytes() {
        String acceptKey = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        byte[] responseBytes = WebSocketHandshake.createHandshakeResponse(acceptKey);
        String response = new String(responseBytes, StandardCharsets.US_ASCII);

        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"),
                "Response must start with HTTP/1.1 101 status line");
        assertTrue(response.toLowerCase().contains("upgrade: websocket\r\n"),
                "Response must contain Upgrade: websocket header");
        assertTrue(response.toLowerCase().contains("connection: upgrade\r\n"),
                "Response must contain Connection: Upgrade header");
        assertTrue(response.contains("Sec-WebSocket-Accept: " + acceptKey + "\r\n"),
                "Response must contain exact computed Sec-WebSocket-Accept header");
        assertTrue(response.endsWith("\r\n\r\n"),
                "Response must terminate with double CRLF (empty line)");
    }

    @Test
    @DisplayName("Generate HTTP 101 response with Sec-WebSocket-Protocol header")
    void shouldIncludeSubprotocolInHandshakeResponse() {
        String acceptKey = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        byte[] responseBytes = WebSocketHandshake.createHandshakeResponse(acceptKey, "bedrock.chat.v1");
        String response = new String(responseBytes, StandardCharsets.US_ASCII);

        assertTrue(response.contains("Sec-WebSocket-Protocol: bedrock.chat.v1\r\n"),
                "Response must include negotiated subprotocol");
    }

    @Test
    @DisplayName("Parse URL query parameters accurately")
    void shouldParseUrlQueryParams() {
        var params = WebSocketHandshake.parseQueryParams("/chat?nick=Alice&room=general&encoded=Hello%20World");
        assertEquals(3, params.size());
        assertEquals("Alice", params.get("nick"));
        assertEquals("general", params.get("room"));
        assertEquals("Hello World", params.get("encoded"));

        var emptyParams = WebSocketHandshake.parseQueryParams("/chat");
        assertTrue(emptyParams.isEmpty());

        var emptyQuery = WebSocketHandshake.parseQueryParams("/chat?");
        assertTrue(emptyQuery.isEmpty());
    }
}
