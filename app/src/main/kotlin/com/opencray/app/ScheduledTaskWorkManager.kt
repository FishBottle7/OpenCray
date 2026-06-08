package com.opencray.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
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

  fun ensurePeriodicRepair()
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

  override fun ensurePeriodicRepair() {
    val request = PeriodicWorkRequestBuilder<ScheduledTaskRepairWorker>(
      PERIODIC_REPAIR_INTERVAL_HOURS,
      TimeUnit.HOURS,
    )
      .setInputData(
        Data.Builder()
          .putString(WORK_DATA_REPAIR_REASON, ScheduledTaskRepairReasons.PERIODIC)
          .build(),
      )
      .setConstraints(defaultConstraints())
      .addTag(PERIODIC_REPAIR_WORK_NAME)
      .build()
    workManager.enqueueUniquePeriodicWork(
      PERIODIC_REPAIR_WORK_NAME,
      ExistingPeriodicWorkPolicy.KEEP,
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
      val interruptedRunRepairTargets = potentialInterruptedRunRepairTargets(applicationContext)
      val scheduledRepairStarted = when {
        !hasDueCommands -> true
        else -> runtimeEnvironment.runtimeServiceAccessGateway.repairSchedules(
          applicationContext,
          reason,
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        )
      }
      val interruptedRunRepairStarted = startInterruptedRunRepairTargets(
        targets = interruptedRunRepairTargets,
        startRepair = { target ->
          runtimeEnvironment.runtimeServiceAccessGateway.resumeInterruptedRuns(
            applicationContext,
            reason,
            target = target,
          )
        },
      )
      when {
        scheduledRepairStarted && interruptedRunRepairStarted -> Result.success()
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
    val scheduler = WorkManagerScheduledWorkScheduler.fromContext(appContext)
    scheduler.enqueueRepair(reason)
    scheduler.ensurePeriodicRepair()
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
): Boolean = potentialInterruptedRunRepairEvidence(context).isNotEmpty()

internal fun hasPotentialInteractiveRunRepairWork(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): Boolean = potentialInterruptedRunRepairEvidence(
  chatSessionStore = chatSessionStore,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
).isNotEmpty()

internal data class InterruptedRunRepairEvidence(
  val sessionId: String,
  val kind: InterruptedRunRepairEvidenceKind,
  val target: RuntimeServiceTarget,
  val runId: String? = null,
  val taskId: String? = null,
  val detailId: String? = null,
) {
  init {
    require(sessionId.isNotBlank()) { "InterruptedRunRepairEvidence sessionId must not be blank." }
    require(runId == null || runId.isNotBlank()) {
      "InterruptedRunRepairEvidence runId must not be blank."
    }
    require(taskId == null || taskId.isNotBlank()) {
      "InterruptedRunRepairEvidence taskId must not be blank."
    }
    require(detailId == null || detailId.isNotBlank()) {
      "InterruptedRunRepairEvidence detailId must not be blank."
    }
  }
}

internal enum class InterruptedRunRepairEvidenceKind {
  QUEUE_TASK,
  PROMPT_CHECKPOINT,
  DETACHED_SUBAGENT_HANDLE,
  RUN_RECORD,
  JOURNAL_TAIL,
}

internal fun potentialInterruptedRunRepairTargets(
  context: Context,
): Set<RuntimeServiceTarget> =
  potentialInterruptedRunRepairEvidence(context)
    .mapTo(linkedSetOf(), InterruptedRunRepairEvidence::target)

internal fun potentialInterruptedRunRepairTargets(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): Set<RuntimeServiceTarget> = potentialInterruptedRunRepairEvidence(
  chatSessionStore = chatSessionStore,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
).mapTo(linkedSetOf(), InterruptedRunRepairEvidence::target)

internal fun potentialInterruptedRunRepairEvidence(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): List<InterruptedRunRepairEvidence> {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
  )
  val evidence = mutableListOf<InterruptedRunRepairEvidence>()
  knownSessionIds.forEach { sessionId ->
    evidence += potentialInterruptedRunRepairEvidenceForSession(
      sessionId = sessionId,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      subAgentHandleStoreFactory = subAgentHandleStoreFactory,
      runRecordStoreFactory = runRecordStoreFactory,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )
  }
  return evidence
}

internal fun hasPotentialInteractiveRunRepairWorkForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): Boolean = potentialInterruptedRunRepairEvidenceForSession(
  sessionId = sessionId,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
).isNotEmpty()

internal fun potentialInterruptedRunRepairTargetsForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): Set<RuntimeServiceTarget> = potentialInterruptedRunRepairEvidenceForSession(
  sessionId = sessionId,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
).mapTo(linkedSetOf(), InterruptedRunRepairEvidence::target)

internal fun potentialInterruptedRunRepairEvidenceForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
): List<InterruptedRunRepairEvidence> {
  val taskSnapshots = snapshotStoreFactory.forChatSession(sessionId)
    .load()
    ?.tasks
    .orEmpty()
  val evidence = mutableListOf<InterruptedRunRepairEvidence>()
  taskSnapshots
    .filter(::isPotentialRunRepairQueueTask)
    .forEach { taskSnapshot ->
      evidence += InterruptedRunRepairEvidence(
        sessionId = sessionId,
        kind = InterruptedRunRepairEvidenceKind.QUEUE_TASK,
        target = runtimeServiceTargetForTask(taskSnapshot.task),
        runId = runIdForTask(taskSnapshot),
        taskId = taskSnapshot.task.id,
      )
    }
  promptCheckpointStoreFactory.forChatSession(sessionId)
    .list()
    .filter(::isPotentialRunRepairCheckpoint)
    .forEach { checkpoint ->
      evidence += InterruptedRunRepairEvidence(
        sessionId = sessionId,
        kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
        target = runtimeServiceTargetForCheckpoint(
          checkpoint = checkpoint,
          taskSnapshots = taskSnapshots,
        ) ?: RuntimeServiceTarget.INTERACTIVE,
        runId = checkpoint.runId,
        taskId = checkpoint.taskId,
        detailId = checkpoint.checkpointId,
      )
    }
  subAgentHandleStoreFactory.forChatSession(sessionId)
    .list()
    .filter(::isPotentialDetachedSubAgentRepairHandle)
    .forEach { handle ->
      evidence += InterruptedRunRepairEvidence(
        sessionId = sessionId,
        kind = InterruptedRunRepairEvidenceKind.DETACHED_SUBAGENT_HANDLE,
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        runId = handle.parentRunId,
        taskId = handle.parentTaskId,
        detailId = handle.agentId,
      )
    }
  val runRecords = runRecordStoreFactory?.forChatSession(sessionId)
    ?.list()
    .orEmpty()
  runRecords
    .filter(::isPotentialRunRepairRecord)
    .forEach { record ->
      evidence += InterruptedRunRepairEvidence(
        sessionId = sessionId,
        kind = InterruptedRunRepairEvidenceKind.RUN_RECORD,
        target = runtimeServiceTargetForRunId(
          runId = record.runId,
          taskSnapshots = taskSnapshots,
        ) ?: RuntimeServiceTarget.INTERACTIVE,
        runId = record.runId,
        taskId = record.taskId,
      )
    }
  potentialRunRepairJournalTails(
    journalEntries = runEventJournalStoreFactory?.forChatSession(sessionId)?.list().orEmpty(),
    terminalRunIds = runRecords
      .filter { record -> record.lastResult != null }
      .mapTo(mutableSetOf(), PersistedAgentRunRecord::runId),
  ).forEach { entry ->
    evidence += InterruptedRunRepairEvidence(
      sessionId = sessionId,
      kind = InterruptedRunRepairEvidenceKind.JOURNAL_TAIL,
      target = runtimeServiceTargetForRunId(
        runId = entry.runId,
        taskSnapshots = taskSnapshots,
      ) ?: RuntimeServiceTarget.INTERACTIVE,
      runId = entry.runId,
      taskId = entry.taskId,
      detailId = entry.eventId,
    )
  }
  return evidence
}

internal fun potentialInterruptedRunRepairEvidence(
  context: Context,
): List<InterruptedRunRepairEvidence> {
  val appContext = context.applicationContext
  return potentialInterruptedRunRepairEvidence(
    chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
    promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext),
    subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(appContext),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
    runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
  )
}

internal fun startInterruptedRunRepairTargets(
  targets: Set<RuntimeServiceTarget>,
  startRepair: (RuntimeServiceTarget) -> Boolean,
): Boolean = INTERRUPTED_RUN_REPAIR_TARGET_WAKE_ORDER
  .filter(targets::contains)
  .map { target ->
    runCatching {
      startRepair(target)
    }.getOrDefault(false)
  }
  .all { started -> started }

private fun isPotentialRunRepairQueueTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): Boolean = taskSnapshot.lifecycleState !in TERMINAL_QUEUE_TASK_LIFECYCLES

private fun isPotentialRunRepairRecord(record: PersistedAgentRunRecord): Boolean {
  if (record.lastResult != null) {
    return false
  }
  return record.lastEvent != null || record.managedProcessIds.isNotEmpty()
}

private fun isPotentialRunRepairCheckpoint(
  checkpoint: PersistedPromptCheckpoint,
): Boolean = checkpoint.checkpointKind != PromptCheckpointKind.FINALIZATION_COMPLETE

private fun potentialRunRepairJournalTails(
  journalEntries: List<PersistedRunJournalEntry>,
  terminalRunIds: Set<String>,
): List<PersistedRunJournalEntry> {
  if (journalEntries.isEmpty()) {
    return emptyList()
  }
  val latestEntriesByRunId = linkedMapOf<String, PersistedRunJournalEntry>()
  journalEntries
    .filterNot { entry -> entry.runId in terminalRunIds }
    .forEach { entry ->
      latestEntriesByRunId[entry.runId] = entry
    }
  return latestEntriesByRunId.values.filter(::isPotentialRunRepairJournalTail)
}

private fun isPotentialRunRepairJournalTail(entry: PersistedRunJournalEntry): Boolean =
  when (entry.payload.kind) {
    PersistedAgentRunEventKind.LIFECYCLE ->
      TERMINAL_JOURNAL_LIFECYCLE_PHASES.none { phase ->
        entry.payload.phase?.equals(phase, ignoreCase = true) == true
      }
    PersistedAgentRunEventKind.ASSISTANT_PHASE ->
      entry.payload.phase?.equals(FINAL_ASSISTANT_PHASE, ignoreCase = true) != true &&
        entry.payload.isFinal != true
    PersistedAgentRunEventKind.CANCELLATION,
    PersistedAgentRunEventKind.CHECKPOINT,
    PersistedAgentRunEventKind.RECOVERY,
    -> false
    PersistedAgentRunEventKind.SUPPLEMENT,
    PersistedAgentRunEventKind.APPROVAL,
    PersistedAgentRunEventKind.SUBAGENT,
    PersistedAgentRunEventKind.TOOL_CALL,
    PersistedAgentRunEventKind.TOOL_RESULT,
    PersistedAgentRunEventKind.MEMORY_RETRIEVAL,
    PersistedAgentRunEventKind.MEMORY_WRITE,
    -> true
  }

private fun runtimeServiceTargetForCheckpoint(
  checkpoint: PersistedPromptCheckpoint,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
): RuntimeServiceTarget? = taskSnapshots
  .firstOrNull { taskSnapshot -> taskSnapshot.task.id == checkpoint.taskId }
  ?.let { taskSnapshot -> runtimeServiceTargetForTask(taskSnapshot.task) }

private fun runtimeServiceTargetForRunId(
  runId: String,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
): RuntimeServiceTarget? = taskSnapshots
  .firstOrNull { taskSnapshot -> runIdForTask(taskSnapshot) == runId }
  ?.let { taskSnapshot -> runtimeServiceTargetForTask(taskSnapshot.task) }

private fun runIdForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String = taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
  ?.takeIf(String::isNotBlank)
  ?: taskSnapshot.task.id

private fun isPotentialDetachedSubAgentRepairHandle(handle: SubAgentHandleState): Boolean =
  handle.snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED ||
    handle.snapshot.state == SubAgentExecutionState.BACKGROUND_RUNNING

private val TERMINAL_QUEUE_TASK_LIFECYCLES: Set<QueueTaskLifecycleState> = setOf(
  QueueTaskLifecycleState.COMPLETED,
  QueueTaskLifecycleState.FAILED,
  QueueTaskLifecycleState.CANCELLED,
)

private val TERMINAL_JOURNAL_LIFECYCLE_PHASES: Set<String> = setOf(
  "END",
  "ERROR",
  "CANCELLED",
)

private const val FINAL_ASSISTANT_PHASE: String = "FINAL_ANSWER"

private val INTERRUPTED_RUN_REPAIR_TARGET_WAKE_ORDER: List<RuntimeServiceTarget> = listOf(
  RuntimeServiceTarget.DETACHED_BACKGROUND,
  RuntimeServiceTarget.INTERACTIVE,
)

private fun scheduleWakeWorkName(scheduleId: String): String =
  "scheduled-task-wake-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(scheduleId)}"

internal const val REPAIR_WORK_NAME: String = "scheduled-task-repair"
internal const val PERIODIC_REPAIR_WORK_NAME: String = "scheduled-task-periodic-repair"
internal const val PERIODIC_REPAIR_INTERVAL_HOURS: Long = 1L
internal const val WORK_DATA_SCHEDULE_ID: String = "schedule_id"
internal const val WORK_DATA_SCHEDULED_FOR_EPOCH_MS: String = "scheduled_for_epoch_ms"
internal const val WORK_DATA_REPAIR_REASON: String = "repair_reason"
