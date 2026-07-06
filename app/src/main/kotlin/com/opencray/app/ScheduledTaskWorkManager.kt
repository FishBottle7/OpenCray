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
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import java.util.concurrent.TimeUnit

internal interface ScheduledWorkScheduler {
  fun scheduleWake(
    scheduleId: String,
    triggerAtEpochMs: Long,
  )

  fun cancel(scheduleId: String)

  fun enqueueRepair(reason: String) {
    enqueueRepair(reason, initialDelayMs = 0L)
  }

  fun enqueueRepair(
    reason: String,
    initialDelayMs: Long,
  )

  fun ensurePeriodicRepair()
}

internal object NoOpScheduledWorkScheduler : ScheduledWorkScheduler {
  override fun scheduleWake(
    scheduleId: String,
    triggerAtEpochMs: Long,
  ) = Unit

  override fun cancel(scheduleId: String) = Unit

  override fun enqueueRepair(
    reason: String,
    initialDelayMs: Long,
  ) = Unit

  override fun ensurePeriodicRepair() = Unit
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

  override fun enqueueRepair(
    reason: String,
    initialDelayMs: Long,
  ) {
    val normalizedDelayMs = initialDelayMs.coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<ScheduledTaskRepairWorker>()
      .setInputData(
        Data.Builder()
          .putString(WORK_DATA_REPAIR_REASON, reason)
          .build(),
      )
      .setConstraints(defaultConstraints())
      .setInitialDelay(normalizedDelayMs, TimeUnit.MILLISECONDS)
      .addTag(REPAIR_WORK_NAME)
      .build()
    workManager.enqueueUniqueWork(
      if (normalizedDelayMs > 0L) delayedRepairWorkName(reason) else REPAIR_WORK_NAME,
      if (normalizedDelayMs > 0L) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
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
      val nowEpochMs = System.currentTimeMillis()
      val hasDueCommands = plannedRepairWakeCommands(
        enabledSpecs = FileBackedScheduledTaskSpecStoreFactory
          .fromContext(applicationContext)
          .create()
          .listEnabled(),
        nowEpochMs = nowEpochMs,
        repairReason = reason,
      ).isNotEmpty()
      val interruptedRunRepairEvidence = potentialInterruptedRunRepairEvidence(applicationContext)
      val interruptedRunRepairTargets = dueInterruptedRunRepairTargets(
        evidence = interruptedRunRepairEvidence,
        nowEpochMs = nowEpochMs,
      )
      scheduleNextInterruptedRunRepairRetry(
        workScheduler = WorkManagerScheduledWorkScheduler.fromContext(applicationContext),
        evidence = interruptedRunRepairEvidence,
        nowEpochMs = nowEpochMs,
      )
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
  val repairAfterEpochMs: Long? = null,
  val managedProcessReconnectStatus: String? = null,
  val managedProcessReconnectRecoveryState: String? = null,
  val managedProcessReconnectAttemptCount: Int? = null,
  val runtimeExecutionOwnershipTier: String? = null,
  val durableRuntimeControllerId: String? = null,
  val managedProcessContinuationBasis: String? = null,
  val managedProcessRestoreScope: String? = null,
  val managedProcessRestoreDecision: String? = null,
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
    require(repairAfterEpochMs == null || repairAfterEpochMs >= 0L) {
      "InterruptedRunRepairEvidence repairAfterEpochMs must be >= 0."
    }
    require(managedProcessReconnectStatus == null || managedProcessReconnectStatus.isNotBlank()) {
      "InterruptedRunRepairEvidence managedProcessReconnectStatus must not be blank."
    }
    require(
      managedProcessReconnectRecoveryState == null ||
        managedProcessReconnectRecoveryState.isNotBlank(),
    ) {
      "InterruptedRunRepairEvidence managedProcessReconnectRecoveryState must not be blank."
    }
    require(
      managedProcessReconnectAttemptCount == null ||
        managedProcessReconnectAttemptCount > 0,
    ) {
      "InterruptedRunRepairEvidence managedProcessReconnectAttemptCount must be > 0."
    }
    require(runtimeExecutionOwnershipTier == null || runtimeExecutionOwnershipTier.isNotBlank()) {
      "InterruptedRunRepairEvidence runtimeExecutionOwnershipTier must not be blank."
    }
    require(durableRuntimeControllerId == null || durableRuntimeControllerId.isNotBlank()) {
      "InterruptedRunRepairEvidence durableRuntimeControllerId must not be blank."
    }
    require(managedProcessContinuationBasis == null || managedProcessContinuationBasis.isNotBlank()) {
      "InterruptedRunRepairEvidence managedProcessContinuationBasis must not be blank."
    }
    require(managedProcessRestoreScope == null || managedProcessRestoreScope.isNotBlank()) {
      "InterruptedRunRepairEvidence managedProcessRestoreScope must not be blank."
    }
    require(managedProcessRestoreDecision == null || managedProcessRestoreDecision.isNotBlank()) {
      "InterruptedRunRepairEvidence managedProcessRestoreDecision must not be blank."
    }
  }
}

internal enum class InterruptedRunRepairEvidenceKind {
  QUEUE_TASK,
  PROMPT_CHECKPOINT,
  DETACHED_SUBAGENT_HANDLE,
  RUN_RECORD,
  JOURNAL_TAIL,
  MANAGED_PROCESS_RECONNECT,
  RUNTIME_PROJECTION_WORK,
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
  processRegistryFactory: AgentProcessRegistryFactory? = null,
): Set<RuntimeServiceTarget> = potentialInterruptedRunRepairEvidence(
  chatSessionStore = chatSessionStore,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
  processRegistryFactory = processRegistryFactory,
).mapTo(linkedSetOf(), InterruptedRunRepairEvidence::target)

internal fun potentialInterruptedRunRepairEvidence(
  chatSessionStore: ChatSessionLocalStore,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
  processRegistryFactory: AgentProcessRegistryFactory? = null,
  runtimeServiceProjectionSnapshots: Map<RuntimeServiceTarget, RuntimeServiceProjectionSnapshot> =
    emptyMap(),
): List<InterruptedRunRepairEvidence> {
  val knownSessionIds = recoveryCandidateSessionIds(
    chatSessionStore = chatSessionStore,
    snapshotStoreFactory = snapshotStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentHandleStoreFactory = subAgentHandleStoreFactory,
    runRecordStoreFactory = runRecordStoreFactory,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    processRegistryFactory = processRegistryFactory,
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
      processRegistryFactory = processRegistryFactory,
    )
  }
  val sessionsWithDurableEvidence = evidence.mapTo(mutableSetOf()) { item -> item.sessionId }
  evidence += runtimeServiceProjectionRepairEvidence(runtimeServiceProjectionSnapshots)
    .filterNot { item -> item.sessionId in sessionsWithDurableEvidence }
  return evidence
}

internal fun hasPotentialInteractiveRunRepairWorkForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
  processRegistryFactory: AgentProcessRegistryFactory? = null,
): Boolean = potentialInterruptedRunRepairEvidenceForSession(
  sessionId = sessionId,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
  processRegistryFactory = processRegistryFactory,
).isNotEmpty()

internal fun potentialInterruptedRunRepairTargetsForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
  processRegistryFactory: AgentProcessRegistryFactory? = null,
): Set<RuntimeServiceTarget> = potentialInterruptedRunRepairEvidenceForSession(
  sessionId = sessionId,
  snapshotStoreFactory = snapshotStoreFactory,
  promptCheckpointStoreFactory = promptCheckpointStoreFactory,
  subAgentHandleStoreFactory = subAgentHandleStoreFactory,
  runRecordStoreFactory = runRecordStoreFactory,
  runEventJournalStoreFactory = runEventJournalStoreFactory,
  processRegistryFactory = processRegistryFactory,
).mapTo(linkedSetOf(), InterruptedRunRepairEvidence::target)

internal fun potentialInterruptedRunRepairEvidenceForSession(
  sessionId: String,
  snapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  subAgentHandleStoreFactory: SubAgentHandleStoreFactory,
  runRecordStoreFactory: AgentRunRecordStoreFactory? = null,
  runEventJournalStoreFactory: RunEventJournalStoreFactory? = null,
  processRegistryFactory: AgentProcessRegistryFactory? = null,
): List<InterruptedRunRepairEvidence> {
  val snapshotStore = snapshotStoreFactory.forChatSession(sessionId)
  val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
  val subAgentHandleStore = subAgentHandleStoreFactory.forChatSession(sessionId)
  val runRecordStore = runRecordStoreFactory?.forChatSession(sessionId)
  val runEventJournalStore = runEventJournalStoreFactory?.forChatSession(sessionId)
  val processRegistry = processRegistryFactory?.forChatSession(sessionId)
  val managedProcesses = processRegistry?.list().orEmpty()
  val taskSnapshots = repairPreflightTaskSnapshots(
    sessionId = sessionId,
    snapshotStore = snapshotStore,
    promptCheckpointStore = promptCheckpointStore,
    runRecordStore = runRecordStore,
    runEventJournalStore = runEventJournalStore,
    managedProcesses = managedProcesses,
  )
  val evidence = mutableListOf<InterruptedRunRepairEvidence>()
  taskSnapshots
    .filter(::isPotentialRunRepairQueueTask)
    .forEach { taskSnapshot ->
      managedProcessReconnectRepairEvidenceForQueueTask(
        sessionId = sessionId,
        taskSnapshot = taskSnapshot,
      )
        ?.let { reconnectEvidence -> evidence += reconnectEvidence }
        ?: run {
          evidence += InterruptedRunRepairEvidence(
            sessionId = sessionId,
            kind = InterruptedRunRepairEvidenceKind.QUEUE_TASK,
            target = runtimeServiceTargetForTask(taskSnapshot.task),
            runId = runIdForTask(taskSnapshot),
            taskId = taskSnapshot.task.id,
            runtimeExecutionOwnershipTier = runtimeExecutionOwnershipTierForTask(taskSnapshot),
            durableRuntimeControllerId = durableRuntimeControllerIdForTask(taskSnapshot),
          )
        }
    }
  promptCheckpointStore.list()
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
  subAgentHandleStore.list()
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
  val runRecords = runRecordStore
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
    journalEntries = runEventJournalStore?.list().orEmpty(),
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
  managedProcesses
    .filter(::isPotentialManagedProcessReconnectRepair)
    .forEach { process ->
      evidence += InterruptedRunRepairEvidence(
        sessionId = sessionId,
        kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
        target = runtimeServiceTargetForManagedProcess(
          process = process,
          taskSnapshots = taskSnapshots,
          runRecords = runRecords,
        ),
        runId = runIdForManagedProcess(
          process = process,
          taskSnapshots = taskSnapshots,
          runRecords = runRecords,
        ),
        taskId = process.taskId,
        detailId = process.processId,
        repairAfterEpochMs = managedProcessReconnectRetryAfterEpochMs(process),
        managedProcessReconnectStatus = managedProcessReconnectStatusForManagedProcess(process),
        managedProcessReconnectRecoveryState =
          managedProcessReconnectRecoveryStateForManagedProcess(process),
        managedProcessReconnectAttemptCount =
          managedProcessReconnectAttemptCountForManagedProcess(process),
        managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
        managedProcessRestoreScope = managedProcessRestoreScopeForManagedProcess(process),
        managedProcessRestoreDecision = managedProcessRestoreDecisionForManagedProcess(process),
        runtimeExecutionOwnershipTier = taskSnapshotForManagedProcess(
          process = process,
          taskSnapshots = taskSnapshots,
        )?.let(::runtimeExecutionOwnershipTierForTask),
        durableRuntimeControllerId = durableRuntimeControllerIdForManagedProcess(
          process = process,
          taskSnapshots = taskSnapshots,
        ),
      )
    }
  return evidence.withManagedProcessReconnectBackoff()
}

private fun repairPreflightTaskSnapshots(
  sessionId: String,
  snapshotStore: SessionQueueSnapshotStore,
  promptCheckpointStore: PromptCheckpointStore,
  runRecordStore: AgentRunRecordStore?,
  runEventJournalStore: RunEventJournalStore?,
  managedProcesses: List<ManagedProcessSnapshot>,
): List<SessionQueueTaskSnapshot> {
  val snapshot = snapshotStore.load() ?: return emptyList()
  if (runRecordStore == null || runEventJournalStore == null) {
    return snapshot.tasks
  }
  return RecoveryAwareQueueSnapshotStore(
    sessionId = sessionId,
    delegate = snapshotStore,
    runRecordStore = runRecordStore,
    runEventJournalStore = runEventJournalStore,
    promptCheckpointStore = promptCheckpointStore,
    managedProcessesProvider = { managedProcesses },
  ).restore(
    snapshot = snapshot,
    restoreEpochMs = System.currentTimeMillis(),
  )?.tasks.orEmpty()
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
    processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(
      context = appContext,
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    ),
    runtimeServiceProjectionSnapshots = runtimeServiceProjectionSnapshotsFromContext(appContext),
  )
}

private fun runtimeServiceProjectionSnapshotsFromContext(
  context: Context,
): Map<RuntimeServiceTarget, RuntimeServiceProjectionSnapshot> {
  val factory = FileBackedRuntimeServiceProjectionStoreFactory.fromContext(context)
  return RuntimeServiceTarget.entries
    .mapNotNull { target ->
      factory.create(target).loadSnapshot()?.let { snapshot -> target to snapshot }
    }
    .toMap()
}

internal fun dueInterruptedRunRepairTargets(
  evidence: List<InterruptedRunRepairEvidence>,
  nowEpochMs: Long,
): Set<RuntimeServiceTarget> = dueInterruptedRunRepairEvidence(
  evidence = evidence,
  nowEpochMs = nowEpochMs,
)
  .mapTo(linkedSetOf(), InterruptedRunRepairEvidence::target)

internal fun dueInterruptedRunRepairEvidence(
  evidence: List<InterruptedRunRepairEvidence>,
  nowEpochMs: Long,
): List<InterruptedRunRepairEvidence> = evidence
  .filter { item -> item.repairAfterEpochMs == null || item.repairAfterEpochMs <= nowEpochMs }

internal fun nextInterruptedRunRepairDelayMs(
  evidence: List<InterruptedRunRepairEvidence>,
  nowEpochMs: Long,
): Long? = nextInterruptedRunRepairAfterEpochMs(
  evidence = evidence,
  nowEpochMs = nowEpochMs,
)?.let { repairAfterEpochMs -> repairAfterEpochMs - nowEpochMs }

internal data class InterruptedRunRepairRetry(
  val repairAfterEpochMs: Long,
  val repairReason: String,
)

internal fun nextInterruptedRunRepairAfterEpochMs(
  evidence: List<InterruptedRunRepairEvidence>,
  nowEpochMs: Long,
): Long? = nextInterruptedRunRepairRetry(
  evidence = evidence,
  nowEpochMs = nowEpochMs,
)?.repairAfterEpochMs

internal fun nextInterruptedRunRepairRetry(
  evidence: List<InterruptedRunRepairEvidence>,
  nowEpochMs: Long,
): InterruptedRunRepairRetry? = evidence
  .mapNotNull { item ->
    item.repairAfterEpochMs
      ?.takeIf { candidateEpochMs -> candidateEpochMs > nowEpochMs }
      ?.let { candidateEpochMs ->
        InterruptedRunRepairRetry(
          repairAfterEpochMs = candidateEpochMs,
          repairReason = delayedRepairReasonForEvidence(item),
        )
      }
  }
  .minWithOrNull(
    compareBy<InterruptedRunRepairRetry> { retry -> retry.repairAfterEpochMs }
      .thenBy { retry -> delayedRepairReasonPriority(retry.repairReason) },
  )

private fun delayedRepairReasonForEvidence(
  evidence: InterruptedRunRepairEvidence,
): String = when (evidence.kind) {
  InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT ->
    ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT
  else -> ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY
}

private fun delayedRepairReasonPriority(repairReason: String): Int =
  when (repairReason) {
    ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT -> 0
    else -> 1
  }

internal fun scheduleNextInterruptedRunRepairRetry(
  workScheduler: ScheduledWorkScheduler,
  nextRepairAfterEpochMs: Long?,
  repairReason: String = ScheduledTaskRepairReasons.INTERRUPTED_RUN_RETRY,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  val retryAtEpochMs = nextRepairAfterEpochMs
    ?.takeIf { repairAfterEpochMs -> repairAfterEpochMs > nowEpochMs }
    ?: return false
  workScheduler.enqueueRepair(
    reason = repairReason,
    initialDelayMs = retryAtEpochMs - nowEpochMs,
  )
  return true
}

internal fun scheduleNextInterruptedRunRepairRetry(
  workScheduler: ScheduledWorkScheduler,
  evidence: List<InterruptedRunRepairEvidence>,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  val nextRetry = nextInterruptedRunRepairRetry(
    evidence = evidence,
    nowEpochMs = nowEpochMs,
  ) ?: return false
  return scheduleNextInterruptedRunRepairRetry(
    workScheduler = workScheduler,
    nextRepairAfterEpochMs = nextRetry.repairAfterEpochMs,
    repairReason = nextRetry.repairReason,
    nowEpochMs = nowEpochMs,
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

private fun managedProcessReconnectRepairEvidenceForQueueTask(
  sessionId: String,
  taskSnapshot: SessionQueueTaskSnapshot,
): List<InterruptedRunRepairEvidence>? {
  val metadata = taskSnapshot.task.metadata
  val recoveryAction = metadata[RunLifecycleMetadataKeys.RECOVERY_ACTION]
    ?.trim()
    ?.takeIf(String::isNotBlank)
  if (!recoveryAction.equals(MANAGED_PROCESS_RECONNECT_RECOVERY_ACTION, ignoreCase = true)) {
    return null
  }
  val target = runtimeServiceTargetForTask(taskSnapshot.task)
  val runId = runIdForTask(taskSnapshot)
  val processIds = managedProcessReconnectProcessIdsForTask(taskSnapshot)
  val repairAfterEpochMs = managedProcessReconnectRetryAfterEpochMsForTask(taskSnapshot)
  val processDetailIds: List<String?> = processIds.takeIf { ids -> ids.isNotEmpty() }
    ?: listOf(null)
  return processDetailIds.map { processId ->
    InterruptedRunRepairEvidence(
      sessionId = sessionId,
      kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
      target = target,
      runId = runId,
      taskId = taskSnapshot.task.id,
      detailId = processId,
      repairAfterEpochMs = repairAfterEpochMs,
      managedProcessReconnectStatus = managedProcessReconnectStatusForTask(taskSnapshot),
      managedProcessReconnectRecoveryState = managedProcessReconnectRecoveryStateForTask(taskSnapshot),
      managedProcessReconnectAttemptCount = managedProcessReconnectAttemptCountForTask(taskSnapshot),
      runtimeExecutionOwnershipTier = runtimeExecutionOwnershipTierForTask(taskSnapshot),
      durableRuntimeControllerId = durableRuntimeControllerIdForTask(taskSnapshot),
      managedProcessContinuationBasis = managedProcessContinuationBasisForTask(taskSnapshot)
        ?: ManagedProcessContinuationBases.RECONNECT_HOLD,
      managedProcessRestoreScope = managedProcessRestoreScopeForTask(taskSnapshot),
      managedProcessRestoreDecision = managedProcessRestoreDecisionForTask(taskSnapshot),
    )
  }
}

private fun runtimeServiceProjectionRepairEvidence(
  snapshotsByTarget: Map<RuntimeServiceTarget, RuntimeServiceProjectionSnapshot>,
): List<InterruptedRunRepairEvidence> = snapshotsByTarget.flatMap { (target, snapshot) ->
  runtimeServiceProjectionRepairSessionIds(snapshot).map { sessionId ->
    InterruptedRunRepairEvidence(
      sessionId = sessionId,
      kind = InterruptedRunRepairEvidenceKind.RUNTIME_PROJECTION_WORK,
      target = target,
      runtimeExecutionOwnershipTier = RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
      durableRuntimeControllerId = durableRuntimeControllerIdForProjection(snapshot),
    )
  }
}

private fun runtimeServiceProjectionRepairSessionIds(
  snapshot: RuntimeServiceProjectionSnapshot,
): List<String> = buildSet {
  addRuntimeProjectionSessionIds(snapshot.runtimeOwnerWorkSummary.activeSessionIds)
  addRuntimeProjectionSessionIds(snapshot.runtimeOwnerWorkSummary.pendingWorkSessionIds)
  addRuntimeProjectionSessionIds(snapshot.runtimeOwnerWorkSummary.liveManagedProcessSessionIds)
  addRuntimeProjectionSessionIds(snapshot.runtimeOwnerWorkSummary.liveSubAgentSessionIds)
}.toList()

private fun MutableSet<String>.addRuntimeProjectionSessionIds(sessionIds: List<String>) {
  sessionIds.mapNotNullTo(this) { sessionId ->
    sessionId.trim().takeIf(String::isNotBlank)
  }
}

private fun durableRuntimeControllerIdForProjection(
  snapshot: RuntimeServiceProjectionSnapshot,
): String? = snapshot.runtimeControllerLifecycle
  ?.durableControllerId
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?: snapshot.runtimeOwnerLifecycle.durableRuntimeControllerId
    .trim()
    .takeIf(String::isNotBlank)

private fun runtimeExecutionOwnershipTierForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER]
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?: RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS

private fun durableRuntimeControllerIdForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun durableRuntimeControllerIdForManagedProcess(
  process: ManagedProcessSnapshot,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
): String? = taskSnapshotForManagedProcess(
  process = process,
  taskSnapshots = taskSnapshots,
)?.let(::durableRuntimeControllerIdForTask)
  ?: process.metadata[MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY]
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun taskSnapshotForManagedProcess(
  process: ManagedProcessSnapshot,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
): SessionQueueTaskSnapshot? = taskSnapshots.firstOrNull { taskSnapshot ->
  taskSnapshot.task.id == process.taskId ||
    process.processId in managedProcessReconnectProcessIdsForTask(taskSnapshot)
}

private fun managedProcessReconnectProcessIdsForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): List<String> = taskSnapshot.task.metadata[
  RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS
]
  ?.split(",")
  ?.mapNotNull { processId -> processId.trim().takeIf(String::isNotBlank) }
  .orEmpty()

private fun managedProcessReconnectRetryAfterEpochMsForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): Long? = taskSnapshot.task.metadata[
  RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS
]
  ?.trim()
  ?.toLongOrNull()

private fun managedProcessReconnectStatusForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_STATUS]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun managedProcessReconnectRecoveryStateForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RECOVERY_STATE]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun managedProcessReconnectAttemptCountForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): Int? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_ATTEMPT_COUNT]
  ?.trim()
  ?.toIntOrNull()
  ?.takeIf { attempt -> attempt > 0 }

private fun managedProcessContinuationBasisForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_CONTINUATION_BASIS]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun managedProcessRestoreScopeForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_SCOPE]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun managedProcessRestoreDecisionForTask(
  taskSnapshot: SessionQueueTaskSnapshot,
): String? = taskSnapshot.task.metadata[RunLifecycleMetadataKeys.MANAGED_PROCESS_RESTORE_DECISION]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun managedProcessRestoreScopeForManagedProcess(
  process: ManagedProcessSnapshot,
): String? = process.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun managedProcessRestoreDecisionForManagedProcess(
  process: ManagedProcessSnapshot,
): String? = process.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY]
  ?.trim()
  ?.takeIf(String::isNotBlank)

private fun List<InterruptedRunRepairEvidence>.withManagedProcessReconnectBackoff():
  List<InterruptedRunRepairEvidence> {
  val reconnectEvidence = filter { evidence ->
    evidence.kind == InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT &&
      evidence.repairAfterEpochMs != null
  }
  if (reconnectEvidence.isEmpty()) {
    return this
  }
  return map { evidence ->
    if (evidence.kind == InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT) {
      evidence
    } else {
      reconnectBackoffEvidenceForEvidence(
        evidence = evidence,
        reconnectEvidence = reconnectEvidence,
      )?.let { reconnect ->
        evidence.withReconnectBackoffEvidence(reconnect)
      } ?: evidence
    }
  }
}

private fun reconnectBackoffEvidenceForEvidence(
  evidence: InterruptedRunRepairEvidence,
  reconnectEvidence: List<InterruptedRunRepairEvidence>,
): InterruptedRunRepairEvidence? = reconnectEvidence
  .filter { reconnect -> reconnect.sharesRunOrTaskIdentityWith(evidence) }
  .filter { reconnect -> reconnect.repairAfterEpochMs != null }
  .minWithOrNull(
    compareBy<InterruptedRunRepairEvidence> { reconnect ->
      reconnect.repairAfterEpochMs ?: Long.MAX_VALUE
    }.thenBy { reconnect -> reconnect.detailId.orEmpty() },
  )

private fun InterruptedRunRepairEvidence.withReconnectBackoffEvidence(
  reconnect: InterruptedRunRepairEvidence,
): InterruptedRunRepairEvidence {
  val reconnectRepairAfterEpochMs = reconnect.repairAfterEpochMs ?: return this
  return copy(
    repairAfterEpochMs = maxOf(repairAfterEpochMs ?: 0L, reconnectRepairAfterEpochMs),
    managedProcessReconnectStatus = managedProcessReconnectStatus
      ?: reconnect.managedProcessReconnectStatus,
    managedProcessReconnectRecoveryState = managedProcessReconnectRecoveryState
      ?: reconnect.managedProcessReconnectRecoveryState,
    managedProcessReconnectAttemptCount = managedProcessReconnectAttemptCount
      ?: reconnect.managedProcessReconnectAttemptCount,
    runtimeExecutionOwnershipTier = runtimeExecutionOwnershipTier
      ?: reconnect.runtimeExecutionOwnershipTier,
    durableRuntimeControllerId = durableRuntimeControllerId
      ?: reconnect.durableRuntimeControllerId,
    managedProcessContinuationBasis = managedProcessContinuationBasis
      ?: reconnect.managedProcessContinuationBasis,
    managedProcessRestoreScope = managedProcessRestoreScope
      ?: reconnect.managedProcessRestoreScope,
    managedProcessRestoreDecision = managedProcessRestoreDecision
      ?: reconnect.managedProcessRestoreDecision,
  )
}

private fun InterruptedRunRepairEvidence.sharesRunOrTaskIdentityWith(
  other: InterruptedRunRepairEvidence,
): Boolean {
  if (sessionId != other.sessionId) {
    return false
  }
  val sameRunId = runId != null &&
    other.runId != null &&
    runId == other.runId
  val sameTaskId = taskId != null &&
    other.taskId != null &&
    taskId == other.taskId
  return sameRunId || sameTaskId
}

private fun isPotentialRunRepairRecord(record: PersistedAgentRunRecord): Boolean {
  if (record.lastResult != null) {
    return false
  }
  return record.lastEvent != null || record.managedProcessIds.isNotEmpty()
}

private fun isPotentialManagedProcessReconnectRepair(
  process: ManagedProcessSnapshot,
): Boolean {
  if (process.status != ManagedProcessStatus.RUNNING) {
    return false
  }
  val reconnectEvidence = process.reconnectSnapshotEvidence()
  val recoveryState = reconnectEvidence.recoveryState
  val retryable = reconnectEvidence.retryable
  return recoveryState == MANAGED_PROCESS_RECONNECT_RECOVERY_STATE_RETRY_SCHEDULED ||
    (
      reconnectEvidence.status == MANAGED_PROCESS_RECONNECT_STATUS_CONNECTING &&
        retryable == true
      )
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

private fun runtimeServiceTargetForTaskId(
  taskId: String,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
): RuntimeServiceTarget? = taskSnapshots
  .firstOrNull { taskSnapshot -> taskSnapshot.task.id == taskId }
  ?.let { taskSnapshot -> runtimeServiceTargetForTask(taskSnapshot.task) }

private fun runtimeServiceTargetForManagedProcess(
  process: ManagedProcessSnapshot,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
  runRecords: List<PersistedAgentRunRecord>,
): RuntimeServiceTarget =
  runtimeServiceTargetForTaskId(
    taskId = process.taskId,
    taskSnapshots = taskSnapshots,
  )
    ?: runRecords
      .firstOrNull { record -> process.processId in record.managedProcessIds }
      ?.let { record ->
        runtimeServiceTargetForRunId(
          runId = record.runId,
          taskSnapshots = taskSnapshots,
        )
      }
    ?: RuntimeServiceTarget.INTERACTIVE

private fun runIdForManagedProcess(
  process: ManagedProcessSnapshot,
  taskSnapshots: List<SessionQueueTaskSnapshot>,
  runRecords: List<PersistedAgentRunRecord>,
): String? =
  taskSnapshots
    .firstOrNull { taskSnapshot -> taskSnapshot.task.id == process.taskId }
    ?.let(::runIdForTask)
    ?: runRecords
      .firstOrNull { record ->
        record.taskId == process.taskId || process.processId in record.managedProcessIds
      }
      ?.runId

private fun managedProcessReconnectRetryAfterEpochMs(
  process: ManagedProcessSnapshot,
): Long? = process.reconnectSnapshotEvidence().retryAfterEpochMs

private fun managedProcessReconnectStatusForManagedProcess(
  process: ManagedProcessSnapshot,
): String? = process.reconnectSnapshotEvidence().status

private fun managedProcessReconnectRecoveryStateForManagedProcess(
  process: ManagedProcessSnapshot,
): String? = process.reconnectSnapshotEvidence().recoveryState

private fun managedProcessReconnectAttemptCountForManagedProcess(
  process: ManagedProcessSnapshot,
): Int? = process.reconnectSnapshotEvidence().attemptCount
  ?.takeIf { attempt -> attempt > 0 }

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
private const val MANAGED_PROCESS_RECONNECT_RECOVERY_ACTION: String = "resume_reconnect_process"
private const val MANAGED_PROCESS_RECONNECT_RECOVERY_STATE_RETRY_SCHEDULED: String = "retry_scheduled"
private const val MANAGED_PROCESS_RECONNECT_STATUS_CONNECTING: String = "connecting"

private val INTERRUPTED_RUN_REPAIR_TARGET_WAKE_ORDER: List<RuntimeServiceTarget> = listOf(
  RuntimeServiceTarget.DETACHED_BACKGROUND,
  RuntimeServiceTarget.INTERACTIVE,
)

private fun scheduleWakeWorkName(scheduleId: String): String =
  "scheduled-task-wake-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(scheduleId)}"

internal fun delayedRepairWorkName(reason: String): String {
  val normalizedReason = reason.trim().ifBlank { ScheduledTaskRepairReasons.WORK_MANAGER }
  return "$DELAYED_REPAIR_WORK_NAME-" +
    FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(normalizedReason)
}

internal const val REPAIR_WORK_NAME: String = "scheduled-task-repair"
internal const val DELAYED_REPAIR_WORK_NAME: String = "scheduled-task-delayed-repair"
internal const val PERIODIC_REPAIR_WORK_NAME: String = "scheduled-task-periodic-repair"
internal const val PERIODIC_REPAIR_INTERVAL_HOURS: Long = 1L
internal const val WORK_DATA_SCHEDULE_ID: String = "schedule_id"
internal const val WORK_DATA_SCHEDULED_FOR_EPOCH_MS: String = "scheduled_for_epoch_ms"
internal const val WORK_DATA_REPAIR_REASON: String = "repair_reason"
