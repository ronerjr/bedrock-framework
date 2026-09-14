# Reviewer M3-2 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m3_2`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m3_gen3\handoff.md`

## Mission
Independent Review of Milestone 3 (BedrockApp Integration & Annotations):
1. Verify declarative annotation mechanics: no bytecode manipulation, no dynamic proxies (CGLIB/ByteBuddy), clean reflection.
2. Verify parameter permutations and argument matching in reflective binding.
3. Verify registration order invariance: `register()` before or after `enableWebSockets()`.
4. Verify IoC container integration: constructor dependency injection into `@BedrockSocket` classes.
5. Verify clean shutdown and resource management: `stop()` and `close()` terminating sessions with Close 1001, releasing TCP ports.
6. Verify tutorial Javadoc standard (`🎓 BEDROCK TUTORIAL`) across all new classes.
7. Run build and tests via `mvn test -pl bedrock-core` and verify all tests pass.
8. Produce a structured `handoff.md` with an explicit verdict: **APPROVE** or **REQUEST_CHANGES**.

## 2026-09-11T13:09:30Z
You are reviewer_m3_2. Your working directory is c:\Users\roner\Documents\repo\bedrock-framework\.agents\reviewer_m3_2. Read the instructions in DISPATCH.md and c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md. Independently review Milestone 3 implementation (annotations, scanner, BedrockApp integration, AutoCloseable lifecycle), run tests via maven, and output your verdict (APPROVE or REQUEST_CHANGES) in handoff.md. Message parent when done.
