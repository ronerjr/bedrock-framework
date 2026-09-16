package com.bedrock.core.ws.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 🎓 BEDROCK TUTORIAL: The Physics of HTTP 101 Switching Protocols & RFC 6455 Handshake
 *
 * <p>In standard enterprise frameworks (such as Spring Boot with {@code @EnableWebSocket}),
 * WebSocket connection establishment is an opaque black box. A developer writes an annotation,
 * and the connection "magically" connects.</p>
 *
 * <p>In Bedrock, we examine the raw physical reality of the JVM and OS network stack:
 * WebSockets do NOT use a new transport layer or a new port. They start their life as ordinary
 * HTTP/1.1 requests over port 80 (or 443 for TLS) to effortlessly traverse enterprise firewalls,
 * NAT gateways, and reverse proxies.</p>
 *
 * <h3>1. The Protocol Upgrade Negotiation (HTTP 101 Switching Protocols):</h3>
 * <p>The client initiates a standard HTTP GET request with hop-by-hop upgrade headers:</p>
 * <pre>{@code
 *   GET /chat HTTP/1.1
 *   Host: server.example.com
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 *   Sec-WebSocket-Version: 13
 * }</pre>
 * <p>
 * The headers {@code Upgrade: websocket} and {@code Connection: Upgrade} inform the HTTP server:
 * "Do not terminate this TCP socket after sending a response body. Instead, switch the protocol
 * on this open socket to RFC 6455 full-duplex binary framing."
 * </p>
 *
 * <h3>2. The RFC 6455 Magic GUID & Defense Against Caching / Cross-Protocol Attacks:</h3>
 * <p>Why does RFC 6455 mandate the constant {@code "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}?
 * Why doesn't the server simply respond with {@code Sec-WebSocket-Accept: ok}?</p>
 *
 * <p>This design defends against two catastrophic vulnerability classes:</p>
 * <ul>
 *   <li><b>Caching Proxy Poisoning</b>: If an upgrade request were answered with standard headers
 *       or fixed tokens, a naive transparent caching proxy between client and server might cache
 *       the upgrade response and serve it to subsequent clients requesting standard HTTP, corrupting the cache.</li>
 *   <li><b>Cross-Protocol Attacks (Port Hijacking)</b>: A malicious webpage running JavaScript could
 *       execute {@code fetch()} or XMLHttpRequest targeting an internal TCP service (e.g., SMTP mail server,
 *       Redis, or Memcached) on a LAN port. If the target server simply echoed back the client's input,
 *       the malicious script could establish an arbitrary persistent stream.</li>
 * </ul>
 * <p>
 * By requiring the server to compute:
 * <pre>{@code
 *   Sec-WebSocket-Accept = Base64( SHA-1( Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11" ) )
 * }</pre>
 * The client proves beyond mathematical doubt that:
 * 1. The remote endpoint genuinely understands RFC 6455 (it possesses the RFC GUID).
 * 2. The response is mathematically fresh and unique to this specific TCP connection.
 * 3. The remote endpoint deliberately consents to upgrade to WebSockets.
 * </p>
 *
 * <h3>3. SHA-1 and Base64 Computation Mechanics:</h3>
 * <p>Official RFC 6455 §1.3 test vector step-by-step:</p>
 * <ol>
 *   <li>Input Key: {@code "dGhlIHNhbXBsZSBub25jZQ=="} (16 random bytes, Base64-encoded)</li>
 *   <li>Concatenation: {@code "dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}</li>
 *   <li>SHA-1 Digest in Java produces 20 binary bytes (160 bits):
 *       {@code [0xb3, 0x7a, 0x4f, 0x2c, 0xc0, 0x62, 0x4f, 0x16, 0x90, 0xf6, 0x46, 0x06, 0xcf, 0x38, 0x59, 0x45, 0xb2, 0xbe, 0xc4, 0xea]}</li>
 *   <li>Base64 Encoding produces: {@code "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="}</li>
 * </ol>
 *
 * <h3>4. Detaching the HTTP Parser:</h3>
 * <p>Once the server writes the HTTP 101 response bytes ending with double CRLF ({@code \r\n\r\n}),
 * the HTTP parser is completely detached from the socket channel. From that exact byte onward,
 * the socket enters raw full-duplex RFC 6455 framing mode on its dedicated Virtual Thread.</p>
 */
public final class WebSocketHandshake {

    /** RFC 6455 §1.3 Fixed UUID constant */
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Alias for GUID matching project specification */
    public static final String MAGIC_GUID = GUID;

    /** Supported WebSocket specification version */
    public static final String SUPPORTED_VERSION = "13";

    /** Maximum allowed HTTP handshake header size in bytes (16 KB) */
    public static final int MAX_HEADER_SIZE = 16 * 1024;

    private static final String CRLF = "\r\n";

    private WebSocketHandshake() {
        // Pure utility class
    }

    // =========================================================================
    // Core Handshake Contract (PROJECT.md)
    // =========================================================================

    /**
     * Inspects raw HTTP request headers to determine if the request is a valid RFC 6455 upgrade request.
     *
     * @param httpHeaders Raw HTTP request string.
     * @return True if all RFC 6455 §4.2.1 upgrade invariants are met; false otherwise.
     */
    public static boolean isUpgradeRequest(String httpHeaders) {
        if (httpHeaders == null || httpHeaders.isBlank()) {
            return false;
        }

        String[] lines = httpHeaders.split("\r?\n");
        if (lines.length == 0) {
            return false;
        }

        // Validate Request Line: Must be GET with HTTP/1.1 or higher
        String requestLine = lines[0].trim();
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 3) {
            return false;
        }
        if (!"GET".equalsIgnoreCase(parts[0])) {
            return false;
        }
        String version = parts[2];
        if (!version.startsWith("HTTP/1.1") && !version.startsWith("HTTP/2")) {
            return false;
        }

        // Parse headers into case-insensitive map
        Map<String, String> headers = parseHeadersToMap(lines);

        // 1. Upgrade: websocket
        String upgrade = headers.get("Upgrade");
        if (upgrade == null || !containsToken(upgrade, "websocket")) {
            return false;
        }

        // 2. Connection: Upgrade
        String connection = headers.get("Connection");
        if (connection == null || !containsToken(connection, "Upgrade")) {
            return false;
        }

        // 3. Sec-WebSocket-Version: 13
        String wsVersion = headers.get("Sec-WebSocket-Version");
        if (wsVersion == null || !SUPPORTED_VERSION.equals(wsVersion.trim())) {
            return false;
        }

        // 4. Sec-WebSocket-Key: present and non-blank
        String key = headers.get("Sec-WebSocket-Key");
        return key != null && !key.isBlank();
    }

    /**
     * Extracts the {@code Sec-WebSocket-Key} header value from HTTP headers, trimmed of whitespace.
     *
     * @param httpHeaders Raw HTTP request headers string.
     * @return The client key string, or null if absent or blank.
     */
    public static String extractKey(String httpHeaders) {
        if (httpHeaders == null || httpHeaders.isBlank()) {
            return null;
        }
        String[] lines = httpHeaders.split("\r?\n");
        Map<String, String> headers = parseHeadersToMap(lines);
        String key = headers.get("Sec-WebSocket-Key");
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    /**
     * Computes the cryptographic {@code Sec-WebSocket-Accept} response header value for a given client key.
     *
     * <p>Algorithm: {@code Base64( SHA-1( clientKey.trim() + GUID ) )}</p>
     *
     * @param clientKey The 16-byte nonce provided by the client in Base64 format.
     * @return The 28-character Base64-encoded SHA-1 digest ending in {@code =}.
     * @throws IllegalArgumentException If clientKey is null or blank.
     */
    public static String computeAccept(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("Sec-WebSocket-Key must not be null or blank");
        }
        String combined = clientKey.trim() + GUID;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(combined.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM standard library missing SHA-1 MessageDigest", e);
        }
    }

    /**
     * Generates the compliant {@code HTTP/1.1 101 Switching Protocols} response byte sequence.
     *
     * @param acceptKey The computed {@code Sec-WebSocket-Accept} value.
     * @return Byte array containing the complete HTTP 101 response terminated with {@code \r\n\r\n}.
     */
    public static byte[] createHandshakeResponse(String acceptKey) {
        return createHandshakeResponse(acceptKey, null);
    }

    /**
     * Generates the compliant {@code HTTP/1.1 101 Switching Protocols} response byte sequence,
     * optionally including the {@code Sec-WebSocket-Protocol} negotiation header.
     *
     * @param acceptKey   The computed {@code Sec-WebSocket-Accept} value.
     * @param subprotocol The negotiated subprotocol, or null if none negotiated.
     * @return Byte array containing the complete HTTP 101 response terminated with {@code \r\n\r\n}.
     */
    public static byte[] createHandshakeResponse(String acceptKey, String subprotocol) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 101 Switching Protocols").append(CRLF)
          .append("Upgrade: websocket").append(CRLF)
          .append("Connection: Upgrade").append(CRLF)
          .append("Sec-WebSocket-Accept: ").append(acceptKey).append(CRLF);
        if (subprotocol != null && !subprotocol.isBlank()) {
            sb.append("Sec-WebSocket-Protocol: ").append(subprotocol.trim()).append(CRLF);
        }
        sb.append(CRLF);
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Parses query parameters from a raw URI string (e.g. "/chat?nick=Alice&room=general").
     *
     * @param rawUri Raw request URI.
     * @return Immutable Map of query parameter names to values (URL-decoded).
     */
    public static Map<String, String> parseQueryParams(String rawUri) {
        if (rawUri == null) {
            return Collections.emptyMap();
        }
        int queryIdx = rawUri.indexOf('?');
        if (queryIdx < 0 || queryIdx >= rawUri.length() - 1) {
            return Collections.emptyMap();
        }
        String queryString = rawUri.substring(queryIdx + 1);
        int fragmentIdx = queryString.indexOf('#');
        if (fragmentIdx >= 0) {
            queryString = queryString.substring(0, fragmentIdx);
        }
        if (queryString.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> params = new java.util.LinkedHashMap<>();
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eqIdx = pair.indexOf('=');
            String key;
            String val;
            if (eqIdx >= 0) {
                key = urlDecode(pair.substring(0, eqIdx));
                val = urlDecode(pair.substring(eqIdx + 1));
            } else {
                key = urlDecode(pair);
                val = "";
            }
            if (!key.isEmpty()) {
                params.put(key, val);
            }
        }
        return Collections.unmodifiableMap(params);
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Extracts the clean URI request path from the HTTP request line, stripping any query parameters.
     *
     * @param httpHeaders Raw HTTP request headers string.
     * @return Clean path (e.g., {@code "/chat"}, {@code "/ws/telemetry"}, or {@code "/"}).
     */
    public static String extractPath(String httpHeaders) {
        if (httpHeaders == null || httpHeaders.isBlank()) {
            return "/";
        }
        String[] lines = httpHeaders.split("\r?\n");
        if (lines.length == 0) {
            return "/";
        }
        String requestLine = lines[0].trim();
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 2) {
            return "/";
        }
        String rawPath = parts[1];
        int queryIdx = rawPath.indexOf('?');
        if (queryIdx >= 0) {
            rawPath = rawPath.substring(0, queryIdx);
        }
        return rawPath.isEmpty() ? "/" : rawPath;
    }

    // =========================================================================
    // NIO ByteBuffer & InputStream Ingestion
    // =========================================================================

    /**
     * Scans an NIO {@link ByteBuffer} non-destructively for the header termination delimiter
     * ({@code \r\n\r\n} or {@code \n\n}).
     *
     * @param buffer The buffer to scan between {@code position()} and {@code limit()}.
     * @return The index immediately following the delimiter, or -1 if delimiter was not found.
     */
    public static int findHeaderEnd(ByteBuffer buffer) {
        if (buffer == null) {
            return -1;
        }
        int limit = buffer.limit();
        int pos = buffer.position();

        for (int i = pos; i <= limit - 4; i++) {
            if (buffer.get(i) == '\r' &&
                    buffer.get(i + 1) == '\n' &&
                    buffer.get(i + 2) == '\r' &&
                    buffer.get(i + 3) == '\n') {
                return i + 4;
            }
        }
        // Fallback check for \n\n
        for (int i = pos; i <= limit - 2; i++) {
            if (buffer.get(i) == '\n' && buffer.get(i + 1) == '\n') {
                return i + 2;
            }
        }
        return -1;
    }

    /**
     * Parses an incoming HTTP upgrade request from an NIO {@link ByteBuffer}.
     * If a full header is found, advances the buffer's position past the header and returns
     * the parsed result. If incomplete, leaves buffer position untouched and returns null.
     *
     * @param buffer The input NIO buffer.
     * @return {@link HandshakeParseResult}, or null if the complete header has not yet arrived.
     * @throws WebSocketException If the header size exceeds {@value #MAX_HEADER_SIZE} bytes.
     */
    public static HandshakeParseResult parse(ByteBuffer buffer) {
        int end = findHeaderEnd(buffer);
        if (end == -1) {
            return null; // Incomplete HTTP header
        }

        int start = buffer.position();
        int length = end - start;
        if (length > MAX_HEADER_SIZE) {
            throw new WebSocketException(
                    WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                    "HTTP handshake header exceeds " + MAX_HEADER_SIZE + " bytes"
            );
        }

        byte[] headerBytes = new byte[length];
        buffer.get(headerBytes); // Advances buffer position to 'end'

        String rawHeaders = new String(headerBytes, StandardCharsets.US_ASCII);
        return parse(rawHeaders);
    }

    /**
     * Reads and parses an HTTP handshake request from a blocking {@link InputStream}.
     *
     * @param in Input stream from socket.
     * @return {@link HandshakeParseResult}.
     * @throws IOException        On socket read error.
     * @throws WebSocketException If header size exceeds {@value #MAX_HEADER_SIZE}.
     */
    public static HandshakeParseResult parse(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] window = new byte[4];
        int b;
        while ((b = in.read()) != -1) {
            baos.write(b);
            if (baos.size() > MAX_HEADER_SIZE) {
                throw new WebSocketException(
                        WebSocketCloseStatus.PROTOCOL_ERROR_CODE,
                        "HTTP handshake header exceeds " + MAX_HEADER_SIZE + " bytes"
                );
            }
            window[0] = window[1];
            window[1] = window[2];
            window[2] = window[3];
            window[3] = (byte) b;

            if (window[0] == '\r' && window[1] == '\n' && window[2] == '\r' && window[3] == '\n') {
                break;
            }
        }
        return parse(baos.toString(StandardCharsets.US_ASCII));
    }

    /**
     * Parses and validates raw HTTP handshake headers from a string.
     *
     * @param httpHeaders Raw HTTP request string.
     * @return Complete {@link HandshakeParseResult} with validation status and response bytes.
     */
    public static HandshakeParseResult parse(String httpHeaders) {
        if (httpHeaders == null || httpHeaders.isBlank()) {
            return new HandshakeParseResult(false, 400, null, null, null, Map.of(), null, null,
                    createResponse400("Empty HTTP request"), "Empty request");
        }

        String[] lines = httpHeaders.split("\r?\n");
        if (lines.length == 0) {
            return new HandshakeParseResult(false, 400, null, null, null, Map.of(), null, null,
                    createResponse400("Malformed HTTP request"), "Malformed request");
        }

        // Request Line
        String requestLine = lines[0].trim();
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 3) {
            return new HandshakeParseResult(false, 400, null, null, null, Map.of(), null, null,
                    createResponse400("Invalid HTTP request line: " + requestLine), "Invalid request line");
        }

        String method = parts[0];
        String rawPath = parts[1];
        String version = parts[2];

        if (!"GET".equalsIgnoreCase(method)) {
            return new HandshakeParseResult(false, 405, method, rawPath, version, Map.of(), null, null,
                    createResponse400("Method Not Allowed: WebSocket handshake requires GET"), "Method not allowed");
        }

        if (!version.startsWith("HTTP/1.1") && !version.startsWith("HTTP/2")) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, Map.of(), null, null,
                    createResponse400("Unsupported HTTP version: " + version), "Unsupported HTTP version");
        }

        Map<String, String> headers = parseHeadersToMap(lines);

        // Upgrade header
        String upgrade = headers.get("Upgrade");
        if (upgrade == null || !containsToken(upgrade, "websocket")) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, headers, null, null,
                    createResponse400("Missing or invalid 'Upgrade: websocket' header"), "Missing Upgrade header");
        }

        // Connection header
        String connection = headers.get("Connection");
        if (connection == null || !containsToken(connection, "Upgrade")) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, headers, null, null,
                    createResponse400("Missing or invalid 'Connection: Upgrade' header"), "Missing Connection header");
        }

        // Sec-WebSocket-Version
        String wsVersion = headers.get("Sec-WebSocket-Version");
        if (wsVersion == null || !SUPPORTED_VERSION.equals(wsVersion.trim())) {
            return new HandshakeParseResult(false, 426, method, rawPath, version, headers, null, null,
                    createResponse426(), "Unsupported WebSocket version: " + wsVersion);
        }

        // Sec-WebSocket-Key
        String clientKey = headers.get("Sec-WebSocket-Key");
        if (clientKey == null || clientKey.isBlank()) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, headers, null, null,
                    createResponse400("Missing 'Sec-WebSocket-Key' header"), "Missing key");
        }

        String trimmedKey = clientKey.trim();
        if (trimmedKey.length() != 24) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, headers, clientKey, null,
                    createResponse400("Sec-WebSocket-Key must be a 24-character Base64-encoded string"), "Invalid key length");
        }
        try {
            byte[] decodedKey = Base64.getDecoder().decode(trimmedKey);
            if (decodedKey.length != 16) {
                return new HandshakeParseResult(false, 400, method, rawPath, version, headers, clientKey, null,
                        createResponse400("Sec-WebSocket-Key must decode to exactly 16 bytes"), "Invalid key length");
            }
        } catch (IllegalArgumentException e) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, headers, clientKey, null,
                    createResponse400("Sec-WebSocket-Key is not valid Base64"), "Malformed key");
        }

        String acceptKey = computeAccept(clientKey);
        byte[] responseBytes = createHandshakeResponse(acceptKey);

        String cleanPath = extractPath(httpHeaders);
        Map<String, String> queryParams = parseQueryParams(rawPath);

        return new HandshakeParseResult(
                true, 101, method, cleanPath, version,
                Collections.unmodifiableMap(headers),
                clientKey.trim(), acceptKey, responseBytes, null,
                rawPath, queryParams
        );
    }

    // =========================================================================
    // Error Responses
    // =========================================================================

    public static byte[] createResponse400(String reason) {
        String body = reason != null ? reason : "Bad Request";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 400 Bad Request" + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: " + bodyBytes.length + CRLF +
                "Connection: close" + CRLF +
                CRLF +
                body;
        return resp.getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] createResponse426() {
        String body = "Upgrade Required: Use WebSocket Version 13";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 426 Upgrade Required" + CRLF +
                "Sec-WebSocket-Version: 13" + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: " + bodyBytes.length + CRLF +
                "Connection: close" + CRLF +
                CRLF +
                body;
        return resp.getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] createResponse404(String path) {
        String body = "WebSocket Endpoint Not Found: " + path;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 404 Not Found" + CRLF +
                "Content-Type: text/plain; charset=utf-8" + CRLF +
                "Content-Length: " + bodyBytes.length + CRLF +
                "Connection: close" + CRLF +
                CRLF +
                body;
        return resp.getBytes(StandardCharsets.US_ASCII);
    }

    // =========================================================================
    // Helpers & Parse Result Record
    // =========================================================================

    private static Map<String, String> parseHeadersToMap(String[] lines) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
            }
        }
        return headers;
    }

    private static boolean containsToken(String headerValue, String expectedToken) {
        if (headerValue == null) return false;
        String[] tokens = headerValue.split(",");
        for (String token : tokens) {
            if (token.trim().equalsIgnoreCase(expectedToken)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Immutable result of an HTTP handshake parsing and validation attempt.
     */
    public record HandshakeParseResult(
            boolean valid,
            int statusCode,
            String method,
            String path,
            String httpVersion,
            Map<String, String> headers,
            String key,
            String acceptKey,
            byte[] responseBytes,
            String errorMessage,
            String rawUri,
            Map<String, String> queryParams
    ) {
        /**
         * Backward-compatible constructor without rawUri and queryParams.
         */
        public HandshakeParseResult(boolean valid, int statusCode, String method, String path,
                                    String httpVersion, Map<String, String> headers,
                                    String key, String acceptKey, byte[] responseBytes,
                                    String errorMessage) {
            this(valid, statusCode, method, path, httpVersion, headers, key, acceptKey, responseBytes, errorMessage,
                    path, Collections.emptyMap());
        }

        /**
         * Returns true if the handshake was successfully negotiated with HTTP 101 Switching Protocols.
         */
        public boolean isSuccess() {
            return valid && statusCode == 101;
        }

        /**
         * Alias for {@link #valid()} conforming to JavaBean boolean getter convention.
         *
         * @return True if handshake headers are structurally valid.
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the client requested subprotocols header (Sec-WebSocket-Protocol), or null if absent.
         */
        public String requestedSubprotocols() {
            return headers != null ? headers.get("Sec-WebSocket-Protocol") : null;
        }
    }
}
