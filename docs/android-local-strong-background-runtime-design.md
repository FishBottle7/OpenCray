# Android Local Strong Background Runtime Design

Last updated: 2026-06-07

## Status

Design with partial implementation

## Purpose

This document defines an Android-first local execution architecture for:

1. long-running agent tasks that should keep progressing after the user leaves the page
2. delayed and scheduled task triggers that can wake the app at a later time
3. a stronger keepalive posture that maximizes local survivability without pretending Android allows infinite self-owned persistence

It complements, not replaces, these existing documents:

- `docs/runtime-checkpoint-and-detached-execution-design.md`
- `docs/host-rebuild-and-background-task-plan.md`
- `docs/runtime-foundation-delivery-plan.md`

Those documents define detached runtime ownership, journaling, and checkpoint recovery in general. This document narrows the problem to the Android product and platform design for local strong background behavior.

## Current Baseline

Today OpenCray already has a service-host boundary:

- `OpenCrayAgentRuntimeService` exists and bootstraps the runtime owner
- the production runtime service now runs in a dedicated `:runtime` process, while the owner and executors still live inside that service process
- `OpenCrayHostRuntime` is no longer the production execution owner
- UI transport can detach without immediately destroying runtime ownership
- same-process service-shell rebuild still reuses the retained execution controller, and explicit retained-owner replacement now reuses the same in-process session/executor core while only rotating runtime-owner lifecycle plus owner-bound access wrappers
- runtime-process-only bootstrap now initializes document support and bundled skills from service startup itself, so the dedicated service process does not depend on main-process `Application` bootstrap side effects before serving runtime work
- caller-side runtime entrypoints now only request service start or wake, so the UI process no longer pre-creates the runtime host before the service lifecycle begins
- the process-scoped execution-controller path now also resolves only a narrowed `RuntimeExecutionDependencies` bag from the environment, so controller bootstrap retains only app context, service-bootstrap context, and retained owner-bootstrap inputs instead of the full runtime context dependency bag
- runtime-service bootstrap factories now also resolve as a single `RuntimeServiceBootstrapDependencies` bundle captured once per service instance, and caller-side start/client access now also resolves as `RuntimeServiceAccessDependencies`, which narrows the remaining same-process global seam fanout around the detached runtime shell
- `OpenCrayAgentRuntimeServiceBootstrap` and `RuntimeServiceShellController` now resolve through a dedicated `RuntimeServiceBootstrapDependencies` bundle plus service-bootstrap factory seam, so the service shell only owns lifecycle delegation while the concrete runtime factories stay behind the runtime bootstrap boundary
- service-owned gateway-bundle assembly inside transport bootstrap now also resolves through `RuntimeServiceGatewayBundleFactory` carried by that same bootstrap-dependency bundle, and that factory now consumes a narrowed `RuntimeServiceGatewayBundleDependencies` bundle instead of the whole `OpenCrayRuntimeServiceHost`, so local HTTP transport startup no longer hardcodes or passes around the monolithic service host just to build shell/chat/skills/settings surfaces
- execution coordinator, wake dispatcher, and binder endpoint now also consume narrowed `RuntimeServiceExecutionCoordinatorDependencies`, `RuntimeServiceWakeCommandDispatcherDependencies`, and `RuntimeServiceBinderEndpointDependencies` bundles instead of the whole `OpenCrayRuntimeServiceHost`, so those detached-runtime service helpers no longer keep a monolithic host reference merely to observe work state, handle wake actions, or serve binder writes
- the runtime-process bootstrap assembly now also retains a `RuntimeServiceProjectionCoordinator`, so projection-store writes, runtime-notification observation, and scheduled-dispatch outcome fanout survive service-shell recreate inside the same `:runtime` process
- the service-owned wake dispatcher and binder endpoint now also depend only on `RuntimeServiceProjectionCoordinator` and `RuntimeServiceShellStateAccess`, so these service-shell helpers no longer keep the full `RuntimeServiceExecutionCoordinator` just to flush projection state or read keepalive/foreground snapshots
- that retained bootstrap assembly now also stores a narrowed `RuntimeServiceBootstrapContext` plus `RuntimeServiceBootstrapRuntimeAccess` rather than the whole runtime dependency bag and monolithic host facade, which keeps the process-retained detached-runtime owner path closer to the real seams it still consumes
- detached-runtime chat/session/schedule/notification consumers now also depend on narrower runtime-access facets such as `RuntimeChatMutationAccess`, `RuntimeChatSubmissionHostAccess`, `RuntimeRunLookupAccess`, and `RuntimeNotificationHostAccess`, so these service-owned helpers no longer all retain the full `OpenCrayRuntimeHostAccess` facade
- runtime-service bootstrap now resolves that raw host once into a `RuntimeServiceBootstrapState` that keeps only pre-derived dependency bundles, and the final `OpenCrayAgentRuntimeServiceBootstrap` no longer exposes `serviceHost`, so detached-runtime startup retains the host only during one-time bootstrap assembly
- the Android `Service` shell itself now delegates lifecycle ownership through a dedicated `RuntimeServiceShellController` constructed directly by `OpenCrayAgentRuntimeService`, so process bootstrap, service bootstrap attach, start-command dispatch, bind exposure, and teardown no longer live inline in `OpenCrayAgentRuntimeService`
- the runtime-service bootstrap dependency bundle now only resolves `RuntimeServiceBootstrapState`, `OpenCrayAgentRuntimeServiceBootstrap.resetRuntimeOwner()`, and related narrowed components instead of a raw `resolveServiceHost(...)` seam or direct execution-controller/provider path, so detached-runtime bootstrap consumers do not hold a structural path back to the monolithic host or process-scoped controller singleton
- the retained `RuntimeServiceExecutionController` also no longer exposes its full bootstrap-assembly bag directly; projection, transport, local-runtime-server, and retained shell-control details now travel only through explicit controller methods or derived bootstrap state, which narrows another detached-runtime escape hatch before stronger process isolation work
- detached service startup now resolves runtime-service bootstrap state only through the process-singleton `RuntimeServiceExecutionController` and derives `RuntimeServiceBootstrapState` from that controller instead of calling `OpenCrayRuntimeServiceHostRegistry.getOrCreate(...)`, so detached service startup no longer depends on a host-registry singleton or a second bootstrap pipeline on its main path
- that production path now reuses the dedicated `RuntimeServiceBootstrapAssembly` already captured by the execution controller, so detached service startup no longer needs a materialized `OpenCrayRuntimeServiceHost` even as an internal bootstrap intermediate on the main path
- that bootstrap assembly is now service-neutral and cached behind a runtime-process `RuntimeServiceExecutionController`, so service recreate inside the same `:runtime` process no longer re-runs execution-core bootstrap and instead only derives a new service-shell lifecycle descriptor plus service-scoped dependency bags
- service-shell reset now also stays on that explicit retained controller path: `RuntimeServiceShellController` replaces the attached controller's runtime owner directly, while the old default-controller `peek/reset/swap` helpers are kept only in test compat, so production shell reset no longer mutates a hidden global controller helper outside the bootstrap dependency graph
- the runtime-process execution controller now also resolves an explicit `RuntimeOwnerBootstrap`, but only carries forward controller lifecycle plus the service-neutral bootstrap assembly while the retained bootstrap path consumes the explicit lifecycle/access facets exposed by that bootstrap, so process-owned runtime owner creation is now a first-class boundary without keeping a second owner-level registry alongside the execution controller
- old direct bootstrap-state and in-process owner singleton shims now live only in test compat, so the production detached-runtime path no longer ships those legacy owner/host shortcuts in main code
- the bootstrap-only assembly/state conversion helpers now live in dedicated runtime-service bootstrap support files rather than `OpenCrayRuntimeServiceHost` or a default resolver singleton, which keeps the detached service helpers from each re-owning their own host-conversion bridge
- the remaining production bootstrap seam names are now host-free as well: `RuntimeServiceBootstrapFactory`, `RuntimeServiceBootstrapParts`, `bootstrapRuntimeServiceSessions(...)`, and `resumeInterruptedRuntimeServiceRuns(...)` now describe detached runtime-service behavior directly, and the old `OpenCrayRuntimeServiceHost` / `OpenCrayRuntimeServiceHostRegistry` compatibility layer has been removed from `main` code and retained only as test-local fixture support
- runtime-service approval/session-approval/reject handling now also routes through a dedicated `RuntimeServiceApprovalDecisionAccess`, and wake/binder/gateway dependency bundles now use that access plus the host-free interrupted-run repair helper instead of retaining direct closures over host approval methods
- binder-endpoint snapshot loading now also resolves through narrowed `RuntimeServiceBridgeSnapshotDependencies` instead of a host-backed snapshot lambda, so detached binder snapshot reads no longer retain the full service host after bootstrap
- scheduled-task wake dispatch and repair now also resolve through explicit `ScheduledTaskDispatcherDependencies` and `ScheduledTaskRepairDependencies` captured at bootstrap, so the detached wake path no longer depends on host extension methods once the service is running
- the old in-process/existing fallback runtime-service bridge compat layer has now been removed from main code entirely, so detached client fallback no longer retains non-bootstrap host-backed bridge constructors on the production path
- runtime-service bridge snapshots now also carry explicit `runtimeOwnerLifecycle` and `runtimeOwnerWorkSummary` fields, so projection reads and binder diagnostics no longer need to traverse the full runtime-owner access object to render detached-runtime owner state
- the production service-owned loopback transport now creates an attach-scoped `OpenCrayLocalRuntimeServer` directly from `RuntimeServiceLoopbackBootstrapFactory` instead of starting through `OpenCrayLocalRuntimeServerRegistry`, so detached service HTTP no longer depends on the process-global default-provider path
- loopback transport provider fanout now captures the explicit service-owned shell/chat/skills/settings gateway bundle produced by the same transport bootstrap, so local runtime HTTP no longer re-reads a mutable retained coordinator bundle just to return to the same detached service owner
- that retained transport coordinator now also owns the current local runtime-server state provider, and loopback bootstrap binds `server::currentState` into it once startup succeeds, so the retained execution-controller/bootstrap-assembly path no longer defaults to `OpenCrayLocalRuntimeServerRegistry.peekState()` just to surface loopback diagnostics
- the attach-scoped service-owned loopback bootstrap now also disposes its own local runtime server on service-shell teardown, so explicit shell reset or ordinary same-process service recreate does not leave the old loopback socket/provider chain alive until process death
- default client-side local/service-backed gateway assembly now resolves through `OpenCrayClientGatewayBundleFactory`, so the Flutter activity/bridge entry path still shares one detached-runtime client composition seam even though the production service-owned loopback path no longer consumes that caller-side default bundle
- service-backed shell/chat/skills/settings composition now also resolves through `OpenCrayServiceBackedGatewayBundleFactory`, and service-owned loopback provider fanout now resolves through `OpenCrayLocalRuntimeServerProvidersSupport`, so the detached client path and the service-owned loopback path still share the same normalized runtime-surface shape without sharing the old registry startup path
- host runtime, service-owned shell, projection shell, and projection chat now also share the same runtime/service diagnostics-head projector via `RuntimeServiceDiagnosticsProjectionSupport`, which keeps detached-runtime diagnostics consistent across binder and fallback reads
- service-owned shell/chat diagnostics plus host-runtime shell diagnostics now also receive local runtime-server state through injected provider seams, so detached snapshot rendering no longer performs its own registry read at the consumer boundary
- the retained runtime-service bootstrap assembly and process-scoped execution-controller provider now also receive that local runtime-server state through an explicit provider seam instead of peeking the registry during assembly creation, which keeps the retained detached-runtime path transport-neutral and removes one more same-process singleton dependency from service recreate
- client-side runtime-service start and base bind-intent lookup now route through the environment-owned `RuntimeServiceAccessGateway`, so caller-side transport wiring no longer holds a direct reference to the concrete Android service-intent builder
- runtime-service bind/start/repair/approval intents now also resolve through a dedicated `RuntimeServiceIntentFactory` file with an injectable component provider, and caller-side access now only consumes endpoint-built intents instead of hand-encoding wake/start actions or extras in the access facade

But the background product surface is still incomplete:

- execution is still driven by in-runtime-process executors rather than a deeper controller/process split, even though the service shell now reuses a process-singleton execution controller across service recreate
- the runtime service now promotes itself to foreground while keepalive-required work exists, but execution ownership is still only isolated to the dedicated runtime service process and not yet a separate stronger controller tier
- the service shell now also treats app visibility plus the strong-background tier as first-class keepalive inputs: when the app goes invisible, idle-grace stop deadlines can stretch by tier and foreground notification retention can continue through idle grace for `active_background` or `strong_background`, which removes one of the remaining software-caused interruption paths during ordinary backgrounding
- the service shell now also returns `START_STICKY` while keepalive or foreground-notification state remains active after a start command, and only falls back to `START_NOT_STICKY` for idle/no-work starts, which gives Android a restart path for live detached work without replaying already-consumed wake intents
- the retained keepalive shell path now also uses `stopSelfResult(startId)` for delayed idle shutdown, so a stale idle-grace stop cannot tear down a newer service start generation once detached work has already advanced
- the service shell now also reuses process-retained keepalive and foreground controllers across same-process service recreate, with each fresh Android `Service` shell only rebinding stop/foreground adapters onto that retained shell control instead of allocating a new shell-state machine every time
- the retained transport coordinator now also disposes the previously bound service-owned gateway bundle when a new shell instance rebinds, so shell/chat observer registrations do not silently accumulate across same-process service recreate
- ordinary shell teardown now also releases its own currently bound service-owned gateway bundle through that retained transport coordinator, using bundle-instance identity so a stale dispose from an older shell cannot clear a newer rebound bundle
- binder-unavailable shell/chat projection now reads service/runtime status from a file-backed runtime-service projection snapshot rather than consulting a live host-registry bridge, which makes host rebuild UI reads closer to the detached-owner target
- binder-pending chat projection now also reads durable managed-process state through a passive `projection_only` restore mode, so non-owner UI fallback no longer marks reconnectable running processes interrupted just because binder attach has not completed yet
- binder-backed service shell diagnostics now also derive `hostLifecycle` from the live service shell plus runtime-owner/controller identities instead of minting a synthetic host descriptor, so attached-shell diagnostics point at the real detached runtime shell instance
- when Android returns a remote `BinderProxy` instead of a same-process binder endpoint, the client now reports an explicit `invalid_binder` state and leaves read continuity to the higher-level projection gateway seam instead of inventing a lower-level loopback transport mode
- caller-side runtime-service composition now removes low-level loopback HTTP read and command fallback from `main` code entirely; projection snapshot plus explicit projection-only gateway assembly remain the built-in caller-side fallback path
- service-backed chat/skills/settings writes now require binder-backed dispatch, and the old low-level caller-side command fallback escape hatch is gone from `main` code
- caller-side projection fallback assembly now resolves through explicit `OpenCrayProjectionGatewayBundleFactory` plus configurable client/service-backed gateway-bundle factories, so the remaining binder-pending fallback seam is isolated above the low-level binder client
- service-backed chat/runtime observers now also use passive connection-state observation like shell/skills/settings, so simply opening the runtime page no longer starts or binds the service just to watch projection fallback
- projection shell/chat fallback built from the same client bundle now shares one explicit `HostRuntimeLifecycleDescriptor`, which removes avoidable diagnostics churn when the UI bounces between fallback surfaces before binder attach
- `AlarmManager` and `WorkManager` trigger bridges now exist for scheduled wake-up and repair
- active-runtime, approval-needed, completion/interruption, and scheduled-dispatch notifications now exist, including notification-side approve/reject entry for approval waits
- scheduled-dispatch notifications now also expose service-backed retry/manual-run, disable, and snooze actions for skipped-busy and dispatch-failed outcomes, so a notification action can wake `:runtime`, enqueue a fresh manual scheduled trigger, disable the durable schedule, or persist a short deferral without reopening the UI first
- file-backed managed-process registries can now reattach live controllers across registry or host rebuild while the same app process remains alive, and that restore path is now scoped by runtime controller identity instead of only durable directory; reconnectable managed-process backends now also have a tested cross-owner restore path when a rebuilt runtime owner reopens the same durable session directory; registry read/modify/write operations now also use a directory-scoped JVM lock plus OS file lock so concurrent detached owners or projection readers do not overwrite each other's managed-process snapshots, although the overall detached runtime is still only isolated to the dedicated `:runtime` process rather than a stronger controller tier
- checkpoint-backed observational managed-process restore is now also narrower and safer: interrupted `ProcessRead` or `ProcessWait` can auto-resume only when the durable pending action still matches the same live `process_id` and the reconnect state is already stable (`attached_live` or `completed`), while `connecting`, `retry_scheduled`, `failed_terminal`, or other ambiguous reconnect states remain explicit interruption instead of silent replay
- recovery diagnostics now stamp run attempts and checkpoint ids on recovery-aware queue rewrites and append non-runtime recovery markers to the durable run journal, so strong-background reports can distinguish an initial submission from a checkpoint-backed restore without relying only on queue state
- startup plus boot/package-replaced repair paths now re-register scheduled work, enqueue one-shot repair, and keep a unique periodic `WorkManager` repair registered; the repair worker can also wake `OpenCrayAgentRuntimeService` with `ACTION_RESUME_INTERRUPTED_RUNS` when queue or checkpoint state suggests interrupted interactive work, but continuation still reuses the normal checkpoint-aware resume path rather than a separate long-lived repair controller
- notification/background settings now expose notification, exact-alarm, and battery-optimization system actions, including direct exemption request where Android allows it

So the current system now has real strong-background primitives and a substantially narrowed detached-runtime composition path, but it is still not yet a full local strong-background product.

## Product Target

The target is not "never die".

The target is:

- active tasks should survive page exit, app backgrounding, and many ordinary host rebuilds
- scheduled tasks should wake the app without requiring an open page
- interrupted work should resume from safe checkpoints instead of silently replaying the whole run
- the product should expose the same background affordances users expect from strong mobile apps:
  - persistent active-task notification
  - approval-needed notification
  - completion and interruption notification
  - scheduled automation visibility

This should feel closer to a "local Codex-style automation runtime" than to a foreground-only chat page.

## Hard Android Boundaries

The design must respect platform reality.

### Boundaries we can work with

- `AlarmManager` can trigger future work outside the lifetime of the current process.
- `WorkManager` can persist delayed work and wake the app later.
- foreground services can keep user-visible active work alive much more reliably than a plain process.
- battery-optimization exemption can materially improve local survivability for acceptable app categories.

### Boundaries we cannot break

- if the user force-stops the app, the package cannot self-start until the user explicitly interacts with it again
- if the user stops an app from Android's Active apps UI, the whole app process dies immediately
- Android 14+ does not pre-grant exact alarm access for most newly installed apps
- `WorkManager` is durable but not exact, and periodic work is inexact
- Android 14+ requires foreground service types
- Android 15 imposes timeout rules on some foreground service types such as `dataSync`

This means "WeChat-like keepalive" can only mean "maximize survivability with supported mechanisms", not "guarantee indefinite execution after any system or user action".

## Design Goals

### Primary goals

- active runs continue when the user leaves the UI
- scheduled tasks can wake the app later and create work without an open page
- background execution remains local, not remote
- run recovery after kill or rebuild uses journal and checkpoint boundaries, not whole-run replay
- users can see and control background work through notifications and in-app diagnostics

### Secondary goals

- preserve a clean upgrade path from same-process service host to a stronger runtime process if needed later
- keep transport-neutral semantics so binder and loopback HTTP remain mere attachment paths
- keep scheduled work on the same queue, journal, approval, and checkpoint model as interactive work

## Non-Goals

- do not promise survival after force-stop
- do not emulate OEM-private keepalive hacks
- do not move execution to a remote server
- do not introduce a second recovery model just for scheduled runs
- do not inject scheduled prompts into an already-running run by default

## User-Facing Modes

The product should explicitly model these runtime modes:

1. Attached interactive run
   - user is in the UI
   - work may still be service-owned

2. Detached active run
   - user left the page or app
   - task remains active under the runtime service
   - foreground notification is visible while long-running work exists

3. Waiting for trigger
   - no active run yet
   - a scheduled spec is persisted
   - next wake is registered with Android

4. Triggered queued run
   - alarm or worker woke the app
   - the service accepted the scheduled trigger
   - a normal run was enqueued under the target session

5. Interrupted recoverable run
   - the process or service died during active work
   - the next startup can recover from a safe checkpoint

6. Interrupted manual-attention run
   - continuation is unsafe
   - the product surfaces the interruption instead of replaying automatically

## Core Product Model

### 1. ScheduledTaskSpec

Persist one record per automation definition.

Recommended shape:

```kotlin
data class ScheduledTaskSpec(
  val scheduleId: String,
  val sessionId: String,
  val title: String,
  val enabled: Boolean,
  val trigger: ScheduledTrigger,
  val payload: ScheduledTaskPayload,
  val policy: ScheduledTaskPolicy,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)

sealed interface ScheduledTrigger {
  data class RunAtTimestamp(val triggerAtEpochMs: Long) : ScheduledTrigger
  data class RunAfterDelay(val delayMs: Long, val createdAtEpochMs: Long) : ScheduledTrigger
  data class Periodic(
    val intervalMs: Long,
    val flexMs: Long? = null,
    val anchorEpochMs: Long? = null,
  ) : ScheduledTrigger
}

data class ScheduledTaskPayload(
  val prompt: String,
  val workingDirectory: String? = null,
  val attachmentRelativePaths: List<String> = emptyList(),
  val variables: Map<String, String> = emptyMap(),
)

data class ScheduledTaskPolicy(
  val conflictPolicy: ScheduledConflictPolicy = ScheduledConflictPolicy.ENQUEUE_NEW_RUN,
  val requiresForegroundNotification: Boolean = true,
  val notifyOnQueued: Boolean = false,
  val notifyOnApproval: Boolean = true,
  val notifyOnCompletion: Boolean = true,
  val notifyOnInterruption: Boolean = true,
)

enum class ScheduledConflictPolicy {
  ENQUEUE_NEW_RUN,
  SKIP_IF_SESSION_BUSY,
  CANCEL_OLDER_WAITING_TRIGGER,
}
```

### 2. ScheduledTaskRunRecord

Persist one record per trigger attempt.

Recommended purpose:

- durable audit trail
- dedupe and idempotency
- trigger failure diagnostics
- link from a schedule to the actual run that was created

Recommended fields:

- `scheduleRunId`
- `scheduleId`
- `sessionId`
- `triggerReason`
- `triggeredAtEpochMs`
- `acceptedAtEpochMs`
- `createdRunId`
- `result`
- `failureReason`
- `recoverySource`

### 3. Scheduled runs create normal runs

A scheduled trigger should create a normal run on the existing session queue.

The scheduled trigger should not, by default:

- inject a supplement into an in-flight run
- replace the currently running run
- invent a special execution pipeline

This keeps scheduled execution compatible with the same:

- queue semantics
- run journal
- prompt checkpoints
- approval model
- interruption model

## Android Architecture

## 1. Trigger layer

Introduce a small scheduling boundary above Android APIs:

- `ScheduledTaskRegistry`
- `ScheduledTriggerRegistrar`
- `ScheduledWakeDispatcher`

Responsibilities:

- persist schedule specs
- compute next wake for each enabled spec
- register Android wake mechanisms
- route wake intents into the runtime service
- write trigger journal entries

This layer should not own the agent loop.

## 2. Dual wake strategy

Use two wake paths, each for a different problem.

### Exact or user-intent time

Use `AlarmManager` for:

- run at a precise timestamp
- cron-like "run at 09:00" style schedules
- wake during Doze when the user explicitly expects time precision

Recommended behavior:

- schedule only the next exact firing, not an unbounded repeating exact alarm
- after a trigger fires, compute and register the next wake
- if exact alarm permission is unavailable, degrade to inexact fallback and surface that downgrade in UI

### Durable delayed or inexact periodic work

Use `WorkManager` for:

- delayed execution that does not need exact wall-clock precision
- periodic maintenance
- reconciliation after missed alarms or process loss
- boot-time and app-upgrade repair scans

Recommended behavior:

- `WorkManager` wakes the app
- it loads the schedule spec or repair scope
- it hands off immediately to the runtime service
- it does not execute the full agent loop itself

## 3. Runtime service wake handoff

Extend `OpenCrayAgentRuntimeService` with explicit wake actions:

- `ACTION_RUN_SCHEDULED_TASK`
- `ACTION_REPAIR_SCHEDULES`
- `ACTION_RESUME_INTERRUPTED_RUNS`
- `ACTION_APPROVAL_DECISION`

Recommended command extras:

- `scheduleId`
- `scheduleRunId`
- `triggeredAtEpochMs`
- `triggerReason`
- `targetSessionId`

The service should:

1. validate the wake intent
2. load the schedule spec or recovery target
3. persist a trigger journal event
4. create or recover the appropriate run
5. decide whether foreground keepalive is required

## 4. Foreground keepalive for active work

This is the core of "local strong background".

The runtime service should run as:

- bound-only while the UI is attached and no long work exists
- started service while detached work exists
- foreground service while active work is expected to outlive the foreground UI or while approval-visible work is pending

Introduce:

- `RuntimeForegroundController`
- `RuntimeNotificationFactory`
- `RuntimeNotificationChannelRegistry`

Responsibilities:

- decide when to enter foreground mode
- publish the active-task notification
- downgrade and stop foreground mode when no eligible active work remains
- attach notification actions for open, approve/reject, and supported scheduled retry/manual-run, snooze, and disable flows

## 5. Notification model

Introduce stable channels:

- `runtime_active`
- `runtime_approval`
- `runtime_completion`
- `runtime_schedule`

Recommended notification surfaces:

### Active runtime notification

Shown while detached long work exists.

Include:

- active run count
- current session title
- current run summary
- elapsed time
- `Open`

### Approval-needed notification

Shown when a detached run is waiting for approval.

Include:

- run title
- tool summary
- `Approve`
- `Reject`
- `Open`

Approval actions should go through the same durable approval checkpoint path as in-app decisions.

### Completion notification

Shown when a scheduled or detached run finishes while the app is backgrounded.

Include:

- session
- run summary
- `Open`

### Interruption notification

Shown when background work became interrupted and cannot continue safely.

Include:

- short reason
- `Review`

### Scheduled dispatch notification

Shown when a scheduled trigger queues, skips, or fails while the app is backgrounded.

Current implemented controls:

- `Open`
- `Retry` for skipped-busy or dispatch-failed schedule outcomes, routed as a stable `RUN_SCHEDULE_NOW` runtime-service command that re-dispatches the schedule as a normal manual scheduled trigger
- `Snooze` for skipped-busy or dispatch-failed schedule outcomes, routed as a stable `SNOOZE_SCHEDULE` runtime-service command that persists a short deferral and re-syncs the original trigger without rewriting it
- `Disable` for skipped-busy or dispatch-failed schedule outcomes, routed as a stable `DISABLE_SCHEDULE` runtime-service command that disables the durable schedule and cancels registered triggers

## 6. Battery optimization and OEM posture

Provide a formal "Strong background mode" setup flow.

Recommended checks:

- exact alarm access granted
- battery optimization exemption status
- notification permission status
- OEM-specific background restriction guidance availability

Recommended actions:

- open exact alarm settings when needed
- open battery optimization settings
- offer direct exemption request only when the app's use case qualifies
- show OEM-specific guidance for major ROM families

Important policy stance:

- task automation apps are an acceptable use case for battery optimization exemption when scheduling automated actions is core to the app
- chat-style real-time messaging alone is not sufficient justification if FCM can solve the use case

OpenCray should therefore position the exemption request around local agent automation, scheduled actions, and long-running user-requested automations, not generic "please keep me alive" language.

## 7. Boot and repair flows

Introduce:

- `BOOT_COMPLETED` receiver
- `MY_PACKAGE_REPLACED` receiver
- optional app-start repair scan
- periodic repair worker registration

Responsibilities:

- reload enabled `ScheduledTaskSpec`s
- re-register their next wake
- enqueue a lightweight reconciliation worker
- keep the unique periodic reconciliation worker registered

Do not try to restart arbitrary active runs directly from boot broadcast handling.

Instead:

- re-register schedules
- enqueue repair work
- ensure periodic repair remains registered
- let repair work or next foreground/service start evaluate checkpoint recovery

## 8. Recovery semantics

This design depends on the runtime journal and checkpoint foundation.

Required behavior:

- if the process dies during active work, the next wake or app start must inspect journal plus checkpoint state
- if recovery is safe, resume from checkpoint
- if recovery is unsafe, mark interrupted and notify
- scheduled triggers must never cause blind whole-run replay

This means scheduled execution can only ship as a first-class feature once journal and checkpoint behavior are already trustworthy.

## Keepalive Tiers

To align product expectations with Android reality, expose explicit tiers.

### Tier 0: Normal background

- no exemption
- no exact alarm access
- best-effort background only

Expected behavior:

- interactive tasks survive ordinary detach
- scheduled tasks are inexact
- long detached runs are less reliable under aggressive OEM policies

### Tier 1: Strong background

- foreground runtime notification enabled
- exact alarm access granted where needed
- battery optimization exemption granted

Expected behavior:

- much stronger detached task survivability
- precise scheduled wake where permission allows
- better resilience under Doze and standby

### Tier 2: Extended local survivability

- all Tier 1 requirements
- boot receivers enabled
- schedule repair enabled
- optional future dedicated runtime process

Expected behavior:

- strongest supported local posture
- still not immune to force-stop or explicit user-stop

This tiered model is the correct interpretation of "keepalive like WeChat": maximize survivability through supported product setup, not secret immortality.

## Foreground Service Type Strategy

Android 14+ requires foreground service types, and Android 15 adds timeouts for some types.

Because OpenCray agent work is broader than plain file sync, the runtime service should not assume `dataSync` is always appropriate.

Recommended policy:

- treat generic agent execution as a separate runtime concern
- if the product distribution model allows it, use `specialUse` for the generic runtime service and document the use case clearly
- if distribution constraints make `specialUse` unacceptable, limit foreground keepalive to workloads that fit an approved type and keep other workloads detached-but-not-foreground

The design should therefore keep the foreground controller pluggable by service type decision, not hardcode `dataSync`.

## Exact Alarm Strategy

Exact alarms should be reserved for user-intentioned schedules:

- "run at 08:00 every weekday"
- "run this workflow tonight at 23:30"
- "remind and execute at a precise time"

They should not be used for:

- internal polling
- generic queue heartbeats
- continuous keepalive

Recommended policy:

- exact time schedules require exact alarm capability or a clear user-visible downgrade
- periodic non-critical schedules use inexact wake plus `WorkManager`
- cron-style schedules are compiled into the next single wake rather than permanently repeating exact alarms

## Runtime Process Reservation

This design stays local even if we later move to a stronger runtime process.

To preserve that option:

- the runtime service command protocol must remain process-neutral
- the scheduled wake dispatcher must not depend on in-process singletons
- notification actions must route through stable intents, not direct object references
- journal and checkpoint stores must remain file-backed and process-safe

The current rollout already uses:

- `com.opencray.app:runtime`
- an explicit runtime-service intent descriptor parser that derives wake dispatch, reset intent, and bootstrap-foreground requirements from one normalized command model instead of scattering those action checks across shell and wake paths
- schedule notification retry/manual-run, snooze, and disable actions that route through that same command model and detached runtime target instead of holding direct object references from the notification

as a dedicated process for stronger isolation from UI crashes and Flutter engine churn.

The remaining design requirement is to preserve a later path from this dedicated service process to an even stronger detached controller/runtime tier if product needs expand.

## Rollout Status

### Phase 0: Product scaffolding

Status:

- substantially implemented in the current dedicated `:runtime` service host

- add notification channels
- add runtime notification factory
- add foreground controller
- expose keepalive diagnostics in debug UI

Exit criteria:

- detached active runs can show a stable active notification

### Phase 1: Scheduled model and wake bridge

Status:

- substantially implemented for timestamp/delay scheduling, wake dispatch, and repair

- add `ScheduledTaskSpec`
- add `ScheduledTaskRunRecord`
- add `AlarmManager` wake path
- add `WorkManager` wake and repair path
- add service wake commands

Exit criteria:

- delayed and timestamp triggers can create runs without an open page

### Phase 2: Strong background mode

Status:

- partially implemented; capability checks, settings actions, and the in-app notifications/background page are already present, the production service shell now runs in a dedicated `:runtime` process, service recreate now reuses a process-singleton execution controller plus service-neutral bootstrap assembly, managed-process restore inside that runtime process is now controller-aware, and the retained runtime composition path now resolves through explicit environment-owned seams instead of broad deep fallback helpers, including a narrowed execution-controller dependency bag that no longer carries the full runtime context; periodic repair precheck now also routes persisted scheduled, detached-control, checkpoint-linked, and durable background-subagent repair evidence to the matching runtime-service target before waking `:runtime`, but the overall detached/background runtime still uses a single runtime-process owner/executor and is not yet a stronger controller-isolated runtime
- approval notification approve/reject actions now wake the service into a service-owned command path instead of going back through a service-local `OpenCrayHostRuntime` facade
- accepted scheduled-run notifications now also expose a Cancel action that wakes `:runtime` through the existing service-owned chat-write interrupt command, so per-run cancellation does not require a UI-side runtime owner
- the runtime service now also resolves wake intent parsing and dispatch through a dedicated wake-command-dispatcher seam, so notification approval actions, scheduled task wakes, interrupted-run resume wakes, and schedule repair wakes all share the same service-owned handoff boundary instead of living inline in `OpenCrayAgentRuntimeService`
- the shell controller now honors explicit runtime-reset requests without conflating them with ordinary interrupted-run resume wakes, so the `:runtime` process can still dispose the current shell, replace retained runtime ownership inside the existing execution controller, and attach a fresh shell when reset is actually requested, while `ACTION_RESUME_INTERRUPTED_RUNS` continues through the normal repair/resume path without forcing a retained-runtime rebuild
- `RuntimeServiceIntentFactory` and the environment-owned `RuntimeServiceAccessGateway` now also expose a standalone `ACTION_RESET_RUNTIME` / `resetRuntime(...)` wake path, and the shell controller recognizes either that explicit action or `EXTRA_FORCE_RUNTIME_RESET`, so repair or diagnostics callers in the app process can ask the runtime process to rebuild retained ownership instead of resetting controller state locally in the wrong process
- foreground-started wake actions for scheduled-task dispatch, schedule repair, explicit runtime reset, interrupted-run resume, and supported fire-and-forget chat-write wake commands now also force an immediate bootstrap foreground notification before dispatch continues, which closes the Android foreground-service timing gap during detached wake-up when real work-state transitions have not yet propagated
- the shell controller's default reset path now also reuses the current runtime-process execution controller and swaps only the retained runtime owner plus owner-bound observers instead of resetting the whole execution-controller provider, so the detached service shell can rebuild retained ownership without dropping retained transport, projection, or shell-control state inside `:runtime`
- that retained-owner swap path now also keeps the same in-process session manager, executors, approval/journal/checkpoint stores, and managed-process registry continuity alive behind a shared owner-lifecycle state, so explicit same-process reset no longer interrupts active work simply because owner-bound service access was rebuilt
- app visibility is now also projected across processes through a lease-based persisted visibility heartbeat plus app-private visibility broadcast, and the runtime-process shell keepalive/foreground path plus runtime notifications consume that bridged signal instead of reading a same-process `AppVisibilityMonitor` singleton, so detached runtime behavior no longer defaults to “always backgrounded” just because the service lives in `:runtime`, and it does not collapse idle-grace/notification behavior immediately on every transient activity stop/start edge
- recovery-aware queue restore now also exposes `runAttempt` and checkpoint-backed `recoveredFromCheckpointId` through run lifecycle diagnostics and stores the same recovery basis as durable journal markers, improving detached/background debugging without changing the remaining runtime-process ownership boundary
- the service-owned wake dispatcher now also consumes parsed commands from `RuntimeServiceWakeIntentParser`, so Android `Intent` action/extra decoding is centralized to one transport seam instead of being mixed into dispatch behavior
- the runtime service now also resolves its binder-facing endpoint through a dedicated seam, so the binder object and binder-only chat/skills/settings write-dispatch logic no longer live inline in `OpenCrayAgentRuntimeService`
- the Android runtime `Service` class itself now only resolves and delegates to a `RuntimeServiceShellController`, so even service-shell lifecycle orchestration no longer lives inline in `OpenCrayAgentRuntimeService`
- chat, skills, and settings mutating commands now also terminate through service-owned binder dispatch paths, and chat approval/session-approval/reject decisions resolve inside `OpenCrayRuntimeServiceHost` rather than through a UI-side host facade
- binder-pending projection reads are still same-process and snapshot-backed, but they now avoid the older pattern of routing mutating commands or approval decisions back through a caller-owned host facade
- active chat/runtime, skills, and settings observation on the UI side now also warms and retains the binder lease while that surface is attached, so the common interactive path prefers service-owned gateways instead of repeatedly paying a cold bind before every nearby write
- caller-side runtime-service start, wake, and bind access now live behind a dedicated environment-owned `RuntimeServiceAccessGateway` instead of static methods on `OpenCrayAgentRuntimeService`, which keeps UI/scheduler surfaces pointed at a transport-neutral runtime boundary rather than the Android service shell itself
- runtime-service bootstrap seams now live behind `RuntimeServiceBootstrapDependencies` plus the service-bootstrap factory seam, which reduces the remaining Android service responsibilities to lifecycle ownership plus assembled runtime components and makes later detached-owner migration less coupled to the service class body
- default binder-client construction now also resolves its start requester and base bind-intent factory through `RuntimeServiceAccessDependencies`, so caller-side transport assembly no longer needs to loop back through a production static runtime-service access helper after the dependency bundle has already been resolved
- the binding client now also requires those transport hooks explicitly instead of keeping hidden default callbacks, which removes the last production static runtime-service access side path from the detached client transport
- the binding client now also exposes an explicit `dispose()` path that clears listener state, cancels pending idle-release work, resets cached binder state, and unbinds when needed, so caller-side runtime reset can sever stale transport before the next retained-controller bootstrap
- the service shell now also exposes stable target-scoped delegating binder endpoints across shell reset, so an already bound client can keep using the same binder handle after same-service runtime reset and still land on the rebuilt target shell instead of a stale bootstrap-local endpoint
- background-thread service writes now also tolerate a longer cold-bind window before surfacing `binder_pending`, while the remaining main-thread callers still fail fast rather than freezing the UI
- the runtime-service gateway bundle now routes chat/skills/settings writes through normalized gateway command surfaces, carries chat snapshot invalidation on the service-owned chat gateway itself, and no longer constructs `OpenCrayHostRuntime` during service bootstrap
- that same gateway-bundle dependency surface now also carries explicit owner-observation, chat-mutation, and chat-submission runtime-access facets plus a dedicated `RuntimeServiceApprovalDecisionAccess`, so long-lived service-owned gateway wiring no longer retains a monolithic `OpenCrayRuntimeHostAccess` handle or raw approval lambdas after bootstrap
- the process-retained `RuntimeServiceBootstrapAssembly` now also keeps only explicit `runtimeOwnerLifecycle`, `runtimeHostAccess`, and `runtimeReplayAccess` facets instead of the old monolithic runtime-owner access bundle, which further reduces what the same-process execution controller keeps alive across service-shell recreation
- `RuntimeOwnerBootstrap` and `RuntimeServiceBootstrapFactory` now also expose only the lifecycle/access or scheduled-task infrastructure slices actually consumed during bootstrap, so the same-process runtime service no longer threads a monolithic runtime-owner access bundle or duplicate runtime-dependency payloads through intermediate bootstrap structs just to reach the final assembly
- `RuntimeOwnerBootstrap` now also carries separated observation/notification/approval/chat-submission runtime-access facets instead of a monolithic `OpenCrayRuntimeHostAccess`, so the process-owned bootstrap boundary stays aligned to the exact service-owned seams it still consumes after owner creation
- runtime-owner/work-state projection persistence and runtime notification observation now also run through a retained `RuntimeServiceProjectionCoordinator` stored on the process-owned bootstrap assembly, so same-process service recreate no longer rebuilds that observer/persistence layer just to reconnect a fresh shell
- the retained bootstrap assembly now also carries only narrowed bootstrap context/runtime-access facets instead of the whole runtime dependency bag and monolithic host facade, so same-process detached ownership keeps less nonessential baggage alive across service-shell recreate
- the service-owned execution coordinator now receives only the retained projection coordinator plus work-state tracker, so even the shell adapter no longer keeps unused owner-lifecycle, host-access, or service-lifecycle state on its dependency path
- the remaining service-owned `RuntimeServiceExecutionCoordinator` is now reduced to shell keepalive/foreground orchestration plus explicit projection flush calls into that retained coordinator, which narrows another piece of execution logic away from the Android service shell
- the shell keepalive and foreground state machines themselves now also live in a retained shell-control assembly captured by the process-owned execution controller, while each service recreate only rebinds visibility observation plus the current Android foreground/stop adapters onto those retained controllers
- that same retained transport path now also swaps and disposes the old service-owned gateway bundle on shell rebind, and the service-owned shell/chat gateways explicitly unregister their runtime, keepalive, and projection snapshot observers when released
- the retained controller/bootstrap path now also has an explicit teardown chain for projection observers, current gateway bundle, retained shell control, and caller-side binding transport reset, so controller replacement or explicit runtime reset does not need to wait for process death to release observer state
- the remaining `OpenCrayRuntimeServiceAccess` surface is now test-only compat; production retained-runtime reset/replace now flows through environment-owned gateways and shell bootstrap seams, so owner-replacement entrypoints can replace retained ownership without carrying stale binder state forward
- runtime-environment lookup now also rejects ownerless arbitrary `Context` instead of silently minting a fresh default environment, retained execution-controller defaults for runtime-owner bootstrap are likewise held by the owning runtime environment rather than the controller/provider itself, and the Flutter bridge entrypoint now requires explicit target plus gateway-factory wiring from the activity boundary instead of keeping fallback defaults in the bridge helper
- the matching `RuntimeServiceExecutionCoordinatorDependencies` bag is now likewise narrowed to only the shell/runtime surfaces still consumed after that split, so the service-owned coordinator no longer receives unused localization, chat-store, scheduled-task persistence, or local-runtime-server handles
- the binder-backed service shell gateway now also reads `AppShellStateStore`, runtime-owner work summary, and service lifecycle/work-state/keepalive directly rather than delegating shell snapshot/observation through `OpenCrayHostRuntime`, which removes another read-only surface from the monolithic host facade
- the binder-backed service chat gateway now also serves primary chat snapshot, per-run snapshot/wait, runtime snapshot, memory/soul debug reads, search/slice helpers, and memory-debug actions from projection-backed local stores plus service-owned observer fanout instead of delegating those surfaces through `OpenCrayHostRuntime`
- that same service-owned settings path now also serves settings overview/detail loads plus overview observation through a local `SettingsFacade` and service-owned observer fanout, so the settings-home read surface no longer depends on the monolithic host facade for settings persistence; the remaining host coupling there is narrowed to localized runtime refresh plus chat/skills/settings snapshot fanout
- notification settings on that service-owned settings path now read and write the same store already used for runtime notification delivery, which removes one more settings slice from the monolithic host facade
- that same service-owned settings path now also handles strong-background capability snapshot/actions through `AndroidStrongBackgroundSettingsAccess`, while preserving the projected `runtimeServiceConnectionState` field expected by the UI, so Android-local background setup no longer needs to bounce through the monolithic host facade
- the service shell keepalive and foreground controllers now also consume app visibility plus the derived strong-background tier policy, so runtime idle-grace behavior no longer uses a fixed one-size-fits-all timeout after the app backgrounds and the foreground notification can stay attached through tier-qualified idle grace
- that same service-owned settings path now also handles sandbox load/save through a narrowed sandbox-settings access boundary plus the existing sandbox payload mappers, so the repository-backed sandbox settings flow no longer depends on the monolithic host facade either
- that same service-owned settings path now also handles network-search and media-speech load/save through `LocalNetworkSearchConfigFacade` and `LocalMediaSpeechSettingsFacade`, so those facade-backed settings actions no longer depend on the monolithic host facade either
- that same service-owned settings path now also handles personalization load/save/reset through `LocalPersonalizationFacade`, and app-language persistence now also goes through a service-owned app-language access seam; localized refresh plus chat/skills/settings snapshot fanout now stay inside the service-owned gateway bundle, so binder-connected reads do not keep stale language state and no longer need to route the language write itself through the monolithic host facade
- that same service-owned settings path now also handles safety load/save through `LocalSafetySettingsFacade`, and safety saves emit a narrowed chat-only snapshot notifier rather than bouncing back through the monolithic host save path
- the same service-owned settings path now also handles LLM config load/save/custom-provider/validate plus MCP load/toggle flows through `LocalLlmConfigFacade` and `LocalMcpSettingsFacade`, so those facade-backed settings actions no longer depend on the monolithic host facade either
- network-search and media-speech saves on that service-owned settings path now also emit a narrowed settings-overview notifier, so the settings home observer still refreshes without re-entering the older host-owned save path
- the service-owned skills path now also handles skills snapshot/observation, install and batch-install, update check/update, source inspection, instructions loading, install-source activation, and local skill toggles/delete/refresh directly through `LocalSkillsFacade`, reducing the remaining same-process host-facade dependency for skills management

- add exact alarm capability checks
- add battery optimization checks and guidance
- add in-app setup flow
- add approval and completion notification actions

Exit criteria:

- user can deliberately opt into stronger local background behavior

### Phase 3: Boot and repair

Status:

- substantially implemented for boot/package-replaced schedule repair, startup reconciliation, periodic repair registration, and a first interrupted-interactive-run repair wake path

- add boot receiver
- re-register schedules on boot and package replace
- add startup reconciliation worker

Exit criteria:

- schedules survive reboot and upgrade cleanly

### Phase 4: Extended survivability

Status:

- still pending

- evaluate dedicated runtime process
- harden process-safe stores and service command protocol
- add richer OEM guidance

Exit criteria:

- runtime owner is better isolated from UI and app-shell churn

## Explicit Product Rules

- active work should enter foreground mode when detached and long-running
- scheduled triggers should create new runs unless the schedule policy says otherwise
- a scheduled trigger must never silently restart an interrupted run from task input
- user-visible interruption is better than fake continuity
- exact alarm access is optional capability, not a hidden requirement
- battery optimization exemption should be requested only through a user-visible strong-background setup flow

## Open Questions

1. Should generic agent execution use `specialUse` foreground service type for Android 14+ builds, or should foreground keepalive be restricted to a narrower task class for policy safety?
2. Should approval-needed state keep the runtime in foreground mode, or should it downgrade to a high-priority notification plus dormant checkpoint state?
3. Should periodic schedules support full cron syntax, or should the first release limit itself to timestamp, delay, and interval?
4. When a scheduled trigger fires into a busy session, should the default conflict policy be queue-behind or skip?
5. Should the "Strong background mode" setup be per-device global, per-workspace, or per-schedule?

## Recommended Initial Answers

- use queue-behind as the default conflict policy
- ship timestamp, delay, and interval first; cron can be compiled onto the same trigger model later
- keep approval decisions durable and actionable from notifications
- make strong-background setup device-global, while notification behavior remains per-schedule
- preserve the option for a later dedicated runtime process without blocking the first same-process rollout

## External References

- Android alarms: https://developer.android.com/develop/background-work/services/alarms/schedule
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager/
- Android long-running workers: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- Android exact alarm changes: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 15 foreground service changes: https://developer.android.com/about/versions/15/changes/foreground-service-types
- Android foreground service timeout behavior: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android user-stopped foreground-service apps: https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- Android Doze and App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- Codex app features: https://developers.openai.com/codex/app/features
- Codex automations: https://developers.openai.com/codex/app/automations
