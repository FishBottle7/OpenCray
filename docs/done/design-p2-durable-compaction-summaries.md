# Design: P2 Durable Compaction Summaries

Date: 2026-03-16

## Goal

Implement the next session-history lifecycle step from `docs/context-management-design.md`:

- persist session-level summaries for older transcript slices
- shrink the replay transcript without dropping decision continuity
- keep durable compaction outside `ContextManager`
- make compaction visible in runtime metadata and host/local run snapshots

## Implemented shape

The durable compaction path now runs during prompt-task session preparation, after pre-compaction memory flush and before prompt assembly:

1. session transcript is seeded and the current user input is appended
2. pre-compaction memory flush gets the first chance to preserve durable memory candidates
3. durable compaction evaluates the current transcript against the runtime transcript window policy
4. if enough older messages would fall out of the active tail, those omitted messages are summarized into a durable compaction entry
5. the summary entry is appended to a separate per-session compaction store
6. the runtime transcript is rewritten to the retained tail through `SessionTranscriptStore.replace(...)`
7. later prompt assembly receives the trimmed transcript plus a dedicated `Durable Compaction` context layer
8. compaction trace is projected into runtime result metadata and host/local run snapshots as structured `durableCompaction` data

## Boundary decisions

- `ContextManager` does not decide when durable compaction runs
- `ContextManager` only passes through the already-prepared durable compaction text and trace into prompt/report models
- durable compaction policy, rendering, and persistence live under `runtime/compaction/*`
- `AppAgentSessionTaskRuntimeFactory` only wires the compaction stage into session preparation, after flush and before final transcript snapshot
- host/local snapshot projection stays in `OpenCrayHostRuntime`

## What shipped

- `DurableCompactionCoordinator`, `DurableCompactionPolicy`, `DurableCompactionTrace`, and bounded renderer updates
- a file-backed `SessionCompactionStore` in `runtime/compaction/*` plus app-side per-session factory wiring
- `SessionTranscriptStore.replace(...)` integration as the transcript rewrite mechanism
- session-context wiring in `AppAgentSessionTaskRuntimeFactory`
- dedicated `Durable Compaction` prompt layer in prompt assembly
- runtime metadata projection for compaction lifecycle, retained/source transcript counts, summary counts, and latest compaction timestamp
- host/local run snapshot projection as structured `durableCompaction`
- focused tests for:
  - transcript replace semantics
  - durable compaction coordinator behavior
  - file-backed compaction-store persistence
  - factory wiring and transcript trimming
  - runtime metadata projection
  - host/local snapshot projection

## Intentionally not included

- a background compaction worker or separate durable compaction task
- per-entry snapshot projection of every stored compaction summary
- bootstrap-file compaction or bootstrap-aware context trace
- any change to `ContextManager` budget-selection ownership

## Next recommended step

Move to the remaining phase-2 context sources and trace depth:

1. bootstrap context files
2. subagent context modes
3. broader full-context trace beyond the current memory/skill/flush/compaction surfaces
