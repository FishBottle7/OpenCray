package com.opencray.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

internal object AppVisibilitySignalContract {
  const val ACTION_APP_VISIBILITY_CHANGED: String =
    "com.opencray.app.action.APP_VISIBILITY_CHANGED"
  const val EXTRA_APP_VISIBLE: String = "appVisible"
}

private const val DEFAULT_APP_VISIBILITY_PREFERENCES: String = "opencray.app-visibility"
private const val KEY_APP_VISIBLE: String = "app_visible"
private const val KEY_APP_VISIBLE_UNTIL_EPOCH_MS: String = "app_visible_until_epoch_ms"
internal const val DEFAULT_APP_VISIBILITY_LEASE_DURATION_MS: Long = 5_000L

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

internal class SharedPreferencesAppVisibilityStateStore(
  private val sharedPreferences: SharedPreferences,
) : AppVisibilityStateStore {
  override fun loadAppVisible(): Boolean {
    val visibleUntilEpochMs = loadAppVisibleUntilEpochMs()
    if (visibleUntilEpochMs != null) {
      return visibleUntilEpochMs > System.currentTimeMillis()
    }
    return sharedPreferences.getBoolean(KEY_APP_VISIBLE, false)
  }

  override fun saveAppVisible(appVisible: Boolean) {
    sharedPreferences.edit()
      .putBoolean(KEY_APP_VISIBLE, appVisible)
      .apply {
        if (!appVisible) {
          remove(KEY_APP_VISIBLE_UNTIL_EPOCH_MS)
        }
      }
      .commit()
  }

  override fun loadAppVisibleUntilEpochMs(): Long? =
    if (sharedPreferences.contains(KEY_APP_VISIBLE_UNTIL_EPOCH_MS)) {
      sharedPreferences.getLong(KEY_APP_VISIBLE_UNTIL_EPOCH_MS, 0L)
    } else {
      null
    }

  override fun saveAppVisibleUntilEpochMs(
    visibleUntilEpochMs: Long?,
  ) {
    sharedPreferences.edit()
      .putBoolean(KEY_APP_VISIBLE, visibleUntilEpochMs != null)
      .apply {
        if (visibleUntilEpochMs == null) {
          remove(KEY_APP_VISIBLE_UNTIL_EPOCH_MS)
        } else {
          putLong(KEY_APP_VISIBLE_UNTIL_EPOCH_MS, visibleUntilEpochMs)
        }
      }
      .commit()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_APP_VISIBILITY_PREFERENCES,
    ): SharedPreferencesAppVisibilityStateStore = SharedPreferencesAppVisibilityStateStore(
      sharedPreferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
      ),
    )
  }
}

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
  val stateStore = SharedPreferencesAppVisibilityStateStore.fromContext(appContext)
  return PersistingAppVisibilityPublisher(
    stateStore = stateStore,
    broadcaster = BroadcastIntentAppVisibilityChangeBroadcaster(appContext),
  )
}

internal fun defaultAppVisibilitySignalAccess(
  context: Context,
): AppVisibilitySignalAccess {
  val appContext = context.applicationContext
  val stateStore = SharedPreferencesAppVisibilityStateStore.fromContext(appContext)
  return StoreBackedAppVisibilitySignalAccess(
    stateStore = stateStore,
    listenerRegistrar = BroadcastReceiverAppVisibilityChangeListenerRegistrar(
      appContext = appContext,
      stateStore = stateStore,
    ),
  )
}
