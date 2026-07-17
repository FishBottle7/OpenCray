package com.opencray.app

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal class OpenCrayAgentRuntimeService : TargetedOpenCrayAgentRuntimeService(
  serviceTarget = RuntimeServiceTarget.INTERACTIVE,
)

internal class OpenCrayDetachedRuntimeService : TargetedOpenCrayAgentRuntimeService(
  serviceTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
)

internal abstract class TargetedOpenCrayAgentRuntimeService(
  internal val serviceTarget: RuntimeServiceTarget,
) : Service() {
  private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
    Handler(Looper.getMainLooper())
  }
  @Volatile
  private var shellControllerInstance: RuntimeServiceShellController? = null
  private val shellController: RuntimeServiceShellController
    get() = shellControllerInstance
      ?: error("Runtime service shell controller accessed before OpenCrayAgentRuntimeService bootstrap.")

  override fun onCreate() {
    super.onCreate()
    shellControllerInstance = runtimeServiceShellController(
      service = this,
      appContext = applicationContext,
      mainHandler = mainHandler,
      runtimeTargetReader = { intent ->
        runtimeServiceTargetForComponentIntent(intent, serviceTarget) ?: serviceTarget
      },
      ownerLeaseRetryScheduler = { target ->
        scheduleRuntimeOwnerLeaseExpiryRepair(applicationContext, target)
      },
    )
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (!acceptsRuntimeIntent(intent)) {
      return rejectedRuntimeServiceStartResult(startId, ::stopSelf)
    }
    val controller = shellController
    if (!controller.attachForStart(intent, serviceTarget)) {
      return rejectedRuntimeServiceStartResult(startId, ::stopSelf)
    }
    return controller.onStartCommand(intent = intent, startId = startId)
  }

  override fun onBind(intent: Intent?): IBinder? = if (acceptsRuntimeIntent(intent)) {
    shellController.onBind(intent)
  } else {
    null
  }

  override fun onDestroy() {
    shellControllerInstance?.dispose()
    shellControllerInstance = null
    super.onDestroy()
  }

  internal fun acceptsRuntimeIntent(intent: Intent?): Boolean =
    runtimeServiceTargetForComponentIntent(intent, serviceTarget) == serviceTarget
}

internal fun rejectedRuntimeServiceStartResult(
  startId: Int,
  stopSelf: (Int) -> Unit,
): Int {
  stopSelf(startId)
  return Service.START_NOT_STICKY
}

internal fun runtimeServiceTargetForComponentIntent(
  intent: Intent?,
  componentTarget: RuntimeServiceTarget,
): RuntimeServiceTarget? {
  if (intent == null) {
    return componentTarget
  }
  val wireTarget = runCatching {
    intent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET)
  }.getOrNull()?.trim()
  if (wireTarget.isNullOrEmpty()) {
    return componentTarget
  }
  return RuntimeServiceTarget.fromWireValue(wireTarget)
}
