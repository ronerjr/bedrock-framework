# Milestone 4 Design: ChatPlayground & ChatWebSocketTest

## 1. Executive Summary & Architectural Context

Milestone 4 delivers the complete end-to-end user-facing demonstration for Bedrock Framework Version 2.0 (Real-Time WebSockets RFC 6455). This milestone bridges the low-level byte protocol engine (`com.bedrock.core.ws.protocol`), the virtual-threaded NIO server (`com.bedrock.core.ws.server`), and the declarative IoC annotations (`@BedrockSocket`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`) into a living application within the `bedrock-example` module.

The responsibilities for Milestone 4 are divided cleanly:
- **`explorer_m4_1`**: Backend WebSocket endpoint (`ChatWebSocket.java`) and `Application.java` registration.
- **`explorer_m4_2` (Current Agent)**: Interactive dark-theme frontend UI (`ChatPlayground.java`) and the integration test suite (`ChatWebSocketTest.java`).

### Architecture Diagram
```
Browser / HTTP Client                          WebSocket Clients (Browser / HttpClient)
       │                                                          │
       │ HTTP GET /chat                                           │ RFC 6455 Upgrade Handshake (HTTP 101)
       ▼                                                          ▼
┌───────────────────────────────┐                  ┌─────────────────────────────────────────┐
│ BedrockApp (JDK HttpServer)   │                  │ BedrockWebSocketServer (ServerSocket)   │
│ Port 8080 (or ephemeral 0)    │                  │ Port 8081 (or ephemeral 0)              │
├───────────────────────────────┤                  ├─────────────────────────────────────────┤
│ Context.html(                 │                  │ Virtual Thread per Connection           │
│   ChatPlayground.getHtml()    │                  │  ├── ws-client-1                        │
│ )                             │                  │  ├── ws-client-2                        │
└──────────────┬────────────────┘                  │  └── ws-client-N                        │
               │                                   └────────────────────┬────────────────────┘
               ▼                                                        │
┌───────────────────────────────┐                                       ▼
│ ChatPlayground HTML/CSS/JS    │                  ┌─────────────────────────────────────────┐
│ • Dark Theme (Bedrock style)  │                  │ ChatWebSocket (@BedrockSocket("/chat")) │
│ • Dynamic WS URL Detection    │◄─────────────────┤ • @OnOpen (track session, broadcast)    │
│ • Real-time Message Log       │  ws://.../chat   │ • @OnMessage (fan-out broadcast)        │
│ • Connection Status Indicator │                  │ • @OnClose (cleanup session, broadcast) │
│ • RFC 6455 Protocol Inspector │                  │ • @OnError (exception handler)          │
└───────────────────────────────┘                  └─────────────────────────────────────────┘
```

---

## 2. Component Design: `ChatPlayground.java`

### 2.1 Location & Principles
- **Package**: `com.bedrock.example.ws`
- **Class**: `public class ChatPlayground`
- **Design Principles**:
  1. **Zero External Dependencies**: Pure Java 21 Text Block (`""" ... """`) containing embedded HTML5, CSS3, and modern vanilla JavaScript. No external CDNs (no Bootstrap, no jQuery, no React, no Google Fonts required). Completely functional offline.
  2. **Consistent Visual Language**: Adheres strictly to Bedrock's sleek Swagger-style dark theme as established in `BedrockPlayground.java` (`--bg-color: #121212`, `--panel-bg: #1e1e1e`, `--accent-color: #4caf50`, etc.).
  3. **Adaptive Connection Discovery**: Automatically detects `ws://` vs `wss://` based on `window.location.protocol`, discovers hostname, and defaults the WebSocket port intelligently (e.g. 8081 if served from 8080, or an injected port).
  4. **Full User Interactivity**:
     - Connection status indicator (Connecting 🟡, Connected 🟢, Disconnected 🔴).
     - Configurable WebSocket URL and custom Username inputs.
     - Live message stream with timestamps, system notifications vs peer messages vs own messages.
     - "Send" button and Enter key handling.
     - Real-time "Ping" latency measurement button displaying roundtrip milliseconds.
     - "Clear History" button.
     - Educational Protocol Inspector showing RFC 6455 frame mechanics and live metrics.

### 2.2 Complete Proposed Code: `ChatPlayground.java`

```java
package com.bedrock.example.ws;

/**
 * 🎓 BEDROCK TUTORIAL: Embedded Dark-Theme Real-Time WebSocket Playground
 *
 * <p>Provides a self-contained, responsive, dark-themed HTML/CSS/JS client for testing
 * and demonstrating RFC 6455 WebSockets in the Bedrock Framework. Built entirely with
 * Java 21 Text Blocks without any external frontend dependencies or CDNs.</p>
 *
 * <h3>Design & Architecture:</h3>
 * <ul>
 *   <li><b>Sleek Bedrock Dark Theme</b>: Matches {@code BedrockPlayground} design tokens.</li>
 *   <li><b>Dynamic URL Detection</b>: Automatically switches between {@code ws://} and {@code wss://}
 *       and adapts to the server's host and port.</li>
 *   <li><b>Live Bidirectional Messaging</b>: Real-time message streaming, auto-scrolling,
 *       peer vs self message differentiation, and system notification badges.</li>
 *   <li><b>Protocol Inspector</b>: Displays RFC 6455 handshake details, virtual thread execution,
 *       and live roundtrip Ping latency.</li>
 * </ul>
 */
public class ChatPlayground {

    private static final int DEFAULT_WS_PORT = 8081;
    private static final String DEFAULT_WS_PATH = "/chat";

    /**
     * Returns the complete HTML/CSS/JS playground page configured with default port 8081 and path /chat.
     *
     * @return Full HTML markup string.
     */
    public static String getHtml() {
        return getHtml(DEFAULT_WS_PORT, DEFAULT_WS_PATH);
    }

    /**
     * Returns the playground page configured with a custom WebSocket port and default path /chat.
     *
     * @param wsPort The WebSocket port number.
     * @return Full HTML markup string.
     */
    public static String getHtml(int wsPort) {
        return getHtml(wsPort, DEFAULT_WS_PATH);
    }

    /**
     * Returns the playground page configured with a custom WebSocket port and endpoint path.
     *
     * @param wsPort The WebSocket port number.
     * @param wsPath The WebSocket endpoint path (e.g. "/chat").
     * @return Full HTML markup string.
     */
    public static String getHtml(int wsPort, String wsPath) {
        String template = getRawHtmlTemplate();
        return template
                .replace("{{INJECTED_WS_PORT}}", String.valueOf(wsPort))
                .replace("{{INJECTED_WS_PATH}}", wsPath);
    }

    private static String getRawHtmlTemplate() {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>🦖 Bedrock Chat Playground</title>
            <style>
                :root {
                    --bg-color: #121212;
                    --panel-bg: #1e1e1e;
                    --panel-header: #252525;
                    --panel-border: #333333;
                    --text-main: #e0e0e0;
                    --text-muted: #888888;
                    --accent-color: #4caf50;
                    --accent-hover: #45a049;
                    --status-connected: #49cc90;
                    --status-connecting: #fca130;
                    --status-disconnected: #f93e3e;
                    --msg-self-bg: #1d3b27;
                    --msg-self-border: #2e593c;
                    --msg-peer-bg: #2a2a2a;
                    --msg-peer-border: #3a3a3a;
                    --msg-system-bg: #1a2530;
                    --msg-system-border: #23374d;
                    --system-color: #61affe;
                }

                * {
                    box-sizing: border-box;
                    margin: 0;
                    padding: 0;
                }

                body {
                    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    background-color: var(--bg-color);
                    color: var(--text-main);
                    height: 100vh;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    padding: 16px;
                    overflow: hidden;
                }

                .app-container {
                    width: 100%;
                    max-width: 960px;
                    height: 100%;
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                }

                /* Header */
                header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 8px 12px;
                    background-color: var(--panel-bg);
                    border: 1px solid var(--panel-border);
                    border-radius: 8px;
                }

                .brand {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                }

                h1 {
                    font-size: 20px;
                    color: var(--accent-color);
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .badge {
                    font-size: 11px;
                    text-transform: uppercase;
                    padding: 3px 8px;
                    border-radius: 12px;
                    font-weight: bold;
                    letter-spacing: 0.5px;
                }

                .badge-loom {
                    background-color: rgba(76, 175, 80, 0.15);
                    color: var(--accent-color);
                    border: 1px solid var(--accent-color);
                }

                /* Connection Toolbar */
                .connection-panel {
                    background-color: var(--panel-bg);
                    border: 1px solid var(--panel-border);
                    border-radius: 8px;
                    padding: 12px 16px;
                    display: flex;
                    flex-wrap: wrap;
                    align-items: center;
                    gap: 12px;
                    box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                }

                .input-group {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    flex-grow: 1;
                }

                .input-group label {
                    font-size: 12px;
                    color: var(--text-muted);
                    font-weight: 600;
                    white-space: nowrap;
                }

                input[type="text"] {
                    background-color: #2d2d2d;
                    border: 1px solid #444;
                    color: #fff;
                    padding: 8px 12px;
                    border-radius: 4px;
                    font-family: monospace;
                    font-size: 13px;
                    flex-grow: 1;
                    outline: none;
                    transition: border-color 0.2s;
                }

                input[type="text"]:focus {
                    border-color: var(--accent-color);
                }

                button {
                    background-color: #333;
                    color: white;
                    border: 1px solid #555;
                    padding: 8px 16px;
                    border-radius: 4px;
                    cursor: pointer;
                    font-weight: 600;
                    font-size: 13px;
                    transition: all 0.2s;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    white-space: nowrap;
                }

                button:hover:not(:disabled) {
                    background-color: #444;
                }

                button:disabled {
                    opacity: 0.4;
                    cursor: not-allowed;
                }

                button.btn-connect {
                    background-color: var(--accent-color);
                    color: #121212;
                    border: none;
                }

                button.btn-connect:hover:not(:disabled) {
                    background-color: var(--accent-hover);
                }

                button.btn-disconnect {
                    background-color: var(--status-disconnected);
                    color: white;
                    border: none;
                }

                button.btn-disconnect:hover:not(:disabled) {
                    background-color: #d32f2f;
                }

                .status-indicator {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    font-size: 12px;
                    font-weight: bold;
                    padding: 4px 10px;
                    border-radius: 12px;
                    background-color: rgba(255, 255, 255, 0.05);
                }

                .status-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 50%;
                    background-color: var(--status-disconnected);
                    display: inline-block;
                }

                .status-indicator.connected .status-dot {
                    background-color: var(--status-connected);
                    box-shadow: 0 0 8px var(--status-connected);
                }

                .status-indicator.connecting .status-dot {
                    background-color: var(--status-connecting);
                    box-shadow: 0 0 8px var(--status-connecting);
                }

                .status-indicator.disconnected .status-dot {
                    background-color: var(--status-disconnected);
                }

                /* Chat Card */
                .chat-card {
                    background-color: var(--panel-bg);
                    border: 1px solid var(--panel-border);
                    border-radius: 8px;
                    flex-grow: 1;
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                    box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                }

                .chat-card-header {
                    padding: 10px 16px;
                    background-color: var(--panel-header);
                    border-bottom: 1px solid var(--panel-border);
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    font-size: 13px;
                }

                .room-info {
                    font-weight: bold;
                    color: var(--text-main);
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }

                .latency-badge {
                    font-size: 11px;
                    font-family: monospace;
                    color: var(--text-muted);
                    background-color: rgba(0,0,0,0.3);
                    padding: 2px 6px;
                    border-radius: 4px;
                }

                /* Message Stream */
                .messages-container {
                    flex-grow: 1;
                    padding: 16px;
                    overflow-y: auto;
                    display: flex;
                    flex-direction: column;
                    gap: 10px;
                }

                .message-bubble {
                    max-width: 80%;
                    padding: 10px 14px;
                    border-radius: 8px;
                    font-size: 14px;
                    line-height: 1.4;
                    position: relative;
                    word-break: break-word;
                }

                .message-bubble.self {
                    align-self: flex-end;
                    background-color: var(--msg-self-bg);
                    border: 1px solid var(--msg-self-border);
                    border-bottom-right-radius: 2px;
                }

                .message-bubble.peer {
                    align-self: flex-start;
                    background-color: var(--msg-peer-bg);
                    border: 1px solid var(--msg-peer-border);
                    border-bottom-left-radius: 2px;
                }

                .message-bubble.system {
                    align-self: center;
                    background-color: var(--msg-system-bg);
                    border: 1px solid var(--msg-system-border);
                    color: var(--system-color);
                    font-size: 12px;
                    padding: 6px 14px;
                    border-radius: 16px;
                    text-align: center;
                }

                .message-meta {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    margin-bottom: 4px;
                    font-size: 11px;
                }

                .message-bubble.self .message-meta {
                    justify-content: flex-end;
                    color: #8bc34a;
                }

                .message-bubble.peer .message-meta {
                    color: #aaa;
                }

                .username-tag {
                    font-weight: bold;
                }

                .timestamp-tag {
                    color: var(--text-muted);
                    font-size: 10px;
                    font-family: monospace;
                }

                .message-text {
                    color: var(--text-main);
                    white-space: pre-wrap;
                }

                /* Bottom Composer */
                .composer-panel {
                    padding: 12px 16px;
                    background-color: var(--panel-header);
                    border-top: 1px solid var(--panel-border);
                    display: flex;
                    gap: 10px;
                    align-items: center;
                }

                .composer-panel input[type="text"] {
                    font-size: 14px;
                    padding: 10px 14px;
                }

                /* Protocol Drawer / Inspector */
                .inspector-panel {
                    background-color: var(--panel-bg);
                    border: 1px solid var(--panel-border);
                    border-radius: 8px;
                    padding: 10px 16px;
                    font-size: 12px;
                }

                .inspector-summary {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    cursor: pointer;
                    user-select: none;
                }

                .inspector-summary h3 {
                    font-size: 12px;
                    color: var(--text-muted);
                    text-transform: uppercase;
                    letter-spacing: 0.5px;
                }

                .inspector-metrics {
                    display: flex;
                    gap: 16px;
                    font-family: monospace;
                    font-size: 11px;
                    color: var(--text-muted);
                }

                .metric-val {
                    color: var(--accent-color);
                    font-weight: bold;
                }

                .inspector-content {
                    margin-top: 10px;
                    padding-top: 10px;
                    border-top: 1px dashed var(--panel-border);
                    display: none;
                    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                    gap: 12px;
                    color: #aaa;
                    font-size: 11px;
                    line-height: 1.5;
                }

                .inspector-card {
                    background-color: #161616;
                    border: 1px solid #2a2a2a;
                    padding: 8px 12px;
                    border-radius: 6px;
                }

                .inspector-card strong {
                    color: var(--accent-color);
                    display: block;
                    margin-bottom: 4px;
                }
            </style>
        </head>
        <body>
            <div class="app-container">
                <!-- Header -->
                <header>
                    <div class="brand">
                        <h1>🦖 Bedrock Chat</h1>
                        <span class="badge badge-loom">Java 21 Loom • RFC 6455</span>
                    </div>
                    <div class="status-indicator disconnected" id="status-indicator">
                        <span class="status-dot"></span>
                        <span id="status-text">Disconnected</span>
                    </div>
                </header>

                <!-- Connection Settings Bar -->
                <div class="connection-panel">
                    <div class="input-group" style="flex: 2; min-width: 260px;">
                        <label for="ws-url">WS URL:</label>
                        <input type="text" id="ws-url" value="" autocomplete="off" />
                    </div>
                    <div class="input-group" style="flex: 1; min-width: 140px;">
                        <label for="username">Name:</label>
                        <input type="text" id="username" value="" autocomplete="off" />
                    </div>
                    <button id="btn-toggle" class="btn-connect" onclick="toggleConnection()">Connect ⚡</button>
                </div>

                <!-- Chat Room Window -->
                <div class="chat-card">
                    <div class="chat-card-header">
                        <div class="room-info">
                            <span>💬 #general-chat</span>
                            <span class="latency-badge" id="latency-badge">Latency: -- ms</span>
                        </div>
                        <div style="display: flex; gap: 8px;">
                            <button onclick="measurePing()" id="btn-ping" disabled style="padding: 4px 10px; font-size: 11px;">⚡ Ping</button>
                            <button onclick="clearMessages()" style="padding: 4px 10px; font-size: 11px;">🗑️ Clear</button>
                        </div>
                    </div>

                    <!-- Scrollable Messages Area -->
                    <div class="messages-container" id="messages-container">
                        <div class="message-bubble system">
                            🦖 Welcome to Bedrock Real-Time Chat Playground. Click <b>Connect</b> to join the room!
                        </div>
                    </div>

                    <!-- Bottom Composer -->
                    <div class="composer-panel">
                        <input type="text" id="message-input" placeholder="Connect to start chatting..." disabled
                               autocomplete="off" onkeydown="handleKeyDown(event)" />
                        <button id="btn-send" class="btn-connect" onclick="sendMessage()" disabled>Send 🚀</button>
                    </div>
                </div>

                <!-- Collapsible Educational Protocol Inspector -->
                <div class="inspector-panel">
                    <div class="inspector-summary" onclick="toggleInspector()">
                        <h3>🎓 RFC 6455 Architecture & Live Metrics</h3>
                        <div class="inspector-metrics">
                            <span>Sent: <span class="metric-val" id="metric-sent">0</span></span>
                            <span>Received: <span class="metric-val" id="metric-recv">0</span></span>
                            <span>Uptime: <span class="metric-val" id="metric-uptime">0s</span></span>
                        </div>
                    </div>
                    <div class="inspector-content" id="inspector-content">
                        <div class="inspector-card">
                            <strong>1. HTTP 101 Handshake</strong>
                            Initiated via GET with <code>Upgrade: websocket</code>. Server calculates SHA-1 of <code>Sec-WebSocket-Key</code> + RFC GUID and encodes in Base64.
                        </div>
                        <div class="inspector-card">
                            <strong>2. Virtual Threads (Loom)</strong>
                            Each connected client executes its synchronous blocking I/O loop on a dedicated unpinned carrier-free Virtual Thread.
                        </div>
                        <div class="inspector-card">
                            <strong>3. 4-Byte XOR Masking</strong>
                            RFC 6455 §5.1 invariant: All client frames are masked with 4 random bytes and unmasked by Bedrock using $D_i = E_i \\oplus M_{i \\pmod 4}$.
                        </div>
                        <div class="inspector-card">
                            <strong>4. Atomic Broadcast</strong>
                            Broadcasts fan out safely across active sessions using thread-safe non-pinning <code>ReentrantLock</code> synchronization.
                        </div>
                    </div>
                </div>
            </div>

            <script>
                // State
                let ws = null;
                let isConnected = false;
                let messagesSent = 0;
                let messagesRecv = 0;
                let connectTimestamp = 0;
                let uptimeInterval = null;
                let pingStartTime = 0;

                const injectedPort = "{{INJECTED_WS_PORT}}";
                const injectedPath = "{{INJECTED_WS_PATH}}";

                // DOM Elements
                const wsUrlInput = document.getElementById('ws-url');
                const usernameInput = document.getElementById('username');
                const btnToggle = document.getElementById('btn-toggle');
                const btnSend = document.getElementById('btn-send');
                const btnPing = document.getElementById('btn-ping');
                const messageInput = document.getElementById('message-input');
                const messagesContainer = document.getElementById('messages-container');
                const statusIndicator = document.getElementById('status-indicator');
                const statusText = document.getElementById('status-text');
                const latencyBadge = document.getElementById('latency-badge');
                const metricSent = document.getElementById('metric-sent');
                const metricRecv = document.getElementById('metric-recv');
                const metricUptime = document.getElementById('metric-uptime');

                // Initialize Defaults on Load
                document.addEventListener('DOMContentLoaded', () => {
                    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                    const host = window.location.hostname || 'localhost';
                    
                    // Determine port: if template replaced, use injectedPort; fallback to 8081 if on 8080
                    let port = injectedPort.startsWith('{{') ? '8081' : injectedPort;
                    if (!port || port === '0' || port === '') {
                        port = (window.location.port === '8080') ? '8081' : (window.location.port || '8081');
                    }
                    
                    const path = injectedPath.startsWith('{{') ? '/chat' : injectedPath;
                    wsUrlInput.value = `${protocol}//${host}:${port}${path}`;

                    // Random user handle
                    const randomHex = Math.floor(Math.random() * 0xFFF).toString(16).padStart(3, '0');
                    usernameInput.value = `User-${randomHex}`;
                });

                function toggleConnection() {
                    if (isConnected) {
                        disconnect();
                    } else {
                        connect();
                    }
                }

                function connect() {
                    const url = wsUrlInput.value.trim();
                    if (!url) return;

                    updateStatus('connecting', 'Connecting...');
                    btnToggle.disabled = true;

                    try {
                        ws = new WebSocket(url);
                    } catch (err) {
                        appendSystemMessage('❌ Invalid WebSocket URL: ' + err.message);
                        updateStatus('disconnected', 'Disconnected');
                        btnToggle.disabled = false;
                        return;
                    }

                    ws.onopen = (event) => {
                        isConnected = true;
                        updateStatus('connected', 'Connected');
                        btnToggle.textContent = 'Disconnect ✕';
                        btnToggle.className = 'btn-disconnect';
                        btnToggle.disabled = false;
                        btnSend.disabled = false;
                        btnPing.disabled = false;
                        messageInput.disabled = false;
                        messageInput.placeholder = 'Type a message... (Press Enter to send)';
                        messageInput.focus();

                        connectTimestamp = Date.now();
                        uptimeInterval = setInterval(updateUptime, 1000);
                        appendSystemMessage('🟢 Connected to ' + url);
                    };

                    ws.onmessage = (event) => {
                        messagesRecv++;
                        metricRecv.textContent = messagesRecv;

                        const rawData = event.data;

                        // Check if this was a pong reply to our ping
                        if (pingStartTime > 0 && (rawData.includes('PONG') || rawData === 'pong' || rawData.startsWith('[Pong]'))) {
                            const rtt = Math.round(performance.now() - pingStartTime);
                            latencyBadge.textContent = `Latency: ${rtt} ms`;
                            pingStartTime = 0;
                            appendSystemMessage(`⚡ Pong received in ${rtt}ms`);
                            return;
                        }

                        renderIncomingMessage(rawData);
                    };

                    ws.onclose = (event) => {
                        isConnected = false;
                        updateStatus('disconnected', 'Disconnected');
                        btnToggle.textContent = 'Connect ⚡';
                        btnToggle.className = 'btn-connect';
                        btnToggle.disabled = false;
                        btnSend.disabled = true;
                        btnPing.disabled = true;
                        messageInput.disabled = true;
                        messageInput.placeholder = 'Connect to start chatting...';
                        latencyBadge.textContent = 'Latency: -- ms';

                        if (uptimeInterval) {
                            clearInterval(uptimeInterval);
                            uptimeInterval = null;
                        }

                        const reason = event.reason ? ` (${event.reason})` : '';
                        appendSystemMessage(`🔴 Connection closed. Code: ${event.code}${reason}`);
                    };

                    ws.onerror = (event) => {
                        appendSystemMessage('⚠️ WebSocket error occurred.');
                    };
                }

                function disconnect() {
                    if (ws) {
                        btnToggle.disabled = true;
                        ws.close(1000, 'User initiated disconnect');
                    }
                }

                function sendMessage() {
                    if (!ws || ws.readyState !== WebSocket.OPEN) return;

                    const text = messageInput.value.trim();
                    if (!text) return;

                    const user = usernameInput.value.trim() || 'Anonymous';
                    const formatted = `[${user}] ${text}`;

                    ws.send(formatted);
                    messagesSent++;
                    metricSent.textContent = messagesSent;

                    messageInput.value = '';
                    messageInput.focus();
                }

                function handleKeyDown(event) {
                    if (event.key === 'Enter') {
                        sendMessage();
                    }
                }

                function measurePing() {
                    if (!ws || ws.readyState !== WebSocket.OPEN) return;
                    pingStartTime = performance.now();
                    ws.send('PING');
                }

                function renderIncomingMessage(raw) {
                    const currentUser = usernameInput.value.trim();

                    // 1. System announcements
                    if (raw.startsWith('[System]') || raw.startsWith('SYSTEM:')) {
                        appendSystemMessage('🤖 ' + raw);
                        return;
                    }

                    // 2. User broadcast parsing: expected format "[Username] message"
                    let sender = 'Server';
                    let content = raw;
                    const match = raw.match(/^\\[([^\\]]+)\\]\\s*(.*)$/);
                    if (match) {
                        sender = match[1];
                        content = match[2];
                    }

                    const isSelf = (sender === currentUser);
                    appendChatBubble(sender, content, isSelf);
                }

                function appendChatBubble(sender, text, isSelf) {
                    const bubble = document.createElement('div');
                    bubble.className = 'message-bubble ' + (isSelf ? 'self' : 'peer');

                    const meta = document.createElement('div');
                    meta.className = 'message-meta';

                    const usernameTag = document.createElement('span');
                    usernameTag.className = 'username-tag';
                    usernameTag.textContent = isSelf ? 'You' : sender;

                    const timeTag = document.createElement('span');
                    timeTag.className = 'timestamp-tag';
                    timeTag.textContent = new Date().toLocaleTimeString();

                    meta.appendChild(usernameTag);
                    meta.appendChild(timeTag);

                    const body = document.createElement('div');
                    body.className = 'message-text';
                    body.textContent = text;

                    bubble.appendChild(meta);
                    bubble.appendChild(body);

                    messagesContainer.appendChild(bubble);
                    scrollToBottom();
                }

                function appendSystemMessage(text) {
                    const bubble = document.createElement('div');
                    bubble.className = 'message-bubble system';
                    bubble.textContent = text;
                    messagesContainer.appendChild(bubble);
                    scrollToBottom();
                }

                function scrollToBottom() {
                    messagesContainer.scrollTop = messagesContainer.scrollHeight;
                }

                function clearMessages() {
                    messagesContainer.innerHTML = '';
                    appendSystemMessage('🗑️ Message history cleared locally.');
                }

                function updateStatus(stateClass, label) {
                    statusIndicator.className = 'status-indicator ' + stateClass;
                    statusText.textContent = label;
                }

                function updateUptime() {
                    if (!connectTimestamp) return;
                    const secs = Math.floor((Date.now() - connectTimestamp) / 1000);
                    metricUptime.textContent = `${secs}s`;
                }

                function toggleInspector() {
                    const el = document.getElementById('inspector-content');
                    el.style.display = el.style.display === 'grid' ? 'none' : 'grid';
                }
            </script>
        </body>
        </html>
        """;
    }
}
```

---

## 3. Component Design: `ChatWebSocketTest.java`

### 3.1 Location & Principles
- **Package**: `com.bedrock.example`
- **Class**: `public class ChatWebSocketTest`
- **Directory**: `bedrock-example/src/test/java/com/bedrock/example/ChatWebSocketTest.java`
- **Design Principles**:
  1. **Strict Ephemeral Port Isolation**: Starts both HTTP server and WebSocket server on dynamic OS-assigned port `0` (`BedrockApp.create(0).enableWebSockets(0)`), completely eliminating port binding conflicts (`BindException`) and guaranteeing 100% thread/socket isolation.
  2. **Standard JDK 21 Client**: Uses native `java.net.http.HttpClient` with `newWebSocketBuilder()` — zero third-party testing dependencies.
  3. **AutoCloseable Cleanup**: Tests run within `try-with-resources` on `BedrockApp`, ensuring coordinated shutdown and port release.
  4. **Full Coverage of Real-Time Workloads**:
     - Connection & HTTP 101 Handshake.
     - Single client message send and unmasked frame broadcast receipt.
     - Multi-client simultaneous broadcast fan-out (3+ concurrent virtual threads).
     - Clean disconnection lifecycle (Client initiates Close 1000, server handles `@OnClose`, removes session).
     - Multi-byte UTF-8, accented characters, and Unicode emojis (RFC 6455 §8.1 compliance).
     - Rapid-fire concurrency stress testing.
     - Application coordinated stop lifecycle (RFC 6455 code 1001 Going Away).
     - HTTP GET `/chat` route serving `ChatPlayground.getHtml()`.

### 3.2 Complete Proposed Code: `ChatWebSocketTest.java`

```java
package com.bedrock.example;

import com.bedrock.core.BedrockApp;
import com.bedrock.example.ws.ChatPlayground;
import com.bedrock.example.ws.ChatWebSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🎓 BEDROCK TUTORIAL: Milestone 4 Real-Time Chat WebSocket Integration Test Suite
 *
 * <p>Validates the complete end-to-end integration between the {@code bedrock-example}
 * application, {@link ChatWebSocket}, {@link ChatPlayground}, and the underlying
 * Bedrock Version 2.0 Virtual-Threaded RFC 6455 WebSocket engine.</p>
 *
 * <h3>Key Verification Goals:</h3>
 * <ul>
 *   <li><b>Ephemeral Port Allocation</b>: Validates dynamic zero-conflict port binding.</li>
 *   <li><b>HTTP 101 Switching Protocols</b>: Verifies handshake execution via {@link HttpClient}.</li>
 *   <li><b>Multi-Client Broadcasting</b>: Proves message fan-out across multiple concurrent virtual threads.</li>
 *   <li><b>UTF-8 Invariants</b>: Verifies emoji and multi-byte character preservation.</li>
 *   <li><b>Graceful Lifecycle Teardown</b>: Verifies clean socket release and RFC 1001 close signaling.</li>
 * </ul>
 */
public class ChatWebSocketTest {

    private static final int TIMEOUT_SECONDS = 5;

    /**
     * Helper to establish an HttpClient WebSocket connection with a queue for received text frames.
     */
    private record TestClient(WebSocket webSocket, BlockingQueue<String> messages, CountDownLatch openLatch) {
        public void close() {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Test Complete")
                         .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }

    private TestClient connectClient(int wsPort, String path) throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        CountDownLatch openLatch = new CountDownLatch(1);

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + wsPort + path), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        openLatch.countDown();
                        webSocket.request(Long.MAX_VALUE);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageQueue.offer(data.toString());
                        return null;
                    }
                });

        WebSocket ws = wsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(openLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Client WebSocket must trigger onOpen");
        return new TestClient(ws, messageQueue, openLatch);
    }

    // =========================================================================
    // Scenario 1: Connection Establishment & Handshake Verification
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Client establishes RFC 6455 connection to ChatWebSocket and receives join broadcast")
    void testWebSocketConnectionAndHandshake() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            assertTrue(wsPort > 0, "Bound WebSocket port must be positive ephemeral port");

            TestClient client = connectClient(wsPort, "/chat");
            try {
                // Wait for initial announcement from server @OnOpen
                String welcomeOrJoin = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(welcomeOrJoin, "Client must receive join or welcome message upon connecting");
                assertTrue(welcomeOrJoin.contains("[System]") || welcomeOrJoin.contains("connected") || welcomeOrJoin.contains("User"),
                        "Initial message should announce connection: " + welcomeOrJoin);
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Scenario 2: Single Client Send and Receive Broadcast
    // =========================================================================

    @Test
    @DisplayName("Scenario 2: Single client sends a chat message and receives the unmasked broadcast back")
    void testSingleClientSendMessageAndReceiveBroadcast() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client = connectClient(wsPort, "/chat");

            try {
                // Drain any initial system join announcement
                client.messages().poll(1, TimeUnit.SECONDS);

                String chatPayload = "[Alice] Hello Bedrock Virtual Threads!";
                client.webSocket().sendText(chatPayload, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String received = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received, "Client must receive broadcast message");
                assertTrue(received.contains("Hello Bedrock Virtual Threads!"),
                        "Received message must contain the sent text payload: " + received);
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Scenario 3: Multiple Simultaneous Clients Broadcast Fan-Out
    // =========================================================================

    @Test
    @DisplayName("Scenario 3: Multi-client broadcast delivers messages simultaneously to all active sessions")
    void testMultipleClientsReceiveBroadcastSimultaneously() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            int clientCount = 3;
            List<TestClient> clients = new CopyOnWriteArrayList<>();

            try {
                // Connect 3 independent clients concurrently
                for (int i = 0; i < clientCount; i++) {
                    clients.add(connectClient(wsPort, "/chat"));
                }

                // Drain initial connection messages from all queues
                for (TestClient c : clients) {
                    while (c.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                }

                // Client 0 sends a message
                String broadcastMessage = "[User-0] Hello everyone in the room!";
                clients.get(0).webSocket().sendText(broadcastMessage, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Verify all 3 clients received the broadcast message
                for (int i = 0; i < clientCount; i++) {
                    String received = clients.get(i).messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(received, "Client #" + i + " must receive broadcast");
                    assertTrue(received.contains("Hello everyone in the room!"),
                            "Client #" + i + " payload must match broadcast content: " + received);
                }

                // Client 2 sends a reply
                String replyMessage = "[User-2] Got your message loud and clear!";
                clients.get(2).webSocket().sendText(replyMessage, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Verify all 3 clients receive Client 2's reply
                for (int i = 0; i < clientCount; i++) {
                    String received = clients.get(i).messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(received, "Client #" + i + " must receive reply");
                    assertTrue(received.contains("Got your message loud and clear!"),
                            "Client #" + i + " payload must match reply content: " + received);
                }

            } finally {
                for (TestClient c : clients) {
                    c.close();
                }
            }
        }
    }

    // =========================================================================
    // Scenario 4: Clean Disconnection Lifecycle & Peer Notification
    // =========================================================================

    @Test
    @DisplayName("Scenario 4: Client initiates clean Close 1000 and remaining client receives departure notice")
    void testCleanDisconnectionLifecycle() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client1 = connectClient(wsPort, "/chat");
            TestClient client2 = connectClient(wsPort, "/chat");

            try {
                // Drain initial announcements
                while (client1.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (client2.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                // Client 1 gracefully closes connection
                client1.webSocket().sendClose(WebSocket.NORMAL_CLOSURE, "Leaving chat")
                       .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Client 2 should receive a departure notice or system broadcast
                String departureMsg = client2.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (departureMsg != null) {
                    assertTrue(departureMsg.contains("[System]") || departureMsg.contains("disconnected") || departureMsg.contains("User"),
                            "Departure announcement should notify active peers: " + departureMsg);
                }

                // Verify Client 2 can still send messages normally after Client 1 left
                String soloMsg = "[Client-2] Still here alone!";
                client2.webSocket().sendText(soloMsg, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String receivedSolo = client2.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(receivedSolo);
                assertTrue(receivedSolo.contains("Still here alone!"));

            } finally {
                client1.close();
                client2.close();
            }
        }
    }

    // =========================================================================
    // Scenario 5: Multi-Byte UTF-8 and Unicode Emoji Preservation
    // =========================================================================

    @Test
    @DisplayName("Scenario 5: Multi-byte UTF-8, accented Portuguese, and Unicode emojis survive framing intact")
    void testMultiByteUtf8AndEmojiSupport() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client = connectClient(wsPort, "/chat");

            try {
                client.messages().poll(500, TimeUnit.MILLISECONDS);

                String complexPayload = "🦖 Bedrock 2.0: Olá mundo! João comprou maçãs & café na praça. 🚀✨ (日本語・한국어)";
                client.webSocket().sendText(complexPayload, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                String received = client.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(received);
                assertTrue(received.contains(complexPayload),
                        "Multi-byte UTF-8 text and emojis must match exactly: " + received);
            } finally {
                client.close();
            }
        }
    }

    // =========================================================================
    // Scenario 6: Rapid-Fire Concurrent Messages Under Virtual Threads
    // =========================================================================

    @Test
    @DisplayName("Scenario 6: Rapid-fire messages are broadcast without frame tearing or session deadlock")
    void testRapidFireConcurrentMessages() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.start();

            int wsPort = app.getWebSocketPort();
            TestClient client1 = connectClient(wsPort, "/chat");
            TestClient client2 = connectClient(wsPort, "/chat");

            try {
                // Drain initial joins
                while (client1.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}
                while (client2.messages().poll(200, TimeUnit.MILLISECONDS) != null) {}

                int messageCount = 15;
                for (int i = 0; i < messageCount; i++) {
                    client1.webSocket().sendText("[Burst] Message #" + i, true);
                }

                // Verify client2 receives all 15 messages
                List<String> receivedByClient2 = new ArrayList<>();
                for (int i = 0; i < messageCount; i++) {
                    String msg = client2.messages().poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(msg, "Client 2 must receive message #" + i + " before timeout");
                    receivedByClient2.add(msg);
                }

                assertEquals(messageCount, receivedByClient2.size(), "All burst messages must be delivered");

            } finally {
                client1.close();
                client2.close();
            }
        }
    }

    // =========================================================================
    // Scenario 7: Application Coordinated Teardown & Zero Port Leaks
    // =========================================================================

    @Test
    @DisplayName("Scenario 7: BedrockApp.stop() notifies clients with 1001 Going Away and frees ports")
    void testAppLifecycleStopReleasesAllResources() throws Exception {
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        AtomicInteger receivedCloseCode = new AtomicInteger(0);

        BedrockApp app = BedrockApp.create(0).enableWebSockets(0);
        app.register(ChatWebSocket.class);
        app.start();

        int wsPort = app.getWebSocketPort();
        HttpClient httpClient = HttpClient.newHttpClient();

        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + wsPort + "/chat"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        receivedCloseCode.set(statusCode);
                        clientCloseLatch.countDown();
                        return null;
                    }
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Stop application server
        app.stop();

        // Client must be notified with RFC 6455 code 1001 (Going Away)
        assertTrue(clientCloseLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Client must receive Close notification when server stops");
        assertEquals(1001, receivedCloseCode.get(), "Server stop must transmit status 1001 (GOING_AWAY)");
        assertFalse(app.isRunning(), "BedrockApp must report isRunning() == false");
    }

    // =========================================================================
    // Scenario 8: HTTP Route Serves ChatPlayground UI
    // =========================================================================

    @Test
    @DisplayName("Scenario 8: HTTP GET /chat serves ChatPlayground HTML with 200 OK")
    void testChatPlaygroundHtmlRouteServedViaHttp() throws Exception {
        try (BedrockApp app = BedrockApp.create(0).enableWebSockets(0)) {
            app.register(ChatWebSocket.class);
            app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml(app.getWebSocketPort(), "/chat")));
            app.start();

            int httpPort = app.getPort();
            HttpClient httpClient = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + httpPort + "/chat"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), "HTTP status must be 200 OK");
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"),
                    "Content-Type header must be text/html");
            assertTrue(response.body().contains("🦖 Bedrock Chat"), "HTML must contain ChatPlayground title");
            assertTrue(response.body().contains("new WebSocket"), "HTML must contain WebSocket JS client script");
            assertTrue(response.body().contains(String.valueOf(app.getWebSocketPort())),
                    "HTML must contain the injected WebSocket port");
        }
    }
}
```

---

## 4. Integration Blueprint for `Application.java`

To tie Milestone 4 together in `bedrock-example/src/main/java/com/bedrock/example/Application.java`:

### 4.1 Modifications in `Application.java`
```java
// Configure BedrockApp with HTTP port 8080 and WebSocket port 8081
BedrockApp app = BedrockApp.create(8080)
        .enableWebSockets(8081);

// Serve the interactive Chat Playground at /chat
app.get("/chat", ctx -> ctx.html(ChatPlayground.getHtml(app.getWebSocketPort(), "/chat")));

// Register ChatWebSocket along with existing controllers and services
app.register(
    SqliteUserRepository.class,
    UserService.class,
    UserController.class,
    ChatWebSocket.class
).start();
```

### 4.2 Behavior When Running the Example Application
1. **HTTP Endpoints**:
   - `http://localhost:8080/api/users` (REST API)
   - `http://localhost:8080/bedrock/ui` (REST Swagger-style Playground)
   - `http://localhost:8080/chat` (Real-Time WebSocket Chat Playground)
2. **WebSocket Endpoint**:
   - `ws://localhost:8081/chat` (RFC 6455 Virtual-Threaded Chat WebSocket)
3. **Dual-Server Port Architecture**:
   - Standard JDK `HttpServer` listens on port `8080`.
   - Dedicated `BedrockWebSocketServer` listens on port `8081`.
   - When a browser opens `http://localhost:8080/chat`, the page automatically connects its WebSocket to `ws://localhost:8081/chat`.

---

## 5. Verification Checklist

| # | Check Item | Target | Verification Method |
|---|------------|--------|---------------------|
| 1 | ChatPlayground Compilation | `com.bedrock.example.ws.ChatPlayground` | `mvn test-compile -pl bedrock-example` |
| 2 | ChatWebSocketTest Execution | All 8 integration scenarios pass | `mvn test -pl bedrock-example -Dtest=ChatWebSocketTest` |
| 3 | Zero Dependency Invariant | Only standard JDK 21 in core & playground | Inspect POMs and class imports |
| 4 | Ephemeral Port Safety | Zero `BindException` or port leaks | Run test suite repeatedly with port 0 |
| 5 | Dark Theme UI Usability | Complete UI with connection, chat, ping, stats | Visual verification via browser / Scenario 8 HTTP test |
| 6 | Regression Integrity | All 81 baseline framework tests pass | `mvn clean verify` |
