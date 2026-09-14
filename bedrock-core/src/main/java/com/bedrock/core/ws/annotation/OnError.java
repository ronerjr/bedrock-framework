package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Error Isolation & Protocol Exception Handling
 *
 * <h3>1. The Two Categories of WebSocket Errors:</h3>
 * <p>
 * During full-duplex communication, errors fall into two distinct domains:
 * <ol>
 *   <li><b>RFC 6455 Protocol Violations</b>: Handled by the framework parser
 *       ({@link com.bedrock.core.ws.protocol.WebSocketException}). Examples include:
 *       <ul>
 *         <li>Unmasked client frames (violating RFC 6455 §5.1, status 1002).</li>
 *         <li>Malformed UTF-8 bytes in text frames (violating RFC 6455 §8.1, status 1007).</li>
 *         <li>Reserved bits set without extension negotiation (RSV1-3 != 0, status 1002).</li>
 *         <li>Control frame payloads exceeding 125 bytes (status 1002).</li>
 *       </ul>
 *   </li>
 *   <li><b>Application Business Exceptions</b>: Uncaught exceptions thrown inside developer code
 *       (e.g., inside an {@link OnMessage} or {@link OnOpen} handler).</li>
 * </ol>
 * </p>
 *
 * <h3>2. The Error Dispatch Guarantee:</h3>
 * <p>
 * In both cases, Bedrock routes the failure directly to the method annotated with {@code @OnError}.
 * This guarantees:
 * <ul>
 *   <li>Exceptions never crash the server accept loop.</li>
 *   <li>Developers have an isolated hook for logging, metrics, alerting, or audit records.</li>
 *   <li>In protocol errors, Bedrock transmits the appropriate Close frame and terminates the socket.</li>
 * </ul>
 * </p>
 *
 * <h3>3. Supported Method Signatures:</h3>
 * <ul>
 *   <li>{@code public void onError(BedrockWebSocketSession session, Throwable throwable)}</li>
 *   <li>{@code public void onError(Throwable throwable, BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onError(Throwable throwable)}</li>
 *   <li>{@code public void onError(BedrockWebSocketSession session)}</li>
 *   <li>{@code public void onError()}</li>
 * </ul>
 *
 * @see BedrockSocket
 * @see com.bedrock.core.ws.protocol.WebSocketException
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnError {
}
