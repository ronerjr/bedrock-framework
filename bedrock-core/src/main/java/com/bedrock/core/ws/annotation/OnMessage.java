package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: RFC 6455 Frame Ingestion, 4-Byte Masking & Virtual Thread Dispatch
 *
 * <h3>1. Protocol Physics: The Anatomy of a WebSocket Frame (RFC 6455 §5.2):</h3>
 * <p>
 * Unlike HTTP where data flows as request/response text streams, WebSockets exchange discrete
 * binary frames. When a client sends a text message, the wire bytes look like this:
 * <pre>{@code
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-------+-+-------------+-------------------------------+
 *  |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 *  |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 *  |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 *  | |1|2|3|       |K|             |                               |
 *  +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 *  |     Masking-key (4 octets, client-to-server frames only)      |
 *  +---------------------------------------------------------------+
 *  |       Payload Data (unmasked in-place via XOR 4-byte key)     |
 *  +---------------------------------------------------------------+
 * }</pre>
 * </p>
 *
 * <h3>2. Why Client Masking is Mandatory (RFC 6455 §5.1):</h3>
 * <p>
 * Notice the {@code MASK} bit. In RFC 6455, <b>every frame sent from a client to a server MUST
 * be masked</b> with a randomized 4-byte key. This prevents "cache poisoning" attacks against
 * intermediary proxies that might mistake WebSocket frames for HTTP requests.
 * </p>
 * <p>
 * Bedrock strictly enforces this:
 * <ul>
 *   <li>If a client frame arrives with {@code MASK=0}, Bedrock terminates the connection with
 *       close code {@code 1002 (Protocol Error)}.</li>
 *   <li>If {@code MASK=1}, Bedrock executes the RFC 6455 unmasking equation in-place:
 *       <pre>{@code
 *   D[i] = E[i] ^ M[i % 4]
 *       }</pre>
 *       where {@code D} is the unmasked byte, {@code E} is the encoded byte, and {@code M} is the 4-byte key.</li>
 * </ul>
 * </p>
 *
 * <h3>3. Dispatching to {@code @OnMessage}:</h3>
 * <p>
 * Once the payload is unmasked and validated as valid UTF-8, Bedrock reflects the decoded string
 * into the {@code @OnMessage} method. Execution occurs directly on the Virtual Thread dedicated
 * to this socket, preserving stack traces and context without thread switching.
 * </p>
 *
 * <h3>4. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onMessage(BedrockWebSocketSession session, String message)}</li>
 *   <li>{@code public void onMessage(String message, BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onMessage(String message)}</li>
 *   <li>{@code public void onMessage(BedrockWebSocketSession session)}</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.server.BedrockWebSocketSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnMessage {
}
