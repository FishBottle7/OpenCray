# Runtime Foundation Delivery Plan

Last updated: 2026-03-27

## Status

Phase 1 complete; Phase 2 approval-boundary and first general checkpoint restore slice partially implemented; in-process detached-owner foundation, same-process service host, foreground keepalive, and scheduled wake bridges landed. True detached ownership and controller-level managed-process reconnect remain pending.

## Goal

This document turns the earlier runtime recovery proposals into a concrete delivery plan for three missing foundations:

1. detached runtime ownership
2. append-only run journal
3. general checkpoint and resume

For the Android-specific product design that sits on top of those foundations, including foreground-service survival and scheduled wake-up, see `docs/android-local-strong-background-runtime-design.md`.

The target behavior is strict:

- a task must not be silently restarted or replaced before the final reply
- leaving the UI must not affect task execution
- host or process loss must resume from the last safe boundary when possible
- when safe continuation is impossible, the system must surface explicit interruption instead of replaying the whole run

## Recommended Architecture

Recommended order:

1. append-only run journal
2. general prompt checkpoint and recovery planner
3. detached runtime owner via Android service
4. scheduled and background task triggering

This order is deliberate.

- without the journal, reconnect still loses expanded-card history
- without checkpoints, a service can keep tasks alive only while the host survives
- without a detached owner, UI attachment is still coupled to execution lifetime

## Why Not Start With Service

Starting with an Android service alone does not solve the core restart problem.

It only solves:

- UI detachment
- page exit
- session switch

It does not solve:

- host rebuild after crash
- process recreation
- mid-tool interruption with uncertain commit state
- expanded history loss after restore

Because of that, `AgentRuntimeService` is Phase 2, not Phase 1.

## Phase 1: Append-Only Run Journal

### Purpose

Make run history durable and replayable without relying on in-memory runtime events or transcript heuristics.

### New components

- `RunEventJournalStoreFactory`
- `RunEventJournalStore`
- `PersistedRunJournalEntry`

### Storage model

Per session:

- `files/agent-runtime/session-<encoded>/`

Per run:

- `runs/run-<encoded>/events/`

Each event is stored as its own immutable file.

Recommended filename shape:

- `000000000001-lifecycle.json`
- `000000000002-tool_call.json`
- `000000000003-tool_result.json`

This keeps the storage append-only in practice and avoids rewriting a large session-wide file for every new event.

### Journal entry shape

Each entry should contain:

- `schemaVersion`
- `sessionId`
- `runId`
- `taskId`
- `seq`
- `eventId`
- `kind`
- `emittedAtEpochMs`
- `persistedAtEpochMs`
- `payload`

`payload` should reuse the existing persisted event mapping already used by `PersistedAgentRunEvent`.

### Journaled event classes

Phase 1 should journal every replayable runtime event already projected to the chat runtime surface:

- lifecycle
- assistant
- progress
- supplement
- approval
- subagent
- tool_call
- tool_result
- memory_retrieval
- memory_write
- cancellation

### Write path

Phase 1 write path should attach at the current host event aggregation point:

- `OpenCrayHostRuntime.recordRuntimeEventLocked(...)`

Rationale:

- this path already receives both runtime-originated events and host-synthesized events
- it captures approval, cancellation, recovery, and memory events uniformly
- it gives immediate value before service ownership exists

### Read path

The runtime activity replay path should prefer the journal:

1. live in-memory events
2. durable journal events
3. transcript replay only as fallback for old runs that predate the journal

This changes the expanded-card source of truth from:

- in-memory events + transcript replay

to:

- in-memory tail + durable journal

### Success criteria

- host rebuild no longer erases detailed run history
- approval and failure paths remain visible after restore
- transcript replay becomes compatibility fallback, not the main recovery mechanism

## Phase 2: General Prompt Checkpoint And Recovery Planner

### Purpose

Stop recovering at the task boundary only. Recover at the last safe prompt-execution boundary instead.

### New components

- `PromptCheckpointStoreFactory`
- `PromptCheckpointStore`
- `PersistedPromptCheckpoint`
- `RunRecoveryPlanner`

### Current implementation slice

Implemented so far in Phase 2:

- durable prompt checkpoints now exist for approval boundaries
- waiting-for-approval state is persisted as a session-scoped checkpoint instead of only host memory
- approve/reject decisions are persisted as `approved_pending_resume` or `rejected_pending_resume`
- runtime execution now falls back to those durable checkpoints when the in-memory approval registry is empty after host rebuild
- a first non-approval general resume slice now persists durable prompt resume state after committed tool results, and runtime execution can rebuild from that `general_resume` checkpoint
- once resumed execution emits the next real runtime event, the approval checkpoint is cleared conservatively so stale resume state is not reused later
- a first `RunRecoveryPlanner` skeleton now classifies runs into checkpoint resume, approval wait, rejected-stop-awaiting-direction, managed-process reconnect, interrupted recovery required, or legacy requeue
- run snapshots now project that planner output so recovery intent is explicit even before queue restore behavior is switched over
- app-layer restore now wraps the queue snapshot store and rewrites approval-boundary restores before `SessionQueue` sees them
- app-layer restore can now also rewrite interrupted runs back to the same queued run when a durable `general_resume` checkpoint proves that post-tool-result continuation is safe
- interrupted runs with `approved_pending_resume` can restore as the same queued run, while `rejected_pending_resume` now restores as a stopped run awaiting the next user instruction instead of being stranded in explicit-retry failure
- interrupted runs with `waiting_approval` can restore as the same suspended run instead of silently changing recovery shape after host rebuild
- live managed-process restores now prefer durable checkpoint replay when a safe `general_resume` checkpoint exists, instead of projecting a reconnect state that cannot yet continue end-to-end
- file-backed managed-process registries can now reattach a live controller across registry or host rebuild inside the same app process, which avoids falsely marking still-live managed processes as interrupted in that narrow same-process restore case
- provider-native builtin web-search observations now emit the same general resume checkpoint metadata as normal tool results, so host rebuild after provider-managed search can still resume from a durable post-tool boundary
- app-layer restore now prefers durable journal tail over `lastEvent` summary when feeding recovery decisions
- when an explicit `general_resume` checkpoint is missing, app-layer restore can now synthesize one from the durable journal tail or persisted `lastEvent` if that boundary already carries `OpenCrayPromptResumeMetadata`, so safe post-tool-result continuation no longer depends solely on the checkpoint store still being present
- when explicit approval checkpoints are missing, app-layer restore can now also synthesize `waiting_approval`, `approved_pending_resume`, or `rejected_pending_resume` from durable approval-denial result metadata plus the durable tail approval event, so host rebuild no longer loses the user's approval state solely because the checkpoint row is missing
- interrupted runs without a recoverable checkpoint now surface as explicit interruption in planner output instead of implying automatic legacy rerun

Still pending in Phase 2:

- additional generalized checkpoint boundaries beyond the current post-tool-result `general_resume` slice
- true cross-process managed-process controller reconnect restore
- generalized planner integration inside `core` queue restore and detached runtime ownership

Detached-owner foundation landed in app process:

- production `OpenCrayHostRuntime` no longer exposes the old app-process `fromContext()` facade path; service-owned host construction now flows through `createForRuntimeService(...)`
- a shared in-process runtime owner now keeps `DefaultAgentSessionRuntimeManager`, run journal/checkpoint stores, supplement store, approval registry, and runtime replay hooks separate from the host facade
- host instances now project both `hostLifecycle` and `runtimeOwnerLifecycle`, which gives a concrete seam for later Android service ownership without changing the UI contract again

First service-host slice landed:

- `OpenCrayAgentRuntimeService` is now declared in the app manifest and started from app bootstrap and host access paths
- the service eagerly initializes the shared in-process runtime owner on `onCreate()`, so owner bootstrap is no longer implicit in `OpenCrayHostRuntime` alone
- the service host now exposes a host-facing runtime access bundle instead of handing `OpenCrayHostRuntime` the raw owner object, which reduces coupling ahead of binder-backed control flow
- `OpenCrayHostRuntime` itself now talks to that owner only through `OpenCrayRuntimeHostAccess`, so session-manager, approval-registry, and journal/checkpoint/supplement store wiring no longer leak into the host facade
- host bootstrap now goes through a formal runtime service client that returns both the runtime snapshot and the host-visible connection state instead of reaching into the service bridge directly
- runtime and shell snapshots now project `runtimeServiceConnectionState`, so binder-backed access and in-process fallback are distinguishable without inferring from implementation details
- shell snapshots now also project `localRuntimeServerState`, so loopback HTTP server startup, listening, and bind-failure diagnostics are visible separately from binder transport state
- app bootstrap now only ensures `OpenCrayAgentRuntimeService`, and that service bootstraps the local loopback runtime server on `onCreate()`, so both Android transports now initialize from the same runtime-service boundary
- the runtime service client now issues a real asynchronous `bindService(...)` request and keeps projecting connection transitions through the same snapshot field, without blocking synchronous host creation paths
- host observers now refresh shell/runtime snapshots when the service client connection state changes, so later binder attachment is visible without rebuilding the host facade
- production `OpenCrayHostRuntime` construction is now projection-only with respect to session bootstrap: active-session `resume()` plus terminal replay repair moved to one-time runtime service host startup instead of running from every host-facade init path
- caller-side runtime-service entrypoints now only request service start or wake; runtime service host bootstrap happens inside `OpenCrayAgentRuntimeService.onCreate()`, and the binding client fallback bridge only projects an already-initialized host without lazily `getOrCreate(...)`ing the runtime host during first snapshot reads
- service-backed chat/skills/settings gateways now treat binder fallback as projection-only for reads: `load*` and `observe*` paths may still project through the fallback facade, but mutating or tool-executing operations now fail explicitly unless a binder-backed service gateway is attached
- the shell snapshot surface is now normalized behind `OpenCrayShellGateway`, and both the Flutter bridge and loopback HTTP server prefer a binder-backed service shell gateway for `loadShellSnapshot()` and shell observation when the binder is available
- the execution-facing chat/runtime surface is now normalized behind `OpenCrayChatRuntimeGateway`, and both the Flutter bridge and loopback HTTP server dispatch that path through the gateway instead of calling chat/runtime host methods directly
- the runtime service binder now exposes a service-owned chat/runtime gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for chat/runtime loads and commands when the binder is available
- chat/runtime observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the same event channels can follow the service-owned execution path without recreating the Flutter bridge
- the skills-management surface is now normalized behind `OpenCraySkillsGateway`, and both the Flutter bridge and loopback HTTP server dispatch skills snapshot, observation, install, update, delete, inspect, and instructions flows through the same service-preferred boundary
- the runtime service binder now exposes a service-owned skills gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for skills loads and commands whenever binding succeeds
- skills observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the skills page can follow the service-owned runtime path without recreating the host bridge
- the settings and runtime-configuration surface is now normalized behind `OpenCraySettingsGateway`, and both the Flutter bridge and loopback HTTP server dispatch settings overview, config loads, and config writes through that same service-preferred boundary
- the runtime service binder now exposes a service-owned settings gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for settings and runtime-config loads or writes whenever binding succeeds
- settings overview observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the settings UI can follow the service-owned runtime path without recreating the host bridge
- service-backed shell/chat/skills/settings observers now re-check the active gateway immediately after connection observation registration, which closes the binder-connect race that could otherwise leave a UI stream stuck on projection fallback until a later connection transition
- the Android runtime-service client now treats binder attachment as an idle-released transport lease instead of a permanent process-wide bind: active connection observers keep the binder attached, transient reads or commands schedule an automatic unbind after a short quiet window, and detached execution still belongs to the started service plus keepalive path rather than the UI transport
- workspace tree/document operations, local file open/share, native toast, twin import probing, and draft attachment import are now isolated behind `OpenCrayLocalHostGateway`, so the Flutter bridge and loopback HTTP server no longer need to hold a full `OpenCrayHostRuntime` just to reach pure local device/workspace capabilities
- `OpenCrayHostRuntime` now implements that same local-only gateway by delegation, which keeps the remaining projection fallback compatible while separating service-owned runtime surfaces from local-only host helpers
- shell, settings, and skills read fallback are now served by dedicated projection-only gateways instead of a full `OpenCrayHostRuntime`, so those surfaces no longer pull the UI-side host facade into existence just to satisfy binder-pending reads
- projection-only skills fallback is now strictly local-only: it filters the local snapshot and local instructions without issuing `SkillsList` or `SkillsFind`, which keeps tool-executing skills discovery on the binder-owned pipeline
- this is still a same-process service host; stronger detached ownership, dedicated-runtime-process isolation, and true controller-level managed-process reconnect remain later slices

### Recommended checkpoint boundaries

Checkpoint only at explicit safe boundaries:

1. before model request
2. after model action batch parse
3. after progress emission is durably committed
4. after tool result is durably committed
5. while waiting for approval
6. after approval resume is committed
7. after supplement ingestion is durably committed
8. finalization complete

### Checkpoint payload

The durable checkpoint should generalize the current approval-only `OpenCrayPromptResumeState`.

Minimum fields:

- `sessionId`
- `runId`
- `taskId`
- `checkpointId`
- `checkpointKind`
- `turnIndex`
- `toolCallCount`
- `transcript`
- `pendingActions`
- `nextActionIndex`
- `activeSkillName`
- `activeSkillActivationSource`
- `pendingToolCall`
- `lastCommittedJournalSeq`
- `updatedAtEpochMs`

The runtime-facing `OpenCrayPromptResumeState` should become a derived payload built from the durable checkpoint.

### Recovery planner rules

`RunRecoveryPlanner` should inspect:

- queue snapshot
- latest checkpoint
- journal tail
- managed process registry
- approval registry

Planner outputs:

- `resume_from_checkpoint`
- `resume_waiting_for_approval`
- `stop_rejected_awaiting_direction`
- `resume_reconnect_process`
- `interrupt_recovery_required`
- `legacy_requeue` for work that was already safely queued before restore, not for interrupted in-flight recovery

Current implementation note:

- queue restore now prefers `resume_from_checkpoint` whenever a durable safe checkpoint exists, even if a managed process is still live
- `stop_rejected_awaiting_direction` is now used for durable rejected-approval restores so the run stays stopped instead of re-entering a queued resume path
- `resume_reconnect_process` remains a projected recovery intent for live managed-process state, but true reconnect without checkpoint replay is still not implemented end-to-end

### Safety rule

Do not auto-rerun uncertain mutating work.

If a tool was dispatched but the system cannot prove durable result commit, recovery must become:

- explicit interrupted state

not:

- silent replay from the beginning

### Success criteria

- process or host loss resumes from the last safe boundary when possible
- uncertain mid-tool interruption never silently duplicates mutation
- task-level rerun becomes last-resort legacy fallback

## Phase 3: Detached Runtime Owner

### Purpose

Make execution independent from UI attachment and host facade lifetime.

### New component

- `AgentRuntimeService`

### Ownership split

`AgentRuntimeService` becomes the execution owner for:

- `DefaultAgentSessionRuntimeManager`
- queue snapshot stores
- run journal stores
- prompt checkpoint stores
- managed process registries

`OpenCrayHostRuntime` becomes a facade for:

- UI snapshot assembly
- user actions
- bridge adaptation
- service binding
- diagnostics projection

### Recommended first deployment shape

Use a service in the main app process first.

Do not start with a dedicated `:runtime` process.

Reasons:

- existing runtime objects, tool pipeline dependencies, Python launcher, and local stores are all main-process oriented
- dedicated-process IPC would multiply risk before recovery semantics are proven
- same-process service already solves the current UI-coupling problem

### Runtime states

Recommended service modes:

- bound while UI is attached
- started while active work exists
- foreground service while active work is expected to outlive the foreground UI
- idle shutdown after no active runs remain for a grace window

### Success criteria

- leaving the chat page does not affect live work
- switching sessions does not replace the live task
- execution lifetime is no longer tied to a visible Flutter screen

## Phase 4: Scheduled And Background Task Triggering

### Purpose

Prepare delayed execution and future periodic tasks without reintroducing UI ownership.

### Current implementation status

Already landed in app process:

- scheduled task registry, durable spec storage, and run records
- `AlarmManager` wake path plus `WorkManager` wake and repair path
- runtime-service wake entrypoints for scheduled dispatch and repair
- schedule, approval, completion/interruption, and active-runtime notification surfaces

Still pending in this phase:

- richer schedule-side notification actions beyond the current approval/open flows
- a scheduler-owned repair loop for active interactive runs that does not rely on normal startup/checkpoint recovery
- any future periodic/task-automation expansions that need more than the current wake/dispatch bridge

### Components in this phase

- scheduled task registry
- `WorkManager` trigger bridge
- service wake entrypoints
- notification actions for approval and open flows

### Behavior

- `WorkManager` should wake the runtime service
- the service should create or recover the target task
- the task should run under the same queue, journal, and checkpoint model as interactive runs

### Success criteria

- delayed tasks and future scheduled tasks do not need an open UI
- scheduled execution shares the same recovery semantics as interactive runs

## Module Impact

### `app/`

- add run journal storage
- add prompt checkpoint storage
- add service-owned runtime gateway
- migrate `OpenCrayHostRuntime` toward facade-only responsibilities

### `runtime/`

- emit generalized checkpoints at safe boundaries
- restore from durable checkpoint payloads
- expose recovery metadata for planner decisions

### `core/`

- extend queue restore behavior to support checkpoint-aware recovery states
- stop treating non-terminal restore as unconditional rerun

### `persistence/`

- no immediate Room migration required
- keep Phase 1 and Phase 2 on file-backed stores unless scale proves otherwise

### `flutter_app/`

- runtime activity should prefer service and journal-backed snapshots
- UI should show explicit recovery states
- UI must never infer a fresh run from the absence of live in-memory events alone

## Recommended First Slice

The first implementation slice should be:

1. add `RunEventJournalStore`
2. append every runtime event at `OpenCrayHostRuntime.recordRuntimeEventLocked(...)`
3. load journal-backed replay in chat runtime snapshot generation
4. keep transcript replay as fallback for older runs

This is the smallest slice that improves user-visible continuity immediately and gives later checkpoint and service work a stable base.

## Explicit Non-Goals For The First Slice

- do not introduce `AgentRuntimeService` yet
- do not move to a dedicated runtime process
- do not attempt generic mid-tool recovery yet
- do not replace the current tool policy pipeline

## Decision Summary

Recommended plan:

- Phase 1 first
- Phase 2 immediately after Phase 1 stabilizes
- Phase 3 after checkpoint recovery is trusted
- Phase 4 after service ownership is stable

This is the lowest-risk path that can reach the desired product contract:

- no silent whole-run replay
- no expanded-card history loss on restore
- no execution dependency on an open UI page
