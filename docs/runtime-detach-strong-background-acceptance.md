# Detached Runtime And Strong Background Acceptance

Last updated: 2026-07-18

## Delivery Gate

The local implementation gate for detached runtime ownership and Android strong-background
execution is complete. Release validation still requires physical-device/OEM repetition and a
credentialed E2B cloud reconnect run.

This gate uses the following ownership contract:

- `INTERACTIVE` execution is owned by `OpenCrayAgentRuntimeService` in `:runtime`.
- `DETACHED_BACKGROUND` and scheduled execution are owned by
  `OpenCrayDetachedRuntimeService` in the independent `:runtime_controller` process.
- UI, Flutter engine, activity, and main-process recreation do not own or implicitly resubmit
  detached work.
- A started-service wake that requires foreground bootstrap publishes the target-scoped minimal
  notification before runtime-environment resolution, shell construction, owner-lease acquisition,
  transport startup, or execution attachment. The retained shell controller adopts that foreground
  state after attach; a bound-only service creation remains non-foreground.
- A foreground-service start rejected for target mismatch or owner-lease contention stops its
  current Android start id before returning `START_NOT_STICKY`, so lease arbitration cannot leave
  a shell waiting for the foreground deadline.
- Killing the process that executes a run destroys that process's uncheckpointed memory. The
  supported recovery contract is same-run continuation from proven safe checkpoints, provider
  reconnect for durable managed processes, or explicit interruption when replay safety cannot be
  proven. Mutating work is never silently replayed.
- A third supervisor process that could move execution out of the target service process is a
  possible future isolation tier, not part of this delivery gate. A nominal extra process without
  moving execution ownership would not improve durability and must not set
  `controllerProcessSeparate=true`.

## Acceptance Matrix

| Requirement | Production evidence | Verification evidence | Status |
| --- | --- | --- | --- |
| Independent detached ownership | `AndroidManifest.xml`, `OpenCrayAgentRuntimeService.kt`, `RuntimeServiceIntentFactory.kt`, and `RuntimeServiceProcessDescriptor.kt` map the two runtime targets to separate components, PIDs, owner leases, foreground notification ids, projections, and loopback endpoints. | `RuntimeServiceProcessIsolationTest.targetServicesExposeV2RemoteControllersFromIndependentProcesses` plus runtime-service bootstrap/routing JVM tests. | Local gate complete |
| Cross-process controller reads and writes | `IRuntimeServiceController.aidl` v2 and `RuntimeServiceControllerBinderProtocol.kt` provide bounded projection reads, capability negotiation, and schema-versioned chat/skills/settings writes. V1 remains projection-only and falls back through the process-neutral command path. | `OpenCrayRuntimeServiceHostTest`, `RuntimeServiceWriteCommandProtocolTest`, and the API 35 v1/v2 remote-Binder isolation cases. | Local gate complete |
| Managed-process reconnect across runtime PIDs | `AgentProcessRegistry.kt`, `RoutedManagedProcessControllerFactory.kt`, `RoutingManagedProcessControllerFactory.kt`, and the E2B envd backend restore durable process identity, reconnect through the provider route, preserve backoff, and persist `cross_process`, `reconnect_attempted`, and `attached_live` evidence. | `E2BManagedProcessRegistryReconnectIntegrationTest` and `RuntimeServiceProcessIsolationTest.detachedProcessDeathReconnectsE2BManagedProcessThroughExternalEndpoint`. | Controlled local gate complete; credentialed cloud evidence pending |
| Process-safe durable state | `DirectoryDurableTextStorage` supplies per-file JVM plus OS sidecar locking, locked read-modify-write, temporary-file replacement, malformed-record recovery, and reentrant access. Runtime queue, checkpoint, journal, run, projection, settings, schedule, notification, subagent, media, sandbox, and managed-process stores use that storage family or an equivalent session-scoped OS lock. | `DirectoryDurableTextStorageTest`, file-store factory tests, run-journal tests, managed-process registry tests, and the runtime/persistence JVM suites. | Local gate complete |
| Process-safe workspace mutation | `FileMutationLockCoordinator` and `FileOpsService` hold one runtime-root OS lock across checkpoint capture, mutation, commit, and rollback. Replacement and rollback writes are atomic; `Edit` and `MultiEdit` keep source read and write in one reentrant transaction. | `FileOpsTest.executeBatchSerializesSharedWorkspaceAcrossServiceInstances` and the filesystem JVM suite. | Local gate complete |
| Safe process-death recovery | General prompt checkpoints cover pre-model, parsed action, commentary, committed tool result, supplement ingestion, finalization, and approval boundaries. The recovery planner resumes only proven state and records explicit interruption otherwise. | API 35 process-death cases verify same-task/run checkpoint continuation and the complementary no-checkpoint, no-replay interruption path; recovery planner and queue restore JVM tests cover the decision matrix. | Local gate complete |
| Convergent repair policy | `ScheduledTaskWorkManager`, runtime-service bootstrap/resume repair, owner-lease repair, and typed projection evidence support startup, boot/package replacement, periodic, reconnect-backoff, and lease-expiry wakes. Checkpoint continuation has a three-attempt budget; terminal and exhausted identities suppress stale repair projections while explicit Retry resets the budget. | `ScheduledTaskWorkManagerTest`, `OpenCrayRuntimeServiceInteractiveRepairTest`, checkpoint recovery tests, and the API 35 owner-lease repair case. | Local gate complete |
| Notification policy and actions | One process-safe notification-settings snapshot controls the master event-alert switch, default delivery, quiet hours, and seven application event types. The required foreground-service notice is presented as an Android system requirement rather than a fake toggle. Approval notifications expose Approve/Reject service actions; schedule and interrupted-run notifications expose their supported run, cancel, disable, snooze, retry, and open actions. | Notification policy/store/coordinator JVM tests, Flutter settings tests, and `RuntimeServiceProcessIsolationTest.approvalNotificationActionsSendImmutableServicePendingIntentsToDetachedRuntime` verify settings persistence plus immutable service `PendingIntent` delivery into `:runtime_controller`. | Local gate complete |
| Scheduled-task lifecycle and management | `ScheduledTaskCreate/List/Get/Update/RunNow/Snooze/Delete` share the runtime policy pipeline and `ScheduledTaskManager`. Binder v2, target-scoped loopback HTTP, and platform bridges expose list/detail/enable/run/snooze management to Flutter. The settings UI provides real list/detail state, enable switches, immediate run, 15-minute snooze, recent history, and schedule-id deep links without presenting unsaved prompt/trigger editors. | Runtime tool/policy tests, app manager/gateway/Binder/HTTP tests, Flutter bridge and settings tests, and the API 35 notification snooze-to-main-process WorkManager case. | Local gate complete |
| Strong-background delivery | Foreground `specialUse` services, target-scoped minimal bootstrap foreground before shell assembly, retained-controller notification handoff, app-visibility lease policy, AlarmManager plus main-process WorkManager wake bridging, boot/package repair, schedule actions, and explicit runtime wake commands keep detached work independent from visible UI. Bound-only attach does not enter foreground, and rejected starts stop their exact start id. | Runtime foreground, notification, scheduler routing, WorkManager, strong-background settings, shell startup-order JVM tests, and API 35 detached schedule/process-death/action tests. The isolated run has empty XML `system-err` and no FGS timeout in the cleared device log. | Local gate complete |

## Verification Baseline

The checked-in baseline has the following retained evidence:

- API 35 `RuntimeServiceProcessIsolationTest`: 8 tests, 0 failures, covering independent PIDs,
  AIDL v1/v2 compatibility, detached kill/recreate, owner-lease repair, safe checkpoint continuation,
  no-checkpoint interruption, process-external E2B reconnect, and detached-to-main WorkManager
  delivery. The schedule case sends the real notification snooze action, and the approval case sends
  immutable Approve/Reject service `PendingIntent`s into the detached process. The result XML has no
  `system-err`, and the cleared device log has no foreground-service deadline or fatal runtime
  exception.
- `:app:testDebugUnitTest`: 1655 tests, 12 skipped, 0 failures, and 0 errors.
- `:runtime:testDebugUnitTest`: 733 tests passed.
- `:filesystem:testDebugUnitTest`: 6 tests passed.
- `:persistence:testDebugUnitTest`: 16 tests passed.
- `:app:compileDebugKotlin` and `:app:compileDebugUnitTestKotlin`: passed.
- `dart analyze flutter_app`: passed with no issues.
- Focused Flutter settings, routing, and bridge regression: 147 tests passed.
- The complete Flutter suite still reproduces 20 pre-existing failures in
  `chat_feature_screen_test.dart`, concentrated in chat trace rendering, scroll-controller,
  attachment-tap, and image-inspector expectations. Running that file alone reproduces the same
  baseline failures; this detached-runtime/settings slice does not modify those chat surfaces.

Before release, rerun the practical JVM/compile regression on the merge candidate because these
counts can change as `master` evolves.

## External Release Gates

The following items require environment evidence and do not represent missing local ownership or
recovery code:

1. Run the isolated Android process-death suite repeatedly on at least one physical device and
   retain process, lease, checkpoint, reconnect, and WorkManager diagnostics.
2. Repeat kill/recreate and delayed-wake checks on the supported OEM/background-policy matrix.
3. Run the provider reconnect chain against a credentialed E2B cloud endpoint and retain the
   same-remote-pid reconnect evidence.

Broader automation trigger types and any future third-process supervisor are product extensions
outside this acceptance gate. The current schedule UI intentionally exposes only operations backed
by the shipped management gateway; schedule creation and full prompt/trigger editing remain on the
agent tool surface rather than appearing as non-functional settings controls.
