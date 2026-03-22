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
    latestBinderAccess = binder
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int = START_NOT_STICKY

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    if (latestBinderAccess === binder) {
      latestBinderAccess = null
    }
    super.onDestroy()
  }

  internal inner class LocalBinder : Binder(), OpenCrayRuntimeServiceBinderAccess {
    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
      OpenCrayRuntimeServiceHostRegistry.getOrCreate(applicationContext).toBridgeSnapshot()

    fun peekRuntimeOwnerLifecycle(): Map<String, Any?> =
      loadSnapshot()
        .runtimeAccess
        ?.lifecycleDescriptor
        ?.snapshotMap()

    fun peekRuntimeServiceLifecycle(): Map<String, Any?> =
      loadSnapshot()
        .serviceLifecycle
        ?.snapshotMap()
  }

  companion object {
    @Volatile
    private var latestBinderAccess: OpenCrayRuntimeServiceBinderAccess? = null

    fun ensureStarted(context: Context) {
      val appContext = context.applicationContext
      runCatching {
        appContext.startService(
          Intent(appContext, OpenCrayAgentRuntimeService::class.java),
        )
      }
    }

    fun ensureBridge(context: Context): OpenCrayRuntimeServiceBridge {
      val appContext = context.applicationContext
      ensureStarted(appContext)
      val binderAccess = latestBinderAccess
      return if (binderAccess != null) {
        BinderBackedOpenCrayRuntimeServiceBridge(binderAccess)
      } else {
        InProcessOpenCrayRuntimeServiceBridge {
          OpenCrayRuntimeServiceHostRegistry.getOrCreate(appContext)
        }
      }
    }
  }
}
