package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: The RFC 6455 Two-Way Close Handshake & Session Eviction
 *
 * <h3>1. Protocol Physics: The Two-Way Close Handshake (RFC 6455 §5.5.1 & §7.1):</h3>
 * <p>
 * In WebSockets, neither party simply drops the TCP socket. Clean disconnection requires
 * a formal two-way close exchange using control frames with {@code Opcode 0x8 (Close)}:
 * <ol>
 *   <li><b>Initiation</b>: One peer transmits a Close frame containing a 2-byte unsigned integer
 *       status code followed by an optional UTF-8 reason phrase (e.g., {@code 1000 "Normal Closure"}).</li>
 *   <li><b>Echo Response</b>: Upon receiving the Close frame, the responding peer MUST echo back
 *       a Close frame with the appropriate status code.</li>
 *   <li><b>TCP Teardown</b>: Only after the Close frame is transmitted may the underlying
 *       TCP {@link java.nio.channels.SocketChannel} be cleanly closed.</li>
 * </ol>
 * </p>
 *
 * <h3>2. What Happens When {@code @OnClose} is Invoked:</h3>
 * <p>
 * When a close event occurs (either clean two-way handshake, client drop, or error):
 * <ul>
 *   <li>The session is unregistered from {@link com.bedrock.core.ws.server.WebSocketSessionRegistry}
 *       to prevent stale broadcasts to dead sockets.</li>
 *   <li>The session is marked closed ({@code session.isOpen() == false}).</li>
 *   <li>The method annotated with {@code @OnClose} is invoked with the status code and reason string.</li>
 *   <li>The Virtual Thread terminates its synchronous read loop and releases resources.</li>
 * </ul>
 * </p>
 *
 * <h3>3. Standard Status Codes (RFC 6455 §7.4):</h3>
 * <ul>
 *   <li>{@code 1000} - Normal Closure: Purpose for connection fulfilled.</li>
 *   <li>{@code 1001} - Going Away: Server shutdown or browser navigating away.</li>
 *   <li>{@code 1002} - Protocol Error: Client violated framing rules (e.g. unmasked frame).</li>
 *   <li>{@code 1007} - Invalid Payload Data: Non-UTF-8 bytes received in text frame.</li>
 *   <li>{@code 1009} - Message Too Big: Payload exceeded buffer threshold.</li>
 *   <li>{@code 1006} - Abnormal Closure: Reserved code used internally when connection drops abruptly.</li>
 * </ul>
 *
 * <h3>4. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, int statusCode, String reason)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, int statusCode)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session, String reason)}</li>
 *   <li>{@code public void onClose(BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onClose(int statusCode, String reason)}</li>
 *   <li>{@code public void onClose(int statusCode)}</li>
 *   <li>{@code public void onClose(String reason)}</li>
 *   <li>{@code public void onClose()}</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.protocol.WebSocketCloseStatus
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnClose {
}
