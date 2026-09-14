## 2026-09-11T04:10:49Z
You are challenger_m1_1, an Adversarial Challenger for Milestone 1: RFC 6455 Protocol Engine.
Your working directory is: c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m1_1
The user request is located at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\ORIGINAL_REQUEST.md (you MUST read this file first).
The project scope document is at: c:\Users\roner\Documents\repo\bedrock-framework\PROJECT.md.
Worker's handoff is at: c:\Users\roner\Documents\repo\bedrock-framework\.agents\worker_m1\handoff.md

Your Mission:
Empirically challenge the correctness and robustness of the implementation:
1. Challenge `WebSocketHandshake`: test with exotic whitespace, lowercase header names, multi-token Connection headers ("keep-alive, Upgrade"), invalid version (e.g. 8), truncated keys.
2. Challenge `WebSocketFrameParser`: test with custom byte sequences for:
   - Client frames with MASK=0 (must reject with 1002).
   - In-place XOR unmasking with non-trivial 4-byte keys.
   - Non-minimal payload length encodings (length 10 encoded as 16-bit 0x000A -> must reject with 1002).
   - RSV1=1, RSV2=1, or RSV3=1 (must reject with 1002).
   - Malformed UTF-8 sequences (e.g. invalid continuation bytes, truncated multi-byte sequences -> must reject with 1007).
   - Control frames with length > 125 or FIN=0 (must reject with 1002).
   - Close frame with payload length 1 (must reject with 1002).
3. If possible, write and execute stress tests or assertion scripts.

Deliverables:
- Write challenge report to `c:\Users\roner\Documents\repo\bedrock-framework\.agents\challenger_m1_1\challenge.md`
- Write `handoff.md` with an explicit verdict: **APPROVE** (confirmed correct) or **REQUEST_CHANGES** (bugs found).
- Send completion message to parent orchestrator.
