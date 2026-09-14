## 2026-09-11T04:05:29Z
You are worker_m1, the Implementation Worker for Milestone 1: RFC 6455 Protocol Engine.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md (read for architecture, contracts, and code layout).

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Design Documents to Follow:
- Handshake & Protocol design: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_1\handshake_design.md
- Frame Parser & Serializer design: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_2\frame_design.md
- Unit Tests & Tutorial design: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_3\test_and_tutorial_design.md

Exclusive File Write Ownership:
You own and will create/modify ONLY the following files:
1. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketOpcode.java`
2. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketCloseStatus.java`
3. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketException.java`
4. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketHandshake.java`
5. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrame.java`
6. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrameParser.java`
7. `bedrock-core/src/main/java/com/bedrock/core/ws/protocol/WebSocketFrameWriter.java`
8. `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/WebSocketHandshakeTest.java`
9. `bedrock-core/src/test/java/com/bedrock/core/ws/protocol/WebSocketFrameTest.java`

Key Requirements:
- Zero external runtime dependencies: use strictly JDK 21 standard library (`java.nio`, `java.security.MessageDigest`, `java.net`, `java.util.Base64`, etc.). DO NOT modify `bedrock-core/pom.xml`.
- Include rich `🎓 BEDROCK TUTORIAL` Javadocs on all classes explaining the physics of Handshake 101, SHA-1/GUID security, frame bit layout, and XOR unmasking.
- Implement the in-place 4-byte XOR unmasking: $D_i = E_i \oplus M_{i \pmod 4}$.
- Enforce mandatory client masking (reject unmasked with 1002), minimal-byte length encoding (1002), control frame invariants (FIN=1, length <= 125), and UTF-8 validation (1007).
- Implement all 12 test methods in `WebSocketHandshakeTest` and all 20 test methods in `WebSocketFrameTest`.
- Run `mvn test` (or `mvn clean test-compile test`) and verify that all 81 existing tests PLUS all new tests pass with 100% success.
- Report all build and test outputs in your handoff report.

Deliverables:
- Write changes.md and handoff.md in your working directory: `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m1`
- Send completion message to parent orchestrator.
