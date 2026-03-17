# Design: P2 Pre-Compaction Memory Flush

Date: 2026-03-16

## Goal

Implement the next OpenClaw-aligned memory-pressure step from `docs/context-management-design.md`:

- detect transcript/context pressure before older turns fall out of the active window
- preserve durable notes through the existing structured memory pipeline
- keep `ContextManager` as allocator only
- make flush behavior traceable and prevent repeated flushes against the same omitted window or the same already-flushed candidate set

## Implemented shape

The pre-compaction flush path now runs before prompt assembly finishes for prompt tasks:

1. session transcript is seeded and the current user input is appended
2. memory flush reuses prompt-local pruning plus transcript window selection to inspect the would-be omitted history
3. if the omitted history crosses the flush threshold, runtime memory logic extracts durable candidates from that omitted slice
4. candidates are written through the existing structured memory writer
5. if new records were written, the factory reloads memory records so the same run can immediately recall or tool-read the refreshed memory state
6. flush metadata is projected into runtime result metadata and host/local run snapshots as structured `memoryFlush` data

## Boundary decisions

- `ContextManager` does not decide whether flush happens
- memory flush policy and evidence extraction live under `runtime/memory/*`
- `AppAgentSessionTaskRuntimeFactory` only wires the flush stage into session preparation and reloads fresh memory records afterward
- host/local snapshot projection stays in `OpenCrayHostRuntime`

## What shipped

- `MemoryFlushPolicy`, `MemoryFlushCoordinator`, and structured `MemoryFlushTrace`
- app-level `ChatMemoryIngestionCoordinator.flushBeforeCompaction(...)`
- pre-run flush wiring in `AppAgentSessionTaskRuntimeFactory`
- same-run memory record refresh after successful flush writes
- omitted-window signature dedupe plus stable candidate-id dedupe so transcript growth without new durable facts does not keep rewriting the same records
- runtime metadata projection for flush outcome, omitted counts, candidate counts, and written record ids/kinds
- host/local run snapshot projection as structured `memoryFlush`
- focused tests for:
  - flush threshold and one-signature dedupe
  - factory wiring and same-run recall refresh
  - runtime metadata projection
  - host/local snapshot projection

## Intentionally not included

- a separate durable compaction worker or compaction task
- a dedicated runtime event stream entry for memory flush
- transcript rewriting or session-level compaction summaries
- any change to `ContextManager` prompt-budget responsibilities

## Next recommended step

Move to the still-missing durable history lifecycle:

1. durable compaction summaries
2. bootstrap context files
3. deeper full-context trace for compaction-era artifacts
