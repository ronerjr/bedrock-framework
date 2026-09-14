# Reviewer M4-2 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m4_2`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m4_gen3\handoff.md`

## Mission
Independent Review of Milestone 4 (bedrock-example Real-Time Demo):
1. Verify declarative annotation mechanics and IoC binding of `ChatWebSocket` in `Application.java`.
2. Verify broadcast concurrency safety under Project Loom virtual threads.
3. Verify client isolation and dead session eviction during broadcast errors.
4. Verify `ChatPlayground` offline independence (no external CDN dependencies).
5. Verify test coverage and clean port release in `ChatWebSocketTest`.
6. Produce a structured `handoff.md` with explicit verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:31:35Z
You are reviewer_m4_2. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m4_2. Read DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Independently review Milestone 4 (broadcast concurrency safety, client error boundaries, zero CDN offline ChatPlayground, test coverage), and output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.

