package com.opencray.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal object AppVisibilityMonitor : Application.ActivityLifecycleCallbacks {
  private val lock = Any()
  private val listeners = linkedSetOf<(Boolean) -> Unit>()
  private var registered: Boolean = false
  private var startedActivityCount: Int = 0

  fun register(application: Application) {
    synchronized(lock) {
      if (registered) {
        return
      }
      registered = true
    }
    application.registerActivityLifecycleCallbacks(this)
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
    synchronized(lock) {
      val nextCount = (startedActivityCount + delta).coerceAtLeast(0)
      if (nextCount == startedActivityCount) {
        return
      }
      startedActivityCount = nextCount
      nextVisibility = nextCount > 0
      listenersToNotify = listeners.toList()
    }
    listenersToNotify.forEach { listener -> listener(nextVisibility) }
  }
}
