# Chat Single Live Task Design

Last updated: 2026-03-20

## Status

Design proposal

## Purpose

This document defines the task-lifecycle contract the chat product should follow:

1. In one chat session, one explicit user prompt creates one live task.
2. Before the agent emits its terminal final reply, that task must not be silently restarted, replaced, or split into a new task because of UI lifecycle, session switching, approval handling, or follow-up user input.
3. Additional user input, approval actions, and UI attach/detach should all be routed into the same live task.

This document complements:

- `docs/host-rebuild-and-background-task-plan.md`
- `docs/runtime-checkpoint-and-detached-execution-design.md`

Those documents focus on restore, journaling, and detached execution. This document defines the stricter product contract the runtime should serve.

## Product Contract

### Core rule

Within a single chat session, there may be at most one non-terminal live task at a time.

That task remains the owner of the session conversation until one of these terminal conditions happens:

- the agent emits a terminal final reply and there is no buffered user input left to consume
- the user explicitly cancels the task
- the user explicitly deletes or recalls the pending turn in a way that cancels the task
- the software process truly exits or the app is explicitly restarted
- the runtime enters an explicit interrupted state because safe continuation is impossible

### Required user-visible behavior

If a task is already active in a session:

- sending more user text must not create a new task
- approving a pending approval must not create a new task
- rejecting a pending approval must not cancel the task and create a replacement task
- switching to another session must not stop, restart, or replace the task
- switching back to the session must not auto-create a new task
- leaving the chat page must not stop, restart, or replace the task

In short:

- one session
- one live task
- one task owner until terminal completion

## Definitions

### Session

A persistent chat thread with transcript, runtime state, and UI selection state.

### Live task

The current non-terminal runtime task bound to a session. A live task may be:

- running
- waiting for tool completion
- waiting for approval
- waiting for buffered user input to be merged
- temporarily interrupted but still logically the same task

### Buffered input

User input received while a live task is still active. Buffered input belongs to the same task and must not be promoted into a separate follow-up task.

### Control signal

A structured non-message event routed into the live task, such as:

- approval approved
- approval rejected
- delegated child outcome
- user cancellation intent

### Terminal final reply

The point where the runtime is allowed to close the live task. A reply is terminal only when:

- the agent has produced its final answer for the current task
- no unresolved approval or delegated child state remains
- no buffered user input remains to be consumed by the same task

## Current Mismatch With This Contract

The current implementation is still closer to a "run per turn, with supplements when possible" model than a true single-live-task model.

### 1. Follow-up user input can become a new task

Current path in `OpenCrayHostRuntime.submitChatMessage()`:

- if there is an active run and there are no already-queued pending inputs, input is appended as a supplement
- otherwise input falls back to `pendingUserInputs`

That fallback means later user input can leave the current task and become a separate future task instead of staying inside the same live task.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`

### 2. Queued pending inputs can auto-start as a new run

Current auto-start paths:

- when a run completes, `startNextQueuedChatRunLocked()` is called
- when the active session is resumed, `ensureActiveSessionResumed()` calls `startNextQueuedChatRunLocked()`
- when the user selects a session, `selectChatSession()` also calls `startNextQueuedChatRunLocked()`

This means session restore, page attach, or session switching can create a new task from queued input without the old task being the owner anymore.

Relevant file:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

### 3. Approval rejection currently cancels the task

`rejectChatApproval()` currently calls `requestCancel(approval.taskId)`.

That behavior violates the desired contract. Rejection should be routed back into the same task as a structured control signal so the agent can continue inside the same task.

Relevant file:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

### 4. Session selection still has runtime-driving side effects

Selecting a session does more than subscribe the UI to state:

- it resumes the session runtime
- it may immediately start a queued chat run

Under the target model, session selection is only an observation concern. It must not decide whether a new task starts.

## Target Runtime Model

## 1. One live task slot per session

Each session should have:

- `activeTaskId: String?`
- `activeRunId: String?`
- `activeTaskState`

Rules:

- if `activeTaskId == null`, a new user message may create a new task
- if `activeTaskId != null` and the task is non-terminal, all further user input belongs to that task
- no second task may be created for that session until the active task becomes terminal

## 2. Replace pending follow-up task creation with a task inbox

Current `pendingUserInputs` semantics should be split:

- `session-level queued tasks` for true future work
- `task-level inbox` for follow-up user input while a live task exists

For chat, active-task follow-up input should go to the task inbox, not the session queue.

Recommended new model:

- `TaskInboxEntry`
- ordered by creation time
- persisted per task
- drained by the same task runtime at safe checkpoints

This changes the question from:

- "should the next message start another run?"

to:

- "when should the same live task consume its next inbox entry?"

## 3. Route approvals as control signals, not lifecycle breaks

Approval handling should be unified:

- approve -> control signal: `approval_approved`
- reject -> control signal: `approval_rejected`

Both signals must continue the same task.

Rejection should no longer imply:

- immediate task cancellation
- replay summary only
- waiting for a brand new task

Instead, the runtime should receive the rejection signal and decide the next action inside the same task:

- revise plan
- ask the user a follow-up question
- choose a safer alternative
- stop with a final answer if no safe continuation exists

## 4. Session switching must be observation-only

Session switching must not:

- start a queued run
- resume a queued follow-up task
- flush buffered input into a new run

Session switching may:

- change which session the UI renders
- attach or detach observers
- clear unread counts

But it must not alter task ownership.

## 5. UI attach/detach must not affect task ownership

The live task must not be owned by:

- the selected chat page
- the current Flutter route
- whether the user is currently looking at that session

The UI should only:

- submit user input
- send control signals
- subscribe to task state

The runtime owner should decide execution.

## Task State Model

Recommended user-facing live-task states:

- `running`
- `waiting_tool`
- `waiting_approval`
- `waiting_input_merge`
- `recovering`
- `interrupted`
- `completed`
- `cancelled`
- `failed`

Important rule:

- `waiting_approval` and `waiting_input_merge` are still the same live task
- `recovering` is still the same live task
- only `completed`, `cancelled`, and terminal `failed` release the session's live-task slot

## Finalization Rules

The runtime must not finalize a live task too early.

Recommended finalization gate:

- model says final answer is ready
- no pending approval remains
- no delegated child remains unresolved
- no buffered task inbox entries remain
- no resumable continuation cursor remains

If buffered user input exists when the agent reaches an apparent final answer, the runtime should continue the same task and process that buffered input before closing.

This is the core difference from the current "complete this run, then maybe start the next run" behavior.

## Required Architecture Changes

## 1. Introduce a session live-task coordinator

Recommended new responsibility:

- `SessionLiveTaskCoordinator`

Responsibilities:

- own `activeTaskId` per session
- decide whether a user action is routed to the live task or starts a new task
- block accidental task creation while a live task is still non-terminal

## 2. Replace `pendingUserInputs` auto-run behavior for active sessions

Current behavior that must be removed for active tasks:

- `startNextQueuedChatRunLocked()` on task completion
- `startNextQueuedChatRunLocked()` during `ensureActiveSessionResumed()`
- `startNextQueuedChatRunLocked()` during `selectChatSession()`

These paths are valid for explicit queued future work, but not for chat follow-up input while a live task exists.

## 3. Replace supplement promotion with a unified inbox

Today the product has two separate concepts:

- supplement store
- pending user input queue

Under the target model, both should be unified into one task inbox for active tasks.

That inbox should be:

- ordered
- durable
- visible in debug state
- consumed by the same task, not promoted into a new task

## 4. Make rejection resumable

`rejectChatApproval()` must stop cancelling the task.

Instead it should:

- persist a rejection control signal
- clear the approval wait state
- resume the same task with the rejection payload

## 5. Decouple runtime ownership from active session UI

This requirement aligns with the detached-runtime documents:

- the runtime owner must outlive page attachment
- task ownership must not depend on selected session
- UI selection must not trigger task creation

The long-term home for this is still a detached runtime owner or service. But even before that lands, the in-process owner must already obey the single-live-task contract.

## Behavioral Scenarios

### Scenario A: User sends follow-up input while task is running

Expected behavior:

- same `taskId`
- same logical live task
- follow-up input appended to task inbox
- no new run is created

### Scenario B: User approves a tool request

Expected behavior:

- same `taskId`
- same logical live task
- approval signal resumes the same task

### Scenario C: User rejects a tool request

Expected behavior:

- same `taskId`
- same logical live task
- rejection signal resumes the same task
- no cancellation unless the user explicitly cancels

### Scenario D: User switches to another session and back

Expected behavior:

- original session task keeps running or waiting
- switching sessions does not create a new task
- switching back only reattaches the UI to that same task

### Scenario E: User talks in another session while the first session task is still active

Expected behavior:

- session A keeps its own live task
- session B may create its own separate live task
- returning to session A must show the same task, not a replacement task

### Scenario F: Host owner rebuilds without a true app restart

Expected behavior:

- same logical task remains the owner if recovery is safe
- otherwise surface explicit `interrupted`
- never silently cancel and replace the task with a fresh one

## Migration Plan

## Phase 1: Behavioral guardrails

Goal:

- stop the most obvious violations before deeper architecture work

Work:

- remove automatic `startNextQueuedChatRunLocked()` from session-selection and active-session resume flows
- block follow-up input from falling back into session-level queued task creation while a live task exists
- stop using approval rejection as task cancellation

Exit criteria:

- no in-process UI/session action can silently replace a live task with a new one

## Phase 2: Task inbox and control-signal model

Goal:

- make single-live-task semantics first-class

Work:

- add durable task inbox store
- add approval/control signal channel
- update runtime to consume inbox entries and control signals inside the same task
- define explicit finalization gate

Exit criteria:

- multiple user follow-ups and approval actions are processed by the same task

## Phase 3: Detached runtime owner

Goal:

- make the contract independent from UI and host attachment details

Work:

- move runtime ownership into the detached runtime owner defined in the background-task docs
- keep UI as observer/controller only

Exit criteria:

- task lifetime is not coupled to selected session or page attachment

## Phase 4: Process-loss recovery

Goal:

- extend the same contract across true app restart or process death

Work:

- combine task inbox, control signals, run journal, and prompt checkpoints
- recover the same logical task after process recreation when safe
- otherwise show explicit interrupted recovery without silent replacement

Exit criteria:

- software restart is no longer the only boundary where task continuity is lost

## Acceptance Criteria

The design is successful when all of these are true:

- sending three follow-up messages during one live task does not create three tasks
- approval rejection does not terminate the task unless the user explicitly cancels
- selecting another session does not create or replace the original session task
- reselecting the original session does not auto-create a new follow-up run
- the task remains the session owner until final reply or explicit interruption/cancel
- any apparent restart is explicit in state and reason, never silent

## Recommendation

OpenCray should stop treating chat as "one run per turn plus optional supplements" and move to "one live task per session until terminal completion."

That is the only model that matches the intended user experience:

- no silent task replacement
- no session-driven task restart
- no approval rejection as hidden task termination
- no UI-driven lifecycle surprises before the agent actually finishes
