package com.opencray.app

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppVisibilityBridgeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedAppVisibilityStateStoreWritesLeaseStateThroughSingleStorageUpdate() {
    val storage = RecordingDurableTextStorage()
    var nowEpochMs = 1_000L
    val stateStore = fileBackedAppVisibilityStateStore(
      storage = storage,
      clock = { nowEpochMs },
    )

    stateStore.saveAppVisibleUntilEpochMs(2_500L)

    val persisted = PersistenceJson.instance.decodeFromString(
      PersistedVisibilityStateRecordCompat.serializer(),
      checkNotNull(storage.currentText),
    )
    assertEquals(1, storage.updateTextCallCount)
    assertEquals(1, storage.writeCount)
    assertTrue(stateStore.loadAppVisible())
    assertEquals(2_500L, stateStore.loadAppVisibleUntilEpochMs())
    assertEquals(true, persisted.appVisible)
    assertEquals(2_500L, persisted.visibleUntilEpochMs)
    assertEquals(1_000L, persisted.updatedAtEpochMs)
  }

  @Test
  fun fileBackedAppVisibilityStateStoreDoesNotRewriteWhenClearedStateIsAlreadyCurrent() {
    val storage = RecordingDurableTextStorage()
    var nowEpochMs = 1_000L
    val stateStore = fileBackedAppVisibilityStateStore(
      storage = storage,
      clock = { nowEpochMs },
    )

    stateStore.saveAppVisible(true)
    stateStore.saveAppVisible(false)
    nowEpochMs = 1_500L
    stateStore.saveAppVisible(false)

    assertEquals(3, storage.updateTextCallCount)
    assertEquals(2, storage.writeCount)
    assertFalse(stateStore.loadAppVisible())
    assertEquals(null, stateStore.loadAppVisibleUntilEpochMs())
  }

  @Test
  fun fileBackedAppVisibilityStateStoreRoundTripsAcrossStoreInstances() {
    val runtimeRoot = temporaryFolder.newFolder("app-visibility-state")
    var writerNowEpochMs = 1_000L
    FileBackedAppVisibilityStateStore.fromRootDirectory(
      runtimeRootDirectory = runtimeRoot,
      clock = { writerNowEpochMs },
    ).saveAppVisibleUntilEpochMs(2_500L)

    var readerNowEpochMs = 1_500L
    val restoredBeforeExpiry = FileBackedAppVisibilityStateStore.fromRootDirectory(
      runtimeRootDirectory = runtimeRoot,
      clock = { readerNowEpochMs },
    )
    assertTrue(restoredBeforeExpiry.loadAppVisible())
    assertEquals(2_500L, restoredBeforeExpiry.loadAppVisibleUntilEpochMs())

    readerNowEpochMs = 2_500L
    val restoredAfterExpiry = FileBackedAppVisibilityStateStore.fromRootDirectory(
      runtimeRootDirectory = runtimeRoot,
      clock = { readerNowEpochMs },
    )
    assertFalse(restoredAfterExpiry.loadAppVisible())
    assertEquals(2_500L, restoredAfterExpiry.loadAppVisibleUntilEpochMs())
  }

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

  private class RecordingDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    var updateTextCallCount: Int = 0
      private set
    var writeCount: Int = 0
      private set

    val currentText: String?
      get() = text

    override fun readText(name: String): String? = text

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
        writeCount += 1
      }
      return updated.result
    }
  }

  @kotlinx.serialization.Serializable
  private data class PersistedVisibilityStateRecordCompat(
    val schemaVersion: Int,
    val appVisible: Boolean,
    val visibleUntilEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
  )
}
