# Forensic Auditor M3 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\auditor_m3_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3_gen3\handoff.md`

## Mission
Forensic Integrity Audit of Milestone 3 (BedrockApp Integration & Annotations):
1. Dependency Audit: inspect `bedrock-core/pom.xml`. Confirm ZERO third-party runtime dependencies (standard JDK 21 LTS only).
2. Anti-Cheating & Facade Audit: verify no hardcoded test outputs, no fake implementations, no dummy mocks masquerading as genuine code.
3. Architecture Audit:
   - Confirm standard JDK reflection is used without CGLIB, ByteBuddy, or dynamic runtime bytecode generation.
   - Confirm Project Loom Virtual Threads (`Thread.ofVirtual()`) are used cleanly for client connections.
   - Confirm genuine NIO channels (`ServerSocketChannel`, `SocketChannel`) and RFC 6455 frame parsing/writing.
   - Confirm ReentrantLock on session writes (no carrier thread pinning).
4. Code Quality & Tutorial Audit:
   - Verify `🎓 BEDROCK TUTORIAL` headers on all public classes and methods in `com.bedrock.core.ws.annotation`, `WebSocketEndpointScanner`, and `BedrockApp`.
5. Provide binary audit verdict: **CLEAN** or **INTEGRITY VIOLATION**, with full evidence report in `handoff.md`.

## 2026-09-11T13:09:32Z
You are auditor_m3_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\auditor_m3_1. Read the instructions in DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Conduct a rigorous forensic integrity audit of Milestone 3: check dependencies (zero external libs in bedrock-core), genuine non-facade code, genuine Loom virtual threads, standard reflection without bytecode proxies, and 🎓 BEDROCK TUTORIAL Javadocs. Output your binary verdict (CLEAN or INTEGRITY VIOLATION) in handoff.md. Message parent when done.
