# Host Rebuild And Background Task Plan

Last updated: 2026-03-22

## Status

Phase 2 recovery slice partially implemented; in-process detached runtime owner foundation landed, and a first same-process Android service host now bootstraps that owner. Full service ownership semantics are still pending.

## Implementation Progress

Implemented in code:

- queue restore no longer silently reruns in-flight work from the beginning; interrupted in-flight tasks are normalized into an explicit retry-required terminal state
- restore normalization now stamps task metadata with queue restore diagnostics, including restore time, previous lifecycle state, and recovery reason
- host/runtime snapshots now expose stable lifecycle diagnostics for the current process and host owner, plus per-run submission and recovery diagnostics where available
- append-only run journal storage now persists runtime events per run, and chat runtime replay prefers the durable journal over transcript heuristics when journal data exists
- journal-backed replay is no longer truncated by the old in-memory 24-event cap; the cap now applies only to legacy in-memory or transcript fallback paths
- approval-boundary prompt checkpoints are now durably persisted, including `waiting_approval`, `approved_pending_resume`, and `rejected_pending_resume`
- host approval actions now write durable resume checkpoints before asking the queue to resume, so host rebuild no longer depends solely on the in-memory approval registry
- runtime task creation now falls back to durable approval checkpoints when reconstructing prompt resume state and approved or rejected tool grants
- a first recovery planner now projects per-run recovery intent from queue state, checkpoints, journal tail, and managed-process presence into chat run snapshots
- app-layer restore now preprocesses persisted queue snapshots before `SessionQueue` restore, so approval-boundary recoveries can keep the same run non-terminal instead of falling straight into explicit-retry failure
- `approved_pending_resume` and `rejected_pending_resume` restore back to the same queued run when safe, and `waiting_approval` restores back to the same suspended run when safe
- session resume no longer spins the executor on approval-waiting runs that have no runnable queue work
- restore planning now prefers durable journal tail over `lastEvent` summary, so interruption classification is based on the append-only runtime history when available
- interrupted runs without a recoverable checkpoint now stay explicitly interrupted in planner output instead of hinting that an automatic rerun is expected
- runtime ownership is now split from the host facade inside the app process: an in-process runtime owner registry keeps `DefaultAgentSessionRuntimeManager`, journal/checkpoint stores, approval registry, and runtime callbacks alive even if `OpenCrayHostRuntime` is rebuilt
- runtime snapshots now expose both `hostLifecycle` and `runtimeOwnerLifecycle`, so host recreation can be distinguished from runtime-owner continuity in diagnostics and UI projection
- app startup and host access now both route through `OpenCrayAgentRuntimeService.ensureStarted(...)`, and the service eagerly bootstraps the shared in-process runtime owner on `onCreate()`
- the service host now hands `OpenCrayHostRuntime` a narrowed runtime-access/replay-access bundle instead of the raw owner, which keeps the next binder/service-lifetime slice from changing host wiring again
- this service slice is intentionally same-process and non-foreground for now: it establishes the owner host boundary without yet introducing binder-driven control flow, foreground keepalive, or scheduled wake-up semantics

Not yet implemented:

- detached runtime ownership via Android service with foreground keepalive / binder-driven control
- general prompt checkpoint store beyond the approval-boundary slice
- managed-process reconnect restore path
- generalized checkpoint-aware queue restore in `core` beyond the current app-layer approval-boundary rewrite

## Purpose

This document answers two concrete questions:

1. Why can a run fully restart even when the user did not intentionally "restart the app"?
2. How should OpenCray evolve so tasks can continue after the user leaves the page and later support scheduled/background execution?

This document complements `docs/runtime-checkpoint-and-detached-execution-design.md`. That document defines the recovery model. This document focuses on the current restart boundary, the code-backed investigation, and the phased architecture plan.

## Executive Summary

The current runtime is not page-bound, but it is still app-process-bound.

That distinction matters:

- a normal Flutter page rebuild does not directly recreate the run owner
- a host/app-process recreation does recreate the run owner
- when that happens, the core queue now normalizes in-flight work into an explicit interrupted state instead of blindly replaying it
- approval-boundary restores can already be rewritten at the app layer back into the same `QUEUED` or `SUSPENDED` run when a durable checkpoint proves that recovery is safe
- non-approval prompt boundaries still fall back to interruption because there is no general checkpoint yet

So the user-visible "run restarted" behavior is real. It is not only a UI illusion.

At the same time, the UI makes the problem look worse than it is, because expanded run history is still mostly reconstructed from in-memory runtime events plus partial transcript replay. After host recreation, that history becomes sparse even before the rerun begins.

## Code-Backed Findings

### 1. The run owner is process-scoped, not page-scoped

`OpenCrayHostRuntime` is a singleton in the Android app process.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayLocalRuntimeServer.kt`

Important facts:

- `OpenCrayHostRuntime.fromContext()` caches a single static `instance`
- only two code paths create or access that singleton: the Flutter platform bridge and the local runtime server
- `OpenCrayFlutterHostBridge` only attaches and detaches channels around that singleton; it does not own execution state

Conclusion:

- switching pages or rebuilding a widget tree is not enough to recreate the runtime owner
- recreating the app process is enough to recreate it

### 2. Flutter does not currently auto-resubmit chat prompts on reconnect

Relevant files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/core/bridge/opencray_platform_bridge.dart`
- `flutter_app/lib/core/bridge/opencray_host_bridge_bootstrap.dart`
- `flutter_app/lib/app/opencray_app.dart`

Confirmed behavior:

- `OpenCrayChatFeature.initState()` only does two things on attach:
  - `_hydrateFromHost()`
  - subscribe to `watchChatSnapshot()` and `watchChatRuntimeSnapshot()`
- actual prompt submission only happens in explicit user action paths:
  - `_sendCurrentState()`
  - redo path
- bridge bootstrap runs once in `main()`
- `OpenCrayApp` stores the chosen bridge in a `late final`, so the app does not hot-swap between platform bridge and local HTTP bridge during normal UI rebuilds

Conclusion:

- there is no evidence in current Flutter code of automatic resubmission caused only by page rebuild or event-stream resubscription

### 3. Queue restore is no longer blind replay, but general checkpointed recovery is still incomplete

Relevant files:

- `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

Current restore behavior in `core`:

- `RUNNING`
- `CANCEL_REQUESTED`
- `RETRY_PENDING`

are normalized into an explicit interrupted failure in `SessionQueue.normalizeAfterRestart()`.

Current restore behavior in `app`:

- approval-boundary checkpoints can rewrite a restored run back into the same `QUEUED` run when the user had already approved or rejected and the durable resume payload still exists
- approval-boundary checkpoints can rewrite a restored run back into the same `SUSPENDED` run when the task was waiting for approval
- managed-process reconnect and general prompt checkpoint recovery are still not implemented

Then, when a new `OpenCrayHostRuntime` is created, its initializer calls `ensureActiveSessionResumed()`, which:

- resumes the active session queue
- promotes idle supplements
- starts the next queued chat run if available

Conclusion:

- host recreation no longer blindly replays in-flight work at the queue layer
- approval-boundary runs can already recover into the same run identity when recovery is proven safe
- everything outside those safe boundaries still lacks a general checkpoint model, so interruption remains the default fallback

### 4. Expanded run history is still too memory-backed

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`

Current snapshot composition:

- live runtime events come from in-memory `runtimeEventsBySession`
- restore-time replay comes from transcript messages
- durable run record only stores `lastEvent` and `lastResult`

Current replay gaps:

- supplements are persisted
- progress events are persisted
- successful tool interactions are persisted
- failed tool results are not durably replayed
- denied/in-flight transitions are not durably replayed
- the full append-only event stream is not durably stored

Conclusion:

- after host recreation, the expanded run card loses most of the detail needed to explain what happened before the rerun
- that makes a genuine rerun feel even more abrupt

### 5. There is no detached execution owner yet

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayApplication.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`

Current state:

- the app starts the local loopback server on process startup
- execution is owned by in-process singletons and executors
- there is no Android `Service` owning the runtime
- there is no `WorkManager` or scheduler responsible for delayed/background tasks

Conclusion:

- tasks are currently coupled to the lifetime of the app process even though they are not coupled to a single screen

## What Can Explain "I Did Not Restart The App, But The Run Restarted"

Based on the current code, the following explanation is strong:

- the app process or host owner was recreated without the user consciously treating it as an app restart

Examples that fit that category:

- foreground crash followed by activity relaunch
- OEM or system memory reclaim
- runtime host crash while the visible activity is relaunched
- debugger or package replacement flow
- Flutter engine or app-process restart that looks like an in-place refresh to the user

What does not fit well:

- simple tab switch
- chat page rebuild
- event stream detach/reattach
- selecting a different screen inside the same app shell

What we cannot currently distinguish with confidence:

- true process recreation
- host singleton recreation
- Dart app restart
- observer reset with incomplete restore

The reason is simple: the code does not currently stamp runtime snapshots and submissions with stable lifecycle identifiers.

## Root Problem Statement

OpenCray is still task-durable, not execution-durable.

Today the system persists enough data to know that a task existed, but not enough data to resume the exact in-flight execution cursor safely after host loss.

That leads to two separate failures:

1. The queue restarts work from task input.
2. The UI cannot fully explain the recovery because the detailed event stream is not durably journaled.

## Design Requirements

### Primary requirements

- A normal UI rebuild must never be able to resubmit a prompt implicitly.
- Host recreation must be diagnosable with explicit lifecycle IDs.
- After host loss, a run must resume from the last safe checkpoint instead of whole-task replay whenever recovery is safe.
- If recovery is unsafe, the run must surface as `interrupted` rather than silently rerunning mutating work.
- Tasks must continue after the user leaves the page.

### Secondary requirements

- Support delayed or scheduled task triggers later.
- Keep the existing tool policy path intact.
- Preserve the current memory-first fast path for live execution.

The last point matters: live execution can still stay in memory. We do not need token-by-token persistence. We only need durable state at explicit recovery boundaries.

## Proposed Architecture

### 1. Split runtime ownership from host facade

Introduce a dedicated Android runtime owner:

- `AgentRuntimeService`

Responsibilities:

- own `DefaultAgentSessionRuntimeManager`
- own queue snapshot stores
- own run journal store
- own prompt checkpoint store
- own process registry and scheduled task registry
- continue active tasks without any attached Flutter page

`OpenCrayHostRuntime` should become a facade/controller:

- UI-facing snapshots
- bridge wiring
- user actions
- binding to the service-owned runtime state

Result:

- UI disconnect no longer implies execution owner loss
- future background execution has a clear home

### 2. Add explicit lifecycle and recovery identifiers

Before changing recovery behavior, add durable diagnostics.

Required fields:

- `processStartId`
- `hostInstanceId`
- `runtimeOwnerId`
- `flutterAppInstanceId`
- `bridgeInstanceId`
- `runAttempt`
- `submissionSource`
- `recoveryReason`
- `recoveredFromCheckpointId`
- `queueRestoreEpochMs`

Where to emit them:

- run submission metadata
- run lifecycle journal events
- runtime snapshot payload
- debug page

Result:

- we can finally answer whether a reported restart came from UI reconnect, Flutter restart, host rebuild, or true process recreation

### 3. Introduce a durable append-only run journal

Add:

- `RunEventJournalStore`

Persist every meaningful runtime fact:

- lifecycle transitions
- progress
- supplement
- approval required
- approval approved
- approval rejected
- tool call
- tool result, including denied and failed outcomes
- cancellation
- final assistant message
- background wake and resume events
- scheduled trigger events

The journal becomes the source of truth for the expanded run card.

`PersistedAgentRunRecord.lastEvent` can remain as a summary index, but not as the history source.

### 4. Introduce general prompt checkpoints

Add:

- `PromptCheckpointStore`

Checkpoint only at safe recovery boundaries:

- before next model request
- after model response is parsed into executable actions
- after each completed tool result is committed
- at approval wait
- at safe suspension points

Do not automatically replay unknown mutating boundaries.

Recovery policy:

- `before_model_request`: safe auto-resume
- `awaiting_approval`: safe resume
- `after_committed_tool_result`: safe continue
- `in_flight_readonly_tool`: optionally replay only if tool declares replay-safe semantics
- `in_flight_mutating_tool_unknown_commit`: mark interrupted and require operator or agent recovery

This keeps the design aligned with `ToolPolicyPipeline`: tool policy stays centralized, while runtime recovery consumes standardized tool metadata instead of inventing handler-local rules.

### 5. Move queued execution to the service

After the service exists:

- UI submits tasks to the service-owned runtime
- service runs even if Flutter page detaches
- UI only observes snapshots and sends commands

Queue restore changes:

- `RUNNING` should no longer immediately normalize to blind replay
- restore should first check for checkpoint/journal recovery
- if checkpoint recovery is possible, restore as `RECOVERING`
- if only unsafe replay is possible, restore as `INTERRUPTED`
- only explicit user or policy-approved recovery may requeue unsafe work

### 6. Add scheduled and delayed task triggering

Add two persistent models:

- `ScheduledTaskSpec`
- `ScheduledTaskRunRecord`

Recommended trigger support in first phase:

- immediate detached execution
- run at timestamp
- run after delay

Use `WorkManager` for durable scheduling and wake-up.

Recommended split:

- `WorkManager` owns persistence, wake-up, and restart after process death
- `AgentRuntimeService` owns actual long-running execution

Worker behavior:

- load scheduled task spec
- bind or start `AgentRuntimeService`
- hand off the task
- write trigger journal event

This keeps scheduled execution durable without forcing the worker itself to own a long-running agent loop.

### 7. Add runtime states that make failure explicit

New user-visible run states:

- `running`
- `recovering`
- `awaiting_approval`
- `interrupted`
- `scheduled`
- `waiting_for_trigger`

Why this matters:

- today the system often hides an interruption by replaying from the beginning
- a visible `recovering` or `interrupted` state is more honest and easier to debug

## Phased Plan

### Phase 0: Diagnostics first

Goal:

- prove which lifecycle boundary is actually causing the user's observed restarts

Work:

- add lifecycle IDs to runtime owner, bridge, and Flutter app instance
- stamp run attempts and recovery reasons into metadata
- expose the current IDs in the debug page
- append lifecycle events into a small journal even before full checkpoint work lands

Exit criteria:

- after the next reproduced restart, we can say exactly whether the run was restarted by process recreation, host recreation, Flutter restart, or explicit resubmission

### Phase 1: Durable history before recovery changes

Goal:

- stop losing expanded run history on restore

Work:

- add `RunEventJournalStore`
- backfill chat runtime snapshot from journal instead of only `runtimeEventsBySession`
- persist failed and denied tool results too

Exit criteria:

- expanded run card remains intelligible after host recreation even if the run still cannot fully resume

### Phase 2: General checkpointed prompt recovery

Goal:

- stop whole-task replay for safe boundaries

Work:

- add `PromptCheckpointStore`
- generalize approval resume to prompt resume
- add `RECOVERING` and `INTERRUPTED` run states
- replace `normalizeAfterRestart()` blind replay path with checkpoint-aware restore

Exit criteria:

- safe recoveries continue from checkpoint
- unsafe recoveries stop in `INTERRUPTED` instead of silently restarting the whole run

### Phase 3: Service-owned detached runtime

Goal:

- let tasks outlive the visible page

Work:

- introduce `AgentRuntimeService`
- move `DefaultAgentSessionRuntimeManager` ownership into the service
- make `OpenCrayHostRuntime` a facade
- rebind Flutter/UI snapshots to the service owner

Exit criteria:

- user can leave the chat page and active tasks still progress

### Phase 4: Scheduled and background task execution

Goal:

- enable delayed and future periodic task orchestration

Work:

- add `ScheduledTaskSpec` persistence
- add `WorkManager` wake-up path
- hand off scheduled triggers into `AgentRuntimeService`
- add background journal events and notification policy

Exit criteria:

- delayed tasks fire without a visible page
- triggered runs are observable when the user returns

## Recommended Near-Term Order

The safest build order is:

1. diagnostics
2. durable run journal
3. checkpoint-aware recovery
4. service-owned detached runtime
5. scheduled/background triggers

Do not start with `WorkManager` first. If we do that before solving run recovery and journaling, we will only create a more durable version of the current blind rerun behavior.

## Concrete Answer To The Original Question

Why can a run restart when the user did not intentionally restart the app?

Because the system currently treats host/app-process recreation as a queue restore problem, not as an execution continuation problem. Once that recreation happens, non-terminal tasks are normalized back to `QUEUED`, and the restored host resumes them from the beginning.

Why does this still happen even if the UI page was not intentionally closed?

Because the current execution owner lives in the app process, not in the page. A page staying visible does not prove that the host owner was preserved.

What should we do?

- first make the boundary observable with lifecycle IDs
- then persist full run history
- then add checkpoint-aware recovery
- then move execution ownership into a detached service
- finally add `WorkManager`-based scheduling on top

That is the path that fixes the current restart bug and also gives OpenCray a solid base for future keepalive and timed tasks.
