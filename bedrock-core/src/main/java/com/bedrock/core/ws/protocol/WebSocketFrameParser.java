package com.bedrock.core.ws.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 🎓 BEDROCK TUTORIAL: The Anatomy of a WebSocket Frame & XOR Masking Physics
 *
 * <p>Once the HTTP 101 handshake completes, communication switches from newline-delimited
 * ASCII text headers to a compact, binary-packed framing protocol defined in RFC 6455 §5.</p>
 *
 * <p>In this class, we parse raw binary octets from an NIO {@link ByteBuffer} into
 * strongly-typed {@link WebSocketFrame} records without third-party frameworks.</p>
 *
 * <h3>1. The 32-Bit RFC 6455 Frame Header Layout:</h3>
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-------+-+-------------+-------------------------------+
 *  |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *  |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *  |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *  | |1|2|3|       |K|             |                               |
 *  +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *  |     Extended payload length continued, if payload len == 127  |
 *  + - - - - - - - - - - - - - - - +-------------------------------+
 *  |                               |Masking-key, if MASK set to 1  |
 *  +-------------------------------+-------------------------------+
 *  | Masking-key (continued)       |          Payload Data         |
 *  +-------------------------------- - - - - - - - - - - - - - - - +
 *  :                     Payload Data continued ...                :
 *  + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 *  |                     Payload Data continued ...                |
 *  +---------------------------------------------------------------+
 * </pre>
 *
 * <h3>2. Bitwise Extraction Mechanics:</h3>
 * <ul>
 *   <li><b>Byte 0: Flags & Opcode</b>:
 *     <ul>
 *       <li><b>FIN (Bit 7, {@code 0x80})</b>: 1 if this is the final fragment; 0 otherwise.</li>
 *       <li><b>RSV1-3 (Bits 6-4, {@code 0x70})</b>: Must be 0 unless extension negotiated. Non-zero fails with 1002.</li>
 *       <li><b>Opcode (Bits 3-0, {@code 0x0F})</b>: 0x1 Text, 0x2 Binary, 0x8 Close, 0x9 Ping, 0xA Pong.</li>
 *     </ul>
 *   </li>
 *   <li><b>Byte 1: Mask & Payload Length Indicator</b>:
 *     <ul>
 *       <li><b>MASK (Bit 7, {@code 0x80})</b>: 1 if masked; 0 if unmasked. Clients MUST mask (fails with 1002 if 0).</li>
 *       <li><b>Length Indicator (Bits 6-0, {@code 0x7F})</b>:
 *           0..125 = exact byte length;
 *           126 = unsigned 16-bit extended length (must be &gt;= 126);
 *           127 = unsigned 64-bit extended length (must be &gt;= 65536, MSB must be 0).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>3. Why Client Masking Was Mandated (Transparent Cache Poisoning Prevention):</h3>
 * <p>
 * Before RFC 6455, researchers discovered that an attacker could send bytes over an unmasked
 * WebSocket connection that resembled an HTTP GET request (e.g., {@code GET /updates.js HTTP/1.1}).
 * A buggy corporate transparent caching proxy on port 80 might mistake these bytes for a real HTTP request,
 * fetch the attacker's script, and poison the proxy cache for innocent users.
 * </p>
 * <p>
 * By mandating that clients XOR every payload byte against a freshly generated random 4-byte key,
 * the payload bytes on the physical wire appear as high-entropy pseudo-random noise. No recognizable HTTP
 * headers can appear on the wire, completely neutralizing transparent cache poisoning.
 * </p>
 *
 * <h3>4. The Physics of In-Place XOR Unmasking:</h3>
 * <p>RFC 6455 §5.3 defines the transformation: {@code D[i] = E[i] ^ M[i % 4]}.</p>
 * <p>Because XOR is an involution: {@code (P ^ K) ^ K = P}. In Bedrock, we perform this transformation
 * directly in-place on the byte array using bitwise {@code i & 3} (single CPU instruction),
 * requiring ZERO additional heap allocations.</p>
 */
public final class WebSocketFrameParser {

    /** Maximum allowed frame payload size in bytes (default 16 MB) */
    public static final int MAX_ALLOWED_PAYLOAD_SIZE = 16 * 1024 * 1024;

    private WebSocketFrameParser() {
        // Pure utility class
    }

    /**
     * Parses the next complete {@link WebSocketFrame} from the buffer in server mode (mandatory client masking).
     *
     * @param buffer The NIO buffer containing raw TCP frame bytes.
     * @return The parsed {@link WebSocketFrame}, or null if the buffer does not yet contain a complete frame.
     * @throws WebSocketException If an RFC 6455 protocol violation or encoding error occurs.
     */
    public static WebSocketFrame parse(ByteBuffer buffer) throws WebSocketException {
        return parse(buffer, true, MAX_ALLOWED_PAYLOAD_SIZE);
    }

    /**
     * Parses the next complete {@link WebSocketFrame} from the buffer.
     *
     * @param buffer      The NIO buffer containing raw frame bytes.
     * @param requireMask True if client masking is strictly enforced (server mode).
     * @return The parsed {@link WebSocketFrame}, or null if the buffer does not yet contain a complete frame.
     * @throws WebSocketException If an RFC 6455 protocol violation occurs.
     */
    public static WebSocketFrame parse(ByteBuffer buffer, boolean requireMask) throws WebSocketException {
        return parse(buffer, requireMask, MAX_ALLOWED_PAYLOAD_SIZE);
    }

    /**
     * Parses the next complete {@link WebSocketFrame} from the buffer with a configurable maximum payload size.
     *
     * @param buffer         The NIO buffer containing raw frame bytes.
     * @param requireMask    True if client masking is strictly enforced (server mode).
     * @param maxPayloadSize Maximum allowed frame payload size in bytes.
     * @return The parsed {@link WebSocketFrame}, or null if the buffer does not yet contain a complete frame.
     * @throws WebSocketException If an RFC 6455 protocol violation occurs or payload exceeds maxPayloadSize.
     */
    public static WebSocketFrame parse(ByteBuffer buffer, boolean requireMask, int maxPayloadSize) throws WebSocketException {
        if (buffer == null || buffer.remaining() < 2) {
            return null;
        }

        // Mark buffer position for non-destructive reset if full frame has not yet arrived
        buffer.mark();

        // 1. Byte 0: FIN, RSV1-3, Opcode
        byte b0 = buffer.get();
        boolean fin = (b0 & 0x80) != 0;
        int rsv = (b0 & 0x70) >>> 4;
        int opcodeNum = b0 & 0x0F;

        if (rsv != 0) {
            throw new WebSocketException(
                    WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                    "RSV bits must be 0; received RSV=" + rsv
            );
        }

        WebSocketOpcode opcode = WebSocketOpcode.fromCode(opcodeNum);

        if (opcode.isControl() && !fin) {
            throw new WebSocketException(
                    WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                    "Control frames cannot be fragmented (FIN must be 1 for opcode " + opcode + ")"
            );
        }

        // 2. Byte 1: Mask bit & Payload length indicator
        byte b1 = buffer.get();
        boolean masked = (b1 & 0x80) != 0;
        int lenIndicator = b1 & 0x7F;

        if (requireMask && !masked) {
            throw new WebSocketException(
                    WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                    "Client frame must be masked (RFC 6455 §5.1)"
            );
        }

        // 3. Extended Payload Length & Minimal Length Validation
        long payloadLength;
        if (lenIndicator <= 125) {
            payloadLength = lenIndicator;
        } else if (lenIndicator == 126) {
            if (buffer.remaining() < 2) {
                buffer.reset();
                return null;
            }
            int b2 = buffer.get() & 0xFF;
            int b3 = buffer.get() & 0xFF;
            int extLen = (b2 << 8) | b3;

            // Minimal length enforcement: must be >= 126
            if (extLen < 126) {
                throw new WebSocketException(
                        WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                        "Non-minimal 16-bit payload length encoding: " + extLen + " (must be >= 126)"
                );
            }
            if (extLen > maxPayloadSize) {
                throw new WebSocketException(
                        WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE,
                        "Payload length " + extLen + " exceeds maximum allowed (" + maxPayloadSize + ")"
                );
            }
            payloadLength = extLen;
        } else { // lenIndicator == 127
            if (buffer.remaining() < 8) {
                buffer.reset();
                return null;
            }
            long extLen = buffer.getLong();

            // MSB must be 0 (positive signed long)
            if (extLen < 0) {
                throw new WebSocketException(
                        WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                        "64-bit payload length MSB must be 0 (received negative length: " + extLen + ")"
                );
            }

            // Minimal length enforcement: must be >= 65536
            if (extLen < 65536) {
                throw new WebSocketException(
                        WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                        "Non-minimal 64-bit payload length encoding: " + extLen + " (must be >= 65536)"
                );
            }

            // Protection against excessive memory allocation
            if (extLen > maxPayloadSize) {
                throw new WebSocketException(
                        WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE,
                        "Payload length " + extLen + " exceeds maximum allowed (" + maxPayloadSize + ")"
                );
            }
            payloadLength = extLen;
        }

        // Control frame length invariant (RFC 6455 §5.5)
        if (opcode.isControl() && payloadLength > 125) {
            throw new WebSocketException(
                    WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                    "Control frame payload cannot exceed 125 bytes (received: " + payloadLength + ")"
            );
        }

        // 4. Check whether entire frame (masking key + payload) has arrived
        int requiredBytes = (masked ? 4 : 0) + (int) payloadLength;
        if (buffer.remaining() < requiredBytes) {
            buffer.reset();
            return null;
        }

        // 5. Read 4-byte Masking Key if present
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            buffer.get(maskKey);
        }

        // 6. Read Payload
        byte[] payload = new byte[(int) payloadLength];
        buffer.get(payload);

        // 7. In-place 4-byte XOR Unmasking: D_i = E_i ^ M_{i & 3}
        if (masked && maskKey != null) {
            unmask(payload, maskKey);
        }

        // 8. Payload Semantics & Invariant Checks
        int closeStatusCode = -1;
        String closeReason = null;

        if (opcode == WebSocketOpcode.TEXT) {
            validateUtf8(payload, "text frame");
        } else if (opcode == WebSocketOpcode.CLOSE) {
            if (payload.length == 1) {
                throw new WebSocketException(
                        WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                        "Close frame payload cannot be exactly 1 byte (RFC 6455 §5.5.1)"
                );
            }
            if (payload.length == 0) {
                closeStatusCode = WebSocketCloseStatus.NO_STATUS_CODE; // 1005
                closeReason = "";
            } else {
                int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                WebSocketCloseStatus.validateReceivedCode(code);
                closeStatusCode = code;

                if (payload.length > 2) {
                    byte[] reasonBytes = Arrays.copyOfRange(payload, 2, payload.length);
                    validateUtf8(reasonBytes, "close reason");
                    closeReason = new String(reasonBytes, StandardCharsets.UTF_8);
                } else {
                    closeReason = "";
                }
            }
        }

        return new WebSocketFrame(fin, opcode, masked, payload, closeStatusCode, closeReason);
    }

    /**
     * Performs in-place 4-byte XOR unmasking: {@code D[i] = E[i] ^ M[i & 3]}.
     *
     * @param payload    Array of bytes to be unmasked in-place.
     * @param maskingKey Exactly 4 bytes of masking key.
     * @throws IllegalArgumentException If maskingKey is null or not exactly 4 bytes.
     */
    public static void unmask(byte[] payload, byte[] maskingKey) {
        if (maskingKey == null || maskingKey.length != 4) {
            throw new IllegalArgumentException("Masking key must be exactly 4 bytes");
        }
        if (payload == null || payload.length == 0) {
            return;
        }
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ maskingKey[i & 3]);
        }
    }

    private static void validateUtf8(byte[] bytes, String context) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            throw new WebSocketException(
                    WebSocketCloseStatus.INVALID_DATA_CODE,
                    "Malformed UTF-8 in " + context + ": " + e.getMessage()
            );
        }
    }
}
