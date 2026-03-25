package com.opencray.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal interface ScheduledWorkScheduler {
  fun scheduleWake(
    scheduleId: String,
    triggerAtEpochMs: Long,
  )

  fun cancel(scheduleId: String)

  fun enqueueRepair(reason: String)
}

internal class WorkManagerScheduledWorkScheduler(
  private val workManager: WorkManager,
  private val clock: () -> Long = System::currentTimeMillis,
) : ScheduledWorkScheduler {
  override fun scheduleWake(
    scheduleId: String,
    triggerAtEpochMs: Long,
  ) {
    val delayMs = maxOf(0L, triggerAtEpochMs - clock())
    val request = OneTimeWorkRequestBuilder<ScheduledTaskWakeWorker>()
      .setInputData(
        Data.Builder()
          .putString(WORK_DATA_SCHEDULE_ID, scheduleId)
          .putLong(WORK_DATA_SCHEDULED_FOR_EPOCH_MS, triggerAtEpochMs)
          .build(),
      )
      .setConstraints(defaultConstraints())
      .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
      .addTag(scheduleWakeWorkName(scheduleId))
      .build()
    workManager.enqueueUniqueWork(
      scheduleWakeWorkName(scheduleId),
      ExistingWorkPolicy.REPLACE,
      request,
    )
  }

  override fun cancel(scheduleId: String) {
    workManager.cancelUniqueWork(scheduleWakeWorkName(scheduleId))
  }

  override fun enqueueRepair(reason: String) {
    val request = OneTimeWorkRequestBuilder<ScheduledTaskRepairWorker>()
      .setInputData(
        Data.Builder()
          .putString(WORK_DATA_REPAIR_REASON, reason)
          .build(),
      )
      .setConstraints(defaultConstraints())
      .addTag(REPAIR_WORK_NAME)
      .build()
    workManager.enqueueUniqueWork(
      REPAIR_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      request,
    )
  }

  private fun defaultConstraints(): Constraints = Constraints.Builder().build()

  companion object {
    fun fromContext(context: Context): WorkManagerScheduledWorkScheduler =
      WorkManagerScheduledWorkScheduler(
        workManager = WorkManager.getInstance(context.applicationContext),
      )
  }
}

internal class ScheduledTaskWakeWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
  override fun doWork(): Result {
    val scheduleId = inputData.getString(WORK_DATA_SCHEDULE_ID)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return Result.failure()
    val scheduledForEpochMs = inputData.getLong(WORK_DATA_SCHEDULED_FOR_EPOCH_MS, -1L)
      .takeIf { value -> value >= 0L }
      ?: return Result.failure()
    return runCatching {
      OpenCrayAgentRuntimeService.startScheduledTask(
        applicationContext,
        ScheduledTaskWakeCommand(
          scheduleId = scheduleId,
          scheduleRunId = scheduledTaskRunId(scheduleId, scheduledForEpochMs),
          triggeredAtEpochMs = System.currentTimeMillis(),
          triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
        ),
      )
      Result.success()
    }.getOrElse {
      Result.retry()
    }
  }
}

internal class ScheduledTaskRepairWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
  override fun doWork(): Result {
    val reason = inputData.getString(WORK_DATA_REPAIR_REASON)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: ScheduledTaskRepairReasons.WORK_MANAGER
    return runCatching {
      OpenCrayAgentRuntimeService.repairSchedules(
        applicationContext,
        reason,
      )
      Result.success()
    }.getOrElse {
      Result.retry()
    }
  }
}

internal class ScheduledTaskRepairReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    val reason = when (intent?.action) {
      Intent.ACTION_BOOT_COMPLETED -> ScheduledTaskRepairReasons.BOOT_COMPLETED
      Intent.ACTION_MY_PACKAGE_REPLACED -> ScheduledTaskRepairReasons.PACKAGE_REPLACED
      else -> ScheduledTaskRepairReasons.BROADCAST
    }
    WorkManagerScheduledWorkScheduler.fromContext(context).enqueueRepair(reason)
  }
}

private fun scheduleWakeWorkName(scheduleId: String): String =
  "scheduled-task-wake-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(scheduleId)}"

internal const val REPAIR_WORK_NAME: String = "scheduled-task-repair"
internal const val WORK_DATA_SCHEDULE_ID: String = "schedule_id"
internal const val WORK_DATA_SCHEDULED_FOR_EPOCH_MS: String = "scheduled_for_epoch_ms"
internal const val WORK_DATA_REPAIR_REASON: String = "repair_reason"
