package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class RuntimeServiceStartRequest(
  val action: String? = null,
  val extras: Map<String, Any?> = emptyMap(),
)

internal fun interface RuntimeServiceStarter {
  fun start(
    context: Context,
    request: RuntimeServiceStartRequest,
    foreground: Boolean,
  ): Boolean
}

private object AndroidRuntimeServiceStarter : RuntimeServiceStarter {
  override fun start(
    context: Context,
    request: RuntimeServiceStartRequest,
    foreground: Boolean,
  ): Boolean = runCatching {
    val intent = request.toIntent(context = context, foreground = foreground)
    if (foreground) {
      ContextCompat.startForegroundService(context, intent)
    } else {
      context.startService(intent)
    }
    true
  }.getOrDefault(false)
}

internal class OpenCrayAgentRuntimeService : Service() {
  private val binder = LocalBinder()
  private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
    Handler(Looper.getMainLooper())
  }
  private val commandExecutor: ExecutorService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Executors.newSingleThreadExecutor()
  }
  private val serviceHost: OpenCrayRuntimeServiceHost by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    OpenCrayRuntimeServiceHostRegistry.getOrCreate(applicationContext)
  }
  private val keepAliveController: RuntimeServiceKeepAliveController by lazy(
    LazyThreadSafetyMode.SYNCHRONIZED,
  ) {
    RuntimeServiceKeepAliveController(
      scheduler = HandlerRuntimeServiceDelayScheduler(mainHandler),
      stopRequester = ::stopSelfResult,
    )
  }
  private val runtimeForegroundController: RuntimeForegroundController by lazy(
    LazyThreadSafetyMode.SYNCHRONIZED,
  ) {
    RuntimeForegroundController(
      serviceAdapter = AndroidRuntimeForegroundServiceAdapter(
        service = this,
        notificationFactory = RuntimeActiveNotificationFactory(applicationContext),
      ),
      mainThreadPoster = HandlerMainThreadPoster(mainHandler),
    )
  }
  private val runtimeNotificationController: RuntimeNotificationCoordinator by lazy(
    LazyThreadSafetyMode.SYNCHRONIZED,
  ) {
    val notificationSettingsStore = RuntimeNotificationSettingsStore.fromContext(applicationContext)
    RuntimeNotificationCoordinator(
      appContext = applicationContext,
      localizedContext = serviceHost.dependencies.localizedContext,
      chatSessionStore = serviceHost.dependencies.chatSessionStore,
      hostAccess = serviceHost.runtimeAccess.hostAccess,
      scheduledTaskSpecStore = serviceHost.scheduledTaskSpecStore,
      scheduledTaskRunRecordStore = serviceHost.scheduledTaskRunRecordStore,
      notificationSettingsProvider = notificationSettingsStore::load,
    )
  }
  private val projectionStore: RuntimeServiceProjectionStore by lazy(
    LazyThreadSafetyMode.SYNCHRONIZED,
  ) {
    FileBackedRuntimeServiceProjectionStoreFactory.fromContext(applicationContext).create()
  }
  private var serviceWorkStateObservationDisposer: (() -> Unit)? = null
  private var keepAliveStateObservationDisposer: (() -> Unit)? = null
  private var runtimeOwnerProjectionObservationDisposer: (() -> Unit)? = null
  private val serviceGatewayBundle: OpenCrayRuntimeServiceGatewayBundle by lazy(
    LazyThreadSafetyMode.SYNCHRONIZED,
  ) {
    OpenCrayRuntimeServiceGatewayBundle.createForRuntimeService(
      appContext = applicationContext,
      serviceHost = serviceHost,
      runtimeServiceKeepAliveStateProvider = keepAliveController::currentState,
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveController.observe { listener() }
      },
    )
  }
  private val runtimeOwnerProjectionListener = object : AgentSessionRuntimeListener {
    override fun onTaskStarted(
      sessionId: String,
      task: com.opencray.core.contracts.AgentTask,
    ) {
      persistProjectionSnapshot()
    }

    override fun onTaskFinished(
      sessionId: String,
      task: com.opencray.core.contracts.AgentTask,
      result: com.opencray.core.contracts.ExecutionResult,
    ) {
      persistProjectionSnapshot()
    }
  }

  override fun onCreate() {
    super.onCreate()
    RuntimeNotificationChannelRegistry.ensureRegistered(applicationContext)
    OpenCrayRuntimeServiceHostRegistry.getOrCreate(
      context = applicationContext,
      serviceLifecycleFactory = { RuntimeServiceLifecycleDescriptor() },
    )
    OpenCrayLocalRuntimeServerRegistry.ensureStarted(
      context = applicationContext,
      providers = OpenCrayLocalRuntimeServerProviders(
        localGatewayProvider = { openCrayLocalHostGateway(applicationContext) },
        shellGatewayProvider = { serviceGatewayBundle.shellGateway },
        chatRuntimeGatewayProvider = { serviceGatewayBundle.chatRuntimeGateway },
        skillsGatewayProvider = { serviceGatewayBundle.skillsGateway },
        settingsGatewayProvider = { serviceGatewayBundle.settingsGateway },
      ),
    )
    val serviceWorkStateTracker = serviceHost.serviceWorkStateTracker
    serviceWorkStateObservationDisposer = serviceWorkStateTracker.observe(
      ::onServiceWorkStateChanged,
    )
    keepAliveStateObservationDisposer = keepAliveController.observe(
      ::onKeepAliveStateChanged,
    )
    runtimeOwnerProjectionObservationDisposer = serviceHost.runtimeAccess.hostAccess.observe(
      runtimeOwnerProjectionListener,
    )
    runtimeNotificationController.start()
    onServiceWorkStateChanged(serviceWorkStateTracker.currentState())
    persistProjectionSnapshot()
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    keepAliveController.onStartCommand(startId)
    if (intent?.getBooleanExtra(EXTRA_FOREGROUND_START_REQUESTED, false) == true) {
      runtimeForegroundController.startBootstrapForeground()
    }
    commandExecutor.execute {
      handleWakeIntent(intent)
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    serviceWorkStateObservationDisposer?.invoke()
    serviceWorkStateObservationDisposer = null
    keepAliveStateObservationDisposer?.invoke()
    keepAliveStateObservationDisposer = null
    runtimeOwnerProjectionObservationDisposer?.invoke()
    runtimeOwnerProjectionObservationDisposer = null
    runtimeNotificationController.dispose()
    runtimeForegroundController.onDestroy()
    commandExecutor.shutdownNow()
    val destroyedKeepAliveState = keepAliveController.onDestroy()
    persistProjectionSnapshot(keepAliveState = destroyedKeepAliveState)
    super.onDestroy()
  }

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    keepAliveController.onWorkStateChanged(workState)
    runtimeForegroundController.onWorkStateChanged(workState)
    persistProjectionSnapshot(workState = workState)
  }

  private fun onKeepAliveStateChanged(keepAliveState: RuntimeServiceKeepAliveState) {
    persistProjectionSnapshot(keepAliveState = keepAliveState)
  }

  private fun refreshServiceWorkStateSnapshot() {
    onServiceWorkStateChanged(serviceHost.serviceWorkStateTracker.refresh())
  }

  private fun persistProjectionSnapshot(
    workState: RuntimeServiceWorkState = serviceHost.serviceWorkStateTracker.currentState(),
    keepAliveState: RuntimeServiceKeepAliveState = keepAliveController.currentState(),
  ) {
    projectionStore.saveSnapshot(
      RuntimeServiceProjectionSnapshot(
        runtimeOwnerLifecycle = serviceHost.runtimeAccess.lifecycleDescriptor,
        runtimeOwnerWorkSummary = serviceHost.runtimeAccess.hostAccess.activeWorkSummary(),
        serviceLifecycle = serviceHost.serviceLifecycle,
        serviceWorkState = workState,
        serviceKeepAliveState = keepAliveState,
      ),
    )
  }

  private fun handleWakeIntent(intent: Intent?) {
    parseRuntimeNotificationCommand(intent)?.let { command ->
      when (command) {
        is RuntimeServiceNotificationCommand.ApproveApproval -> {
          serviceHost.approvePendingApproval(command.runId ?: command.taskId.orEmpty())
          serviceGatewayBundle.notifyChatSnapshotsChanged()
        }

        is RuntimeServiceNotificationCommand.RejectApproval -> {
          serviceHost.rejectPendingApproval(command.runId ?: command.taskId.orEmpty())
          serviceGatewayBundle.notifyChatSnapshotsChanged()
        }
      }
      RuntimeNotificationCoordinator.dismissApprovalNotification(
        applicationContext,
        command.taskId,
      )
      refreshServiceWorkStateSnapshot()
      return
    }
    parseScheduledTaskWakeCommand(intent)?.let { scheduledTaskWakeCommand ->
      val outcome = serviceHost.scheduledTaskDispatcher().dispatch(scheduledTaskWakeCommand)
      runtimeNotificationController.onScheduledDispatchOutcome(outcome)
      refreshServiceWorkStateSnapshot()
      return
    }
    if (intent?.action == ACTION_RESUME_INTERRUPTED_RUNS) {
      serviceHost.resumeInterruptedRuns()
      refreshServiceWorkStateSnapshot()
      return
    }
    if (intent?.action == ACTION_REPAIR_SCHEDULES) {
      val repairReason = intent.getStringExtra(EXTRA_REPAIR_REASON)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: ScheduledTaskRepairReasons.WORK_MANAGER
      serviceHost.repairScheduledTasks(repairReason = repairReason)
      refreshServiceWorkStateSnapshot()
    }
  }

  internal inner class LocalBinder : Binder(), OpenCrayRuntimeServiceBinderAccess {
    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
      serviceHost.toBridgeSnapshot(
        serviceKeepAliveState = keepAliveController.currentState(),
      )

    override fun loadShellGateway(): OpenCrayShellGateway = serviceGatewayBundle.shellGateway

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway =
      serviceGatewayBundle.chatRuntimeGateway

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult = this@OpenCrayAgentRuntimeService
      .dispatchChatWriteCommand(command)

    override fun loadSkillsGateway(): OpenCraySkillsGateway = serviceGatewayBundle.skillsGateway

    override fun dispatchSkillsWriteCommand(
      command: OpenCraySkillsWriteCommand,
    ): OpenCraySkillsWriteDispatchResult = this@OpenCrayAgentRuntimeService
      .dispatchSkillsWriteCommand(command)

    override fun loadSettingsGateway(): OpenCraySettingsGateway =
      serviceGatewayBundle.settingsGateway

    override fun dispatchSettingsWriteCommand(
      command: OpenCraySettingsWriteCommand,
    ): OpenCraySettingsWriteDispatchResult = this@OpenCrayAgentRuntimeService
      .dispatchSettingsWriteCommand(command)

    fun peekRuntimeOwnerLifecycle(): Map<String, Any?> =
      loadSnapshot()
        .runtimeAccess
        .lifecycleDescriptor
        .snapshotMap()

    fun peekRuntimeServiceLifecycle(): Map<String, Any?> =
      loadSnapshot()
        .serviceLifecycle
        .snapshotMap()

    fun peekRuntimeServiceWorkState(): Map<String, Any?> =
      loadSnapshot()
        .serviceWorkState
        .snapshotMap()

    fun peekRuntimeServiceKeepAliveState(): Map<String, Any?> =
      loadSnapshot()
        .serviceKeepAliveState
        .snapshotMap()

    fun peekRuntimeForegroundState(): Map<String, Any?> =
      runtimeForegroundController.currentState().snapshotMap()
  }

  private fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = try {
    when (command) {
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.ApplyMemoryDebugAction ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      OpenCrayChatWriteCommand.CreateChatSession ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.CopyChatSession ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.DeleteChatSession ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.SelectChatSession ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.BranchChatSessionFromMessage ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.DeleteChatMessage ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.RecallChatMessage ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.SubmitChatMessage ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.ApproveChatApproval -> {
        serviceHost.approvePendingApproval(command.taskIdOrRunId)
        serviceGatewayBundle.notifyChatSnapshotsChanged()
        OpenCrayChatWriteDispatchResult.Completed
      }

      is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> {
        serviceHost.approvePendingApprovalForSession(command.taskIdOrRunId)
        serviceGatewayBundle.notifyChatSnapshotsChanged()
        OpenCrayChatWriteDispatchResult.Completed
      }

      is OpenCrayChatWriteCommand.RejectChatApproval -> {
        serviceHost.rejectPendingApproval(command.taskIdOrRunId)
        serviceGatewayBundle.notifyChatSnapshotsChanged()
        OpenCrayChatWriteDispatchResult.Completed
      }

      is OpenCrayChatWriteCommand.InterruptChatRun ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)

      is OpenCrayChatWriteCommand.RetryChatRun ->
        serviceGatewayBundle.dispatchChatWriteCommand(command)
    }
  } finally {
    serviceHost.serviceWorkStateTracker.refresh()
    persistProjectionSnapshot()
  }

  private fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult = try {
    serviceGatewayBundle.dispatchSettingsWriteCommand(command)
  } finally {
    serviceHost.serviceWorkStateTracker.refresh()
  }

  private fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult = try {
    serviceGatewayBundle.dispatchSkillsWriteCommand(command)
  } finally {
    serviceHost.serviceWorkStateTracker.refresh()
  }

  companion object {
    @Volatile
    private var client: OpenCrayRuntimeServiceClient? = null
    @Volatile
    private var runtimeServiceStarter: RuntimeServiceStarter = AndroidRuntimeServiceStarter

    fun ensureStarted(context: Context) {
      val appContext = context.applicationContext
      runtimeServiceStarter.start(
        context = appContext,
        request = RuntimeServiceStartRequest(),
        foreground = false,
      )
    }

    fun startScheduledTask(
      context: Context,
      command: ScheduledTaskWakeCommand,
    ) {
      val appContext = context.applicationContext
      runtimeServiceStarter.start(
        context = appContext,
        request = RuntimeServiceStartRequest(
          action = ACTION_RUN_SCHEDULED_TASK,
          extras = buildMap {
            put(EXTRA_SCHEDULE_ID, command.scheduleId)
            put(EXTRA_SCHEDULE_RUN_ID, command.scheduleRunId)
            put(EXTRA_TRIGGERED_AT_EPOCH_MS, command.triggeredAtEpochMs)
            put(EXTRA_TRIGGER_REASON, command.triggerReason)
            command.targetSessionId
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?.let { sessionId ->
                put(EXTRA_TARGET_SESSION_ID, sessionId)
              }
          },
        ),
        foreground = true,
      )
    }

    fun repairSchedules(
      context: Context,
      repairReason: String,
    ): Boolean {
      val appContext = context.applicationContext
      return runtimeServiceStarter.start(
        context = appContext,
        request = RuntimeServiceStartRequest(
          action = ACTION_REPAIR_SCHEDULES,
          extras = mapOf(EXTRA_REPAIR_REASON to repairReason),
        ),
        foreground = true,
      )
    }

    fun resumeInterruptedRuns(
      context: Context,
      repairReason: String,
    ): Boolean {
      val appContext = context.applicationContext
      return runtimeServiceStarter.start(
        context = appContext,
        request = RuntimeServiceStartRequest(
          action = ACTION_RESUME_INTERRUPTED_RUNS,
          extras = mapOf(EXTRA_REPAIR_REASON to repairReason),
        ),
        foreground = true,
      )
    }

    fun ensureClient(context: Context): OpenCrayRuntimeServiceClient {
      val appContext = context.applicationContext
      return client ?: synchronized(this) {
        client ?: AndroidBindingOpenCrayRuntimeServiceClient(appContext).also { created ->
          client = created
        }
      }
    }

    internal fun setRuntimeServiceStarterForTest(
      starter: RuntimeServiceStarter?,
    ) {
      runtimeServiceStarter = starter ?: AndroidRuntimeServiceStarter
    }
  }
}

internal fun resumeInterruptedRunsServiceIntent(
  context: Context,
  repairReason: String,
): Intent = Intent(context, OpenCrayAgentRuntimeService::class.java)
  .setAction(ACTION_RESUME_INTERRUPTED_RUNS)
  .putExtra(EXTRA_REPAIR_REASON, repairReason)

private fun RuntimeServiceStartRequest.toIntent(
  context: Context,
  foreground: Boolean = false,
): Intent = Intent(context, OpenCrayAgentRuntimeService::class.java).apply {
  if (foreground) {
    putExtra(EXTRA_FOREGROUND_START_REQUESTED, true)
  }
  action?.let(::setAction)
  this@toIntent.extras.forEach { entry ->
    val key = entry.key
    val value = entry.value
    when (value) {
      null -> Unit
      is String -> putExtra(key, value)
      is Long -> putExtra(key, value)
      is Int -> putExtra(key, value)
      is Boolean -> putExtra(key, value)
      else -> error("Unsupported runtime service extra type for '$key': ${value::class.java.simpleName}")
    }
  }
}

internal const val ACTION_RESUME_INTERRUPTED_RUNS: String =
  "com.opencray.app.action.RESUME_INTERRUPTED_RUNS"
internal const val EXTRA_FOREGROUND_START_REQUESTED: String = "foregroundStartRequested"
