package com.bedrock.core.ws.protocol;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 4-Bit Frame Opcodes
 *
 * <p>In standard enterprise frameworks (such as Spring or Netty), frame opcodes are
 * often hidden behind high-level message abstractions. In Bedrock, we study the raw
 * physical bits that traverse the network.</p>
 *
 * <h3>Anatomy of Byte 0 in an RFC 6455 WebSocket Frame:</h3>
 * <pre>
 *   Bit:   7     6     5     4     3   2   1   0
 *        +-----+-----+-----+-----+---------------+
 *        | FIN | RSV1| RSV2| RSV3|    Opcode     |
 *        +-----+-----+-----+-----+---------------+
 * </pre>
 * <p>
 * The Opcode occupies the lower 4 bits ({@code 0x0F}) of Byte 0.
 * RFC 6455 divides opcodes into two strictly segregated classes:
 * </p>
 *
 * <h3>1. Data Opcodes (0x0 - 0x7):</h3>
 * <p>Data frames carry application payload data and may be fragmented across multiple frames:</p>
 * <ul>
 *   <li>{@code 0x0 (CONTINUATION)}: Carries subsequent fragments of a fragmented message.</li>
 *   <li>{@code 0x1 (TEXT)}: Carries UTF-8 formatted text payload.</li>
 *   <li>{@code 0x2 (BINARY)}: Carries arbitrary uninterpreted binary data.</li>
 *   <li>{@code 0x3 - 0x7}: Reserved for future non-control frame extensions.</li>
 * </ul>
 *
 * <h3>2. Control Opcodes (0x8 - 0xF):</h3>
 * <p>Control frames communicate connection state, health, and lifecycle:</p>
 * <ul>
 *   <li>{@code 0x8 (CLOSE)}: Connection close handshake control frame.</li>
 *   <li>{@code 0x9 (PING)}: Heartbeat ping frame (peer MUST respond with Pong).</li>
 *   <li>{@code 0xA (PONG)}: Heartbeat pong frame echoing the Ping payload.</li>
 *   <li>{@code 0xB - 0xF}: Reserved for future control frame extensions.</li>
 * </ul>
 *
 * <h3>The Control Bit Invariant:</h3>
 * <p>
 * Notice that all control opcodes have their most significant bit (Bit 3) set to 1!
 * Mathematically, {@code isControl()} evaluates to {@code (opcode & 0x8) != 0}.
 * Under RFC 6455 §5.5, control frames CANNOT be fragmented (FIN must be 1) and MUST have
 * a payload length of 125 bytes or less.
 * </p>
 */
public enum WebSocketOpcode {

    /**
     * Opcode 0x0: Continuation of a fragmented message.
     */
    CONTINUATION(0x0),

    /**
     * Opcode 0x1: Text frame containing UTF-8 formatted data.
     */
    TEXT(0x1),

    /**
     * Opcode 0x2: Binary frame containing raw binary bytes.
     */
    BINARY(0x2),

    /**
     * Opcode 0x8: Close control frame initiating or replying to connection termination.
     */
    CLOSE(0x8),

    /**
     * Opcode 0x9: Ping control frame used for heartbeat probes.
     */
    PING(0x9),

    /**
     * Opcode 0xA: Pong control frame echoing an incoming Ping.
     */
    PONG(0xA);

    private final int code;

    WebSocketOpcode(int code) {
        this.code = code;
    }

    /**
     * Returns the 4-bit numeric code of this opcode.
     *
     * @return Integer between 0x0 and 0xF.
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the 4-bit numeric code as a primitive byte.
     *
     * @return Byte value.
     */
    public byte getCodeAsByte() {
        return (byte) code;
    }

    /**
     * Returns true if this opcode represents an RFC 6455 control frame (Opcode >= 0x8).
     *
     * @return True for CLOSE, PING, and PONG; false for CONTINUATION, TEXT, and BINARY.
     */
    public boolean isControl() {
        return (code & 0x8) != 0;
    }

    /**
     * Returns true if this opcode represents an application data frame (Opcode < 0x8).
     *
     * @return True for CONTINUATION, TEXT, and BINARY; false for control frames.
     */
    public boolean isData() {
        return !isControl();
    }

    /**
     * Maps an integer code to its corresponding {@link WebSocketOpcode}.
     *
     * @param code The 4-bit opcode from Byte 0.
     * @return The resolved {@link WebSocketOpcode}.
     * @throws WebSocketException If the opcode is unknown or reserved (RFC 6455 §5.2 protocol violation).
     */
    public static WebSocketOpcode fromCode(int code) {
        for (WebSocketOpcode opcode : values()) {
            if (opcode.code == code) {
                return opcode;
            }
        }
        throw new WebSocketException(
                WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                "Unknown or reserved RFC 6455 opcode: 0x" + Integer.toHexString(code)
        );
    }

    /**
     * Checks whether an integer code corresponds to a known, valid RFC 6455 opcode.
     *
     * @param code The 4-bit opcode integer.
     * @return True if recognized; false otherwise.
     */
    public static boolean isValid(int code) {
        for (WebSocketOpcode opcode : values()) {
            if (opcode.code == code) {
                return true;
            }
        }
        return false;
    }
}
