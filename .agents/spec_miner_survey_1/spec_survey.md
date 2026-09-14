# RFC 6455 WebSocket Technical Specification Survey
**Author**: `spec_miner_survey_1` (RFC 6455 Specification Miner)  
**Target System**: Bedrock Java Framework v2.0 (Real-Time WebSockets on JDK 21 Virtual Threads)  
**Authoritative Standard**: [IETF RFC 6455 — The WebSocket Protocol](https://www.rfc-editor.org/rfc/rfc6455.txt)  
**Status**: Authoritative Reference & Engineering Blueprint

---

## 1. Executive Summary & Specification Scope

The WebSocket Protocol (RFC 6455) provides bidirectional, full-duplex communication channels over a single TCP connection. It is designed to work over existing HTTP ports (80 and 443) and through existing HTTP proxies and firewalls.

For **Bedrock v2.0**, the protocol implementation must strictly adhere to the following principles:
1. **Zero External Dependencies**: Implemented purely with standard Java 21 APIs (`java.nio.channels`, `java.nio.ByteBuffer`, `java.security.MessageDigest`, `java.util.Base64`, `java.nio.charset.StandardCharsets`).
2. **Pedagogical Clarity (🎓 BEDROCK TUTORIAL)**: Demystify the protocol layers — from the initial HTTP 101 upgrade handshake, to bitwise frame header manipulation, to 4-byte XOR payload unmasking.
3. **High-Performance Virtual Threads**: Every active WebSocket connection is managed by a lightweight Virtual Thread (`Thread.ofVirtual().name("ws-client-", ...)`), enabling clean synchronous I/O without the complexity or cognitive overhead of reactive frameworks or Netty.
4. **RFC 6455 Conformance**: Complete adherence to frame parsing, length encoding (7-bit, 16-bit, 64-bit), control frame invariants, close handshakes, and error handling (closing connections with exact status codes such as 1002 Protocol Error or 1007 Invalid Frame Payload Data).

---

## 2. HTTP 101 Switching Protocols Handshake

### 2.1 Handshake Overview & Flow

The connection begins as an HTTP/1.1 request from the client requesting an upgrade of the connection from HTTP to WebSocket. Once upgraded, the underlying TCP connection remains open in full-duplex binary mode.

```
       Client                                       Server
          |                                            |
          |  1. HTTP GET /chat                         |
          |     Upgrade: websocket                     |
          |     Connection: Upgrade                    |
          |     Sec-WebSocket-Key: dGhlIHNhbXBsZ...    |
          |     Sec-WebSocket-Version: 13              |
          | -----------------------------------------> |
          |                                            |  Verify headers
          |                                            |  Compute Sec-WebSocket-Accept
          |                                            |  (SHA-1(Key + GUID) -> Base64)
          |  2. HTTP/1.1 101 Switching Protocols       |
          |     Upgrade: websocket                     |
          |     Connection: Upgrade                    |
          |     Sec-WebSocket-Accept: s3pPLMBiT...     |
          |     \r\n                                   |
          | <----------------------------------------- |
          |                                            |
          | ========================================== |
          |  Connection is now OPEN (RFC 6455 Frames)  |
          | ========================================== |
          |                                            |
          |  3. Masked Client Frame (Opcode 0x1)       |
          | -----------------------------------------> |
          |                                            |  XOR Unmasking
          |                                            |  Invoke @OnMessage
          |  4. Unmasked Server Frame (Opcode 0x1)     |
          | <----------------------------------------- |
```

---

### 2.2 Client Request Validation (RFC 6455 §4.2.1)

To initiate a valid WebSocket connection, the client HTTP request MUST meet all of the following criteria:

| Header / Field | Required Value / Format | Validation Rule | RFC Reference | Error on Mismatch |
|---|---|---|---|---|
| **Request Method** | `GET` | Case-sensitive; must be HTTP `GET`. | §4.2.1 item 1 | HTTP 405 Method Not Allowed or 400 Bad Request |
| **HTTP Version** | `HTTP/1.1` or higher | Must be at least HTTP/1.1. | §4.2.1 item 2 | HTTP 400 Bad Request |
| **Request-URI** | Valid URI path (e.g., `/chat`) | Must match a registered `@BedrockSocket` path. | §4.2.1 item 3 | HTTP 404 Not Found |
| **Host** | `<host>[:<port>]` | Standard HTTP/1.1 requirement. | §4.2.1 item 4 | HTTP 400 Bad Request |
| **Upgrade** | `websocket` | Case-insensitive token. Must contain `"websocket"`. | §4.2.1 item 5 | HTTP 400 Bad Request |
| **Connection** | `Upgrade` | Case-insensitive token list. Must contain `"upgrade"` (e.g. `"keep-alive, Upgrade"` is valid). | §4.2.1 item 6 | HTTP 400 Bad Request |
| **Sec-WebSocket-Key** | Base64 string of 16-byte nonce | Must be a valid Base64 string that decodes to exactly 16 bytes. | §4.2.1 item 7 | HTTP 400 Bad Request |
| **Sec-WebSocket-Version**| `13` | Must be exactly `"13"`. | §4.2.1 item 8 | HTTP 426 Upgrade Required + `Sec-WebSocket-Version: 13` |
| **Origin** (Optional) | URI string | Sent by browser clients. Used for CORS / origin validation. | §4.2.1 item 9 | HTTP 403 Forbidden (if policy rejects) |
| **Sec-WebSocket-Protocol** (Optional) | Comma-separated tokens | Client requested subprotocols (e.g. `chat, superchat`). | §4.2.1 item 10 | Ignored or matched |

#### Handling `Sec-WebSocket-Version` mismatch:
If `Sec-WebSocket-Version` is missing or not `"13"`, RFC 6455 §4.4 dictates:
```http
HTTP/1.1 426 Upgrade Required\r\n
Sec-WebSocket-Version: 13\r\n
Content-Type: text/plain\r\n
Content-Length: 26\r\n
\r\n
Upgrade Required: Use RFC 6455 (Version 13)
```

---

### 2.3 `Sec-WebSocket-Accept` Calculation Algorithm (RFC 6455 §4.2.2 & §1.3)

The calculation of `Sec-WebSocket-Accept` is mathematically specified as:

$$\text{Sec-WebSocket-Accept} = \text{Base64}\Big(\text{SHA-1}\big(\text{Sec-WebSocket-Key} \,\|\, \text{"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"}\big)\Big)$$

Where:
- $\|$ denotes string concatenation.
- The GUID `"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"` is a fixed constant defined in RFC 6455.
- The concatenated string is converted to bytes using US-ASCII or ISO-8859-1 (standard ASCII charset).
- SHA-1 produces a 160-bit (20-byte) binary digest.
- Base64 encodes the 20 bytes into a 28-character ASCII string terminated with `=`.

#### Implementation in Standard JDK 21:
```java
public static String computeAcceptKey(String clientKey) {
    if (clientKey == null || clientKey.isBlank()) {
        throw new IllegalArgumentException("Sec-WebSocket-Key cannot be null or empty");
    }
    String magicGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    String combined = clientKey.trim() + magicGuid;
    try {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(combined.getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-1 digest algorithm missing from JVM", e);
    }
}
```

---

### 2.4 Official Test Vectors (RFC 6455 §1.3)

#### Test Vector 1 (Official RFC 6455 §1.3):
- **Client Key (`Sec-WebSocket-Key`)**: `"dGhlIHNhbXBsZSBub25jZQ=="`
- **Concatenation**: `"dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11"`
- **SHA-1 Hash (Hex)**: `b3 7a 4f 2c c0 62 4f 16 90 f6 46 06 cf 38 59 45 b2 be c4 ea`
- **Computed Accept (`Sec-WebSocket-Accept`)**: `"s3pPLMBiTxaQ9kYGzzhZRbK+xOo="`

#### Test Vector 2 (Additional verification vector):
- **Client Key**: `"x3JJHMbDL1EzLkh9GBhXDw=="`
- **Concatenation**: `"x3JJHMbDL1EzLkh9GBhXDw==258EAFA5-E914-47DA-95CA-C5AB0DC85B11"`
- **SHA-1 Hash (Hex)**: `01 5b 60 74 6a b4 5f f2 42 f6 b1 a9 7e 12 37 f0 45 f4 a8 4d`
- **Computed Accept**: `"AVtgdGq0X/JC9rGpfhI38EX0qE0="`

---

### 2.5 Server Handshake Response (RFC 6455 §4.2.2)

When the server accepts the upgrade, it writes the following byte sequence to the `SocketChannel`:

```http
HTTP/1.1 101 Switching Protocols\r\n
Upgrade: websocket\r\n
Connection: Upgrade\r\n
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n
\r\n
```

- Each line MUST terminate with CRLF (`\r\n`, hex `0x0D 0x0A`).
- The header block MUST terminate with a blank line (`\r\n\r\n`).
- No HTTP body is sent.
- Immediately following the second `\r\n`, the TCP socket is in WebSocket framing mode.

---

## 3. RFC 6455 Frame Anatomy and Bit-Level Mechanics

### 3.1 32-Bit Frame Layout Diagram (RFC 6455 §5.2)

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

---

### 3.2 Detailed Bitfield Specification

#### Byte 0: Flags and Opcode
```
Bit:   7     6     5     4     3   2   1   0
     +-----+-----+-----+-----+---------------+
     | FIN | RSV1| RSV2| RSV3|    Opcode     |
     +-----+-----+-----+-----+---------------+
```

1. **FIN (Bit 7, `0x80`)**:
   - `1`: Final fragment of a message. A single unfragmented frame has `FIN = 1`.
   - `0`: More fragments follow for this message.
2. **RSV1, RSV2, RSV3 (Bits 6-4, `0x40`, `0x20`, `0x10`)**:
   - MUST be `0` unless an extension negotiated in the handshake (e.g. permessage-deflate) gives them meaning.
   - **Validation Rule**: If non-zero and no extension is negotiated, server MUST close connection with status code `1002` (Protocol Error).
3. **Opcode (Bits 3-0, `0x0F`)**:
   - Defines the interpretation of the payload data.
   - **Data Opcodes**:
     - `0x0` (`0000b`): **Continuation Frame** (continues payload of previous frame).
     - `0x1` (`0001b`): **Text Frame** (payload is valid UTF-8 text).
     - `0x2` (`0010b`): **Binary Frame** (payload is arbitrary binary).
     - `0x3` - `0x7`: Reserved for further non-control frames.
   - **Control Opcodes**:
     - `0x8` (`1000b`): **Connection Close**.
     - `0x9` (`1001b`): **Ping**.
     - `0xA` (`1010b`): **Pong**.
     - `0xB` - `0xF`: Reserved for further control frames.
   - **Validation Rule**: If an unrecognized opcode is received, server MUST fail connection with status code `1002`.

---

#### Byte 1: Mask Bit and Payload Length Indicator
```
Bit:   7     6   5   4   3   2   1   0
     +-----+-------------------------+
     |MASK |   Payload Length (7)    |
     +-----+-------------------------+
```

1. **MASK (Bit 7, `0x80`)**:
   - `1`: Payload is masked using a 4-byte masking key.
   - `0`: Payload is unmasked.
   - **Directional Invariant Rules (RFC 6455 §5.1)**:
     - **Client-to-Server**: Client **MUST** mask all frames (`MASK = 1`). If a server receives an unmasked frame from a client, the server **MUST** close the connection with status code `1002` (Protocol Error).
     - **Server-to-Client**: Server **MUST NOT** mask any frames (`MASK = 0`). If a client receives a masked frame from a server, the client must close connection with status code `1002`.

2. **Payload Length (Bits 6-0, `0x7F`)**:
   - Encodes either the direct length or signals an extended length:
     - `0` to `125`: Direct length of the payload in bytes. Header has NO extended length bytes.
     - `126`: Payload length is encoded in the next **2 bytes** as an unsigned 16-bit integer (range: 126 to 65,535).
     - `127`: Payload length is encoded in the next **8 bytes** as an unsigned 64-bit integer (range: 65,536 to $2^{63}-1$).

---

### 3.3 Extended Payload Length and Minimal Encoding Invariant (§5.2)

To prevent framing ambiguity and spoofing, RFC 6455 enforces **canonical minimal byte encoding**:
- If payload length is $\le 125$, it **MUST** use the 7-bit field. It **MUST NOT** use 126 or 127.
- If payload length is between 126 and 65,535, it **MUST** use 16-bit extended length (indicator 126). It **MUST NOT** use 127.
- If payload length is $\ge 65,536$, it uses 64-bit extended length (indicator 127).
- For 64-bit lengths, the most significant bit (MSB) **MUST be 0** (must be non-negative).

**Validation Rule**: An endpoint MUST close a connection with status code `1002` if it receives a frame whose length is not encoded using the minimal number of bytes.

#### Payload Length Summary Table:

| Length Value ($N$) | Byte 1 (Bits 6-0) | Extended Length Bytes | Total Header Size (Unmasked) | Total Header Size (Masked) |
|---|---|---|---|---|
| $0 \le N \le 125$ | $N$ | 0 bytes | 2 bytes | 6 bytes |
| $126 \le N \le 65,535$ | `126` (`0x7E`) | 2 bytes (unsigned 16-bit big-endian) | 4 bytes | 8 bytes |
| $65,536 \le N < 2^{63}$ | `127` (`0x7F`) | 8 bytes (unsigned 64-bit big-endian, MSB=0) | 10 bytes | 14 bytes |

---

### 3.4 Masking Key and XOR Unmasking Algorithm (RFC 6455 §5.3)

#### Masking Key Offset:
When `MASK = 1`, the 4-byte masking key immediately follows the payload length bytes:
- If length $\le 125$: Masking key is at bytes 2, 3, 4, 5.
- If length == 126: Masking key is at bytes 4, 5, 6, 7.
- If length == 127: Masking key is at bytes 10, 11, 12, 13.

#### Mathematical XOR Transformation:
RFC 6455 §5.3 defines:
$$D_i = E_i \oplus M_{i \pmod 4}$$

Where:
- $E_i$ is the $i$-th octet (byte) of the encoded (masked) payload ($0$-indexed).
- $M$ is the 4-byte masking key: $M_0, M_1, M_2, M_3$.
- $D_i$ is the $i$-th octet of the decoded (unmasked) original payload.
- $\oplus$ denotes bitwise exclusive-OR (XOR).

#### Proof of Involution:
Because XOR is self-inverse:
$$(P \oplus K) \oplus K = P \oplus (K \oplus K) = P \oplus 0 = P$$
Therefore, the exact same function performs both masking and unmasking!

#### Standard Java 21 Unmasking Implementation:
```java
public static void unmask(byte[] payload, byte[] maskKey) {
    if (maskKey == null || maskKey.length != 4) {
        throw new IllegalArgumentException("Mask key must be exactly 4 bytes");
    }
    for (int i = 0; i < payload.length; i++) {
        payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
    }
}
```

#### Fast In-Place Buffer Unmasking:
```java
public static void unmaskByteBuffer(ByteBuffer buffer, int offset, int length, byte[] mask) {
    for (int i = 0; i < length; i++) {
        int index = offset + i;
        byte b = buffer.get(index);
        buffer.put(index, (byte) (b ^ mask[i % 4]));
    }
}
```

---

### 3.5 Control Frames Specification (RFC 6455 §5.5)

Control frames communicate state about the WebSocket connection itself (Close, Ping, Pong).

#### Mandatory Invariant Rules for All Control Frames:
1. **No Fragmentation**: `FIN` bit **MUST** be `1` (Opcodes 0x8, 0x9, 0xA cannot have `FIN = 0`).
2. **Maximum Payload Length**: Payload length **MUST NOT exceed 125 bytes**. Control frames must never use extended length 126 or 127.
3. **Interleaving Permitted**: Control frames **MAY be injected in the middle of a fragmented data message**. The receiving endpoint must process the control frame immediately without corrupting the reassembly of the fragmented message.
4. **Validation Failure**: If a control frame has `FIN = 0` or length $> 125$, the server MUST close connection with status code `1002` (Protocol Error).

---

### 3.6 Ping & Pong Mechanics (RFC 6455 §5.5.2 & §5.5.3)

- **Ping Frame (`0x9`)**:
  - May contain an application data payload (0 to 125 bytes).
  - Used for keep-alive, heartbeat, and detecting dead TCP connections.
- **Pong Frame (`0xA`)**:
  - **Echo Requirement**: When an endpoint receives a Ping frame, it **MUST** send a Pong frame in response as soon as practical.
  - The Pong frame **MUST** contain the exact same application data bytes as the Ping frame it responds to.
  - **Unsolicited Pong**: An endpoint may send a Pong frame proactively as a unidirectional heartbeat. If an endpoint receives an unsolicited Pong, it may simply ignore it.

---

### 3.7 Close Frame Anatomy and Handshake (RFC 6455 §5.5.1)

#### Anatomy:
A Close frame (`Opcode 0x8`) may have:
- **0 bytes payload**: Valid close frame with no status code (treated as code 1005).
- **$\ge 2$ bytes payload**:
  - Bytes 0-1: 16-bit unsigned integer status code (big-endian).
  - Bytes 2..N: UTF-8 encoded reason string explaining the closure.
- **1 byte payload**: **STRICTLY FORBIDDEN**. A close frame body of exactly 1 byte is a protocol violation. Server MUST fail the connection with status code `1002`.

```
Close Payload:
 0                   1                   2 ... N
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-----------------------+
|      Status Code (16 bits)    | UTF-8 Reason (opt)    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-----------------------+
```

#### The Two-Way Close Handshake:
1. When Client sends a Close frame:
   - Server reads status code and reason.
   - Server responds with an outgoing Close frame echoing the status code (or sending 1000 Normal Closure).
   - Server immediately closes the underlying TCP socket.
2. When Server initiates Close:
   - Server sends Close frame with appropriate status code.
   - Server waits for Client's Close response (or times out) and closes the TCP socket.

---

## 4. Status Codes Registry (RFC 6455 §7.4)

### 4.1 Authoritative Status Code Table

| Status Code | Name | Meaning | Can Be Sent on Wire? | Trigger Condition |
|---|---|---|:---:|---|
| **1000** | Normal Closure | The purpose for which the connection was established has been fulfilled. | **YES** | Clean shutdown / user logout. |
| **1001** | Going Away | Endpoint is "going away" (server shutdown, browser page navigation). | **YES** | Server terminating, client closing tab. |
| **1002** | Protocol Error | Endpoint is terminating the connection due to a protocol error. | **YES** | Unmasked client frame, invalid opcode, RSV bits set, minimal length violation, FIN=0 on control frame. |
| **1003** | Unsupported Data | Endpoint received data type it cannot accept. | **YES** | E.g. binary frame received by text-only endpoint. |
| **1004** | Reserved | Reserved for future use. | NO | Do not use. |
| **1005** | No Status Received | Expected status code was not present in Close frame. | **NO (Wire-forbidden)** | Internal status only; must NOT be placed in Close frame. |
| **1006** | Abnormal Closure | Connection closed abnormally without receiving Close frame. | **NO (Wire-forbidden)** | Internal status only; used when TCP dropped / EOF / IOException. |
| **1007** | Invalid Frame Payload Data | Received data inconsistent with message type (invalid UTF-8). | **YES** | Malformed UTF-8 in Text frame (0x1) or Close reason. |
| **1008** | Policy Violation | Endpoint received message that violates its policy. | **YES** | Authentication failure, rate limiting, permissions. |
| **1009** | Message Too Big | Message is too big for the endpoint to process. | **YES** | Frame or reassembled message exceeds max buffer. |
| **1010** | Mandatory Extension | Client expected server to negotiate extension. | **YES** | Client-only code. |
| **1011** | Internal Server Error | Server encountered an unexpected condition that prevented request fulfillment. | **YES** | Unhandled exception in server message handler. |
| **1015** | TLS Handshake Failure | TLS handshake failure (e.g. invalid certificate). | **NO (Wire-forbidden)** | Internal status only. |

### 4.2 Status Code Ranges (§7.4.2)
- `0 - 999`: Not used / forbidden.
- `1000 - 2999`: Reserved for RFC 6455 and future IETF standards.
- `3000 - 3999`: Reserved for libraries, frameworks, and public specifications (IANA registration).
- `4000 - 4999`: Reserved for private use / application-specific status codes.

---

## 5. UTF-8 Validation Rules (RFC 6455 §8.1)

1. Text frames (`Opcode 0x1`) and the reason string in Close frames (`Opcode 0x8`) **MUST** consist of valid UTF-8.
2. If invalid UTF-8 is detected:
   - For Text frame: Server **MUST** fail connection with status code `1007` (Invalid Frame Payload Data).
   - For Close frame: If reason string is invalid UTF-8, server fails connection with status code `1007`.
3. Java standard decoding with `StandardCharsets.UTF_8`:
   - Note: `new String(bytes, StandardCharsets.UTF_8)` replaces malformed bytes with `\uFFFD` without throwing an exception!
   - To strictly enforce RFC 6455, the server MUST use `CharsetDecoder`:
     ```java
     CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
         .onMalformedInput(CodingErrorAction.REPORT)
         .onUnmappableCharacter(CodingErrorAction.REPORT);
     try {
         CharBuffer decoded = decoder.decode(ByteBuffer.wrap(payload));
         return decoded.toString();
     } catch (CharacterCodingException e) {
         // MUST close connection with status code 1007
         throw new WebSocketProtocolException(1007, "Invalid UTF-8 payload");
     }
     ```

---

## 6. Message Fragmentation and Reassembly (RFC 6455 §5.4)

A message can be fragmented into multiple frames to stream data of unknown total size or to prevent large messages from monopolizing the channel:

```
Frame 1: FIN=0, Opcode=0x1 (Text)   [First fragment]
Frame 2: FIN=0, Opcode=0x0 (Cont.)  [Middle fragment]
Frame 3: FIN=1, Opcode=0x0 (Cont.)  [Final fragment]
```

### Invariants for Fragmentation:
1. First fragment has `FIN = 0` and `Opcode != 0x0` (e.g. `0x1` Text or `0x2` Binary).
2. Subsequent fragments have `Opcode = 0x0` (Continuation).
3. The final fragment has `FIN = 1` and `Opcode = 0x0`.
4. Opcode in subsequent fragments **MUST** be `0x0`. If a non-control frame arrives with opcode != 0 before the previous message is finished, server MUST fail connection with `1002`.
5. If a continuation frame (`0x0`) arrives when no fragmented message is in progress, server MUST fail connection with `1002`.
6. Control frames (`0x8`, `0x9`, `0xA`) **CAN** appear between fragments and must be handled immediately without altering the continuation state!

---

## 7. 🎓 BEDROCK TUTORIAL: Didactic Explanations

### 7.1 Why Do We Need the HTTP 101 Handshake?
> **The Lesson**: WebSockets didn't invent a new port. They use ports 80 (HTTP) and 443 (HTTPS) so they can traverse every enterprise firewall, reverse proxy, and cloud load balancer on the internet.
> 
> The HTTP 101 ("Switching Protocols") handshake is an explicit negotiation:
> 1. The client tells the server: *"I want to stop speaking HTTP request/response and upgrade to full-duplex binary framing."*
> 2. The server verifies the client's credentials and protocol version (`Sec-WebSocket-Version: 13`).
> 3. Once the server returns `HTTP/1.1 101 Switching Protocols\r\n\r\n`, the HTTP parser is completely detached from the TCP socket. From that exact byte onward, the socket is a bidirectional raw binary stream governed by RFC 6455.

---

### 7.2 Why SHA-1 and the "Magic GUID" (`258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)?
> **The Lesson**: Why didn't the protocol designers just have the server reply with `Sec-WebSocket-Accept: true`?
> 
> **Cross-Protocol Attack Prevention**:
> Suppose a malicious webpage scripts an HTTP client to connect to an internal raw TCP service (like an SMTP mail server or an internal Redis/database server on port 6379) and sends text lines.
> 
> If a server simply echoed back the headers, a non-WebSocket server might accidentally keep the connection open.
> 
> By requiring the server to:
> 1. Take a random 16-byte nonce (`Sec-WebSocket-Key`) generated uniquely by the client for that specific connection,
> 2. Concatenate it with the exact RFC 6455 GUID `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`,
> 3. Hash it with SHA-1, and
> 4. Base64 encode the result...
> 
> The client receives mathematical proof that:
> - The server is genuinely a WebSocket server (it knows the RFC 6455 GUID).
> - The response was generated in real-time for this specific connection (preventing proxy cache poisoning).
> - The server was not tricked into an unintended cross-protocol session.

---

### 7.3 Why Must the Client Mask Frames (and Why Server Frames Are Unmasked)?
> **The Lesson**: The most mysterious rule in RFC 6455: *Why does the client have to XOR mask every frame, while the server sends unmasked frames?*
>
> **The Attack: Transparent Proxy Cache Poisoning**:
> In the early days of the web, many corporate networks routed all port 80 traffic through "transparent caching proxies" (like Squid).
> 
> If a client sent unmasked frames, a malicious script in a browser could craft a WebSocket payload that looked like:
> ```http
> GET /evil.js HTTP/1.1
> Host: bank.com
> ```
> A broken or naive transparent proxy inspecting port 80 might misinterpret this payload as a genuine HTTP GET request, fetch `evil.js`, and store it in its cache for `bank.com`, poisoning the cache for everyone in the company!
>
> **The Solution: 4-Byte Random XOR Masking**:
> When the client masks the payload with a random 4-byte key for every single frame:
> - The bytes on the wire become pseudo-random noise.
> - No predictable HTTP headers or byte sequences can ever appear on the physical wire.
> - A transparent proxy cannot recognize or cache anything.
>
> **Why Servers Don't Mask**:
> Servers only respond; clients do not cache incoming server streams as reverse-proxy cache targets, so server-to-client frames do not pose this threat. Avoiding server masking saves CPU cycles on the server.

---

### 7.4 Virtual Threads vs Reactor / Netty in WebSockets
> **The Lesson**: Traditional WebSocket servers (Netty, Undertow) use non-blocking event loops (`Selector`, event loop threads). While fast, event loops introduce complex callback chains, pipeline handlers, and backpressure state machines.
>
> In **Bedrock v2.0**, we pair standard `SocketChannel` / `InputStream` blocking reads with **JDK 21 Virtual Threads**:
> - Each connected client gets its own dedicated Virtual Thread (`Thread.ofVirtual().start(...)`).
> - The thread can perform a simple, straightforward `while (running) { readFrame(); dispatch(); }` blocking loop.
> - When waiting for data from the client, the Virtual Thread unmounts from its carrier OS thread at the JVM level with near-zero memory footprint (~few hundred bytes).
> - We get the throughput and scalability of asynchronous reactive architectures with the simplicity, debuggability, and didactic beauty of clean synchronous Java code.

---

## 8. Features Discovered Table

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | Handshake | HTTP 101 Upgrade Request | Client requests protocol switch to WebSocket | HTTP GET, `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Key`, `Sec-WebSocket-Version: 13` | HTTP 101 Switching Protocols response | 400 Bad Request if headers invalid; 426 Upgrade Required if version != 13 | RFC 6455 §4.1, §4.2 |
| 2 | Handshake | Sec-WebSocket-Accept Hash | Computes SHA-1 hash of key + GUID encoded in Base64 | Client `Sec-WebSocket-Key` string | 28-char Base64 string | Throws IllegalArgumentException if key is null/empty | RFC 6455 §1.3, §4.2.2 |
| 3 | Handshake | Version Negotiation | Validates client RFC version is 13 | `Sec-WebSocket-Version` header | Upgrade proceeds if "13" | Returns HTTP 426 with `Sec-WebSocket-Version: 13` if version != 13 | RFC 6455 §4.4 |
| 4 | Framing | Frame Bit Header Parsing | Decodes FIN, RSV1-3, and Opcode from byte 0 | 1 byte (`b0`) | `fin` (boolean), `rsv` (int), `opcode` (int) | Close code 1002 if RSV != 0 or opcode unknown | RFC 6455 §5.2 |
| 5 | Framing | Mask Bit Detection | Checks if payload is masked | Byte 1 bit 7 (`0x80`) | `masked` (boolean) | Close code 1002 if client frame is unmasked (`MASK = 0`) | RFC 6455 §5.1 |
| 6 | Framing | 7-Bit Payload Length | Decodes length values $\le 125$ | Byte 1 bits 0-6 (`0x7F`) | Integer length $0 \le L \le 125$ | None | RFC 6455 §5.2 |
| 7 | Framing | 16-Bit Extended Payload Length | Decodes length for indicator 126 | Indicator 126 + next 2 bytes big-endian | Integer length $126 \le L \le 65535$ | Close code 1002 if decoded length < 126 (minimal encoding violation) | RFC 6455 §5.2 |
| 8 | Framing | 64-Bit Extended Payload Length | Decodes length for indicator 127 | Indicator 127 + next 8 bytes big-endian | Long length $65536 \le L < 2^{63}$ | Close code 1002 if MSB != 0 or decoded length < 65536 | RFC 6455 §5.2 |
| 9 | Framing | XOR Payload Unmasking | Reverses client payload masking using 4-byte key | Masked byte array, 4-byte key | Unmasked byte array | Throws if key != 4 bytes | RFC 6455 §5.3 |
| 10| Framing | Server Frame Serialization | Encodes unmasked frame to client | `opcode`, `fin`, `byte[] payload` | Formatted `ByteBuffer` or byte stream | Throws if control frame payload > 125 | RFC 6455 §5.1, §5.2 |
| 11| Control | Ping Frame Handling | Heartbeat request from client | Opcode 0x9, payload 0..125 bytes | Pong frame (0xA) with exact same payload | Close code 1002 if FIN=0 or payload > 125 | RFC 6455 §5.5.2 |
| 12| Control | Pong Frame Handling | Heartbeat response or unsolicited Pong | Opcode 0xA, payload 0..125 bytes | Heartbeat timestamp updated or ignored | Close code 1002 if FIN=0 or payload > 125 | RFC 6455 §5.5.3 |
| 13| Control | Close Handshake Handling | Connection termination negotiation | Opcode 0x8, 0 or $\ge 2$ bytes payload | Close frame response echo + TCP socket close | Close code 1002 if payload == 1 byte | RFC 6455 §5.5.1 |
| 14| Control | Close Status Code Extraction | Reads 16-bit status code from close payload | 2-byte prefix of close body | Integer status code (e.g. 1000, 1001) | Close code 1002 if code is invalid or in reserved range | RFC 6455 §7.4 |
| 15| Control | Close Reason Extraction | Reads UTF-8 reason string from close payload | Bytes 2..N of close body | String reason | Close code 1007 if reason is not valid UTF-8 | RFC 6455 §5.5.1, §8.1 |
| 16| Data | Text Message UTF-8 Validation | Enforces strict UTF-8 on text payloads | Opcode 0x1 payload bytes | Validated Java `String` | Close code 1007 if payload contains invalid UTF-8 | RFC 6455 §8.1 |
| 17| Data | Binary Message Handling | Passes raw byte array to application | Opcode 0x2 payload bytes | `byte[]` or `ByteBuffer` | Close code 1003 if endpoint does not support binary | RFC 6455 §5.6 |
| 18| Data | Message Fragmentation Reassembly| Reassembles multi-frame messages | Frame with FIN=0, followed by Opcode 0x0 frames | Complete reassembled message payload | Close code 1002 if continuation frame arrives without active message | RFC 6455 §5.4 |
| 19| Control | Interleaved Control Frames | Handles Ping/Pong/Close during fragmented data | Control frame arriving between fragment frames | Control frame processed immediately; continuation unaffected | Close code 1002 if control frame is fragmented (FIN=0) | RFC 6455 §5.4 |

---

## 9. Edge Cases and Protocol Invariants

| # | Feature | Input / Condition | Observed / Required RFC 6455 Behavior |
|---|---|---|---|
| 1 | Handshake | `Sec-WebSocket-Version: 8` (or not 13) | Server MUST respond with HTTP `426 Upgrade Required` and header `Sec-WebSocket-Version: 13`. |
| 2 | Handshake | Missing `Upgrade: websocket` or `Connection: Upgrade` | Server MUST reject the request with HTTP `400 Bad Request`. |
| 3 | Handshake | Empty or malformed `Sec-WebSocket-Key` | Server MUST reject with HTTP `400 Bad Request`. |
| 4 | Framing | Client sends unmasked frame (`MASK = 0`) | Server MUST close connection with status code `1002` (Protocol Error). |
| 5 | Framing | Server sends frame with `MASK = 1` | FORBIDDEN: Server must never mask frames sent to client. |
| 6 | Framing | Reserved bit `RSV1`, `RSV2`, or `RSV3` set to 1 without extension | Server MUST close connection with status code `1002` (Protocol Error). |
| 7 | Framing | Unknown Opcode (e.g. `0x3` or `0xF`) | Server MUST close connection with status code `1002` (Protocol Error). |
| 8 | Framing | Non-minimal length: 19-byte payload sent with length indicator `126` | Server MUST close connection with status code `1002` (Protocol Error). |
| 9 | Framing | Non-minimal length: 500-byte payload sent with length indicator `127` | Server MUST close connection with status code `1002` (Protocol Error). |
| 10| Framing | 64-bit length has MSB set to 1 (negative long in Java) | Server MUST close connection with status code `1002` (MSB must be 0). |
| 11| Control | Control frame with `FIN = 0` (fragmented Ping, Pong, or Close) | Server MUST close connection with status code `1002` (Control frames cannot be fragmented). |
| 12| Control | Control frame with payload length > 125 bytes | Server MUST close connection with status code `1002` (Control frame max payload is 125). |
| 13| Control | Close frame with body of exactly 1 byte | Server MUST close connection with status code `1002` (Body must be 0 or $\ge 2$ bytes). |
| 14| Control | Close frame with body containing invalid UTF-8 reason string | Server MUST close connection with status code `1007` (Invalid frame payload data). |
| 15| Control | Close frame with reserved status code (e.g. 1005, 1006, 1015 on the wire) | Server MUST close connection with status code `1002` (Wire-forbidden codes received on wire). |
| 16| Control | Ping frame received while reassembling fragmented text message | Server replies with Pong immediately; fragmentation state continues unaffected. |
| 17| Data | Text frame containing invalid UTF-8 sequence (e.g. invalid continuation byte `0xFF`) | Server MUST close connection with status code `1007` (Invalid frame payload data). |
| 18| Data | Continuation frame (`Opcode 0x0`) received when no message is being fragmented | Server MUST close connection with status code `1002` (Protocol Error). |
| 19| Data | New data frame (`Opcode 0x1` or `0x2`) received while previous fragmented message is uncompleted | Server MUST close connection with status code `1002` (Previous message not finished). |
| 20| Lifecycle| Client sends frames after sending Close frame | Server ignores or drops frames after Close handshake initiated. |

---

## 10. Architectural Recommendations for Bedrock v2.0

### 10.1 Package Structure (`bedrock-core`)
```
com.bedrock.websocket
├── BedrockWebSocketServer.java       // Standalone NIO ServerSocketChannel loop on Virtual Threads
├── WebSocketHandshake.java           // RFC 6455 HTTP 101 upgrade validation & Sec-WebSocket-Accept calculation
├── WebSocketFrame.java               // Immutable or value record representing a frame (FIN, Opcode, Payload)
├── WebSocketParser.java              // Pure NIO ByteBuffer frame decoder with XOR unmasking
├── WebSocketSerializer.java          // Serializes unmasked server frames to ByteBuffers
├── BedrockWebSocketSession.java      // Thread-safe session handle (send, close, broadcast, attributes)
├── CloseStatus.java                  // RFC 6455 status code constants (1000, 1001, 1002, 1007, etc.)
├── annotation
│   ├── BedrockSocket.java            // @BedrockSocket(path = "/chat")
│   ├── OnOpen.java                   // @OnOpen
│   ├── OnMessage.java                // @OnMessage
│   ├── OnClose.java                  // @OnClose
│   └── OnError.java                  // @OnError
```

### 10.2 Server & Virtual Thread Lifecycle Flow
```java
// 1. Accept Connection Loop (BedrockWebSocketServer)
ServerSocketChannel serverChannel = ServerSocketChannel.open();
serverChannel.bind(new InetSocketAddress(port));

while (running) {
    SocketChannel clientChannel = serverChannel.accept();
    Thread.ofVirtual()
          .name("ws-client-" + clientId.incrementAndGet())
          .start(() -> handleClient(clientChannel));
}

// 2. Client Handler Loop (inside dedicated Virtual Thread)
private void handleClient(SocketChannel channel) {
    try {
        // Step A: Read HTTP request & validate RFC 6455 Handshake
        HandshakeResult handshake = WebSocketHandshake.upgrade(channel);
        if (!handshake.isSuccess()) {
            return; // 400 or 426 already sent
        }

        BedrockWebSocketSession session = new BedrockWebSocketSession(channel, handshake.endpoint());
        endpoint.invokeOnOpen(session);

        // Step B: Frame Reading Loop
        WebSocketParser parser = new WebSocketParser(channel);
        while (session.isOpen()) {
            WebSocketFrame frame = parser.nextFrame();
            if (frame == null) break; // EOF

            switch (frame.opcode()) {
                case TEXT -> {
                    String text = frame.asUtf8Text(); // Validates UTF-8 strictly -> 1007 on error
                    endpoint.invokeOnMessage(session, text);
                }
                case PING -> {
                    // RFC 6455 §5.5.2: Server MUST respond with Pong carrying same payload
                    session.sendPong(frame.payload());
                }
                case PONG -> {
                    // Heartbeat acknowledgment
                }
                case CLOSE -> {
                    // RFC 6455 §5.5.1: Two-way close handshake
                    session.handleCloseFrame(frame);
                    return;
                }
                case CONTINUATION -> {
                    // Reassemble fragments
                }
            }
        }
    } catch (Exception e) {
        endpoint.invokeOnError(session, e);
    } finally {
        session.cleanup();
        endpoint.invokeOnClose(session);
    }
}
```

### 10.3 Integration with `BedrockApp`
In `BedrockApp`:
```java
public BedrockApp enableWebSockets(int port) {
    this.wsPort = port;
    return this;
}
```
When `app.register(MyChatSocket.class)` is called:
- If `clazz.isAnnotationPresent(BedrockSocket.class)`, Bedrock registers it in the WebSocket routing table for the WebSocket server.
- When `app.start()` runs, if WebSocket endpoints are registered, the `BedrockWebSocketServer` is started on its designated port, creating seamless HTTP + WebSocket capability.
