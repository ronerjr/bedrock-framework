package com.bedrock.core.ws.protocol;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Protocol Exceptions & Close Code Propagation
 *
 * <p>In conventional HTTP frameworks, when an error occurs, the server emits an HTTP status
 * code (such as 400 Bad Request or 500 Internal Server Error). In the WebSocket protocol, once
 * the HTTP 101 upgrade has occurred, HTTP status codes no longer exist.</p>
 *
 * <h3>Error Propagation via WebSocket Close Frames:</h3>
 * <p>
 * Whenever a protocol anomaly is detected on the wire—such as an unmasked client frame,
 * non-zero RSV bits, non-minimal length encoding, or malformed UTF-8—the server cannot simply
 * throw an arbitrary unchecked exception and abruptly drop the TCP connection.
 * </p>
 * <p>
 * Instead, RFC 6455 mandates that the server MUST terminate the WebSocket connection by
 * sending an {@code Opcode 0x8 (CLOSE)} control frame containing a specific 2-byte unsigned
 * status code (e.g. {@code 1002 Protocol Error} or {@code 1007 Invalid Frame Payload Data}).
 * </p>
 * <p>
 * {@link WebSocketException} encapsulates this RFC 6455 close status code alongside a diagnostic
 * error message. When caught by the server's Virtual Thread connection loop, it immediately enables
 * the server to serialize a compliant Close frame before gracefully closing the {@code SocketChannel}.
 * </p>
 */
public class WebSocketException extends RuntimeException {

    private final int statusCode;

    /**
     * Constructs a WebSocketException with a specific RFC 6455 close status code and detail message.
     *
     * @param statusCode The 16-bit RFC 6455 status code (e.g., 1002, 1007, 1011).
     * @param message    Diagnostic error description.
     */
    public WebSocketException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Constructs a WebSocketException with a specific RFC 6455 close status code, detail message, and cause.
     *
     * @param statusCode The 16-bit RFC 6455 status code.
     * @param message    Diagnostic error description.
     * @param cause      Root cause exception.
     */
    public WebSocketException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Constructs a WebSocketException defaulting to RFC 6455 {@code 1002 Protocol Error}.
     *
     * @param message Diagnostic error description.
     */
    public WebSocketException(String message) {
        this(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, message);
    }

    /**
     * Constructs a WebSocketException defaulting to RFC 6455 {@code 1011 Internal Server Error}.
     *
     * @param message Diagnostic error description.
     * @param cause   Root cause exception.
     */
    public WebSocketException(String message, Throwable cause) {
        this(WebSocketCloseStatus.SERVER_ERROR_CODE, message, cause);
    }

    /**
     * Returns the 16-bit RFC 6455 close status code associated with this protocol failure.
     *
     * @return Integer close status code.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the reason phrase or message for this exception.
     *
     * @return String reason message.
     */
    public String getReason() {
        return getMessage();
    }

    @Override
    public String toString() {
        return "WebSocketException[code=" + statusCode + " (" +
                WebSocketCloseStatus.getReasonPhrase(statusCode) + "): " + getMessage() + "]";
    }
}
