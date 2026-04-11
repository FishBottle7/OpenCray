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
  @Volatile
  private var serviceBootstrapInstance: OpenCrayAgentRuntimeServiceBootstrap? = null
  private val serviceBootstrap: OpenCrayAgentRuntimeServiceBootstrap
    get() = serviceBootstrapInstance
      ?: error("Runtime service bootstrap accessed before OpenCrayAgentRuntimeService bootstrap.")

  override fun onCreate() {
    super.onCreate()
    bootstrapOpenCrayRuntimeProcessSupport(applicationContext)
    RuntimeNotificationChannelRegistry.ensureRegistered(applicationContext)
    serviceBootstrapInstance = openCrayAgentRuntimeServiceBootstrap(
      service = this,
      appContext = applicationContext,
      mainHandler = mainHandler,
    )
    serviceBootstrap.transportBootstrap.ensureStarted()
    serviceBootstrap.executionCoordinator.attach()
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    serviceBootstrap.executionCoordinator.onStartCommand(startId)
    serviceBootstrap.wakeCommandDispatcher.dispatch(intent)
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = serviceBootstrap.binderEndpoint

  override fun onDestroy() {
    serviceBootstrapInstance?.executionCoordinator?.dispose()
    serviceBootstrapInstance = null
    super.onDestroy()
  }
}

internal const val ACTION_RESUME_INTERRUPTED_RUNS: String =
  "com.opencray.app.action.RESUME_INTERRUPTED_RUNS"
