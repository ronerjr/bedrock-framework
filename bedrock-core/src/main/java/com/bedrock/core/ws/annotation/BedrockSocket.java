package com.bedrock.core.ws.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🎓 BEDROCK TUTORIAL: Declarative WebSocket Route Mapping & Anti-Magic Architecture
 *
 * <h3>1. The Problem with Enterprise "Annotation Magic":</h3>
 * <p>
 * In conventional enterprise frameworks (such as Spring Boot's {@code @ServerEndpoint} or
 * {@code @EnableWebSocket}), developers place an annotation on a class and everything
 * "automagically" works. However, this magic comes at a heavy engineering price:
 * <ul>
 *   <li><b>Opaque Classpath Scanning</b>: Frameworks scan every byte on the classpath using
 *       ASM or byte-code readers during startup, drastically slowing boot times and obscuring
 *       which classes are actually active.</li>
 *   <li><b>Dynamic Bytecode Proxies</b>: To intercept invocations, frameworks generate synthetic
 *       subclasses at runtime using CGLIB or ByteBuddy. When an exception occurs, the stack trace
 *       is polluted with 40+ proxy trampoline frames:
 *       <pre>{@code
 *   at com.example.ChatSocket$$EnhancerBySpringCGLIB$$7f8a9b.onMessage(<generated>)
 *   at org.springframework.web.socket.handler.AbstractWebSocketHandler.handleMessage(...)
 *   at org.springframework.web.socket.adapter.standard.StandardWebSocketHandlerAdapter...
 *       }</pre></li>
 *   <li><b>Loss of Protocol Reality</b>: Developers forget that WebSockets are not magic;
 *       they are persistent, full-duplex TCP connections initiated by an HTTP/1.1 101 upgrade.</li>
 * </ul>
 * </p>
 *
 * <h3>2. The Bedrock Philosophy: Explicit, Transparent & Zero Black Boxes:</h3>
 * <p>
 * In Bedrock, {@code @BedrockSocket} is an explicit semantic contract declaring that a class handles
 * real-time RFC 6455 WebSocket connections on a designated URI path:
 * <ol>
 *   <li><b>Explicit Registration</b>: Sockets are never auto-discovered. The developer registers
 *       them explicitly via {@code app.register(ChatSocket.class)}. No slow background classpath scanning occurs.</li>
 *   <li><b>IoC Dependency Injection</b>: The class is instantiated by {@code BedrockContainer}.
 *       Dependencies declared in its constructor (services, repositories, database clients)
 *       are resolved and injected cleanly before socket binding.</li>
 *   <li><b>Reflective Method Binding (Inspect Once, Invoke Directly)</b>: Bedrock inspects
 *       the methods of the class during registration, caches parameter positions for
 *       {@link OnOpen}, {@link OnMessage}, {@link OnClose}, and {@link OnError}, and wraps
 *       them in a {@code WebSocketEndpointBinding}.</li>
 *   <li><b>Direct Invocation</b>: At runtime, when an RFC 6455 frame arrives, Bedrock invokes
 *       the method directly using {@link java.lang.reflect.Method#invoke(Object, Object...)}.
 *       There are zero dynamic proxy classes, zero bytecode generation, and call stacks remain
 *       crystal clear.</li>
 * </ol>
 * </p>
 *
 * <h3>3. Usage Example:</h3>
 * <pre>{@code
 *   @BedrockSocket("/chat")
 *   public class ChatSocket {
 *       private final ChatService chatService;
 *
 *       // Constructor injection supported natively by BedrockContainer
 *       public ChatSocket(ChatService chatService) {
 *           this.chatService = chatService;
 *       }
 *
 *       @OnOpen
 *       public void onOpen(BedrockWebSocketSession session) {
 *           session.send(chatService.getWelcomeMessage());
 *       }
 *
 *       @OnMessage
 *       public void onMessage(BedrockWebSocketSession session, String message) {
 *           chatService.broadcast("[" + session.getId() + "]: " + message);
 *       }
 *
 *       @OnClose
 *       public void onClose(BedrockWebSocketSession session, int code, String reason) {
 *           chatService.remove(session.getId());
 *       }
 *
 *       @OnError
 *       public void onError(BedrockWebSocketSession session, Throwable error) {
 *           System.err.println("Session " + session.getId() + " error: " + error.getMessage());
 *       }
 *   }
 *
 *   // In Application startup:
 *   BedrockApp app = BedrockApp.create(8080)
 *       .enableWebSockets(8080)
 *       .register(ChatService.class, ChatSocket.class);
 *   app.start();
 * }</pre>
 *
 * @see OnOpen
 * @see OnMessage
 * @see OnClose
 * @see OnError
 * @see com.bedrock.core.BedrockApp#enableWebSockets(int)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BedrockSocket {

    /**
     * The WebSocket route path (e.g., "/chat", "/telemetry", or "/").
     * Alias for {@link #path()}.
     *
     * @return The URI route path.
     */
    String value() default "";

    /**
     * The WebSocket route path (e.g., "/chat", "/telemetry", or "/").
     * Alias for {@link #value()}.
     *
     * @return The URI route path.
     */
    String path() default "";
}
