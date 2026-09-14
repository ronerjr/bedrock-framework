package com.bedrock.core.ws.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite for {@link WebSocketFrame}, {@link WebSocketFrameParser},
 * and {@link WebSocketFrameWriter}.
 *
 * Validates RFC 6455 §5 framing specification:
 * - Masked frame decoding and 4-byte XOR unmasking.
 * - Server unmasked frame generation.
 * - Length boundaries (7-bit, 16-bit extended, 64-bit extended).
 * - Control frames (Ping, Pong, Close).
 * - Protocol violations with mandatory status codes (1002, 1007).
 */
class WebSocketFrameTest {

    // =========================================================================
    // 1. OFFICIAL RFC 6455 TEST VECTORS (§5.7)
    // =========================================================================

    @Test
    @DisplayName("RFC 6455 §5.7: Decode official single-frame masked text message 'Hello'")
    void shouldDecodeOfficialRfc6455MaskedHelloTextFrame() {
        // RFC 6455 Section 5.7 wire vector:
        // Byte 0: 0x81 (FIN=1, Opcode=1 Text)
        // Byte 1: 0x85 (MASK=1, Len=5)
        // Bytes 2-5: Masking key 0x37 0xfa 0x21 0x3d
        // Bytes 6-10: Masked payload 0x7f 0x9f 0x4d 0x51 0x58
        byte[] wireBytes = new byte[]{
                (byte) 0x81, (byte) 0x85,
                (byte) 0x37, (byte) 0xfa, (byte) 0x21, (byte) 0x3d,
                (byte) 0x7f, (byte) 0x9f, (byte) 0x4d, (byte) 0x51, (byte) 0x58
        };

        WebSocketFrame frame = WebSocketFrameParser.parse(ByteBuffer.wrap(wireBytes));

        assertNotNull(frame);
        assertTrue(frame.isFin(), "FIN bit must be true");
        assertEquals(WebSocketOpcode.TEXT, frame.getOpcode(), "Opcode must be TEXT (0x1)");
        assertTrue(frame.isMasked(), "Frame must be marked as masked");
        assertEquals("Hello", frame.getPayloadAsText(), "Payload must unmask to 'Hello'");
    }

    @Test
    @DisplayName("RFC 6455 §5.7: Encode official single-frame unmasked text message 'Hello'")
    void shouldEncodeOfficialRfc6455UnmaskedHelloTextFrame() {
        // RFC 6455 Section 5.7 unmasked server wire vector:
        // 0x81 0x05 0x48 0x65 0x6c 0x6c 0x6f
        byte[] expected = new byte[]{
                (byte) 0x81, (byte) 0x05,
                0x48, 0x65, 0x6c, 0x6c, 0x6f
        };

        byte[] actual = WebSocketFrameWriter.createTextFrame("Hello");

        assertArrayEquals(expected, actual, "Server text frame must match RFC 6455 §5.7 unmasked vector");
    }

    // =========================================================================
    // 2. PAYLOAD LENGTH BOUNDARIES: 7-BIT (<126), 16-BIT, 64-BIT
    // =========================================================================

    @Test
    @DisplayName("Encode and decode empty text frame (7-bit payload length 0)")
    void shouldEncodeAndDecodeEmptyTextFrame() {
        byte[] serverFrame = WebSocketFrameWriter.createTextFrame("");
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x00}, serverFrame);

        // Masked empty frame from client: header + 4-byte mask, 0 payload bytes
        byte[] clientFrame = new byte[]{
                (byte) 0x81, (byte) 0x80,
                0x10, 0x20, 0x30, 0x40
        };
        WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame));
        assertNotNull(parsed);
        assertTrue(parsed.isFin());
        assertEquals(WebSocketOpcode.TEXT, parsed.getOpcode());
        assertEquals("", parsed.getPayloadAsText());
    }

    @Test
    @DisplayName("Encode and decode max 7-bit payload length boundary (125 bytes)")
    void shouldEncodeAndDecode7BitBoundaryTextFrame() {
        String payload = "A".repeat(125);
        byte[] serverFrame = WebSocketFrameWriter.createTextFrame(payload);

        assertEquals(127, serverFrame.length, "Total frame length must be 2 header bytes + 125 payload");
        assertEquals((byte) 0x81, serverFrame[0]);
        assertEquals((byte) 125, serverFrame[1]);

        // Client masked frame round-trip
        byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.TEXT, payload.getBytes(StandardCharsets.UTF_8));
        WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame));
        assertNotNull(parsed);
        assertEquals(payload, parsed.getPayloadAsText());
    }

    @Test
    @DisplayName("Encode and decode min 16-bit payload length boundary (126 bytes)")
    void shouldEncodeAndDecode16BitMinBoundaryPayload() {
        String payload = "B".repeat(126);
        byte[] serverFrame = WebSocketFrameWriter.createTextFrame(payload);

        assertEquals(130, serverFrame.length, "Total frame length must be 4 header bytes + 126 payload");
        assertEquals((byte) 0x81, serverFrame[0]);
        assertEquals((byte) 126, serverFrame[1], "Payload indicator must be 126 (0x7E)");
        assertEquals((byte) 0x00, serverFrame[2], "16-bit extended length MSB must be 0x00");
        assertEquals((byte) 126, serverFrame[3], "16-bit extended length LSB must be 126");

        // Client masked frame round-trip
        byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.TEXT, payload.getBytes(StandardCharsets.UTF_8));
        WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame));
        assertNotNull(parsed);
        assertEquals(payload, parsed.getPayloadAsText());
    }

    @Test
    @DisplayName("Encode and decode 16-bit medium payloads (500, 1000, 65000 bytes)")
    void shouldEncodeAndDecode16BitMediumPayloads() {
        for (int size : new int[]{500, 1000, 65000}) {
            byte[] raw = new byte[size];
            Arrays.fill(raw, (byte) 'X');
            String payload = new String(raw, StandardCharsets.UTF_8);

            byte[] serverFrame = WebSocketFrameWriter.createTextFrame(payload);
            assertEquals((byte) 0x81, serverFrame[0]);
            assertEquals((byte) 126, serverFrame[1]);
            int encodedLen = ((serverFrame[2] & 0xFF) << 8) | (serverFrame[3] & 0xFF);
            assertEquals(size, encodedLen);

            // Client masked frame round-trip
            byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.TEXT, raw);
            WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame));
            assertNotNull(parsed);
            assertEquals(size, parsed.getPayload().length);
            assertArrayEquals(raw, parsed.getPayload());
        }
    }

    @Test
    @DisplayName("Encode and decode max 16-bit boundary payload (65,535 bytes)")
    void shouldEncodeAndDecode16BitMaxBoundaryPayload() {
        byte[] raw = new byte[65535];
        Arrays.fill(raw, (byte) 'Z');

        byte[] serverFrame = WebSocketFrameWriter.createTextFrame(new String(raw, StandardCharsets.ISO_8859_1));
        assertEquals((byte) 0x81, serverFrame[0]);
        assertEquals((byte) 126, serverFrame[1]);
        assertEquals((byte) 0xFF, serverFrame[2]);
        assertEquals((byte) 0xFF, serverFrame[3]);

        byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.BINARY, raw);
        WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame));
        assertNotNull(parsed);
        assertEquals(65535, parsed.getPayload().length);
        assertArrayEquals(raw, parsed.getPayload());
    }

    @Test
    @DisplayName("Encode and decode 64-bit large payload (>65,535 bytes, e.g. 70,000 bytes)")
    void shouldEncodeAndDecode64BitLargePayload() {
        int size = 70_000;
        byte[] raw = new byte[size];
        Arrays.fill(raw, (byte) 'Q');

        byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.BINARY, raw);
        WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame));

        assertNotNull(parsed);
        assertTrue(parsed.isFin());
        assertEquals(WebSocketOpcode.BINARY, parsed.getOpcode());
        assertEquals(size, parsed.getPayload().length);
        assertArrayEquals(raw, parsed.getPayload());
    }

    // =========================================================================
    // 3. CONTROL FRAMES: PING, PONG, CLOSE
    // =========================================================================

    @Test
    @DisplayName("Encode and decode Ping and Pong control frames with payload echo")
    void shouldEncodeAndDecodePingAndPongControlFrames() {
        byte[] pingPayload = "bedrock-ping".getBytes(StandardCharsets.UTF_8);

        // Server Ping
        byte[] serverPing = WebSocketFrameWriter.createPingFrame(pingPayload);
        assertEquals((byte) 0x89, serverPing[0], "Ping opcode is 0x9 with FIN=1");
        assertEquals((byte) pingPayload.length, serverPing[1]);

        // Client Ping masked
        byte[] clientPing = createMaskedClientFrame(WebSocketOpcode.PING, pingPayload);
        WebSocketFrame parsedPing = WebSocketFrameParser.parse(ByteBuffer.wrap(clientPing));
        assertNotNull(parsedPing);
        assertEquals(WebSocketOpcode.PING, parsedPing.getOpcode());
        assertArrayEquals(pingPayload, parsedPing.getPayload());

        // Server Pong replying with identical payload
        byte[] serverPong = WebSocketFrameWriter.createPongFrame(parsedPing.getPayload());
        assertEquals((byte) 0x8A, serverPong[0], "Pong opcode is 0xA with FIN=1");
        assertArrayEquals(pingPayload, Arrays.copyOfRange(serverPong, 2, serverPong.length));
    }

    @Test
    @DisplayName("Encode and decode Close frame with status code 1000 and reason string")
    void shouldEncodeAndDecodeCloseFrameWithStatusCodeAndReason() {
        int statusCode = 1000;
        String reason = "Normal Closure";

        byte[] serverClose = WebSocketFrameWriter.createCloseFrame(statusCode, reason);
        assertEquals((byte) 0x88, serverClose[0], "Close opcode is 0x8 with FIN=1");
        assertEquals((byte) (2 + reason.getBytes(StandardCharsets.UTF_8).length), serverClose[1]);

        // Verify status code bytes big-endian: 1000 = 0x03E8
        assertEquals((byte) 0x03, serverClose[2]);
        assertEquals((byte) 0xE8, serverClose[3]);

        // Client Close frame round-trip
        ByteBuffer closeBody = ByteBuffer.allocate(2 + reason.getBytes(StandardCharsets.UTF_8).length);
        closeBody.putShort((short) statusCode);
        closeBody.put(reason.getBytes(StandardCharsets.UTF_8));

        byte[] clientClose = createMaskedClientFrame(WebSocketOpcode.CLOSE, closeBody.array());
        WebSocketFrame parsedClose = WebSocketFrameParser.parse(ByteBuffer.wrap(clientClose));

        assertNotNull(parsedClose);
        assertEquals(WebSocketOpcode.CLOSE, parsedClose.getOpcode());
        assertEquals(1000, parsedClose.getCloseStatusCode());
        assertEquals("Normal Closure", parsedClose.getCloseReason());
    }

    @Test
    @DisplayName("Encode and decode empty Close frame (0-byte payload)")
    void shouldEncodeAndDecodeEmptyCloseFrame() {
        byte[] emptyClose = createMaskedClientFrame(WebSocketOpcode.CLOSE, new byte[0]);
        WebSocketFrame parsed = WebSocketFrameParser.parse(ByteBuffer.wrap(emptyClose));

        assertNotNull(parsed);
        assertEquals(WebSocketOpcode.CLOSE, parsed.getOpcode());
        // RFC 6455 §7.4.1: If no status code was present, internal code 1005 is assumed
        assertEquals(1005, parsed.getCloseStatusCode());
        assertEquals("", parsed.getCloseReason());
    }

    // =========================================================================
    // 4. PROTOCOL VIOLATIONS & EDGE CASES (§1002 & §1007 ERRORS)
    // =========================================================================

    @Test
    @DisplayName("RFC 6455 §5.1: Client unmasked frame MUST throw WebSocketException with code 1002")
    void shouldRejectClientUnmaskedFrameWithCode1002() {
        // MASK bit is 0 in client-to-server frame
        byte[] unmaskedClientFrame = new byte[]{
                (byte) 0x81, (byte) 0x05,
                'H', 'e', 'l', 'l', 'o'
        };

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(unmaskedClientFrame)),
                "Unmasked client frames must trigger protocol error");

        assertEquals(1002, ex.getStatusCode(), "Error code must be 1002 (Protocol Error)");
    }

    @Test
    @DisplayName("RFC 6455 §5.2: Non-zero RSV bits MUST throw WebSocketException with code 1002")
    void shouldRejectNonZeroRsvBitsWithCode1002() {
        // RSV1 set (0x40): 0x80 | 0x40 | 0x01 = 0xC1
        byte[] rsv1Frame = createMaskedClientFrame(WebSocketOpcode.TEXT, new byte[0]);
        rsv1Frame[0] = (byte) 0xC1;

        WebSocketException ex1 = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(rsv1Frame)));
        assertEquals(1002, ex1.getStatusCode());

        // RSV2 set (0x20): 0x80 | 0x20 | 0x01 = 0xA1
        byte[] rsv2Frame = createMaskedClientFrame(WebSocketOpcode.TEXT, new byte[0]);
        rsv2Frame[0] = (byte) 0xA1;

        WebSocketException ex2 = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(rsv2Frame)));
        assertEquals(1002, ex2.getStatusCode());

        // RSV3 set (0x10): 0x80 | 0x10 | 0x01 = 0x91
        byte[] rsv3Frame = createMaskedClientFrame(WebSocketOpcode.TEXT, new byte[0]);
        rsv3Frame[0] = (byte) 0x91;

        WebSocketException ex3 = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(rsv3Frame)));
        assertEquals(1002, ex3.getStatusCode());
    }

    @Test
    @DisplayName("RFC 6455 §5.2: Non-minimal length indicator 126 for length < 126 MUST throw 1002")
    void shouldRejectNonMinimalLengthEncoding126WithCode1002() {
        // 19-byte payload encoded using extended 16-bit indicator (126) instead of 7-bit
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + 4 + 19);
        buf.put((byte) 0x81);              // FIN=1, TEXT
        buf.put((byte) 0xFE);              // MASK=1 | 126
        buf.putShort((short) 19);          // Extended length 19 (< 126 is illegal!)
        buf.put(new byte[]{1, 2, 3, 4});   // 4-byte mask
        buf.put(new byte[19]);             // 19 payload bytes

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(buf.array())));
        assertEquals(1002, ex.getStatusCode(), "Non-minimal 16-bit length must throw 1002");
    }

    @Test
    @DisplayName("RFC 6455 §5.2: Non-minimal length indicator 127 for length < 65536 MUST throw 1002")
    void shouldRejectNonMinimalLengthEncoding127WithCode1002() {
        // 500-byte payload encoded using extended 64-bit indicator (127) instead of 16-bit
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4 + 500);
        buf.put((byte) 0x81);              // FIN=1, TEXT
        buf.put((byte) 0xFF);              // MASK=1 | 127
        buf.putLong(500L);                 // Extended 64-bit length 500 (< 65536 is illegal!)
        buf.put(new byte[]{1, 2, 3, 4});   // 4-byte mask
        buf.put(new byte[500]);            // 500 payload bytes

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(buf.array())));
        assertEquals(1002, ex.getStatusCode(), "Non-minimal 64-bit length must throw 1002");
    }

    @Test
    @DisplayName("RFC 6455 §5.5: Control frame with FIN=0 MUST throw 1002")
    void shouldRejectControlFrameWithFinZeroWithCode1002() {
        // Ping frame with FIN=0 (0x09)
        byte[] fragmentedPing = createMaskedClientFrame(WebSocketOpcode.PING, new byte[0]);
        fragmentedPing[0] = (byte) 0x09; // Clear FIN bit

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(fragmentedPing)));
        assertEquals(1002, ex.getStatusCode(), "Fragmented control frames are strictly forbidden");
    }

    @Test
    @DisplayName("RFC 6455 §5.5: Control frame with payload > 125 bytes MUST throw 1002")
    void shouldRejectControlFrameWithPayloadGreaterThan125WithCode1002() {
        // Ping frame with payload length 126
        byte[] overSizedPing = createMaskedClientFrame(WebSocketOpcode.PING, new byte[126]);

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(overSizedPing)));
        assertEquals(1002, ex.getStatusCode(), "Control frame payload > 125 must throw 1002");
    }

    @Test
    @DisplayName("RFC 6455 §5.5.1: Close frame with payload length == 1 MUST throw 1002")
    void shouldRejectCloseFrameWithPayloadLength1WithCode1002() {
        // Close frame body must be 0 or >= 2 bytes
        byte[] invalidClose = createMaskedClientFrame(WebSocketOpcode.CLOSE, new byte[]{0x03});

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(invalidClose)));
        assertEquals(1002, ex.getStatusCode(), "1-byte close frame payload violates §5.5.1");
    }

    @Test
    @DisplayName("RFC 6455 §8.1: Invalid UTF-8 bytes in Text payload MUST throw 1007")
    void shouldRejectMalformedUtf8InTextPayloadWithCode1007() {
        // 0xC3 0x28 is an invalid UTF-8 multi-byte sequence
        byte[] invalidUtf8 = new byte[]{(byte) 0xC3, (byte) 0x28};
        byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.TEXT, invalidUtf8);

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame)));
        assertEquals(1007, ex.getStatusCode(), "Malformed UTF-8 text frame must trigger 1007 close code");
    }

    @Test
    @DisplayName("RFC 6455 §8.1: Invalid UTF-8 bytes in Close reason MUST throw 1007")
    void shouldRejectMalformedUtf8InCloseReasonWithCode1007() {
        // 2-byte status code 1000 followed by malformed UTF-8 bytes (0xFF 0xFF)
        byte[] payload = new byte[]{(byte) 0x03, (byte) 0xE8, (byte) 0xFF, (byte) 0xFF};
        byte[] clientFrame = createMaskedClientFrame(WebSocketOpcode.CLOSE, payload);

        WebSocketException ex = assertThrows(WebSocketException.class,
                () -> WebSocketFrameParser.parse(ByteBuffer.wrap(clientFrame)));
        assertEquals(1007, ex.getStatusCode(), "Malformed UTF-8 in close reason must trigger 1007");
    }

    // =========================================================================
    // HELPER: MASKED CLIENT FRAME GENERATOR
    // =========================================================================

    private static byte[] createMaskedClientFrame(WebSocketOpcode opcode, byte[] payload) {
        int length = payload.length;
        int headerSize = 2; // b0, b1
        if (length <= 125) {
            // no extended length bytes
        } else if (length <= 65535) {
            headerSize += 2;
        } else {
            headerSize += 8;
        }
        headerSize += 4; // 4-byte mask

        byte[] frame = new byte[headerSize + length];
        frame[0] = (byte) (0x80 | (opcode.getCode() & 0x0F)); // FIN=1 | opcode

        int offset = 2;
        if (length <= 125) {
            frame[1] = (byte) (0x80 | length); // MASK=1 | length
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

        byte[] mask = new byte[]{0x37, (byte) 0xFA, 0x21, 0x3D};
        System.arraycopy(mask, 0, frame, offset, 4);
        offset += 4;

        for (int i = 0; i < length; i++) {
            frame[offset + i] = (byte) (payload[i] ^ mask[i & 3]);
        }

        return frame;
    }
}
