package com.bedrock.core.ws.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial and edge-case stress test suite for RFC 6455 Protocol Engine.
 *
 * <p>Validates extreme boundary conditions, non-standard HTTP whitespace, case-insensitivity,
 * multi-token Connection headers, truncated Base64 keys, malformed UTF-8 payloads,
 * in-place XOR bitwise permutations, non-minimal frame encodings, and RFC 6455 protocol errors (1002, 1007).</p>
 */
class WebSocketAdversarialTest {

    private static final String BASE64_16_BYTE_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    // =========================================================================
    // 1. ADVERSARIAL HANDSHAKE CHALLENGES
    // =========================================================================

    @Test
    @DisplayName("Challenge 1.1: Exotic whitespace in request line (tabs, multiple spaces)")
    void shouldHandleExoticWhitespaceInRequestLine() {
        String request = "GET\t\t   /chat/room1   \t\tHTTP/1.1\r\n" +
                "Host:\t   server.example.com\r\n" +
                "Upgrade:   \twebsocket\r\n" +
                "Connection:   \tUpgrade\r\n" +
                "Sec-WebSocket-Key:   \t" + BASE64_16_BYTE_KEY + "   \r\n" +
                "Sec-WebSocket-Version:   13   \r\n" +
                "\r\n";

        assertTrue(WebSocketHandshake.isUpgradeRequest(request));
        WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
        assertTrue(result.isValid());
        assertEquals(101, result.statusCode());
        assertEquals("/chat/room1", result.path());
        assertEquals(BASE64_16_BYTE_KEY, result.key());
    }

    @Test
    @DisplayName("Challenge 1.2: All lowercase header names and values")
    void shouldHandleAllLowercaseHeadersAndValues() {
        String request = "GET /chat HTTP/1.1\r\n" +
                "host: localhost:8080\r\n" +
                "upgrade: websocket\r\n" +
                "connection: upgrade\r\n" +
                "sec-websocket-key: " + BASE64_16_BYTE_KEY + "\r\n" +
                "sec-websocket-version: 13\r\n" +
                "\r\n";

        assertTrue(WebSocketHandshake.isUpgradeRequest(request));
        WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
        assertTrue(result.isValid());
        assertEquals(101, result.statusCode());
    }

    @Test
    @DisplayName("Challenge 1.3: All uppercase header names and values")
    void shouldHandleAllUppercaseHeadersAndValues() {
        String request = "GET /chat HTTP/1.1\r\n" +
                "HOST: LOCALHOST:8080\r\n" +
                "UPGRADE: WEBSOCKET\r\n" +
                "CONNECTION: UPGRADE\r\n" +
                "SEC-WEBSOCKET-KEY: " + BASE64_16_BYTE_KEY + "\r\n" +
                "SEC-WEBSOCKET-VERSION: 13\r\n" +
                "\r\n";

        assertTrue(WebSocketHandshake.isUpgradeRequest(request));
        WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
        assertTrue(result.isValid());
        assertEquals(101, result.statusCode());
    }

    @Test
    @DisplayName("Challenge 1.4: Multi-token Connection headers with varying order and spacing")
    void shouldHandleMultiTokenConnectionHeaders() {
        String[] variations = {
                "keep-alive, Upgrade",
                "Upgrade, keep-alive",
                "keep-alive, close, Upgrade",
                "  keep-alive  ,   Upgrade  ",
                "UPGRADE, KEEP-ALIVE"
        };

        for (String conn : variations) {
            String request = "GET /chat HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: " + conn + "\r\n" +
                    "Sec-WebSocket-Key: " + BASE64_16_BYTE_KEY + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n";

            assertTrue(WebSocketHandshake.isUpgradeRequest(request),
                    "Failed on Connection: " + conn);
            WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
            assertTrue(result.isValid(), "Failed parse on Connection: " + conn);
            assertEquals(101, result.statusCode());
        }
    }

    @Test
    @DisplayName("Challenge 1.5: Unsupported WebSocket version (8, 7, 0, 14, text) must return 426 Upgrade Required")
    void shouldRejectUnsupportedWebSocketVersionsWith426() {
        String[] invalidVersions = {"8", "7", "0", "14", "99", "v13", "random"};

        for (String version : invalidVersions) {
            String request = "GET /chat HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + BASE64_16_BYTE_KEY + "\r\n" +
                    "Sec-WebSocket-Version: " + version + "\r\n" +
                    "\r\n";

            assertFalse(WebSocketHandshake.isUpgradeRequest(request),
                    "isUpgradeRequest should be false for version: " + version);

            WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
            assertFalse(result.isValid());
            assertEquals(426, result.statusCode(),
                    "RFC 6455 §4.4 mandates status 426 for unsupported version: " + version);
            assertNotNull(result.responseBytes());
            String response = new String(result.responseBytes(), StandardCharsets.US_ASCII);
            assertTrue(response.contains("426 Upgrade Required"));
            assertTrue(response.contains("Sec-WebSocket-Version: 13"));
        }
    }

    @Test
    @DisplayName("Challenge 1.6: Truncated or malformed Sec-WebSocket-Key must return 400 Bad Request")
    void shouldRejectTruncatedOrMalformedSecWebSocketKeyWith400() {
        // Valid 16-byte nonce is 24 Base64 chars. Below are non-16-byte or malformed keys:
        String[] invalidKeys = {
                "dGhl",                                    // 3 bytes decoded
                "dGhlIHNhbXBsZQ==",                        // 11 bytes decoded
                "dGhlIHNhbXBsZSBub25jZQ",                  // Missing Base64 padding
                "????====!!!!",                            // Illegal Base64 characters
                Base64.getEncoder().encodeToString(new byte[15]), // 15 bytes
                Base64.getEncoder().encodeToString(new byte[17]), // 17 bytes
                Base64.getEncoder().encodeToString(new byte[32])  // 32 bytes
        };

        for (String key : invalidKeys) {
            String request = "GET /chat HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + key + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n";

            WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
            assertFalse(result.isValid(), "Parse must reject invalid key: " + key);
            assertEquals(400, result.statusCode(),
                    "RFC 6455 §4.2.1/§4.2.2 mandates 400 for key length != 16 bytes: " + key);
        }
    }

    @Test
    @DisplayName("Challenge 1.7: Handshake with LF-only line endings (Unix style)")
    void shouldHandleLfOnlyLineEndings() {
        String request = "GET /chat HTTP/1.1\n" +
                "Host: localhost\n" +
                "Upgrade: websocket\n" +
                "Connection: Upgrade\n" +
                "Sec-WebSocket-Key: " + BASE64_16_BYTE_KEY + "\n" +
                "Sec-WebSocket-Version: 13\n" +
                "\n";

        assertTrue(WebSocketHandshake.isUpgradeRequest(request));
        WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
        assertTrue(result.isValid());
        assertEquals(101, result.statusCode());
    }

    @Test
    @DisplayName("Challenge 1.8: Non-GET HTTP methods must return 405 Method Not Allowed")
    void shouldRejectNonGetMethodsWith405() {
        String[] nonGetMethods = {"POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"};

        for (String method : nonGetMethods) {
            String request = method + " /chat HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + BASE64_16_BYTE_KEY + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n";

            assertFalse(WebSocketHandshake.isUpgradeRequest(request));
            WebSocketHandshake.HandshakeParseResult result = WebSocketHandshake.parse(request);
            assertFalse(result.isValid());
            assertEquals(405, result.statusCode());
        }
    }

    @Test
    @DisplayName("Challenge 1.9: Oversized HTTP handshake header must throw WebSocketException(1002)")
    void shouldRejectOversizedHandshakeHeader() {
        StringBuilder largeHeaders = new StringBuilder("GET /chat HTTP/1.1\r\nHost: localhost\r\n");
        // Exceed MAX_HEADER_SIZE (16 KB)
        largeHeaders.append("X-Large-Header: ").append("A".repeat(17 * 1024)).append("\r\n\r\n");

        byte[] rawBytes = largeHeaders.toString().getBytes(StandardCharsets.US_ASCII);

        assertThrows(WebSocketException.class,
                () -> WebSocketHandshake.parse(ByteBuffer.wrap(rawBytes)));

        assertThrows(WebSocketException.class,
                () -> WebSocketHandshake.parse(new ByteArrayInputStream(rawBytes)));
    }

    // =========================================================================
    // 2. ADVERSARIAL FRAME PARSER CHALLENGES
    // =========================================================================

    @Test
    @DisplayName("Challenge 2.1: Client frame with MASK=0 must throw WebSocketException(1002)")
    void shouldRejectUnmaskedClientFrameWith1002() {
        // 0x81 (FIN=1, Text), 0x05 (MASK=0, Len=5), "Hello"
        byte[] unmaskedFrame = new byte[]{(byte) 0x81, (byte) 0x05, 'H', 'e', 'l', 'l', 'o'};

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(unmaskedFrame)));
        assertEquals(1002, ex.getStatusCode());
    }

    @Test
    @DisplayName("Challenge 2.2: In-place XOR unmasking with non-trivial 4-byte keys and random data")
    void shouldCorrectlyUnmaskNonTrivialKeysAcrossPayloadSizes() {
        byte[][] testKeys = new byte[][]{
                {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                {(byte) 0xFF, (byte) 0x00, (byte) 0xAA, (byte) 0x55},
                {(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04},
                {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}
        };

        Random random = new Random(42);

        for (byte[] maskKey : testKeys) {
            for (int size : new int[]{1, 2, 3, 4, 5, 7, 8, 15, 16, 125, 126, 256, 1024, 65535}) {
                byte[] original = new byte[size];
                random.nextBytes(original);

                // Clone and apply mask
                byte[] masked = original.clone();
                WebSocketFrameParser.unmask(masked, maskKey);

                // Applying same XOR again must recover original (involution property)
                WebSocketFrameParser.unmask(masked, maskKey);
                assertArrayEquals(original, masked,
                        "In-place XOR failed for size=" + size + " with key=" + Arrays.toString(maskKey));

                // Round-trip through parser
                byte[] wireFrame = buildClientFrame(WebSocketOpcode.BINARY, original, maskKey, true);
                WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(wireFrame));
                assertNotNull(parsed);
                assertArrayEquals(original, parsed.getPayload(),
                        "Parser unmasking failed for size=" + size);
            }
        }
    }

    @Test
    @DisplayName("Challenge 2.3: Non-minimal 16-bit payload length encoding (length 10 encoded as 0x000A) must throw 1002")
    void shouldRejectNonMinimal16BitEncodingWith1002() {
        // Length 10 using 16-bit extended length indicator (126)
        byte[] frame = new byte[]{
                (byte) 0x81, (byte) 0xFE, // FIN=1 Text, MASK=1 | 126
                0x00, 0x0A,               // 16-bit length 10 (< 126 is illegal!)
                0x11, 0x22, 0x33, 0x44,   // 4-byte mask
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 10 payload bytes
        };

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(frame)));
        assertEquals(1002, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Non-minimal 16-bit payload length"));
    }

    @Test
    @DisplayName("Challenge 2.4: Non-minimal 64-bit payload length encoding (length 500 or 65535) must throw 1002")
    void shouldRejectNonMinimal64BitEncodingWith1002() {
        for (long illegalLen : new long[]{0, 10, 125, 126, 500, 65535}) {
            ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4 + (int) illegalLen);
            buf.put((byte) 0x82); // FIN=1, BINARY
            buf.put((byte) 0xFF); // MASK=1 | 127
            buf.putLong(illegalLen);
            buf.put(new byte[]{1, 2, 3, 4});
            buf.put(new byte[(int) illegalLen]);

            WebSocketException ex = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(buf.array())),
                    "Failed for non-minimal 64-bit length: " + illegalLen);
            assertEquals(1002, ex.getStatusCode());
        }
    }

    @Test
    @DisplayName("Challenge 2.5: 64-bit payload length with MSB=1 (negative long) must throw 1002")
    void shouldRejectNegative64BitLengthWith1002() {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4);
        buf.put((byte) 0x82);
        buf.put((byte) 0xFF);
        buf.putLong(-1L); // 0xFFFFFFFFFFFFFFFF (MSB=1)
        buf.put(new byte[]{1, 2, 3, 4});

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(buf.array())));
        assertEquals(1002, ex.getStatusCode());
    }

    @Test
    @DisplayName("Challenge 2.6: RSV1=1, RSV2=1, or RSV3=1 must throw 1002")
    void shouldRejectAllRsvBitCombinationsWith1002() {
        byte[] mask = {1, 2, 3, 4};
        int[] rsvBitMasks = {0x40, 0x20, 0x10, 0x60, 0x50, 0x30, 0x70};

        for (int rsv : rsvBitMasks) {
            byte[] frame = new byte[]{
                    (byte) (0x80 | rsv | 0x01), // FIN=1, RSV, Opcode=Text
                    (byte) 0x80,                // MASK=1, Len=0
                    mask[0], mask[1], mask[2], mask[3]
            };

            WebSocketException ex = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(frame)),
                    "Failed for RSV mask: 0x" + Integer.toHexString(rsv));
            assertEquals(1002, ex.getStatusCode());
        }
    }

    @Test
    @DisplayName("Challenge 2.7: Malformed UTF-8 sequences in Text payload must throw 1007")
    void shouldRejectMalformedUtf8SequencesInTextPayloadWith1007() {
        byte[][] malformedUtf8Vectors = new byte[][]{
                {(byte) 0x80},                            // Unexpected continuation byte
                {(byte) 0xBF},                            // Unexpected continuation byte
                {(byte) 0xC3, (byte) 0x28},               // Invalid 2-byte continuation
                {(byte) 0xC0, (byte) 0xAF},               // Overlong ASCII representation (0xC0 is illegal)
                {(byte) 0xC1, (byte) 0xBF},               // Overlong ASCII representation (0xC1 is illegal)
                {(byte) 0xE0, (byte) 0x80, (byte) 0xAF},   // Overlong 3-byte encoding
                {(byte) 0xED, (byte) 0xA0, (byte) 0x80},   // UTF-16 surrogate (U+D800)
                {(byte) 0xED, (byte) 0xBF, (byte) 0xBF},   // UTF-16 surrogate (U+DFFF)
                {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, // Out of range codepoint (> U+10FFFF)
                {(byte) 0xC2}                             // Truncated multi-byte sequence (missing 2nd byte)
        };

        byte[] mask = {0x10, 0x20, 0x30, 0x40};

        for (byte[] badUtf8 : malformedUtf8Vectors) {
            byte[] wireFrame = buildClientFrame(WebSocketOpcode.TEXT, badUtf8, mask, true);

            WebSocketException ex = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(wireFrame)),
                    "Failed to reject malformed UTF-8: " + Arrays.toString(badUtf8));
            assertEquals(1007, ex.getStatusCode(),
                    "RFC 6455 §8.1 requires 1007 for invalid UTF-8");
        }
    }

    @Test
    @DisplayName("Challenge 2.8: Valid complex UTF-8 text frames (CJK, Cyrillic, Emoji 4-byte) must succeed")
    void shouldAcceptValidComplexUtf8() {
        String[] validStrings = {
                "Hello, World!",
                "Olá, mundo! Café e pão de queijo.",
                "Привет, мир! Тестирование WebSocket.",
                "你好，世界！RFC 6455 协议引擎测试。",
                "🚀🎉🔥 WebSockets on Virtual Threads with Project Loom! 🤖✨"
        };

        byte[] mask = {0x37, (byte) 0xFA, 0x21, 0x3D};

        for (String text : validStrings) {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            byte[] wireFrame = buildClientFrame(WebSocketOpcode.TEXT, payload, mask, true);

            WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(wireFrame));
            assertNotNull(parsed);
            assertEquals(text, parsed.getPayloadAsText());
        }
    }

    @Test
    @DisplayName("Challenge 2.9: Control frame with length > 125 or FIN=0 must throw 1002")
    void shouldRejectControlFramesViolatingConstraintsWith1002() {
        byte[] mask = {1, 2, 3, 4};

        for (WebSocketOpcode ctrlOpcode : new WebSocketOpcode[]{WebSocketOpcode.CLOSE, WebSocketOpcode.PING, WebSocketOpcode.PONG}) {
            // FIN=0 on control frame
            byte[] fragCtrl = buildClientFrame(ctrlOpcode, new byte[0], mask, false);
            WebSocketException ex1 = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(fragCtrl)),
                    "Fragmented control frame must throw 1002 for " + ctrlOpcode);
            assertEquals(1002, ex1.getStatusCode());

            // Length > 125 on control frame (e.g. 126 bytes)
            byte[] overCtrl = buildClientFrame(ctrlOpcode, new byte[126], mask, true);
            WebSocketException ex2 = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(overCtrl)),
                    "Control frame length 126 must throw 1002 for " + ctrlOpcode);
            assertEquals(1002, ex2.getStatusCode());
        }
    }

    @Test
    @DisplayName("Challenge 2.10: Close frame with payload length 1 must throw 1002")
    void shouldRejectCloseFrameWithPayloadLength1With1002() {
        byte[] mask = {1, 2, 3, 4};
        byte[] closeFrame1Byte = buildClientFrame(WebSocketOpcode.CLOSE, new byte[]{0x03}, mask, true);

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(closeFrame1Byte)));
        assertEquals(1002, ex.getStatusCode());
    }

    @Test
    @DisplayName("Challenge 2.11: Wire-forbidden Close status codes (1005, 1006, 1015) must throw 1002")
    void shouldRejectWireForbiddenCloseStatusCodesWith1002() {
        byte[] mask = {1, 2, 3, 4};
        int[] forbiddenCodes = {1005, 1006, 1015, 1004, 999, 0, 1012, 1999, 2999, 5000};

        for (int code : forbiddenCodes) {
            byte[] payload = new byte[]{(byte) ((code >>> 8) & 0xFF), (byte) (code & 0xFF)};
            byte[] frame = buildClientFrame(WebSocketOpcode.CLOSE, payload, mask, true);

            WebSocketException ex = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(frame)),
                    "Failed to reject wire-forbidden code: " + code);
            assertEquals(1002, ex.getStatusCode(),
                    "RFC 6455 §7.4.1 mandates 1002 on wire-forbidden code: " + code);
        }
    }

    @Test
    @DisplayName("Challenge 2.12: Unknown / reserved opcode (0x3..0x7, 0xB..0xF) must throw 1002")
    void shouldRejectUnknownOpcodesWith1002() {
        int[] unknownOpcodes = {0x3, 0x4, 0x5, 0x6, 0x7, 0xB, 0xC, 0xD, 0xE, 0xF};

        for (int op : unknownOpcodes) {
            byte[] frame = new byte[]{
                    (byte) (0x80 | op), // FIN=1, unknown opcode
                    (byte) 0x80,        // MASK=1, len=0
                    1, 2, 3, 4
            };

            WebSocketException ex = assertThrows(WebSocketException.class,
                    () -> WebSocketFrameParser.parse(ByteBuffer.wrap(frame)),
                    "Failed to reject opcode: 0x" + Integer.toHexString(op));
            assertEquals(1002, ex.getStatusCode());
        }
    }

    @Test
    @DisplayName("Challenge 2.13: WebSocketFrameWriter strictly enforces unmasked invariant and valid codes")
    void shouldVerifyWebSocketFrameWriterInvariants() {
        byte[] textFrame = WebSocketFrameWriter.createTextFrame("Bedrock");
        // Server frame MUST NOT be masked
        assertEquals(0x00, textFrame[1] & 0x80, "Server frame MASK bit must be 0");

        // Forbidden send codes must throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketFrameWriter.createCloseFrame(1005, "Forbidden"));
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketFrameWriter.createCloseFrame(1006, "Forbidden"));
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketFrameWriter.createCloseFrame(1015, "Forbidden"));

        // Close reason UTF-8 byte length > 123 (making total payload > 125) must throw IllegalArgumentException
        String longReason = "R".repeat(124);
        assertThrows(IllegalArgumentException.class,
                () -> WebSocketFrameWriter.createCloseFrame(1000, longReason));
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private static byte[] buildClientFrame(WebSocketOpcode opcode, byte[] payload, byte[] maskKey, boolean fin) {
        int length = payload.length;
        int headerSize = 2;
        if (length <= 125) {
            // no extra length bytes
        } else if (length <= 65535) {
            headerSize += 2;
        } else {
            headerSize += 8;
        }
        headerSize += 4; // mask

        byte[] frame = new byte[headerSize + length];
        frame[0] = (byte) ((fin ? 0x80 : 0x00) | (opcode.getCode() & 0x0F));

        int offset = 2;
        if (length <= 125) {
            frame[1] = (byte) (0x80 | length);
        } else if (length <= 65535) {
            frame[1] = (byte) (0x80 | 126);
            frame[offset++] = (byte) ((length >>> 8) & 0xFF);
            frame[offset++] = (byte) (length & 0xFF);
        } else {
            frame[1] = (byte) (0x80 | 127);
            long len = length;
            frame[offset++] = (byte) ((len >>> 56) & 0xFF);
            frame[offset++] = (byte) ((len >>> 48) & 0xFF);
            frame[offset++] = (byte) ((len >>> 40) & 0xFF);
            frame[offset++] = (byte) ((len >>> 32) & 0xFF);
            frame[offset++] = (byte) ((len >>> 24) & 0xFF);
            frame[offset++] = (byte) ((len >>> 16) & 0xFF);
            frame[offset++] = (byte) ((len >>> 8) & 0xFF);
            frame[offset++] = (byte) (len & 0xFF);
        }

        System.arraycopy(maskKey, 0, frame, offset, 4);
        offset += 4;

        for (int i = 0; i < length; i++) {
            frame[offset + i] = (byte) (payload[i] ^ maskKey[i & 3]);
        }

        return frame;
    }
}
