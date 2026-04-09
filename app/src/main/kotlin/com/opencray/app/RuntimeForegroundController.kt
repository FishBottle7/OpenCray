package com.opencray.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

internal interface RuntimeForegroundServiceAdapter {
  fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel)

  fun stopForeground(removeNotification: Boolean)
}

internal class RuntimeForegroundController(
  private val serviceAdapter: RuntimeForegroundServiceAdapter,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private var destroyed: Boolean = false
  private var currentState: RuntimeForegroundState = RuntimeForegroundState(
    changedAtEpochMs = clock(),
  )

  fun currentState(): RuntimeForegroundState = synchronized(lock) { currentState }

  fun startBootstrapForeground(
    keepAliveReason: String = RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP,
  ): RuntimeForegroundState {
    val model = RuntimeForegroundNotificationModel(
      activeRunCount = 0,
      activeSessionCount = 0,
      liveManagedProcessSessionCount = 0,
      keepAliveReason = keepAliveReason,
    )
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
    }
    // Android expects startForeground() synchronously after startForegroundService().
    serviceAdapter.startOrUpdateForeground(model)
    return nextState
  }

  fun onWorkStateChanged(workState: RuntimeServiceWorkState): RuntimeForegroundState {
    val command: RuntimeForegroundCommand?
    val nextState: RuntimeForegroundState
    synchronized(lock) {
      if (destroyed) {
        return currentState
      }
      val now = clock()
      if (!workState.keepAliveRequired) {
        if (currentState.phase == RuntimeForegroundState.PHASE_IDLE) {
          return currentState
        }
        currentState = RuntimeForegroundState(
          phase = RuntimeForegroundState.PHASE_IDLE,
          notificationVisible = false,
          activeRunCount = workState.activeRunCount,
          activeSessionCount = workState.activeSessionCount,
          changedAtEpochMs = now,
        )
        nextState = currentState
        command = RuntimeForegroundCommand.Stop
      } else {
        val model = RuntimeForegroundNotificationModel(
          activeRunCount = workState.activeRunCount,
          activeSessionCount = workState.activeSessionCount,
          liveManagedProcessSessionCount = workState.liveManagedProcessSessionCount,
          keepAliveReason = workState.keepAliveReason,
        )
        if (
          currentState.phase == RuntimeForegroundState.PHASE_FOREGROUND &&
          currentState.activeRunCount == model.activeRunCount &&
          currentState.activeSessionCount == model.activeSessionCount &&
          currentState.keepAliveReason == model.keepAliveReason
        ) {
          return currentState
        }
        currentState = RuntimeForegroundState(
          phase = RuntimeForegroundState.PHASE_FOREGROUND,
          notificationVisible = true,
          activeRunCount = model.activeRunCount,
          activeSessionCount = model.activeSessionCount,
          keepAliveReason = model.keepAliveReason,
          changedAtEpochMs = now,
        )
        nextState = currentState
        command = RuntimeForegroundCommand.StartOrUpdate(model)
      }
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
    mainThreadPoster.post {
      when (command) {
        is RuntimeForegroundCommand.StartOrUpdate ->
          serviceAdapter.startOrUpdateForeground(command.model)
        RuntimeForegroundCommand.Stop ->
          serviceAdapter.stopForeground(removeNotification = true)
      }
    }
  }

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
  private val notificationId: Int = NOTIFICATION_ID_RUNTIME_ACTIVE,
) : RuntimeForegroundServiceAdapter {
  override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
    service.startForeground(
      notificationId,
      notificationFactory.build(model),
    )
  }

  override fun stopForeground(removeNotification: Boolean) {
    @Suppress("DEPRECATION")
    service.stopForeground(removeNotification)
  }

  private companion object {
    const val NOTIFICATION_ID_RUNTIME_ACTIVE: Int = 42_601
  }
}

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
