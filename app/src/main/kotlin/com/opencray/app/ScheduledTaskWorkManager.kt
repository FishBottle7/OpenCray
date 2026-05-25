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
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
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
  private val runtimeEnvironment: OpenCrayRuntimeServiceEnvironment by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    openCrayRuntimeServiceEnvironment(applicationContext)
  }

  override fun doWork(): Result {
    val scheduleId = inputData.getString(WORK_DATA_SCHEDULE_ID)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return Result.failure()
    val scheduledForEpochMs = inputData.getLong(WORK_DATA_SCHEDULED_FOR_EPOCH_MS, -1L)
      .takeIf { value -> value >= 0L }
      ?: return Result.failure()
    return runCatching {
      runtimeEnvironment.runtimeServiceAccessGateway.startScheduledTask(
        applicationContext,
        ScheduledTaskWakeCommand(
          scheduleId = scheduleId,
          scheduleRunId = scheduledTaskRunId(scheduleId, scheduledForEpochMs),
          triggeredAtEpochMs = System.currentTimeMillis(),
          triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
        ),
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
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
  private val runtimeEnvironment: OpenCrayRuntimeServiceEnvironment by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    openCrayRuntimeServiceEnvironment(applicationContext)
  }

  override fun doWork(): Result {
    val reason = inputData.getString(WORK_DATA_REPAIR_REASON)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: ScheduledTaskRepairReasons.WORK_MANAGER
    return runCatching {
      resyncEnabledScheduledTasksFromContext(applicationContext)
      val hasDueCommands = plannedRepairWakeCommands(
        enabledSpecs = FileBackedScheduledTaskSpecStoreFactory
          .fromContext(applicationContext)
          .create()
          .listEnabled(),
        nowEpochMs = System.currentTimeMillis(),
        repairReason = reason,
      ).isNotEmpty()
      val hasInteractiveRepairWork = hasPotentialInteractiveRunRepairWork(applicationContext)
      val scheduledRepairStarted = when {
        !hasDueCommands -> true
        else -> runtimeEnvironment.runtimeServiceAccessGateway.repairSchedules(
          applicationContext,
          reason,
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        )
      }
      val interactiveRepairStarted = when {
        !hasInteractiveRepairWork -> true
        else -> runtimeEnvironment.runtimeServiceAccessGateway.resumeInterruptedRuns(
          applicationContext,
          reason,
          target = RuntimeServiceTarget.INTERACTIVE,
        )
      }
      when {
        scheduledRepairStarted && interactiveRepairStarted -> Result.success()
        else -> Result.retry()
      }
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
    val appContext = context.applicationContext
    val reason = scheduledTaskRepairReasonForAction(intent?.action) ?: return
    runCatching {
      resyncEnabledScheduledTasksFromContext(appContext)
    }
    WorkManagerScheduledWorkScheduler.fromContext(appContext).enqueueRepair(reason)
  }
}

internal fun scheduledTaskRepairReasonForAction(action: String?): String? =
  when (action) {
    Intent.ACTION_BOOT_COMPLETED -> ScheduledTaskRepairReasons.BOOT_COMPLETED
    Intent.ACTION_MY_PACKAGE_REPLACED -> ScheduledTaskRepairReasons.PACKAGE_REPLACED
    else -> null
  }

internal fun resyncEnabledScheduledTasksFromContext(context: Context) {
  val appContext = context.applicationContext
  resyncEnabledScheduledTasks(
    specStore = FileBackedScheduledTaskSpecStoreFactory.fromContext(appContext).create(),
    triggerRegistrar = DefaultScheduledTriggerRegistrar(
      alarmScheduler = AlarmManagerScheduledAlarmScheduler.fromContext(appContext),
      workScheduler = WorkManagerScheduledWorkScheduler.fromContext(appContext),
    ),
    triggerSyncStateStore = FileBackedScheduledTaskTriggerSyncStateStoreFactory
      .fromContext(appContext)
      .create(),
  )
}

internal fun hasPotentialInteractiveRunRepairWork(
  context: Context,
): Boolean {
  val appContext = context.applicationContext
  return hasPotentialInteractiveRunRepairWork(
    chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
    promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext),
    subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(appContext),
  )
}

internal fun hasPotentialInteractiveRunRepairWork(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
): Boolean {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  )
  return knownSessionIds.any { sessionId ->
    hasPotentialInteractiveRunRepairWorkForSession(
      sessionId = sessionId,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    )
  }
}

internal fun hasPotentialInteractiveRunRepairWorkForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
): Boolean {
  val hasNonTerminalQueueTask = snapshotStoreFactory.forChatSession(sessionId)
    .load()
    ?.tasks
    ?.any { taskSnapshot ->
      taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.COMPLETED &&
        taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED &&
        taskSnapshot.lifecycleState != com.opencray.core.orchestrator.QueueTaskLifecycleState.CANCELLED
    } == true
  val hasRecoverableSubAgentHandle = subAgentHandleStoreFactory.forChatSession(sessionId)
    .list()
    .any(::isPotentialInteractiveRepairSubAgentHandle)
  return hasNonTerminalQueueTask ||
    hasRecoverableSubAgentHandle ||
    promptCheckpointStoreFactory.forChatSession(sessionId).list().isNotEmpty()
}

private fun isPotentialInteractiveRepairSubAgentHandle(handle: SubAgentHandleState): Boolean =
  handle.snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED ||
    handle.snapshot.state == SubAgentExecutionState.BACKGROUND_RUNNING

private fun scheduleWakeWorkName(scheduleId: String): String =
  "scheduled-task-wake-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(scheduleId)}"

internal const val REPAIR_WORK_NAME: String = "scheduled-task-repair"
internal const val WORK_DATA_SCHEDULE_ID: String = "schedule_id"
internal const val WORK_DATA_SCHEDULED_FOR_EPOCH_MS: String = "scheduled_for_epoch_ms"
internal const val WORK_DATA_REPAIR_REASON: String = "repair_reason"
