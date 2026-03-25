# Android Local Strong Background Runtime Design

Last updated: 2026-03-25

## Status

Design proposal

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
- `OpenCrayHostRuntime` is no longer the production execution owner
- UI transport can detach without immediately destroying runtime ownership

But the background product surface is still incomplete:

- execution is still driven by same-process executors
- the runtime service now promotes itself to foreground while keepalive-required work exists, but execution ownership is still same-process and not yet a separate stronger runtime process
- `AlarmManager` and `WorkManager` trigger bridges now exist for scheduled wake-up and repair
- active-runtime, approval-needed, completion/interruption, and scheduled-dispatch notifications now exist, including notification-side approve/reject entry for approval waits
- startup plus boot/package-replaced repair paths now re-register scheduled work, but active interactive runs still rely on checkpoint-based recovery rather than an independent scheduler-owned repair loop
- there is no battery-optimization guidance or exemption flow

So the current system now has the first real strong-background primitives, but it is still not yet a full local strong-background product.

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
- `ACTION_CANCEL_RUN`

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
- attach notification actions for open, cancel, and approve flows

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
- `Cancel`

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

Responsibilities:

- reload enabled `ScheduledTaskSpec`s
- re-register their next wake
- enqueue a lightweight reconciliation worker

Do not try to restart arbitrary active runs directly from boot broadcast handling.

Instead:

- re-register schedules
- enqueue repair work
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

Immediate rollout can stay same-process.

But the design should preserve a later path to:

- `com.opencray.app:runtime`

as a dedicated process for stronger isolation from UI crashes and Flutter engine churn.

## Recommended Rollout

### Phase 0: Product scaffolding

- add notification channels
- add runtime notification factory
- add foreground controller
- expose keepalive diagnostics in debug UI

Exit criteria:

- detached active runs can show a stable active notification

### Phase 1: Scheduled model and wake bridge

- add `ScheduledTaskSpec`
- add `ScheduledTaskRunRecord`
- add `AlarmManager` wake path
- add `WorkManager` wake and repair path
- add service wake commands

Exit criteria:

- delayed and timestamp triggers can create runs without an open page

### Phase 2: Strong background mode

- add exact alarm capability checks
- add battery optimization checks and guidance
- add in-app setup flow
- add approval and completion notification actions

Exit criteria:

- user can deliberately opt into stronger local background behavior

### Phase 3: Boot and repair

- add boot receiver
- re-register schedules on boot and package replace
- add startup reconciliation worker

Exit criteria:

- schedules survive reboot and upgrade cleanly

### Phase 4: Extended survivability

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
