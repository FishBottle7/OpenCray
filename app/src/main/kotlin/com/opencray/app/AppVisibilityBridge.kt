package com.opencray.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import kotlinx.serialization.Serializable

internal object AppVisibilitySignalContract {
  const val ACTION_APP_VISIBILITY_CHANGED: String =
    "com.opencray.app.action.APP_VISIBILITY_CHANGED"
  const val EXTRA_APP_VISIBLE: String = "appVisible"
}

internal const val DEFAULT_APP_VISIBILITY_LEASE_DURATION_MS: Long = 5_000L
private const val APP_VISIBILITY_STATE_FILE_NAME: String = "app-visibility-state.json"

internal interface AppVisibilityStateStore {
  fun loadAppVisible(): Boolean

  fun saveAppVisible(appVisible: Boolean)

  fun loadAppVisibleUntilEpochMs(): Long? = null

  fun saveAppVisibleUntilEpochMs(
    visibleUntilEpochMs: Long?,
  ) {
    saveAppVisible(visibleUntilEpochMs != null)
  }
}

internal class FileBackedAppVisibilityStateStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : AppVisibilityStateStore {
  override fun loadAppVisible(): Boolean {
    val record = loadRecord() ?: return false
    val visibleUntilEpochMs = record.visibleUntilEpochMs
    if (visibleUntilEpochMs != null) {
      return visibleUntilEpochMs > clock()
    }
    return record.appVisible
  }

  override fun saveAppVisible(appVisible: Boolean) {
    saveRecord(
      appVisible = appVisible,
      visibleUntilEpochMs = null,
    )
  }

  override fun loadAppVisibleUntilEpochMs(): Long? = loadRecord()?.visibleUntilEpochMs

  override fun saveAppVisibleUntilEpochMs(
    visibleUntilEpochMs: Long?,
  ) {
    saveRecord(
      appVisible = visibleUntilEpochMs != null,
      visibleUntilEpochMs = visibleUntilEpochMs,
    )
  }

  private fun loadRecord(): PersistedAppVisibilityStateRecord? =
    storage.readText(APP_VISIBILITY_STATE_FILE_NAME)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::decodeRecordOrNull)

  private fun saveRecord(
    appVisible: Boolean,
    visibleUntilEpochMs: Long?,
  ) {
    storage.updateRecord(
      name = APP_VISIBILITY_STATE_FILE_NAME,
      serializer = PersistedAppVisibilityStateRecord.serializer(),
    ) { current ->
      val existing = current ?: PersistedAppVisibilityStateRecord()
      val stateChanged =
        existing.appVisible != appVisible || existing.visibleUntilEpochMs != visibleUntilEpochMs
      val next = existing.copy(
        appVisible = appVisible,
        visibleUntilEpochMs = visibleUntilEpochMs,
        updatedAtEpochMs = if (stateChanged) clock() else existing.updatedAtEpochMs,
      )
      RecordStorageUpdate(
        value = next,
        result = Unit,
        write = stateChanged,
      )
    }
  }

  private fun decodeRecordOrNull(
    encoded: String,
  ): PersistedAppVisibilityStateRecord? = runCatching {
    PersistenceJson.instance.decodeFromString(
      deserializer = PersistedAppVisibilityStateRecord.serializer(),
      string = encoded,
    )
  }.getOrNull()

  companion object {
    fun fromRootDirectory(
      runtimeRootDirectory: File,
      clock: () -> Long = System::currentTimeMillis,
    ): FileBackedAppVisibilityStateStore {
      if (!runtimeRootDirectory.exists()) {
        runtimeRootDirectory.mkdirs()
      }
      return FileBackedAppVisibilityStateStore(
        storage = DirectoryDurableTextStorage(runtimeRootDirectory),
        clock = clock,
      )
    }

    fun fromContext(
      context: Context,
      clock: () -> Long = System::currentTimeMillis,
    ): FileBackedAppVisibilityStateStore = fromRootDirectory(
      runtimeRootDirectory = File(
        context.applicationContext.filesDir,
        FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
      ),
      clock = clock,
    )
  }
}

internal fun fileBackedAppVisibilityStateStore(
  storage: DurableTextStorage,
  clock: () -> Long = System::currentTimeMillis,
): AppVisibilityStateStore = FileBackedAppVisibilityStateStore(
  storage = storage,
  clock = clock,
)

internal fun interface AppVisibilityChangeBroadcaster {
  fun broadcast(appVisible: Boolean)
}

internal fun interface AppVisibilityChangeListenerRegistrar {
  fun register(listener: (Boolean) -> Unit): () -> Unit
}

internal class PersistingAppVisibilityPublisher(
  private val stateStore: AppVisibilityStateStore,
  private val broadcaster: AppVisibilityChangeBroadcaster = AppVisibilityChangeBroadcaster { },
  private val visibilityLeaseDurationMs: Long = DEFAULT_APP_VISIBILITY_LEASE_DURATION_MS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun publish(appVisible: Boolean) {
    stateStore.saveAppVisibleUntilEpochMs(
      if (appVisible) {
        clock() + visibilityLeaseDurationMs.coerceAtLeast(0L)
      } else {
        null
      },
    )
    broadcaster.broadcast(appVisible)
  }
}

internal interface AppVisibilitySignalAccess {
  fun currentVisibility(): Boolean

  fun observe(listener: (Boolean) -> Unit): () -> Unit
}

internal class StoreBackedAppVisibilitySignalAccess(
  private val stateStore: AppVisibilityStateStore,
  private val listenerRegistrar: AppVisibilityChangeListenerRegistrar,
  private val delaySchedulerFactory: () -> RuntimeServiceDelayScheduler = {
    HandlerRuntimeServiceDelayScheduler(Handler(Looper.getMainLooper()))
  },
  private val clock: () -> Long = System::currentTimeMillis,
) : AppVisibilitySignalAccess {
  override fun currentVisibility(): Boolean = resolveCurrentVisibility()

  override fun observe(listener: (Boolean) -> Unit): () -> Unit {
    val taskLock = Any()
    val delayScheduler = delaySchedulerFactory()
    var expiryTask: RuntimeServiceDelayedTask? = null

    fun cancelExpiryTask() {
      synchronized(taskLock) {
        expiryTask?.cancel()
        expiryTask = null
      }
    }

    fun scheduleExpiryTask() {
      cancelExpiryTask()
      val visibleUntilEpochMs = stateStore.loadAppVisibleUntilEpochMs() ?: return
      val delayMs = (visibleUntilEpochMs - clock()).coerceAtLeast(0L)
      val task = delayScheduler.schedule(delayMs) {
        synchronized(taskLock) {
          expiryTask = null
        }
        if (!resolveCurrentVisibility()) {
          listener(false)
        }
      }
      synchronized(taskLock) {
        expiryTask = task
      }
    }

    if (resolveCurrentVisibility()) {
      scheduleExpiryTask()
    }
    val disposer = listenerRegistrar.register { appVisible ->
      listener(appVisible)
      if (appVisible) {
        scheduleExpiryTask()
      } else {
        cancelExpiryTask()
      }
    }
    return {
      cancelExpiryTask()
      disposer.invoke()
    }
  }

  private fun resolveCurrentVisibility(): Boolean {
    val visibleUntilEpochMs = stateStore.loadAppVisibleUntilEpochMs()
    return if (visibleUntilEpochMs != null) {
      visibleUntilEpochMs > clock()
    } else {
      stateStore.loadAppVisible()
    }
  }
}

private class BroadcastIntentAppVisibilityChangeBroadcaster(
  private val appContext: Context,
) : AppVisibilityChangeBroadcaster {
  override fun broadcast(appVisible: Boolean) {
    appContext.sendBroadcast(
      Intent(AppVisibilitySignalContract.ACTION_APP_VISIBILITY_CHANGED)
        .setPackage(appContext.packageName)
        .putExtra(AppVisibilitySignalContract.EXTRA_APP_VISIBLE, appVisible),
    )
  }
}

private class BroadcastReceiverAppVisibilityChangeListenerRegistrar(
  private val appContext: Context,
  private val stateStore: AppVisibilityStateStore,
) : AppVisibilityChangeListenerRegistrar {
  override fun register(listener: (Boolean) -> Unit): () -> Unit {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        val appVisible = runCatching {
          intent?.getBooleanExtra(
            AppVisibilitySignalContract.EXTRA_APP_VISIBLE,
            stateStore.loadAppVisible(),
          ) ?: stateStore.loadAppVisible()
        }.getOrElse {
          stateStore.loadAppVisible()
        }
        listener(appVisible)
      }
    }
    ContextCompat.registerReceiver(
      appContext,
      receiver,
      IntentFilter(AppVisibilitySignalContract.ACTION_APP_VISIBILITY_CHANGED),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return {
      runCatching {
        appContext.unregisterReceiver(receiver)
      }
    }
  }
}

internal fun defaultAppVisibilityPublisher(
  context: Context,
): PersistingAppVisibilityPublisher {
  val appContext = context.applicationContext
  val stateStore = FileBackedAppVisibilityStateStore.fromContext(appContext)
  return PersistingAppVisibilityPublisher(
    stateStore = stateStore,
    broadcaster = BroadcastIntentAppVisibilityChangeBroadcaster(appContext),
  )
}

internal fun defaultAppVisibilitySignalAccess(
  context: Context,
): AppVisibilitySignalAccess {
  val appContext = context.applicationContext
  val stateStore = FileBackedAppVisibilityStateStore.fromContext(appContext)
  return StoreBackedAppVisibilitySignalAccess(
    stateStore = stateStore,
    listenerRegistrar = BroadcastReceiverAppVisibilityChangeListenerRegistrar(
      appContext = appContext,
      stateStore = stateStore,
    ),
  )
}

@Serializable
private data class PersistedAppVisibilityStateRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val appVisible: Boolean = false,
  val visibleUntilEpochMs: Long? = null,
  val updatedAtEpochMs: Long = 0L,
)
