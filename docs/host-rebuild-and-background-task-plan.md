# Host Rebuild And Background Task Plan

Last updated: 2026-06-10

## Status

Phase 2 recovery slice partially implemented; in-process detached runtime owner foundation landed, and the production Android runtime service now runs behind a dedicated `:runtime` service shell that bootstraps that owner. Foreground keepalive, notifications, scheduled wake bridges, an interrupted-run repair wake path, and a dedicated service wake-command dispatcher seam are in place. Managed-process restore is now controller-aware inside the same runtime process, a rebuilt owner can reconnect a live managed process across process/controller identity changes when the backend exposes reconnect support, and the runtime-level managed-process router now preserves non-Python-runtime delegate reconnect support instead of hiding it behind the Python adapter route. Retained execution-controller reset or replacement now also disposes the retained runtime owner instead of leaving session caches or executors hanging. Runtime routing is now explicit end to end as well: UI/client bundles default to `INTERACTIVE`, scheduled and detached-control work route to `DETACHED_BACKGROUND`, the service shell keeps target-keyed bootstraps instead of a single mutable default shell, service-backed chat writes now resolve their target from durable queue or checkpoint state before dispatch, durable projection fallback is now target-scoped so interactive and detached clients no longer overwrite each other's last snapshot bucket, and service-owned loopback transport plus lazy client projection fallback are now target-scoped too. Scheduler-owned repair is now target-aware at precheck time as well: persisted scheduled-task metadata, detached-control metadata, checkpoint-linked queue tasks, and durable background subagent handles can wake `DETACHED_BACKGROUND`, while checkpoint-only, run-record-only, and non-terminal journal-tail-only evidence stays conservative `INTERACTIVE`; run-record-only and journal-tail-only sessions are now included in repair candidate enumeration instead of requiring a queue/checkpoint row to survive, while terminal run records and final journal tails are ignored for interrupted-work detection. Schedule notification actions now also include accepted-run cancellation through the existing service-owned chat-write interrupt wake command, so cancellation stays attached to the runtime-service owner; tapping a schedule notification now opens the Notifications & Background settings detail entrypoint and carries `notificationScheduleId` forward for later schedule-specific management UI. The retained-runtime composition path is now also narrower: runtime dependency loading, local host gateway creation, gateway bundle assembly, Flutter/runtime target bridge entrypoints, and execution-controller bootstrap all resolve through explicit environment-owned seams instead of broad deep fallback helpers, with the controller path now taking only a narrowed `RuntimeExecutionDependencies` bag rather than the full runtime context dependency bag. Full detached ownership semantics beyond that runtime-service process are still pending.
Explicit runtime-service command envelopes now reject mismatched protocol versions before wake dispatch, so stale typed schedule/reset/chat-write commands do not fall through to action-based parsing.
Current repair targeting update: journal-tail-only evidence can now inherit `DETACHED_BACKGROUND` when it matches a scheduled/detached queue task; only unmatched non-terminal journal-tail evidence remains conservative `INTERACTIVE`.
Current ownership-evidence update: each target now has a process-safe runtime-owner lease heartbeat that records held/released owner evidence with process, controller, owner, service, heartbeat, and expiry fields. Different owners can no longer overwrite a still-held unexpired lease, rejected acquire attempts are recorded on the held lease with attempted owner/controller/service ids, retained runtime-owner replacement releases the previous owner lease before saving the new owner heartbeat, a coordinator that fails to acquire the target lease skips projection writes instead of replacing the active owner's projection, attach-denied shells now tear down their fresh shell/transport/execution bootstrap immediately instead of leaving a failed detached shell cached in-process, retained projection/notification observers only activate after a shell has actually attached with the lease, and non-owner service shells skip shell attach, start-command handling, wake-command dispatch, binder chat/skills/settings writes, and sticky restart requests. This narrows host-rebuild diagnostics and arbitration for retained-owner continuity, attach-denied ownership conflicts, and controller teardown, but it is still for the existing `:runtime` process owner rather than a separate detached controller process.
Current owner-lease repair update: attach-denied service starts now read the held target lease and enqueue an `owner_lease_expired` delayed repair for `expiresAtEpochMs`, while delayed repair work names are partitioned by reason so owner-lease expiry retries do not replace managed-process reconnect retries.
The shared durable text store now also uses per-file sidecar OS locks for read/write/delete/update, and key read-modify-write JSON stores for queue snapshots, run records, checkpoints, schedules, transcript fallback, memory, notification-delivery dedupe, session supplements, subagent handles, the runtime notification settings snapshot, and the cross-process app-visibility heartbeat now update under that same file lock. Scheduled run-record deletion/pruning now uses that single-file update path too, preventing stale schedule-delete snapshots from overwriting newer run records written by foreground, service, or repair flows. Runtime notification-delivery dedupe writes are covered by the same update primitive, preventing a stale foreground/service/repair snapshot from dropping another run's newer terminal-notification fingerprint. Runtime notification settings now also resolve from one runtime-root durable snapshot instead of multi-key `SharedPreferences`, so the main process settings UI and the `:runtime` notification coordinator share one process-safe detached-runtime source of truth. Mid-loop supplement append/consume is covered as well, preventing service-side consumption from re-saving an older snapshot over a supplement injected by another owner path. Transcript fallback append/replace/repair now uses the same primitive, preventing stale host rebuild or projection-fallback snapshots from dropping newer transcript events while journal-backed replay stays preferred. Projection snapshots get file-operation locking through the same abstraction. Direct-file run journal append/list/clear now uses a session-level sidecar OS lock, disk-derived sequence allocation under that lock, and temp-file atomic moves, so journal events are no longer a known cross-owner overwrite risk.
Current managed-process recovery update: live run snapshots, projection-only chat fallback, and host diagnostics now expose `hasAutoResumeEligibleManagedProcesses`, using the same stable-reconnect check as recovery-aware queue restore. `attached_live`, `completed`, and legacy local live snapshots can be distinguished from `connecting`, `retry_scheduled`, or interrupted terminal restore states before recovery chooses checkpoint replay, reconnect-hold, or explicit interruption. Live `ProcessRead` / `ProcessWait` observation tails with reconnect backoff now restore as `resume_reconnect_process` and persist process id, reconnect status, recovery state, retry-after, and attempt evidence into task metadata, lifecycle diagnostics, and the durable recovery journal marker.
Current managed-process repair update: scheduler repair preflight and runtime-service bootstrap/resume scans now read the file-backed process registry in passive `projection_only` mode, classify retry-scheduled or retryable connecting managed processes as `MANAGED_PROCESS_RECONNECT` evidence, and also recognize queue-stored `resume_reconnect_process` recovery metadata when only the recovery-aware queue snapshot survives. The repair path now carries process id and `repairAfterEpochMs` from that queue evidence into typed reconnect repair/projection, lets same-run/task queue, run-record, or journal-tail evidence inherit the reconnect deadline, and enqueues a replacement delayed repair for future `retryAfterEpochMs` values instead of relying only on the hourly periodic pass. Runtime-service bootstrap/resume scans still project future reconnect evidence for diagnostics, but they do not resume durable-only sessions until the retry deadline is due, and their projected result now exposes `nextRepairAfterEpochMs` directly while re-registering the delayed managed-process reconnect repair from that deadline.

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
- a first general prompt-resume slice now persists `general_resume` checkpoints after committed tool results, and runtime task creation plus app-layer restore can continue from that durable post-tool-result boundary after host rebuild
- a first recovery planner now projects per-run recovery intent from queue state, checkpoints, journal tail, and managed-process presence into chat run snapshots
- persisted queue restore now preprocesses snapshots through an explicit `SessionQueueRestoreTransformer` seam before `SessionQueue` restore, so approval-boundary recoveries can keep the same run non-terminal instead of falling straight into explicit-retry failure
- `approved_pending_resume` and `general_resume` checkpoints restore back to the same queued run when safe, `waiting_approval` restores back to the same suspended run when safe, and `rejected_pending_resume` restores into a stopped run awaiting the next user instruction
- live managed-process restores now also fall back to that same queued checkpoint-resume path when a safe `general_resume` checkpoint exists, instead of staying in a reconnect state that cannot yet continue end-to-end
- run snapshots now expose managed-process auto-resume eligibility consistently across live owner reads and projection-only fallback, so host rebuild diagnostics can show whether a live process is stable enough for checkpoint-backed observation replay or is still in reconnect backoff
- live managed-process observation tails that are not stable enough for checkpoint-backed auto-resume now restore to a suspended `resume_reconnect_process` state with durable reconnect evidence, instead of failing as a generic uncertain in-flight mutation
- scheduler repair preflight now promotes queue-stored `resume_reconnect_process` metadata into typed `MANAGED_PROCESS_RECONNECT` evidence, preserving the persisted process id and retry-after delay even when the process registry cannot provide live reconnect state
- reconnect-hold backoff now dominates matching same-run/task repair evidence, so a stale run record or journal tail cannot wake repair or service-side bootstrap/resume ahead of the persisted retry deadline
- service-side interrupted-run repair projection now persists `nextRepairAfterEpochMs`, so host rebuild and binder-unavailable diagnostics can report the next delayed repair deadline without recomputing it from individual evidence entries, and the service-side bootstrap/resume result handler now also re-enqueues that delayed managed-process reconnect repair
- file-backed managed-process registries can now reattach a live controller across registry or host rebuild while the same app process is still alive, so same-process rebuild no longer always degrades into `PROCESS_INTERRUPTED_ON_RESTORE`
- that managed-process restore path is now also scoped by runtime controller identity instead of only session directory, so rebuilt controllers in the same process no longer accidentally share a live-process reattach slot
- interrupted managed-process restore now stamps restore-scope metadata (`same_controller`, `same_process_new_controller`, `cross_process`, or `unknown`) plus current process/controller identity, and lifecycle diagnostics surface those narrower reasons in repaired runs
- app-layer session restore is now also covered by a real cross-owner reconnect path for reconnectable managed-process backends, so a rebuilt runtime owner can reopen the durable session directory with a new process/controller identity and keep the live managed process attached instead of forcing `PROCESS_INTERRUPTED_ON_RESTORE`
- runtime-layer `RoutedManagedProcessControllerFactory` now also forwards non-Python-runtime reconnect to a reconnectable default delegate, while Python-runtime-adapter script executions remain explicitly non-reconnectable until they have an external controller handle
- provider-native builtin web-search observations now also persist recoverable `general_resume` metadata through their synthetic tool-result events, so a host rebuild after provider-managed search no longer loses that safe continuation boundary
- session resume no longer spins the executor on approval-waiting runs that have no runnable queue work
- restore planning now prefers durable journal tail over `lastEvent` summary, so interruption classification is based on the append-only runtime history when available
- if the explicit `general_resume` checkpoint is missing, restore can now synthesize one from the durable journal tail or persisted `lastEvent` when that safe boundary already contains `OpenCrayPromptResumeMetadata`, which lets the same run continue without falling back to interruption solely because the checkpoint store lost that row
- if explicit approval checkpoints are missing, restore can now synthesize `waiting_approval`, `approved_pending_resume`, or `rejected_pending_resume` from durable approval-denial result metadata plus the durable tail approval event, which preserves approval-state recovery even when the checkpoint store loses that task row
- host-side terminal replay repair now also backfills the pending assistant chat message from durable finalization evidence or other synthesized terminal results, so host rebuild no longer leaves an already-finished run visually stuck on the pre-finish placeholder
- interrupted runs without a recoverable checkpoint now stay explicitly interrupted in planner output instead of hinting that an automatic rerun is expected
- runtime ownership is now split from the host facade inside the app process: an in-process runtime owner registry keeps `DefaultAgentSessionRuntimeManager`, journal/checkpoint stores, approval registry, and runtime callbacks alive even if `OpenCrayHostRuntime` is rebuilt
- runtime snapshots now expose both `hostLifecycle` and `runtimeOwnerLifecycle`, so host recreation can be distinguished from runtime-owner continuity in diagnostics and UI projection
- client-delivered shell and chat-runtime snapshot payloads now also carry explicit `flutterAppInstanceId` and `bridgeInstanceId`, so the diagnostics page can distinguish Flutter-isolate continuity from Android host-bridge recreation without treating either one as runtime-owner loss
- runtime service bootstrap now happens only when an explicit wake, binder-demanding command, or other service start path actually starts `OpenCrayAgentRuntimeService`; once started, the service bootstraps the shared in-process runtime owner on `onCreate()`
- `OpenCrayAgentRuntimeService` no longer eagerly attaches a default runtime shell on `onCreate()`; start and bind now attach the requested `RuntimeServiceTarget`, and `RuntimeServiceShellController` keeps target-keyed bootstraps so interactive and detached shells can coexist inside one Android service instance
- the service host now hands `OpenCrayHostRuntime` a narrowed runtime-access/replay-access bundle instead of the raw owner, which keeps the next binder/service-lifetime slice from changing host wiring again
- `OpenCrayHostRuntime` now consumes that boundary through a single `OpenCrayRuntimeHostAccess` facade rather than directly reaching for session runtime manager, approval registry, or per-session store factories
- host bootstrap now resolves a formal runtime service client, not the bridge directly, so transport choice and lifecycle projection are separated from host construction
- runtime and shell snapshots now emit `runtimeServiceConnectionState`, which makes binder-backed access versus in-process fallback explicit during same-process service rollout
- shell snapshots now also emit `localRuntimeServerState`, which makes loopback HTTP server startup and bind failures visible separately from binder transport churn
- app bootstrap no longer eagerly ensures the runtime service; it only performs app-level registration, skill seeding, schedule resync, and repair enqueue. When the runtime service is later started by an explicit wake or binder-demanding path, the local loopback server is bootstrapped from `OpenCrayAgentRuntimeService.onCreate()`, so both Android transports still initialize from the same service-side boundary
- the runtime service client now performs a real non-blocking `bindService(...)` attempt and keeps its connection state live, instead of inferring binder reachability only from same-process static access
- host observers now emit fresh shell/runtime snapshots when that client state changes, so a late binder attachment no longer requires rebuilding the host singleton to become visible
- production `OpenCrayHostRuntime` creation is now projection-only for session bootstrap: active-session `resume()` and terminal replay repair run once from runtime service host initialization instead of from every host-facade constructor
- caller-side runtime-service entrypoints now only request service start or wake; runtime service host bootstrap happens inside `OpenCrayAgentRuntimeService.onCreate()`, and the client fallback snapshot bridge only reads an existing host instead of hiding a caller-side `getOrCreate(...)` bootstrap behind the first binder-pending snapshot load
- durable runtime-service projection persistence and binder-unavailable client fallback are now target-scoped too, so `INTERACTIVE` and `DETACHED_BACKGROUND` no longer share one last-snapshot bucket when binder access is missing
- caller-side runtime-service start, wake, and client acquisition now also route through an environment-owned `RuntimeServiceAccessGateway`, so UI and scheduler code no longer depend directly on `OpenCrayAgentRuntimeService` static methods just to reach the detached runtime boundary
- runtime-service bootstrap dependency injection now routes through `RuntimeServiceBootstrapDependencies` plus the service-bootstrap factory seam, which keeps host/bootstrap seams outside the Android service class and narrows the remaining same-process service shell further toward lifecycle-only responsibilities
- runtime-service bootstrap seams now also collapse into a single `RuntimeServiceBootstrapDependencies` bundle that the service instance captures once during `onCreate()`, and the caller-side access seam now also collapses into `RuntimeServiceAccessDependencies`, which reduces registry/access global state fanout and makes test cleanup/reset no longer depend on clearing each bootstrap field separately
- service-shell attach/start/bind wiring now stays aligned to a host-free assembly seam: `RuntimeServiceShellController` still owns only lifecycle delegation while `OpenCrayAgentRuntimeServiceBootstrap` consumes the injected `RuntimeServiceBootstrapDependencies` bundle at bootstrap time
- runtime-service bootstrap tests now inject `RuntimeServiceBootstrapDependencies` directly at the call site, so production code no longer carries bootstrap-specific global override seams just to satisfy tests
- caller-side binder-client construction now also receives its start requester and base bind-intent factory through `RuntimeServiceAccessDependencies`, so the default Android binding client no longer reaches back into a production static runtime-service access facade while assembling its own transport bootstrap
- that binding client now also requires the start requester and base intent factory explicitly at construction time, so the detached client path no longer hides a default callback path back into a production static runtime-service access facade
- the loopback runtime server main-code surface is now gateway/provider-only as well, with the prior `OpenCrayHostRuntime` convenience constructor removed from production code and retained only as test-local composition, which further reduces accidental host-facade coupling outside the detached runtime boundary
- runtime-service bind/action intent construction is now centralized behind `RuntimeServiceIntentFactory`, which removes another scattered source of service-class coupling from notification, binder, and scheduled-wake code paths
- caller-side start/wake transport now also consumes those endpoint-built intents directly, so the production access gateway no longer re-describes runtime-service action names or extras while issuing scheduled/repair/start requests
- bridge-default runtime-service start and base-bind-intent lookup now also route through the environment-owned `RuntimeServiceAccessGateway`, so those caller-side defaults no longer retain a side door back to the concrete Android intent factory outside the access seam
- loopback runtime-server startup is now routed through `RuntimeServiceLoopbackBootstrapFactory`, which narrows transport bootstrap down to gateway-bundle assembly plus a transport-owned startup hook instead of directly wiring the loopback registry and provider bundle inline
- that loopback provider fanout now also dereferences shell/chat/skills/settings gateways through a retained `RuntimeServiceTransportCoordinator`, so later service-shell recreate can swap in the newest gateway bundle without forcing a loopback server/provider restart
- that retained transport coordinator now also owns the current local runtime-server state provider, and loopback bootstrap binds `server::currentState` into it once startup succeeds, so the retained execution-controller/bootstrap-assembly path no longer defaults to `OpenCrayLocalRuntimeServerRegistry.peekState()` just to surface loopback diagnostics
- service-owned gateway-bundle assembly inside that transport bootstrap now also routes through a dedicated `RuntimeServiceGatewayBundleFactory` carried by `RuntimeServiceBootstrapDependencies`, and that factory now receives a narrowed `RuntimeServiceGatewayBundleDependencies` bundle instead of the whole `OpenCrayRuntimeServiceHost`, which keeps both transport bootstrap and gateway composition aligned to explicit runtime/service dependencies rather than a monolithic host object
- execution coordinator, wake dispatcher, and binder endpoint now likewise consume narrowed `RuntimeServiceExecutionCoordinatorDependencies`, `RuntimeServiceWakeCommandDispatcherDependencies`, and `RuntimeServiceBinderEndpointDependencies` bundles instead of the whole `OpenCrayRuntimeServiceHost`, which keeps those long-lived service helpers aligned to explicit behavior/data dependencies rather than a monolithic host object
- service-owned chat/session/schedule/notification consumers now also depend on narrower runtime-access facets such as `RuntimeChatMutationAccess`, `RuntimeChatSubmissionHostAccess`, `RuntimeRunLookupAccess`, and `RuntimeNotificationHostAccess`, so detached runtime helpers no longer all retain the full `OpenCrayRuntimeHostAccess` facade
- runtime-service bootstrap now resolves the raw host once into a `RuntimeServiceBootstrapState` that only carries those pre-derived dependency bundles, and the final `OpenCrayAgentRuntimeServiceBootstrap` no longer exposes `serviceHost`, which confines the monolithic host object to one-time bootstrap assembly rather than the long-lived detached-runtime surface
- the runtime-service bootstrap dependency bundle now only exposes `RuntimeServiceBootstrapState`, `OpenCrayAgentRuntimeServiceBootstrap.resetRuntimeOwner()`, and narrowed assembled components rather than a raw `resolveServiceHost(...)` seam or direct execution-controller path, which keeps both the monolithic host object and the process-scoped controller behind the bootstrap boundary even in tests
- the retained `RuntimeServiceExecutionController` no longer exposes its full bootstrap-assembly bag directly either; retained projection, transport, local-runtime-server, and shell-control details now flow only through explicit controller methods or derived bootstrap state, which removes another broad detached-runtime escape hatch from production code
- production runtime-service bootstrap state now resolves only through the process-singleton `RuntimeServiceExecutionController` and derives `RuntimeServiceBootstrapState` from that controller instead of calling `OpenCrayRuntimeServiceHostRegistry.getOrCreate(...)`, so the detached runtime bootstrap main path no longer depends on a host-registry singleton or a second bootstrap pipeline
- that production path now reuses the dedicated `RuntimeServiceBootstrapAssembly` already captured by the execution controller, so dependency-bag derivation no longer needs a materialized `OpenCrayRuntimeServiceHost` even as an internal bootstrap intermediate on the main path
- the bootstrap-only assembly/state conversion helpers now live in dedicated runtime-service bootstrap support files rather than `OpenCrayRuntimeServiceHost` or a default resolver singleton, which keeps transport, binder, wake, and scheduled-task files from each carrying their own host-conversion adapters
- the remaining production bootstrap seam names are now host-free as well: `RuntimeServiceBootstrapFactory`, `RuntimeServiceBootstrapParts`, `bootstrapRuntimeServiceSessions(...)`, and `resumeInterruptedRuntimeServiceRuns(...)` now describe detached runtime-service behavior directly, and the old `OpenCrayRuntimeServiceHost` / `OpenCrayRuntimeServiceHostRegistry` compatibility layer has been removed from `main` code and retained only as test-local fixture support
- runtime-owner access/replay-access seams and runtime-service lifecycle/work-state carrier types now also live in dedicated support files rather than `OpenCrayRuntimeServiceHost`, so the host file no longer acts as the shared detached-runtime type bucket for process-owned execution state
- runtime-owner creation is now separated from runtime-access projection by an explicit `RuntimeOwnerBootstrap` provider/helper, and the execution-controller registry now resolves runtime dependencies before creating that owner bootstrap, which narrows another hidden process-singleton side path out of service bootstrap without keeping a second owner-level registry alive in production
- that same retained execution-controller path now also inherits its default runtime-owner bootstrap provider from the owning runtime environment instead of deciding that owner bootstrap locally, and production runtime-environment lookup no longer falls back to minting a fresh default environment for arbitrary ownerless `Context`
- the execution-controller registry now also resolves a dedicated `RuntimeExecutionDependencies` bag from that environment-owned loader, so the retained controller path keeps only app context, bootstrap context, and retained owner-bootstrap inputs instead of carrying the full `OpenCrayRuntimeContextDependencies` bag through controller creation
- `RuntimeServiceBootstrapFactory` now consumes pre-resolved runtime dependencies plus the explicit lifecycle/access facets surfaced through `RuntimeOwnerBootstrap`, and the execution controller only retains controller lifecycle plus the service-neutral bootstrap assembly, so once the runtime-process execution controller exists the bootstrap factory no longer re-loads dependencies, hold onto the full owner-controller shell, or re-hydrates a monolithic owner-access bundle on its own
- the Android service shell now now resets retained runtime ownership only through the attached bootstrap's explicit `resetRuntimeOwner()` seam, which trims another controller-level dependency off the long-lived shell path without changing retained reset semantics
- the production `RuntimeServiceExecutionControllerProvider` contract is now resolve-only too; mutating hooks such as `peek/reset/swap` are no longer part of the `main` bootstrap abstraction and remain only as concrete process-scoped test seams, which removes another hidden controller-mutation edge from the detached runtime path
- remaining legacy host/owner bootstrap shims such as the direct bootstrap-state helper and the old in-process owner registry are now confined to test-only compat code instead of shipping on the main detached-runtime path
- `OpenCrayHostRuntime.createForTest(...)` now also lives only in test compat, with production keeping only a neutral `createWithRuntimeAccess(...)` bridge, so the detached-runtime line no longer exposes a test-named host-runtime constructor plus in-memory defaults from `main`
- runtime-service approval/session-approval/reject handling now also resolves through a dedicated `RuntimeServiceApprovalDecisionAccess`, and the wake/binder/gateway dependency bundles now point at that access plus the host-free interrupted-run repair helper instead of retaining direct closures over host approval methods
- binder-endpoint snapshot loading now also carries narrowed `RuntimeServiceBridgeSnapshotDependencies` instead of a host-backed snapshot lambda, which keeps post-bootstrap binder snapshot reads from closing over the monolithic service host
- scheduled-task wake dispatch and repair now also carry explicit `ScheduledTaskDispatcherDependencies` and `ScheduledTaskRepairDependencies` inside the wake-dispatcher dependency bundle, which removes another post-bootstrap side door back into host extension methods
- the old in-process/existing fallback runtime-service bridge compat layer has now been removed from main code entirely; production binder clients read binder snapshots directly and otherwise fall back only to durable projection snapshots
- runtime-service bridge snapshots now also carry explicit `runtimeOwnerLifecycle` and `runtimeOwnerWorkSummary` fields, so projection fallback and binder diagnostics no longer need to traverse the full runtime-owner access object to render detached-runtime owner state
- the old local loopback runtime-server registry plus its `OpenCrayLocalRuntimeServerProvidersFactory` default bundle path now live only in test compat, so production main code no longer keeps a process-global non-service loopback bootstrap seam around just to assemble `serviceBackedOpenCray*Gateway(...)`
- default client-side local/service-backed gateway assembly now resolves through `OpenCrayClientGatewayBundleFactory`, so the Flutter activity/bridge path and the loopback provider path no longer each construct their own `serviceBackedOpenCray*Gateway(...)` combination inline
- service-backed shell/chat/skills/settings assembly now also resolves through `OpenCrayServiceBackedGatewayBundleFactory`, and loopback provider fanout now resolves through `OpenCrayLocalRuntimeServerProvidersSupport`, so the client-facing detached bridge path and the service-owned loopback path no longer drift on which gateways/providers make up the HTTP surface
- the repeated runtime/service diagnostics-head map assembly used by host runtime, service-owned shell, projection shell, and projection chat now resolves through `RuntimeServiceDiagnosticsProjectionSupport`, which reduces drift across binder-owned and projection-owned read paths
- `OpenCrayHostRuntime` now carries runtime-owner/service diagnostics providers plus keepalive/connection refresh registrars through `HostRuntimeDiagnosticsBridge`, so the monolithic host constructor no longer stores that read-only service bridge as a loose set of inline fields
- caller-side `OpenCrayClientGatewayBundleFactory` now also caches the assembled client gateway bundle per `RuntimeServiceTarget` and reuses one app-scoped `OpenCrayLocalHostGateway`, so ordinary Flutter activity or bridge recreation no longer rebuilds the whole service-backed plus projection fallback gateway stack on every attach
- the existing repair worker now preflights interrupted repair candidates from persisted queue snapshots, prompt checkpoints, durable background subagent handles, durable run-record-only evidence, or non-terminal journal-tail-only evidence and can wake `OpenCrayAgentRuntimeService` with `ACTION_RESUME_INTERRUPTED_RUNS`
- runtime-service interrupted-run repair plus first-bootstrap session scan now reuse that same durable per-session repair predicate, so persisted queue, checkpoint, durable background-subagent, run-record-only, and non-terminal journal-tail-only recovery candidates are not lost just because the service-side rescan starts from an empty in-memory session projection; the latest bootstrap/resume repair projection is persisted into the target-scoped runtime-service projection snapshot for binder-unavailable diagnostics; terminal run records and final journal tails remain terminal evidence rather than recurring interrupted-work triggers
- target-scoped runtime-owner lease heartbeat now writes held/released owner evidence through a process-safe durable store and mirrors the latest lease plus the latest rejected acquire attempt into runtime-service projection diagnostics, so host rebuild reports can identify which runtime owner/controller last claimed a target and which competing shell was refused without peeking a live process-local singleton
- app-start and boot/package-replaced paths now also keep a unique periodic `WorkManager` repair registered, so interrupted interactive repair and schedule reconciliation get recurring autonomous checks instead of only a one-shot app-start or broadcast pass
- the app-visibility bridge that feeds runtime-process keepalive, foreground, and notification policy now also persists its lease through the same process-safe runtime-root file store instead of `SharedPreferences`, while the app-private broadcast remains only the fast fanout path, so main-process and `:runtime` reads converge on one durable visibility source of truth
- service-backed chat/skills/settings gateways now keep fallback strictly projection-only for reads; binder-unavailable writes and tool-executing commands fail explicitly instead of silently dropping back to the UI-side facade
- the shell snapshot surface now goes through a dedicated `OpenCrayShellGateway`, and the Flutter bridge plus loopback HTTP server prefer a binder-backed service shell gateway for shell loads and shell observation
- chat/runtime commands and snapshots are now fronted by a dedicated `OpenCrayChatRuntimeGateway`, and both the Flutter host bridge and the loopback HTTP server use that gateway for the execution-facing path
- the service binder now exposes a service-owned chat/runtime gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for chat/runtime loads and commands whenever binding succeeds
- chat/runtime mutating commands now go through an explicit binder dispatch path instead of first fetching a live binder chat gateway for writes; reads and observers still rebind between binder and projection fallback as connection state changes
- approval, rejection, and session-scope approval for chat/runtime now resolve inside `OpenCrayRuntimeServiceHost` rather than delegating back into the UI-side host runtime, keeping approval checkpoints, session grants, snapshot emission, and sub-agent replay on the same service-owned boundary
- chat/runtime observation now rebinds between the fallback host gateway and the binder-backed service gateway as service connection state changes, so the UI event streams can follow the service-owned runtime without reconstructing the bridge
- skills snapshot, observation, install, update, delete, inspect, and instructions flows are now fronted by a dedicated `OpenCraySkillsGateway`, and both the Flutter host bridge and the loopback HTTP server use that gateway for the skills-management path
- the service binder now exposes a service-owned skills gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for skills loads and commands whenever binding succeeds
- skills mutating commands now go through an explicit binder dispatch path instead of first fetching a live binder skills gateway for writes; reads and observers still rebind between binder and projection fallback as connection state changes
- skills observation now rebinds between the fallback host gateway and the binder-backed service gateway as service connection state changes, so the skills page can follow the service-owned runtime without reconstructing the bridge
- settings overview, runtime-config loads, and runtime-config writes are now fronted by a dedicated `OpenCraySettingsGateway`, and both the Flutter host bridge and the loopback HTTP server use that gateway for the settings-management path
- the service binder now exposes a service-owned settings gateway, and the Flutter bridge plus loopback HTTP server prefer that binder-backed gateway for settings and runtime-config loads or writes whenever binding succeeds
- settings mutating commands now use the same service-owned write boundary instead of requiring a live binder settings gateway object first, so fallback remains read-only for settings projection while settings writes stay attached to the runtime service owner
- settings observation now rebinds between the fallback host gateway and the binder-backed service gateway as service connection state changes, so the settings UI can follow the service-owned runtime without reconstructing the bridge
- on the binder-backed path, settings overview/detail loads and overview observation now also terminate inside a service-owned `SettingsFacade` plus local observer fanout, which removes another read path back into `OpenCrayHostRuntime`; app-language persistence now also routes through a service-owned seam, leaving only localized runtime refresh plus chat/skills/settings snapshot fanout on the narrower host side
- service-backed shell/chat/skills/settings observers now re-check the active gateway immediately after connection observation registration, which closes the binder-connect race that could otherwise leave a UI stream stuck on projection fallback until a later connection transition
- the Android runtime-service client now treats binder attachment as an idle-released transport lease instead of a permanent process-wide bind: active connection observers keep the binder attached, transient reads or commands schedule an automatic unbind after a short quiet window, and detached execution still belongs to the started service plus keepalive path rather than the UI transport
- caller-side runtime-service bundles now default to `INTERACTIVE`, while scheduled wakes, detached approvals, and other detached-control paths explicitly target `DETACHED_BACKGROUND`; service-backed chat writes now also resolve `submit`, `approve`, `reject`, `retry`, and `interrupt` through durable queue or checkpoint state instead of pinning those writes to the interactive client
- service-backed shell/chat/skills/settings read observers now subscribe passively to connection-state changes, so startup-time UI snapshot streams no longer trigger `ensureStarted(...)` or `bindService(...)` just to watch fallback projection state
- workspace tree/document operations, local file open/share, native toast, twin import probing, and draft attachment import are now fronted by a dedicated `OpenCrayLocalHostGateway`, so the Flutter host bridge and the loopback HTTP server no longer hold a full `OpenCrayHostRuntime` just to reach local-only device/workspace capabilities
- `OpenCrayHostRuntime` now implements that same local-only gateway by delegation, which preserves the current projection fallback path while keeping service-owned runtime surfaces separate from local host helpers
- the binder-backed service shell gateway now also serves shell snapshot/observation directly from `AppShellStateStore`, runtime-owner work summary, and service lifecycle/work-state/keepalive providers rather than delegating that read-only surface through `OpenCrayHostRuntime`
- the binder-backed service chat gateway now also serves primary chat snapshot, per-run snapshot/wait, runtime snapshot, memory/soul debug reads, search/slice helpers, and memory-debug actions through projection-backed local stores plus service-owned observer fanout instead of delegating those read surfaces through `OpenCrayHostRuntime`
- shell, settings, and skills read fallback are now served by dedicated projection-only gateways rather than a full `OpenCrayHostRuntime`, so binder-pending reads on those surfaces no longer instantiate the UI-side host facade
- chat projection fallback now also reads runtime/service lifecycle metadata only from the client-visible bridge snapshot path instead of performing its own direct service-host registry peek, which keeps binder-pending chat projection aligned with the same transport-neutral snapshot boundary used elsewhere
- shell/chat projection fallback now persist and reload a durable runtime-service projection snapshot for runtime-owner lifecycle, work-summary, service lifecycle, service work-state, and keepalive state, which removes the last production read path that needed a live service-host registry fallback when binder access is missing
- projection-only chat pending-approval rendering now uses the same shared approval lookup/projection helper as the service-owned approval path, so detached approval runs with only durable run/checkpoint state remain visible even when the queue task snapshot is missing
- the Android runtime-service client's legacy full-snapshot APIs now default to an explicit missing-snapshot bridge rather than `OpenCrayRuntimeServiceHostRegistry.peek()`, so production binder-unavailable callers must use binder attachment or projection snapshots instead of silently reading a live host singleton
- the service-started loopback HTTP runtime server now receives direct service-owned shell/chat/skills/settings gateways from `OpenCrayAgentRuntimeService`, so in-service HTTP calls no longer re-enter runtime transport via `serviceBackedOpenCray*Gateway(...)`
- `OpenCrayLocalHostGateway` no longer resolves sandbox-preview embed config by calling `ensureInProcessRuntimeOwner(...)`; it now builds that preview helper directly from sandbox settings plus the persisted E2B session store, which removes another local-only path back into the old in-process owner
- the runtime-service gateway bundle now dispatches chat/skills/settings writes through the normalized gateway command helpers and carries chat snapshot invalidation as part of the service-owned chat gateway boundary, which removes another set of direct `OpenCrayHostRuntime` function references from the service surface
- the runtime-service gateway bundle no longer constructs `OpenCrayHostRuntime` during service bootstrap; service-owned chat/skills/settings surfaces are now wired directly from projection stores, local facades, and narrowed access seams, and sandbox-session refresh now submits straight through the shared runtime-owner helper
- that same gateway-bundle dependency surface now also carries explicit owner-observation, chat-mutation, and chat-submission runtime-access facets plus a dedicated `RuntimeServiceApprovalDecisionAccess`, so long-lived service-owned gateway wiring no longer retains a monolithic `OpenCrayRuntimeHostAccess` handle or raw approval lambdas after bootstrap
- the process-retained `RuntimeServiceBootstrapAssembly` now also keeps only explicit `runtimeOwnerLifecycle`, `runtimeHostAccess`, and `runtimeReplayAccess` facets instead of the old monolithic runtime-owner access bundle, which narrows the detached execution-controller boundary further and keeps nonessential owner surfaces out of service-recreate state
- `RuntimeOwnerBootstrap` and `RuntimeServiceBootstrapFactory` now also expose only the lifecycle/access or scheduled-task infrastructure slices needed by bootstrap, so process bootstrap no longer threads a monolithic runtime-owner access bundle or duplicated runtime dependencies through intermediate factory results before reaching the final assembly
- `RuntimeOwnerBootstrap` now also carries only separated observation/notification/approval/chat-submission runtime-access facets instead of a monolithic `OpenCrayRuntimeHostAccess` field, so the retained execution-controller/bootstrap boundary no longer has to thread that combined host facade type through process-owned service state
- runtime-owner/work-state projection persistence plus runtime notification observation now also sit behind a retained `RuntimeServiceProjectionCoordinator` carried by the process-owned bootstrap assembly, which means service recreate no longer has to rebuild that observer/persistence layer just to reconnect a new shell
- that retained projection coordinator now also keeps only `RuntimeOwnerObservationAccess` for projection snapshot writes, while notification-specific run lookup stays in `RuntimeNotificationCoordinator`, which trims another unnecessary owner-access edge off the long-lived detached-runtime path
- that retained bootstrap assembly now also stores a narrowed `RuntimeServiceBootstrapContext` plus `RuntimeServiceBootstrapRuntimeAccess` instead of the whole runtime dependency bag and monolithic host facade, which keeps the process-retained detached-runtime state closer to the actual store/facade/runtime-access seams it still consumes
- the service-owned `RuntimeServiceExecutionCoordinator` is now correspondingly narrower and only bridges service-shell keepalive/foreground lifecycle into that retained projection layer, rather than directly owning notification delivery and durable projection writes
- that service-owned execution-coordinator dependency bag is now also trimmed again to just the retained projection coordinator plus work-state tracker, so even the shell adapter no longer retains runtime-owner lifecycle, host access, service lifecycle, localization, chat-store, scheduled-task persistence, or local-runtime-server handles it never reads
- service-owned gateway localization now resolves through a shared host-runtime-strings helper instead of statically calling `OpenCrayHostRuntime` just to assemble localized labels, which removes another leftover service-to-host dependency
- runtime-owner resolution now flows through an explicit `RuntimeOwnerBootstrap` provider/helper instead of calling `ensureInProcessRuntimeOwner(...)` inline, so the current same-process owner remains the default but a stronger owner implementation can swap in without reopening host bootstrap wiring
- runtime-service host bootstrap now also resolves Android-backed dependency loading, scheduled-task stores, and trigger registrar wiring through a dedicated bootstrap-factory seam instead of assembling them inline inside `OpenCrayRuntimeServiceHost`, which narrows the eventual migration path toward a stronger detached owner/bootstrap path
- `OpenCrayAgentRuntimeService` now resolves a dedicated `OpenCrayAgentRuntimeServiceBootstrap` bundle during `onCreate()`, removing the last duplicate direct registry bootstrap path from the service entrypoint itself and avoiding a long-lived raw service-host field on the Android service shell
- `OpenCrayAgentRuntimeService` now also constructs a dedicated `RuntimeServiceShellController` directly, so the Android service class itself has been narrowed to pure lifecycle delegation rather than owning process bootstrap, start-command dispatch, bind exposure, and teardown inline
- `OpenCrayAgentRuntimeService` now also resolves keepalive and foreground controller construction through a dedicated shell-control bundle factory seam, so those controller instances are no longer hardcoded inline in the service before later bootstrap steps attach to them
- `OpenCrayAgentRuntimeService` now also resolves its transport/bootstrap wiring through a dedicated transport-bootstrap factory seam that assembles the service-owned gateway bundle and starts the loopback runtime server, so the service entrypoint no longer hardcodes local transport startup itself
- `OpenCrayAgentRuntimeService` now also resolves keepalive/foreground/notification/projection observer wiring through a dedicated execution-coordinator seam, so the service entrypoint no longer owns those observer registrations and projection-store writes inline
- that runtime-process bootstrap state now also retains a dedicated `RuntimeServiceProjectionCoordinator`, so projection persistence and scheduled-dispatch notification fanout survive service-shell recreate instead of hanging off a single service-shell instance
- `OpenCrayAgentRuntimeService` now also resolves wake intent parsing and dispatch through a dedicated wake-command-dispatcher seam, so approval notification actions, scheduled dispatch wakes, interrupted-run resume wakes, and schedule repair wakes no longer live inline in the service entrypoint
- that service-owned wake path now also decodes Android `Intent` transport through a dedicated `RuntimeServiceWakeIntentParser`, so the dispatcher itself only handles parsed commands instead of reading `action`/`extras` inline
- `OpenCrayAgentRuntimeService` now also resolves its local binder exposure through a dedicated binder-endpoint seam, so the binder object plus service-owned chat/skills/settings write-dispatch logic no longer live inline in the service entrypoint
- the wake dispatcher and binder endpoint now consume only the narrow surfaces they still need, namely `RuntimeServiceProjectionCoordinator` plus `RuntimeServiceShellStateAccess`, so those detached-runtime helpers no longer retain the full `RuntimeServiceExecutionCoordinator` just to flush projection snapshots or read keepalive/foreground state
- caller-side runtime-service bind/start/repair/approval intents now also flow through a dedicated `RuntimeServiceIntentFactory` file with an injectable component-provider seam, so the production access gateway no longer embeds the concrete runtime-service class reference while assembling transport intents
- notification-settings reads and writes inside the service-owned settings gateway now terminate at the same persistent notification-settings store already used by runtime notification delivery, instead of bouncing that slice back through `OpenCrayHostRuntime`
- strong-background capability snapshot/actions now also terminate inside the service-owned settings gateway through `AndroidStrongBackgroundSettingsAccess`, while preserving the projected `runtimeServiceConnectionState` field on that settings surface, so that Android-local settings slice no longer depends on `OpenCrayHostRuntime`
- sandbox load/save now also terminate inside the service-owned settings gateway through a narrowed sandbox-settings access boundary plus the existing sandbox payload mappers, which removes that repository-backed settings slice from `OpenCrayHostRuntime`
- network-search load/save and media-speech load/save now also terminate inside the service-owned settings gateway through `LocalNetworkSearchConfigFacade` and `LocalMediaSpeechSettingsFacade`, which removes another pair of facade-backed settings slices from `OpenCrayHostRuntime`
- personalization load/save/reset now also terminate inside the service-owned settings gateway through `LocalPersonalizationFacade`, and app-language persistence now also flows through a service-owned app-language access seam; localized refresh plus chat/skills/settings snapshot fanout now stay inside the service-owned gateway bundle, so binder-connected reads do not keep stale language state and no longer route the language write itself back through `OpenCrayHostRuntime`
- safety load/save now also terminate inside the service-owned settings gateway through `LocalSafetySettingsFacade`, and that save path emits a narrowed chat-snapshot notifier instead of bouncing back through the host-owned settings save path
- LLM config load/save/custom-provider/validate and MCP settings load/master-toggle/per-server-toggle now also terminate inside the service-owned settings gateway through `LocalLlmConfigFacade` and `LocalMcpSettingsFacade`, which removes another facade-backed settings cluster from `OpenCrayHostRuntime`
- service-owned network-search and media-speech saves now also trigger a narrowed settings-overview notifier, so settings overview observers stay behaviorally aligned with the old host-owned save path even though those writes no longer bounce back through `OpenCrayHostRuntime`
- the service-owned skills gateway now resolves skills snapshot/observation, install and batch-install, update check/update, source inspection, instructions, install-source activation, and local skill toggles/delete/refresh through `LocalSkillsFacade` plus a narrowed skills snapshot notifier, which removes another cluster of facade-backed skills flows from `OpenCrayHostRuntime`
- projection-only skills fallback is now strictly local-only and no longer issues `SkillsList` or `SkillsFind`, which keeps tool-executing skills discovery and remote install metadata on the binder-owned pipeline
- the runtime service now promotes itself to foreground while keepalive-required work exists, and service-owned notification flows now cover active-runtime, approval-needed, completion/interruption, and scheduled-dispatch surfaces
- scheduled-dispatch notifications now also have service-backed retry/manual-run, disable, and snooze actions for skipped-busy and dispatch-failed outcomes; those actions are parsed by the runtime-service wake-command pipeline, start the detached target in the foreground, and either enqueue a new manual scheduled trigger through the normal scheduled-task dispatcher, disable the durable schedule with trigger re-sync, or persist a short schedule deferral while preserving the original trigger. The trigger re-sync transaction itself is now guarded by a runtime-root cross-process lock around schedule-spec readback, prior synced-id readback, cancel/sync fanout, and synced-id persistence, so app bootstrap, repair, and detached notification actions do not race each other into stale registered-trigger state.
- completion/interruption notifications now backfill from durable run state when the app is backgrounded, and terminal delivery is deduped across restore/backfill via a persistent notification-delivery store
- this service slice is still intentionally same-process: foreground keepalive, notification surfaces, scheduled wake-up semantics, target-aware UI write routing, target-scoped projection fallback, and target-scoped local loopback transport have landed, but binder-driven control flow as the dominant path and stronger detached ownership semantics are still later slices

Not yet implemented:

- fully detached runtime ownership via Android service with binder-driven control as the primary execution path
- additional prompt checkpoint boundaries beyond the current approval and post-tool-result slices
- generalized checkpoint-aware queue restore in `core` beyond the current app-layer recovery-aware rewrite

## Purpose

This document answers two concrete questions:

1. Why can a run fully restart even when the user did not intentionally "restart the app"?
2. How should OpenCray evolve so tasks can continue after the user leaves the page and later support scheduled/background execution?

This document complements `docs/runtime-checkpoint-and-detached-execution-design.md`. That document defines the recovery model. This document focuses on the current restart boundary, the code-backed investigation, and the phased architecture plan.

For the Android-specific local strong-background product design that sits on top of this plan, including foreground-service survival, scheduled wake-up, and battery-optimization posture, see `docs/android-local-strong-background-runtime-design.md`.

## Executive Summary

The current runtime is not page-bound, but it is still runtime-process-bound.

That distinction matters:

- a normal Flutter page rebuild does not directly recreate the run owner
- a main app-process or host recreation does not necessarily recreate the run owner anymore, because the production runtime now lives in the dedicated `:runtime` process
- a `:runtime` process recreation or an explicit retained-owner reset still recreates or rotates the run owner
- when that happens, the core queue now normalizes in-flight work into an explicit interrupted state instead of blindly replaying it
- approval-boundary restores can already be rewritten at the app layer into the same safe continuation shape when a durable checkpoint proves that recovery is safe: `QUEUED` after approved/general resume, `SUSPENDED` while waiting for approval, or stopped awaiting a new instruction after rejection
- post-tool-result `general_resume` checkpoints can already recover the same run when that boundary is durably committed, but checkpoint coverage is still incomplete outside the current safe slices

So the user-visible "run restarted" behavior is real. It is not only a UI illusion.

At the same time, the UI makes the problem look worse than it is, because expanded run history is still mostly reconstructed from in-memory runtime events plus partial transcript replay. After host recreation, that history becomes sparse even before the rerun begins.

## Code-Backed Findings

### 1. The run owner is process-scoped, not page-scoped

`OpenCrayHostRuntime` no longer exposes an app-process singleton facade entrypoint in production.

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayLocalRuntimeServer.kt`

Important facts:

- production `OpenCrayHostRuntime` is created from the runtime service path, not from a UI-side `fromContext()` singleton
- the Flutter platform bridge and the local runtime server no longer hold a full host runtime just to do workspace/device work; those pure local paths now go through `OpenCrayLocalHostGateway`
- service-backed gateways project reads through dedicated projection gateways while binder-backed ownership is incomplete; the bridge/server no longer route their local-only capabilities through a UI-owned host facade
- `OpenCrayFlutterHostBridge` still only attaches and detaches channels around host-facing gateways; it does not own execution state

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

- approval-boundary checkpoints can rewrite a restored run back into the same `QUEUED` run when the user had already approved and the durable resume payload still exists
- approval-boundary checkpoints can rewrite a restored run back into the same `SUSPENDED` run when the task was waiting for approval
- rejected approval checkpoints restore into a stopped run that waits for the user's next instruction instead of resuming execution
- post-tool-result general prompt checkpoint recovery is implemented for the current safe slices, including live-process restores when a durable checkpoint exists
- live `ProcessRead` / `ProcessWait` tails with reconnect backoff now restore into a suspended reconnect intent and preserve process id/status/recovery-state/retry-after evidence, while non-observational in-flight tool calls still fall back to explicit interruption
- controller-level managed-process reconnect is implemented for reconnectable backends, including the runtime routed-factory path when the default delegate supports reconnect, but the broader detached-runtime/controller split is still not implemented

Today that startup edge is split:

- the first runtime service host creation performs a bootstrap scan that resumes the active session plus any known session with pending work, live managed processes, durable repair evidence, run records, or non-terminal journal-tail evidence, then repairs terminal replay state for sessions with runs
- later `ACTION_RESUME_INTERRUPTED_RUNS` wakes can rescan known sessions from chat state, queue/checkpoint/subagent stores, run records, and non-terminal journal tails, then resume those whose restored runs still project as active or whose durable evidence still requires the conservative interactive repair predicate
- production `OpenCrayHostRuntime` creation is projection-only and no longer resumes the active session from the host-facade initializer
- test-only host construction can still opt into the old init-time resume behavior where assertions depend on it

Conclusion:

- host recreation no longer blindly replays in-flight work at the queue layer
- approval-boundary runs can already recover into the same run identity when recovery is proven safe
- everything outside those safe boundaries still lacks a general checkpoint model, so interruption remains the default fallback except for live managed-process observation reconnect holds that can be preserved without replay

### 4. Expanded run history is no longer purely memory-backed, but replay is still layered

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/RunEventJournalStoreFactory.kt`

Current snapshot composition:

- live runtime events come from in-memory `runtimeEventsBySession`
- restore-time replay now prefers the append-only run journal when it exists
- transcript replay remains a compatibility fallback for older runs or gaps that predate the journal
- durable run record still stores only `lastEvent` and `lastResult`, but that record is no longer the full replay source of truth

Current remaining replay limits:

- journal-backed runs retain far more detail after host recreation than the old transcript-only fallback
- pre-journal or compatibility-fallback runs can still look sparse after restore
- the live in-memory tail can still be richer than the last durably committed journal event if the host dies between event emission and later UI observation

Conclusion:

- host recreation no longer erases most recorded runtime detail for journal-backed runs
- continuity issues now come more from incomplete checkpoint coverage and execution ownership boundaries than from total loss of replayable event history

### 5. Detached execution ownership is only partially implemented

Relevant files:

- `app/src/main/kotlin/com/opencray/app/OpenCrayApplication.kt`
- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt`

Current state:

- app bootstrap no longer eagerly starts `OpenCrayAgentRuntimeService`; it only performs app-level bootstrap plus repair/schedule registration, including one-shot app-start repair and unique periodic repair registration. When the runtime service is later started by an explicit wake or binder-demanding path, that service bootstraps the local loopback server on `onCreate()`
- execution now routes through a dedicated `:runtime` Android `Service` host boundary rather than directly through a UI-owned host facade
- runtime-service lifecycle/projection diagnostics now include expected and observed service process identity, and runtime-service bootstrap now rejects a misplaced main-process or secondary-process shell before it can create runtime ownership
- runtime-process controller lifecycle now has a target-scoped durable controller identity persisted under the runtime storage root; projection snapshots and task lifecycle metadata expose it separately as `durableControllerId` / `_host.durableRuntimeControllerId` while the existing per-instance `runtimeControllerId` remains unchanged for managed-process restore scope
- target-scoped runtime-owner lease heartbeat now records held/released owner evidence under the runtime storage root, projects the latest lease into shell/chat diagnostics, records the latest rejected acquire attempt on the held lease, prevents a different owner from replacing a still-held unexpired lease, prevents non-owner coordinators from overwriting the active owner's target projection, gates shell attach, service start-command handling, sticky restart decisions, plus wake/binder writes on the held target lease, and schedules owner-lease-expiry repair when service attach is denied by a still-held lease
- binder attachment now also obeys that owner-lease gate at bind time: a non-owner shell returns Android null binding, so the main process falls back to the target-scoped projection snapshot with an explicit `null_binding` connection state instead of serving stale runtime-owned state or crashing the service bind path
- execution is still ultimately backed by runtime-process singletons and executors
- `AlarmManager` plus `WorkManager` trigger bridges now exist for scheduled wake-up and repair, and schedule notifications can retry/manual-run, snooze, or disable failed/skipped schedules through the runtime-service wake path, but interactive active runs still execute under that runtime-process owner
- interrupted-run repair scans now preserve typed evidence by session across WorkManager preflight and runtime-service bootstrap/resume repair results, and the service-side bootstrap/resume result is now saved into durable projection diagnostics and used to re-register the next delayed managed-process reconnect repair, so host rebuild and binder-unavailable fallback can tell whether a repair wake came from queue state, a prompt checkpoint, a detached subagent handle, a durable run record, the latest non-terminal journal tail, or managed-process reconnect backoff; finalization checkpoints are treated as terminal evidence and do not independently trigger repair wake
- scheduled spec and scheduled run-record list/get normalization repair now also use the same process-safe durable update primitive, so schedule repair or notification actions do not race by re-saving older repaired schedule snapshots over newer schedule state
- scheduled run-record deletion/pruning now uses the same process-safe durable update primitive as run-record upsert, so deleting a schedule's history does not race by re-saving an older snapshot over unrelated schedule run records
- agent run-record list/normalization repair now also uses that same process-safe durable update primitive, so host rebuild or projection fallback does not race by re-saving an older repaired run-record snapshot over a newer run record
- prompt-checkpoint list/get normalization repair now also uses that same process-safe durable update primitive, so checkpoint resume or approval fallback does not race by re-saving an older repaired checkpoint snapshot over a newer checkpoint
- detached subagent-handle list/get normalization repair now also uses that same process-safe durable update primitive, so subagent repair scans do not race by re-saving an older repaired handle snapshot over a newer delegated-run handle
- runtime notification-delivery dedupe writes now use that same process-safe update primitive, so terminal notification backfill after host rebuild does not race by re-saving an older delivered-fingerprint snapshot over unrelated runs
- mid-loop supplement append/consume now uses that same process-safe update primitive, so host/service rebuild paths do not lose newer supplement input while consuming an older pending set
- transcript fallback append/replace/repair now uses that same process-safe update primitive, so host/service rebuild paths do not lose newer transcript fallback events while journal-backed replay remains preferred

Conclusion:

- tasks are no longer page-bound, but they are still coupled to the lifetime of the runtime process

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

What we can now distinguish better:

- host-facade recreation versus runtime-owner continuity
- binder transport churn versus loopback server churn
- runtime-service shell placement in the dedicated `:runtime` process versus main-process or other secondary-process mismatch
- per-controller-instance churn versus the same target-scoped durable runtime-controller identity
- held versus released target owner lease evidence, including the last runtime owner/controller/service ids and heartbeat/expiry timestamps
- stable managed-process auto-resume eligibility versus reconnect backoff or interrupted terminal managed-process restore
- per-run managed-process reconnect-hold evidence, including process id, reconnect status, recovery state, retry-after, and attempt count when recovery parks a live observation instead of replaying it
- per-run recovery intent and restore reason when a recovery plan or restore diagnostic exists
- per-session interrupted-run repair evidence kind for wake/rescan decisions, including queue-task, prompt-checkpoint, detached-subagent, run-record, and journal-tail-only recovery candidates, plus the latest persisted service-side repair projection when binder fallback reads only the target-scoped projection snapshot

What is now safer:

- detached/runtime-process and projection-only readers no longer rely only on process-local synchronization for the managed-process registry; session-level registry read/modify/write operations now take a directory-scoped JVM lock plus OS file lock, so concurrent owners do not lose durable managed-process snapshots while reconnect or passive projection is in flight

What we still cannot distinguish with confidence:

- the exact OS-level reason an app process died
- every Dart-side observer glitch versus a real host rebuild
- true controller-level live managed-process reattachment versus checkpoint-based recovery fallback

The reason is now narrower: lifecycle diagnostics and managed-process restore scope now distinguish same-controller, same-process-new-controller, and cross-process interruption, runtime-service bootstrap now refuses to create ownership outside the dedicated `:runtime` process, and a target-scoped durable controller identity now survives service/controller recreate for diagnostics and projection fallback. That durable id is intentionally separate from the live controller instance id; it does not make in-memory execution survive runtime-process death or provide a stronger cross-process controller/runtime tier by itself.

## Root Problem Statement

OpenCray is now recovery-aware, but it is still not execution-durable.

Today the system can persist durable journal and checkpoint state and recover some runs back into the same identity after host loss, and it can now also repair the narrow "final answer already emitted" window back into a terminal result instead of replaying the prompt. It still loses in-memory execution ownership whenever the app process or runtime host is recreated.

That leaves two remaining failures:

1. runs outside the current safe checkpoint boundaries, plus any case where terminal evidence is incomplete, still fall back to explicit interruption, reconnect-hold, or later repair/resume instead of seamless continuation
2. UI continuity still depends on layered host, service, and bridge reattachment rather than a truly detached execution controller

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

Current implementation status:

- `processStartId`, `hostInstanceId`, and `runtimeOwnerId` are already emitted through runtime diagnostics and run lifecycle metadata
- `flutterAppInstanceId` and `bridgeInstanceId` are now emitted on client-delivered shell/chat runtime snapshot payloads and shown in the runtime diagnostics page
- `runAttempt` is now stamped on new run/task metadata and advanced by recovery-aware queue restore when a restore plan rewrites the task lifecycle
- `recoveredFromCheckpointId` is now stamped for checkpoint-driven recovery rewrites and projected through run lifecycle diagnostics
- recovery-aware queue rewrites now also append non-runtime recovery marker entries to the durable run journal, carrying recovery action, restore epoch, previous lifecycle state, recovery reason, run attempt, and checkpoint id for later host-rebuild diagnosis

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

### 6. Add scheduled and recurring task triggering

Add two persistent models:

- `ScheduledTaskSpec`
- `ScheduledTaskRunRecord`

Recommended trigger support in first phase:

- immediate detached execution
- one-shot absolute time
- one-shot relative delay
- recurring schedule via `start_at + timezone + rrule`

Use `AlarmManager` for the next concrete fire time and `WorkManager` for durable repair and wake-up fallback.

Recommended split:

- `AlarmManager` owns the next exact wake when the platform allows it
- `WorkManager` owns repair, restart after process death, and coarse wake fallback
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

- enable delayed and future recurrence task orchestration

Work:

- add `ScheduledTaskSpec` persistence
- add `WorkManager` wake-up path
- hand off scheduled triggers into `AgentRuntimeService`
- add background journal events and notification policy
- route accepted scheduled-run Cancel actions through the service-owned interrupt command path

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

Do not treat `WorkManager` alone as the fix. Without run recovery and journaling, it only creates a more durable way to wake the same restore logic.

## Concrete Answer To The Original Question

Why can a run restart when the user did not intentionally restart the app?

Because host/app-process recreation still tears down in-memory execution ownership. After that, restore consults journal and checkpoint state first: safe approval or `general_resume` boundaries can continue the same run, while runs without a safe boundary stay explicitly interrupted instead of silently replaying. The later `ACTION_RESUME_INTERRUPTED_RUNS` wake path can retry that checkpoint-aware repair scan, but it is still not the same as continuous ownership surviving the rebuild.

Why does this still happen even if the UI page was not intentionally closed?

Because the current execution owner lives in the app process, not in the page. A page staying visible does not prove that the host owner was preserved.

What should we do?

- keep the lifecycle boundary observable with explicit IDs and recovery reasons
- keep expanding durable journal and checkpoint coverage
- keep moving execution ownership toward a more detached service owner
- extend scheduler-owned repair beyond the current target-aware periodic `ACTION_RESUME_INTERRUPTED_RUNS` wake-and-rescan path
- layer richer `WorkManager`-based scheduling on top of that runtime foundation

That is the path that fixes the current restart bug and also gives OpenCray a solid base for future keepalive and timed tasks.
