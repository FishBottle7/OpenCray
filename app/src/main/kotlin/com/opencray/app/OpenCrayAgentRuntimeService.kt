package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder

internal class OpenCrayAgentRuntimeService : Service() {
  private val binder = LocalBinder()

  override fun onCreate() {
    super.onCreate()
    OpenCrayRuntimeServiceHostRegistry.getOrCreate(
      context = applicationContext,
      serviceLifecycleFactory = { RuntimeServiceLifecycleDescriptor() },
    )
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int = START_NOT_STICKY

  override fun onBind(intent: Intent?): IBinder = binder

  internal inner class LocalBinder : Binder() {
    fun peekRuntimeOwnerLifecycle(): Map<String, Any?> =
      OpenCrayRuntimeServiceHostRegistry.peek()
        ?.runtimeAccess
        ?.lifecycleDescriptor
        ?.snapshotMap()
        ?: emptyMap()

    fun peekRuntimeServiceLifecycle(): Map<String, Any?> =
      OpenCrayRuntimeServiceHostRegistry.peek()
        ?.serviceLifecycle
        ?.snapshotMap()
        ?: emptyMap()
  }

  companion object {
    fun ensureStarted(context: Context) {
      val appContext = context.applicationContext
      runCatching {
        appContext.startService(
          Intent(appContext, OpenCrayAgentRuntimeService::class.java),
        )
      }
    }

    fun ensureServiceHost(context: Context): OpenCrayRuntimeServiceHost {
      val appContext = context.applicationContext
      ensureStarted(appContext)
      return OpenCrayRuntimeServiceHostRegistry.getOrCreate(appContext)
    }
  }
}
