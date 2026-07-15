package com.opencray.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal enum class WorkManagerClientRoute {
  MAIN_PROCESS,
  MAIN_PROCESS_PROXY,
}

internal fun workManagerClientRoute(
  packageName: String,
  processName: String?,
): WorkManagerClientRoute {
  val normalizedPackageName = packageName.trim()
  val normalizedProcessName = processName?.trim()?.takeIf(String::isNotBlank)
  return if (
    normalizedPackageName.isNotBlank() &&
    normalizedProcessName == normalizedPackageName
  ) {
    WorkManagerClientRoute.MAIN_PROCESS
  } else {
    WorkManagerClientRoute.MAIN_PROCESS_PROXY
  }
}

internal object ProcessSafeScheduledWorkSchedulerFactory {
  fun fromContext(
    context: Context,
    processName: String? = currentProcessNameOrNull(),
  ): ScheduledWorkScheduler {
    val appContext = context.applicationContext
    return when (workManagerClientRoute(appContext.packageName, processName)) {
      WorkManagerClientRoute.MAIN_PROCESS ->
        WorkManagerScheduledWorkScheduler.fromContext(appContext)
      WorkManagerClientRoute.MAIN_PROCESS_PROXY ->
        MainProcessScheduledWorkSchedulerProxy.fromContext(appContext)
    }
  }
}

internal sealed interface ScheduledWorkCommand {
  data class ScheduleWake(
    val scheduleId: String,
    val triggerAtEpochMs: Long,
  ) : ScheduledWorkCommand

  data class CancelWake(
    val scheduleId: String,
  ) : ScheduledWorkCommand

  data class EnqueueRepair(
    val reason: String,
    val initialDelayMs: Long,
  ) : ScheduledWorkCommand

  data object EnsurePeriodicRepair : ScheduledWorkCommand
}

internal class MainProcessScheduledWorkSchedulerProxy(
  private val commandSender: (ScheduledWorkCommand) -> Unit,
) : ScheduledWorkScheduler {
  override fun scheduleWake(
    scheduleId: String,
    triggerAtEpochMs: Long,
  ) {
    commandSender(
      ScheduledWorkCommand.ScheduleWake(
        scheduleId = scheduleId,
        triggerAtEpochMs = triggerAtEpochMs,
      ),
    )
  }

  override fun cancel(scheduleId: String) {
    commandSender(ScheduledWorkCommand.CancelWake(scheduleId))
  }

  override fun enqueueRepair(
    reason: String,
    initialDelayMs: Long,
  ) {
    commandSender(
      ScheduledWorkCommand.EnqueueRepair(
        reason = reason,
        initialDelayMs = initialDelayMs.coerceAtLeast(0L),
      ),
    )
  }

  override fun ensurePeriodicRepair() {
    commandSender(ScheduledWorkCommand.EnsurePeriodicRepair)
  }

  companion object {
    fun fromContext(context: Context): MainProcessScheduledWorkSchedulerProxy {
      val appContext = context.applicationContext
      return MainProcessScheduledWorkSchedulerProxy { command ->
        appContext.sendBroadcast(scheduledWorkCommandIntent(appContext, command))
      }
    }
  }
}

internal class ScheduledWorkCommandReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    val command = parseScheduledWorkCommand(intent) ?: return
    dispatchScheduledWorkCommand(
      command = command,
      scheduler = WorkManagerScheduledWorkScheduler.fromContext(context.applicationContext),
    )
  }
}

internal fun scheduledWorkCommandIntent(
  context: Context,
  command: ScheduledWorkCommand,
): Intent = encodeScheduledWorkCommand(
  intent = Intent(context, ScheduledWorkCommandReceiver::class.java)
    .setPackage(context.packageName),
  command = command,
)

internal fun encodeScheduledWorkCommand(
  intent: Intent,
  command: ScheduledWorkCommand,
): Intent {
  intent.setAction(ACTION_SCHEDULED_WORK_COMMAND)
  when (command) {
    is ScheduledWorkCommand.ScheduleWake -> intent
      .putExtra(EXTRA_SCHEDULED_WORK_COMMAND_KIND, COMMAND_SCHEDULE_WAKE)
      .putExtra(EXTRA_SCHEDULED_WORK_SCHEDULE_ID, command.scheduleId)
      .putExtra(EXTRA_SCHEDULED_WORK_TRIGGER_AT_EPOCH_MS, command.triggerAtEpochMs)
    is ScheduledWorkCommand.CancelWake -> intent
      .putExtra(EXTRA_SCHEDULED_WORK_COMMAND_KIND, COMMAND_CANCEL_WAKE)
      .putExtra(EXTRA_SCHEDULED_WORK_SCHEDULE_ID, command.scheduleId)
    is ScheduledWorkCommand.EnqueueRepair -> intent
      .putExtra(EXTRA_SCHEDULED_WORK_COMMAND_KIND, COMMAND_ENQUEUE_REPAIR)
      .putExtra(EXTRA_SCHEDULED_WORK_REPAIR_REASON, command.reason)
      .putExtra(EXTRA_SCHEDULED_WORK_INITIAL_DELAY_MS, command.initialDelayMs)
    ScheduledWorkCommand.EnsurePeriodicRepair -> intent
      .putExtra(EXTRA_SCHEDULED_WORK_COMMAND_KIND, COMMAND_ENSURE_PERIODIC_REPAIR)
  }
  return intent
}

internal fun parseScheduledWorkCommand(intent: Intent?): ScheduledWorkCommand? =
  parseScheduledWorkCommand(
    action = safeScheduledWorkAction(intent),
    commandKind = safeScheduledWorkStringExtra(intent, EXTRA_SCHEDULED_WORK_COMMAND_KIND),
    scheduleId = safeScheduledWorkStringExtra(intent, EXTRA_SCHEDULED_WORK_SCHEDULE_ID),
    triggerAtEpochMs = safeScheduledWorkLongExtra(
      intent,
      EXTRA_SCHEDULED_WORK_TRIGGER_AT_EPOCH_MS,
    ),
    repairReason = safeScheduledWorkStringExtra(intent, EXTRA_SCHEDULED_WORK_REPAIR_REASON),
    initialDelayMs = safeScheduledWorkLongExtra(
      intent,
      EXTRA_SCHEDULED_WORK_INITIAL_DELAY_MS,
    ),
  )

internal fun parseScheduledWorkCommand(
  action: String?,
  commandKind: String?,
  scheduleId: String?,
  triggerAtEpochMs: Long?,
  repairReason: String?,
  initialDelayMs: Long?,
): ScheduledWorkCommand? {
  if (action != ACTION_SCHEDULED_WORK_COMMAND) {
    return null
  }
  return when (commandKind) {
    COMMAND_SCHEDULE_WAKE -> ScheduledWorkCommand.ScheduleWake(
      scheduleId = scheduleId.normalizedScheduledWorkValue() ?: return null,
      triggerAtEpochMs = triggerAtEpochMs?.takeIf { value -> value >= 0L } ?: return null,
    )
    COMMAND_CANCEL_WAKE -> ScheduledWorkCommand.CancelWake(
      scheduleId = scheduleId.normalizedScheduledWorkValue() ?: return null,
    )
    COMMAND_ENQUEUE_REPAIR -> ScheduledWorkCommand.EnqueueRepair(
      reason = repairReason.normalizedScheduledWorkValue() ?: return null,
      initialDelayMs = initialDelayMs?.takeIf { value -> value >= 0L } ?: return null,
    )
    COMMAND_ENSURE_PERIODIC_REPAIR -> ScheduledWorkCommand.EnsurePeriodicRepair
    else -> null
  }
}

internal fun dispatchScheduledWorkCommand(
  command: ScheduledWorkCommand,
  scheduler: ScheduledWorkScheduler,
) {
  when (command) {
    is ScheduledWorkCommand.ScheduleWake -> scheduler.scheduleWake(
      scheduleId = command.scheduleId,
      triggerAtEpochMs = command.triggerAtEpochMs,
    )
    is ScheduledWorkCommand.CancelWake -> scheduler.cancel(command.scheduleId)
    is ScheduledWorkCommand.EnqueueRepair -> scheduler.enqueueRepair(
      reason = command.reason,
      initialDelayMs = command.initialDelayMs,
    )
    ScheduledWorkCommand.EnsurePeriodicRepair -> scheduler.ensurePeriodicRepair()
  }
}

private fun String?.normalizedScheduledWorkValue(): String? =
  this?.trim()?.takeIf(String::isNotBlank)

private fun safeScheduledWorkAction(intent: Intent?): String? =
  runCatching { intent?.action }.getOrNull()

private fun safeScheduledWorkStringExtra(
  intent: Intent?,
  key: String,
): String? = runCatching { intent?.getStringExtra(key) }.getOrNull()

private fun safeScheduledWorkLongExtra(
  intent: Intent?,
  key: String,
): Long? = runCatching {
  intent?.getLongExtra(key, INVALID_SCHEDULED_WORK_LONG)
    ?.takeUnless { value -> value == INVALID_SCHEDULED_WORK_LONG }
}.getOrNull()

internal const val ACTION_SCHEDULED_WORK_COMMAND: String =
  "com.opencray.app.action.SCHEDULED_WORK_COMMAND"
internal const val EXTRA_SCHEDULED_WORK_COMMAND_KIND: String = "scheduled_work_command_kind"
internal const val EXTRA_SCHEDULED_WORK_SCHEDULE_ID: String = "scheduled_work_schedule_id"
internal const val EXTRA_SCHEDULED_WORK_TRIGGER_AT_EPOCH_MS: String =
  "scheduled_work_trigger_at_epoch_ms"
internal const val EXTRA_SCHEDULED_WORK_REPAIR_REASON: String = "scheduled_work_repair_reason"
internal const val EXTRA_SCHEDULED_WORK_INITIAL_DELAY_MS: String =
  "scheduled_work_initial_delay_ms"

private const val COMMAND_SCHEDULE_WAKE: String = "schedule_wake"
private const val COMMAND_CANCEL_WAKE: String = "cancel_wake"
private const val COMMAND_ENQUEUE_REPAIR: String = "enqueue_repair"
private const val COMMAND_ENSURE_PERIODIC_REPAIR: String = "ensure_periodic_repair"
private const val INVALID_SCHEDULED_WORK_LONG: Long = Long.MIN_VALUE
