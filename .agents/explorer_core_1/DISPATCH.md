## 2026-09-11T03:55:57Z

You are explorer_core_1, a Codebase Explorer.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).

Your Mission:
Investigate the existing `bedrock-core` module in c:\Users\roner\Documents\repo\bedrock-framework:
1. Architecture & Structure:
   - Inspect pom.xml files (root pom.xml, bedrock-core/pom.xml, etc.). Check dependencies, compiler version (Java 21), plugins.
   - Inspect BedrockApp and server startup: How does Bedrock start? How does the existing HTTP server work? (Does it use ServerSocketChannel / SocketChannel, or com.sun.net.httpserver, or plain ServerSocket?)
   - How are routers, handlers, filters, and IoC container (dependency injection / bean registration) implemented?
   - How does `app.register(...)` work currently?
2. Extension Points for WebSockets:
   - How should `app.enableWebSockets(int port)` and `app.register(MyWebSocketEndpoint.class)` be integrated cleanly with BedrockApp?
   - Can BedrockWebSocketServer run on its own port or share/coexist with HTTP? (Check what ORIGINAL_REQUEST says: `app.enableWebSockets(port)`).
   - How are routes mapped (e.g. `@BedrockSocket(path = "/chat")`)?
   - How are annotations handled in bedrock-core? (Check existing annotations like `@BedrockController`, `@Get`, etc.).
   - How is thread management done currently? Are Virtual Threads already used in the HTTP server or anywhere in the project?
3. Pedagogical Standard (🎓 BEDROCK TUTORIAL):
   - Inspect existing Javadocs across bedrock-core. What does the `🎓 BEDROCK TUTORIAL` convention look like? (Style, headers, tone, code examples).
4. Zero-Dependency Constraint:
   - Check if any new dependencies would be needed or if JDK 21 standard packages (java.nio, java.net, java.security, java.lang.reflect) are completely sufficient.

Deliverables:
- Write your detailed codebase analysis to: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1\core_survey.md
- Write handoff.md in your working directory.
- Send a completion message to the parent orchestrator.
