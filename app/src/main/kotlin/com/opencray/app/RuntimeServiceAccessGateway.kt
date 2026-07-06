package com.opencray.app

import android.app.PendingIntent
import android.content.Context

internal interface RuntimeServiceAccessGateway {
  fun ensureClient(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): OpenCrayRuntimeServiceClient

  fun startScheduledTask(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean

  fun repairSchedules(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean

  fun resumeInterruptedRuns(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean

  fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): PendingIntent

  fun chatWriteActionPendingIntent(
    context: Context,
    command: OpenCrayChatWriteCommand,
    requestCode: Int,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
    terminalNotificationTaskId: String? = null,
  ): PendingIntent = error("Runtime service chat write actions are unavailable.")

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

internal class DefaultRuntimeServiceAccessGateway(
  private val accessDependencies: RuntimeServiceAccessDependencies,
) : RuntimeServiceAccessGateway {
  @Volatile
  private var clients: Map<RuntimeServiceTarget, OpenCrayRuntimeServiceClient> = emptyMap()

  override fun ensureClient(
    context: Context,
    target: RuntimeServiceTarget,
  ): OpenCrayRuntimeServiceClient {
    val appContext = context.applicationContext
    val dependencies = accessDependencies
    val bootstrap = runtimeServiceClientBootstrap(dependencies, target)
    clients[target]?.let { existing -> return existing }
    return synchronized(this) {
      clients[target] ?: dependencies.runtimeServiceClientProvider.create(
        appContext,
        bootstrap,
      ).also { created ->
        clients = clients + (target to created)
      }
    }
  }

  override fun startScheduledTask(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget,
  ): Boolean {
    val appContext = context.applicationContext
    val dependencies = accessDependencies
    return dependencies.runtimeServiceStarter.start(
      context = appContext,
      intent = dependencies.runtimeServiceEndpoint.scheduledTaskIntent(
        appContext,
        command,
        target,
      ),
      foreground = true,
    )
  }

  override fun repairSchedules(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget,
  ): Boolean {
    val appContext = context.applicationContext
    val dependencies = accessDependencies
    return dependencies.runtimeServiceStarter.start(
      context = appContext,
      intent = dependencies.runtimeServiceEndpoint.scheduledRepairIntent(
        appContext,
        repairReason,
        target,
      ),
      foreground = true,
    )
  }

  override fun resumeInterruptedRuns(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget,
  ): Boolean {
    val appContext = context.applicationContext
    val dependencies = accessDependencies
    return dependencies.runtimeServiceStarter.start(
      context = appContext,
      intent = dependencies.runtimeServiceEndpoint.resumeInterruptedRunsIntent(
        appContext,
        repairReason,
        target,
      ),
      foreground = true,
    )
  }

  override fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
    target: RuntimeServiceTarget,
  ): PendingIntent = accessDependencies.runtimeServiceEndpoint.approvalActionPendingIntent(
    context = context.applicationContext,
    action = action,
    sessionId = sessionId,
    taskId = taskId,
    runId = runId,
    requestCode = requestCode,
    target = target,
  )

  override fun chatWriteActionPendingIntent(
    context: Context,
    command: OpenCrayChatWriteCommand,
    requestCode: Int,
    target: RuntimeServiceTarget,
    terminalNotificationTaskId: String?,
  ): PendingIntent = accessDependencies.runtimeServiceEndpoint.chatWriteActionPendingIntent(
    context = context.applicationContext,
    command = command,
    requestCode = requestCode,
    target = target,
    terminalNotificationTaskId = terminalNotificationTaskId,
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
  ): PendingIntent = accessDependencies.runtimeServiceEndpoint.scheduleNotificationActionPendingIntent(
    context = context.applicationContext,
    action = action,
    scheduleId = scheduleId,
    sessionId = sessionId,
    taskId = taskId,
    runId = runId,
    requestCode = requestCode,
    target = target,
  )

  internal fun dispose() {
    dropCachedClients()
  }

  private fun dropCachedClients() {
    val previousClients = synchronized(this) {
      clients.values.toList().also {
        clients = emptyMap()
      }
    }
    previousClients.forEach(OpenCrayRuntimeServiceClient::dispose)
  }
}
