# RFC 6455 Protocol Engine: Frame Representation, Parsing & Serialization Design

**Author**: `explorer_m1_2` (Protocol Frame Explorer)  
**Target Module**: `bedrock-core` (`com.bedrock.core.ws.protocol`)  
**Standard**: [IETF RFC 6455 — The WebSocket Protocol](https://www.rfc-editor.org/rfc/rfc6455.txt)  
**JDK Target**: Java 21 LTS (Pure JDK standard library: `java.nio`, `java.nio.charset`, zero third-party dependencies)  
**Pedagogical Guideline**: `🎓 BEDROCK TUTORIAL` Standard  

---

## 1. Executive Architecture & Component Map

The low-level WebSocket protocol engine (`com.bedrock.core.ws.protocol`) is responsible for the pure binary framing mechanics of RFC 6455. Following the upgrade handshake (HTTP 101), the TCP socket transitions into a raw binary frame stream.

```
       Incoming TCP Bytes (SocketChannel)
                       │
                       ▼
       ┌───────────────────────────────┐
       │     ByteBuffer (NIO Buffer)   │
       └───────────────┬───────────────┘
                       │
                       ▼
       ┌───────────────────────────────┐
       │     WebSocketFrameParser      │
       │   - Bitwise Header Decoding   │
       │   - Minimal Length Check      │
       │   - 4-Byte XOR Unmasking      │
       │   - Strict UTF-8 Validation   │
       │   - Protocol Error (1002/1007)│
       └───────────────┬───────────────┘
                       │ produces
                       ▼
       ┌───────────────────────────────┐
       │       WebSocketFrame          │
       │   (Immutable Value Object)    │
       │   - FIN, Opcode, Mask, Payload│
       │   - Close Code, Close Reason  │
       └───────────────┬───────────────┘
                       │ dispatched to
                       ▼
       ┌───────────────────────────────┐
       │    WebSocketClientHandler     │
       │   (Virtual Thread Loop)       │
       └───────────────┬───────────────┘
                       │ replies with
                       ▼
       ┌───────────────────────────────┐
       │     WebSocketFrameWriter      │
       │   - Server Frames (MASK = 0)  │
       │   - Minimal Length Headers    │
       │   - Text, Ping, Pong, Close   │
       └───────────────┬───────────────┘
                       │
                       ▼
       Outgoing TCP Bytes (SocketChannel)
```

### Component Roles & Invariants
1. **`WebSocketFrame`**: Immutable representation of an individual WebSocket frame. Holds frame flags (`fin`, `masked`), opcode, defensibly copied payload bytes, and parsed close attributes (`statusCode`, `reason`).
2. **`WebSocketFrameParser`**: Pure, stateless, non-destructive NIO `ByteBuffer` frame decoder. Enforces all RFC 6455 client frame invariants (mandatory masking, zero RSV bits, canonical minimal length encoding, control frame payload limit $\le 125$ bytes, close frame length $\ne 1$, strict UTF-8 validation for text and close reasons).
3. **`WebSocketFrameWriter`**: Server-to-client frame encoder. Enforces RFC 6455 §5.1 server unmasked invariant (`MASK = 0`), constructs minimal length headers, and produces byte arrays ready for `SocketChannel.write()`.

---

## 2. 32-Bit Frame Anatomy and Bitfield Specification (RFC 6455 §5.2)

```
  0                   1                   2                   3
  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 +-+-+-+-+-------+-+-------------+-------------------------------+
 |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 | |1|2|3|       |K|             |                               |
 +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 |     Extended payload length continued, if payload len == 127  |
 + - - - - - - - - - - - - - - - +-------------------------------+
 |                               |Masking-key, if MASK set to 1  |
 +-------------------------------+-------------------------------+
 | Masking-key (continued)       |          Payload Data         |
 +-------------------------------- - - - - - - - - - - - - - - - +
 :                     Payload Data continued ...                :
 + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 |                     Payload Data continued ...                |
 +---------------------------------------------------------------+
```

### Bit-Level Breakdown:
1. **Byte 0: Flags & Opcode**:
   - `FIN` (Bit 7, mask `0x80`): Indicates whether this is the final fragment of a message. `1` for single unfragmented frames.
   - `RSV1, RSV2, RSV3` (Bits 6-4, mask `0x70`): Reserved for extensions. In standard Bedrock (no extensions negotiated), non-zero RSV **MUST** cause immediate connection termination with status code `1002` (Protocol Error).
   - `Opcode` (Bits 3-0, mask `0x0F`):
     - `0x0`: Continuation
     - `0x1`: Text (UTF-8 payload)
     - `0x2`: Binary (arbitrary raw bytes)
     - `0x8`: Close (connection termination handshake)
     - `0x9`: Ping (heartbeat probe)
     - `0xA`: Pong (heartbeat response)
     - Any other opcode ($0x3-0x7$, $0xB-0xF$) is invalid and **MUST** trigger close code `1002`.

2. **Byte 1: Mask Bit & Payload Length Indicator**:
   - `MASK` (Bit 7, mask `0x80`): `1` if payload is masked; `0` if unmasked.
     - **Client-to-Server**: Client **MUST** mask all frames (`MASK = 1`). If server receives unmasked client frame, server **MUST** close with status `1002` (RFC 6455 §5.1).
     - **Server-to-Client**: Server **MUST NOT** mask frames (`MASK = 0`).
   - `Payload Length` (Bits 6-0, mask `0x7F`):
     - $0 \le N \le 125$: The value is the exact payload length in bytes.
     - $N = 126$: Payload length is an unsigned 16-bit integer contained in the next 2 bytes.
     - $N = 127$: Payload length is an unsigned 64-bit integer contained in the next 8 bytes.

3. **Extended Payload Length (2 or 8 Bytes)**:
   - **Canonical Minimal Length Rule (§5.2)**:
     - If indicator is `126`, parsed 16-bit integer **MUST be $\ge 126$**. If $< 126$, reject with status `1002`.
     - If indicator is `127`, parsed 64-bit integer **MUST be $\ge 65536$**. If $< 65536$, reject with status `1002`.
     - For 64-bit lengths, the most significant bit (MSB) **MUST be 0** (positive signed Java `long`). If negative, reject with status `1002`.

4. **Masking Key (4 Bytes)**:
   - Only present if `MASK = 1`. Placed immediately after the payload length bytes.
   - Used for XOR unmasking: $D_i = E_i \oplus M_{i \pmod 4}$.

---

## 3. Detailed Design: `WebSocketFrame.java`

### 3.1 Design Principles & Invariants
- **Strict Immutability**: All fields are `final`. The payload `byte[]` is defensively copied on construction and on read via `getPayload()`.
- **Close Frame Intelligence**: If the frame is a Close frame (`Opcode 0x8`), the status code and reason string are parsed or defaulted (code 1005 if payload is empty) upon creation and made directly accessible.
- **Defensive API**: Provides convenient factory methods for text, ping, pong, and close frames.

### 3.2 Method Contracts & Signatures
```java
package com.bedrock.core.ws.protocol;

public final class WebSocketFrame {
    // Factory methods
    public static WebSocketFrame text(String text);
    public static WebSocketFrame ping(byte[] applicationData);
    public static WebSocketFrame pong(byte[] applicationData);
    public static WebSocketFrame close(int statusCode, String reason);
    public static WebSocketFrame binary(byte[] data);

    // Core accessors
    public boolean isFin();
    public WebSocketOpcode getOpcode();
    public boolean isMasked();
    public byte[] getPayload();
    public int getPayloadLength();
    public String getPayloadAsText();
    public int getCloseStatusCode();
    public String getCloseReason();
    public boolean isControl();
}
```

### 3.3 Reference Implementation

```java
package com.bedrock.core.ws.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Frame Representation
 *
 * In RFC 6455, all data transmitted across a WebSocket connection is packaged
 * into discrete binary units called "Frames". A frame contains metadata flags
 * (FIN, RSV, Opcode), payload length information, optional masking keys,
 * and application payload data.
 *
 * This class provides an immutable, thread-safe representation of a parsed
 * or outbound frame.
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
        this.payload = payload != null && payload.length > 0 ? payload.clone() : EMPTY_PAYLOAD;
        this.closeStatusCode = closeStatusCode;
        this.closeReason = closeReason != null ? closeReason : "";
    }

    /**
     * Convenience constructor that automatically inspects close frames.
     */
    public WebSocketFrame(boolean fin, WebSocketOpcode opcode, boolean masked, byte[] payload) {
        this.fin = fin;
        this.opcode = Objects.requireNonNull(opcode, "Opcode cannot be null");
        this.masked = masked;
        this.payload = payload != null && payload.length > 0 ? payload.clone() : EMPTY_PAYLOAD;

        if (opcode == WebSocketOpcode.CLOSE) {
            if (this.payload.length == 0) {
                this.closeStatusCode = WebSocketCloseStatus.NO_STATUS_RECEIVED; // 1005
                this.closeReason = "";
            } else if (this.payload.length >= 2) {
                this.closeStatusCode = ((this.payload[0] & 0xFF) << 8) | (this.payload[1] & 0xFF);
                if (this.payload.length > 2) {
                    this.closeReason = new String(this.payload, 2, this.payload.length - 2, StandardCharsets.UTF_8);
                } else {
                    this.closeReason = "";
                }
            } else {
                // 1 byte close payload violates RFC 6455 §5.5.1
                this.closeStatusCode = WebSocketCloseStatus.PROTOCOL_ERROR;
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

    public static WebSocketFrame text(String text) {
        byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : EMPTY_PAYLOAD;
        return new WebSocketFrame(true, WebSocketOpcode.TEXT, false, bytes, -1, null);
    }

    public static WebSocketFrame ping(byte[] applicationData) {
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Control frame payload cannot exceed 125 bytes");
        }
        return new WebSocketFrame(true, WebSocketOpcode.PING, false, applicationData, -1, null);
    }

    public static WebSocketFrame pong(byte[] applicationData) {
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Control frame payload cannot exceed 125 bytes");
        }
        return new WebSocketFrame(true, WebSocketOpcode.PONG, false, applicationData, -1, null);
    }

    public static WebSocketFrame close(int statusCode, String reason) {
        if (WebSocketCloseStatus.isWireForbidden(statusCode)) {
            throw new IllegalArgumentException("Close status code " + statusCode + " cannot be sent on wire");
        }
        String safeReason = reason != null ? reason : "";
        byte[] reasonBytes = safeReason.getBytes(StandardCharsets.UTF_8);
        if (2 + reasonBytes.length > 125) {
            throw new IllegalArgumentException("Close frame payload cannot exceed 125 bytes");
        }
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((statusCode >>> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);

        return new WebSocketFrame(true, WebSocketOpcode.CLOSE, false, payload, statusCode, safeReason);
    }

    public static WebSocketFrame binary(byte[] data) {
        return new WebSocketFrame(true, WebSocketOpcode.BINARY, false, data, -1, null);
    }

    // ==========================================
    // Accessors
    // ==========================================

    public boolean isFin() {
        return fin;
    }

    public WebSocketOpcode getOpcode() {
        return opcode;
    }

    public boolean isMasked() {
        return masked;
    }

    public byte[] getPayload() {
        return payload.length > 0 ? payload.clone() : EMPTY_PAYLOAD;
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public String getPayloadAsText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public int getCloseStatusCode() {
        return closeStatusCode;
    }

    public String getCloseReason() {
        return closeReason;
    }

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
```

---

## 4. Detailed Design: `WebSocketFrameParser.java`

### 4.1 Parser Architecture & Streaming Decoder Mechanism
In a NIO network server running Virtual Threads (`SocketChannel.read(ByteBuffer)`), TCP segments may arrive fragmented or aggregated:
- **Fragmentation**: A frame header or payload may arrive partially over multiple network reads.
- **Aggregation / Pipelining**: Multiple small frames may arrive in a single `read()` call.

To handle both cases without complex stateful state machines or blocking threads, `WebSocketFrameParser.parse(ByteBuffer buffer)` uses the **NIO Mark/Reset Non-Destructive Parser Pattern**:
1. Before attempting to parse, call `buffer.mark()`.
2. Inspect available bytes (`buffer.remaining()`).
3. If at any point fewer bytes are available than needed for the header or payload, call `buffer.reset()` and return `null`.
4. The caller's Virtual Thread loop simply leaves the unconsumed bytes in the buffer, compacts (`buffer.compact()`), reads more data from the `SocketChannel`, flips (`buffer.flip()`), and parses again!
5. When a frame is complete, the buffer position is positioned exactly at the start of the next frame. The caller can loop `while ((frame = parse(buffer)) != null)` to consume all pipelined frames.

### 4.2 Step-by-Step Byte & Bit Decoding Specification

#### Step 1: Minimum Header Availability Check
```java
if (buffer.remaining() < 2) {
    return null; // Need at least 2 bytes (Byte 0 and Byte 1)
}
buffer.mark();
```

#### Step 2: Byte 0 (Flags & Opcode)
```java
byte b0 = buffer.get();
boolean fin = (b0 & 0x80) != 0;
int rsv = (b0 & 0x70) >>> 4;
int opcodeNum = b0 & 0x0F;

// 1. Validate RSV bits (RFC 6455 §5.2)
if (rsv != 0) {
    throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
        "RSV bits must be 0; received RSV=" + rsv);
}

// 2. Validate Opcode (RFC 6455 §5.2)
WebSocketOpcode opcode = WebSocketOpcode.fromCode(opcodeNum);
if (opcode == null) {
    throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
        "Unknown WebSocket opcode: " + opcodeNum);
}

// 3. Validate Control Frame Fragmentation (RFC 6455 §5.5)
if (opcode.isControl() && !fin) {
    throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
        "Control frames cannot be fragmented (FIN must be 1 for opcode " + opcode + ")");
}
```

#### Step 3: Byte 1 (Mask & Payload Length Indicator)
```java
byte b1 = buffer.get();
boolean masked = (b1 & 0x80) != 0;
int lenIndicator = b1 & 0x7F;

// Client-to-Server Mask Invariant (RFC 6455 §5.1)
if (requireMask && !masked) {
    throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
        "Client frames must be masked (RFC 6455 §5.1)");
}
```

#### Step 4: Extended Payload Length & Minimal Encoding Rules
```java
long payloadLength;
if (lenIndicator <= 125) {
    payloadLength = lenIndicator;
} else if (lenIndicator == 126) {
    // Need 2 bytes for unsigned 16-bit integer
    if (buffer.remaining() < 2) {
        buffer.reset();
        return null;
    }
    int b2 = buffer.get() & 0xFF;
    int b3 = buffer.get() & 0xFF;
    int extLen = (b2 << 8) | b3;

    // Minimal length enforcement: must be >= 126
    if (extLen < 126) {
        throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
            "Non-minimal 16-bit payload length encoding: " + extLen + " (must be >= 126)");
    }
    payloadLength = extLen;
} else { // lenIndicator == 127
    // Need 8 bytes for unsigned 64-bit integer
    if (buffer.remaining() < 8) {
        buffer.reset();
        return null;
    }
    long extLen = buffer.getLong();

    // MSB must be 0 (RFC 6455 §5.2)
    if (extLen < 0) {
        throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
            "64-bit payload length MSB must be 0 (received negative length: " + extLen + ")");
    }

    // Minimal length enforcement: must be >= 65536
    if (extLen < 65536) {
        throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
            "Non-minimal 64-bit payload length encoding: " + extLen + " (must be >= 65536)");
    }

    // Protection against excessive memory allocation
    if (extLen > MAX_ALLOWED_PAYLOAD_SIZE) {
        throw new WebSocketException(WebSocketCloseStatus.MESSAGE_TOO_BIG,
            "Payload length " + extLen + " exceeds maximum allowed (" + MAX_ALLOWED_PAYLOAD_SIZE + ")");
    }
    payloadLength = extLen;
}

// Control frame length invariant (RFC 6455 §5.5)
if (opcode.isControl() && payloadLength > 125) {
    throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
        "Control frame payload cannot exceed 125 bytes (received: " + payloadLength + ")");
}
```

#### Step 5: Masking Key & Payload Availability Check
```java
int requiredRemaining = (masked ? 4 : 0) + (int) payloadLength;
if (buffer.remaining() < requiredRemaining) {
    buffer.reset();
    return null; // Complete frame not yet available in buffer
}

byte[] maskKey = null;
if (masked) {
    maskKey = new byte[4];
    buffer.get(maskKey);
}

byte[] payload = new byte[(int) payloadLength];
buffer.get(payload);
```

#### Step 6: In-Place XOR Unmasking Algorithm
$$D_i = E_i \oplus M_{i \pmod 4}$$

```java
if (masked && maskKey != null) {
    unmask(payload, maskKey);
}
```
**Performance & Bitwise Optimization**:
Since the masking key has length 4, the operation `i % 4` can be replaced with `i & 3` (binary bitwise AND with `0b0011`). Because bitwise AND executes in a single CPU cycle and avoids division instructions, `payload[i] ^= maskKey[i & 3]` is optimal.

```java
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
```

#### Step 7: UTF-8 & Close Frame Invariant Validation
1. **Text Frame UTF-8 Validation (Opcode 0x1)**:
   Standard `new String(payload, UTF_8)` does not throw exceptions on invalid UTF-8 bytes (it silently substitutes `\uFFFD`). RFC 6455 §8.1 strictly mandates connection termination with code `1007` (Invalid Frame Payload Data) if invalid UTF-8 sequences are received.
   ```java
   if (opcode == WebSocketOpcode.TEXT) {
       validateUtf8(payload, "text frame");
   }
   ```
   Implementation:
   ```java
   private static void validateUtf8(byte[] bytes, String context) {
       CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
           .onMalformedInput(CodingErrorAction.REPORT)
           .onUnmappableCharacter(CodingErrorAction.REPORT);
       try {
           decoder.decode(ByteBuffer.wrap(bytes));
       } catch (CharacterCodingException e) {
           throw new WebSocketException(WebSocketCloseStatus.INVALID_PAYLOAD_DATA,
               "Malformed UTF-8 in " + context + ": " + e.getMessage());
       }
   }
   ```

2. **Close Frame Validation (Opcode 0x8)**:
   - **Length Invariant**: RFC 6455 §5.5.1 states that a close frame payload **MUST be 0 bytes or $\ge 2$ bytes**. A close frame with a 1-byte payload is a protocol error (`1002`).
   - **Wire-Forbidden Status Codes**: Status codes 1005 (No Status Received), 1006 (Abnormal Closure), and 1015 (TLS Handshake) **MUST NOT** appear on the wire. If received, fail with status `1002`.
   - **Valid Code Ranges**:
     - $0 - 999$: Forbidden
     - $1000 - 1003$: Valid (Normal, Going Away, Protocol Error, Unsupported Data)
     - $1004$: Reserved / Forbidden on wire
     - $1005, 1006$: Forbidden on wire
     - $1007 - 1011$: Valid (Invalid UTF-8, Policy Violation, Message Too Big, Mandatory Extension, Internal Error)
     - $1012 - 1014$: Valid (Service Restart, Try Again Later, Bad Gateway)
     - $1015$: Forbidden on wire
     - $1016 - 2999$: Reserved for future IETF
     - $3000 - 3999$: Reserved for libraries/frameworks
     - $4000 - 4999$: Reserved for private applications
     - $\ge 5000$: Invalid
   - **UTF-8 Reason String**: If payload length $> 2$, the remaining bytes (bytes $2 \dots N-1$) **MUST** be valid UTF-8. If malformed, fail with status `1007`.

```java
int closeStatus = -1;
String closeReason = null;

if (opcode == WebSocketOpcode.CLOSE) {
    if (payload.length == 1) {
        throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
            "Close frame payload cannot be exactly 1 byte (RFC 6455 §5.5.1)");
    }
    if (payload.length == 0) {
        closeStatus = WebSocketCloseStatus.NO_STATUS_RECEIVED; // 1005
        closeReason = "";
    } else {
        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        validateCloseStatusCode(code);
        closeStatus = code;

        if (payload.length > 2) {
            byte[] reasonBytes = Arrays.copyOfRange(payload, 2, payload.length);
            validateUtf8(reasonBytes, "close reason");
            closeReason = new String(reasonBytes, StandardCharsets.UTF_8);
        } else {
            closeReason = "";
        }
    }
}
```

### 4.3 Reference Implementation: `WebSocketFrameParser.java`

```java
package com.bedrock.core.ws.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Frame Parser & XOR Unmasking
 *
 * This class decodes binary frames from an NIO ByteBuffer. It strictly enforces:
 * 1. Client Masking Invariant (RFC 6455 §5.1): All client-to-server frames MUST be masked.
 * 2. RSV Validation: RSV1-3 bits must be 0.
 * 3. Minimal Length Encoding (RFC 6455 §5.2): No padded length encodings allowed.
 * 4. Control Frame Constraints: Payload <= 125 bytes and FIN must be 1.
 * 5. Close Frame Constraints: Payload length 0 or >= 2 (never 1); wire-forbidden status codes rejected.
 * 6. UTF-8 Validation: Strict rejection of malformed UTF-8 in text frames and close reasons.
 */
public final class WebSocketFrameParser {

    /** Maximum frame payload size allowed (default 16MB) */
    public static final int MAX_ALLOWED_PAYLOAD_SIZE = 16 * 1024 * 1024;

    private WebSocketFrameParser() {
        // Pure utility class
    }

    /**
     * Parses the next complete WebSocketFrame from the buffer in server mode (enforcing client masking).
     *
     * @param buffer The NIO buffer containing raw TCP frame bytes.
     * @return The parsed WebSocketFrame, or null if the buffer does not yet contain a complete frame.
     * @throws WebSocketException If a protocol violation or encoding error occurs.
     */
    public static WebSocketFrame parse(ByteBuffer buffer) throws WebSocketException {
        return parse(buffer, true);
    }

    /**
     * Parses the next complete WebSocketFrame from the buffer.
     *
     * @param buffer The NIO buffer containing raw frame bytes.
     * @param requireMask True if client-masking is strictly enforced (server mode).
     * @return The parsed WebSocketFrame, or null if incomplete.
     * @throws WebSocketException On protocol violation.
     */
    public static WebSocketFrame parse(ByteBuffer buffer, boolean requireMask) throws WebSocketException {
        if (buffer == null || buffer.remaining() < 2) {
            return null;
        }

        // Mark buffer position so we can reset non-destructively if incomplete
        buffer.mark();

        // 1. Byte 0: FIN, RSV1-3, Opcode
        byte b0 = buffer.get();
        boolean fin = (b0 & 0x80) != 0;
        int rsv = (b0 & 0x70) >>> 4;
        int opcodeNum = b0 & 0x0F;

        if (rsv != 0) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "RSV bits must be 0; received RSV=" + rsv);
        }

        WebSocketOpcode opcode = WebSocketOpcode.fromCode(opcodeNum);
        if (opcode == null) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "Unknown WebSocket opcode: " + opcodeNum);
        }

        if (opcode.isControl() && !fin) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "Control frames cannot be fragmented (FIN must be 1 for opcode " + opcode + ")");
        }

        // 2. Byte 1: Mask bit & Payload length indicator
        byte b1 = buffer.get();
        boolean masked = (b1 & 0x80) != 0;
        int lenIndicator = b1 & 0x7F;

        if (requireMask && !masked) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "Client frame must be masked (RFC 6455 §5.1)");
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

            if (extLen < 126) {
                throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                    "Non-minimal 16-bit payload length encoding: " + extLen + " (must be >= 126)");
            }
            payloadLength = extLen;
        } else { // 127
            if (buffer.remaining() < 8) {
                buffer.reset();
                return null;
            }
            long extLen = buffer.getLong();

            if (extLen < 0) {
                throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                    "64-bit payload length MSB must be 0 (received: " + extLen + ")");
            }
            if (extLen < 65536) {
                throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                    "Non-minimal 64-bit payload length encoding: " + extLen + " (must be >= 65536)");
            }
            if (extLen > MAX_ALLOWED_PAYLOAD_SIZE) {
                throw new WebSocketException(WebSocketCloseStatus.MESSAGE_TOO_BIG,
                    "Payload length " + extLen + " exceeds maximum allowed (" + MAX_ALLOWED_PAYLOAD_SIZE + ")");
            }
            payloadLength = extLen;
        }

        if (opcode.isControl() && payloadLength > 125) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "Control frame payload cannot exceed 125 bytes (received: " + payloadLength + ")");
        }

        // 4. Check whether entire frame (mask key + payload) is available
        int requiredBytes = (masked ? 4 : 0) + (int) payloadLength;
        if (buffer.remaining() < requiredBytes) {
            buffer.reset();
            return null;
        }

        // 5. Read Masking Key
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            buffer.get(maskKey);
        }

        // 6. Read Payload
        byte[] payload = new byte[(int) payloadLength];
        buffer.get(payload);

        // 7. In-place XOR Unmasking
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
                throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                    "Close frame payload cannot be exactly 1 byte (RFC 6455 §5.5.1)");
            }
            if (payload.length == 0) {
                closeStatusCode = WebSocketCloseStatus.NO_STATUS_RECEIVED; // 1005
                closeReason = "";
            } else {
                int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                validateCloseStatusCode(code);
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
     * Performs in-place 4-byte XOR unmasking: D_i = E_i ^ M_{i mod 4}.
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
            throw new WebSocketException(WebSocketCloseStatus.INVALID_PAYLOAD_DATA,
                "Malformed UTF-8 in " + context + ": " + e.getMessage());
        }
    }

    private static void validateCloseStatusCode(int code) {
        if (WebSocketCloseStatus.isWireForbidden(code)) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "Close status code " + code + " is forbidden on the wire");
        }
        if (code < 1000 || code == 1004 || (code >= 1016 && code <= 2999) || code > 4999) {
            throw new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR,
                "Invalid close status code: " + code);
        }
    }
}
```

---

## 5. Detailed Design: `WebSocketFrameWriter.java`

### 5.1 Server Framing Principles (RFC 6455 §5.1)
- **Zero Server Masking**: Server **MUST NOT** mask outgoing frames (`MASK = 0`).
- **Minimal Encoding**: 
  - $N \le 125$: 2-byte header.
  - $126 \le N \le 65535$: 4-byte header (indicator 126 + 2 length bytes).
  - $N \ge 65536$: 10-byte header (indicator 127 + 8 length bytes).
- **Single Byte Array Allocation**: To maximize performance and reduce garbage collector churn, `WebSocketFrameWriter` computes the exact header length ($2$, $4$, or $10$), allocates a single contiguous `byte[headerLength + payloadLength]`, populates the header, copies the payload using `System.arraycopy`, and returns the array.

### 5.2 Method Contracts & Signatures
```java
package com.bedrock.core.ws.protocol;

public final class WebSocketFrameWriter {
    public static byte[] createTextFrame(String text);
    public static byte[] createPingFrame(byte[] applicationData);
    public static byte[] createPongFrame(byte[] applicationData);
    public static byte[] createCloseFrame(int statusCode, String reason);
    public static byte[] createBinaryFrame(byte[] data);

    // Generic frame encoder (unmasked server frame)
    public static byte[] createFrame(WebSocketOpcode opcode, byte[] payload, boolean fin);

    // Test helper for generating masked frames
    public static byte[] createMaskedFrame(WebSocketOpcode opcode, byte[] payload, boolean fin, byte[] maskKey);
}
```

### 5.3 Reference Implementation: `WebSocketFrameWriter.java`

```java
package com.bedrock.core.ws.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 🎓 BEDROCK TUTORIAL: Server-to-Client Frame Serializer
 *
 * In RFC 6455 §5.1, an important asymmetry exists between client and server frames:
 * - Clients MUST mask all frames.
 * - Servers MUST NOT mask any frames.
 *
 * This class serializes unmasked server frames directly into raw byte arrays,
 * formatting the 7-bit, 16-bit, or 64-bit payload length headers according to
 * the canonical minimal length rules.
 */
public final class WebSocketFrameWriter {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private WebSocketFrameWriter() {
        // Pure utility class
    }

    public static byte[] createTextFrame(String text) {
        byte[] payload = text != null ? text.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES;
        return createFrame(WebSocketOpcode.TEXT, payload, true);
    }

    public static byte[] createPingFrame(byte[] applicationData) {
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Ping payload cannot exceed 125 bytes");
        }
        return createFrame(WebSocketOpcode.PING, applicationData != null ? applicationData : EMPTY_BYTES, true);
    }

    public static byte[] createPongFrame(byte[] applicationData) {
        if (applicationData != null && applicationData.length > 125) {
            throw new IllegalArgumentException("Pong payload cannot exceed 125 bytes");
        }
        return createFrame(WebSocketOpcode.PONG, applicationData != null ? applicationData : EMPTY_BYTES, true);
    }

    public static byte[] createCloseFrame(int statusCode, String reason) {
        if (WebSocketCloseStatus.isWireForbidden(statusCode)) {
            throw new IllegalArgumentException("Close status code " + statusCode + " is wire-forbidden");
        }
        byte[] reasonBytes = reason != null ? reason.getBytes(StandardCharsets.UTF_8) : EMPTY_BYTES;
        if (2 + reasonBytes.length > 125) {
            throw new IllegalArgumentException("Close frame payload cannot exceed 125 bytes");
        }

        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((statusCode >>> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);

        return createFrame(WebSocketOpcode.CLOSE, payload, true);
    }

    public static byte[] createBinaryFrame(byte[] data) {
        return createFrame(WebSocketOpcode.BINARY, data != null ? data : EMPTY_BYTES, true);
    }

    /**
     * Serializes an unmasked server frame (MASK = 0).
     */
    public static byte[] createFrame(WebSocketOpcode opcode, byte[] payload, boolean fin) {
        return encodeFrame(opcode, payload, fin, false, null);
    }

    /**
     * Serializes a masked frame (used primarily for unit testing client frames).
     */
    public static byte[] createMaskedFrame(WebSocketOpcode opcode, byte[] payload, boolean fin, byte[] maskKey) {
        if (maskKey == null || maskKey.length != 4) {
            throw new IllegalArgumentException("Mask key must be exactly 4 bytes");
        }
        return encodeFrame(opcode, payload, fin, true, maskKey);
    }

    private static byte[] encodeFrame(WebSocketOpcode opcode, byte[] payload, boolean fin, boolean masked, byte[] maskKey) {
        if (payload == null) {
            payload = EMPTY_BYTES;
        }

        int payloadLen = payload.length;
        int headerLen;
        int lengthIndicator;

        if (payloadLen <= 125) {
            headerLen = 2;
            lengthIndicator = payloadLen;
        } else if (payloadLen <= 65535) {
            headerLen = 4;
            lengthIndicator = 126;
        } else {
            headerLen = 10;
            lengthIndicator = 127;
        }

        if (masked) {
            headerLen += 4; // 4-byte masking key
        }

        byte[] frame = new byte[headerLen + payloadLen];

        // Byte 0: FIN (bit 7) + Opcode (bits 3-0)
        frame[0] = (byte) ((fin ? 0x80 : 0x00) | (opcode.getCode() & 0x0F));

        // Byte 1: MASK bit (bit 7) + Payload length indicator (bits 6-0)
        frame[1] = (byte) ((masked ? 0x80 : 0x00) | (lengthIndicator & 0x7F));

        int offset = 2;

        // Extended payload length bytes
        if (lengthIndicator == 126) {
            frame[offset++] = (byte) ((payloadLen >>> 8) & 0xFF);
            frame[offset++] = (byte) (payloadLen & 0xFF);
        } else if (lengthIndicator == 127) {
            long len = payloadLen;
            frame[offset++] = (byte) ((len >>> 56) & 0xFF);
            frame[offset++] = (byte) ((len >>> 48) & 0xFF);
            frame[offset++] = (byte) ((len >>> 40) & 0xFF);
            frame[offset++] = (byte) ((len >>> 32) & 0xFF);
            frame[offset++] = (byte) ((len >>> 24) & 0xFF);
            frame[offset++] = (byte) ((len >>> 16) & 0xFF);
            frame[offset++] = (byte) ((len >>> 8) & 0xFF);
            frame[offset++] = (byte) (len & 0xFF);
        }

        // Masking key
        if (masked) {
            System.arraycopy(maskKey, 0, frame, offset, 4);
            offset += 4;

            // Copy and mask payload
            for (int i = 0; i < payloadLen; i++) {
                frame[offset + i] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
        } else {
            // Unmasked: direct copy
            System.arraycopy(payload, 0, frame, offset, payloadLen);
        }

        return frame;
    }
}
```

---

## 6. Byte-by-Byte Worked Verification Vectors

### 6.1 Vector 1: Client Masked Text Frame ("Hello")
- **Payload**: `"Hello"` (UTF-8 bytes: `0x48, 0x65, 0x6C, 0x6C, 0x6F`, length 5)
- **Masking Key**: `0x37, 0xFA, 0x21, 0x3D`
- **Masked Payload Calculation**:
  - $D_0 = 0x48 \oplus 0x37 = 0x7F$
  - $D_1 = 0x65 \oplus 0xFA = 0x9F$
  - $D_2 = 0x6C \oplus 0x21 = 0x4D$
  - $D_3 = 0x6C \oplus 0x3D = 0x51$
  - $D_4 = 0x6F \oplus 0x37 = 0x58$
- **Raw Frame Bytes (Hex)**:
  `81 85 37 FA 21 3D 7F 9F 4D 51 58`
- **Decoded Result**:
  - `fin = true`
  - `opcode = TEXT (0x1)`
  - `masked = true`
  - `payload = "Hello"`

### 6.2 Vector 2: Server Unmasked Text Frame ("Hello")
- **Payload**: `"Hello"`
- **Byte 0**: `0x81` (FIN=1, Opcode=1)
- **Byte 1**: `0x05` (MASK=0, Length=5)
- **Payload**: `0x48, 0x65, 0x6C, 0x6C, 0x6F`
- **Raw Frame Bytes (Hex)**:
  `81 05 48 65 6C 6C 6F`

### 6.3 Vector 3: Medium Payload (256 Bytes, Unmasked Server Frame)
- **Payload**: 256 bytes (`0x00` through `0xFF`)
- **Byte 0**: `0x82` (FIN=1, Opcode=BINARY 0x2)
- **Byte 1**: `0x7E` (MASK=0, indicator 126)
- **Bytes 2-3**: `0x01, 0x00` ($256 = 0x0100$)
- **Bytes 4-259**: 256 data bytes.
- **Total Frame Size**: 260 bytes.

### 6.4 Vector 4: Close Frame (Code 1000, Reason "Normal")
- **Status Code**: 1000 (`0x03E8`)
- **Reason**: `"Normal"` (`0x4E, 0x6F, 0x72, 0x6D, 0x61, 0x6C`, 6 bytes)
- **Payload**: 8 bytes (`03 E8 4E 6F 72 6D 61 6C`)
- **Byte 0**: `0x88` (FIN=1, Opcode=0x8)
- **Byte 1**: `0x08` (MASK=0, Length=8)
- **Raw Frame Bytes (Hex)**:
  `88 08 03 E8 4E 6F 72 6D 61 6C`

### 6.5 Vector 5: Protocol Error Invariants (Status 1002 / 1007 Triggers)
| Test Vector Description | Raw Bytes (Hex) | Expected Exception | Rationale |
|---|---|---|---|
| **Unmasked Client Frame** | `81 05 48 65 6C 6C 6F` | `WebSocketException(1002)` | Client frames must have MASK=1. |
| **Non-Zero RSV1** | `C1 80 12 34 56 78` | `WebSocketException(1002)` | RSV1 bit set without extension negotiation. |
| **Unknown Opcode 0x3** | `83 80 12 34 56 78` | `WebSocketException(1002)` | Opcode 0x3 is reserved. |
| **Fragmented Control Frame** | `09 80 12 34 56 78` | `WebSocketException(1002)` | Ping frame has FIN=0. |
| **Control Frame Length > 125** | `89 FE 00 80 12 34 56 78 ...` | `WebSocketException(1002)` | Ping has extended length 128 bytes. |
| **Non-Minimal 16-Bit Length** | `81 FE 00 13 12 34 56 78 ...` | `WebSocketException(1002)` | Length 19 encoded with indicator 126. |
| **Non-Minimal 64-Bit Length** | `81 FF 00 00 00 00 00 00 01 F4 ...` | `WebSocketException(1002)` | Length 500 encoded with indicator 127. |
| **1-Byte Close Frame** | `88 81 12 34 56 78 AA` | `WebSocketException(1002)` | Close frame payload cannot be 1 byte. |
| **Forbidden Close Code (1005)**| `88 82 12 34 56 78 03 ED` | `WebSocketException(1002)` | Status code 1005 forbidden on wire. |
| **Invalid UTF-8 in Text** | `81 82 00 00 00 00 FF FF` | `WebSocketException(1007)` | Payload contains invalid UTF-8 bytes. |

---

## 7. Educational Standard (`🎓 BEDROCK TUTORIAL`) Integration

Each class header includes comprehensive didactic documentation:

### `WebSocketFrameParser` Didactic Header:
```java
/**
 * 🎓 BEDROCK TUTORIAL: Demystifying WebSocket Frame Parsing & Masking
 *
 * <h3>1. Why Does WebSocket Need Framing?</h3>
 * Unlike HTTP/1.1 where messages are separated by Content-Length headers or
 * Chunked Transfer Encoding ("\r\n"), WebSocket is a continuous, full-duplex
 * binary TCP stream. Framing provides message boundaries without requiring
 * delimiter scanning (like '\n').
 *
 * <h3>2. Why Must Clients Mask Every Frame?</h3>
 * The "Transparent Proxy Cache Poisoning" Vulnerability:
 * Before RFC 6455 was standardized, researchers discovered that malicious scripts
 * inside a browser could send byte sequences looking like genuine HTTP GET requests
 * over an unmasked WebSocket connection. Naive transparent caching proxies on port 80
 * would misinterpret these bytes as HTTP requests, fetch the malicious file, and
 * cache it for subsequent legitimate users.
 *
 * By requiring the client to XOR-mask every frame with a random 4-byte key,
 * the bytes on the physical wire become pseudo-random noise. No recognizable HTTP
 * headers can appear on the physical network.
 *
 * <h3>3. The Physics of XOR Unmasking</h3>
 * XOR (Exclusive OR, denoted by ^ in Java) possesses a unique algebraic property:
 * it is an involution (self-inverse):
 * (A ^ B) ^ B = A ^ (B ^ B) = A ^ 0 = A
 *
 * The exact same XOR operation masks data on the client and unmasks data on the server.
 * With a 4-byte key, byte index 'i' is masked with key[i % 4]. In high-performance
 * Java, 'i % 4' is expressed bitwise as 'i & 3'.
 */
```

### `WebSocketFrameWriter` Didactic Header:
```java
/**
 * 🎓 BEDROCK TUTORIAL: Server Frame Serialization & Asymmetry
 *
 * <h3>1. The Protocol Asymmetry</h3>
 * Notice that WebSocketFrameWriter NEVER masks server-to-client frames!
 * Why?
 * Clients do not operate as transparent reverse-proxy caches for downstream users.
 * An attacker cannot poison a client cache via incoming server traffic in the same
 * manner as upstream proxies. Thus, RFC 6455 §5.1 explicitly forbids server masking
 * to save CPU cycles on high-throughput servers.
 *
 * <h3>2. Minimal Length Encoding</h3>
 * RFC 6455 strictly enforces canonical encoding:
 * A 10-byte message MUST be encoded with a 7-bit length header. If a sender encoded
 * it using the 16-bit indicator 126, a compliant receiver MUST reject it with status 1002.
 * This prevents framing ambiguity and smuggling attacks.
 */
```

---

## 8. Summary of Interface Alignment with Other Milestones

| Target File | Package | Milestone | Dependent Agents | Alignment Status |
|---|---|---|---|---|
| `WebSocketFrame.java` | `com.bedrock.core.ws.protocol` | M1 | M2 (`WebSocketClientHandler`), M3 (`BedrockWebSocketSession`) | 100% compliant with `PROJECT.md` contracts |
| `WebSocketFrameParser.java` | `com.bedrock.core.ws.protocol` | M1 | M2 (`WebSocketClientHandler`), M1 (`WebSocketFrameTest`) | 100% compliant with `PROJECT.md` contracts |
| `WebSocketFrameWriter.java` | `com.bedrock.core.ws.protocol` | M1 | M2 (`BedrockWebSocketSession`), M1 (`WebSocketFrameTest`) | 100% compliant with `PROJECT.md` contracts |
