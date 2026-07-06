# Runtime Foundation Delivery Plan

Last updated: 2026-07-06

## Status

Phase 1 complete; Phase 2 approval-boundary and generalized checkpoint restore slice substantially implemented; in-process detached-owner foundation, a production `:runtime` service shell, foreground keepalive, scheduled wake bridges, a first interrupted-run repair wake path, service-owned approval notification handling, and a dedicated service wake-command dispatcher seam landed. Service recreate now also reuses a runtime-process execution controller plus service-neutral bootstrap assembly instead of re-bootstraping the execution core on every shell instance, the keepalive/foreground shell-control state machines now also survive same-process service recreate behind that retained controller, and the service shell now feeds a lease-based app-visibility heartbeat plus strong-background tier into idle-grace/foreground behavior to reduce software-caused interruption during ordinary backgrounding without reacting to every transient UI stop/start edge immediately. That app-visibility heartbeat now also persists through the runtime storage root's file-backed durable store instead of `SharedPreferences`, so the main process and `:runtime` process read the same process-safe lease record while app-private broadcast still handles fast local fanout. The retained transport coordinator now also disposes the previously bound service-owned gateway bundle on shell rebind, so shell/chat observer registrations do not accumulate across same-process service recreate. Explicit retained-owner replacement now reuses a retained in-process execution core and only rotates runtime-owner lifecycle/access wrappers, so same-process reset no longer drops live session managers, executors, or managed-process continuity merely because the shell asked for a rebuild; full controller disposal still tears that retained core down deterministically even if bootstrap-assembly disposal throws. Runtime routing is now explicit end to end as well: caller-side UI bundles default to `INTERACTIVE`, scheduled wakes and detached-control or scheduled approvals route to `DETACHED_BACKGROUND`, the Android service shell keeps concurrent target-scoped bootstraps instead of one mutable default shell, service-backed chat writes now resolve their target from durable queue or checkpoint state before dispatch, durable projection fallback is now target-scoped so interactive and detached clients no longer overwrite each other's last snapshot bucket, and service-owned loopback transport now also splits by `RuntimeServiceTarget` so concurrent interactive and detached shells no longer fight over one loopback port or one default server-state bucket. Binder-unavailable Android clients also now reopen the matching target-scoped durable projection store even when no projection store was injected explicitly. The retained-runtime composition path is now narrower too: client/service gateway factories, local-host gateway creation, runtime dependency loading, Flutter/runtime target bridge entrypoints, and the process-scoped execution-controller path all resolve through explicit environment-owned seams instead of deep `Context -> singleton/helper` fallbacks, with execution-controller bootstrap now consuming only a narrowed `RuntimeExecutionDependencies` bag rather than the full runtime context dependency bag. Managed-process restore is now controller-aware inside the same runtime process, and its file-backed registry now serializes session-level read/modify/write operations behind a directory-scoped JVM lock plus OS file lock so concurrent owners do not lose process snapshots while detached and projection-only readers overlap. Runtime-level managed-process routing now also preserves delegate reconnect support for non-Python-runtime processes, so file-backed registry restore does not hide reconnectable backend capability behind the runtime adapter layer. Runtime-service lifecycle diagnostics now record the expected dedicated service process, the observed current process, and any mismatch reason, projection persistence carries that descriptor so binder fallback reads can detect a service/process ownership regression instead of only trusting the manifest, and bootstrap now rejects a mismatched service process before creating the runtime owner. Recovery diagnostics now also stamp initial `runAttempt`, advance it on recovery-aware queue rewrites, and surface checkpoint-driven `recoveredFromCheckpointId` in run snapshots. App-start and boot/package-replaced paths now also keep a unique periodic `WorkManager` repair registered, so interrupted-run and schedule reconciliation are no longer limited to the first one-shot wake after process start or broadcast delivery; that repair precheck now resolves persisted queue/checkpoint/subagent, run-record-only, and non-terminal journal-tail evidence into `INTERACTIVE` and `DETACHED_BACKGROUND` targets before waking `:runtime`, with terminal run records and final journal tails excluded from interrupted-work detection. Schedule failure/skip notifications now also expose service-backed retry/manual-run, disable, and snooze actions that wake `:runtime`, mutate durable schedule state where needed, and re-sync registered triggers from the service-owned dispatcher; accepted scheduled-run notifications now also expose a per-run Cancel action that reuses the service-owned chat-write interrupt wake command instead of adding a separate cancellation path. Interrupted terminal notifications now also expose a Retry action that builds a target-scoped service PendingIntent for the existing `RetryChatRun` wake command and carries the terminal notification task id into the wake dispatcher, so explicit user retry can wake `:runtime`, start retry dispatch, and clear the stale interrupted notification without first reopening the UI. That schedule re-sync path is now also serialized by a runtime-root cross-process lock around spec enumeration, prior synced-id readback, cancel/sync fanout, and synced-id persistence, so app-process bootstrap, repair, and runtime-side notification actions do not race each other into stale trigger registration state. Schedule notification taps now open the Notifications & Background settings detail entrypoint and preserve `notificationScheduleId` for later schedule-specific surfaces. True detached ownership and a stronger controller-owned cross-process reconnect tier remain pending.
The runtime service command envelope now also rejects explicit `runtimeServiceCommandKind` intents when their `runtimeServiceCommandVersion` does not match the current protocol, including schedule and reset actions, instead of falling back to legacy action parsing.
Binder-unavailable service writes now also have a bounded command fallback for background-thread callers: the Android binding client first waits for binder dispatch, then can send chat/skills/settings write commands through the target-scoped service-owned loopback command transport. Main-thread callers still fail fast rather than blocking the UI, and chat fire-and-forget commands keep their foreground wake fallback when the loopback command path is not yet listening.
Retry chat-write wake dispatch now also persists the latest runtime-service projection even when the retry command fails, but only dismisses the stale interrupted terminal notification after dispatch returns successfully. A failed explicit retry therefore remains visible for user follow-up while binder-unavailable diagnostics still see the refreshed projection state.
The latest runtime-service bootstrap/resume interrupted-run repair scan now also persists typed repair evidence into the target-scoped projection snapshot, so binder-unavailable shell/chat diagnostics can inspect the last queue/checkpoint/subagent/run-record/journal-tail repair reasons without reaching back into a live host owner.
Target-scoped runtime-owner lease heartbeat now also persists the current held/released owner evidence into the runtime storage root and projection diagnostics, including process/controller/owner/service ids plus heartbeat and expiry timestamps. The lease store now rejects a different owner while the current held lease is unexpired, retained runtime-owner replacement explicitly releases the previous owner lease before writing the new heartbeat, and a runtime-service projection coordinator that cannot acquire the target lease now skips projection snapshot writes instead of overwriting the active owner's target projection. Rejected acquire attempts are now recorded on the held lease with attempted owner/controller/service ids plus holder expiry, and the active owner's next heartbeat carries that conflict into projection diagnostics for binder-unavailable fallback. The same owner check now gates shell attach, service `onStartCommand(...)`, wake-command dispatch, binder chat/skills/settings writes, service-owned loopback POST routes, and sticky start-result decisions, so a losing shell does not start transport/observers, mutate keepalive state, expose a cached binder or loopback write endpoint, mutate runtime-owned data, or ask Android to keep restarting it after another owner holds the target lease. This is still ownership arbitration for the current `:runtime` process boundary, not a separate controller-process ownership tier.
Owner-lease attach denial now also schedules a repair retry at the held lease expiry. Production service attach failure reads the durable target lease, enqueues an `owner_lease_expired` repair through WorkManager for `expiresAtEpochMs`, and delayed repair work is partitioned by reason so owner-lease retry, managed-process reconnect retry, and other delayed repair sources do not replace each other.
Owner-lease bind denial now also returns Android's null-binding path instead of exposing a cached binder or throwing from service bind. Binder-unavailable clients already translate that into an explicit `null_binding` in-process fallback and keep reading the target-scoped durable projection snapshot, so a losing shell remains observable without mutating or serving runtime-owned state.
Retained transport ownership now also activates later: a contender shell no longer replaces the retained gateway bundle during bootstrap assembly alone, and the transport coordinator only switches bundles when that shell actually reaches loopback listening state. If the contender is denied or its loopback start fails first, its bundle is disposed locally without disconnecting the currently active detached shell.
The shared `DirectoryDurableTextStorage` path now also wraps file read/write/delete/update operations in per-file JVM locks plus sidecar OS file locks before atomic replace, and key read-modify-write stores for queue snapshots, run records, checkpoints, schedules, transcript fallback, memory, notification-delivery dedupe, session supplements, subagent handles, skill install manifest state, sandbox settings, E2B sandbox-session resume state, safety policy settings, live-context mode settings, network-search settings, MCP master settings, LLM config settings, media/speech settings, app-language settings, runtime working-state snapshots, and the cross-process app-visibility lease now use that single-file update primitive instead of splitting load/mutate/save across separate process locks. Agent run-record list/normalization repair is covered by the same single-file update path, so host rebuild or projection fallback cannot repair an older run-record snapshot over a newer concurrent run record. Prompt-checkpoint list/get normalization repair is covered too, so checkpoint resume or approval fallback cannot save an older repaired checkpoint snapshot over a newer concurrent checkpoint. Detached subagent-handle list/get normalization repair now also uses the same single-file update path, so subagent repair scans cannot save an older repaired handle snapshot over a newer concurrent delegated-run handle. Production runtime-owner assembly now injects a per-session file-backed working-state store under the runtime storage root instead of the runtime factory's in-memory default, so owner replacement or runtime-process recreate can restore prompt working state from process-safe storage. Chat workspace todo replacement now also reloads and writes the current workspace record through the file-backed store update path, so service/runtime `TodoWrite` cannot overwrite newer chat workspace extensions by saving from a stale pre-update snapshot. Scheduled spec and scheduled run-record list/get normalization repair are covered by the same single-file update path, and scheduled run-record pruning/deletion is covered too, so schedule repair, notification actions, or deletion no longer write stale pre-repair snapshots over newer schedule state. Runtime notification-delivery dedupe writes are covered too, so foreground/service/repair overlaps no longer lose another run's terminal-notification fingerprint by saving an older dedupe snapshot. Service-owned skill installs, checks, updates, and removals now merge manifest entries under the same locked single-file update path, so concurrent app/runtime skills actions do not lose another skill's newer manifest entry by saving a stale install snapshot. Sandbox execution settings now also default to a runtime-root durable JSON snapshot with a legacy SharedPreferences migration, so main-process settings writes and `:runtime` execution routing read the same process-safe backend/session/timeout state. E2B sandbox-session resume state now also defaults to a runtime-root durable JSON snapshot with legacy migration, so app-process preview/session controls and runtime-process sandbox reconnect/resume read one process-safe active-session record. Safety policy settings and live-context mode now also default to runtime-root durable JSON snapshots with the same legacy migration rule, so app-process settings saves and runtime-process tool-policy/live-context reads share one automation/external-access/tool-override/context-mode source of truth. Network-search settings now also default to a runtime-root durable JSON snapshot with legacy migration, so app-process provider/API-key edits and runtime-process search-tool routing read one process-safe slots payload. MCP master settings now also default to a runtime-root durable JSON snapshot with legacy migration, so app-process settings saves and runtime-process MCP tool-surface reads agree on the global enable switch. LLM config settings now also default to a runtime-root durable JSON snapshot with legacy migration, so app-process provider/model edits, saved custom providers, and runtime-process model routing/capability-cache reads use one process-safe config payload. Media/speech settings now also default to a runtime-root durable JSON snapshot with legacy migration, so app-process media/STT provider edits and runtime-process media tool routing read one process-safe provider payload. App-language settings now also default to a runtime-root durable JSON snapshot with legacy migration, so service-owned settings writes, projection fallback, and runtime-localized labels read one process-safe language value. Mid-loop supplement append/consume is covered as well, so a service-side consume cannot erase a newer supplement injected by another foreground or repair path. Transcript fallback append/replace/repair now updates under the same lock, so host rebuild or projection fallback does not lose a newer transcript event by saving an older snapshot. The direct-file run journal append path is now process-safe as well: append/list/clear operations serialize through a session-level sidecar OS lock, allocate sequence numbers from disk under that lock, and write entries through temp-file atomic moves.
LiteRT on-device model install save/delete now also merge through the same locked durable update path, so foreground settings actions and background download workers do not overwrite each other's model install records by saving a stale manifest.
Memory debug action audit append now also updates through the locked durable read-modify-write path, so overlapping runtime/service debug actions do not drop another action's audit entry by saving an older audit record.
Agent registry create/update/select/archive now also merge through the locked durable update path, so foreground agent management and service/runtime agent-scope reads do not lose active-agent or descriptor changes through stale registry writes.
Workspace voice metadata cache writes now also merge through the locked durable update path and cache reads refresh from the durable snapshot, so foreground and service/runtime attachment backfill cannot drop another owner's waveform/duration/transcript entry through a stale in-memory cache snapshot.
Chat workspace extension writes beyond todo replacement now also use the same current-record update path for working state, pending user input enqueue/consume/clear, native web search approval, and session-scoped-state flags, so service/runtime extension writes do not save an older workspace extension map over newer foreground or repair-owned extensions.
Chat workspace transcript append paths for direct message append, submitted-turn creation, and voice attachment metadata merge now also reload the current workspace record under the file-backed update lock and preserve monotonic workspace/session timestamps, so service/runtime chat writes do not drop newer foreground or repair-owned extensions while adding transcript messages or backfilling media metadata.
All `ChatSessionLocalStore` workspace mutations now route through the file-backed current-record update path instead of writing a caller-held workspace snapshot directly. Session create/select/copy/delete, branch, recall, message insert/replace/prune/delete, transcript append, pending-input, todo, working-state, web-search approval, session-scoped-state, and voice-metadata writes all reload the locked workspace record before persisting, so service-owned chat/session mutations no longer have a known stale-snapshot overwrite path for concurrent foreground or repair-owned workspace extensions.

App-shell navigation state now also defaults to a runtime-root durable JSON snapshot with legacy SharedPreferences migration, so main-process activity launches and service/projection shell readers share one process-safe selected tab/settings subpage value instead of relying on process-local `SharedPreferences`.

Skills enablement state now also defaults to a runtime-root durable JSON snapshot with legacy SharedPreferences migration, so app-process skills toggles, service-owned skills reads, projection fallback, and runtime tool-surface loading share one process-safe enabled/disabled map instead of relying on process-local `SharedPreferences`.

Telemetry/privacy-guard settings now also default to a runtime-root durable JSON snapshot with legacy SharedPreferences migration, so settings UI saves, service-owned settings reads, and projection fallback share one process-safe telemetry/privacy preference source of truth.

Managed-process auto-resume eligibility is now shared across recovery-aware queue restore, live session snapshots, projection-only chat fallback, and host-runtime run diagnostics. Stable live process states (`attached_live`, `completed`, or legacy local live snapshots without reconnect metadata) project `hasAutoResumeEligibleManagedProcesses=true`, while `connecting`, `retry_scheduled`, and terminal interrupted restore states remain explicit non-eligible recovery evidence. Recovery-aware queue restore now also keeps live `ProcessRead` or `ProcessWait` observation tails with reconnect backoff in `resume_reconnect_process` instead of converting them to generic interruption, and stamps process id, reconnect status, recovery state, retry-after, and attempt evidence into task metadata, lifecycle diagnostics, and the durable recovery journal marker.

Managed-process reconnect backoff is now also part of autonomous repair evidence. The repair precheck and runtime-service bootstrap/resume scan read the file-backed process registry in passive `projection_only` mode, classify `retry_scheduled` or retryable `connecting` managed processes as typed `MANAGED_PROCESS_RECONNECT` evidence, keep future attempts deferred until `retryAfterEpochMs` through a replacement delayed `WorkManager` repair, and persist that `repairAfterEpochMs` evidence into target-scoped runtime-service projection diagnostics. The same precheck now also recognizes queue-stored `resume_reconnect_process` recovery metadata, so a suspended run that only has the recovery-aware queue snapshot left still enters managed-process reconnect repair with the persisted process id and retry-after evidence instead of falling back to a generic queue-task repair reason. When a reconnect hold exists, same-run/task queue, run-record, or journal-tail evidence now inherits the reconnect retry deadline plus reconnect status, recovery state, continuation basis, restore scope, and restore decision diagnostics; runtime-service bootstrap/resume scans record the evidence but do not resume durable-only sessions until that deadline is due, and the projected repair result now carries `nextRepairAfterEpochMs` plus `nextRepairReason`. Managed-process reconnect keeps its dedicated delayed WorkManager reason, while other future delayed interrupted-run evidence falls back to the generic `interrupted_run_retry` reason instead of being mislabeled as managed-process reconnect; the WorkManager preflight path now also re-enqueues the evidence-derived reason instead of hardcoding every future retry as managed-process reconnect.

The active file-backed managed-process registry now also honors persisted reconnect backoff during `ACTIVE` restore: a `retry_scheduled` running snapshot with a future `retryAfterEpochMs` stays running, stamps restore metadata, and does not reconnect or mark the process interrupted until the deadline is due. App-level auto-resume, repair precheck, and recovery-planner diagnostics now share one reconnect-evidence reader that can recognize newer stable metadata such as `attached_live` even when an older typed reconnect snapshot still says `retry_scheduled`, so controller-level live reattachment evidence no longer looks like checkpoint-only reconnect fallback.

Managed-process recovery plans now also stamp `_host.managedProcessContinuationBasis` into recovered task metadata, lifecycle diagnostics, and durable recovery journal markers. The wire values distinguish `checkpoint_resume` for safe checkpoint-backed `ProcessRead`/`ProcessWait` observation replay, `live_reattach` for stable attached-live reconnect evidence, and `reconnect_hold` for live processes that must remain suspended while reconnect/backoff is still unresolved. This improves detached-runtime diagnostics without claiming the pending stronger controller-process ownership tier is complete.

Interrupted-run repair evidence now carries the same managed-process continuation basis through WorkManager preflight, runtime-service bootstrap/resume results, diagnostic snapshot maps, and the file-backed runtime-service projection store. Queue-stored reconnect holds preserve the `_host.managedProcessContinuationBasis` value, while registry-discovered retry/backoff reconnect evidence defaults to `reconnect_hold`, so binder-unavailable repair diagnostics no longer lose the recovery basis after the queue rewrite.

Managed-process restore scope now also projects through the same recovery and repair surfaces. Managed-process recovery plans aggregate runtime-registry `managedProcessRestoreScope` and `managedProcessRestoreDecision` evidence into `_host.managedProcessRestoreScope` / `_host.managedProcessRestoreDecision` for both safe checkpoint-backed observation resume and reconnect-hold plans, recovery-aware queue rewrites copy those fields into task metadata, lifecycle diagnostics, and durable `RECOVERY` markers, and interrupted-run repair evidence plus runtime-service projection persistence carry them through queue-stored and registry-discovered reconnect holds. This makes `same_controller`, `same_process_new_controller`, and `cross_process` reconnect evidence visible without implying cross-process controller ownership is solved.

Managed-process reconnect repair evidence now also preserves reconnect status, reconnect recovery state, and attempt count alongside retry deadline, continuation basis, and restore scope. Queue-stored `resume_reconnect_process` metadata and registry-discovered reconnect/backoff snapshots project the same structured fields through WorkManager preflight, runtime-service diagnostics, and the file-backed projection store, so later repair policy can distinguish `connecting` retry backoff from other reconnect holds without reparsing task metadata.

Managed-process active restore now also stamps `managedProcessRestoreDecision` into registry restore metadata and carries it through recovery-aware queue metadata, run lifecycle diagnostics, interrupted-run repair evidence, and the runtime-service projection store. The current values distinguish reconnect attempts, reconnect deferral behind retry backoff, and interrupted fallback when no controller/reconnect path is available; this makes repair policy auditable without pretending the pending controller-process ownership tier has landed.

The runtime-process execution controller now also resolves a target-scoped durable controller identity from file-backed runtime storage by default whenever the Android context exposes `filesDir`; only no-filesDir JVM/test stubs fall back to an in-process identity store. Each controller recreate still receives a fresh `controllerInstanceId`, preserving managed-process restore scope semantics, while `durableControllerId` / `_host.durableRuntimeControllerId` gives diagnostics, projection fallback, and repair evidence a stable ownership anchor for the same runtime target. This is not a substitute for a separate controller/runtime process: in-memory execution still dies with the runtime process and live reconnect beyond that boundary remains a later slice.

Managed-process ownership now also carries that durable controller anchor. `ProcessStart` derives `ManagedProcessRuntimeIdentity` from `_host.processStartId`, `_host.runtimeControllerId`, and `_host.durableRuntimeControllerId`, production file-backed process registries pass the target durable controller id into restore metadata, and run-snapshot association preserves `managedProcessRestoreCurrentDurableRuntimeControllerId`. Restore scope still uses the live controller/process ids, so durable identity improves detached diagnostics without pretending a recreated controller is the same live owner.

Runtime diagnostics now also expose a derived `runtimeExecutionOwnership` projection with `ownershipTier=runtime_process`, `controllerProcessSeparate=false`, and the current owner/controller/service process ids plus service process placement. This makes the current execution boundary machine-readable for shell/chat/projection fallback without adding a second persistence source, and keeps the stronger controller-process ownership tier explicitly pending.

Run lifecycle metadata now stamps `_host.runtimeExecutionOwnershipTier=runtime_process` and `_host.runtimeControllerProcessSeparate=false`, and `RunLifecycleDiagnostics` projects those values into run snapshots. Recovery-aware queue rewrites backfill those fields for older restored tasks and copy them into durable `RECOVERY` journal markers, so per-run diagnostics can be correlated with the shell/projection ownership head when investigating host rebuilds while execution remains runtime-process-bound.

The Android runtime service foreground path now also declares and uses the Android 14+ `specialUse` foreground-service type for generic local agent runtime work. The manifest carries `FOREGROUND_SERVICE_SPECIAL_USE`, `foregroundServiceType="specialUse"`, and a subtype description, while `RuntimeForegroundServiceTypeResolver` supplies `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` only on Android 14+ so the foreground controller remains replaceable if distribution policy requires a narrower service type later.

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
- queue restore now flows through an explicit `SessionQueueRestoreTransformer` seam, with `AgentSessionRuntimeManager` and projection-only runtime reads both invoking the same recovery-aware rewrite path before `SessionQueue` normalizes restart state
- that explicit restore seam can now also rewrite interrupted runs back to the same queued run when a durable `general_resume` checkpoint proves that post-tool-result continuation is safe
- interrupted runs with `approved_pending_resume` can restore as the same queued run, while `rejected_pending_resume` now restores as a stopped run awaiting the next user instruction instead of being stranded in explicit-retry failure
- interrupted runs with `waiting_approval` can restore as the same suspended run instead of silently changing recovery shape after host rebuild
- live managed-process restores now prefer durable checkpoint replay when a safe `general_resume` checkpoint exists, instead of projecting a reconnect state that cannot yet continue end-to-end
- interrupted restore of an observational `ProcessRead` or `ProcessWait` call can now also auto-resume from the existing durable checkpoint path, but only in the narrow safe case where the pending resumable action still matches the same `process_id` and the associated managed-process reconnect state is already stable (`attached_live` or `completed`)
- live session snapshots and projection-only chat fallback now expose the same managed-process auto-resume eligibility flag used by recovery-aware restore, so diagnostics can distinguish a stable attached live process from a reconnect backoff state before choosing checkpoint replay versus interruption
- file-backed managed-process registries can now reattach a live controller across registry or host rebuild inside the same app process, which avoids falsely marking still-live managed processes as interrupted in that narrow same-process restore case
- managed-process restore is now scoped by runtime controller identity instead of only durable directory, so same-controller live reattach no longer leaks across rebuilt controllers in the same process
- when that live controller is gone, restore now stamps explicit managed-process restore scope metadata (`same_controller`, `same_process_new_controller`, `cross_process`, or `unknown`) plus current process/controller identity, and run lifecycle diagnostics now surface those narrower interrupted-restore reasons
- provider-native builtin web-search observations now emit the same general resume checkpoint metadata as normal tool results, so host rebuild after provider-managed search can still resume from a durable post-tool boundary
- that recovery-aware restore seam now prefers durable journal tail over `lastEvent` summary when feeding recovery decisions
- when an explicit `general_resume` checkpoint is missing, that recovery-aware restore seam can now synthesize one from the durable journal tail or persisted `lastEvent` if that boundary already carries `OpenCrayPromptResumeMetadata`, so safe post-tool-result continuation no longer depends solely on the checkpoint store still being present
- that recovery-aware restore seam can now also synthesize generalized prompt checkpoints from durable `lastResult.metadata` when the checkpoint row and journal tail are both missing but the run result still carries `OpenCrayPromptResumeMetadata`, which closes the paused-run restore gap for retry-exhausted and similar result-backed resumes
- when explicit approval checkpoints are missing, that recovery-aware restore seam can now also synthesize `waiting_approval`, `approved_pending_resume`, or `rejected_pending_resume` from durable approval-denial result metadata plus the durable tail approval event, so host rebuild no longer loses the user's approval state solely because the checkpoint row is missing
- final assistant events now emit an explicit `finalization_complete` checkpoint boundary, and recovery-aware restore now repairs stale running queue entries into durable terminal results when the final assistant event or terminal `ExecutionResult` already proves the run finished
- host-side terminal replay repair now also backfills the pending assistant chat bubble from the durable finalization event or synthesized terminal result, so a rebuilt host no longer leaves the UI stuck on the old "Thinking" placeholder after queue recovery already proved the run finished
- interrupted runs without a recoverable checkpoint now surface as explicit interruption in planner output instead of implying automatic legacy rerun
- `PRE_MODEL_REQUEST` and `ACTION_BATCH_PARSED` now also emit durable journal markers, so those safe boundaries no longer depend solely on the checkpoint store row remaining present after host rebuild
- app-layer session-handle restore is now covered by a real cross-owner managed-process reconnect test: when a rebuilt runtime owner opens the same durable session directory with a new process/controller identity and the backend exposes `ReconnectableManagedProcessControllerFactory`, the live managed process reconnects instead of degrading to `PROCESS_INTERRUPTED_ON_RESTORE`
- runtime-layer `RoutedManagedProcessControllerFactory` now also implements `ReconnectableManagedProcessControllerFactory` by forwarding non-Python-runtime snapshots to its reconnectable default delegate, while explicitly leaving Python-runtime-adapter script executions non-reconnectable until they have a real external controller handle
- retained runtime-owner replacement inside the process-scoped execution controller now keeps the same in-process execution core alive and updates session/runtime lifecycle stamping through shared owner-lifecycle state, so explicit same-process reset no longer interrupts active work just to refresh owner-bound service access
- environment-owned runtime composition is now also narrower end to end: gateway bundle factories no longer hide a default-target overload, local host gateway creation now consumes already-resolved runtime dependencies instead of looking them up implicitly, retained execution-controller bootstrap now derives both its runtime dependency loader and default runtime-owner bootstrap provider from the owning runtime environment, arbitrary ownerless `Context` lookup no longer silently rebuilds a fresh runtime environment, and Flutter bridge plus runtime-target entrypoints now require explicit environment/target or gateway-factory wiring from their real activity/service boundaries instead of silently rebuilding defaults from arbitrary `Context`
- run lifecycle diagnostics now include `runAttempt` and `recoveredFromCheckpointId`: new submissions start at attempt 1, recovery-aware queue rewrites advance the attempt once per recovery basis, and checkpoint-backed recovery rewrites preserve the checkpoint id that justified the restore
- recovery-aware queue rewrites now also append durable `RECOVERY` journal markers that do not project as runtime events, so recovery action/reason, restore epoch, previous lifecycle state, run attempt, and checkpoint id survive alongside the run history without perturbing event replay
- live `ProcessRead` or `ProcessWait` observation tails whose managed process is still present but reconnect is not yet stable now restore into `resume_reconnect_process` / `SUSPENDED` instead of `interrupt_recovery_required`; the rewrite records managed-process reconnect ids, status, recovery state, `retryAfterEpochMs`, and attempt count in task metadata, lifecycle diagnostics, and the `RECOVERY` journal marker, so projection-only and repair readers can see why auto-resume was withheld without replaying the task

Still pending in Phase 2:

- any additional future checkpoint boundaries beyond the current safe-boundary set if product semantics later require them
- further generalized planner integration inside `core` queue restore and detached runtime ownership

Detached-owner foundation first landed in app process and is now carried by the dedicated `:runtime` service process:

- production `OpenCrayHostRuntime` no longer exposes the old app-process `fromContext()` facade path; service-owned host construction now flows through `createForRuntimeService(...)`
- a shared in-process runtime owner now keeps `DefaultAgentSessionRuntimeManager`, run journal/checkpoint stores, supplement store, approval registry, and runtime replay hooks separate from the host facade
- host instances now project both `hostLifecycle` and `runtimeOwnerLifecycle`, which gives a concrete seam for later Android service ownership without changing the UI contract again

First service-host slice landed:

- `OpenCrayAgentRuntimeService` is now declared in the app manifest and started from app bootstrap and host access paths
- the production runtime service now runs in a dedicated `:runtime` process, while runtime ownership and executors still live inside that service process rather than yet moving to a separate controller tier
- the runtime-service process bootstrap now runs when the default process-scoped `RuntimeServiceExecutionControllerProvider` first creates the process-singleton execution controller, and that one-time bootstrap now covers runtime-process-only document support, bundled-skill seeding, and notification-channel registration, so the dedicated service process no longer depends on main-process `Application` bootstrap side effects and service-shell recreate no longer repeats those process-scoped side effects
- the service eagerly initializes the shared in-process runtime owner on `onCreate()`, so owner bootstrap is no longer implicit in `OpenCrayHostRuntime` alone
- the service host now exposes a host-facing runtime access bundle instead of handing `OpenCrayHostRuntime` the raw owner object, which reduces coupling ahead of binder-backed control flow
- `OpenCrayHostRuntime` itself now talks to that owner only through `OpenCrayRuntimeHostAccess`, so session-manager, approval-registry, and journal/checkpoint/supplement store wiring no longer leak into the host facade
- host bootstrap now goes through a formal runtime service client that returns both the runtime snapshot and the host-visible connection state instead of reaching into the service bridge directly
- runtime and shell snapshots now project `runtimeServiceConnectionState`, so binder-backed access and in-process fallback are distinguishable without inferring from implementation details
- shell snapshots now also project `localRuntimeServerState`, so loopback HTTP server startup, listening, and bind-failure diagnostics are visible separately from binder transport state
- service-owned shell/chat diagnostics and host-runtime shell diagnostics now consume that `localRuntimeServerState` through bootstrap or diagnostics-bridge provider seams instead of calling the registry directly at snapshot-render time; the remaining registry references on this path are narrowed to default-provider injection sites
- the retained `RuntimeServiceBootstrapAssembly` and process-scoped execution-controller provider now also receive `localRuntimeServerStateProvider` explicitly instead of resolving `OpenCrayLocalRuntimeServerRegistry` inline during assembly creation, which removes another registry dependency from the retained detached-runtime path and keeps later transport replacement localized to one provider seam
- app bootstrap no longer eagerly ensures `OpenCrayAgentRuntimeService`; it only performs app-level bootstrap plus repair/schedule registration. When the runtime service is later started by an explicit wake or binder-demanding path, that service bootstraps the local loopback runtime server on `onCreate()`, so both Android transports still initialize from the same runtime-service boundary
- `OpenCrayAgentRuntimeService` no longer eagerly attaches a default runtime shell on `onCreate()`; `onStartCommand(...)` and `onBind(...)` now attach the requested `RuntimeServiceTarget`, and `RuntimeServiceShellController` retains target-keyed bootstraps so `INTERACTIVE` and `DETACHED_BACKGROUND` shells can coexist within one Android service instance
- the runtime service client now issues a real asynchronous `bindService(...)` request and keeps projecting connection transitions through the same snapshot field, without blocking synchronous host creation paths
- host observers now refresh shell/runtime snapshots when the service client connection state changes, so later binder attachment is visible without rebuilding the host facade
- production `OpenCrayHostRuntime` construction is now projection-only with respect to session bootstrap: active-session `resume()` plus terminal replay repair moved to one-time runtime service host startup instead of running from every host-facade init path
- caller-side runtime-service entrypoints now only request service start or wake; runtime service host bootstrap happens inside `OpenCrayAgentRuntimeService.onCreate()`, and binder-unavailable snapshot reads now fall back only to the durable runtime-service projection store instead of lazily reaching a live host bridge during first snapshot reads
- durable runtime-service projection persistence and binder-unavailable client fallback are now target-scoped as well, so `INTERACTIVE` and `DETACHED_BACKGROUND` lanes no longer overwrite one another's last projection bucket when the binder is unavailable
- service-backed chat/skills/settings gateways now treat binder fallback as projection-only for reads: `load*` paths may still project through the fallback facade, active `observe*` subscriptions now also warm and retain the binder lease while the UI surface is observing, and mutating or tool-executing operations still fail explicitly unless a binder-backed service gateway is attached
- the shell snapshot surface is now normalized behind `OpenCrayShellGateway`, and both the Flutter bridge and loopback HTTP server prefer a binder-backed service shell gateway for `loadShellSnapshot()` and shell observation when the binder is available
- the execution-facing chat/runtime surface is now normalized behind `OpenCrayChatRuntimeGateway`, and both the Flutter bridge and loopback HTTP server dispatch that path through the gateway instead of calling chat/runtime host methods directly
- the runtime service binder now exposes a service-owned chat/runtime gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for chat/runtime loads and commands when the binder is available
- chat/runtime mutating commands no longer depend on handing the UI a live binder `OpenCrayChatRuntimeGateway` instance first; those writes now flow through an explicit binder dispatch path, while read/observe fallback remains projection-only
- caller-side runtime-service client bundles now default to `INTERACTIVE`, while scheduled wakes, scheduled repairs, detached-control work, and notification approval actions explicitly target `DETACHED_BACKGROUND`; UI chat writes that name a run or task, or submit into an active detached lane, now also resolve the target from durable queue or checkpoint state instead of pinning every write to the interactive client
- chat approval/reject/session-approval decisions now also terminate inside `OpenCrayRuntimeServiceHost` instead of bouncing back through the UI-side host runtime, so the service-owned path owns approval checkpoints, session-scoped grants, snapshot refresh, and sub-agent replay parity
- projection-only chat pending-approval cards now resolve through the same shared approval lookup and approval-presentation helpers used by the service-owned path, including detached approval states that still have a durable run/checkpoint but no queue-task snapshot
- chat/runtime observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the same event channels can follow the service-owned execution path without recreating the Flutter bridge
- the skills-management surface is now normalized behind `OpenCraySkillsGateway`, and both the Flutter bridge and loopback HTTP server dispatch skills snapshot, observation, install, update, delete, inspect, and instructions flows through the same service-preferred boundary
- the runtime service binder now exposes a service-owned skills gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for skills loads and commands whenever binding succeeds
- skills mutating commands no longer depend on handing the UI a live binder `OpenCraySkillsGateway` instance first; those writes now flow through an explicit binder dispatch path, while read/observe fallback remains projection-only
- skills observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, and active observation now keeps the binder lease alive, so the skills page can follow the service-owned runtime path without recreating the host bridge or rebinding for every action
- the settings and runtime-configuration surface is now normalized behind `OpenCraySettingsGateway`, and both the Flutter bridge and loopback HTTP server dispatch settings overview, config loads, and config writes through that same service-preferred boundary
- the runtime service binder now exposes a service-owned settings gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for settings and runtime-config loads or writes whenever binding succeeds
- settings mutating commands now also use an explicit service-owned write-dispatch path instead of requiring the UI to fetch a live binder settings gateway first, so binder-pending reads may still project through fallback but settings writes stay attached to the runtime-service owner
- settings overview observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, and active observation now keeps the binder lease alive, so the settings UI can follow the service-owned runtime path without recreating the host bridge or rebinding for every action
- inside the binder-backed service path, settings overview/detail loads and settings overview observation now also terminate at a service-owned `SettingsFacade` plus local observer fanout, so those settings-home reads no longer depend on the host facade for settings persistence; the remaining host coupling there is narrowed to localized runtime refresh plus chat/skills/settings snapshot fanout
- service-backed shell/chat/skills/settings observers now re-check the active gateway immediately after connection observation registration, which closes the binder-connect race that could otherwise leave a UI stream stuck on projection fallback until a later connection transition
- the Android runtime-service client now treats binder attachment as an idle-released transport lease instead of a permanent process-wide bind: active connection observers keep the binder attached, transient reads or commands schedule an automatic unbind after a short quiet window, and detached execution still belongs to the started service plus keepalive path rather than the UI transport
- runtime-service shell reset decisions, bootstrap-foreground promotion, and wake dispatch parsing now also derive from one explicit `RuntimeServiceIntentDescriptor` parser, which narrows another process-neutral command-protocol seam that had still been duplicated across `RuntimeServiceShellController` helpers and the wake dispatcher
- the runtime service shell now also returns `START_STICKY` whenever detached keepalive or foreground-notification state is still active after `onStartCommand(...)`, while idle/no-work starts remain `START_NOT_STICKY`; this gives Android a restart path for background runtime ownership without redelivering already-consumed mutating wake intents
- foreground-started wake actions (`ACTION_RUN_SCHEDULED_TASK`, `ACTION_REPAIR_SCHEDULES`, `ACTION_RESET_RUNTIME`, `ACTION_RESUME_INTERRUPTED_RUNS`, and supported fire-and-forget chat-write wake intents such as approval/reject/interrupt/retry) now also trigger an immediate bootstrap foreground promotion before service dispatch continues, so scheduled/repair/reset/resume and binder-unavailable chat-write wakes satisfy Android's foreground-service contract even when the actual runtime work has not yet emitted a richer work-state update
- the retained keepalive shell path now also stops the Android service through `stopSelfResult(startId)` rather than an unscoped `stopSelf()`, so delayed idle-grace shutdown uses the same start-id boundary as Android service start sequencing and cannot tear down newer detached work with a stale stop request
- the runtime service shell now binds clients through stable target-scoped delegating binder endpoints rather than exposing one bootstrap-local binder object directly, so same-service runtime reset can rebuild a target shell without leaving already bound clients pinned to the stale pre-reset endpoint
- service-backed shell observation still subscribes passively to connection-state changes, so startup-time shell snapshot streams can continue to watch projection fallback without forcing a bind when the UI only needs read-only shell state
- service-backed chat/runtime, skills, and settings observers now use active connection-state subscriptions instead, so an attached interactive surface warms the binder path early and keeps the service-owned gateway available across nearby user actions rather than falling back into repeated bind-pending writes
- workspace tree/document operations, local file open/share, native toast, twin import probing, and draft attachment import are now isolated behind `OpenCrayLocalHostGateway`, so the Flutter bridge and loopback HTTP server no longer need to hold a full `OpenCrayHostRuntime` just to reach pure local device/workspace capabilities
- `OpenCrayHostRuntime` now implements that same local-only gateway by delegation, which keeps the remaining projection fallback compatible while separating service-owned runtime surfaces from local-only host helpers
- the binder-backed service shell gateway now also reads `AppShellStateStore`, runtime-owner work summary, service lifecycle/work-state, and keepalive state directly rather than delegating shell snapshot/observation through `OpenCrayHostRuntime`, which removes another read-only surface from the monolithic host facade
- that binder-backed service shell path now also derives `hostLifecycle` directly from the live service-lifecycle plus runtime-owner/controller descriptors instead of minting a synthetic host identity, so binder-attached shell diagnostics line up with the actual detached service shell
- the binder-backed service chat gateway now also serves primary chat snapshot, per-run snapshot/wait, runtime snapshot, memory/soul debug reads, search/slice helpers, and memory-debug actions from projection-backed local stores plus service-owned observer fanout; unread badges can still be hydrated from the shared service-owned unread state without routing those reads through `OpenCrayHostRuntime`
- shell, settings, and skills read fallback are now served by dedicated projection-only gateways instead of a full `OpenCrayHostRuntime`, so those surfaces no longer pull the UI-side host facade into existence just to satisfy binder-pending reads
- chat projection fallback now also reads service lifecycle/work metadata only from the client-visible bridge snapshot path instead of performing its own direct service-host registry peek, which keeps binder-pending runtime projection aligned with the same transport-neutral snapshot boundary used by shell projection
- shell and chat projection fallback now source runtime-owner/service lifecycle, work-summary, and keepalive metadata from a durable runtime-service projection store when binder access is unavailable, so binder-pending or host-rebuilt reads no longer need to fall back through a live service-host registry bridge
- the retained projection coordinator now also writes a target-scoped runtime-owner lease heartbeat into a process-safe durable store and carries the latest lease through projection diagnostics, so binder-attached and binder-unavailable reads can distinguish the current held owner, its process/controller ids, a released retained-controller teardown, and the last rejected acquire attempt without consulting process-local state
- projection-only chat fallback now also opens managed-process durable state through a passive `projection_only` restore mode, so binder-pending UI reads no longer repair reconnectable running process snapshots into `PROCESS_INTERRUPTED_ON_RESTORE` from the non-owner process
- projection-only chat pending-approval rendering now reuses the same shared approval lookup/projection helper as the service-owned approval path, so detached approval runs that only have durable run/checkpoint state still surface approval cards even when the queue task snapshot is gone
- the Android runtime-service client no longer defaults legacy `loadSnapshot()/peekSnapshot()` fallback to `OpenCrayRuntimeServiceHostRegistry.peek()` in production; only explicitly injected test/compat bridges can still use that live-host snapshot path
- legacy client snapshot reads now also fall back to the durable runtime-service projection snapshot instead of requiring a live same-process bridge object, which removes the last snapshot/diagnostics blocker for dedicated-process service transport
- the default caller-side runtime-service client still keeps low-level loopback HTTP out of read fallback; projection snapshot fallback remains the only built-in read fallback below the service-backed gateway layer
- service-backed chat/skills/settings writes now prefer binder-backed dispatch but can use the target-scoped loopback command fallback from background threads when binder attach fails; main-thread writes still fail fast unless a non-blocking chat wake fallback is available
- the service-owned loopback HTTP server now receives direct service-owned gateways when started from `OpenCrayAgentRuntimeService`, so same-process runtime HTTP traffic no longer bounces through the client/binder abstraction just to get back into the same service owner
- the loopback runtime server production surface is now provider- and gateway-only in main code; the old `OpenCrayHostRuntime` convenience constructor was removed from production code and replaced by test-local helpers, which keeps the loopback transport aligned with the same host-detached boundary used in service bootstrap
- unsupported remote binder attachment now stays an explicit `invalid_binder` in-process client state; higher-level reads may still project through the explicit projection gateway seam, but production client transport no longer invents a lower-level loopback mode
- caller-side projection shell/chat fallback created from the same client bundle now also shares one explicit `HostRuntimeLifecycleDescriptor`, which removes per-surface synthetic host-identity churn from binder-pending diagnostics
- caller-side projection fallback assembly now resolves through explicit `OpenCrayProjectionGatewayBundleFactory` plus configurable client/service-backed gateway-bundle factories, so the remaining read fallback seam is isolated above the low-level binder client instead of being hardcoded inside it
- caller-side `OpenCrayClientGatewayBundleFactory` now also caches the assembled client gateway bundle per `RuntimeServiceTarget` and reuses one app-scoped `OpenCrayLocalHostGateway`, so ordinary Flutter activity or bridge recreation no longer reassembles the whole service-backed plus projection fallback gateway stack on every attach
- local-only sandbox preview embed resolution no longer depends on `ensureInProcessRuntimeOwner(...)`; that helper is now constructed directly from sandbox settings and persisted E2B session state, which removes another non-service path back into the old owner singleton
- the runtime-service gateway bundle itself is now gateway-shaped instead of host-runtime-shaped: chat/skills/settings writes dispatch through the normalized gateway command surfaces, and chat snapshot invalidation is carried by a service-owned chat gateway capability instead of separate `OpenCrayHostRuntime` function references
- the runtime-service gateway bundle no longer constructs `OpenCrayHostRuntime` at all during service bootstrap; service-owned shell/chat/skills/settings gateways are now assembled directly from projection stores, local facades, and narrowed access seams, and sandbox-session refresh now writes through the shared runtime-owner helper instead of bouncing through the host facade
- that same gateway-bundle dependency surface now also carries explicit owner-observation, chat-mutation, and chat-submission runtime-access facets plus a dedicated `RuntimeServiceApprovalDecisionAccess`, so long-lived service-owned gateway wiring no longer retains a monolithic `OpenCrayRuntimeHostAccess` handle or raw approval lambdas after bootstrap
- service-owned gateway localization now also resolves through a shared host-runtime-strings helper instead of statically calling `OpenCrayHostRuntime` to assemble localized labels, which removes another leftover service-to-host dependency from runtime-service bootstrap
- runtime-owner resolution now flows through an explicit `RuntimeOwnerBootstrap` provider/helper instead of calling `ensureInProcessRuntimeOwner(...)` inline, which keeps the current in-process owner as the default while narrowing the later migration path toward a stronger detached owner
- runtime-service host bootstrap now also resolves Android-backed dependency loading, scheduled-task stores, and trigger registrar wiring through a dedicated bootstrap-factory seam instead of assembling them inline inside `OpenCrayRuntimeServiceHost`, which further narrows the later migration path toward a stronger detached runtime bootstrap
- `OpenCrayAgentRuntimeService` itself now resolves a dedicated `OpenCrayAgentRuntimeServiceBootstrap` bundle during `onCreate()`, instead of directly calling `OpenCrayRuntimeServiceHostRegistry.getOrCreate(...)` or holding a long-lived raw service host on the service shell
- `OpenCrayAgentRuntimeService` now also constructs a dedicated `RuntimeServiceShellController` directly, so the Android `Service` class itself has been narrowed further to lifecycle delegation while process bootstrap, transport startup, bind/start routing, and shutdown handling live behind the shell-controller seam
- the cached runtime-process bootstrap assembly now also retains a `RuntimeServiceProjectionCoordinator`, so projection-store persistence, runtime-notification observation, and scheduled-dispatch outcome fanout survive service-shell recreate instead of living on each service-owned execution coordinator instance
- caller-side runtime-service start, scheduled wake, repair wake, and client acquisition now flow through an environment-owned `RuntimeServiceAccessGateway` rather than static companion entrypoints on `OpenCrayAgentRuntimeService`, so UI- and scheduler-facing code no longer depends directly on the Android service class just to start, wake, or bind the detached runtime owner
- service bootstrap dependency injection now lives behind `RuntimeServiceBootstrapDependencies` plus the service-bootstrap factory seam, so `OpenCrayAgentRuntimeService` itself is narrowed further toward a pure Android lifecycle shell while future detached-owner bootstrap migration no longer has to route through the service class companion
- runtime-service bootstrap factories now also travel as a single `RuntimeServiceBootstrapDependencies` bundle that `OpenCrayAgentRuntimeService` captures once during `onCreate()`, and caller-side start/client dependencies now also travel as `RuntimeServiceAccessDependencies`, which reduces further global seam fanout and narrows test reset back down to one bootstrap bundle plus one access bundle
- `OpenCrayAgentRuntimeServiceBootstrap` and `RuntimeServiceShellController` now keep service attach/start/bind orchestration separate from runtime assembly: the shell still owns lifecycle delegation, while bootstrap consumes the injected `RuntimeServiceBootstrapDependencies` bundle when it assembles the runtime components
- the service-owned wake and binder seams now only consume `RuntimeServiceProjectionCoordinator` and `RuntimeServiceShellStateAccess` instead of the full `RuntimeServiceExecutionCoordinator`, so scheduled wake handling, projection flushes, and binder shell-state reads no longer hold a wider service-owned execution object than they actually need
- runtime-service bootstrap tests now inject `RuntimeServiceBootstrapDependencies` directly at the call site instead of mutating main-code global override seams
- default binder-client construction now also receives its service-start requester and base bind-intent factory through that same `RuntimeServiceAccessDependencies` bundle, so `AndroidBindingOpenCrayRuntimeServiceClient` no longer reaches back into production static runtime-service access helpers while assembling the caller-side transport
- `AndroidBindingOpenCrayRuntimeServiceClient` now also requires those two transport hooks as explicit constructor inputs rather than hiding a default route back to a production static runtime-service access helper, so the binding client no longer carries a static side door to the caller-side access seam
- `AndroidBindingOpenCrayRuntimeServiceClient` now also has an explicit `dispose()` path that clears listeners, cancels pending idle-release work, resets cached binder state, and unbinds when needed, so caller-side runtime reset no longer leaves stale binder transport attached across retained-controller replacement
- runtime-service bind intents, scheduled wake intents, repair intents, and notification approval intents now also flow through a shared `RuntimeServiceIntentFactory`, so the concrete Android service class reference is centralized to a single main-code boundary instead of being re-embedded across bridge, notification, and scheduler code
- that intent-factory boundary now also lives in its own dedicated support file with an injectable component provider, and caller-side start/wake access now only consumes endpoint-built `Intent`s rather than hand-encoding action or extras metadata in the production access gateway
- caller-side transport description is therefore reduced to one `RuntimeServiceEndpoint` seam: the access facade no longer duplicates wake/start action names or extras while assembling runtime-service requests
- loopback runtime-server startup is now also isolated behind a dedicated `RuntimeServiceLoopbackBootstrapFactory`, and the production service path now creates an attach-scoped `OpenCrayLocalRuntimeServer` directly instead of starting through `OpenCrayLocalRuntimeServerRegistry`, so service-owned loopback no longer bounces through the process-global default-provider path
- loopback runtime-server providers now capture the explicit service-owned `OpenCrayRuntimeServiceGatewayBundle` created by the same transport bootstrap, so service-owned runtime HTTP no longer re-reads a mutable retained coordinator bundle just to get back into the same service shell
- that retained transport coordinator now also disposes the previously bound service-owned gateway bundle when a fresh shell rebinds, so service-owned shell/chat observer registrations do not stack up across same-process service recreate
- ordinary service-shell teardown now also releases its own currently bound service-owned gateway bundle through the retained transport coordinator, guarded by bundle instance identity, so an old shell dispose cannot tear down a newer rebound bundle and the retained controller no longer keeps a dead gateway tree pinned until full controller disposal
- the attach-scoped service-owned loopback bootstrap now also disposes its own local runtime server when the Android service shell tears down, so same-process shell reset does not leave an old loopback socket/provider set behind until process death
- service-owned loopback bootstrap now also selects a stable requested port per `RuntimeServiceTarget` (`INTERACTIVE` remains on `42617`, `DETACHED_BACKGROUND` uses a second loopback port), and the retained transport coordinator now resets to that same target-scoped default server-state descriptor instead of collapsing both lanes back onto one shared default
- `AndroidBindingOpenCrayRuntimeServiceClient` now also carries its `runtimeTarget` into lazy durable projection-store creation, so binder-unavailable interactive clients no longer accidentally reopen the detached projection bucket when no explicit projection store was injected
- the old local loopback runtime-server registry plus its `OpenCrayLocalRuntimeServerProvidersFactory` default gateway-bundle path now live only in test compat, so production main code no longer ships a process-global non-service loopback bootstrap seam alongside the service-owned transport bootstrap
- default client-side local/service-backed gateway assembly now resolves through `OpenCrayClientGatewayBundleFactory`, so `OpenCrayFlutterActivity` and `OpenCrayFlutterHostBridge` share one caller-side composition seam even though the production service-owned loopback path no longer consumes that default client bundle
- service-backed shell/chat/skills/settings composition now also resolves through `OpenCrayServiceBackedGatewayBundleFactory`, while service-owned loopback provider fanout resolves through `OpenCrayLocalRuntimeServerProvidersSupport`, so the detached client surface and the service-owned loopback surface still share the same normalized gateway shapes without sharing the old process-global startup path
- shared runtime/service diagnostics-head projection now resolves through `RuntimeServiceDiagnosticsProjectionSupport`, so host runtime, service-owned shell, projection shell, and projection chat no longer each hand-assemble the same lifecycle/work-state/connection map block independently
- `OpenCrayHostRuntime` now carries its service/runtime diagnostics providers and refresh registrars through a dedicated `HostRuntimeDiagnosticsBridge`, which narrows the remaining host-facade coupling down to one read-only adapter instead of a cluster of inline constructor fields
- runtime-service keepalive and foreground controller construction now also resolve through a dedicated shell-control bundle factory seam, so `OpenCrayAgentRuntimeService` no longer hardcodes those controller instances inline before handing them to later bootstrap steps
- runtime-service transport bootstrap now also resolves through a dedicated transport-bootstrap factory seam that assembles the service-owned gateway bundle and starts the loopback runtime server, so `OpenCrayAgentRuntimeService` no longer hardcodes local HTTP transport bootstrap in its entrypoint
- service-owned gateway-bundle assembly inside that transport bootstrap now also resolves through a dedicated `RuntimeServiceGatewayBundleFactory` carried by `RuntimeServiceBootstrapDependencies`, and that factory now consumes a narrowed `RuntimeServiceGatewayBundleDependencies` bundle instead of the whole `OpenCrayRuntimeServiceHost`, so transport bootstrap and gateway composition both depend only on the specific runtime/service surfaces they actually need
- runtime-service observer, projection-persistence, foreground, keepalive, and notification attachment now also resolve through a dedicated execution-coordinator seam, so `OpenCrayAgentRuntimeService` no longer wires those observers and projection-store writes inline in the service entrypoint
- runtime-service wake intent parsing and dispatch now also resolve through a dedicated wake-command-dispatcher seam, so approval notification actions, scheduled dispatch wakes, interrupted-run resume wakes, and schedule repair wakes no longer live inline in `OpenCrayAgentRuntimeService`
- the runtime-service shell controller now honors only explicit runtime-reset requests, disposes the current service shell, replaces retained runtime ownership inside the existing `:runtime` execution controller when possible, and then reattaches a fresh shell before dispatching the reset command; ordinary `ACTION_RESUME_INTERRUPTED_RUNS` repair wakes no longer imply a retained-runtime reset
- `RuntimeServiceIntentFactory` and the environment-owned `RuntimeServiceAccessGateway` now also expose a dedicated `ACTION_RESET_RUNTIME` / `resetRuntime(...)` wake path, and the shell controller recognizes either that explicit action or `EXTRA_FORCE_RUNTIME_RESET`, so app-process callers can request a real service-side retained-runtime reset without mutating the controller singleton in the wrong process
- the service-side shell reset path now reuses the current retained execution controller and swaps only the runtime owner plus owner-bound observers instead of resetting the whole execution-controller provider, so the `:runtime` shell can rebuild retained ownership locally without dropping retained transport, projection, or shell-control state
- app visibility is now also bridged into the `:runtime` process through a lease-based persisted visibility heartbeat plus app-private visibility broadcast, and retained shell keepalive/foreground plus runtime notifications now consume that signal instead of a same-process `AppVisibilityMonitor` static, so runtime-process idle-grace and notification decisions no longer silently assume the app is always backgrounded or react to every transient activity stop as a real background transition
- service-owned wake `Intent` decoding now also resolves through a dedicated `RuntimeServiceWakeIntentParser`, so the dispatcher no longer reads Android action/extra metadata inline and instead operates on parsed wake commands
- runtime-service binder exposure now also resolves through a dedicated binder-endpoint seam, so the local binder object and service-owned chat/skills/settings write-dispatch logic no longer live inline in `OpenCrayAgentRuntimeService`
- those execution-coordinator, wake-dispatcher, and binder-endpoint seams now also consume narrowed `RuntimeServiceExecutionCoordinatorDependencies`, `RuntimeServiceWakeCommandDispatcherDependencies`, and `RuntimeServiceBinderEndpointDependencies` bundles instead of the whole `OpenCrayRuntimeServiceHost`, so the remaining service host object is mostly confined to bootstrap-time assembly and no longer propagated through those long-lived runtime helpers
- service-owned chat/session/schedule/notification consumers now also depend on narrower runtime-access facets such as `RuntimeChatMutationAccess`, `RuntimeChatSubmissionHostAccess`, `RuntimeRunLookupAccess`, and `RuntimeNotificationHostAccess`, so detached runtime helpers no longer all retain the full `OpenCrayRuntimeHostAccess` facade
- service bootstrap now resolves the raw `OpenCrayRuntimeServiceHost` once into a `RuntimeServiceBootstrapState` that only carries pre-derived dependency bundles, and `OpenCrayAgentRuntimeServiceBootstrap` itself no longer exposes a raw `serviceHost`, which tightens the detached-runtime boundary further around one-time bootstrap assembly
- the runtime-service bootstrap dependency bundle no longer exposes a raw `resolveServiceHost(...)` entrypoint; callers and tests now resolve only `RuntimeServiceBootstrapState` plus other narrowed assembled components, which keeps the raw host object behind the bootstrap boundary instead of a bootstrap resolver surface
- `RuntimeServiceBootstrapDependencies` now inject a narrowed `RuntimeServiceBootstrapStateProvider` rather than exposing the execution-controller/provider path directly, so callers and override seams derive only `RuntimeServiceBootstrapState` plus the bootstrap-owned reset seam without gaining a structural handle on the process-scoped controller singleton
- `RuntimeServiceExecutionController` no longer exposes its retained `RuntimeServiceBootstrapAssembly` bag directly either; production now reaches retained projection/transport/shell details only through explicit controller methods or derived bootstrap state, which removes another coarse-grained assembly escape hatch from the detached-runtime core
- production runtime-service bootstrap state now resolves only through the process-singleton `RuntimeServiceExecutionController` and derives `RuntimeServiceBootstrapState` from that controller instead of calling `OpenCrayRuntimeServiceHostRegistry.getOrCreate(...)`, so the runtime bootstrap main path no longer depends on a host-registry singleton or a second bootstrap pipeline
- that production path now reuses the dedicated `RuntimeServiceBootstrapAssembly` already captured by the execution controller and derives the dependency bags from that assembly, so `RuntimeServiceBootstrapState` no longer needs a materialized `OpenCrayRuntimeServiceHost` on the production bootstrap path
- the bootstrap-only assembly/state conversion helpers now live in dedicated runtime-service bootstrap support files rather than `OpenCrayRuntimeServiceHost` or a default resolver singleton, so host-to-bundle derivation no longer leaks across transport, wake, binder, or scheduled-task files
- runtime-owner access/replay-access seams and runtime-service lifecycle/work-state carrier types now also live in dedicated support files instead of `OpenCrayRuntimeServiceHost`, so the host file no longer doubles as the shared detached-runtime type bucket for process-owned execution state
- the remaining production bootstrap seam names are now host-free as well: `RuntimeServiceBootstrapFactory`, `RuntimeServiceBootstrapParts`, `bootstrapRuntimeServiceSessions(...)`, and `resumeInterruptedRuntimeServiceRuns(...)` now describe detached runtime-service behavior directly, and the old `OpenCrayRuntimeServiceHost` / `OpenCrayRuntimeServiceHostRegistry` compatibility layer has been removed from `main` code and left only as test-local fixture support
- runtime-service bootstrap assembly is now service-neutral, and `OpenCrayAgentRuntimeService` resolves a process-singleton `RuntimeServiceExecutionController` from the default process-scoped `RuntimeServiceExecutionControllerProvider` before deriving the current service shell's `RuntimeServiceBootstrapState`
- that means service recreate inside the same runtime process now reuses the existing execution assembly and only mints a fresh `RuntimeServiceLifecycleDescriptor` for the new shell instance, instead of re-running runtime-owner bootstrap, session bootstrap, or projection/wake/binder dependency assembly from scratch
- `RuntimeServiceShellController` reset now only targets the already-attached `RuntimeServiceExecutionController` carried by the current shell bootstrap, and the old default-controller `peek/reset/swap` helpers now live only in test compat, so production service reset no longer reaches back into a hidden global controller singleton outside its explicit bootstrap path
- that shell reset path now also receives only the explicit `resetRuntimeOwner()` seam from `OpenCrayAgentRuntimeServiceBootstrap`, so the Android service shell no longer needs the full execution-controller surface merely to rotate retained runtime ownership
- the production `RuntimeServiceExecutionControllerProvider` surface is now resolve-only as well; the old mutable provider contract has been removed from `main`, and `peek/reset/swap` survive only as concrete test hooks on `ProcessScopedRuntimeServiceExecutionControllerProvider`, so the detached-runtime bootstrap interface no longer advertises controller mutation outside test compat
- runtime-owner resolution now flows through an explicit `RuntimeOwnerBootstrap` provider/helper, and the process-scoped execution-controller provider now resolves a narrowed `RuntimeExecutionDependencies` bag whose retained owner slice is only `RuntimeOwnerBootstrapDependencies`, so process-owned runtime owner creation is explicit without carrying a second owner-level process singleton alongside the execution controller or leaking the full runtime context bag into the controller path
- `RuntimeServiceBootstrapFactory` now consumes already-resolved runtime dependencies plus the explicit lifecycle/access facets surfaced through `RuntimeOwnerBootstrap`, and the execution controller only carries controller lifecycle plus the service-neutral bootstrap assembly, which means service bootstrap no longer reloads runtime dependencies, retain the full owner-controller shell, or reconstruct a monolithic owner-access bundle inline once the runtime-process execution controller exists
- that same process-retained `RuntimeServiceBootstrapAssembly` now stores only explicit `runtimeOwnerLifecycle`, `runtimeHostAccess`, and `runtimeReplayAccess` facets instead of retaining the old monolithic runtime-owner access bundle, so service-recreate-time execution state no longer drags transcript or memory-write surfaces through the long-lived controller path
- `RuntimeOwnerBootstrap` and `RuntimeServiceBootstrapFactory` now both expose only the lifecycle/access or scheduled-task infrastructure slices actually consumed by bootstrap, so the controller/bootstrap path no longer passes a monolithic runtime-owner access bundle or duplicated runtime dependencies through factory-return structs just to reach the final assembly
- `RuntimeOwnerBootstrap` now also exposes separated observation/notification/approval/chat-submission runtime-access facets directly instead of retaining a monolithic `OpenCrayRuntimeHostAccess` field, so the process-owned execution-controller/bootstrap path no longer routes retained service state through that combined host facade type at all
- runtime-service projection persistence and runtime notification observation now also live behind a retained `RuntimeServiceProjectionCoordinator` inside the process-owned bootstrap assembly, so service recreate no longer restarts those runtime-owner/work-state observers or their durable projection/notification side effects
- that retained projection coordinator now also consumes only `RuntimeOwnerObservationAccess` for snapshot persistence, while approval/run lookup remains isolated inside `RuntimeNotificationCoordinator`, so service-recreate-time projection writes no longer retain a wider notification/run-lookup surface than they actually use
- that retained projection/bootstrap layer now also exposes explicit dispose paths for controller teardown, including projection observer shutdown, current gateway-bundle release, and retained shell-control teardown, so future controller replacement does not have to rely on process death to clean observer state
- the remaining `OpenCrayRuntimeServiceAccess` name is now test-only compat; production retained-runtime reset/replace now drops stale binding transport through environment-owned gateways and shell bootstrap seams instead of mutating controller singletons through a production static facade
- that retained bootstrap assembly now also stores a narrowed `RuntimeServiceBootstrapContext` plus `RuntimeServiceBootstrapRuntimeAccess` instead of the whole runtime dependency bag and monolithic `OpenCrayRuntimeHostAccess`, so the process-owned controller path retains only the localized store/facade/runtime-access slices still needed after one-time bootstrap
- the remaining service-owned `RuntimeServiceExecutionCoordinator` now acts as a thinner shell adapter for keepalive/foreground transitions plus explicit projection flushes, instead of owning notification delivery, runtime-owner observation, and projection-store persistence directly
- `RuntimeServiceExecutionCoordinatorDependencies` is now also trimmed down again to just the retained `RuntimeServiceProjectionCoordinator` plus `RuntimeServiceWorkStateTracker`, so the service-owned coordinator no longer receives stale owner lifecycle, host access, service lifecycle, local-runtime-server, localization, chat-store, or scheduled-task persistence dependencies it does not consume
- remaining legacy host/owner bootstrap shims such as the direct `createRuntimeServiceBootstrapState(...)` shortcut and the old in-process owner registry are now test-only compat scaffolds rather than main-code runtime paths
- `OpenCrayHostRuntime.createForTest(...)` now also lives in test compat as a companion extension layered over a neutral `createWithRuntimeAccess(...)` bridge, so production main code no longer ships a test-named host-runtime constructor or its in-memory supplement/executor defaults
- target-aware write routing is now also shared outside notifications: the service-backed chat gateway resolves `approve`, `reject`, `retry`, `interrupt`, and active-session `submit` commands through the same runtime-target rules already used by scheduled wake and notification approval routing, which removes the last obvious UI-side hard pin back to the interactive client for those paths
- remaining local settings/personalization/MCP facade factories are now host-neutral `create(...)` helpers rather than `createForTest(...)` members in `main`, so production code no longer calls test-named facade constructors just to rebuild localized runtime dependencies
- runtime-service approval/session-approval/reject handling now resolves through a dedicated `RuntimeServiceApprovalDecisionAccess`, and the wake/binder/gateway dependency bundles now bind to that access plus the host-free interrupted-run repair helper instead of closing directly over `OpenCrayRuntimeServiceHost` approval methods
- binder-endpoint snapshot loading now also resolves through narrowed `RuntimeServiceBridgeSnapshotDependencies` instead of a host-backed snapshot lambda, so post-bootstrap binder snapshot reads no longer close over the raw `OpenCrayRuntimeServiceHost`
- scheduled-task wake dispatch and repair now also flow through explicit `ScheduledTaskDispatcherDependencies` and `ScheduledTaskRepairDependencies` captured inside `RuntimeServiceWakeCommandDispatcherDependencies`, so that wake path no longer relies on `OpenCrayRuntimeServiceHost` extension methods after bootstrap
- the old in-process/existing fallback runtime-service bridge compat layer has now been removed from main code entirely; production binder clients read binder snapshots directly and otherwise fall back only to durable projection snapshots
- runtime-service bridge snapshots now also carry explicit `runtimeOwnerLifecycle` and `runtimeOwnerWorkSummary` fields, so projection snapshots and binder diagnostics no longer need to walk back through the old monolithic runtime-owner access payload on the read path
- client-delivered shell and chat-runtime snapshots now also carry explicit `flutterAppInstanceId` and `bridgeInstanceId`, with the Android host bridge supplying the native bridge identity and both Flutter transports normalizing those IDs into the same snapshot models for diagnostics
- bridge-default runtime-service start and base-bind-intent lookup now also route back through the environment-owned `RuntimeServiceAccessGateway`, so direct Android service-intent construction is fully centralized inside the access boundary instead of lingering in caller-side bridge defaults
- notification-settings load/save inside the service-owned settings gateway now read and write the same notification settings store used by runtime delivery, instead of routing that slice back through `OpenCrayHostRuntime`
- sandbox load/save inside the service-owned settings gateway now resolve through a narrowed sandbox-settings access boundary plus the existing sandbox payload mappers, so that repository-backed settings slice no longer needs to bounce back through `OpenCrayHostRuntime`
- network-search load/save and media-speech load/save inside the service-owned settings gateway now terminate at `LocalNetworkSearchConfigFacade` and `LocalMediaSpeechSettingsFacade`, so those facade-backed settings slices also no longer bounce back through `OpenCrayHostRuntime`
- strong-background capability snapshot/actions inside the service-owned settings gateway now terminate at `AndroidStrongBackgroundSettingsAccess`, while preserving the projected `runtimeServiceConnectionState` field on the binder-owned snapshot shape, so that Android-local settings slice no longer bounces back through `OpenCrayHostRuntime`
- personalization load/save/reset inside the service-owned settings gateway now terminate at `LocalPersonalizationFacade`, and app-language persistence now also writes through a service-owned app-language access seam; localized refresh plus chat/skills/settings snapshot fanout now stay inside the service-owned gateway bundle, so binder-connected reads no longer need to route the language write itself back through `OpenCrayHostRuntime`
- safety load/save inside the service-owned settings gateway now terminate at `LocalSafetySettingsFacade`, and service-owned safety saves emit a narrowed chat-snapshot notifier instead of routing that slice back through the monolithic host save path
- LLM config load/save/custom-provider/validate and MCP settings load/master-toggle/per-server-toggle inside the service-owned settings gateway now terminate at `LocalLlmConfigFacade` and `LocalMcpSettingsFacade`, so those facade-backed settings slices no longer bounce back through `OpenCrayHostRuntime`
- service-owned network-search and media-speech saves now also emit a narrowed settings-overview notifier through the runtime-service gateway bundle, so settings overview observers keep updating without routing those writes back through the monolithic host save path
- the service-owned skills gateway now serves skills snapshot/observation, install and batch-install flows, update check/update flows, source inspection, instructions loading, install-source activation, and local skills mutations directly from `LocalSkillsFacade`, while using a narrowed skills snapshot notifier instead of routing those flows back through `OpenCrayHostRuntime`
- projection-only skills fallback is now strictly local-only: it filters the local snapshot and local instructions without issuing `SkillsList` or `SkillsFind`, which keeps tool-executing skills discovery on the binder-owned pipeline
- the existing repair worker can now preflight interrupted interactive repair candidates from non-terminal queue snapshots, prompt checkpoints, durable background subagent handles, durable run-record-only evidence, or non-terminal journal-tail-only evidence and wake the runtime service with `ACTION_RESUME_INTERRUPTED_RUNS`
- runtime-service interrupted-run repair and bootstrap session scan now reuse that same durable per-session repair predicate, so queue-snapshot, prompt-checkpoint, durable background-subagent, run-record-only, and non-terminal journal-tail-only recovery candidates no longer depend on whether the wake path started from WorkManager preflight or from the service-side rescan itself; terminal run records and final journal tails stay out of interrupted-work detection
- interrupted-run repair scanning now materializes typed `InterruptedRunRepairEvidence` entries for queue tasks, prompt checkpoints, detached subagent handles, run records, journal tails, and managed-process reconnect backoff; queue-backed repair evidence is now based on the recovery-aware queue projection when run-record and journal stores are available, so already durable terminal-result repairs no longer surface as live queue repair candidates, and that evidence also carries the run ownership tier with a `runtime_process` default for older task metadata plus the durable runtime-controller id when task metadata has one; `RuntimeServiceBootstrapResult` and `RuntimeServiceInterruptedRunRepairResult` carry those entries by session, the retained projection coordinator persists the latest bootstrap/resume repair projection into the target-scoped runtime-service projection snapshot for binder-unavailable diagnostics, journal-tail-only evidence can inherit a matching scheduled/detached task target, `retryAfterEpochMs` reconnect evidence is held until its due time through delayed repair registration from both WorkManager preflight and service-side bootstrap/resume result handling, and `FINALIZATION_COMPLETE` checkpoints are excluded from repair preflight because they are terminal evidence rather than resumable work
- retained runtime-owner lease heartbeat is now target-scoped and process-safe: the runtime-process owner periodically renews a durable held lease, projection flushes carry that lease into diagnostics, stale owner writes cannot overwrite a newer lease, retained-controller disposal writes a released lease for teardown evidence, and denied service attach attempts enqueue an owner-lease-expiry repair instead of waiting indefinitely for another external wake
- denied service attach attempts now also tear down their just-created shell/transport/execution bootstrap immediately, and retained projection/notification observers do not activate until a shell has actually attached with the owner lease, so a failed attach cannot leave a dormant detached-shell projection writer or lease heartbeat alive inside the shared runtime process
- runtime notification settings now also persist as one target-neutral durable snapshot under the shared runtime root instead of multi-key `SharedPreferences`, so main-process settings writes and detached runtime notification reads share one process-safe source of truth for quiet hours, delivery mode, and event-channel toggles
- sandbox execution settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process settings changes and runtime-process execution routing converge on one process-safe source of truth for backend/session/timeout state
- E2B sandbox-session resume state now also persists as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process preview/session controls and runtime-process sandbox reconnect/resume converge on one process-safe active-session source of truth
- safety policy settings and live-context mode now also persist as runtime-root durable snapshots instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when each durable snapshot is empty, so app-process settings saves and runtime-process tool-policy/live-context reads converge on one process-safe source of truth for automation mode, external access, tool overrides, subagent context policy, and live context mode
- network-search settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process settings saves and runtime-process search-tool routing converge on one process-safe slots source of truth
- MCP master settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process settings saves and runtime-process MCP tool-surface reads converge on one process-safe global enable source of truth
- LLM config settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process provider/model edits, saved custom providers, and runtime-process model routing/capability-cache reads converge on one process-safe config source of truth
- media/speech settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process media/STT provider edits and runtime-process media tool routing converge on one process-safe provider source of truth
- app-language settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so service-owned settings writes, projection fallback, and runtime-localized labels converge on one process-safe language source of truth
- app-shell navigation state now also persists as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing SharedPreferences state is migrated when the durable snapshot is empty, so app-process launches and service/projection shell readers converge on one process-safe selected tab/settings subpage source of truth
- skills enablement state now also persists as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing `enabled:<skill>` preference values are migrated when the durable snapshot is empty, so app-process toggles, service-owned skills reads, projection fallback, and runtime tool-surface loading converge on one process-safe enablement source of truth
- telemetry/privacy-guard settings now also persist as one runtime-root durable snapshot instead of defaulting to multi-key `SharedPreferences`; existing telemetry preference values are migrated when the durable snapshot is empty, so settings UI saves, service-owned settings reads, and projection fallback converge on one process-safe telemetry/privacy source of truth
- schedule-side notification actions now also have a runtime-service command path: skipped-busy and dispatch-failed schedule notifications can emit `RUN_SCHEDULE_NOW`, `DISABLE_SCHEDULE`, or `SNOOZE_SCHEDULE` actions through the same stable command envelope, wake the detached runtime target in the foreground, and either re-dispatch the schedule as a normal manual scheduled trigger, disable the durable schedule with trigger re-sync, or persist a short schedule deferral without rewriting the original trigger
- this is still a same runtime-process owner/executor; service-shell recreate is now narrower because it reuses a process-level execution controller, and managed-process restore inside that runtime process is now controller-aware with a tested cross-owner reconnect path for reconnectable controllers, but stronger detached ownership beyond that runtime process remains a later slice

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

`finalization_complete` is terminal repair evidence, not a resumable prompt checkpoint. Recovery should complete the run from the durable final assistant event or terminal `ExecutionResult`, not enqueue another prompt execution.

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

- `restore_terminal_result`
- `resume_from_checkpoint`
- `resume_waiting_for_approval`
- `stop_rejected_awaiting_direction`
- `resume_reconnect_process`
- `interrupt_recovery_required`

Current implementation note:

- queue restore now prefers `resume_from_checkpoint` whenever a durable safe checkpoint exists, even if a managed process is still live
- interrupted restore of checkpoint-backed `ProcessRead` or `ProcessWait` observation now also uses `resume_from_checkpoint` in the narrow safe case above, while live observation tails with `connecting`, `retry_scheduled`, or otherwise ambiguous reconnect state now stay suspended in `resume_reconnect_process` with durable reconnect evidence instead of replaying the task or degrading to a generic interruption
- `stop_rejected_awaiting_direction` is now used for durable rejected-approval restores so the run stays stopped instead of re-entering a queued resume path
- `resume_reconnect_process` is now wired through recovery-aware queue restore for live observation reconnect backoff, including task metadata, lifecycle diagnostics, and `RECOVERY` journal evidence; true controller-level reconnect without checkpoint replay is still not implemented end-to-end across runtime-process death
- plain queued work with no prior execution evidence now stays plain queued with no recovery action
- queued work that shows prior execution progress but has no durable checkpoint is now converted into explicit interrupted retry state instead of silently replaying from task input

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

This recommendation is now historical rather than current.

The production runtime already uses a dedicated `com.opencray.app:runtime` service process.
Runtime service bootstrap now also refuses to create the runtime owner when the observed process name does not match that dedicated process.

What remains true from the original recommendation is the rollout constraint:

- keep execution ownership explicit and process-safe
- do not reintroduce UI-side singleton shortcuts just because the runtime now lives out of process
- keep binder/projection fallback semantics explicit until stronger detached ownership lands

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

Prepare future scheduled execution and recurring automation without reintroducing UI ownership.

### Current implementation status

Already landed in app process:

- scheduled task registry, durable spec storage, and run records
- `AlarmManager` wake path plus `WorkManager` wake and repair path
- runtime-service wake entrypoints for scheduled dispatch, schedule repair, and interrupted-run resume repair
- runtime-service command envelopes with explicit version/kind checks that reject mismatched explicit command versions before foreground/reset dispatch
- shared durable text storage now uses per-file sidecar OS locks around read/write/delete/update, key JSON read-modify-write stores built on `DirectoryDurableTextStorage` now update under one file lock instead of split load/save operations, scheduled run-record deletion/pruning now uses the same locked update path instead of a stale load/save snapshot, runtime notification-delivery dedupe writes now preserve newer terminal-notification fingerprints written by overlapping foreground/service/repair flows, mid-loop supplement append/consume now preserves newer injected supplements across overlapping foreground/service/repair paths, and transcript fallback append/replace/repair no longer overwrites newer transcript events from another owner path
- app-start and boot/package-replaced registration now keep a unique periodic repair worker alive for recurring schedule/interrupted-run reconciliation
- the recurring repair precheck is now target-aware and recovery-aware for queue snapshots: persisted scheduled-task metadata, detached-control metadata, checkpoint-linked queue tasks, durable background subagent handles, managed-process reconnect evidence tied to scheduled/detached queue tasks, queue-stored `resume_reconnect_process` evidence with process id and retry-after metadata, journal-tail-only evidence that can be matched back to a scheduled/detached queue task, and target-scoped runtime-service projection work-summary session ids can wake `DETACHED_BACKGROUND` when no stronger durable evidence already exists for that session, while checkpoint-only, run-record-only, and unmatched non-terminal journal-tail-only repair remains conservative `INTERACTIVE`; future reconnect retry evidence schedules a delayed repair at `retryAfterEpochMs` instead of waiting for the next hourly periodic pass, generic future interrupted-run evidence is re-enqueued under `interrupted_run_retry`, same-run/task repair evidence can no longer wake the service ahead of that reconnect retry deadline, terminal-result queue repairs are filtered through the same recovery-aware projection before wake classification, and service-side repair projection now records and re-enqueues the next pending repair deadline explicitly
- approval notification approve/reject actions now execute through `OpenCrayRuntimeServiceHost` directly instead of routing the service wake path through `OpenCrayHostRuntime`
- schedule, approval, completion/interruption, and active-runtime notification surfaces
- schedule notification open/detail routing to the Notifications & Background settings entrypoint with `notificationScheduleId` launch metadata, plus retry/manual-run, disable, and 15-minute snooze actions for skipped-busy and dispatch-failed schedule outcomes, per-run Cancel for accepted scheduled runs, and terminal interrupted-run Retry with stale-notification dismissal, all routed through stable runtime-service command envelopes

Still pending in this phase:

- richer schedule-side notification actions beyond the current notifications/background detail entrypoint, retry/manual-run, disable, fixed snooze, and accepted-run Cancel flows, such as a concrete schedule management/detail page keyed by the preserved `notificationScheduleId`
- broader autonomous repair policy for interrupted runs beyond the current target-aware typed-evidence `WorkManager` precheck, projection work-summary fallback evidence, managed-process reconnect delayed repair, owner-lease-expiry delayed repair, periodic repair registration, and `ACTION_RESUME_INTERRUPTED_RUNS` wake-and-resume scan
- any future periodic/task-automation expansions that need more than the current wake/dispatch bridge

### Components in this phase

- scheduled task registry
- `WorkManager` trigger bridge
- service wake entrypoints
- notification actions for approval, open, terminal interrupted-run retry, retry/manual scheduled-run, disable-schedule, and snooze-schedule flows

### Behavior

- `WorkManager` should wake the runtime service
- the service should create or recover the target task
- the task should run under the same queue, journal, and checkpoint model as interactive runs

### Success criteria

- delayed tasks and future scheduled tasks do not need an open UI
- scheduled execution shares the same recovery semantics as interactive runs

## Module Impact

### `app/`

- add process-safe run journal storage
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
