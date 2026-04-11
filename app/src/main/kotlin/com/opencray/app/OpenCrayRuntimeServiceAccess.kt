package com.opencray.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

internal data class RuntimeServiceStartRequest(
  val action: String? = null,
  val extras: Map<String, Any?> = emptyMap(),
)

internal fun interface RuntimeServiceStarter {
  fun start(
    context: Context,
    request: RuntimeServiceStartRequest,
    endpoint: RuntimeServiceEndpoint,
    foreground: Boolean,
  ): Boolean
}

internal data class RuntimeServiceClientBootstrap(
  val startRequester: (Context) -> Unit,
  val serviceIntentFactory: (Context) -> Intent,
)

internal fun interface RuntimeServiceClientProvider {
  fun create(
    context: Context,
    bootstrap: RuntimeServiceClientBootstrap,
  ): OpenCrayRuntimeServiceClient
}

internal interface RuntimeServiceEndpoint {
  fun baseIntent(context: Context): Intent

  fun startRequestIntent(
    context: Context,
    request: RuntimeServiceStartRequest,
  ): Intent

  fun scheduledTaskIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
  ): Intent

  fun scheduledRepairIntent(
    context: Context,
    repairReason: String,
  ): Intent

  fun resumeInterruptedRunsIntent(
    context: Context,
    repairReason: String,
  ): Intent

  fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
  ): PendingIntent
}

private object AndroidRuntimeServiceStarter : RuntimeServiceStarter {
  override fun start(
    context: Context,
    request: RuntimeServiceStartRequest,
    endpoint: RuntimeServiceEndpoint,
    foreground: Boolean,
  ): Boolean = runCatching {
    val intent = endpoint.startRequestIntent(context, request)
    if (foreground) {
      ContextCompat.startForegroundService(context, intent)
    } else {
      context.startService(intent)
    }
    true
  }.getOrDefault(false)
}

private object AndroidBindingRuntimeServiceClientProvider : RuntimeServiceClientProvider {
  override fun create(
    context: Context,
    bootstrap: RuntimeServiceClientBootstrap,
  ): OpenCrayRuntimeServiceClient = AndroidBindingOpenCrayRuntimeServiceClient(
    appContext = context.applicationContext,
    startRequester = bootstrap.startRequester,
    serviceIntentFactory = bootstrap.serviceIntentFactory,
    fallbackGatewayBundle = loopbackRuntimeServiceReadFallbackGatewayBundle(
      mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
    ),
    commandFallbackTransport = LoopbackHttpRuntimeServiceCommandFallbackTransport(),
  )
}

private object AndroidRuntimeServiceIntentFactory {
  fun baseIntent(context: Context): Intent =
    Intent(context, OpenCrayAgentRuntimeService::class.java)

  fun startRequestIntent(
    context: Context,
    request: RuntimeServiceStartRequest,
  ): Intent = baseIntent(context).apply {
    request.action?.let(::setAction)
    request.extras.forEach { (key, value) ->
      putRuntimeServiceExtra(key, value)
    }
  }

  fun scheduledTaskIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
  ): Intent = baseIntent(context)
    .setAction(ACTION_RUN_SCHEDULED_TASK)
    .putExtra(EXTRA_SCHEDULE_ID, command.scheduleId)
    .putExtra(EXTRA_SCHEDULE_RUN_ID, command.scheduleRunId)
    .putExtra(EXTRA_TRIGGERED_AT_EPOCH_MS, command.triggeredAtEpochMs)
    .putExtra(EXTRA_TRIGGER_REASON, command.triggerReason)
    .apply {
      command.targetSessionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { sessionId ->
          putExtra(EXTRA_TARGET_SESSION_ID, sessionId)
        }
    }

  fun scheduledRepairIntent(
    context: Context,
    repairReason: String,
  ): Intent = baseIntent(context)
    .setAction(ACTION_REPAIR_SCHEDULES)
    .putExtra(EXTRA_REPAIR_REASON, repairReason)

  fun resumeInterruptedRunsIntent(
    context: Context,
    repairReason: String,
  ): Intent = baseIntent(context)
    .setAction(ACTION_RESUME_INTERRUPTED_RUNS)
    .putExtra(EXTRA_REPAIR_REASON, repairReason)

  fun approvalActionIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
  ): Intent = baseIntent(context).apply {
    setAction(action)
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID, sessionId)
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_TASK_ID, taskId)
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_RUN_ID, runId)
  }
}

private object AndroidRuntimeServiceEndpoint : RuntimeServiceEndpoint {
  override fun baseIntent(context: Context): Intent =
    AndroidRuntimeServiceIntentFactory.baseIntent(context)

  override fun startRequestIntent(
    context: Context,
    request: RuntimeServiceStartRequest,
  ): Intent = AndroidRuntimeServiceIntentFactory.startRequestIntent(context, request)

  override fun scheduledTaskIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
  ): Intent = AndroidRuntimeServiceIntentFactory.scheduledTaskIntent(context, command)

  override fun scheduledRepairIntent(
    context: Context,
    repairReason: String,
  ): Intent = AndroidRuntimeServiceIntentFactory.scheduledRepairIntent(context, repairReason)

  override fun resumeInterruptedRunsIntent(
    context: Context,
    repairReason: String,
  ): Intent = AndroidRuntimeServiceIntentFactory.resumeInterruptedRunsIntent(context, repairReason)

  override fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
  ): PendingIntent = PendingIntent.getService(
    context,
    requestCode,
    AndroidRuntimeServiceIntentFactory.approvalActionIntent(
      context = context,
      action = action,
      sessionId = sessionId,
      taskId = taskId,
      runId = runId,
    ),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

internal data class RuntimeServiceAccessDependencies(
  val runtimeServiceStarter: RuntimeServiceStarter,
  val runtimeServiceClientProvider: RuntimeServiceClientProvider,
  val runtimeServiceEndpoint: RuntimeServiceEndpoint,
)

private fun defaultRuntimeServiceAccessDependencies(): RuntimeServiceAccessDependencies =
  RuntimeServiceAccessDependencies(
    runtimeServiceStarter = AndroidRuntimeServiceStarter,
    runtimeServiceClientProvider = AndroidBindingRuntimeServiceClientProvider,
    runtimeServiceEndpoint = AndroidRuntimeServiceEndpoint,
  )

private fun runtimeServiceClientBootstrap(
  dependencies: RuntimeServiceAccessDependencies,
): RuntimeServiceClientBootstrap = RuntimeServiceClientBootstrap(
  startRequester = { context ->
    dependencies.runtimeServiceStarter.start(
      context = context.applicationContext,
      request = RuntimeServiceStartRequest(),
      endpoint = dependencies.runtimeServiceEndpoint,
      foreground = false,
    )
  },
  serviceIntentFactory = { context ->
    dependencies.runtimeServiceEndpoint.baseIntent(context.applicationContext)
  },
)

internal object OpenCrayRuntimeServiceAccess {
  @Volatile
  private var client: OpenCrayRuntimeServiceClient? = null
  @Volatile
  private var accessDependencies: RuntimeServiceAccessDependencies =
    defaultRuntimeServiceAccessDependencies()

  internal fun resolveAccessDependencies(): RuntimeServiceAccessDependencies = accessDependencies

  fun ensureStarted(context: Context) {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    dependencies.runtimeServiceStarter.start(
      context = appContext,
      request = RuntimeServiceStartRequest(),
      endpoint = dependencies.runtimeServiceEndpoint,
      foreground = false,
    )
  }

  fun startScheduledTask(
    context: Context,
    command: ScheduledTaskWakeCommand,
  ) {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    dependencies.runtimeServiceStarter.start(
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
      endpoint = dependencies.runtimeServiceEndpoint,
      foreground = true,
    )
  }

  fun repairSchedules(
    context: Context,
    repairReason: String,
  ): Boolean {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    return dependencies.runtimeServiceStarter.start(
      context = appContext,
      request = RuntimeServiceStartRequest(
        action = ACTION_REPAIR_SCHEDULES,
        extras = mapOf(EXTRA_REPAIR_REASON to repairReason),
      ),
      endpoint = dependencies.runtimeServiceEndpoint,
      foreground = true,
    )
  }

  fun resumeInterruptedRuns(
    context: Context,
    repairReason: String,
  ): Boolean {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    return dependencies.runtimeServiceStarter.start(
      context = appContext,
      request = RuntimeServiceStartRequest(
        action = ACTION_RESUME_INTERRUPTED_RUNS,
        extras = mapOf(EXTRA_REPAIR_REASON to repairReason),
      ),
      endpoint = dependencies.runtimeServiceEndpoint,
      foreground = true,
    )
  }

  fun baseIntent(context: Context): Intent =
    resolveAccessDependencies().runtimeServiceEndpoint.baseIntent(context.applicationContext)

  internal fun scheduledTaskServiceIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
  ): Intent = resolveAccessDependencies().runtimeServiceEndpoint.scheduledTaskIntent(
    context.applicationContext,
    command,
  )

  internal fun scheduledTaskRepairServiceIntent(
    context: Context,
    repairReason: String,
  ): Intent = resolveAccessDependencies().runtimeServiceEndpoint.scheduledRepairIntent(
    context.applicationContext,
    repairReason,
  )

  internal fun resumeInterruptedRunsServiceIntent(
    context: Context,
    repairReason: String,
  ): Intent = resolveAccessDependencies().runtimeServiceEndpoint.resumeInterruptedRunsIntent(
    context.applicationContext,
    repairReason,
  )

  internal fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
  ): PendingIntent = resolveAccessDependencies().runtimeServiceEndpoint.approvalActionPendingIntent(
    context = context.applicationContext,
    action = action,
    sessionId = sessionId,
    taskId = taskId,
    runId = runId,
    requestCode = requestCode,
  )

  fun ensureClient(context: Context): OpenCrayRuntimeServiceClient {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    val bootstrap = runtimeServiceClientBootstrap(dependencies)
    return client ?: synchronized(this) {
      client ?: dependencies.runtimeServiceClientProvider.create(
        appContext,
        bootstrap,
      ).also { created ->
        client = created
      }
    }
  }

  internal fun setRuntimeServiceStarterForTest(
    starter: RuntimeServiceStarter?,
  ) {
    accessDependencies = accessDependencies.copy(
      runtimeServiceStarter =
        starter ?: defaultRuntimeServiceAccessDependencies().runtimeServiceStarter,
    )
  }

  internal fun setRuntimeServiceEndpointForTest(
    endpoint: RuntimeServiceEndpoint?,
  ) {
    accessDependencies = accessDependencies.copy(
      runtimeServiceEndpoint =
        endpoint ?: defaultRuntimeServiceAccessDependencies().runtimeServiceEndpoint,
    )
  }

  internal fun setRuntimeServiceClientProviderForTest(
    provider: RuntimeServiceClientProvider?,
  ) {
    accessDependencies = accessDependencies.copy(
      runtimeServiceClientProvider =
        provider ?: defaultRuntimeServiceAccessDependencies().runtimeServiceClientProvider,
    )
    client = null
  }

  internal fun setAccessDependenciesForTest(
    dependencies: RuntimeServiceAccessDependencies?,
  ) {
    accessDependencies = dependencies ?: defaultRuntimeServiceAccessDependencies()
    client = null
  }

  internal fun clearForTest() {
    accessDependencies = defaultRuntimeServiceAccessDependencies()
    client = null
  }
}

private fun Intent.putRuntimeServiceExtra(
  key: String,
  value: Any?,
) {
  when (value) {
    null -> Unit
    is String -> putExtra(key, value)
    is Long -> putExtra(key, value)
    is Int -> putExtra(key, value)
    is Boolean -> putExtra(key, value)
    else -> error("Unsupported runtime service extra type for '$key': ${value::class.java.simpleName}")
  }
}
