# Challenger M3-1 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m3_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3_gen3\handoff.md`

## Mission
Adversarial Verification of Milestone 3 (BedrockApp Integration & Annotations):
1. Write and run stress/boundary tests for `WebSocketEndpointScanner` and `BedrockApp` WebSocket lifecycle.
2. Test edge cases:
   - Complex parameter ordering on lifecycle methods (e.g. `@OnMessage` with `(BedrockWebSocketSession, String)`, `(String, BedrockWebSocketSession)`, `(String)`).
   - Malformed endpoints: duplicate annotations, unsupported types, missing paths.
   - Concurrency: multiple simultaneous client connections and rapid open/message/close sequences.
   - Resource leaks: verifying ports and threads are completely cleaned up after `app.stop()`.
3. Report any bugs, edge case failures, or performance degradations.
4. Produce a structured `handoff.md` with an explicit verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:09:31Z
You are challenger_m3_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m3_1. Read the instructions in DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Empirically and adversarially test Milestone 3 (concurrency, malformed inputs, parameter permutations, lifecycle leaks). Write tests if needed and run via maven. Output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.
