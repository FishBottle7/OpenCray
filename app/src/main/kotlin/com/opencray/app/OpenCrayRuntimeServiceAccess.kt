package com.opencray.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

internal fun interface RuntimeServiceStarter {
  fun start(
    context: Context,
    intent: Intent,
    foreground: Boolean,
  ): Boolean
}

internal data class RuntimeServiceClientBootstrap(
  val target: RuntimeServiceTarget,
  val startRequester: (Context) -> Unit,
  val serviceIntentFactory: (Context) -> Intent,
  val chatWriteWakeRequester: (Context, OpenCrayChatWriteCommand) -> Boolean = { _, _ -> false },
)

internal fun interface RuntimeServiceClientProvider {
  fun create(
    context: Context,
    bootstrap: RuntimeServiceClientBootstrap,
  ): OpenCrayRuntimeServiceClient
}

internal interface RuntimeServiceEndpoint {
  fun baseIntent(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent

  fun scheduledTaskIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent

  fun scheduledRepairIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent

  fun resetRuntimeIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent

  fun resumeInterruptedRunsIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent

  fun chatWriteIntent(
    context: Context,
    command: OpenCrayChatWriteCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent? = null

  fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): PendingIntent

  fun scheduleNotificationActionPendingIntent(
    context: Context,
    action: String,
    scheduleId: String,
    sessionId: String?,
    taskId: String? = null,
    runId: String? = null,
    requestCode: Int,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): PendingIntent = error("Runtime service schedule notification actions are unavailable.")
}

private object AndroidRuntimeServiceStarter : RuntimeServiceStarter {
  override fun start(
    context: Context,
    intent: Intent,
    foreground: Boolean,
  ): Boolean = runCatching {
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
    projectionStore = FileBackedRuntimeServiceProjectionStoreFactory
      .fromContext(context.applicationContext)
      .create(bootstrap.target),
    runtimeTarget = bootstrap.target,
    startRequester = bootstrap.startRequester,
    serviceIntentFactory = bootstrap.serviceIntentFactory,
    wakeChatWriteRequester = { command ->
      bootstrap.chatWriteWakeRequester(context.applicationContext, command)
    },
    commandFallbackTransport = LoopbackHttpRuntimeServiceCommandFallbackTransport(
      requestClient = openCrayLocalRuntimeLoopbackHttpClientForTarget(bootstrap.target),
    ),
  )
}

private val androidRuntimeServiceIntentFactory: RuntimeServiceIntentFactory =
  RuntimeServiceIntentFactory()

private object AndroidRuntimeServiceEndpoint : RuntimeServiceEndpoint {
  override fun baseIntent(
    context: Context,
    target: RuntimeServiceTarget,
  ): Intent = androidRuntimeServiceIntentFactory.baseIntent(context, target = target)

  override fun scheduledTaskIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget,
  ): Intent = androidRuntimeServiceIntentFactory.scheduledTaskIntent(
    context = context,
    command = command,
    target = target,
  )

  override fun scheduledRepairIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget,
  ): Intent = androidRuntimeServiceIntentFactory.scheduledRepairIntent(
    context = context,
    repairReason = repairReason,
    target = target,
  )

  override fun resetRuntimeIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget,
  ): Intent = androidRuntimeServiceIntentFactory.resetRuntimeIntent(
    context = context,
    repairReason = repairReason,
    target = target,
  )

  override fun resumeInterruptedRunsIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget,
  ): Intent = androidRuntimeServiceIntentFactory.resumeInterruptedRunsIntent(
    context = context,
    repairReason = repairReason,
    target = target,
  )

  override fun chatWriteIntent(
    context: Context,
    command: OpenCrayChatWriteCommand,
    target: RuntimeServiceTarget,
  ): Intent? = androidRuntimeServiceIntentFactory.chatWriteIntent(
    context = context,
    command = command,
    target = target,
  )

  override fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
    target: RuntimeServiceTarget,
  ): PendingIntent = PendingIntent.getService(
    context,
    requestCode,
    androidRuntimeServiceIntentFactory.approvalActionIntent(
      context = context,
      action = action,
      sessionId = sessionId,
      taskId = taskId,
      runId = runId,
      target = target,
    ),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  override fun scheduleNotificationActionPendingIntent(
    context: Context,
    action: String,
    scheduleId: String,
    sessionId: String?,
    taskId: String?,
    runId: String?,
    requestCode: Int,
    target: RuntimeServiceTarget,
  ): PendingIntent = PendingIntent.getService(
    context,
    requestCode,
    androidRuntimeServiceIntentFactory.scheduleNotificationActionIntent(
      context = context,
      action = action,
      scheduleId = scheduleId,
      sessionId = sessionId,
      taskId = taskId,
      runId = runId,
      target = target,
    ),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

internal data class RuntimeServiceAccessDependencies(
  val runtimeServiceStarter: RuntimeServiceStarter,
  val runtimeServiceClientProvider: RuntimeServiceClientProvider,
  val runtimeServiceEndpoint: RuntimeServiceEndpoint,
)

internal fun defaultRuntimeServiceAccessDependencies(): RuntimeServiceAccessDependencies =
  RuntimeServiceAccessDependencies(
    runtimeServiceStarter = AndroidRuntimeServiceStarter,
    runtimeServiceClientProvider = AndroidBindingRuntimeServiceClientProvider,
    runtimeServiceEndpoint = AndroidRuntimeServiceEndpoint,
  )

internal fun runtimeServiceClientBootstrap(
  dependencies: RuntimeServiceAccessDependencies,
  target: RuntimeServiceTarget,
): RuntimeServiceClientBootstrap = RuntimeServiceClientBootstrap(
  target = target,
  startRequester = { context ->
    val appContext = context.applicationContext
    dependencies.runtimeServiceStarter.start(
      context = appContext,
      intent = dependencies.runtimeServiceEndpoint.baseIntent(appContext, target = target),
      foreground = false,
    )
  },
  serviceIntentFactory = { context ->
    dependencies.runtimeServiceEndpoint.baseIntent(
      context.applicationContext,
      target = target,
    )
  },
  chatWriteWakeRequester = { context, command ->
    val appContext = context.applicationContext
    val wakeIntent = dependencies.runtimeServiceEndpoint.chatWriteIntent(
      appContext,
      command,
      target,
    )
    if (wakeIntent == null) {
      false
    } else {
      dependencies.runtimeServiceStarter.start(
        context = appContext,
        intent = wakeIntent,
        foreground = true,
      )
    }
  },
)
