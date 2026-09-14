# Worker M5 Dispatch Instructions

## Working Directory
`c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m5_gen3`

## Mandatory Documents to Read
1. `c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md`
2. `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`
3. `c:\Users\roner\Documents\repo\bedrock-framework\TEST_INFRA.md`
4. `c:\Users\roner\Documents\repo\bedrock-framework\ROADMAP.md`

## Mission
Execute Milestone 5 (Full E2E Pass, Hardening, Documentation & Final Verification):
1. Run verification across the entire multi-module project:
   - Run `mvn clean verify` or `mvn clean test` across the whole repository.
   - Verify that all 81 baseline framework tests (REST, JSON, Validation, JDBC, IoC) pass with 100% success.
   - Verify that all new WebSocket tests across `bedrock-core` and `bedrock-example` pass with 100% success.
   - Verify Javadoc generation (`mvn javadoc:javadoc -pl bedrock-core`).
2. Update `c:\Users\roner\Documents\repo\bedrock-framework\ROADMAP.md`:
   - Mark Version 2.0 as complete: `## ✅ Versão 2.0 - *Comunicação em Tempo Real (WebSockets)* (Concluído)`.
   - Change both `- [ ]` checkboxes to `- [x]`.
   - Add summary of delivered features: RFC 6455 Protocol Engine, Virtual-Threaded NIO Server, `@BedrockSocket` lifecycle annotations, explicit `enableWebSockets()` dual-server orchestration, and the real-time chat demo in `bedrock-example`.
3. Update `c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md`:
   - In `## Milestones` table, mark all milestones (M1, M2, M3, M4, M5) as `DONE`.
4. Produce a structured `handoff.md` with full verification details, test inventory, pass counts, and confirmation of zero regressions.

## Mandatory Integrity Warning
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## 2026-09-11T13:38:14Z
User Prompt:
You are worker_m5_gen3, executing Milestone 5 of Bedrock Framework Version 2.0 (Full E2E Pass, Hardening, ROADMAP update & Final Verification).
Working directory: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m5_gen3
Workspace root is: c:\Users\roner\Documents\repo\bedrock-framework

Execute:
1. Run full verification via Maven:
   - Run mvn clean test or mvn clean verify across all modules.
   - Verify that all 81 baseline framework tests pass 100%.
   - Verify all new WebSocket tests in bedrock-core and bedrock-example pass 100%.
   - Verify Javadoc compilation without errors.
2. Update ROADMAP.md:
   - Mark Version 2.0 as complete: ## ✅ Versão 2.0 - *Comunicação em Tempo Real (WebSockets)* (Concluído)
   - Mark items with [x] and describe completed features.
3. Update PROJECT.md:
   - Mark all milestones (M1, M2, M3, M4, M5) as DONE in the Milestones table.
4. Write handoff.md summarizing the complete verification report and notify parent when done.

