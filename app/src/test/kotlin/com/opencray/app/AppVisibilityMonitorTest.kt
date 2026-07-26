package com.opencray.app

import android.app.Activity
import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityMonitorTest {
  @Test
  fun registerPublishesHeartbeatWhileVisibleAndDoesNotPublishFalseImmediatelyOnStop() {
    val publishedValues = mutableListOf<Boolean>()
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val stateStore = RecordingAppVisibilityStateStore()
    val publisher = PersistingAppVisibilityPublisher(
      stateStore = stateStore,
      broadcaster = AppVisibilityChangeBroadcaster { visible ->
        publishedValues += visible
      },
    )
    val activity = Activity()
    val monitor = AppVisibilityLifecycleMonitor()

    monitor.register(
      application = Application(),
      visibilityPublisher = publisher,
      heartbeatScheduler = scheduler,
      heartbeatIntervalMs = 100L,
      callbacksRegistrar = { _, _ -> },
    )

    monitor.onActivityStarted(activity)
    stateStore.failNextSave()
    scheduler.runNext()
    assertEquals(1, scheduler.pendingTaskCount)
    scheduler.runNext()
    monitor.onActivityStopped(activity)

    assertEquals(listOf(false, true, true), publishedValues)
    assertEquals(0, scheduler.pendingTaskCount)
  }

  private class RecordingAppVisibilityStateStore : AppVisibilityStateStore {
    private var appVisibleUntilEpochMs: Long? = null
    private var shouldFailNextSave: Boolean = false

    fun failNextSave() {
      shouldFailNextSave = true
    }

    override fun loadAppVisible(): Boolean = appVisibleUntilEpochMs != null

    override fun saveAppVisible(appVisible: Boolean) {
      appVisibleUntilEpochMs = if (appVisible) 1L else null
    }

    override fun loadAppVisibleUntilEpochMs(): Long? = appVisibleUntilEpochMs

    override fun saveAppVisibleUntilEpochMs(visibleUntilEpochMs: Long?) {
      if (shouldFailNextSave) {
        shouldFailNextSave = false
        throw IllegalStateException("injected visibility persistence failure")
      }
      appVisibleUntilEpochMs = visibleUntilEpochMs
    }
  }

  private class RecordingRuntimeServiceDelayScheduler : RuntimeServiceDelayScheduler {
    private val actions = ArrayDeque<() -> Unit>()

    val pendingTaskCount: Int
      get() = actions.size

    override fun schedule(
      delayMs: Long,
      action: () -> Unit,
    ): RuntimeServiceDelayedTask {
      actions.addLast(action)
      return RuntimeServiceDelayedTask {
        actions.remove(action)
      }
    }

    fun runNext() {
      actions.removeFirstOrNull()?.invoke()
    }
  }
}
