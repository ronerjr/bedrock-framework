# BRIEFING — 2026-09-11T04:04:00Z

## Mission
Design exact byte-level parsing, serialization, and unmasking algorithms for WebSocketFrame, WebSocketFrameParser, and WebSocketFrameWriter adhering to RFC 6455.

## 🔒 My Identity
- Archetype: explorer
- Roles: investigation, specification analysis, protocol frame algorithm design
- Working directory: c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_2
- Original parent: 77cf56d8-3c86-4c29-988f-a89e17f229f1
- Milestone: Milestone 1 (RFC 6455 Protocol Engine)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement code outside .agents/explorer_m1_2
- Only write within c:\Users\roner\Documents\repo\bedrock-framework\.agents\explorer_m1_2
- Strict adherence to RFC 6455 framing requirements (FIN, RSV, Opcode, Mask, Minimal length encoding, Control frame constraints, UTF-8 validation, Close frame payload validation)
- Zero external dependencies beyond Java 21 stdlib

## Current Parent
- Conversation ID: 77cf56d8-3c86-4c29-988f-a89e17f229f1
- Updated: 2026-09-11T04:01:37Z

## Investigation State
- **Explored paths**:
  - `ORIGINAL_REQUEST.md` (R1 protocol requirements, R4 educational standard)
  - `PROJECT.md` (Milestone 1 architecture, layer contracts)
  - `.agents/spec_miner_survey_1/spec_survey.md` (RFC 6455 framing specs, XOR mechanics, status codes)
  - `.agents/explorer_core_1/core_survey.md` (Bedrock v1.3 architecture, Java 21 NIO Virtual Threads)
  - `.agents/explorer_m1_1/DISPATCH.md` and `.agents/explorer_m1_3/DISPATCH.md` (peer boundaries)
- **Key findings**:
  - Stateless non-destructive mark/reset parsing on `ByteBuffer` enables zero-copy streaming decoding in Virtual Threads.
  - Client frames strictly require MASK=1 (throw 1002 on unmasked).
  - Server frames strictly require MASK=0 (unmasked).
  - Minimal length encoding rules strictly enforced: 16-bit length must be >= 126; 64-bit length must be >= 65536 with MSB=0.
  - Control frames: payload <= 125 bytes, FIN=1.
  - Close frames: payload length 0 or >= 2 (never 1 byte); 1005, 1006, 1015 forbidden on wire.
  - Strict UTF-8 validation using `CharsetDecoder` with `CodingErrorAction.REPORT` for text frames and close reasons (throw 1007 on malformation).
- **Unexplored areas**: None for M1 frame design scope.

## Key Decisions Made
- Designed `WebSocketFrame` with immutable representation, defensive array copying, and pre-computed close frame status code and reason string.
- Designed `WebSocketFrameParser.parse(ByteBuffer)` using NIO mark/reset pattern, returning `null` on partial data and advancing buffer position on complete frame.
- Implemented in-place XOR unmasking with single-cycle `i & 3` optimization.
- Designed `WebSocketFrameWriter` with single contiguous byte array allocation for zero GC overhead and added `createMaskedFrame` helper for testing.
- Created `frame_design.md` and `handoff.md`.

## Artifact Index
- DISPATCH.md — Initial dispatch message
- BRIEFING.md — Working memory and situational awareness
- progress.md — Liveness heartbeat and progress tracking
- frame_design.md — Detailed design for frame representation, parsing, and serialization
- handoff.md — 5-component handoff report
