package com.opencray.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import org.opencray.app.R

internal data class RuntimeForegroundState(
  val phase: String = PHASE_IDLE,
  val notificationVisible: Boolean = false,
  val activeRunCount: Int = 0,
  val activeSessionCount: Int = 0,
  val keepAliveReason: String? = null,
  val changedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("notificationVisible", notificationVisible)
    put("activeRunCount", activeRunCount)
    put("activeSessionCount", activeSessionCount)
    keepAliveReason?.let { reason ->
      put("keepAliveReason", reason)
    }
    put("changedAtEpochMs", changedAtEpochMs)
  }

  companion object {
    const val PHASE_IDLE: String = "idle"
    const val PHASE_FOREGROUND: String = "foreground"
    const val PHASE_DESTROYED: String = "destroyed"
  }
}

internal data class RuntimeForegroundNotificationModel(
  val activeRunCount: Int,
  val activeSessionCount: Int,
  val liveManagedProcessSessionCount: Int,
  val keepAliveReason: String?,
)

internal fun runtimeServiceBootstrapForegroundNotificationModel(
  keepAliveReason: String = RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP,
): RuntimeForegroundNotificationModel = RuntimeForegroundNotificationModel(
  activeRunCount = 0,
  activeSessionCount = 0,
  liveManagedProcessSessionCount = 0,
  keepAliveReason = keepAliveReason,
)

internal fun startRuntimeServiceBootstrapForeground(
  service: Service,
  appContext: Context,
  target: RuntimeServiceTarget,
) {
  RuntimeNotificationChannelRegistry.ensureRegistered(appContext)
  AndroidRuntimeForegroundServiceAdapter(
    service = service,
    notificationFactory = RuntimeActiveNotificationFactory(appContext),
    notificationId = runtimeActiveForegroundNotificationId(target),
  ).startOrUpdateForeground(runtimeServiceBootstrapForegroundNotificationModel())
}

internal interface RuntimeForegroundServiceAdapter {
  fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel)

  fun stopForeground(removeNotification: Boolean)
}

internal class RuntimeForegroundServiceTypeResolver(
  private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
) {
  fun foregroundServiceType(
    _model: RuntimeForegroundNotificationModel,
  ): Int? {
    return if (sdkIntProvider() >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
      null
    }
  }
}

internal class RuntimeForegroundController(
  serviceAdapter: RuntimeForegroundServiceAdapter? = null,
  private val retainForegroundDuringIdleGraceProvider: () -> Boolean = { false },
  private val appVisibleProvider: () -> Boolean,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private var destroyed: Boolean = false
  private var boundServiceAdapter: RuntimeForegroundServiceAdapter? = serviceAdapter
  private var appVisible: Boolean = appVisibleProvider()
  private var lastObservedWorkState: RuntimeServiceWorkState = RuntimeServiceWorkState(
    changedAtEpochMs = clock(),
  )
  private var lastKeepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(
    appVisible = appVisible,
    changedAtEpochMs = clock(),
  )
  private var currentState: RuntimeForegroundState = RuntimeForegroundState(
    changedAtEpochMs = clock(),
  )
  private var currentNotificationModel: RuntimeForegroundNotificationModel? = null

  fun currentState(): RuntimeForegroundState = synchronized(lock) { currentState }

  fun bindServiceAdapter(
    serviceAdapter: RuntimeForegroundServiceAdapter,
  ): RuntimeForegroundState {
    val modelToReplay = synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      boundServiceAdapter = serviceAdapter
      currentNotificationModel
    }
    if (modelToReplay != null) {
      dispatchTo(
        serviceAdapter = serviceAdapter,
        command = RuntimeForegroundCommand.StartOrUpdate(modelToReplay),
      )
    }
    return currentState()
  }

  fun unbindServiceAdapter(
    serviceAdapter: RuntimeForegroundServiceAdapter? = null,
  ) {
    synchronized(lock) {
      if (serviceAdapter == null || boundServiceAdapter === serviceAdapter) {
        boundServiceAdapter = null
      }
    }
  }

  fun startBootstrapForeground(
    keepAliveReason: String = RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP,
  ): RuntimeForegroundState {
    val model = runtimeServiceBootstrapForegroundNotificationModel(keepAliveReason)
    val nextState: RuntimeForegroundState
    synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      if (
        currentState.phase == RuntimeForegroundState.PHASE_FOREGROUND &&
        currentState.activeRunCount == model.activeRunCount &&
        currentState.activeSessionCount == model.activeSessionCount &&
        currentState.keepAliveReason == model.keepAliveReason
      ) {
        return currentState
      }
      nextState = RuntimeForegroundState(
        phase = RuntimeForegroundState.PHASE_FOREGROUND,
        notificationVisible = true,
        activeRunCount = model.activeRunCount,
        activeSessionCount = model.activeSessionCount,
        keepAliveReason = model.keepAliveReason,
        changedAtEpochMs = clock(),
      )
      currentState = nextState
      currentNotificationModel = model
    }
    // Android expects startForeground() synchronously after startForegroundService().
    dispatchToCurrentServiceAdapter(
      RuntimeForegroundCommand.StartOrUpdate(model),
    )
    return nextState
  }

  fun onWorkStateChanged(workState: RuntimeServiceWorkState): RuntimeForegroundState {
    val now = clock()
    val command: RuntimeForegroundCommand?
    val nextState: RuntimeForegroundState
    synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      lastObservedWorkState = workState
      val transition = reduceStateLocked(now)
      nextState = transition.state
      command = transition.command
    }
    dispatch(command)
    return nextState
  }

  fun onKeepAliveStateChanged(
    keepAliveState: RuntimeServiceKeepAliveState,
  ): RuntimeForegroundState {
    val now = clock()
    val command: RuntimeForegroundCommand?
    val nextState: RuntimeForegroundState
    synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      lastKeepAliveState = keepAliveState
      val transition = reduceStateLocked(now)
      nextState = transition.state
      command = transition.command
    }
    dispatch(command)
    return nextState
  }

  fun onAppVisibilityChanged(appVisible: Boolean): RuntimeForegroundState {
    val now = clock()
    val command: RuntimeForegroundCommand?
    val nextState: RuntimeForegroundState
    synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      if (this.appVisible == appVisible) {
        return currentState
      }
      this.appVisible = appVisible
      val transition = reduceStateLocked(now)
      nextState = transition.state
      command = transition.command
    }
    dispatch(command)
    return nextState
  }

  fun onDestroy(): RuntimeForegroundState {
    val command: RuntimeForegroundCommand?
    val nextState: RuntimeForegroundState
    synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      destroyed = true
      val now = clock()
      command = if (currentState.notificationVisible) {
        RuntimeForegroundCommand.Stop
      } else {
        null
      }
      currentNotificationModel = null
      currentState = currentState.copy(
        phase = RuntimeForegroundState.PHASE_DESTROYED,
        notificationVisible = false,
        changedAtEpochMs = now,
      )
      nextState = currentState
    }
    dispatch(command)
    return nextState
  }

  private fun dispatch(command: RuntimeForegroundCommand?) {
    if (command == null) {
      return
    }
    dispatchToCurrentServiceAdapter(command)
  }

  private fun dispatchToCurrentServiceAdapter(command: RuntimeForegroundCommand) {
    val serviceAdapter = synchronized(lock) { boundServiceAdapter } ?: return
    dispatchTo(
      serviceAdapter = serviceAdapter,
      command = command,
    )
  }

  private fun dispatchTo(
    serviceAdapter: RuntimeForegroundServiceAdapter,
    command: RuntimeForegroundCommand,
  ) {
    mainThreadPoster.post {
      when (command) {
        is RuntimeForegroundCommand.StartOrUpdate ->
          serviceAdapter.startOrUpdateForeground(command.model)
        RuntimeForegroundCommand.Stop ->
          serviceAdapter.stopForeground(removeNotification = true)
      }
    }
  }

  private fun reduceStateLocked(
    now: Long,
  ): RuntimeForegroundTransition {
    val model = desiredForegroundModelLocked()
    if (model == null) {
      if (currentState.phase == RuntimeForegroundState.PHASE_IDLE) {
        return RuntimeForegroundTransition(
          state = currentState,
          command = null,
        )
      }
      currentState = RuntimeForegroundState(
        phase = RuntimeForegroundState.PHASE_IDLE,
        notificationVisible = false,
        activeRunCount = lastObservedWorkState.activeRunCount,
        activeSessionCount = lastObservedWorkState.activeSessionCount,
        changedAtEpochMs = now,
      )
      currentNotificationModel = null
      return RuntimeForegroundTransition(
        state = currentState,
        command = RuntimeForegroundCommand.Stop,
      )
    }
    if (
      currentState.phase == RuntimeForegroundState.PHASE_FOREGROUND &&
      currentState.activeRunCount == model.activeRunCount &&
      currentState.activeSessionCount == model.activeSessionCount &&
      currentState.keepAliveReason == model.keepAliveReason
    ) {
      return RuntimeForegroundTransition(
        state = currentState,
        command = null,
      )
    }
    currentNotificationModel = model
    currentState = RuntimeForegroundState(
      phase = RuntimeForegroundState.PHASE_FOREGROUND,
      notificationVisible = true,
      activeRunCount = model.activeRunCount,
      activeSessionCount = model.activeSessionCount,
      keepAliveReason = model.keepAliveReason,
      changedAtEpochMs = now,
    )
    return RuntimeForegroundTransition(
      state = currentState,
      command = RuntimeForegroundCommand.StartOrUpdate(model),
    )
  }

  private fun desiredForegroundModelLocked(): RuntimeForegroundNotificationModel? {
    if (lastObservedWorkState.keepAliveRequired) {
      return RuntimeForegroundNotificationModel(
        activeRunCount = lastObservedWorkState.activeRunCount,
        activeSessionCount = lastObservedWorkState.activeSessionCount,
        liveManagedProcessSessionCount = lastObservedWorkState.liveManagedProcessSessionCount,
        keepAliveReason = lastObservedWorkState.keepAliveReason,
      )
    }
    if (!shouldRetainForegroundDuringIdleGraceLocked()) {
      return null
    }
    return RuntimeForegroundNotificationModel(
      activeRunCount = lastObservedWorkState.activeRunCount,
      activeSessionCount = lastObservedWorkState.activeSessionCount,
      liveManagedProcessSessionCount = lastObservedWorkState.liveManagedProcessSessionCount,
      keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_IDLE_GRACE,
    )
  }

  private fun shouldRetainForegroundDuringIdleGraceLocked(): Boolean =
    !appVisible &&
      retainForegroundDuringIdleGraceProvider() &&
      lastKeepAliveState.phase == RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE &&
      lastKeepAliveState.stopScheduled

  private data class RuntimeForegroundTransition(
    val state: RuntimeForegroundState,
    val command: RuntimeForegroundCommand?,
  )

  private sealed interface RuntimeForegroundCommand {
    data class StartOrUpdate(
      val model: RuntimeForegroundNotificationModel,
    ) : RuntimeForegroundCommand

    data object Stop : RuntimeForegroundCommand
  }
}

internal object RuntimeNotificationChannelRegistry {
  const val CHANNEL_RUNTIME_ACTIVE: String = "runtime_active"
  const val CHANNEL_RUNTIME_APPROVAL: String = "runtime_approval"
  const val CHANNEL_RUNTIME_COMPLETION: String = "runtime_completion"
  const val CHANNEL_RUNTIME_SCHEDULE: String = "runtime_schedule"
  const val CHANNEL_MODEL_DOWNLOAD: String = "model_download"

  fun ensureRegistered(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
    notificationManager.createNotificationChannels(
      listOf(
        NotificationChannel(
          CHANNEL_RUNTIME_ACTIVE,
          context.getString(R.string.runtime_notification_active_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = context.getString(R.string.runtime_notification_active_channel_description)
        },
        NotificationChannel(
          CHANNEL_RUNTIME_APPROVAL,
          context.getString(R.string.runtime_notification_approval_channel_name),
          NotificationManager.IMPORTANCE_HIGH,
        ).apply {
          description = context.getString(R.string.runtime_notification_approval_channel_description)
        },
        NotificationChannel(
          CHANNEL_RUNTIME_COMPLETION,
          context.getString(R.string.runtime_notification_completion_channel_name),
          NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
          description = context.getString(R.string.runtime_notification_completion_channel_description)
        },
        NotificationChannel(
          CHANNEL_RUNTIME_SCHEDULE,
          context.getString(R.string.runtime_notification_schedule_channel_name),
          NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
          description = context.getString(R.string.runtime_notification_schedule_channel_description)
        },
        NotificationChannel(
          CHANNEL_MODEL_DOWNLOAD,
          context.getString(R.string.model_download_notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = context.getString(R.string.model_download_notification_channel_description)
        },
      ),
    )
  }
}

internal class RuntimeActiveNotificationFactory(
  private val context: Context,
  private val openAppIntentProvider: () -> PendingIntent = {
    createOpenAppPendingIntent(context)
  },
) {
  fun build(model: RuntimeForegroundNotificationModel): Notification {
    val contentText = when {
      model.keepAliveReason == RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP ->
        context.getString(R.string.runtime_notification_active_bootstrap_text)
      model.keepAliveReason == RuntimeServiceWorkState.KEEP_ALIVE_REASON_IDLE_GRACE ->
        context.getString(R.string.runtime_notification_active_idle_grace_text)
      model.keepAliveReason == RuntimeServiceWorkState.KEEP_ALIVE_REASON_MANAGED_PROCESS &&
        model.liveManagedProcessSessionCount > 0 ->
        context.getString(
          R.string.runtime_notification_active_text_with_processes,
          model.activeRunCount,
          model.activeSessionCount,
        )
      else ->
        context.getString(
          R.string.runtime_notification_active_text,
          model.activeRunCount,
          model.activeSessionCount,
        )
    }
    return NotificationCompat.Builder(
      context,
      RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_ACTIVE,
    )
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(context.getString(R.string.runtime_notification_active_title))
      .setContentText(contentText)
      .setContentIntent(openAppIntentProvider())
      .addAction(
        0,
        context.getString(R.string.runtime_notification_action_open),
        openAppIntentProvider(),
      )
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .build()
  }
}

internal class AndroidRuntimeForegroundServiceAdapter(
  private val service: Service,
  private val notificationFactory: RuntimeActiveNotificationFactory,
  private val notificationId: Int = runtimeActiveForegroundNotificationId(
    RuntimeServiceTarget.INTERACTIVE,
  ),
  private val serviceTypeResolver: RuntimeForegroundServiceTypeResolver =
    RuntimeForegroundServiceTypeResolver(),
) : RuntimeForegroundServiceAdapter {
  override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
    val notification = notificationFactory.build(model)
    val serviceType = serviceTypeResolver.foregroundServiceType(model)
    if (serviceType != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      service.startForeground(notificationId, notification, serviceType)
    } else {
      service.startForeground(notificationId, notification)
    }
  }

  override fun stopForeground(removeNotification: Boolean) {
    @Suppress("DEPRECATION")
    service.stopForeground(removeNotification)
  }
}

internal fun runtimeActiveForegroundNotificationId(
  target: RuntimeServiceTarget,
): Int = when (target) {
  RuntimeServiceTarget.INTERACTIVE -> NOTIFICATION_ID_RUNTIME_ACTIVE_INTERACTIVE
  RuntimeServiceTarget.DETACHED_BACKGROUND -> NOTIFICATION_ID_RUNTIME_ACTIVE_DETACHED
}

private const val NOTIFICATION_ID_RUNTIME_ACTIVE_INTERACTIVE: Int = 42_601
private const val NOTIFICATION_ID_RUNTIME_ACTIVE_DETACHED: Int = 42_602

private fun createOpenAppPendingIntent(
  context: Context,
): PendingIntent {
  val intent = Intent(context, OpenCrayFlutterActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
  }
  return PendingIntent.getActivity(
    context,
    0,
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}
