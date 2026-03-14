# P0 Context Management Fix Plan

Last updated: 2026-03-13

## Status

Completed and verified

This plan has been fully implemented and passed high-precision review plus local verification.

## Why this document exists

The first pass of the context-management refactor landed two useful pieces:

- named prompt layers
- persistent queue snapshot wiring

It still left several P0 gaps open.

This document records the concrete repair scope before continuing with Phase 1+ work.

## P0 work already landed

- `PromptAssembler` owns named prompt layers instead of `AppShellActivity` string concatenation.
- `ChatRuntimeSessionContextFactory` rebuilds stored chat transcript into runtime-visible context.
- `TranscriptWindowBuilder` applies a bounded message window.
- `AgentQueueSnapshotStoreFactory` switched the live path from in-memory queue snapshots to `SessionStoreQueueSnapshotStore`.
- runtime metadata now carries minimal context assembly counts.

## P0 repair scope

These items must be fixed before memory, skill capsules, or bootstrap context continue.

### 1. Session-owned runtime

Problem:

- `AppShellActivity` still creates runtime, loop, and drain flow per submit.

Repair:

- introduce `AgentSessionRuntimeManager`
- introduce `AgentSessionHandle`
- move submit / cancel / retry / resume / processing ownership out of `AppShellActivity`

### 2. In-flight submit correctness

Problem:

- submitting while a session is already running does not enqueue a real task
- the UI transcript shows a waiting state, but the queue does not own that work

Repair:

- every submit must create a real `AgentTask`
- every submit must enqueue through the session handle

### 3. Restore path

Problem:

- queue persistence exists, but opening a session does not restore and resume through a stable owner path

Repair:

- session open/select must route through the same manager path used by submit
- restored queued work must become visible and resumable from that same owner

### 4. Host-only transcript pollution

Problem:

- host-only temporary messages can leak into runtime context

Repair:

- define a strict filter for runtime-visible transcript entries
- keep system/session policy injection separate from dynamic task context

### 5. Queue identity safety

Problem:

- queue directory naming must be collision-free for chat session ids

Repair:

- replace lossy path sanitization with reversible or collision-safe encoding

### 6. Trace and budgeting accuracy

Problem:

- failure paths lose context trace detail
- report counts only cover the bounded window, not the full source size
- windowing is still too naive around tool-heavy turns

Repair:

- preserve context report on failure
- separate total transcript size from window size
- make transcript windowing prefer recent human turns over bulky tool noise

## Execution order

1. Introduce session runtime manager and handle.
2. Route submit/select/open through the manager.
3. Remove fake in-flight transcript states and enqueue all real work.
4. Tighten transcript-to-runtime filtering and prompt layer boundaries.
5. Fix queue session id mapping and trace/budget reporting.
6. Extend tests around restore, queued submit, and prompt hygiene.

## Explicit non-goals for this repair pass

- memory recall / write
- skill capsule activation
- bootstrap file injection
- compaction summaries
- sub-agent context modes

Those remain after the P0 repair pass is stable.
