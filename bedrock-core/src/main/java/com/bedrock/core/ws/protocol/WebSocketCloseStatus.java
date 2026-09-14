package com.bedrock.core.ws.protocol;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Close Status Codes & Wire Validation
 *
 * <p>Unlike an abrupt TCP FIN disconnection or socket reset, WebSockets perform an orderly
 * application-level two-way close handshake (RFC 6455 §7.1). Either endpoint may initiate
 * connection closure by sending an {@code Opcode 0x8 (CLOSE)} control frame.</p>
 *
 * <h3>1. Anatomy of a Close Frame Payload:</h3>
 * <p>
 * If the Close frame carries a payload, the first 2 bytes represent a 16-bit big-endian unsigned
 * integer denoting the RFC 6455 status code. Any bytes following this 2-byte prefix represent
 * an optional human-readable UTF-8 reason string explaining why the connection was closed:
 * </p>
 * <pre>
 *   0                   1                   2
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 ...
 *  +-------------------------------+-----------------------------------+
 *  |       Close Status Code       |     Optional UTF-8 Reason...      |
 *  +-------------------------------+-----------------------------------+
 * </pre>
 *
 * <h3>2. Why Are Status Codes 1005, 1006, and 1015 Forbidden on the Wire?</h3>
 * <p>
 * RFC 6455 §7.4.1 defines certain status codes exclusively for internal JVM and application APIs:
 * </p>
 * <ul>
 *   <li><b>1005 (No Status Received)</b>: Designated for internal use to report that a peer closed
 *       the connection with an empty Close frame (0 bytes of payload). It MUST NOT be sent on the wire.</li>
 *   <li><b>1006 (Abnormal Closure)</b>: Designated for internal use to report that the underlying TCP
 *       socket dropped abnormally without receiving any Close control frame. It MUST NOT be sent on the wire.</li>
 *   <li><b>1015 (TLS Handshake Failure)</b>: Designated for internal use when TLS certificate verification fails.</li>
 * </ul>
 * <p>
 * If an incoming Close frame physically contains 1005, 1006, or 1015 on the wire, RFC 6455 §7.4.1
 * mandates that this is a protocol violation, and the receiving endpoint MUST fail the connection
 * with close code <b>1002 (Protocol Error)</b>.
 * </p>
 *
 * <h3>3. RFC 6455 §7.4.2 Status Code Ranges:</h3>
 * <ul>
 *   <li>{@code 0 - 999}: Unused and forbidden.</li>
 *   <li>{@code 1000 - 2999}: Reserved for RFC 6455 and future IETF standards. Unassigned codes (e.g. 1004, 1016-2999) cannot be sent on the wire.</li>
 *   <li>{@code 3000 - 3999}: Reserved for framework, library, and container registration via IANA.</li>
 *   <li>{@code 4000 - 4999}: Reserved for private, application-defined custom status codes.</li>
 * </ul>
 */
public enum WebSocketCloseStatus {

    /**
     * 1000: Normal closure; the purpose for which the connection was established has been fulfilled.
     */
    NORMAL_CLOSURE(1000, "Normal Closure"),

    /**
     * 1001: Endpoint is "going away", such as a server going down or a browser navigating away.
     */
    GOING_AWAY(1001, "Going Away"),

    /**
     * 1002: Endpoint terminated the connection due to a protocol error.
     */
    PROTOCOL_ERROR(1002, "Protocol Error"),

    /**
     * 1003: Endpoint received a type of data it cannot accept (e.g. binary data on a text-only endpoint).
     */
    UNSUPPORTED_DATA(1003, "Unsupported Data"),

    /**
     * 1005: Reserved value indicating that no status code was present in a Close frame. (Wire-forbidden).
     */
    NO_STATUS_RECEIVED(1005, "No Status Received"),

    /**
     * 1006: Reserved value indicating that the connection was closed abnormally. (Wire-forbidden).
     */
    ABNORMAL_CLOSURE(1006, "Abnormal Closure"),

    /**
     * 1007: Endpoint received payload data that was not consistent with the message type (e.g. non-UTF-8 in text frame).
     */
    INVALID_FRAME_PAYLOAD_DATA(1007, "Invalid Frame Payload Data"),

    /**
     * 1008: Endpoint terminated the connection because it received a message that violates its policy.
     */
    POLICY_VIOLATION(1008, "Policy Violation"),

    /**
     * 1009: Endpoint terminated the connection because it received a message too large to process.
     */
    MESSAGE_TOO_BIG(1009, "Message Too Big"),

    /**
     * 1010: Client terminated connection because server failed to negotiate one or more required extensions.
     */
    MANDATORY_EXTENSION(1010, "Mandatory Extension"),

    /**
     * 1011: Server encountered an unexpected condition that prevented it from fulfilling the request.
     */
    INTERNAL_SERVER_ERROR(1011, "Internal Server Error"),

    /**
     * 1015: Reserved value indicating failure to perform a TLS handshake. (Wire-forbidden).
     */
    TLS_HANDSHAKE_FAILURE(1015, "TLS Handshake Failure");

    // Standard integer constants for use in switch statements and code references
    public static final int NORMAL = 1000;
    public static final int NORMAL_CLOSURE_CODE = 1000;
    public static final int GOING_AWAY_CODE = 1001;
    public static final int PROTOCOL_ERROR_CODE = 1002;
    public static final int UNSUPPORTED_DATA_CODE = 1003;
    public static final int NO_STATUS_CODE = 1005;
    public static final int ABNORMAL_CLOSURE_CODE = 1006;
    public static final int INVALID_DATA_CODE = 1007;
    public static final int INVALID_PAYLOAD_DATA = 1007;
    public static final int POLICY_VIOLATION_CODE = 1008;
    public static final int MESSAGE_TOO_BIG_CODE = 1009;
    public static final int MANDATORY_EXTENSION_CODE = 1010;
    public static final int SERVER_ERROR_CODE = 1011;
    public static final int TLS_ERROR_CODE = 1015;

    private final int code;
    private final String description;

    WebSocketCloseStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the 16-bit numeric close status code.
     *
     * @return Integer status code.
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the human-readable description of this close status code.
     *
     * @return Descriptive phrase.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns true if this status code is strictly reserved for local JVM use and must not appear on the wire.
     *
     * @return True for 1005, 1006, 1015; false for all valid wire codes.
     */
    public boolean isWireForbidden() {
        return isWireForbidden(code);
    }

    /**
     * Checks whether an integer status code is forbidden on the physical network wire (RFC 6455 §7.4.1).
     *
     * @param code The status code to check.
     * @return True if 1005, 1006, or 1015; false otherwise.
     */
    public static boolean isWireForbidden(int code) {
        return code == 1005 || code == 1006 || code == 1015;
    }

    /**
     * Checks whether an integer status code may be legally sent on the wire in an outbound Close frame.
     *
     * @param code The status code to validate.
     * @return True if valid for outbound transmission; false otherwise.
     */
    public static boolean isValidSendCode(int code) {
        if (isWireForbidden(code)) {
            return false;
        }
        if (code >= 1000 && code <= 1011 && code != 1004) {
            return true;
        }
        return code >= 3000 && code <= 4999;
    }

    /**
     * Validates that a status code is valid to be placed into an outbound Close frame.
     *
     * @param code The status code to validate.
     * @throws IllegalArgumentException If the code is wire-forbidden or outside the legal ranges.
     */
    public static void validateSendCode(int code) {
        if (isWireForbidden(code)) {
            throw new IllegalArgumentException(
                    "Cannot send status code " + code + " on the wire: reserved for local JVM use only (RFC 6455 §7.4.1)"
            );
        }
        if (code < 1000 || code > 4999 || (code >= 1012 && code <= 2999) || code == 1004) {
            throw new IllegalArgumentException(
                    "Status code " + code + " is not a valid wire status code according to RFC 6455 §7.4.2"
            );
        }
    }

    /**
     * Validates that an incoming status code received inside an inbound Close frame is compliant with RFC 6455.
     *
     * @param code The status code received on the wire.
     * @throws WebSocketException With status 1002 (Protocol Error) if the code is wire-forbidden or invalid.
     */
    public static void validateReceivedCode(int code) {
        if (isWireForbidden(code) || code < 1000 || code > 4999 || (code >= 1012 && code <= 2999) || code == 1004) {
            throw new WebSocketException(
                    PROTOCOL_ERROR_CODE,
                    "Received invalid or wire-forbidden close status code: " + code
            );
        }
    }

    /**
     * Resolves a known RFC 6455 close status enum by its numeric code.
     *
     * @param code The integer code.
     * @return The matching {@link WebSocketCloseStatus}, or null if not an enumerated constant (e.g. custom 4xxx code).
     */
    public static WebSocketCloseStatus fromCode(int code) {
        for (WebSocketCloseStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * Returns a descriptive string for any close status code, including framework and application ranges.
     *
     * @param code The integer status code.
     * @return A descriptive phrase.
     */
    public static String getReasonPhrase(int code) {
        WebSocketCloseStatus status = fromCode(code);
        if (status != null) {
            return status.getDescription();
        }
        if (code >= 3000 && code <= 3999) {
            return "Framework Registered Status (" + code + ")";
        }
        if (code >= 4000 && code <= 4999) {
            return "Application Custom Status (" + code + ")";
        }
        return "Unknown Status (" + code + ")";
    }
}
