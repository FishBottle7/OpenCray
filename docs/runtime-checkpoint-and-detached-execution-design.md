# Runtime Checkpoint And Detached Execution Design

Last updated: 2026-03-20

## Status

Design proposal

## Purpose

This document defines how OpenCray should stop restarting whole runs after local host resets and move toward a detached execution model that is not coupled to the chat UI lifecycle.

It focuses on four concrete goals:

1. Do not restart a prompt run from turn 0 just because the local host was recreated.
2. Keep expanded run history visible after reconnect or restore.
3. Make the runtime continue without an attached UI.
4. Prepare the Android app for future task keepalive and background execution.

## User-Visible Problem

The current user-facing failure mode is not "it looks like a retry". It is a real run restart:

- the expanded in-progress run card loses its prior step history
- the tool flow replays from the beginning
- the model redoes the same early steps instead of continuing from the last safe boundary

This feels worse than Codex or Claude Code because those products typically keep the agent host alive independently from the UI connection and do not rebuild the whole local execution context on reconnect.

## Current Code-Backed Diagnosis

### 1. Runtime ownership is app-process scoped, not UI scoped, but still local-host scoped

`OpenCrayHostRuntime` is a singleton in the Android app process.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`

This means simple page switches are usually fine, but any host-process recreation still destroys live in-memory runtime state.

### 2. Expanded run history is mostly memory-backed

The run activity panel is built from:

- in-memory `runtimeEventsBySession`
- replayed transcript messages
- queue/run snapshots

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`

This has two important consequences:

- if the host is recreated, the in-memory event list disappears immediately
- replay can only reconstruct what was durably written to transcript or run record storage

### 3. Durable run storage only keeps the last event and last result

`PersistedAgentRunRecord` stores:

- `lastEvent`
- `lastResult`

It does not store the full append-only run event stream.

Relevant file:

- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`

This is why the expanded card can collapse to almost nothing after restore.

### 4. Transcript replay does not persist every runtime event

Today transcript replay writes:

- supplements
- progress events
- successful tool interactions

It does not durably record every failed, denied, or in-flight tool transition.

Relevant file:

- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`

This creates a visibility gap exactly in the failure and interruption paths where users most need continuity.

### 5. Queue restore normalizes running work back to QUEUED

On restore, queue states:

- `RUNNING`
- `CANCEL_REQUESTED`
- `RETRY_PENDING`

are normalized back to `QUEUED`.

Relevant file:

- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`

That behavior is safe for task-level durability, but it guarantees full task rerun when there is no finer-grained checkpoint.

### 6. Prompt resume is currently special-cased for approval waits

We already added a real approval resume path that can continue from a saved action cursor inside one prompt turn.

Relevant files:

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayPromptResumeState.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`

But this still only covers the approval path. It is not yet a general prompt checkpoint system.

## Root Cause Summary

OpenCray is currently durable at the task level, not at the prompt-execution level.

That means:

- queue state survives
- basic run identity survives
- some transcript state survives
- full in-progress execution state does not survive

So when the host loses the live runtime object, the system falls back to the only thing it knows how to recover safely: requeue the task.

## Design Goals

### Primary goals

- A prompt run must resume from the last safe checkpoint, not from task input, after host recreation.
- Expanded run history must survive host recreation.
- The runtime must continue when the UI disconnects.
- Approval, progress, and tool execution must share one recovery model.

### Secondary goals

- Support Android background keepalive for active tasks.
- Prepare for managed process continuation and reconnect.
- Make run recovery reasons explicit in UI and logs.

## Non-Goals

- Do not attempt magical recovery of unknown external side effects.
- Do not guarantee that every mutating tool can be replayed blindly after a crash.
- Do not move execution to a remote server in this phase.
- Do not replace the existing `ToolPolicyPipeline`.

## Design Principles

- Recover from the smallest safe boundary possible.
- Separate runtime ownership from UI ownership.
- Persist execution facts before using them for UI.
- Never silently rerun a mutating action whose commit state is unknown.
- When automatic continuation is unsafe, surface an interrupted-recovery state instead of replaying the whole run.

## Proposed Architecture

## 1. Detached Runtime Owner

Introduce a dedicated Android runtime host component:

- `AgentRuntimeService`

Recommended behavior:

- started service when there is active work
- bound service while UI is attached
- foreground service when a task is expected to outlive the foreground UI

Responsibilities:

- own `DefaultAgentSessionRuntimeManager`
- own queue snapshots, run journal, prompt checkpoints, process registries
- continue tasks without any active Flutter screen
- expose observable runtime snapshots back to UI

`OpenCrayHostRuntime` should become a controller/facade over the service, not the execution owner itself.

## 2. Durable Run Journal

Add an append-only per-run event journal.

Proposed new store:

- `RunEventJournalStore`

Each replayable runtime event should be durably appended:

- lifecycle
- tool_call
- tool_result
- progress
- supplement
- approval required
- approval approved
- approval rejected
- cancellation
- assistant final
- subagent events
- memory events

This journal becomes the source of truth for the expanded run card.

The current `PersistedAgentRunRecord.lastEvent` can stay as a summary index, but the UI should reconstruct detailed history from the journal, not from volatile memory.

## 3. General Prompt Checkpoint Store

Generalize the approval-only resume state into a broader prompt checkpoint model.

Proposed new store:

- `PromptCheckpointStore`

Proposed durable record shape:

```kotlin
data class PromptCheckpoint(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val checkpointId: String,
  val state: PromptRecoveryState,
  val transcript: List<RuntimeConversationMessage>,
  val turnIndex: Int,
  val toolCallCount: Int,
  val activeSkillName: String?,
  val activeSkillActivationSource: String?,
  val pendingActions: List<OpenCraySerializableModelAction>,
  val nextActionIndex: Int,
  val requiresSingleActionReminder: Boolean,
  val inFlightTool: OpenCraySerializableToolCall?,
  val inFlightToolDispatchState: InFlightToolDispatchState?,
  val updatedAtEpochMs: Long,
)
```

`OpenCrayPromptResumeState` should become the runtime payload derived from this durable checkpoint, not the only persistence object.

## 4. Safe Recovery Boundaries

The runtime should checkpoint at explicit boundaries.

### Boundary A: Before a model request

Checkpoint contains:

- current transcript
- current turn
- no pending actions

Restore result:

- safe to request the next model turn again

### Boundary B: After parsing a model action batch

Checkpoint contains:

- parsed ordered actions
- `nextActionIndex`
- whether the current action already emitted a `tool_call`

Restore result:

- continue inside the same turn

### Boundary C: After progress emission

Checkpoint contains:

- progress already committed into transcript and journal
- next action index advanced

Restore result:

- continue from the next action

### Boundary D: After tool result is durably committed

Checkpoint contains:

- tool result committed into transcript and journal
- next action index advanced

Restore result:

- continue from the next action or next turn

### Boundary E: During in-flight tool execution

This is the hardest boundary.

We need explicit recovery semantics by tool class:

- read-only and idempotent tools: safe to retry current action
- reconnectable managed processes: reconnect if process registry says the process still exists
- mutating tools with unknown commit status: do not auto-rerun silently

For uncertain mutating tools, restore should produce:

- `INTERRUPTED_UNCERTAIN_ACTION`

This state tells the user and the model:

- the run itself is not restarted
- the current action needs recovery or confirmation
- prior run history remains intact

## 5. Recovery Planner

Add a planner that decides what to do on host or service restore.

Proposed component:

- `RunRecoveryPlanner`

Inputs:

- queue snapshot
- run journal
- prompt checkpoint
- process registry state
- approval registry state

Outputs:

- `resume_from_checkpoint`
- `resume_waiting_for_approval`
- `resume_reconnect_process`
- `interrupt_recovery_required`
- `restart_from_task_input` only as legacy fallback

The critical change is this:

full task restart must become the last-resort fallback, not the default restore behavior.

## 6. Extended Task Lifecycle

The queue lifecycle needs an intermediate recovery model.

Current queue states are not expressive enough because `RUNNING -> QUEUED` hides whether we had a recoverable checkpoint.

Recommended additions:

- `RECOVERY_PENDING`
- `INTERRUPTED_RECOVERABLE`
- `INTERRUPTED_UNCERTAIN`

Recommended restore behavior:

- `RUNNING` with valid prompt checkpoint -> `RECOVERY_PENDING`
- `RUNNING` with reconnectable managed process -> `RECOVERY_PENDING`
- `RUNNING` with unknown mutating in-flight action -> `INTERRUPTED_UNCERTAIN`
- no checkpoint and no recovery info -> legacy `QUEUED`

## 7. Managed Process Keepalive

Managed processes already have a durable registry. The new runtime host should use that as a first-class recovery input.

Rules:

- if the process is still live, reconnect and keep the same run active
- if the process reached terminal success and result can be harvested, commit result and continue
- if the process is terminal interrupted, mark the run as interrupted instead of replaying the whole task

This is especially important for:

- Bash
- ProcessStart / ProcessWait / ProcessRead
- managed Python execution

## 8. UI Contract

The UI should stop assuming that "missing live event list" means "fresh run".

UI requirements:

- load active run state from service snapshot
- hydrate detailed history from durable run journal
- show explicit recovery badges:
  - resuming
  - waiting for approval
  - reconnecting process
  - interrupted and needs recovery
- preserve expanded-card history across reconnect

The UI must never infer a fresh run solely from the absence of live memory events.

## Recommended Delivery Plan

## Phase 1: Durable continuity inside current process

Goal:

- stop losing expanded run history
- stop full task rerun when a checkpoint exists

Work:

- add append-only run journal
- persist all replayable runtime events
- add general prompt checkpoint store
- restore from prompt checkpoint before falling back to `QUEUED`

Expected result:

- even if the host is recreated, the run card history survives
- prompt tasks can resume from the last safe checkpoint

## Phase 2: Service-owned runtime

Goal:

- execution survives UI detachment

Work:

- introduce `AgentRuntimeService`
- move runtime manager ownership into the service
- keep `OpenCrayHostRuntime` as facade and state adapter
- move runtime observers to service-backed subscriptions

Expected result:

- closing or rebuilding the chat screen does not affect the running task

## Phase 3: Background keepalive

Goal:

- long-running tasks continue when the app is backgrounded

Work:

- promote service to foreground while active work exists
- add notification and resume/cancel actions
- define idle shutdown timeout

Expected result:

- active tasks are not tied to the visible UI

## Phase 4: Strong recovery semantics for mutating actions

Goal:

- avoid unsafe silent reruns of mutating actions

Work:

- classify tools by recoverability
- add per-tool recovery strategy metadata
- add interrupted-recovery UI states

Expected result:

- no full run replay for uncertain mid-tool interruptions
- no silent duplicate write/process/network mutation

## Module Impact

### `core/`

- `SessionQueue`
- queue snapshot model
- task lifecycle model

### `runtime/`

- `OpenCrayAgentRuntime`
- generalized checkpoint emission
- recovery boundary hooks

### `app/`

- `OpenCrayHostRuntime`
- `DefaultAgentSessionRuntimeManager`
- new service host
- run journal persistence
- restored runtime activity projection

### `persistence/`

- new durable stores for event journal and prompt checkpoints

### `flutter_app/`

- runtime activity snapshot rendering
- recovery badges and keepalive states

## Recommended First Implementation Slice

The first slice should not start with Android service work.

It should start with:

1. append-only run journal
2. general prompt checkpoints after every safe action boundary
3. queue restore using checkpoint-based recovery instead of unconditional `RUNNING -> QUEUED`

Why this comes first:

- it directly solves the current "run restarted" bug
- it improves correctness even before background keepalive exists
- it gives the service phase a solid persistence model

## Open Questions

1. Should the runtime service stay in the main app process first, or move directly to a dedicated `:runtime` process?
2. Which mutating tools are safe to auto-replay if a crash happens after `tool_call` but before durable `tool_result` commit?
3. How should interrupted-uncertain recovery be presented in the UI so users do not confuse it with a failure or restart?
4. Should service-backed runtime snapshots remain pull-based over the local bridge, or switch to a stronger subscription channel?

## Recommendation

Recommended path:

- Phase 1 first
- Phase 2 immediately after Phase 1 stabilizes
- Phase 3 only after service ownership is stable
- Phase 4 incrementally by tool class

This is the smallest path that gives OpenCray the "Codex-like" feel the product needs:

- no whole-run replay after local host reset
- no loss of expanded run history
- no dependence on an open UI page for task execution
