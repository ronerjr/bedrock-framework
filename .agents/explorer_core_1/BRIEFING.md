# BRIEFING — 2026-09-11T04:00:00Z

## Mission
Investigate the existing `bedrock-core` module to analyze architecture, server startup, routing, IoC, thread management, pedagogical standards, and extension points for WebSockets v2.0.

## 🔒 My Identity
- Archetype: explorer
- Roles: Codebase Explorer
- Working directory: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1
- Original parent: 77cf56d8-3c86-4c29-988f-a89e17f229f1
- Milestone: Phase 1 - Codebase & Specification Survey

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Zero-dependency constraint on bedrock-core (JDK 21 standard packages only)
- Pedagogical standard: 🎓 BEDROCK TUTORIAL in Javadocs
- Write findings to core_survey.md and handoff.md in working directory
- Deliver completion message to parent orchestrator via send_message

## Current Parent
- Conversation ID: 77cf56d8-3c86-4c29-988f-a89e17f229f1
- Updated: 2026-09-11T04:00:00Z

## Investigation State
- **Explored paths**:
  - `pom.xml`, `bedrock-core/pom.xml`, `bedrock-example/pom.xml`
  - `BedrockApp.java`, `Router.java`, `Context.java`, `Handler.java`, `Middleware.java`, `AfterMiddleware.java`
  - `BedrockContainer.java`, `BedrockComponent.java`, `BedrockInject.java`
  - `BedrockController.java`, `BedrockGet.java`, `BedrockPost.java`, etc.
  - `BedrockLogger.java`, `BedrockJson.java`, `BedrockJdbc.java`
  - All test files across `bedrock-core` and `bedrock-example`
- **Key findings**:
  - Zero runtime dependencies in `bedrock-core`.
  - HTTP server is `com.sun.net.httpserver.HttpServer` with Virtual Threads.
  - WebSockets cannot hijack `HttpExchange` sockets; independent `BedrockWebSocketServer` on dedicated port (`app.enableWebSockets(port)`) is necessary and architecturally decoupled.
  - `app.register(...)` can scan for `@BedrockSocket` and wire endpoints directly into `BedrockWebSocketServer` while leveraging `BedrockContainer` for IoC constructor injection.
  - Virtual Threads are the ideal Project Loom pattern for `SocketChannel` accept and per-connection worker loops.
  - Pedagogical standard `🎓 BEDROCK TUTORIAL` is well-established across 32 places.
- **Unexplored areas**: None for Phase 1. Investigation complete.

## Key Decisions Made
- Confirmed that WebSocket server must run on an independent port via `app.enableWebSockets(int port)` because `com.sun.net.httpserver.HttpServer` does not allow upgrading or hijacking raw TCP socket channels.
- Confirmed that JDK 21 standard packages (`java.nio`, `java.net`, `java.security`, `java.lang.reflect`) satisfy 100% of WebSocket requirements without any external dependency.

## Artifact Index
- DISPATCH.md — incoming instructions
- progress.md — liveness heartbeat
- BRIEFING.md — persistent working memory
- core_survey.md — detailed survey findings (`c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1\core_survey.md`)
- handoff.md — 5-component handoff report (`c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_core_1\handoff.md`)
