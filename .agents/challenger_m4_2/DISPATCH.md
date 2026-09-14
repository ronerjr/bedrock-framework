# Challenger M4-2 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m4_2`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3\handoff.md`

## Mission
Adversarial Verification of Milestone 4:
1. Verify dual server coexistence in `bedrock-example` (HTTP on 8080 or port A, WS on 8081 or port B).
2. Verify that `/chat` HTTP page correctly retrieves the WebSocket port and connects properly.
3. Test edge case commands: `/nick `, `/nick with spaces`, extra long messages.
4. Verify server teardown: `stop()` cleanly notifies all connected chat clients with Close 1001 Going Away and releases ports without leaks.
5. Produce a structured `handoff.md` with explicit verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:31:36Z
You are challenger_m4_2. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m4_2. Read DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Adversarially test Milestone 4 dual-server coexistence, HTML page port resolution, edge case commands, and Close 1001 teardown. Output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.
