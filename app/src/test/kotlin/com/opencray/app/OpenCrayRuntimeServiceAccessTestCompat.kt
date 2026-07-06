package com.opencray.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object OpenCrayRuntimeServiceAccess {
  @Volatile
  private var accessDependencies: RuntimeServiceAccessDependencies =
    defaultRuntimeServiceAccessDependencies()
  @Volatile
  private var compatGateway = DefaultRuntimeServiceAccessGateway(accessDependencies)
  @Volatile
  private var cachedClients: Map<RuntimeServiceTarget, OpenCrayRuntimeServiceClient> = emptyMap()

  internal fun resolveAccessDependencies(): RuntimeServiceAccessDependencies =
    accessDependencies

  fun ensureStarted(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ) {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    dependencies.runtimeServiceStarter.start(
      context = appContext,
      intent = dependencies.runtimeServiceEndpoint.baseIntent(appContext, target = target),
      foreground = false,
    )
  }

  fun startScheduledTask(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean = compatGateway.startScheduledTask(context, command, target)

  fun repairSchedules(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean = compatGateway.repairSchedules(context, repairReason, target)

  fun resetRuntime(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean {
    val appContext = context.applicationContext
    val dependencies = resolveAccessDependencies()
    return dependencies.runtimeServiceStarter.start(
      context = appContext,
      intent = dependencies.runtimeServiceEndpoint.resetRuntimeIntent(
        appContext,
        repairReason,
        target,
      ),
      foreground = true,
    )
  }

  fun resumeInterruptedRuns(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Boolean = compatGateway.resumeInterruptedRuns(context, repairReason, target)

  fun baseIntent(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = resolveAccessDependencies().runtimeServiceEndpoint.baseIntent(
    context.applicationContext,
    target = target,
  )

  internal fun scheduledTaskServiceIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = resolveAccessDependencies().runtimeServiceEndpoint.scheduledTaskIntent(
    context.applicationContext,
    command,
    target,
  )

  internal fun approvalActionPendingIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    requestCode: Int,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): PendingIntent = compatGateway.approvalActionPendingIntent(
    context = context,
    action = action,
    sessionId = sessionId,
    taskId = taskId,
    runId = runId,
    requestCode = requestCode,
    target = target,
  )

  fun ensureClient(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): OpenCrayRuntimeServiceClient = compatGateway.ensureClient(context, target).also { resolved ->
    cachedClients = cachedClients + (target to resolved)
  }

  internal fun setRuntimeServiceStarterForTest(
    starter: RuntimeServiceStarter?,
  ) {
    accessDependencies = accessDependencies.copy(
      runtimeServiceStarter =
        starter ?: defaultRuntimeServiceAccessDependencies().runtimeServiceStarter,
    )
    rebuildGateway()
  }

  internal fun setRuntimeServiceEndpointForTest(
    endpoint: RuntimeServiceEndpoint?,
  ) {
    accessDependencies = accessDependencies.copy(
      runtimeServiceEndpoint =
        endpoint ?: defaultRuntimeServiceAccessDependencies().runtimeServiceEndpoint,
    )
    rebuildGateway()
  }

  internal fun setRuntimeServiceClientProviderForTest(
    provider: RuntimeServiceClientProvider?,
  ) {
    accessDependencies = accessDependencies.copy(
      runtimeServiceClientProvider =
        provider ?: defaultRuntimeServiceAccessDependencies().runtimeServiceClientProvider,
    )
    rebuildGateway()
  }

  internal fun setAccessDependenciesForTest(
    dependencies: RuntimeServiceAccessDependencies?,
  ) {
    accessDependencies = dependencies ?: defaultRuntimeServiceAccessDependencies()
    rebuildGateway()
  }

  internal fun clearForTest() {
    accessDependencies = defaultRuntimeServiceAccessDependencies()
    rebuildGateway()
  }

  internal fun dropCachedClientForTest(
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): OpenCrayRuntimeServiceClient? {
    val previousClient = cachedClients[target]
    rebuildGateway()
    return previousClient
  }

  private fun rebuildGateway() {
    val previousGateway = synchronized(this) {
      compatGateway.also {
        compatGateway = DefaultRuntimeServiceAccessGateway(accessDependencies)
        cachedClients = emptyMap()
      }
    }
    previousGateway.dispose()
  }
}
