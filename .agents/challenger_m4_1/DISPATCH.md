# Challenger M4-1 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m4_1`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3\handoff.md`

## Mission
Adversarial Verification of Milestone 4 (bedrock-example Real-Time Demo):
1. Test stress conditions: rapid-fire concurrent chat messages from multiple virtual threads.
2. Test Unicode, multi-byte UTF-8, emojis, special characters, and empty messages.
3. Test abrupt client disconnects during active broadcast fanout.
4. Verify no race conditions in `ChatWebSocket.sessions` `ConcurrentHashMap`.
5. Produce a structured `handoff.md` with explicit verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:31:36Z
You are challenger_m4_1. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m4_1. Read DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Adversarially test Milestone 4 (burst concurrency, Unicode/emojis, client disconnects during broadcast, session registry thread safety), and output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.

