package com.bedrock.core.ws.server;

import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.protocol.WebSocketFrameWriter;
import com.bedrock.core.ws.protocol.WebSocketOpcode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Live Raw-Socket Wire-Level Protocol Adversarial Suite
 *
 * <p>Validates raw byte-level TCP interactions against a live {@link BedrockWebSocketServer}:</p>
 * <ul>
 *   <li>RFC 6455 §5.1 Mandatory client masking (unmasked client frames trigger 1002 Protocol Error).</li>
 *   <li>HTTP upgrade handshake violations (missing Upgrade header, invalid versions, unregistered routes).</li>
 *   <li>Control frame interactions (Ping payload reflection in Pong, clean 1000 Close handshake).</li>
 *   <li>Multi-byte UTF-8, international scripts, and emoji wire framing.</li>
 *   <li>Adversarial payload injections (invalid UTF-8 triggering 1007 Invalid Data).</li>
 * </ul>
 */
public class BedrockWebSocketRawFrameTest {

    private static final String DEFAULT_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String EXPECTED_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    private static final byte[] MASK_KEY = new byte[]{(byte) 0xA1, (byte) 0xB2, (byte) 0xC3, (byte) 0xD4};

    // =========================================================================
    // Test Fixture Endpoint
    // =========================================================================

    static class RawWireEndpoint implements WebSocketEndpointBinding {
        final AtomicInteger openCount = new AtomicInteger(0);
        final AtomicInteger closeCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<CloseRecord> closeRecords = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        volatile BedrockWebSocketSession activeSession;
        volatile boolean autoEcho = true;

        record CloseRecord(BedrockWebSocketSession session, int code, String reason) {}

        @Override
        public void invokeOnOpen(BedrockWebSocketSession session) {
            this.activeSession = session;
            openCount.incrementAndGet();
        }

        @Override
        public void invokeOnMessage(BedrockWebSocketSession session, String message) {
            messages.offer(message);
            if (autoEcho) {
                session.send(message);
            }
        }

        @Override
        public void invokeOnClose(BedrockWebSocketSession session, int statusCode, String reason) {
            closeCount.incrementAndGet();
            closeRecords.offer(new CloseRecord(session, statusCode, reason));
        }

        @Override
        public void invokeOnError(BedrockWebSocketSession session, Throwable throwable) {
            errorCount.incrementAndGet();
            errors.offer(throwable);
        }
    }

    record RawServerFrame(boolean fin, int opcode, boolean masked, byte[] payload) {
        int getCloseStatusCode() {
            if (payload.length < 2) return -1;
            return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }

        String getCloseReason() {
            if (payload.length <= 2) return "";
            return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
        }

        String getPayloadAsText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    // =========================================================================
    // Wire Protocol Helpers
    // =========================================================================

    private static Socket connectRaw(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(4000);
        return socket;
    }

    private static String sendRawHandshake(Socket socket, String request) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;

        while ((read = in.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
            String current = baos.toString(StandardCharsets.US_ASCII);
            if (current.contains("\r\n\r\n")) {
                return current;
            }
        }
        return baos.toString(StandardCharsets.US_ASCII);
    }

    private static String establishHandshake(Socket socket, int port, String path) throws IOException {
        String req = "GET " + path + " HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + DEFAULT_KEY + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        String response = sendRawHandshake(socket, req);
        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"),
                "Handshake must return HTTP 101 Switching Protocols. Got: " + response);
        assertTrue(response.contains("Sec-WebSocket-Accept: " + EXPECTED_ACCEPT),
                "Handshake response must contain exact Sec-WebSocket-Accept");
        return response;
    }

    private static RawServerFrame readServerFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            throw new EOFException("End of stream while reading frame byte 0");
        }
        int b1 = in.read();
        if (b1 == -1) {
            throw new EOFException("End of stream while reading frame byte 1");
        }

        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        int lenIndicator = b1 & 0x7F;

        long payloadLen;
        if (lenIndicator <= 125) {
            payloadLen = lenIndicator;
        } else if (lenIndicator == 126) {
            byte[] ext = in.readNBytes(2);
            if (ext.length < 2) throw new EOFException("Truncated 16-bit extended length");
            payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
        } else {
            byte[] ext = in.readNBytes(8);
            if (ext.length < 8) throw new EOFException("Truncated 64-bit extended length");
            long l = 0;
            for (int i = 0; i < 8; i++) {
                l = (l << 8) | (ext[i] & 0xFF);
            }
            payloadLen = l;
        }

        byte[] payload = in.readNBytes((int) payloadLen);
        if (payload.length < payloadLen) {
            throw new EOFException("Truncated payload data: expected " + payloadLen + ", got " + payload.length);
        }

        return new RawServerFrame(fin, opcode, masked, payload);
    }

    // =========================================================================
    // 1. Raw Wire-Level Protocol Violations
    // =========================================================================

    @Test
    @DisplayName("Protocol Violation 1.1: Unmasked client text frame causes immediate Close 1002 and socket closure")
    void testClientUnmaskedTextFrameRejection() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/raw-unmasked", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/raw-unmasked");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Build Illegal UNMASKED Text Frame: FIN=1, Opcode=1, MASK=0, Len=4, "TEST"
                byte[] illegalFrame = new byte[]{
                        (byte) 0x81, // FIN=1, Opcode=0x1
                        (byte) 0x04, // MASK=0, Length=4
                        (byte) 'T', (byte) 'E', (byte) 'S', (byte) 'T'
                };
                out.write(illegalFrame);
                out.flush();

                // Server must respond with Close frame (Opcode 0x8) and status code 1002 (Protocol Error)
                RawServerFrame closeFrame = readServerFrame(in);
                assertTrue(closeFrame.fin(), "Close frame must have FIN=1");
                assertEquals(WebSocketOpcode.CLOSE.getCode(), closeFrame.opcode(), "Opcode must be CLOSE (0x8)");
                assertFalse(closeFrame.masked(), "Server frame must be unmasked per RFC 6455 §5.1");
                assertEquals(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, closeFrame.getCloseStatusCode(),
                        "Close status code must be 1002 (Protocol Error)");

                // Server must subsequently terminate the TCP socket
                int eof = in.read();
                assertEquals(-1, eof, "Server must close TCP connection after sending protocol error Close frame");

                // Verify endpoint @OnClose was notified with 1002
                RawWireEndpoint.CloseRecord record = endpoint.closeRecords.poll(2, TimeUnit.SECONDS);
                assertNotNull(record, "Endpoint @OnClose must be invoked");
                assertEquals(1002, record.code(), "CloseRecord status must be 1002");
            }
        }
    }

    @Test
    @DisplayName("Protocol Violation 1.2: Unmasked client Ping frame causes immediate Close 1002 and socket closure")
    void testClientUnmaskedPingFrameRejection() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/raw-unmasked-ping", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/raw-unmasked-ping");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Illegal UNMASKED Ping Frame: FIN=1, Opcode=0x9, MASK=0, Len=2, "HI"
                byte[] illegalPing = new byte[]{
                        (byte) 0x89, // FIN=1, Opcode=0x9
                        (byte) 0x02, // MASK=0, Len=2
                        (byte) 'H', (byte) 'I'
                };
                out.write(illegalPing);
                out.flush();

                RawServerFrame closeFrame = readServerFrame(in);
                assertEquals(WebSocketOpcode.CLOSE.getCode(), closeFrame.opcode(), "Opcode must be CLOSE (0x8)");
                assertEquals(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, closeFrame.getCloseStatusCode(),
                        "Unmasked Ping must trigger Close 1002 Protocol Error");

                int eof = in.read();
                assertEquals(-1, eof, "TCP connection must close after 1002");
            }
        }
    }

    @Test
    @DisplayName("Protocol Violation 1.3: Invalid HTTP GET handshake without Upgrade header returns HTTP 400 Bad Request")
    void testHandshakeMissingUpgradeHeaderReturns400() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/chat", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                // Request missing "Upgrade: websocket"
                String badRequest = "GET /chat HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: " + DEFAULT_KEY + "\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n";

                String response = sendRawHandshake(socket, badRequest);
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"),
                        "Response must start with HTTP/1.1 400 Bad Request. Got: " + response);
                assertTrue(response.toLowerCase().contains("missing or invalid 'upgrade: websocket' header"),
                        "Response body must detail missing Upgrade header");

                // Socket must be closed by server
                InputStream in = socket.getInputStream();
                int eof = in.read();
                assertEquals(-1, eof, "Server must close TCP connection after sending 400");
            }
        }
    }

    @Test
    @DisplayName("Protocol Violation 1.4: Handshake with Sec-WebSocket-Version != 13 returns HTTP 426 Upgrade Required")
    void testHandshakeWithVersion8Returns426() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/chat", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                // Sec-WebSocket-Version: 8 (HyBi-10) is unsupported
                String badVersionRequest = "GET /chat HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: " + DEFAULT_KEY + "\r\n" +
                        "Sec-WebSocket-Version: 8\r\n\r\n";

                String response = sendRawHandshake(socket, badVersionRequest);
                assertTrue(response.startsWith("HTTP/1.1 426 Upgrade Required\r\n"),
                        "Response must be HTTP 426 Upgrade Required. Got: " + response);
                assertTrue(response.contains("Sec-WebSocket-Version: 13"),
                        "Response must include 'Sec-WebSocket-Version: 13' header per RFC 6455 §4.4");

                InputStream in = socket.getInputStream();
                int eof = in.read();
                assertEquals(-1, eof, "Server must close TCP connection after sending 426");
            }
        }
    }

    @Test
    @DisplayName("Protocol Violation 1.5: Handshake targeting nonexistent route returns HTTP 404 Not Found")
    void testHandshakeToNonexistentRouteReturns404() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/registered-endpoint", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                String nonexistentRequest = "GET /unregistered/route/path HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: " + DEFAULT_KEY + "\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n";

                String response = sendRawHandshake(socket, nonexistentRequest);
                assertTrue(response.startsWith("HTTP/1.1 404 Not Found\r\n"),
                        "Response must be HTTP 404 Not Found. Got: " + response);
                assertTrue(response.contains("WebSocket Endpoint Not Found: /unregistered/route/path"),
                        "Response body must state endpoint not found");

                InputStream in = socket.getInputStream();
                int eof = in.read();
                assertEquals(-1, eof, "Server must close TCP connection after sending 404");
            }
        }
    }

    @Test
    @DisplayName("Protocol Violation 1.6: Handshake missing Connection header returns HTTP 400 Bad Request")
    void testHandshakeMissingConnectionHeaderReturns400() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/chat", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                String missingConn = "GET /chat HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Key: " + DEFAULT_KEY + "\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n";

                String response = sendRawHandshake(socket, missingConn);
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"),
                        "Missing Connection header must yield HTTP 400: " + response);
            }
        }
    }

    @Test
    @DisplayName("Protocol Violation 1.7: Handshake with malformed Base64 Sec-WebSocket-Key returns HTTP 400")
    void testHandshakeWithMalformedKeyReturns400() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/chat", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                // Key is invalid Base64 and not 16 bytes
                String malformedKey = "GET /chat HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:" + server.getPort() + "\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: ???malformed!!!\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n";

                String response = sendRawHandshake(socket, malformedKey);
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"),
                        "Malformed key must yield HTTP 400: " + response);
            }
        }
    }

    // =========================================================================
    // 2. Control Frame Interactions (Ping/Pong & Close Handshake)
    // =========================================================================

    @Test
    @DisplayName("Control Frame 2.1: Server immediately responds to Ping with Pong echoing exact arbitrary payload")
    void testPingPongPayloadReflectionAcrossSizes() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/control-ping", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/control-ping");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Test 1: Arbitrary 16-byte binary payload
                byte[] pingPayload1 = new byte[]{
                        0x01, 0x02, 0x03, 0x04, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
                        0x10, 0x20, 0x30, 0x40, (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE
                };
                byte[] maskedPing1 = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.PING, pingPayload1, true, MASK_KEY
                );
                out.write(maskedPing1);
                out.flush();

                RawServerFrame pong1 = readServerFrame(in);
                assertTrue(pong1.fin(), "Pong frame must have FIN=1");
                assertEquals(WebSocketOpcode.PONG.getCode(), pong1.opcode(), "Opcode must be PONG (0xA)");
                assertFalse(pong1.masked(), "Pong frame from server must be unmasked");
                assertArrayEquals(pingPayload1, pong1.payload(), "Pong payload must byte-for-byte match Ping payload");

                // Test 2: Maximum allowed control frame payload (125 bytes)
                byte[] pingPayload125 = new byte[125];
                Arrays.fill(pingPayload125, (byte) 0x7E);
                byte[] maskedPing125 = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.PING, pingPayload125, true, MASK_KEY
                );
                out.write(maskedPing125);
                out.flush();

                RawServerFrame pong125 = readServerFrame(in);
                assertTrue(pong125.fin());
                assertEquals(WebSocketOpcode.PONG.getCode(), pong125.opcode());
                assertFalse(pong125.masked());
                assertEquals(125, pong125.payload().length);
                assertArrayEquals(pingPayload125, pong125.payload(), "125-byte Pong payload must match");

                // Test 3: Empty control frame payload (0 bytes)
                byte[] pingPayloadEmpty = new byte[0];
                byte[] maskedPingEmpty = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.PING, pingPayloadEmpty, true, MASK_KEY
                );
                out.write(maskedPingEmpty);
                out.flush();

                RawServerFrame pongEmpty = readServerFrame(in);
                assertTrue(pongEmpty.fin());
                assertEquals(WebSocketOpcode.PONG.getCode(), pongEmpty.opcode());
                assertEquals(0, pongEmpty.payload().length, "Empty Ping must yield 0-byte Pong");
            }
        }
    }

    @Test
    @DisplayName("Control Frame 2.2: Client sends Close frame with status 1000 and reason; server echoes and terminates socket")
    void testClientInitiatedCloseEchoAndTermination() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/control-close", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/control-close");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Send Close frame: status 1000 (Normal Closure), reason "Client shutting down cleanly"
                String closeReason = "Client shutting down cleanly";
                byte[] reasonBytes = closeReason.getBytes(StandardCharsets.UTF_8);
                byte[] closePayload = new byte[2 + reasonBytes.length];
                closePayload[0] = (byte) ((1000 >>> 8) & 0xFF);
                closePayload[1] = (byte) (1000 & 0xFF);
                System.arraycopy(reasonBytes, 0, closePayload, 2, reasonBytes.length);

                byte[] maskedClose = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.CLOSE, closePayload, true, MASK_KEY
                );
                out.write(maskedClose);
                out.flush();

                // Server must respond with Close frame echoing status code 1000 and the reason
                RawServerFrame serverClose = readServerFrame(in);
                assertTrue(serverClose.fin(), "Close echo must have FIN=1");
                assertEquals(WebSocketOpcode.CLOSE.getCode(), serverClose.opcode(), "Opcode must be CLOSE (0x8)");
                assertFalse(serverClose.masked(), "Server frame must be unmasked");
                assertEquals(1000, serverClose.getCloseStatusCode(), "Server Close status code must be 1000");
                assertEquals(closeReason, serverClose.getCloseReason(), "Server Close reason must match client reason");

                // Socket must be closed by server immediately following Close echo
                int eof = in.read();
                assertEquals(-1, eof, "TCP channel must be closed by server after Close handshake");

                // Verify endpoint @OnClose received 1000 and reason
                RawWireEndpoint.CloseRecord record = endpoint.closeRecords.poll(2, TimeUnit.SECONDS);
                assertNotNull(record, "Endpoint @OnClose must be invoked");
                assertEquals(1000, record.code());
                assertEquals(closeReason, record.reason());
            }
        }
    }

    @Test
    @DisplayName("Control Frame 2.3: Client sends empty Close frame (0 payload bytes); server defaults echo to 1000")
    void testClientEmptyCloseFrameEcho() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/control-empty-close", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/control-empty-close");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // RFC 6455 permits Close frame with length 0 (inferred as 1005 No Status)
                byte[] maskedEmptyClose = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.CLOSE, new byte[0], true, MASK_KEY
                );
                out.write(maskedEmptyClose);
                out.flush();

                RawServerFrame serverClose = readServerFrame(in);
                assertEquals(WebSocketOpcode.CLOSE.getCode(), serverClose.opcode());
                // Server should echo 1000 Normal Closure when incoming had no status code
                assertEquals(1000, serverClose.getCloseStatusCode(), "Server must echo 1000 for empty close frame");

                int eof = in.read();
                assertEquals(-1, eof, "Server must close TCP connection");
            }
        }
    }

    // =========================================================================
    // 3. Multi-Byte UTF-8 & Emoji Wire-Level Transmission
    // =========================================================================

    @Test
    @DisplayName("UTF-8 3.1: Multi-byte UTF-8, complex accents, Cyrillic, CJK, and modern multi-code-point emojis")
    void testMultiByteUtf8AndEmojiRoundtrip() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/utf8-wire", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/utf8-wire");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Test complex multi-byte string
                String complexMessage = "🦖 Bedrock 2.0 WebSockets! 🚀 Olá Mundo! João comprou maçãs & café na estação. " +
                        "Привет мир! こんにちは世界! 안녕하세요! 🌍🎉🔥⚡️ (4-byte emojis: 👨‍👩‍👧‍👦, 🛸, 🦾)";

                byte[] utf8Bytes = complexMessage.getBytes(StandardCharsets.UTF_8);
                byte[] maskedTextFrame = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.TEXT, utf8Bytes, true, MASK_KEY
                );

                out.write(maskedTextFrame);
                out.flush();

                // Endpoint receives and verifies exact string
                String receivedAtEndpoint = endpoint.messages.poll(3, TimeUnit.SECONDS);
                assertEquals(complexMessage, receivedAtEndpoint, "Endpoint must receive exact uncorrupted UTF-8 text");

                // Client reads server echo frame
                RawServerFrame echoFrame = readServerFrame(in);
                assertTrue(echoFrame.fin(), "Echo frame must have FIN=1");
                assertEquals(WebSocketOpcode.TEXT.getCode(), echoFrame.opcode(), "Opcode must be TEXT (0x1)");
                assertFalse(echoFrame.masked(), "Server frame must be unmasked");
                assertEquals(complexMessage, echoFrame.getPayloadAsText(),
                        "Echoed frame payload must match complex UTF-8 string identically");

                // Clean close
                byte[] closeFrame = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.CLOSE, new byte[]{(byte) 0x03, (byte) 0xE8}, true, MASK_KEY
                );
                out.write(closeFrame);
                out.flush();
            }
        }
    }

    @Test
    @DisplayName("UTF-8 3.2: Adversarial invalid UTF-8 byte stream over raw socket causes Close 1007 (Invalid Data)")
    void testAdversarialInvalidUtf8PayloadRejection() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/utf8-invalid", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/utf8-invalid");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Construct invalid UTF-8 sequence: e.g. standalone continuation byte 0x80 or truncated 2-byte header 0xC3
                byte[] invalidUtf8 = new byte[]{(byte) 'H', (byte) 'i', (byte) 0xC3, (byte) 0x28, (byte) '!'};

                byte[] maskedBadFrame = WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.TEXT, invalidUtf8, true, MASK_KEY
                );

                out.write(maskedBadFrame);
                out.flush();

                // Server must detect malformed UTF-8 and reply with Close status 1007 (Invalid Frame Payload Data)
                RawServerFrame closeFrame = readServerFrame(in);
                assertEquals(WebSocketOpcode.CLOSE.getCode(), closeFrame.opcode(), "Opcode must be CLOSE (0x8)");
                assertEquals(WebSocketCloseStatus.INVALID_DATA_CODE, closeFrame.getCloseStatusCode(),
                        "Malformed UTF-8 must trigger Close status 1007 (Invalid Data)");

                int eof = in.read();
                assertEquals(-1, eof, "TCP connection must be closed after 1007 error");

                // Endpoint onError was invoked with WebSocketException
                Throwable error = endpoint.errors.poll(2, TimeUnit.SECONDS);
                assertNotNull(error, "Endpoint @OnError must receive the UTF-8 validation exception");
            }
        }
    }

    @Test
    @DisplayName("Interactive 3.3: Ping control frame interleaved between Text frames preserves message stream")
    void testPingInterleavedBetweenTextFrames() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/interleave", endpoint);
            server.start();

            try (Socket socket = connectRaw(server.getPort())) {
                establishHandshake(socket, server.getPort(), "/interleave");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // 1. Send Text Frame 1
                out.write(WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.TEXT, "Message-1".getBytes(StandardCharsets.UTF_8), true, MASK_KEY
                ));
                // 2. Immediately send Ping Frame
                out.write(WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.PING, "HeartbeatProbe".getBytes(StandardCharsets.UTF_8), true, MASK_KEY
                ));
                // 3. Immediately send Text Frame 2
                out.write(WebSocketFrameWriter.createMaskedFrame(
                        WebSocketOpcode.TEXT, "Message-2".getBytes(StandardCharsets.UTF_8), true, MASK_KEY
                ));
                out.flush();

                // Collect responses: We expect echo for Message-1, Pong for HeartbeatProbe, and echo for Message-2
                // (order of Pong vs Text depends on thread execution, but all 3 must arrive intact)
                RawServerFrame f1 = readServerFrame(in);
                RawServerFrame f2 = readServerFrame(in);
                RawServerFrame f3 = readServerFrame(in);

                RawServerFrame[] frames = new RawServerFrame[]{f1, f2, f3};

                boolean foundPong = false;
                boolean foundMsg1 = false;
                boolean foundMsg2 = false;

                for (RawServerFrame f : frames) {
                    if (f.opcode() == WebSocketOpcode.PONG.getCode()) {
                        assertEquals("HeartbeatProbe", f.getPayloadAsText());
                        foundPong = true;
                    } else if (f.opcode() == WebSocketOpcode.TEXT.getCode()) {
                        String text = f.getPayloadAsText();
                        if ("Message-1".equals(text)) foundMsg1 = true;
                        if ("Message-2".equals(text)) foundMsg2 = true;
                    }
                }

                assertTrue(foundPong, "Pong reply must be received");
                assertTrue(foundMsg1, "Echo for Message-1 must be received");
                assertTrue(foundMsg2, "Echo for Message-2 must be received");
            }
        }
    }

    // =========================================================================
    // 4. Concurrency & Frame Interleaving Prevention (ReentrantLock)
    // =========================================================================

    @Test
    @DisplayName("Concurrency 4.1: Simultaneous broadcast and session sends do not interleave frames (ReentrantLock protection)")
    void testSimultaneousBroadcastAndDirectSendWithoutFrameInterleaving() throws Exception {
        RawWireEndpoint endpoint = new RawWireEndpoint();
        endpoint.autoEcho = false;
        try (BedrockWebSocketServer server = new BedrockWebSocketServer(0)) {
            server.registerEndpoint("/concurrency-interleave", endpoint);
            server.start();

            try (Socket socketA = connectRaw(server.getPort());
                 Socket socketB = connectRaw(server.getPort())) {

                establishHandshake(socketA, server.getPort(), "/concurrency-interleave");
                establishHandshake(socketB, server.getPort(), "/concurrency-interleave");

                // Wait briefly for both virtual threads to complete registration in the registry
                long deadline = System.currentTimeMillis() + 3000;
                while (server.getSessionRegistry().size() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(2, server.getSessionRegistry().size(), "Both sessions must be registered");

                BedrockWebSocketSession sessionA = null;
                int portA = socketA.getLocalPort();
                for (BedrockWebSocketSession s : server.getSessionRegistry().getAll()) {
                    if (s.getRemoteAddress() instanceof java.net.InetSocketAddress inet && inet.getPort() == portA) {
                        sessionA = s;
                        break;
                    }
                }
                assertNotNull(sessionA, "Target session matching socketA local port must not be null");

                int broadcastCount = 30;
                int directSendCount = 20;
                int totalExpectedFramesOnA = broadcastCount + directSendCount;

                java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(broadcastCount + directSendCount);

                String paddingX = "X".repeat(500);
                String paddingY = "Y".repeat(500);

                // Spawn broadcast tasks on virtual threads
                for (int i = 0; i < broadcastCount; i++) {
                    final int idx = i;
                    Thread.ofVirtual().start(() -> {
                        try {
                            startLatch.await();
                            server.getSessionRegistry().broadcast("BROADCAST-" + idx + "-" + paddingX);
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                // Spawn direct session send tasks on virtual threads targeting sessionA
                final BedrockWebSocketSession targetSession = sessionA;
                for (int i = 0; i < directSendCount; i++) {
                    final int idx = i;
                    Thread.ofVirtual().start(() -> {
                        try {
                            startLatch.await();
                            targetSession.send("DIRECT-" + idx + "-" + paddingY);
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                // Unleash all virtual threads simultaneously
                startLatch.countDown();
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All concurrent write tasks must complete");

                // Read and verify all frames from socketA
                InputStream inA = socketA.getInputStream();
                int receivedBroadcasts = 0;
                int receivedDirects = 0;

                for (int i = 0; i < totalExpectedFramesOnA; i++) {
                    RawServerFrame frame = readServerFrame(inA);
                    assertTrue(frame.fin(), "Every frame must have FIN=1");
                    assertEquals(WebSocketOpcode.TEXT.getCode(), frame.opcode(), "Opcode must be TEXT (0x1)");
                    assertFalse(frame.masked(), "Server-to-client frames must be unmasked");

                    String payload = frame.getPayloadAsText();
                    if (payload.startsWith("BROADCAST-")) {
                        assertTrue(payload.endsWith(paddingX), "Broadcast payload must remain contiguous and uncorrupted");
                        receivedBroadcasts++;
                    } else if (payload.startsWith("DIRECT-")) {
                        assertTrue(payload.endsWith(paddingY), "Direct send payload must remain contiguous and uncorrupted");
                        receivedDirects++;
                    } else {
                        fail("Frame payload corrupted or interleaved: " + payload.substring(0, Math.min(50, payload.length())));
                    }
                }

                assertEquals(broadcastCount, receivedBroadcasts, "Must receive all broadcast frames");
                assertEquals(directSendCount, receivedDirects, "Must receive all direct send frames");
            }
        }
    }
}
