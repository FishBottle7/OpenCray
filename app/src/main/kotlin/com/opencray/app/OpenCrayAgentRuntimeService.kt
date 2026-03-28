package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

internal class OpenCrayAgentRuntimeService : Service() {
  private val binder = LocalBinder()
  private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
    Handler(Looper.getMainLooper())
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
  private var serviceWorkStateObservationDisposer: (() -> Unit)? = null
  private val serviceHostRuntime: OpenCrayHostRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    OpenCrayHostRuntime.createForRuntimeService(
      appContext = applicationContext,
      serviceHost = serviceHost,
      runtimeServiceKeepAliveStateProvider = keepAliveController::currentState,
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveController.observe { listener() }
      },
    )
  }

  override fun onCreate() {
    super.onCreate()
    RuntimeNotificationChannelRegistry.ensureRegistered(applicationContext)
    OpenCrayRuntimeServiceHostRegistry.getOrCreate(
      context = applicationContext,
      serviceLifecycleFactory = { RuntimeServiceLifecycleDescriptor() },
    )
    OpenCrayLocalRuntimeServerRegistry.ensureStarted(applicationContext)
    val serviceWorkStateTracker = serviceHost.serviceWorkStateTracker
    serviceWorkStateObservationDisposer = serviceWorkStateTracker.observe(
      ::onServiceWorkStateChanged,
    )
    runtimeNotificationController.start()
    onServiceWorkStateChanged(serviceWorkStateTracker.currentState())
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    keepAliveController.onStartCommand(startId)
    handleWakeIntent(intent)
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    serviceWorkStateObservationDisposer?.invoke()
    serviceWorkStateObservationDisposer = null
    runtimeNotificationController.dispose()
    runtimeForegroundController.onDestroy()
    keepAliveController.onDestroy()
    super.onDestroy()
  }

  private fun onServiceWorkStateChanged(workState: RuntimeServiceWorkState) {
    keepAliveController.onWorkStateChanged(workState)
    runtimeForegroundController.onWorkStateChanged(workState)
  }

  private fun handleWakeIntent(intent: Intent?) {
    parseRuntimeNotificationCommand(intent)?.let { command ->
      when (command) {
        is RuntimeServiceNotificationCommand.ApproveApproval ->
          serviceHostRuntime.approveChatApproval(command.runId ?: command.taskId.orEmpty())

        is RuntimeServiceNotificationCommand.RejectApproval ->
          serviceHostRuntime.rejectChatApproval(command.runId ?: command.taskId.orEmpty())
      }
      RuntimeNotificationCoordinator.dismissApprovalNotification(
        applicationContext,
        command.taskId,
      )
      serviceHost.serviceWorkStateTracker.refresh()
      return
    }
    parseScheduledTaskWakeCommand(intent)?.let { scheduledTaskWakeCommand ->
      val outcome = serviceHost.scheduledTaskDispatcher().dispatch(scheduledTaskWakeCommand)
      runtimeNotificationController.onScheduledDispatchOutcome(outcome)
      serviceHost.serviceWorkStateTracker.refresh()
      return
    }
    if (intent?.action == ACTION_RESUME_INTERRUPTED_RUNS) {
      serviceHost.resumeInterruptedRuns()
      serviceHost.serviceWorkStateTracker.refresh()
      return
    }
    if (intent?.action == ACTION_REPAIR_SCHEDULES) {
      val repairReason = intent.getStringExtra(EXTRA_REPAIR_REASON)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: ScheduledTaskRepairReasons.WORK_MANAGER
      serviceHost.repairScheduledTasks(repairReason = repairReason)
      serviceHost.serviceWorkStateTracker.refresh()
    }
  }

  internal inner class LocalBinder : Binder(), OpenCrayRuntimeServiceBinderAccess {
    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
      serviceHost.toBridgeSnapshot(
        serviceKeepAliveState = keepAliveController.currentState(),
      )

    override fun loadShellGateway(): OpenCrayShellGateway = serviceHostRuntime

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = serviceHostRuntime

    override fun loadSkillsGateway(): OpenCraySkillsGateway = serviceHostRuntime

    override fun loadSettingsGateway(): OpenCraySettingsGateway = serviceHostRuntime

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

  companion object {
    @Volatile
    private var client: OpenCrayRuntimeServiceClient? = null

    fun ensureStarted(context: Context) {
      val appContext = context.applicationContext
      runCatching {
        appContext.startService(
          Intent(appContext, OpenCrayAgentRuntimeService::class.java),
        )
      }
    }

    fun startScheduledTask(
      context: Context,
      command: ScheduledTaskWakeCommand,
    ) {
      val appContext = context.applicationContext
      runCatching {
        ContextCompat.startForegroundService(
          appContext,
          scheduledTaskServiceIntent(appContext, command),
        )
      }
    }

    fun repairSchedules(
      context: Context,
      repairReason: String,
    ): Boolean {
      val appContext = context.applicationContext
      return runCatching {
        ContextCompat.startForegroundService(
          appContext,
          scheduledTaskRepairServiceIntent(appContext, repairReason),
        )
        true
      }.getOrDefault(false)
    }

    fun resumeInterruptedRuns(
      context: Context,
      repairReason: String,
    ): Boolean {
      val appContext = context.applicationContext
      return runCatching {
        ContextCompat.startForegroundService(
          appContext,
          resumeInterruptedRunsServiceIntent(appContext, repairReason),
        )
        true
      }.getOrDefault(false)
    }

    fun ensureClient(context: Context): OpenCrayRuntimeServiceClient {
      val appContext = context.applicationContext
      return client ?: synchronized(this) {
        client ?: AndroidBindingOpenCrayRuntimeServiceClient(appContext).also { created ->
          client = created
        }
      }
    }
  }
}

internal fun resumeInterruptedRunsServiceIntent(
  context: Context,
  repairReason: String,
): Intent = Intent(context, OpenCrayAgentRuntimeService::class.java)
  .setAction(ACTION_RESUME_INTERRUPTED_RUNS)
  .putExtra(EXTRA_REPAIR_REASON, repairReason)

internal const val ACTION_RESUME_INTERRUPTED_RUNS: String =
  "com.opencray.app.action.RESUME_INTERRUPTED_RUNS"
