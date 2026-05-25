package com.opencray.app

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal class OpenCrayAgentRuntimeService : Service() {
  private val mainHandler: Handler by lazy(LazyThreadSafetyMode.NONE) {
    Handler(Looper.getMainLooper())
  }
  private val runtimeEnvironment: OpenCrayRuntimeServiceEnvironment by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    openCrayRuntimeServiceEnvironment(applicationContext)
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
      bootstrapDependencies = runtimeEnvironment.runtimeServiceBootstrapDependencies,
    )
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    val controller = shellController
    controller.attach(runtimeTargetForIntent(intent))
    return controller.onStartCommand(intent = intent, startId = startId)
  }

  override fun onBind(intent: Intent?): IBinder {
    val controller = shellController
    controller.attach(runtimeTargetForIntent(intent))
    return controller.onBind(intent)
  }

  override fun onDestroy() {
    shellControllerInstance?.dispose()
    shellControllerInstance = null
    super.onDestroy()
  }
}
