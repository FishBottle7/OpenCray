# OpenCray Runtime Parity Roadmap

## Goal

Bring OpenCray's agent runtime behavior closer to OpenClaw's runtime behavior.

This roadmap is intentionally runtime-first, not UI-first. The priority is:

1. Make approvals a real suspended run state.
2. Make session/transcript ownership explicit and durable.
3. Make process execution a managed runtime, not a one-shot helper.
4. Centralize tool policy and context compaction.
5. Add subagent lifecycle and reduced-context spawning.

The target is not to copy OpenClaw's stack or prompt wording. The target is to copy the runtime patterns:

- stable session ownership
- append-only transcript discipline
- explicit prompt/context layers
- suspended approvals with resume
- managed process runtime
- tool policy pipeline
- subagent registry and reduced-context spawn

## Current Baseline

OpenCray already has some of the right shape:

- serial session queue and task loop
- layered prompt assembly
- Claude-style local file tools
- approval and high-risk approval semantics
- host-side pending approval cards

But key gaps remain:

- approval is still modeled as a denied result plus retry
- transcript ownership is still mostly host/session-context driven, not append-only runtime-owned
- command/python execution is still one-shot
- tool gating is still largely tool-local
- context compaction is bounded-window only
- there is no subagent runtime

## Implementation Order

### P0. Approval Suspend/Resume

Objective:
Replace `FAILED + requestRetry()` semantics for approval-required actions with an explicit suspended queue state and explicit resume path.

Files:

- `core/src/main/kotlin/com/opencray/core/contracts/AgentContracts.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`
- `core/src/main/kotlin/com/opencray/core/orchestrator/AgentLoop.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

Required behavior:

- queue has an explicit suspended lifecycle state for approval waits
- runtime can request suspension through execution hooks
- approval-required tool results suspend the task instead of ending as a normal failed queue entry
- host approval resumes the suspended task instead of retrying a failed task
- restart recovery preserves suspended approval tasks

Acceptance:

- approval-required tasks appear as suspended, not failed
- approving resumes the same task id
- rejecting clears pending approval without requeueing the task
- queue restore after restart keeps approval-required tasks suspended

Status:

- Completed: queue suspension, explicit resume path, host approval resume flow, and regression tests are in place.

### P1. Session Store And Transcript Discipline

Objective:
Move from transient session-context replay toward runtime-owned transcript append and bounded replay discipline.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/session/`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/ChatRuntimeSessionContextFactory.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`

Required behavior:

- transcript append is explicit and deterministic
- tool call and tool result summaries can be persisted separately from UI bubbles
- replay uses transcript windows from a runtime/session store, not ad hoc UI-visible chat state only
- transcript repair and pruning rules are testable

Acceptance:

- replay source is inspectable and bounded
- transcript append order is deterministic
- transcript restore after restart is stable

Dependencies:

- P0 complete

Status:

- In progress: per-session runtime transcript ownership is in place, approval rejection and user-initiated run cancellation are recorded as replay-visible tool observations, transcript state is persisted under the session runtime directory for restart restore, successful tool call/result summaries are now appended into the durable runtime transcript instead of living only in UI/runtime event history, replay pruning is now category-aware so recent write/mutation summaries and recent discovery/search conclusions are kept ahead of older low-value tool noise without dropping user/assistant turns or terminal tool outcomes, restored failed runs are now backfilled into replay as `run_interrupted` / `retry_abandoned` notes when needed, a first explicit `ContextManager` now owns transcript windowing, prompt-local pruning, prompt-time compaction, and final prompt-space allocation before `PromptAssembler` renders the final prompt layers, and per-session run records are now also durable so run submissions, final results, and the latest runtime event survive handle release or app restart instead of only living in memory.
- Boundary note: memory retrieval/ranking belongs in `runtime/memory`, and effective soul resolution belongs in `runtime/soul`. `ContextManager` may enforce final bounded allocation pressure on already-resolved inputs, but it should not become the semantic selector for which soul or memory content exists in the first place.

### P2. Managed Process Runtime

Objective:
Replace one-shot `command_exec` / `python_exec` thinking with managed process sessions.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/process/`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`

Required behavior:

- background process/session registry
- optional interactive/PTY execution path
- poll, cancel, timeout, cleanup
- python execution aligned with the same process/runtime policy model

Acceptance:

- process lifecycle is inspectable
- long-running commands no longer force one-shot behavior
- cancellation and timeout are stable

Dependencies:

- P0 complete

Status:

- In progress: session-scoped managed process registries are now wired into the app runtime, the dispatcher exposes `ProcessStart` / `ProcessList` / `ProcessRead` / `ProcessWait` / `ProcessTerminate`, `ProcessStart` now supports both raw commands and managed Python script launches through `script_path`, prompt guidance nudges the model toward the managed-process flow for long-running commands and Python work, runtime tests now cover both dispatcher-level behavior and multi-turn agent usage of the new process tools, managed process snapshots are now persisted per session so terminal state survives restart while orphaned restored `RUNNING` records are repaired into an explicit interrupted failure state, idle-session release now keeps sessions alive while they still own live managed processes, deleting a chat session now terminates that session's live managed processes before releasing runtime ownership, and run snapshots now retain managed-process linkage so a restored completed run can still report whether it owns live background work.

### P3. Tool Policy Pipeline

Objective:
Unify path policy, approval policy, and result guarding into one runtime pipeline.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/policy/`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `policy/src/main/kotlin/com/opencray/policy/ModePolicy.kt`

Required behavior:

- input normalization
- workspace/path policy
- standard vs high-risk approval classification
- execution permission handoff
- post-tool result guarding and metadata normalization

Acceptance:

- policy decisions are consistent across file tools, command tools, and python
- approval metadata is emitted once through a single pipeline
- policy tests stop relying on per-tool quirks

Dependencies:

- P0 complete
- P2 strongly recommended first

### P4. Context Manager And Compaction

Objective:
Introduce a dedicated context manager instead of relying only on transcript window truncation.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/context/ContextManager.kt`
- new: `runtime/src/main/kotlin/com/opencray/runtime/context/CompactionPolicy.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

Required behavior:

- compaction policy is explicit
- tool results can be summarized before replay
- layer inclusion remains inspectable
- loop detection and protocol recovery stay deterministic

Acceptance:

- large transcripts are compacted predictably
- engineers can inspect which prompt/context layers were injected
- bounded replay no longer depends only on raw message clipping

Dependencies:

- P1 complete

Status:

- In progress: a dedicated `ContextManager` now owns prompt budgeting, transcript windowing, and prompt-local compaction/pruning, prompt-time compaction emits an explicit omitted-history summary layer, and prompt-local pruning now rewrites oversized tool payloads, collapses attachment-like blobs, and removes consecutive duplicate background noise before transcript windowing.
- Boundary note: this slice should stop at allocation and compaction. Memory recall policy, soul overlay policy, and other source-specific selection rules stay outside `ContextManager`.

### P5. Subagent Runtime

Objective:
Add subagent spawning, reduced-context execution, lifecycle tracking, and result return.

Files:

- new: `runtime/src/main/kotlin/com/opencray/runtime/subagent/`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

Required behavior:

- child run/session registry
- reduced-context prompt mode
- lifecycle events for child runs
- bounded spawn depth
- parent-visible result routing

Acceptance:

- parent can spawn child work and observe completion
- child context is reduced and deterministic
- restart recovery preserves registry state

Dependencies:

- P0 through P4 complete or mostly stable

## Immediate Work Queue

The next concrete execution order is:

1. Add run/process settling and repair rules on top of durable run records plus managed-process state.
2. Decide when a terminal run with only interrupted-restored processes should collapse into a fully settled replay-visible outcome.
3. Purge or compact session runtime artifacts consistently on session deletion once retention semantics are finalized.
4. After managed-process/run durability is stable, move into P3 tool policy pipeline unification.

## Non-Goals For This Slice

These are intentionally deferred:

- full OpenClaw-style PTY/process registry
- subagent runtime
- full transcript compaction
- MCP remote tool bridge
- web tools parity

## Notes

- Keep compatibility where practical. The first suspension slice can preserve existing approval-required error codes while changing queue lifecycle from failed to suspended.
- Do not let UI own transcript replay rules.
- Do not let approval state remain a host-only illusion. The queue/runtime must know a task is suspended.
