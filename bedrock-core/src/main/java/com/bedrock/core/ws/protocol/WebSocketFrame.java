package com.bedrock.core.ws.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Frame Representation
 *
 * <p>In RFC 6455, all data transmitted across an upgraded WebSocket connection is packaged
 * into discrete binary protocol units called <b>Frames</b>. A frame contains metadata flags
 * (FIN, RSV, Opcode), payload length indicators, masking keys, and application payload data.</p>
 *
 * <h3>Design Principles:</h3>
 * <ul>
 *   <li><b>Strict Immutability & Thread Safety</b>: All fields are final. The payload byte array
 *       is defensively cloned during construction and on retrieval via {@link #getPayload()},
 *       preventing malicious or accidental external mutation.</li>
 *   <li><b>Close Frame Intelligence</b>: If the frame is an {@code Opcode 0x8 (CLOSE)} control frame,
 *       the 16-bit status code and UTF-8 reason string are parsed and exposed directly via
 *       {@link #getCloseStatusCode()} and {@link #getCloseReason()}.</li>
 *   <li><b>Zero Dependencies</b>: Relies entirely on standard Java language features and {@code java.nio}.</li>
 * </ul>
 */
public final class WebSocketFrame {

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final boolean fin;
    private final WebSocketOpcode opcode;
    private final boolean masked;
    private final byte[] payload;
    private final int closeStatusCode;
    private final String closeReason;

    /**
     * Primary constructor with explicit close attributes.
     *
     * @param fin             True if this is the final fragment of a message.
     * @param opcode          The RFC 6455 opcode indicating payload type.
     * @param masked          True if the frame was masked on the wire.
     * @param payload         Application data bytes (defensively copied).
     * @param closeStatusCode Close status code if this is a CLOSE frame, or -1 otherwise.
     * @param closeReason     Close reason phrase if this is a CLOSE frame, or null otherwise.
     */
    public WebSocketFrame(boolean fin,
                          WebSocketOpcode opcode,
                          boolean masked,
                          byte[] payload,
                          int closeStatusCode,
                          String closeReason) {
        this.fin = fin;
        this.opcode = Objects.requireNonNull(opcode, "Opcode cannot be null");
        this.masked = masked;
        this.payload = (payload != null && payload.length > 0) ? payload.clone() : EMPTY_PAYLOAD;
        this.closeStatusCode = closeStatusCode;
        this.closeReason = closeReason != null ? closeReason : "";
    }

    /**
     * Convenience constructor that automatically inspects close frames to extract the status code and reason.
     *
     * @param fin     True if this is the final fragment of a message.
     * @param opcode  The RFC 6455 opcode.
     * @param masked  True if masked.
     * @param payload Application data bytes (defensively copied).
     */
    public WebSocketFrame(boolean fin, WebSocketOpcode opcode, boolean masked, byte[] payload) {
        this.fin = fin;
        this.opcode = Objects.requireNonNull(opcode, "Opcode cannot be null");
        this.masked = masked;
        this.payload = (payload != null && payload.length > 0) ? payload.clone() : EMPTY_PAYLOAD;

        if (opcode == WebSocketOpcode.CLOSE) {
            if (this.payload.length == 0) {
                this.closeStatusCode = WebSocketCloseStatus.NO_STATUS_CODE; // 1005
                this.closeReason = "";
            } else if (this.payload.length >= 2) {
                this.closeStatusCode = ((this.payload[0] & 0xFF) << 8) | (this.payload[1] & 0xFF);
                if (this.payload.length > 2) {
                    this.closeReason = new String(this.payload, 2, this.payload.length - 2, StandardCharsets.UTF_8);
                } else {
                    this.closeReason = "";
                }
            } else {
                // 1-byte close payload violates RFC 6455 §5.5.1
                this.closeStatusCode = WebSocketCloseStatus.PROTOCOL_ERROR_CODE;
                this.closeReason = "Invalid 1-byte close payload";
            }
        } else {
            this.closeStatusCode = -1;
            this.closeReason = null;
        }
    }

    // ==========================================
    // Factory Methods
    // ==========================================

    /**
     * Creates an unmasked server text frame.
     *
     * @param text String content to transmit as UTF-8.
     * @return Immutable {@link WebSocketFrame}.
     */
    public static WebSocketFrame text(String text) {
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : EMPTY_PAYLOAD;
        return new WebSocketFrame(true, WebSocketOpcode.TEXT, false, bytes, -1, null);
    }

    /**
     * Creates an unmasked server binary frame.
     *
     * @param data Binary payload bytes.
     * @return Immutable {@link WebSocketFrame}.
     */
    public static WebSocketFrame binary(byte[] data) {
        return new WebSocketFrame(true, WebSocketOpcode.BINARY, false, data, -1, null);
    }

    /**
     * Creates an unmasked server Ping control frame.
     *
     * @param applicationData Optional heartbeat probe payload (must be <= 125 bytes).
     * @return Immutable {@link WebSocketFrame}.
     */
    public static WebSocketFrame ping(byte[] applicationData) {
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Control frame payload cannot exceed 125 bytes");
        }
        return new WebSocketFrame(true, WebSocketOpcode.PING, false, applicationData, -1, null);
    }

    /**
     * Creates an unmasked server Pong control frame.
     *
     * @param applicationData Heartbeat response payload echoing the Ping (must be <= 125 bytes).
     * @return Immutable {@link WebSocketFrame}.
     */
    public static WebSocketFrame pong(byte[] applicationData) {
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Control frame payload cannot exceed 125 bytes");
        }
        return new WebSocketFrame(true, WebSocketOpcode.PONG, false, applicationData, -1, null);
    }

    /**
     * Creates an unmasked server Close control frame with a 16-bit status code and optional reason string.
     *
     * @param statusCode RFC 6455 status code (must not be wire-forbidden: 1005, 1006, 1015).
     * @param reason     Optional UTF-8 reason string.
     * @return Immutable {@link WebSocketFrame}.
     */
    public static WebSocketFrame close(int statusCode, String reason) {
        WebSocketCloseStatus.validateSendCode(statusCode);
        String safeReason = reason != null ? reason : "";
        byte[] reasonBytes = safeReason.getBytes(StandardCharsets.UTF_8);
        if (2 + reasonBytes.length > 125) {
            throw new IllegalArgumentException("Close frame payload cannot exceed 125 bytes (status + reason)");
        }
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((statusCode >>> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);

        return new WebSocketFrame(true, WebSocketOpcode.CLOSE, false, payload, statusCode, safeReason);
    }

    // ==========================================
    // Accessors
    // ==========================================

    /**
     * Returns true if the FIN bit is set to 1, indicating this is the final fragment of a message.
     */
    public boolean isFin() {
        return fin;
    }

    /**
     * Returns the opcode of this frame.
     */
    public WebSocketOpcode getOpcode() {
        return opcode;
    }

    /**
     * Returns true if the frame payload was XOR-masked on the wire.
     */
    public boolean isMasked() {
        return masked;
    }

    /**
     * Returns a defensive copy of the unmasked application payload bytes.
     */
    public byte[] getPayload() {
        return payload.length > 0 ? payload.clone() : EMPTY_PAYLOAD;
    }

    /**
     * Returns the length of the payload in bytes.
     */
    public int getPayloadLength() {
        return payload.length;
    }

    /**
     * Decodes the payload as a UTF-8 string.
     */
    public String getPayloadAsText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Returns the 16-bit close status code if this is a CLOSE frame, or -1 otherwise.
     */
    public int getCloseStatusCode() {
        return closeStatusCode;
    }

    /**
     * Returns the human-readable close reason string if this is a CLOSE frame, or null otherwise.
     */
    public String getCloseReason() {
        return closeReason;
    }

    /**
     * Returns true if this is a control frame (Close, Ping, or Pong).
     */
    public boolean isControl() {
        return opcode.isControl();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebSocketFrame that)) return false;
        return fin == that.fin &&
                masked == that.masked &&
                closeStatusCode == that.closeStatusCode &&
                opcode == that.opcode &&
                Arrays.equals(payload, that.payload) &&
                Objects.equals(closeReason, that.closeReason);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(fin, opcode, masked, closeStatusCode, closeReason);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "WebSocketFrame{" +
                "fin=" + fin +
                ", opcode=" + opcode +
                ", masked=" + masked +
                ", payloadLen=" + payload.length +
                (opcode == WebSocketOpcode.CLOSE ? ", closeStatus=" + closeStatusCode + ", closeReason='" + closeReason + '\'' : "") +
                '}';
    }
}
