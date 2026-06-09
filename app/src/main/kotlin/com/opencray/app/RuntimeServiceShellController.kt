package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder

internal interface RuntimeServiceShellController {
  fun attach(target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET)

  fun onStartCommand(
    intent: Intent?,
    startId: Int,
  ): Int

  fun onBind(intent: Intent?): IBinder

  fun dispose()
}

internal fun runtimeServiceShellController(
  service: Service,
  appContext: Context,
  mainHandler: Handler,
  bootstrapDependencies: RuntimeServiceBootstrapDependencies,
  serviceBootstrapFactory: (
    Service,
    Context,
    Handler,
    RuntimeServiceTarget,
  ) -> RuntimeServiceShellAttachment =
    { resolvedService, resolvedContext, resolvedHandler, resolvedTarget ->
      openCrayAgentRuntimeServiceBootstrap(
        service = resolvedService,
        appContext = resolvedContext,
        mainHandler = resolvedHandler,
        target = resolvedTarget,
        bootstrapDependencies = bootstrapDependencies,
      )
    },
  runtimeTargetReader: (Intent?) -> RuntimeServiceTarget =
    { intent -> runtimeTargetForIntent(intent) },
  runtimeResetRequested: (Intent?) -> Boolean =
    { intent -> intentRequestsRuntimeReset(intent) },
  bootstrapForegroundRequested: (Intent?) -> Boolean =
    { intent -> intentRequiresBootstrapForeground(intent) },
): RuntimeServiceShellController = DefaultRuntimeServiceShellController(
  service = service,
  appContext = appContext.applicationContext,
  mainHandler = mainHandler,
  serviceBootstrapFactory = serviceBootstrapFactory,
  runtimeTargetReader = runtimeTargetReader,
  runtimeResetRequested = runtimeResetRequested,
  bootstrapForegroundRequested = bootstrapForegroundRequested,
)

private class DefaultRuntimeServiceShellController(
  private val service: Service,
  private val appContext: Context,
  private val mainHandler: Handler,
  private val serviceBootstrapFactory: (
    Service,
    Context,
    Handler,
    RuntimeServiceTarget,
  ) -> RuntimeServiceShellAttachment,
  private val runtimeTargetReader: (Intent?) -> RuntimeServiceTarget,
  private val runtimeResetRequested: (Intent?) -> Boolean,
  private val bootstrapForegroundRequested: (Intent?) -> Boolean,
) : RuntimeServiceShellController {
  private val serviceBootstraps =
    linkedMapOf<RuntimeServiceTarget, RuntimeServiceShellAttachment>()
  private val boundEndpoints =
    linkedMapOf<RuntimeServiceTarget, RuntimeServiceBinderEndpoint>()

  override fun attach(target: RuntimeServiceTarget) {
    if (serviceBootstraps.containsKey(target)) {
      return
    }
    val bootstrap = serviceBootstrapFactory(
      service,
      appContext,
      mainHandler,
      target,
    )
    bootstrap.attach()
    serviceBootstraps[target] = bootstrap
  }

  override fun onStartCommand(
    intent: Intent?,
    startId: Int,
  ): Int {
    val target = runtimeTargetReader(intent)
    var bootstrap = requireBootstrap(target)
    if (runtimeResetRequested(intent)) {
      resetRuntimeShell(target)
      bootstrap = requireBootstrap(target)
    }
    if (bootstrapForegroundRequested(intent)) {
      bootstrap.startBootstrapForeground()
    }
    bootstrap.onStartCommand(intent, startId)
    return runtimeServiceStartResult(
      shellStateAccess = bootstrap,
    )
  }

  override fun onBind(intent: Intent?): IBinder =
    runtimeTargetReader(intent).let { target ->
      requireBootstrap(target)
      boundEndpoints.getOrPut(target) {
        DelegatingRuntimeServiceBinderEndpoint(target)
      }
    }

  override fun dispose() {
    disposeAllShells()
  }

  private fun resetRuntimeShell(target: RuntimeServiceTarget) {
    val bootstrap = serviceBootstraps[target]
    disposeShell(target)
    bootstrap?.resetRuntimeOwner()
    attach(target)
  }

  private fun requireBootstrap(
    target: RuntimeServiceTarget,
  ): RuntimeServiceShellAttachment = serviceBootstraps[target]
    ?: error("Runtime service shell controller accessed before attach($target).")

  private fun disposeShell(target: RuntimeServiceTarget) {
    val bootstrap = serviceBootstraps.remove(target) ?: return
    bootstrap.dispose()
  }

  private fun disposeAllShells() {
    val targets = serviceBootstraps.keys.toList().asReversed()
    targets.forEach(::disposeShell)
    boundEndpoints.clear()
  }

  private inner class DelegatingRuntimeServiceBinderEndpoint(
    private val target: RuntimeServiceTarget,
  ) : android.os.Binder(), RuntimeServiceBinderEndpoint {
    private fun currentEndpoint(): RuntimeServiceBinderEndpoint =
      requireBootstrap(target).binderEndpoint

    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
      currentEndpoint().loadSnapshot()

    override fun loadShellGateway(): OpenCrayShellGateway? =
      currentEndpoint().loadShellGateway()

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
      currentEndpoint().loadChatRuntimeGateway()

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult? =
      currentEndpoint().dispatchChatWriteCommand(command)

    override fun loadSkillsGateway(): OpenCraySkillsGateway? =
      currentEndpoint().loadSkillsGateway()

    override fun dispatchSkillsWriteCommand(
      command: OpenCraySkillsWriteCommand,
    ): OpenCraySkillsWriteDispatchResult? =
      currentEndpoint().dispatchSkillsWriteCommand(command)

    override fun loadSettingsGateway(): OpenCraySettingsGateway? =
      currentEndpoint().loadSettingsGateway()

    override fun dispatchSettingsWriteCommand(
      command: OpenCraySettingsWriteCommand,
    ): OpenCraySettingsWriteDispatchResult? =
      currentEndpoint().dispatchSettingsWriteCommand(command)
  }
}

internal fun runtimeServiceStartResult(
  shellStateAccess: RuntimeServiceShellStateAccess,
): Int {
  if (!shellStateAccess.ownsRuntimeServiceStartResult()) {
    return Service.START_NOT_STICKY
  }
  val keepAliveState = shellStateAccess.currentKeepAliveState()
  val foregroundState = shellStateAccess.currentForegroundState()
  val shouldRestart = foregroundState.notificationVisible ||
    keepAliveState.phase == RuntimeServiceKeepAliveState.PHASE_ACTIVE_WORK ||
    keepAliveState.phase == RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE
  return if (shouldRestart) {
    Service.START_STICKY
  } else {
    Service.START_NOT_STICKY
  }
}

internal fun intentRequestsRuntimeReset(
  intent: Intent?,
  actionReader: (Intent?) -> String? = { candidate ->
    runCatching { candidate?.action }.getOrNull()
  },
  forceRuntimeResetReader: (Intent?) -> Boolean = { candidate ->
    runCatching {
      candidate?.getBooleanExtra(EXTRA_FORCE_RUNTIME_RESET, false) == true
    }.getOrDefault(false)
  },
): Boolean = DefaultRuntimeServiceIntentDescriptorParser(
  notificationCommandParser = { null },
  scheduledTaskWakeCommandParser = { null },
  actionReader = actionReader,
  repairReasonReader = { null },
  forceRuntimeResetReader = forceRuntimeResetReader,
).parse(intent).requestsRuntimeReset

internal fun intentRequiresBootstrapForeground(
  intent: Intent?,
  actionReader: (Intent?) -> String? = { candidate ->
    runCatching { candidate?.action }.getOrNull()
  },
): Boolean = DefaultRuntimeServiceIntentDescriptorParser(
  notificationCommandParser = { null },
  scheduledTaskWakeCommandParser = { null },
  actionReader = actionReader,
  repairReasonReader = { null },
).parse(intent).requiresBootstrapForeground

internal fun runtimeTargetForIntent(
  intent: Intent?,
): RuntimeServiceTarget = DefaultRuntimeServiceIntentDescriptorParser()
  .parse(intent)
  .runtimeTarget
