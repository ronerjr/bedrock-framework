package com.bedrock.core.ws.server;

import com.bedrock.core.BedrockLogger;
import com.bedrock.core.ws.protocol.WebSocketCloseStatus;
import com.bedrock.core.ws.protocol.WebSocketException;
import com.bedrock.core.ws.protocol.WebSocketFrame;
import com.bedrock.core.ws.protocol.WebSocketFrameParser;
import com.bedrock.core.ws.protocol.WebSocketFrameWriter;
import com.bedrock.core.ws.protocol.WebSocketHandshake;
import com.bedrock.core.ws.protocol.WebSocketOpcode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🎓 BEDROCK TUTORIAL: Carrier Unmounting Mechanics & Full-Duplex Frame Dispatching
 *
 * <p>This class manages the complete physical lifecycle of an individual WebSocket client connection:
 * from the initial HTTP 101 upgrade handshake to full-duplex RFC 6455 binary framing.</p>
 *
 * <h3>1. The Two-Phase Connection Lifecycle:</h3>
 * <ul>
 *   <li><b>Phase 1: HTTP Upgrade Handshake</b>:
 *     <p>The socket begins life as an ordinary HTTP/1.1 TCP stream. Incoming bytes are read into
 *     an NIO {@link ByteBuffer} and evaluated by {@link WebSocketHandshake}.
 *     If the request line, {@code Upgrade: websocket}, {@code Connection: Upgrade}, and
 *     {@code Sec-WebSocket-Version: 13} headers are valid, the server computes the SHA-1 Base64
 *     {@code Sec-WebSocket-Accept} token and transmits the HTTP 101 Switching Protocols response.</p>
 *     <p>Crucially, before switching to Phase 2, any remaining bytes in the buffer (pipelined
 *     frames sent by an eager client) are preserved via {@link ByteBuffer#compact()}.</p>
 *   </li>
 *   <li><b>Phase 2: Full-Duplex RFC 6455 Frame Loop</b>:
 *     <p>The HTTP parser is permanently discarded. The connection enters an infinite synchronous
 *     read loop, parsing discrete binary frames, performing in-place 4-byte XOR unmasking,
 *     and dispatching opcodes to endpoint bindings.</p>
 *   </li>
 * </ul>
 *
 * <h3>2. The Physics of Carrier Thread Unmounting:</h3>
 * <p>How can a synchronous blocking call like:
 * <pre>{@code
 *   int bytesRead = channel.read(buffer); // Blocks waiting for network bytes
 * }</pre>
 * scale to 100,000 idle WebSocket clients without consuming 100,000 OS threads?</p>
 *
 * <p>Here is what physically occurs within the Java 21 JVM runtime:
 * <ol>
 *   <li>When {@code channel.read(buffer)} is invoked inside a Virtual Thread, the JVM standard library
 *       checks if the socket has bytes immediately available in its OS TCP receive buffer.</li>
 *   <li>If no bytes are ready, the JVM does <b>NOT</b> suspend the underlying OS kernel thread.</li>
 *   <li>Instead, the JVM executes a <i>Continuation Yield</i>: the virtual thread's call stack,
 *       local variables, and instruction pointer are preserved as a compact object on the Java heap (~300 bytes).</li>
 *   <li>The virtual thread <b>unmounts</b> from its OS carrier thread. The carrier thread (an OS thread
 *       in the carrier {@link java.util.concurrent.ForkJoinPool}) is now completely free to execute other virtual threads!</li>
 *   <li>The JVM registers the socket's underlying file descriptor with an internal multiplexer
 *       (e.g., {@code epoll} on Linux, {@code kqueue} on macOS, or {@code IOCP} on Windows).</li>
 *   <li>When incoming TCP packets arrive on the physical network interface card, the OS network
 *       stack notifies the JVM multiplexer.</li>
 *   <li>The JVM wakes the continuation, selects an available carrier thread from the pool,
 *       <b>mounts</b> the virtual thread back onto the carrier, and resumes execution smoothly
 *       at the instruction following {@code channel.read()}!</li>
 * </ol>
 * To the Java developer, the code appears synchronous and simple. But to the operating system,
 * it achieves maximum event-driven, non-blocking efficiency with zero reactive framework baggage.
 * </p>
 *
 * <h3>3. RFC 6455 Control Frame Invariants:</h3>
 * <ul>
 *   <li><b>Ping (Opcode 0x9)</b>: The server MUST immediately reply with a Pong frame (Opcode 0xA)
 *       echoing the exact application data received in the Ping frame (RFC 6455 §5.5.2).</li>
 *   <li><b>Close (Opcode 0x8)</b>: Clean two-way handshake. If the client initiates closure,
 *       the server echoes the Close frame, unregisters the session from the registry, fires
 *       {@code @OnClose}, and terminates the underlying TCP channel (RFC 6455 §5.5.1 &amp; §7.1).</li>
 *   <li><b>Mandatory Client Masking (RFC 6455 §5.1)</b>: If a client transmits an unmasked frame,
 *       the server terminates the connection immediately with status code {@code 1002 (Protocol Error)}.</li>
 * </ul>
 *
 * @see BedrockWebSocketServer
 * @see BedrockWebSocketSession
 * @see WebSocketFrameParser
 * @see WebSocketFrameWriter
 */
public class WebSocketClientHandler {

    private static final AtomicLong CLIENT_COUNTER = new AtomicLong(1);
    private static final int INITIAL_BUFFER_SIZE = 8192; // 8 KB
    private static final int MAX_BUFFER_SIZE = WebSocketFrameParser.MAX_ALLOWED_PAYLOAD_SIZE; // 16 MB

    private final Map<String, WebSocketEndpointBinding> routes;
    private final WebSocketSessionRegistry sessionRegistry;

    /**
     * Constructs a new {@code WebSocketClientHandler}.
     *
     * @param routes          Thread-safe route map of registered endpoints.
     * @param sessionRegistry Session registry tracking active connections.
     */
    public WebSocketClientHandler(Map<String, WebSocketEndpointBinding> routes,
                                  WebSocketSessionRegistry sessionRegistry) {
        this.routes = routes;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Spawns a dedicated Virtual Thread to service the accepted client connection.
     *
     * @param clientChannel The newly accepted SocketChannel.
     */
    public void dispatch(SocketChannel clientChannel) {
        long clientId = CLIENT_COUNTER.getAndIncrement();
        Thread.ofVirtual()
                .name("ws-client-", clientId)
                .start(() -> handleConnection(clientChannel, clientId));
    }

    /**
     * Executes the complete two-phase connection lifecycle for a single client.
     */
    private void handleConnection(SocketChannel channel, long clientId) {
        String sessionId = UUID.randomUUID().toString();
        ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);

        try {
            // -----------------------------------------------------------------
            // Phase 1: HTTP Upgrade Handshake Ingestion
            // -----------------------------------------------------------------
            WebSocketHandshake.HandshakeParseResult handshakeResult = null;

            while (true) {
                int read = channel.read(buffer);
                if (read == -1) {
                    // Client disconnected before completing handshake
                    channel.close();
                    return;
                }

                buffer.flip();
                handshakeResult = WebSocketHandshake.parse(buffer);

                if (handshakeResult != null) {
                    // Complete HTTP header parsed. buffer.position() was advanced past header.
                    break;
                }

                // Incomplete header: restore buffer for continued reading
                buffer.compact();
                if (!buffer.hasRemaining()) {
                    if (buffer.capacity() >= WebSocketHandshake.MAX_HEADER_SIZE) {
                        writeAndClose(channel, WebSocketHandshake.createResponse400("Header too large"));
                        return;
                    }
                    int newCap = Math.min(buffer.capacity() * 2, WebSocketHandshake.MAX_HEADER_SIZE);
                    ByteBuffer larger = ByteBuffer.allocate(newCap);
                    buffer.flip();
                    larger.put(buffer);
                    buffer = larger;
                }
            }

            // -----------------------------------------------------------------
            // Validate Handshake Result & Match Route
            // -----------------------------------------------------------------
            if (handshakeResult.statusCode() == 426) {
                writeAndClose(channel, handshakeResult.responseBytes() != null
                        ? handshakeResult.responseBytes()
                        : WebSocketHandshake.createResponse426());
                return;
            }

            if (!handshakeResult.isSuccess()) {
                writeAndClose(channel, handshakeResult.responseBytes() != null
                        ? handshakeResult.responseBytes()
                        : WebSocketHandshake.createResponse400(handshakeResult.errorMessage()));
                return;
            }

            String path = BedrockWebSocketServer.normalizePath(handshakeResult.path());
            WebSocketEndpointBinding binding = routes.get(path);

            if (binding == null) {
                // Route not registered: return HTTP 404
                writeAndClose(channel, WebSocketHandshake.createResponse404(path));
                return;
            }

            // -----------------------------------------------------------------
            // Upgrade Negotiated: Send HTTP 101 Switching Protocols
            // -----------------------------------------------------------------
            writeFully(channel, ByteBuffer.wrap(handshakeResult.responseBytes()));

            // Construct and register session
            StandardWebSocketSession session = new StandardWebSocketSession(
                    sessionId, channel, path, sessionRegistry
            );
            sessionRegistry.register(session);

            // Invoke @OnOpen
            try {
                binding.invokeOnOpen(session);
            } catch (Throwable t) {
                BedrockLogger.error("WS-CLIENT", "Exception in @OnOpen for session " + sessionId + ": " + t.getMessage());
                binding.invokeOnError(session, t);
                session.close(WebSocketCloseStatus.SERVER_ERROR_CODE, "Error during connection open");
                return;
            }

            // -----------------------------------------------------------------
            // Phase 2: Full-Duplex RFC 6455 Frame Loop
            // -----------------------------------------------------------------
            // Compact any remaining unparsed bytes (e.g. pipelined frames) to buffer start
            buffer.compact();
            runFrameLoop(channel, buffer, session, binding);

        } catch (IOException e) {
            // Early I/O failure before or during upgrade
            try {
                channel.close();
            } catch (IOException ignore) {
            }
        } finally {
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException ignore) {
            }
        }
    }

    /**
     * Executes the synchronous, blocking frame read loop on this connection's Virtual Thread.
     */
    private void runFrameLoop(SocketChannel channel,
                              ByteBuffer buffer,
                              StandardWebSocketSession session,
                              WebSocketEndpointBinding binding) {
        ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();
        WebSocketOpcode[] currentFragmentOpcode = new WebSocketOpcode[1];

        while (session.isOpen() && channel.isOpen()) {
            // 1. Check if buffer already contains complete frame(s)
            buffer.flip();
            WebSocketFrame frame = null;

            try {
                frame = WebSocketFrameParser.parse(buffer);
            } catch (WebSocketException protocolEx) {
                handleProtocolException(channel, session, binding, protocolEx);
                return;
            }

            if (frame != null) {
                // A complete frame was parsed
                buffer.compact();

                boolean shouldContinue = dispatchFrame(channel, session, binding, frame, fragmentBuffer, currentFragmentOpcode);
                if (!shouldContinue) {
                    return;
                }
                // Continue immediately to check for further pipelined frames in buffer
                continue;
            }

            // 2. Buffer does not contain a full frame. Compact and read from channel.
            buffer.compact();

            // Resize buffer if full but still unable to parse a complete frame
            if (!buffer.hasRemaining()) {
                if (buffer.capacity() >= MAX_BUFFER_SIZE) {
                    handleProtocolException(channel, session, binding,
                            new WebSocketException(WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE, "Frame exceeds max payload size"));
                    return;
                }
                int newCap = Math.min(buffer.capacity() * 2, MAX_BUFFER_SIZE);
                ByteBuffer larger = ByteBuffer.allocate(newCap);
                buffer.flip();
                larger.put(buffer);
                buffer = larger;
            }

            // 3. Blocking read (Virtual Thread unmounts here until bytes arrive)
            int bytesRead;
            try {
                bytesRead = channel.read(buffer);
            } catch (IOException e) {
                handleAbnormalClosure(session, binding, e);
                return;
            }

            if (bytesRead == -1) {
                // TCP EOF: Client dropped connection without sending a Close frame
                handleAbnormalClosure(session, binding, null);
                return;
            }
        }
    }

    /**
     * Dispatches a decoded RFC 6455 frame to the endpoint binding or handles control frames inline.
     *
     * @return True to continue the frame loop; false to terminate the connection.
     */
    private boolean dispatchFrame(SocketChannel channel,
                                  StandardWebSocketSession session,
                                  WebSocketEndpointBinding binding,
                                  WebSocketFrame frame,
                                  ByteArrayOutputStream fragmentBuffer,
                                  WebSocketOpcode[] currentFragmentOpcode) {
        WebSocketOpcode opcode = frame.getOpcode();

        switch (opcode) {
            case TEXT -> {
                if (frame.isFin()) {
                    if (currentFragmentOpcode[0] != null) {
                        handleProtocolException(channel, session, binding,
                                new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, "Received new TEXT frame while fragmented message is in progress"));
                        return false;
                    }
                    String message = frame.getPayloadAsText();
                    try {
                        binding.invokeOnMessage(session, message);
                    } catch (Throwable t) {
                        BedrockLogger.error("WS-CLIENT", "Error in @OnMessage for session " + session.getId() + ": " + t.getMessage());
                        binding.invokeOnError(session, t);
                    }
                    return true;
                } else {
                    if (currentFragmentOpcode[0] != null) {
                        handleProtocolException(channel, session, binding,
                                new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, "Received initial fragment while fragmented message is already in progress"));
                        return false;
                    }
                    currentFragmentOpcode[0] = WebSocketOpcode.TEXT;
                    byte[] p = frame.getPayload();
                    fragmentBuffer.write(p, 0, p.length);
                    return true;
                }
            }

            case CONTINUATION -> {
                if (currentFragmentOpcode[0] == null) {
                    handleProtocolException(channel, session, binding,
                            new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, "Received CONTINUATION frame without prior initial fragment"));
                    return false;
                }
                byte[] p = frame.getPayload();
                fragmentBuffer.write(p, 0, p.length);
                if (fragmentBuffer.size() > MAX_BUFFER_SIZE) {
                    handleProtocolException(channel, session, binding,
                            new WebSocketException(WebSocketCloseStatus.MESSAGE_TOO_BIG_CODE, "Fragmented message exceeds maximum allowed payload size"));
                    return false;
                }

                if (frame.isFin()) {
                    WebSocketOpcode orig = currentFragmentOpcode[0];
                    currentFragmentOpcode[0] = null;
                    byte[] reassembled = fragmentBuffer.toByteArray();
                    fragmentBuffer.reset();

                    if (orig == WebSocketOpcode.TEXT) {
                        try {
                            String message = StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(reassembled))
                                    .toString();
                            try {
                                binding.invokeOnMessage(session, message);
                            } catch (Throwable t) {
                                BedrockLogger.error("WS-CLIENT", "Error in @OnMessage for session " + session.getId() + ": " + t.getMessage());
                                binding.invokeOnError(session, t);
                            }
                        } catch (java.nio.charset.CharacterCodingException e) {
                            handleProtocolException(channel, session, binding,
                                    new WebSocketException(WebSocketCloseStatus.INVALID_DATA_CODE, "Fragmented TEXT frame contains invalid UTF-8"));
                            return false;
                        }
                    }
                }
                return true;
            }

            case PING -> {
                // RFC 6455 §5.5.2: Immediately reply with Pong echoing the exact application data
                byte[] pingPayload = frame.getPayload();
                byte[] pongFrame = WebSocketFrameWriter.createPongFrame(pingPayload);
                try {
                    session.writeRaw(ByteBuffer.wrap(pongFrame));
                } catch (IOException e) {
                    BedrockLogger.warn("WS-CLIENT", "Failed to send Pong reply: " + e.getMessage());
                }
                return true;
            }

            case PONG -> {
                // RFC 6455 §5.5.3: Keepalive heartbeat response; no reply required
                BedrockLogger.info("WS-CLIENT", "Received Pong keepalive from session " + session.getId());
                return true;
            }

            case CLOSE -> {
                // RFC 6455 §5.5.1 & §7.1: Clean two-way close handshake
                int code = frame.getCloseStatusCode();
                String reason = frame.getCloseReason();

                int echoCode = (code == WebSocketCloseStatus.NO_STATUS_CODE || code <= 0)
                        ? WebSocketCloseStatus.NORMAL_CLOSURE_CODE
                        : code;

                try {
                    byte[] echoBytes = WebSocketFrameWriter.createCloseFrame(echoCode, reason != null ? reason : "");
                    session.writeRaw(ByteBuffer.wrap(echoBytes));
                } catch (Exception ignore) {
                }

                sessionRegistry.unregister(session.getId());
                session.markClosed();

                try {
                    binding.invokeOnClose(session, code > 0 ? code : WebSocketCloseStatus.NORMAL_CLOSURE_CODE, reason != null ? reason : "");
                } catch (Throwable t) {
                    BedrockLogger.warn("WS-CLIENT", "Error in @OnClose: " + t.getMessage());
                }

                try {
                    channel.close();
                } catch (IOException ignore) {
                }

                return false; // Terminate frame loop
            }

            case BINARY -> {
                if (currentFragmentOpcode[0] != null) {
                    handleProtocolException(channel, session, binding,
                            new WebSocketException(WebSocketCloseStatus.PROTOCOL_ERROR_CODE, "Received BINARY frame while fragmented message is in progress"));
                    return false;
                }
                BedrockLogger.warn("WS-CLIENT", "Binary frame received; endpoint text-only.");
                session.close(WebSocketCloseStatus.UNSUPPORTED_DATA_CODE, "Binary frames not supported");
                return false;
            }
        }
        return true;
    }

    /**
     * Handles RFC 6455 protocol violations (e.g. unmasked client frames, bad UTF-8).
     */
    private void handleProtocolException(SocketChannel channel,
                                        StandardWebSocketSession session,
                                        WebSocketEndpointBinding binding,
                                        WebSocketException ex) {
        BedrockLogger.warn("WS-CLIENT", "Protocol error on session " + session.getId() + ": " + ex.getMessage());

        // 1. Send Close frame with error status code (e.g. 1002, 1007, 1009)
        try {
            byte[] closeBytes = WebSocketFrameWriter.createCloseFrame(ex.getStatusCode(), ex.getReason());
            session.writeRaw(ByteBuffer.wrap(closeBytes));
        } catch (Exception ignore) {
        }

        // 2. Invoke @OnError
        try {
            binding.invokeOnError(session, ex);
        } catch (Throwable t) {
            BedrockLogger.warn("WS-CLIENT", "Error invoking @OnError: " + t.getMessage());
        }

        // 3. Unregister & mark closed
        sessionRegistry.unregister(session.getId());
        session.markClosed();

        // 4. Invoke @OnClose
        try {
            binding.invokeOnClose(session, ex.getStatusCode(), ex.getReason());
        } catch (Throwable t) {
            BedrockLogger.warn("WS-CLIENT", "Error invoking @OnClose: " + t.getMessage());
        }

        // 5. Close underlying channel
        try {
            channel.close();
        } catch (IOException ignore) {
        }
    }

    /**
     * Handles unexpected TCP connection drop or socket error.
     */
    private void handleAbnormalClosure(StandardWebSocketSession session,
                                      WebSocketEndpointBinding binding,
                                      Exception ex) {
        BedrockLogger.info("WS-CLIENT", "Abnormal TCP closure on session " + session.getId());

        sessionRegistry.unregister(session.getId());
        session.markClosed();

        if (ex != null) {
            try {
                binding.invokeOnError(session, ex);
            } catch (Throwable ignore) {
            }
        }

        try {
            binding.invokeOnClose(session, WebSocketCloseStatus.ABNORMAL_CLOSURE_CODE, "Abnormal TCP disconnect");
        } catch (Throwable ignore) {
        }

        try {
            session.close();
        } catch (Exception ignore) {
        }
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void writeAndClose(SocketChannel channel, byte[] bytes) {
        try {
            writeFully(channel, ByteBuffer.wrap(bytes));
        } catch (IOException ignore) {
        } finally {
            try {
                channel.close();
            } catch (IOException ignore) {
            }
        }
    }
}
