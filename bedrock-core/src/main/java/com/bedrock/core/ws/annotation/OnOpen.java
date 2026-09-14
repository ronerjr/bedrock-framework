package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: The HTTP 101 Switching Protocols Handshake & Connection Promotion
 *
 * <h3>1. Protocol Physics (RFC 6455 §4):</h3>
 * <p>
 * A WebSocket connection does not start as a WebSocket. It begins life as a standard HTTP/1.1
 * {@code GET} request containing upgrade negotiation headers:
 * <pre>{@code
 *   GET /chat HTTP/1.1
 *   Host: server.example.com
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 *   Sec-WebSocket-Version: 13
 * }</pre>
 * When the server validates these headers, it computes the SHA-1 Base64 accept token
 * using the RFC 6455 magic GUID ({@code 258EAFA5-E914-47DA-95CA-C5AB0DC85B11}) and transmits:
 * <pre>{@code
 *   HTTP/1.1 101 Switching Protocols
 *   Upgrade: websocket
 *   Connection: Upgrade
 *   Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
 * }</pre>
 * At the exact millisecond the final {@code \r\n\r\n} bytes of this response leave the TCP output buffer,
 * the connection is physically "promoted". HTTP is permanently disabled, and the connection
 * becomes a raw, bi-directional, full-duplex byte channel.
 * </p>
 *
 * <h3>2. What Physically Triggers {@code @OnOpen}:</h3>
 * <p>
 * Immediately after sending the HTTP 101 response, Bedrock:
 * <ol>
 *   <li>Instantiates a {@link com.bedrock.core.ws.server.BedrockWebSocketSession} wrapping
 *       the underlying {@link java.nio.channels.SocketChannel}.</li>
 *   <li>Registers the session in the {@link com.bedrock.core.ws.server.WebSocketSessionRegistry}.</li>
 *   <li>Invokes the method annotated with {@code @OnOpen} on the endpoint instance.</li>
 * </ol>
 * All of this executes synchronously on the connection's dedicated Virtual Thread.
 * </p>
 *
 * <h3>3. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onOpen(BedrockWebSocketSession session)} — Recommended: provides session handle.</li>
 *   <li>{@code public void onOpen()} — Permitted when session handle is not needed.</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.server.BedrockWebSocketSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnOpen {
}
