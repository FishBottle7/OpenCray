package com.opencray.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val DEFAULT_APP_VISIBILITY_HEARTBEAT_INTERVAL_MS: Long = 2_000L

internal class AppVisibilityLifecycleMonitor : Application.ActivityLifecycleCallbacks {
  private val lock = Any()
  private val listeners = linkedSetOf<(Boolean) -> Unit>()
  private var registered: Boolean = false
  private var startedActivityCount: Int = 0
  private var visibilityPublisher: PersistingAppVisibilityPublisher? = null
  private var heartbeatScheduler: RuntimeServiceDelayScheduler? = null
  private var heartbeatIntervalMs: Long = DEFAULT_APP_VISIBILITY_HEARTBEAT_INTERVAL_MS
  private var heartbeatTask: RuntimeServiceDelayedTask? = null

  fun register(
    application: Application,
    visibilityPublisher: PersistingAppVisibilityPublisher = defaultAppVisibilityPublisher(application),
    heartbeatScheduler: RuntimeServiceDelayScheduler =
      HandlerRuntimeServiceDelayScheduler(Handler(Looper.getMainLooper())),
    heartbeatIntervalMs: Long = DEFAULT_APP_VISIBILITY_HEARTBEAT_INTERVAL_MS,
    callbacksRegistrar: (Application, Application.ActivityLifecycleCallbacks) -> Unit =
      { app, callbacks -> app.registerActivityLifecycleCallbacks(callbacks) },
  ) {
    synchronized(lock) {
      if (registered) {
        return
      }
      registered = true
      this.visibilityPublisher = visibilityPublisher
      this.heartbeatScheduler = heartbeatScheduler
      this.heartbeatIntervalMs = heartbeatIntervalMs
    }
    if (isAppVisible()) {
      publishVisibilitySafely(visibilityPublisher, true)
      scheduleVisibilityHeartbeat()
    } else {
      publishVisibilitySafely(visibilityPublisher, false)
    }
    callbacksRegistrar(application, this)
  }

  fun isAppVisible(): Boolean = synchronized(lock) { startedActivityCount > 0 }

  fun observe(listener: (Boolean) -> Unit): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

  override fun onActivityStarted(activity: Activity) {
    updateStartedActivityCount(delta = 1)
  }

  override fun onActivityResumed(activity: Activity) = Unit

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivityStopped(activity: Activity) {
    updateStartedActivityCount(delta = -1)
  }

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit

  private fun updateStartedActivityCount(delta: Int) {
    val listenersToNotify: List<(Boolean) -> Unit>
    val nextVisibility: Boolean
    val publisher: PersistingAppVisibilityPublisher?
    val becameVisible: Boolean
    synchronized(lock) {
      val currentVisibility = startedActivityCount > 0
      val nextCount = (startedActivityCount + delta).coerceAtLeast(0)
      if (nextCount == startedActivityCount) {
        return
      }
      startedActivityCount = nextCount
      nextVisibility = nextCount > 0
      becameVisible = !currentVisibility && nextVisibility
      listenersToNotify = listeners.toList()
      publisher = visibilityPublisher
    }
    if (becameVisible) {
      publisher?.let { resolvedPublisher ->
        publishVisibilitySafely(resolvedPublisher, true)
      }
      scheduleVisibilityHeartbeat()
    } else if (!nextVisibility) {
      cancelHeartbeat()
    }
    listenersToNotify.forEach { listener ->
      try {
        listener(nextVisibility)
      } catch (failure: Exception) {
        logVisibilityFailure("listener", failure)
      }
    }
  }

  private fun scheduleVisibilityHeartbeat() {
    cancelHeartbeat()
    val scheduler = synchronized(lock) { heartbeatScheduler } ?: return
    val intervalMs = synchronized(lock) { heartbeatIntervalMs }.coerceAtLeast(1L)
    var scheduledTask: RuntimeServiceDelayedTask? = null
    scheduledTask = scheduler.schedule(intervalMs) {
      synchronized(lock) {
        if (heartbeatTask === scheduledTask) {
          heartbeatTask = null
        }
      }
      val publisher = synchronized(lock) {
        if (startedActivityCount <= 0) {
          return@synchronized null
        }
        visibilityPublisher
      } ?: return@schedule
      publishVisibilitySafely(publisher, true)
      scheduleVisibilityHeartbeat()
    }
    synchronized(lock) {
      if (startedActivityCount <= 0) {
        scheduledTask.cancel()
        return
      }
      heartbeatTask = scheduledTask
    }
  }

  private fun cancelHeartbeat() {
    synchronized(lock) {
      heartbeatTask?.cancel()
      heartbeatTask = null
    }
  }

  private fun publishVisibilitySafely(
    publisher: PersistingAppVisibilityPublisher,
    appVisible: Boolean,
  ) {
    try {
      publisher.publish(appVisible)
    } catch (failure: Exception) {
      logVisibilityFailure("publish", failure)
    }
  }

  private fun logVisibilityFailure(
    operation: String,
    failure: Exception,
  ) {
    runCatching {
      Log.e(
        APP_VISIBILITY_LOG_TAG,
        "appVisibility.$operation failure=${failure::class.java.name}",
      )
    }
  }
}

private const val APP_VISIBILITY_LOG_TAG: String = "OpenCrayVisibility"

private val defaultAppVisibilityMonitor = AppVisibilityLifecycleMonitor()

internal object AppVisibilityMonitor : Application.ActivityLifecycleCallbacks by defaultAppVisibilityMonitor {
  fun register(
    application: Application,
    visibilityPublisher: PersistingAppVisibilityPublisher = defaultAppVisibilityPublisher(application),
    heartbeatScheduler: RuntimeServiceDelayScheduler =
      HandlerRuntimeServiceDelayScheduler(Handler(Looper.getMainLooper())),
    heartbeatIntervalMs: Long = 2_000L,
    callbacksRegistrar: (Application, Application.ActivityLifecycleCallbacks) -> Unit =
      { app, callbacks -> app.registerActivityLifecycleCallbacks(callbacks) },
  ) = defaultAppVisibilityMonitor.register(
    application = application,
    visibilityPublisher = visibilityPublisher,
    heartbeatScheduler = heartbeatScheduler,
    heartbeatIntervalMs = heartbeatIntervalMs,
    callbacksRegistrar = callbacksRegistrar,
  )

  fun isAppVisible(): Boolean = defaultAppVisibilityMonitor.isAppVisible()

  fun observe(listener: (Boolean) -> Unit): () -> Unit =
    defaultAppVisibilityMonitor.observe(listener)
}
