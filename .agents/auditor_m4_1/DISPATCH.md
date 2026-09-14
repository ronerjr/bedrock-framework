# Forensic Auditor M4 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\auditor_m4_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3\handoff.md`

## Mission
Forensic Integrity Audit of Milestone 4 (bedrock-example Real-Time Demo):
1. Dependency Audit: inspect `bedrock-example/pom.xml`. Confirm NO unauthorized third-party runtime dependencies introduced for WebSockets (only existing sqlite-jdbc if already present; zero new runtime libraries).
2. Anti-Cheating & Facade Audit: verify no hardcoded test responses, no mock implementations masquerading as real code, authentic live WebSocket communication.
3. Architecture Audit:
   - Confirm `ChatWebSocket` uses standard `@BedrockSocket("/chat")` and genuine reflection bindings.
   - Confirm `ChatPlayground` is offline-capable with zero external CDNs.
   - Confirm virtual threads handle connections without thread leaks.
4. Javadoc Tutorial Audit: verify rich `🎓 BEDROCK TUTORIAL` headers on `ChatWebSocket`.
5. Provide binary audit verdict: **CLEAN** or **INTEGRITY VIOLATION**, with full evidence report in `handoff.md`.

## 2026-09-11T13:31:36Z
You are auditor_m4_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\auditor_m4_1. Read DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Conduct a forensic integrity audit of Milestone 4: check dependencies (zero external libs added for WebSockets), genuine non-facade code, authentic live WebSocket messaging, offline zero-CDN UI, and tutorial Javadocs. Output your binary verdict (CLEAN or INTEGRITY VIOLATION) in handoff.md. Message parent when done.
