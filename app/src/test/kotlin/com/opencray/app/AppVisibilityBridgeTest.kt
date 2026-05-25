package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityBridgeTest {
  @Test
  fun persistingAppVisibilityPublisherWritesStoreAndBroadcastsVisibility() {
    val stateStore = RecordingAppVisibilityStateStore(initialVisible = false)
    val broadcastValues = mutableListOf<Boolean>()
    val publisher = PersistingAppVisibilityPublisher(
      stateStore = stateStore,
      broadcaster = AppVisibilityChangeBroadcaster { visible ->
        broadcastValues += visible
      },
    )

    publisher.publish(true)
    publisher.publish(false)

    assertEquals(listOf(true, false), stateStore.savedValues)
    assertEquals(listOf(true, false), broadcastValues)
    assertEquals(false, stateStore.loadAppVisible())
  }

  @Test
  fun storeBackedAppVisibilitySignalAccessReadsCurrentStateAndForwardsChanges() {
    val stateStore = RecordingAppVisibilityStateStore(initialVisible = true)
    val registrar = RecordingAppVisibilityChangeListenerRegistrar()
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val signalAccess = StoreBackedAppVisibilitySignalAccess(
      stateStore = stateStore,
      listenerRegistrar = registrar,
      delaySchedulerFactory = { scheduler },
    )
    val observedValues = mutableListOf<Boolean>()

    val disposer = signalAccess.observe { visible ->
      observedValues += visible
    }
    registrar.dispatch(false)
    registrar.dispatch(true)
    disposer.invoke()
    registrar.dispatch(false)

    assertEquals(true, signalAccess.currentVisibility())
    assertEquals(1, registrar.registerCallCount)
    assertEquals(listOf(false, true), observedValues)
    assertEquals(1, registrar.disposeCallCount)
  }

  @Test
  fun storeBackedAppVisibilitySignalAccessExpiresHeartbeatLeaseWithoutFalseBroadcast() {
    var nowEpochMs = 1_000L
    val stateStore = LeaseAwareRecordingAppVisibilityStateStore(
      clock = { nowEpochMs },
      visibleUntilEpochMs = 1_500L,
    )
    val registrar = RecordingAppVisibilityChangeListenerRegistrar()
    val scheduler = RecordingRuntimeServiceDelayScheduler()
    val signalAccess = StoreBackedAppVisibilitySignalAccess(
      stateStore = stateStore,
      listenerRegistrar = registrar,
      delaySchedulerFactory = { scheduler },
      clock = { nowEpochMs },
    )
    val observedValues = mutableListOf<Boolean>()

    val disposer = signalAccess.observe { visible ->
      observedValues += visible
    }
    assertTrue(signalAccess.currentVisibility())

    nowEpochMs = 1_500L
    scheduler.runNext()

    assertFalse(signalAccess.currentVisibility())
    assertEquals(listOf(false), observedValues)

    disposer.invoke()
  }

  private class RecordingAppVisibilityStateStore(
    initialVisible: Boolean,
  ) : AppVisibilityStateStore {
    private var appVisible: Boolean = initialVisible
    val savedValues = mutableListOf<Boolean>()

    override fun loadAppVisible(): Boolean = appVisible

    override fun saveAppVisible(appVisible: Boolean) {
      this.appVisible = appVisible
      savedValues += appVisible
    }
  }

  private class RecordingAppVisibilityChangeListenerRegistrar :
    AppVisibilityChangeListenerRegistrar {
    private var listener: ((Boolean) -> Unit)? = null
    var registerCallCount: Int = 0
      private set
    var disposeCallCount: Int = 0
      private set

    override fun register(listener: (Boolean) -> Unit): () -> Unit {
      registerCallCount += 1
      this.listener = listener
      return {
        disposeCallCount += 1
        if (this.listener === listener) {
          this.listener = null
        }
      }
    }

    fun dispatch(appVisible: Boolean) {
      listener?.invoke(appVisible)
    }
  }

  private class LeaseAwareRecordingAppVisibilityStateStore(
    private val clock: () -> Long,
    visibleUntilEpochMs: Long?,
  ) : AppVisibilityStateStore {
    private var visibleUntilEpochMs: Long? = visibleUntilEpochMs

    override fun loadAppVisible(): Boolean =
      visibleUntilEpochMs?.let { visibleUntil -> visibleUntil > clock() } ?: false

    override fun saveAppVisible(appVisible: Boolean) {
      visibleUntilEpochMs = if (appVisible) {
        clock() + DEFAULT_APP_VISIBILITY_LEASE_DURATION_MS
      } else {
        null
      }
    }

    override fun loadAppVisibleUntilEpochMs(): Long? = visibleUntilEpochMs

    override fun saveAppVisibleUntilEpochMs(visibleUntilEpochMs: Long?) {
      this.visibleUntilEpochMs = visibleUntilEpochMs
    }
  }

  private class RecordingRuntimeServiceDelayScheduler : RuntimeServiceDelayScheduler {
    private val actions = ArrayDeque<() -> Unit>()

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
