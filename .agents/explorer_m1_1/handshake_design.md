# RFC 6455 Handshake & Protocol Fundamentals Design Specification

**Module**: `bedrock-core`  
**Package**: `com.bedrock.core.ws.protocol`  
**Author**: `explorer_m1_1` (Milestone 1 Protocol Engine Explorer)  
**Standard**: [RFC 6455 — The WebSocket Protocol](https://www.rfc-editor.org/rfc/rfc6455.txt)  
**Target Platform**: JDK 21 LTS (Zero External Dependencies)

---

## 1. Executive Summary & Design Scope

This document specifies the technical architecture, byte-level logic, API method signatures, validation invariants, and pedagogical documentation for four foundational components of the Bedrock WebSocket Protocol Engine:

1. **`WebSocketHandshake`**: HTTP/1.1 101 Switching Protocols upgrade negotiation, request line & header extraction, RFC 6455 client key validation, SHA-1 + Base64 `Sec-WebSocket-Accept` calculation, and response byte sequence generation.
2. **`WebSocketOpcode`**: RFC 6455 4-bit opcode enum representing frame payload types (Continuation, Text, Binary, Close, Ping, Pong), with classification helpers (`isControl()`, `isData()`) and byte parsing.
3. **`WebSocketCloseStatus`**: RFC 6455 status code constants (1000–1011), wire-forbidden code validation (1005, 1006, 1015), range enforcement, and descriptive reason mapping.
4. **`WebSocketException`**: Core protocol exception carrying an RFC 6455 status code and descriptive failure message, enabling clean error-to-close-frame propagation.

---

## 2. Component Specifications

### 2.1 `WebSocketHandshake.java`

#### 2.1.1 Architectural Role
The handshake is the gateway between the HTTP/1.1 world and the RFC 6455 full-duplex binary frame world. In Bedrock's NIO architecture, client connections arrive at a `ServerSocketChannel` and are handed to a dedicated Virtual Thread. Before any frames can be processed, the server must parse the initial HTTP request line and headers, validate all upgrade invariants, compute the cryptographic proof (`Sec-WebSocket-Accept`), and reply with an `HTTP/1.1 101 Switching Protocols` response.

#### 2.1.2 Constants
- `RFC_GUID`: `"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"` (Fixed UUID defined in RFC 6455 §1.3 & §4.2.2).
- `EXPECTED_VERSION`: `"13"` (RFC 6455 §4.2.1 item 8).
- `CRLF`: `"\r\n"`.
- `HEADER_DELIMITER`: `"\r\n\r\n"`.
- `MAX_HEADER_SIZE`: `16384` (16 KB threshold protecting against unbounded buffer memory exhaustion attacks).

#### 2.1.3 Request Validation Rules (RFC 6455 §4.2.1)
An incoming request is a valid WebSocket upgrade request if and only if all of the following conditions hold:
1. **HTTP Method**: Must be `"GET"` (case-sensitive). Any other method returns `405 Method Not Allowed` or `400 Bad Request`.
2. **HTTP Version**: Must be `"HTTP/1.1"` or higher (e.g. `HTTP/1.1`). Requests below 1.1 are rejected with `400 Bad Request`.
3. **`Upgrade` Header**: Must contain `"websocket"` (case-insensitive token comparison).
4. **`Connection` Header**: Must contain the token `"Upgrade"` (case-insensitive token comparison; handles multi-value headers like `"keep-alive, Upgrade"`).
5. **`Sec-WebSocket-Version` Header**: Must be exactly `"13"`. If missing or different, server returns `426 Upgrade Required` with header `Sec-WebSocket-Version: 13`.
6. **`Sec-WebSocket-Key` Header**: Must be present, non-empty, and decode via Base64 to **exactly 16 bytes**.

#### 2.1.4 `Sec-WebSocket-Accept` Algorithm (RFC 6455 §1.3 & §4.2.2)
$$\text{Accept} = \text{Base64}\Big(\text{SHA-1}\big(\text{key.trim()} \,\|\, \text{"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}\big)\Big)$$
1. Trim leading/trailing whitespace from `clientKey`.
2. Concatenate `clientKey` with `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`.
3. Obtain SHA-1 message digest using `MessageDigest.getInstance("SHA-1")`.
4. Feed bytes encoded in `StandardCharsets.US_ASCII`.
5. Base64-encode the resulting 20-byte digest using `Base64.getEncoder().encodeToString(hash)`.
6. Output is a 28-character ASCII string ending in `=`.

**Verification Vectors**:
- Vector 1 (RFC 6455 §1.3):
  - Input Key: `"dGhlIHNhbXBsZSBub25jZQ=="`
  - Concatenated: `"dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11"`
  - SHA-1 Digest (Hex): `b37a4f2cc0624f1690f64606cf385945b2bec4ea`
  - Output Accept: `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`
- Vector 2 (Supplementary):
  - Input Key: `"x3JJHMbDL1EzLkh9GBhXDw=="`
  - Output Accept: `"AVtgdGq0X/JC9rGpfhI38EX0qE0="`

#### 2.1.5 Response Generation
- **101 Switching Protocols**:
  ```http
  HTTP/1.1 101 Switching Protocols\r\n
  Upgrade: websocket\r\n
  Connection: Upgrade\r\n
  Sec-WebSocket-Accept: <acceptKey>\r\n
  \r\n
  ```
- **400 Bad Request**:
  ```http
  HTTP/1.1 400 Bad Request\r\n
  Content-Type: text/plain; charset=utf-8\r\n
  Content-Length: <length>\r\n
  Connection: close\r\n
  \r\n
  <reasonMessage>
  ```
- **426 Upgrade Required** (RFC 6455 §4.4):
  ```http
  HTTP/1.1 426 Upgrade Required\r\n
  Sec-WebSocket-Version: 13\r\n
  Content-Type: text/plain; charset=utf-8\r\n
  Content-Length: 39\r\n
  Connection: close\r\n
  \r\n
  Upgrade Required: Use WebSocket Version 13
  ```

#### 2.1.6 Buffer and Stream Ingestion Mechanics
- **`ByteBuffer` Ingestion**:
  - Scans buffer between `position()` and `limit()` for sequence `0x0D, 0x0A, 0x0D, 0x0A` (`\r\n\r\n`) or `0x0A, 0x0A` (`\n\n`).
  - If delimiter not found, returns `null` or signals incomplete header.
  - If delimiter found at index $k$:
    - Copies bytes from `position()` to $k+4$ into a new byte array.
    - Decodes to US-ASCII String.
    - **Crucial NIO Step**: Sets `buffer.position(k + 4)`. Any remaining unread bytes in the buffer immediately belong to the subsequent RFC 6455 frame stream!
- **`InputStream` Ingestion**:
  - Reads bytes into a `ByteArrayOutputStream` until delimiter `\r\n\r\n` is detected or `MAX_HEADER_SIZE` is exceeded.
  - Throws `WebSocketException(1002, "HTTP header exceeded maximum allowed size of 16384 bytes")` on overflow.
- **`String` Ingestion**:
  - Directly splits text into lines using `\r?\n`.
  - Line 0: Request line (`GET /path HTTP/1.1`).
  - Lines 1..N: Headers parsed into case-insensitive `Map<String, String>` (`TreeMap<>(String.CASE_INSENSITIVE_ORDER)`).

#### 2.1.7 Method Signatures for `WebSocketHandshake`
```java
package com.bedrock.core.ws.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 WebSocket Handshake Negotiator.
 * ...
 */
public final class WebSocketHandshake {

    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final String SUPPORTED_VERSION = "13";
    public static final int MAX_HEADER_SIZE = 16 * 1024; // 16 KB

    private WebSocketHandshake() {}

    // --- Core Contract Required by PROJECT.md ---

    public static boolean isUpgradeRequest(String httpHeaders);

    public static String extractKey(String httpHeaders);

    public static String computeAccept(String clientKey);

    public static byte[] createHandshakeResponse(String acceptKey);

    public static String extractPath(String httpHeaders);

    // --- Stream and Buffer Ingestion API ---

    public static HandshakeParseResult parse(String httpHeaders);

    public static HandshakeParseResult parse(ByteBuffer buffer);

    public static HandshakeParseResult parse(InputStream in) throws IOException;

    public static int findHeaderEnd(ByteBuffer buffer);

    // --- Error Response Generators ---

    public static byte[] createResponse400(String reason);

    public static byte[] createResponse426();

    public static byte[] createResponse404(String path);

    // --- Parsed Result Record ---

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
        String errorMessage
    ) {
        public boolean isSuccess() {
            return valid && statusCode == 101;
        }
    }
}
```

---

### 2.2 `WebSocketOpcode.java`

#### 2.2.1 Architectural Role
RFC 6455 frames use a 4-bit field in Byte 0 (bits 3–0) to denote the type and semantics of the payload. Opcodes are strictly partitioned into **Data Opcodes** (`0x0`–`0x7`) and **Control Opcodes** (`0x8`–`0xF`). Control opcodes can be interleaved within fragmented data messages and have strict invariants (e.g. payload length $\le 125$ bytes, must not be fragmented with FIN=0).

#### 2.2.2 Opcode Enum Definitions
| Enum Constant | Hex Code | Binary | Type | RFC 6455 Meaning |
|---|---|---|---|---|
| `CONTINUATION` | `0x0` | `0000b` | Data | Continuation of a fragmented message |
| `TEXT` | `0x1` | `0001b` | Data | UTF-8 encoded text message |
| `BINARY` | `0x2` | `0010b` | Data | Arbitrary uninterpreted binary payload |
| `CLOSE` | `0x8` | `1000b` | Control | Connection close handshake control frame |
| `PING` | `0x9` | `1001b` | Control | Heartbeat ping control frame |
| `PONG` | `0xA` | `1010b` | Control | Heartbeat pong control frame |

#### 2.2.3 RFC 6455 Invariants and Validation Rules
1. **Control Bit Invariant**: An opcode is a control frame if and only if bit 3 is set (`(code & 0x8) != 0`).
2. **Reserved Opcodes**:
   - `0x3`–`0x7`: Reserved non-control opcodes.
   - `0xB`–`0xF`: Reserved control opcodes.
   - If an unknown or reserved opcode is encountered on the wire, the endpoint **MUST fail the connection** with status code `1002 Protocol Error` (RFC 6455 §5.2).

#### 2.2.4 Method Signatures for `WebSocketOpcode`
```java
package com.bedrock.core.ws.protocol;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 WebSocket 4-Bit Frame Opcode.
 * ...
 */
public enum WebSocketOpcode {

    CONTINUATION(0x0),
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);

    private final int code;

    WebSocketOpcode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public byte getCodeAsByte() {
        return (byte) code;
    }

    public boolean isControl() {
        return (code & 0x8) != 0;
    }

    public boolean isData() {
        return !isControl();
    }

    public static WebSocketOpcode fromCode(int code) {
        for (WebSocketOpcode opcode : values()) {
            if (opcode.code == code) {
                return opcode;
            }
        }
        throw new WebSocketException(
            WebSocketCloseStatus.PROTOCOL_ERROR,
            "Unknown or reserved RFC 6455 opcode: 0x" + Integer.toHexString(code)
        );
    }

    public static boolean isValid(int code) {
        for (WebSocketOpcode opcode : values()) {
            if (opcode.code == code) return true;
        }
        return false;
    }
}
```

---

### 2.3 `WebSocketCloseStatus.java`

#### 2.3.1 Architectural Role
RFC 6455 §7.4 specifies an IANA registry of 16-bit status codes indicating why a connection was closed. Status codes can appear in the 2-byte prefix of a Close control frame payload (`Opcode 0x8`). Crucially, certain status codes are strictly designated for internal API usage and **MUST NEVER be transmitted on the physical wire**.

#### 2.3.2 Status Code Registry
| Code | Constant Name | Can Be Sent on Wire? | Description |
|---|---|:---:|---|
| **1000** | `NORMAL_CLOSURE` | **YES** | Purpose for which connection was established is fulfilled. |
| **1001** | `GOING_AWAY` | **YES** | Endpoint is going away (server shutting down or client navigating away). |
| **1002** | `PROTOCOL_ERROR` | **YES** | Endpoint terminated connection due to a protocol error. |
| **1003** | `UNSUPPORTED_DATA` | **YES** | Endpoint received data type it cannot accept (e.g. binary on text endpoint). |
| **1005** | `NO_STATUS_RECEIVED` | **NO (Wire-Forbidden)** | Designated for internal API use when no close code was present in Close frame. |
| **1006** | `ABNORMAL_CLOSURE` | **NO (Wire-Forbidden)** | Designated for internal API use when connection dropped abnormally without Close frame. |
| **1007** | `INVALID_FRAME_PAYLOAD_DATA`| **YES** | Data was inconsistent with message type (e.g. invalid UTF-8 in text frame). |
| **1008** | `POLICY_VIOLATION` | **YES** | Endpoint received message violating application policy (auth, rate limits). |
| **1009** | `MESSAGE_TOO_BIG` | **YES** | Message payload exceeds server processing threshold. |
| **1010** | `MANDATORY_EXTENSION` | **YES** | Client required extension negotiation that server failed to fulfill. |
| **1011** | `INTERNAL_SERVER_ERROR` | **YES** | Server encountered an unexpected error fulfilling the request. |
| **1015** | `TLS_HANDSHAKE_FAILURE` | **NO (Wire-Forbidden)** | Designated for internal API use when TLS negotiation fails. |

#### 2.3.3 RFC 6455 §7.4.2 Status Code Ranges
- `0–999`: Strictly forbidden / unused.
- `1000–2999`: Reserved for RFC 6455 and future IETF standards. Unassigned codes in this range (e.g. 1004, 1012–2999) cannot be used on the wire without specification.
- `3000–3999`: Reserved for framework and library registration via IANA.
- `4000–4999`: Reserved for private / application-defined custom status codes.

#### 2.3.4 Wire-Forbidden Codes & Validation Logic
1. **Outgoing Validation (`validateSendCode(int code)`)**:
   - If `code == 1005 || code == 1006 || code == 1015`, throw `IllegalArgumentException("Status code " + code + " is forbidden on the wire (RFC 6455 §7.4.1)")`.
   - If `code < 1000 || code > 4999`, throw `IllegalArgumentException("Status code out of range: " + code)`.
   - If `code` is in `1012..2999`, throw `IllegalArgumentException("Status code " + code + " is unassigned/reserved in RFC 6455")`.
2. **Incoming Validation (`validateReceivedCode(int code)`)**:
   - If a peer sends a Close frame containing a status code of `1005`, `1006`, or `1015`, the receiving endpoint **MUST terminate the connection with status code 1002 (Protocol Error)**.
   - If `code < 1000 || code > 4999 || (code >= 1012 && code <= 2999)`, receiving endpoint terminates with `1002`.

#### 2.3.5 Method Signatures for `WebSocketCloseStatus`
```java
package com.bedrock.core.ws.protocol;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 WebSocket Close Status Codes & Wire Validation.
 * ...
 */
public enum WebSocketCloseStatus {

    NORMAL_CLOSURE(1000, "Normal Closure"),
    GOING_AWAY(1001, "Going Away"),
    PROTOCOL_ERROR(1002, "Protocol Error"),
    UNSUPPORTED_DATA(1003, "Unsupported Data"),
    NO_STATUS_RECEIVED(1005, "No Status Received"),
    ABNORMAL_CLOSURE(1006, "Abnormal Closure"),
    INVALID_FRAME_PAYLOAD_DATA(1007, "Invalid Frame Payload Data"),
    POLICY_VIOLATION(1008, "Policy Violation"),
    MESSAGE_TOO_BIG(1009, "Message Too Big"),
    MANDATORY_EXTENSION(1010, "Mandatory Extension"),
    INTERNAL_SERVER_ERROR(1011, "Internal Server Error"),
    TLS_HANDSHAKE_FAILURE(1015, "TLS Handshake Failure");

    // Standard Integer Constants for Switch Statements
    public static final int NORMAL = 1000;
    public static final int GOING_AWAY_CODE = 1001;
    public static final int PROTOCOL_ERROR_CODE = 1002;
    public static final int UNSUPPORTED_DATA_CODE = 1003;
    public static final int NO_STATUS_CODE = 1005;
    public static final int ABNORMAL_CLOSURE_CODE = 1006;
    public static final int INVALID_DATA_CODE = 1007;
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

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isWireForbidden() {
        return isWireForbidden(code);
    }

    public static boolean isWireForbidden(int code) {
        return code == 1005 || code == 1006 || code == 1015;
    }

    public static boolean isValidSendCode(int code) {
        if (isWireForbidden(code)) return false;
        if (code >= 1000 && code <= 1011 && code != 1004) return true;
        return code >= 3000 && code <= 4999;
    }

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

    public static void validateReceivedCode(int code) {
        if (isWireForbidden(code) || code < 1000 || code > 4999 || (code >= 1012 && code <= 2999) || code == 1004) {
            throw new WebSocketException(
                PROTOCOL_ERROR_CODE,
                "Received invalid or wire-forbidden close status code: " + code
            );
        }
    }

    public static WebSocketCloseStatus fromCode(int code) {
        for (WebSocketCloseStatus status : values()) {
            if (status.code == code) return status;
        }
        return null;
    }

    public static String getReasonPhrase(int code) {
        WebSocketCloseStatus status = fromCode(code);
        if (status != null) return status.getDescription();
        if (code >= 3000 && code <= 3999) return "Framework Registered Status (" + code + ")";
        if (code >= 4000 && code <= 4999) return "Application Custom Status (" + code + ")";
        return "Unknown Status (" + code + ")";
    }
}
```

---

### 2.4 `WebSocketException.java`

#### 2.4.1 Architectural Role
`WebSocketException` is an unchecked runtime exception (`RuntimeException`) specifically designed for protocol violations, parsing errors, framing anomalies, and unhandled endpoint exceptions. It encapsulates an RFC 6455 status code (`statusCode`), guaranteeing that whenever an exception is caught in the connection loop, the server can immediately construct a compliant RFC 6455 Close frame with the precise error code before terminating the TCP socket.

#### 2.4.2 Invariant & Educational Design
- Always provides `getStatusCode()` returning an integer status code (defaulting to `1002 Protocol Error` or `1011 Server Error`).
- Implements Bedrock's educational message formatting, clearly printing the protocol reason and remediation instructions.

#### 2.4.3 Method Signatures for `WebSocketException`
```java
package com.bedrock.core.ws.protocol;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Protocol Exception with Status Code.
 * ...
 */
public class WebSocketException extends RuntimeException {

    private final int statusCode;

    public WebSocketException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public WebSocketException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public WebSocketException(String message) {
        this(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, message);
    }

    public WebSocketException(String message, Throwable cause) {
        this(WebSocketCloseStatus.SERVER_ERROR_CODE, message, cause);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReason() {
        return getMessage();
    }

    @Override
    public String toString() {
        return "WebSocketException[code=" + statusCode + " (" + 
               WebSocketCloseStatus.getReasonPhrase(statusCode) + "): " + getMessage() + "]";
    }
}
```

---

## 3. Byte-Level Algorithms & Implementation Mechanics

### 3.1 Handshake Ingestion State Machine for NIO `ByteBuffer`
When receiving an incoming connection on a `SocketChannel`, data arrives in a `ByteBuffer`:

```
+-----------------------------------------------------------------------+
|  GET /chat HTTP/1.1\r\nHost: localhost:8080\r\n...\r\n\r\n | Frame 1  |
+-----------------------------------------------------------------------+
 ^                                                          ^           ^
 position = 0                                            end = k+4     limit
```

```java
public static HandshakeParseResult parse(ByteBuffer buffer) {
    int end = findHeaderEnd(buffer);
    if (end == -1) {
        return null; // Incomplete HTTP header, need more read() from SocketChannel
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
    buffer.get(headerBytes); // Advances buffer.position to 'end'
    
    String rawHeaders = new String(headerBytes, StandardCharsets.US_ASCII);
    return parse(rawHeaders);
}
```

### 3.2 String Header Parser & Case-Insensitive Extraction
```java
public static HandshakeParseResult parse(String httpHeaders) {
    if (httpHeaders == null || httpHeaders.isBlank()) {
        return new HandshakeParseResult(false, 400, null, null, null, Map.of(), null, null, 
            createResponse400("Empty HTTP request"), "Empty request");
    }

    String[] lines = httpHeaders.split("\r?\n");
    if (lines.length == 0) {
        return new HandshakeParseResult(false, 400, null, null, null, Map.of(), null, null,
            createResponse400("Malformed HTTP request line"), "Malformed request");
    }

    // Parse Request Line: GET /path HTTP/1.1
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

    // Parse Headers into Case-Insensitive Map
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 1; i < lines.length; i++) {
        String line = lines[i].trim();
        if (line.isEmpty()) continue;
        int colon = line.indexOf(':');
        if (colon > 0) {
            String headerName = line.substring(0, colon).trim();
            String headerVal = line.substring(colon + 1).trim();
            headers.put(headerName, headerVal);
        }
    }

    // Validate Upgrade: websocket
    String upgrade = headers.get("Upgrade");
    if (upgrade == null || !containsToken(upgrade, "websocket")) {
        return new HandshakeParseResult(false, 400, method, rawPath, version, headers, null, null,
            createResponse400("Missing or invalid 'Upgrade: websocket' header"), "Missing Upgrade header");
    }

    // Validate Connection: Upgrade
    String connection = headers.get("Connection");
    if (connection == null || !containsToken(connection, "Upgrade")) {
        return new HandshakeParseResult(false, 400, method, rawPath, version, headers, null, null,
            createResponse400("Missing or invalid 'Connection: Upgrade' header"), "Missing Connection header");
    }

    // Validate Sec-WebSocket-Version: 13
    String wsVersion = headers.get("Sec-WebSocket-Version");
    if (wsVersion == null || !SUPPORTED_VERSION.equals(wsVersion.trim())) {
        return new HandshakeParseResult(false, 426, method, rawPath, version, headers, null, null,
            createResponse426(), "Unsupported WebSocket version: " + wsVersion);
    }

    // Validate Sec-WebSocket-Key
    String clientKey = headers.get("Sec-WebSocket-Key");
    if (clientKey == null || clientKey.isBlank()) {
        return new HandshakeParseResult(false, 400, method, rawPath, version, headers, null, null,
            createResponse400("Missing 'Sec-WebSocket-Key' header"), "Missing key");
    }

    try {
        byte[] decodedKey = Base64.getDecoder().decode(clientKey.trim());
        if (decodedKey.length != 16) {
            return new HandshakeParseResult(false, 400, method, rawPath, version, headers, clientKey, null,
                createResponse400("Sec-WebSocket-Key must decode to exactly 16 bytes"), "Invalid key length");
        }
    } catch (IllegalArgumentException e) {
        return new HandshakeParseResult(false, 400, method, rawPath, version, headers, clientKey, null,
            createResponse400("Sec-WebSocket-Key is not valid Base64"), "Malformed key");
    }

    // Compute Sec-WebSocket-Accept
    String acceptKey = computeAccept(clientKey);
    byte[] responseBytes = createHandshakeResponse(acceptKey);

    // Normalize Path (strip query parameters if present for routing)
    String cleanPath = rawPath;
    int queryIdx = cleanPath.indexOf('?');
    if (queryIdx >= 0) {
        cleanPath = cleanPath.substring(0, queryIdx);
    }

    return new HandshakeParseResult(
        true, 101, method, cleanPath, version, 
        Collections.unmodifiableMap(headers), 
        clientKey, acceptKey, responseBytes, null
    );
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
```

---

## 4. 🎓 BEDROCK TUTORIAL Javadoc Documentation Plan

The Bedrock philosophy rejects "magic" annotations in favor of radical transparency and pedagogical depth. Each class will feature rich Javadoc explaining the protocol physics.

### 4.1 `WebSocketHandshake` Tutorial Javadoc
```java
/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 WebSocket Handshake Negotiation
 * 
 * <h3>Why Does WebSockets Need an HTTP Handshake?</h3>
 * <p>
 * WebSockets didn't invent a new TCP port. They intentionally operate over ports 80 (HTTP)
 * and 443 (HTTPS). This allows WebSocket connections to seamlessly traverse enterprise
 * firewalls, NAT gateways, and reverse proxies without requiring custom firewall configurations.
 * </p>
 * 
 * <h3>The Upgrade Dance (HTTP 101 Switching Protocols):</h3>
 * <ol>
 *   <li>The client initiates a standard HTTP/1.1 GET request, but includes two crucial headers:
 *       <code>Upgrade: websocket</code> and <code>Connection: Upgrade</code>.</li>
 *   <li>The client provides a random 16-byte nonce encoded in Base64: <code>Sec-WebSocket-Key</code>.</li>
 *   <li>The server verifies the version (must be 13) and key, hashes the key with the RFC GUID
 *       <code>258EAFA5-E914-47DA-95CA-C5AB0DC85B11</code> via SHA-1, Base64-encodes the digest,
 *       and replies with <code>HTTP/1.1 101 Switching Protocols</code>.</li>
 *   <li>Immediately following the final <code>\r\n\r\n</code> of the 101 response, the HTTP protocol
 *       is completely detached from the socket. From that exact byte onward, the socket is a raw
 *       bidirectional binary stream governed exclusively by RFC 6455 framing!</li>
 * </ol>
 * 
 * <h3>Why the "Magic GUID" (258EAFA5-E914-47DA-95CA-C5AB0DC85B11)?</h3>
 * <p>
 * Why didn't the protocol designers simply return <code>Sec-WebSocket-Accept: ok</code>?
 * To prevent <b>Cross-Protocol Poisoning</b>. If a malicious website induced a browser to send
 * an HTTP request to an internal raw TCP service (such as SMTP, Redis, or an internal database),
 * an unsuspecting server echoing headers might inadvertently maintain an open bidirectional pipe.
 * The SHA-1 + GUID handshake proves mathematically that:
 * </p>
 * <ul>
 *   <li>The server explicitly understands RFC 6455 WebSockets.</li>
 *   <li>The reply was computed uniquely for this exact connection request (preventing proxy caching).</li>
 * </ul>
 */
```

### 4.2 `WebSocketOpcode` Tutorial Javadoc
```java
/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 4-Bit Frame Opcodes
 * 
 * <h3>Anatomy of Byte 0 in a WebSocket Frame:</h3>
 * <pre>
 *   Bit:   7     6     5     4     3   2   1   0
 *        +-----+-----+-----+-----+---------------+
 *        | FIN | RSV1| RSV2| RSV3|    Opcode     |
 *        +-----+-----+-----+-----+---------------+
 * </pre>
 * <p>
 * The Opcode occupies the lower 4 bits (0x0F) of Byte 0.
 * RFC 6455 divides opcodes into two distinct classes:
 * </p>
 * <ul>
 *   <li><b>Data Opcodes (0x0 - 0x7)</b>: Carry application payload data.
 *       <ul>
 *         <li><code>0x0 (CONTINUATION)</code>: Carries fragments of a multi-frame message.</li>
 *         <li><code>0x1 (TEXT)</code>: Carries UTF-8 formatted text.</li>
 *         <li><code>0x2 (BINARY)</code>: Carries arbitrary binary bytes.</li>
 *       </ul>
 *   </li>
 *   <li><b>Control Opcodes (0x8 - 0xF)</b>: Manage connection state.
 *       Bit 3 is always set (<code>isControl() == true</code>).
 *       <ul>
 *         <li><code>0x8 (CLOSE)</code>: Initiates or acknowledges two-way close handshake.</li>
 *         <li><code>0x9 (PING)</code>: Heartbeat check; requires immediate Pong echo.</li>
 *         <li><code>0xA (PONG)</code>: Heartbeat reply carrying identical payload.</li>
 *       </ul>
 *   </li>
 * </ul>
 */
```

### 4.3 `WebSocketCloseStatus` Tutorial Javadoc
```java
/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Close Status Codes & Wire Invariants
 * 
 * <h3>The Two-Way Close Handshake:</h3>
 * <p>
 * Unlike an abrupt TCP FIN drop, WebSockets perform a clean application-level close handshake.
 * Either endpoint sends an <code>Opcode 0x8</code> frame containing a 2-byte big-endian unsigned
 * integer status code, optionally followed by a UTF-8 reason string.
 * </p>
 * 
 * <h3>Why Are Codes 1005, 1006, and 1015 Forbidden on the Wire?</h3>
 * <p>
 * RFC 6455 §7.4.1 defines certain status codes exclusively for internal JVM and application APIs:
 * </p>
 * <ul>
 *   <li><b>1005 (No Status Received)</b>: Used internally when a peer sent a Close frame with 0 bytes of payload.</li>
 *   <li><b>1006 (Abnormal Closure)</b>: Used internally when the TCP socket dropped, reset, or timed out without any Close frame.</li>
 *   <li><b>1015 (TLS Failure)</b>: Used internally when the TLS handshake failed.</li>
 * </ul>
 * <p>
 * If an endpoint receives 1005, 1006, or 1015 inside an incoming Close frame payload, RFC 6455 §7.4.1
 * dictates that this is a protocol violation, and the connection MUST be failed with <b>1002 (Protocol Error)</b>!
 * </p>
 */
```

---

## 5. Verification Plan & Test Vectors

### 5.1 Unit Test Specifications for `WebSocketHandshakeTest.java`
The implementation will be validated by the following comprehensive test suite:

1. **RFC 6455 §1.3 Official Test Vector**:
   - Client Key: `"dGhlIHNhbXBsZSBub25jZQ=="`
   - Expected `computeAccept`: `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`
2. **Alternative Test Vector**:
   - Client Key: `"x3JJHMbDL1EzLkh9GBhXDw=="`
   - Expected `computeAccept`: `"AVtgdGq0X/JC9rGpfhI38EX0qE0="`
3. **Valid Handshake Request Parsing**:
   - Multi-line HTTP GET request with standard headers.
   - Assert `isUpgradeRequest()` returns `true`.
   - Assert `extractKey()` returns exact key string.
   - Assert `extractPath()` correctly extracts `"/chat"` from `"GET /chat HTTP/1.1"`.
   - Assert `createHandshakeResponse()` byte array matches RFC 101 format.
4. **Header Extraction with Query String**:
   - `"GET /ws/telemetry?token=abc123xyz HTTP/1.1"`
   - Assert extracted clean path is `"/ws/telemetry"`.
5. **Multi-Value `Connection` Header**:
   - `"Connection: keep-alive, Upgrade"`
   - Assert handshake accepts connection.
6. **Case-Insensitive Header Names**:
   - `"upgrade: WEBSOCKET"`
   - `"connection: UPGRADE"`
   - Assert handshake accepts connection.
7. **Version Rejection (HTTP 426)**:
   - `"Sec-WebSocket-Version: 8"`
   - Assert `parse()` returns `statusCode == 426`.
   - Assert response bytes contain `Sec-WebSocket-Version: 13`.
8. **Invalid Nonce Validation (HTTP 400)**:
   - Key that decodes to 15 bytes (invalid).
   - Key that decodes to 17 bytes (invalid).
   - Key that is not valid Base64 (`"???notbase64???"`).
   - Assert `parse()` returns `statusCode == 400`.
9. **Method Rejection (HTTP 405/400)**:
   - `"POST /chat HTTP/1.1"`
   - Assert rejected.
10. **NIO `ByteBuffer` Delimiter Scan**:
    - ByteBuffer containing request + partial frame data.
    - Assert `findHeaderEnd` locates exact end index.
    - Assert `parse(ByteBuffer)` advances buffer position to frame boundary.

### 5.2 Unit Test Specifications for `WebSocketOpcodeTest.java`
1. `fromCode(0x1)` returns `TEXT`.
2. `fromCode(0x8)` returns `CLOSE`.
3. `TEXT.isControl()` returns `false`.
4. `PING.isControl()` returns `true`.
5. `fromCode(0x3)` throws `WebSocketException` with code `1002`.
6. `fromCode(0xF)` throws `WebSocketException` with code `1002`.

### 5.3 Unit Test Specifications for `WebSocketCloseStatusTest.java`
1. `validateSendCode(1000)` succeeds.
2. `validateSendCode(1005)` throws `IllegalArgumentException`.
3. `validateSendCode(1006)` throws `IllegalArgumentException`.
4. `validateSendCode(1015)` throws `IllegalArgumentException`.
5. `validateReceivedCode(1005)` throws `WebSocketException` with status code `1002`.
6. `validateReceivedCode(1002)` succeeds.
7. Custom status code `4001` is recognized as application custom code and allowed for send/receive.

---

## 6. Downstream Interface Contracts

The components designed in this document establish the following verified contracts for subsequent Milestone 1 and Milestone 2 tasks:

| Consumer | Provided Method / Interface | Purpose |
|---|---|---|
| `WebSocketFrameParser` | `WebSocketOpcode.fromCode(int)` | Maps parsed 4-bit opcode from Byte 0 into type-safe enum; fails immediately on unknown opcodes with status 1002. |
| `WebSocketFrameParser` | `WebSocketException(int, String)` | Thrown on client masking violations (MASK=0), RSV bits != 0, non-minimal lengths, or invalid UTF-8. |
| `WebSocketFrameWriter` | `WebSocketCloseStatus.validateSendCode(int)` | Validates close status code before serializing server-initiated Close frame. |
| `WebSocketClientHandler` | `WebSocketHandshake.parse(ByteBuffer)` | Ingests initial raw TCP bytes from `SocketChannel`, validates upgrade, writes HTTP 101 response, and hands off remaining buffer bytes to `WebSocketFrameParser`. |
| `BedrockWebSocketSession`| `WebSocketCloseStatus.*` | Provides standard status codes for `session.close(int, String)`. |
