# Runtime Foundation Delivery Plan

Last updated: 2026-04-07

## Status

Phase 1 complete; Phase 2 approval-boundary and generalized checkpoint restore slice substantially implemented; in-process detached-owner foundation, same-process service host, foreground keepalive, scheduled wake bridges, a first interrupted-run repair wake path, service-owned approval notification handling, and a dedicated service wake-command dispatcher seam landed. True detached ownership and controller-level managed-process reconnect remain pending.

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
- app-layer restore can now also synthesize generalized prompt checkpoints from durable `lastResult.metadata` when the checkpoint row and journal tail are both missing but the run result still carries `OpenCrayPromptResumeMetadata`, which closes the paused-run restore gap for retry-exhausted and similar result-backed resumes
- when explicit approval checkpoints are missing, app-layer restore can now also synthesize `waiting_approval`, `approved_pending_resume`, or `rejected_pending_resume` from durable approval-denial result metadata plus the durable tail approval event, so host rebuild no longer loses the user's approval state solely because the checkpoint row is missing
- interrupted runs without a recoverable checkpoint now surface as explicit interruption in planner output instead of implying automatic legacy rerun
- `PRE_MODEL_REQUEST` and `ACTION_BATCH_PARSED` now also emit durable journal markers, so those safe boundaries no longer depend solely on the checkpoint store row remaining present after host rebuild

Still pending in Phase 2:

- any additional future checkpoint boundaries beyond the current safe-boundary set if product semantics later require them
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
- app bootstrap no longer eagerly ensures `OpenCrayAgentRuntimeService`; it only performs app-level bootstrap plus repair/schedule registration. When the runtime service is later started by an explicit wake or binder-demanding path, that service bootstraps the local loopback runtime server on `onCreate()`, so both Android transports still initialize from the same runtime-service boundary
- the runtime service client now issues a real asynchronous `bindService(...)` request and keeps projecting connection transitions through the same snapshot field, without blocking synchronous host creation paths
- host observers now refresh shell/runtime snapshots when the service client connection state changes, so later binder attachment is visible without rebuilding the host facade
- production `OpenCrayHostRuntime` construction is now projection-only with respect to session bootstrap: active-session `resume()` plus terminal replay repair moved to one-time runtime service host startup instead of running from every host-facade init path
- caller-side runtime-service entrypoints now only request service start or wake; runtime service host bootstrap happens inside `OpenCrayAgentRuntimeService.onCreate()`, and the binding client fallback bridge only projects an already-initialized host without lazily `getOrCreate(...)`ing the runtime host during first snapshot reads
- service-backed chat/skills/settings gateways now treat binder fallback as projection-only for reads: `load*` and `observe*` paths may still project through the fallback facade, but mutating or tool-executing operations now fail explicitly unless a binder-backed service gateway is attached
- the shell snapshot surface is now normalized behind `OpenCrayShellGateway`, and both the Flutter bridge and loopback HTTP server prefer a binder-backed service shell gateway for `loadShellSnapshot()` and shell observation when the binder is available
- the execution-facing chat/runtime surface is now normalized behind `OpenCrayChatRuntimeGateway`, and both the Flutter bridge and loopback HTTP server dispatch that path through the gateway instead of calling chat/runtime host methods directly
- the runtime service binder now exposes a service-owned chat/runtime gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for chat/runtime loads and commands when the binder is available
- chat/runtime mutating commands no longer depend on handing the UI a live binder `OpenCrayChatRuntimeGateway` instance first; those writes now flow through an explicit binder dispatch path, while read/observe fallback remains projection-only
- chat approval/reject/session-approval decisions now also terminate inside `OpenCrayRuntimeServiceHost` instead of bouncing back through the UI-side host runtime, so the service-owned path owns approval checkpoints, session-scoped grants, snapshot refresh, and sub-agent replay parity
- projection-only chat pending-approval cards now resolve through the same shared approval lookup and approval-presentation helpers used by the service-owned path, including detached approval states that still have a durable run/checkpoint but no queue-task snapshot
- chat/runtime observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the same event channels can follow the service-owned execution path without recreating the Flutter bridge
- the skills-management surface is now normalized behind `OpenCraySkillsGateway`, and both the Flutter bridge and loopback HTTP server dispatch skills snapshot, observation, install, update, delete, inspect, and instructions flows through the same service-preferred boundary
- the runtime service binder now exposes a service-owned skills gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for skills loads and commands whenever binding succeeds
- skills mutating commands no longer depend on handing the UI a live binder `OpenCraySkillsGateway` instance first; those writes now flow through an explicit binder dispatch path, while read/observe fallback remains projection-only
- skills observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the skills page can follow the service-owned runtime path without recreating the host bridge
- the settings and runtime-configuration surface is now normalized behind `OpenCraySettingsGateway`, and both the Flutter bridge and loopback HTTP server dispatch settings overview, config loads, and config writes through that same service-preferred boundary
- the runtime service binder now exposes a service-owned settings gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for settings and runtime-config loads or writes whenever binding succeeds
- settings mutating commands now also use an explicit service-owned write-dispatch path instead of requiring the UI to fetch a live binder settings gateway first, so binder-pending reads may still project through fallback but settings writes stay attached to the runtime-service owner
- settings overview observers now switch dynamically between the fallback host gateway and the binder-backed service gateway based on service connection state, so the settings UI can follow the service-owned runtime path without recreating the host bridge
- inside the binder-backed service path, settings overview/detail loads and settings overview observation now also terminate at a service-owned `SettingsFacade` plus local observer fanout, so those settings-home reads no longer depend on the host facade for settings persistence; the remaining host coupling there is narrowed to localized runtime refresh plus chat/skills/settings snapshot fanout
- service-backed shell/chat/skills/settings observers now re-check the active gateway immediately after connection observation registration, which closes the binder-connect race that could otherwise leave a UI stream stuck on projection fallback until a later connection transition
- the Android runtime-service client now treats binder attachment as an idle-released transport lease instead of a permanent process-wide bind: active connection observers keep the binder attached, transient reads or commands schedule an automatic unbind after a short quiet window, and detached execution still belongs to the started service plus keepalive path rather than the UI transport
- service-backed shell/chat/skills/settings read observers now subscribe passively to connection-state changes, so startup-time UI snapshot streams no longer trigger runtime-service start/bind just to watch projection fallback state
- workspace tree/document operations, local file open/share, native toast, twin import probing, and draft attachment import are now isolated behind `OpenCrayLocalHostGateway`, so the Flutter bridge and loopback HTTP server no longer need to hold a full `OpenCrayHostRuntime` just to reach pure local device/workspace capabilities
- `OpenCrayHostRuntime` now implements that same local-only gateway by delegation, which keeps the remaining projection fallback compatible while separating service-owned runtime surfaces from local-only host helpers
- the binder-backed service shell gateway now also reads `AppShellStateStore`, runtime-owner work summary, service lifecycle/work-state, and keepalive state directly rather than delegating shell snapshot/observation through `OpenCrayHostRuntime`, which removes another read-only surface from the monolithic host facade
- the binder-backed service chat gateway now also serves primary chat snapshot, per-run snapshot/wait, runtime snapshot, memory/soul debug reads, search/slice helpers, and memory-debug actions from projection-backed local stores plus service-owned observer fanout; unread badges can still be hydrated from the shared service-owned unread state without routing those reads through `OpenCrayHostRuntime`
- shell, settings, and skills read fallback are now served by dedicated projection-only gateways instead of a full `OpenCrayHostRuntime`, so those surfaces no longer pull the UI-side host facade into existence just to satisfy binder-pending reads
- chat projection fallback now also reads service lifecycle/work metadata only from the client-visible bridge snapshot path instead of performing its own direct service-host registry peek, which keeps binder-pending runtime projection aligned with the same transport-neutral snapshot boundary used by shell projection
- shell and chat projection fallback now source runtime-owner/service lifecycle, work-summary, and keepalive metadata from a durable runtime-service projection store when binder access is unavailable, so binder-pending or host-rebuilt reads no longer need to fall back through a live service-host registry bridge
- projection-only chat pending-approval rendering now reuses the same shared approval lookup/projection helper as the service-owned approval path, so detached approval runs that only have durable run/checkpoint state still surface approval cards even when the queue task snapshot is gone
- the Android runtime-service client no longer defaults legacy `loadSnapshot()/peekSnapshot()` fallback to `OpenCrayRuntimeServiceHostRegistry.peek()` in production; only explicitly injected test/compat bridges can still use that live-host snapshot path
- the service-owned loopback HTTP server now receives direct service-owned gateways when started from `OpenCrayAgentRuntimeService`, so same-process runtime HTTP traffic no longer bounces through the client/binder abstraction just to get back into the same service owner
- the loopback runtime server production surface is now provider- and gateway-only in main code; the old `OpenCrayHostRuntime` convenience constructor was removed from production code and replaced by test-local helpers, which keeps the loopback transport aligned with the same host-detached boundary used in service bootstrap
- local-only sandbox preview embed resolution no longer depends on `ensureInProcessRuntimeOwner(...)`; that helper is now constructed directly from sandbox settings and persisted E2B session state, which removes another non-service path back into the old owner singleton
- the runtime-service gateway bundle itself is now gateway-shaped instead of host-runtime-shaped: chat/skills/settings writes dispatch through the normalized gateway command surfaces, and chat snapshot invalidation is carried by a service-owned chat gateway capability instead of separate `OpenCrayHostRuntime` function references
- the runtime-service gateway bundle no longer constructs `OpenCrayHostRuntime` at all during service bootstrap; service-owned shell/chat/skills/settings gateways are now assembled directly from projection stores, local facades, and narrowed access seams, and sandbox-session refresh now writes through the shared runtime-owner helper instead of bouncing through the host facade
- service-owned gateway localization now also resolves through a shared host-runtime-strings helper instead of statically calling `OpenCrayHostRuntime` to assemble localized labels, which removes another leftover service-to-host dependency from runtime-service bootstrap
- runtime-service host bootstrap now resolves `OpenCrayRuntimeOwnerAccess` through a dedicated owner-access factory seam instead of calling `ensureInProcessRuntimeOwner(...)` inline, which keeps the current in-process owner as the default while narrowing the later migration path toward a stronger detached owner
- runtime-service host bootstrap now also resolves Android-backed dependency loading, scheduled-task stores, and trigger registrar wiring through a dedicated bootstrap-factory seam instead of assembling them inline inside `OpenCrayRuntimeServiceHost`, which further narrows the later migration path toward a stronger detached runtime bootstrap
- `OpenCrayAgentRuntimeService` itself now resolves a dedicated `OpenCrayAgentRuntimeServiceBootstrap` bundle during `onCreate()`, instead of directly calling `OpenCrayRuntimeServiceHostRegistry.getOrCreate(...)` or holding a long-lived raw service host on the service shell
- caller-side runtime-service start, scheduled wake, repair wake, and client acquisition now flow through a dedicated `OpenCrayRuntimeServiceAccess` facade rather than static companion entrypoints on `OpenCrayAgentRuntimeService`, so UI- and scheduler-facing code no longer depends directly on the Android service class just to start, wake, or bind the detached runtime owner
- service bootstrap dependency injection and factory overrides now live behind a dedicated `RuntimeServiceBootstrapRegistry`, so `OpenCrayAgentRuntimeService` itself is narrowed further toward a pure Android lifecycle shell while test seams and future detached-owner bootstrap migration no longer have to route through the service class companion
- runtime-service bootstrap factories now also travel as a single `RuntimeServiceBootstrapDependencies` bundle that `OpenCrayAgentRuntimeService` captures once during `onCreate()`, and caller-side start/client dependencies now also travel as `RuntimeServiceAccessDependencies`, which reduces further global seam fanout and narrows test reset back down to one bootstrap bundle plus one access bundle
- default binder-client construction now also receives its service-start requester and base bind-intent factory through that same `RuntimeServiceAccessDependencies` bundle, so `AndroidBindingOpenCrayRuntimeServiceClient` no longer reaches back into `OpenCrayRuntimeServiceAccess` static helpers while assembling the caller-side transport
- runtime-service bind intents, scheduled wake intents, repair intents, and notification approval intents now also flow through a shared `RuntimeServiceIntentFactory`, so the concrete Android service class reference is centralized to a single main-code boundary instead of being re-embedded across bridge, notification, and scheduler code
- loopback runtime-server startup is now also isolated behind a dedicated `RuntimeServiceLoopbackBootstrapFactory`, so transport bootstrap no longer owns local-gateway/provider assembly or the concrete registry start call directly
- the local loopback runtime-server registry now resolves its default gateway bundle through `OpenCrayLocalRuntimeServerProvidersFactory`, so that registry no longer hardcodes `serviceBackedOpenCray*Gateway(...)` assembly inline and can migrate with the rest of the detached runtime bootstrap seams
- default client-side local/service-backed gateway assembly now resolves through `OpenCrayClientGatewayBundleFactory`, so `OpenCrayFlutterActivity`, `OpenCrayFlutterHostBridge`, and the loopback runtime-server provider path no longer each decide their own `serviceBackedOpenCray*Gateway(...)` wiring
- service-backed shell/chat/skills/settings composition now also resolves through `OpenCrayServiceBackedGatewayBundleFactory`, while loopback provider fanout resolves through `OpenCrayLocalRuntimeServerProvidersSupport`, so the detached client surface and the service-owned loopback surface no longer each hand-assemble their own four-gateway or five-provider combinations
- shared runtime/service diagnostics-head projection now resolves through `RuntimeServiceDiagnosticsProjectionSupport`, so host runtime, service-owned shell, projection shell, and projection chat no longer each hand-assemble the same lifecycle/work-state/connection map block independently
- `OpenCrayHostRuntime` now carries its service/runtime diagnostics providers and refresh registrars through a dedicated `HostRuntimeDiagnosticsBridge`, which narrows the remaining host-facade coupling down to one read-only adapter instead of a cluster of inline constructor fields
- runtime-service keepalive and foreground controller construction now also resolve through a dedicated controller-bundle factory seam, so `OpenCrayAgentRuntimeService` no longer hardcodes those controller instances inline before handing them to later bootstrap steps
- runtime-service transport bootstrap now also resolves through a dedicated transport-bootstrap factory seam that assembles the service-owned gateway bundle and starts the loopback runtime server, so `OpenCrayAgentRuntimeService` no longer hardcodes local HTTP transport bootstrap in its entrypoint
- service-owned gateway-bundle assembly inside that transport bootstrap now also resolves through a dedicated `RuntimeServiceGatewayBundleFactory` carried by `RuntimeServiceBootstrapDependencies`, and that factory now consumes a narrowed `RuntimeServiceGatewayBundleDependencies` bundle instead of the whole `OpenCrayRuntimeServiceHost`, so transport bootstrap and gateway composition both depend only on the specific runtime/service surfaces they actually need
- runtime-service observer, projection-persistence, foreground, keepalive, and notification attachment now also resolve through a dedicated execution-coordinator seam, so `OpenCrayAgentRuntimeService` no longer wires those observers and projection-store writes inline in the service entrypoint
- runtime-service wake intent parsing and dispatch now also resolve through a dedicated wake-command-dispatcher seam, so approval notification actions, scheduled dispatch wakes, interrupted-run resume wakes, and schedule repair wakes no longer live inline in `OpenCrayAgentRuntimeService`
- runtime-service binder exposure now also resolves through a dedicated binder-endpoint seam, so the local binder object and service-owned chat/skills/settings write-dispatch logic no longer live inline in `OpenCrayAgentRuntimeService`
- those execution-coordinator, wake-dispatcher, and binder-endpoint seams now also consume narrowed `RuntimeServiceExecutionCoordinatorDependencies`, `RuntimeServiceWakeCommandDispatcherDependencies`, and `RuntimeServiceBinderEndpointDependencies` bundles instead of the whole `OpenCrayRuntimeServiceHost`, so the remaining service host object is mostly confined to bootstrap-time assembly and no longer propagated through those long-lived runtime helpers
- service bootstrap now resolves the raw `OpenCrayRuntimeServiceHost` once into a `RuntimeServiceBootstrapState` that only carries pre-derived dependency bundles, and `OpenCrayAgentRuntimeServiceBootstrap` itself no longer exposes a raw `serviceHost`, which tightens the detached-runtime boundary further around one-time bootstrap assembly
- `RuntimeServiceBootstrapRegistry` no longer exposes a raw `resolveServiceHost(...)` entrypoint; callers and tests now resolve only `RuntimeServiceBootstrapState`, which keeps the raw host object behind the bootstrap boundary instead of the registry surface
- `RuntimeServiceBootstrapDependencies` now inject a `RuntimeServiceBootstrapStateProvider` rather than a raw `RuntimeServiceHostProvider`, so even override/test seams no longer need a monolithic host reference just to derive bootstrap state
- the default production `RuntimeServiceBootstrapStateProvider` now builds `RuntimeServiceBootstrapState` through direct service-bootstrap assembly instead of `OpenCrayRuntimeServiceHostRegistry.getOrCreate(...)`, so the runtime bootstrap main path no longer depends on a host-registry singleton
- that production path now also assembles a dedicated `RuntimeServiceBootstrapAssembly` and derives the dependency bags directly from that assembly, so `RuntimeServiceBootstrapState` no longer needs a materialized `OpenCrayRuntimeServiceHost` on the production bootstrap path
- the bootstrap-only assembly/state conversion helpers now live in dedicated runtime-service bootstrap support files rather than `OpenCrayRuntimeServiceHost` or `RuntimeServiceBootstrapRegistry`, so host-to-bundle derivation no longer leaks across transport, wake, binder, or scheduled-task files
- the remaining production bootstrap seam names are now host-free as well: `RuntimeServiceBootstrapFactory`, `RuntimeServiceBootstrapParts`, `bootstrapRuntimeServiceSessions(...)`, and `resumeInterruptedRuntimeServiceRuns(...)` now describe detached runtime-service behavior directly, and the old `OpenCrayRuntimeServiceHost` / `OpenCrayRuntimeServiceHostRegistry` compatibility layer has been removed from `main` code and left only as test-local fixture support
- runtime-service approval/session-approval/reject handling now resolves through a dedicated `RuntimeServiceApprovalDecisionAccess`, and the wake/binder/gateway dependency bundles now bind to that access plus the host-free interrupted-run repair helper instead of closing directly over `OpenCrayRuntimeServiceHost` approval methods
- binder-endpoint snapshot loading now also resolves through narrowed `RuntimeServiceBridgeSnapshotDependencies` instead of a host-backed snapshot lambda, so post-bootstrap binder snapshot reads no longer close over the raw `OpenCrayRuntimeServiceHost`
- scheduled-task wake dispatch and repair now also flow through explicit `ScheduledTaskDispatcherDependencies` and `ScheduledTaskRepairDependencies` captured inside `RuntimeServiceWakeCommandDispatcherDependencies`, so that wake path no longer relies on `OpenCrayRuntimeServiceHost` extension methods after bootstrap
- in-process and existing fallback runtime-service bridges now also read from snapshot providers instead of host providers, which removes the last non-bootstrap host-backed bridge constructors from main code
- bridge-default runtime-service start and base-bind-intent lookup now also route back through `OpenCrayRuntimeServiceAccess`, so direct Android service-intent construction is fully centralized inside the access boundary instead of lingering in caller-side bridge defaults
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
- the existing repair worker can now preflight interrupted interactive repair candidates from non-terminal queue snapshots or prompt checkpoints and wake the runtime service with `ACTION_RESUME_INTERRUPTED_RUNS`, and the service host can rescan known sessions to resume runs that still project as active
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

Current implementation note:

- queue restore now prefers `resume_from_checkpoint` whenever a durable safe checkpoint exists, even if a managed process is still live
- `stop_rejected_awaiting_direction` is now used for durable rejected-approval restores so the run stays stopped instead of re-entering a queued resume path
- `resume_reconnect_process` remains a projected recovery intent for live managed-process state, but true reconnect without checkpoint replay is still not implemented end-to-end
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

Prepare future scheduled execution and recurring automation without reintroducing UI ownership.

### Current implementation status

Already landed in app process:

- scheduled task registry, durable spec storage, and run records
- `AlarmManager` wake path plus `WorkManager` wake and repair path
- runtime-service wake entrypoints for scheduled dispatch, schedule repair, and interrupted-run resume repair
- approval notification approve/reject actions now execute through `OpenCrayRuntimeServiceHost` directly instead of routing the service wake path through `OpenCrayHostRuntime`
- schedule, approval, completion/interruption, and active-runtime notification surfaces

Still pending in this phase:

- richer schedule-side notification actions beyond the current approval/open flows
- broader autonomous repair policy for interrupted interactive runs beyond the current `WorkManager` precheck plus `ACTION_RESUME_INTERRUPTED_RUNS` wake-and-resume scan
- any future automation expansions that need more than the current wake/dispatch bridge

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
