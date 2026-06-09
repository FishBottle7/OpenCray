package com.opencray.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatTranscriptRole
import java.util.UUID

internal object ScheduledTaskMetadataKeys {
  const val SCHEDULE_ID: String = "_host.scheduleId"
  const val SCHEDULE_RUN_ID: String = "_host.scheduleRunId"
  const val SCHEDULE_TITLE: String = "_host.scheduleTitle"
  const val SCHEDULE_TRIGGER_REASON: String = "_host.scheduleTriggerReason"
  const val SCHEDULE_TRIGGERED_AT_EPOCH_MS: String = "_host.scheduleTriggeredAtEpochMs"
  const val SCHEDULE_WORKING_DIRECTORY: String = "_host.scheduleWorkingDirectory"
}

internal object ScheduledTaskTriggerReasons {
  const val ALARM: String = "alarm"
  const val MANUAL: String = "manual"
  const val REPAIR: String = "repair"
  const val WORK_MANAGER: String = "work_manager"
}

internal object ScheduledTaskRepairReasons {
  const val APP_START: String = "app_start"
  const val BOOT_COMPLETED: String = "boot_completed"
  const val PACKAGE_REPLACED: String = "package_replaced"
  const val WORK_MANAGER: String = "work_manager"
  const val PERIODIC: String = "periodic"
  const val BROADCAST: String = "broadcast"
}

internal const val SCHEDULED_TASK_NOTIFICATION_SNOOZE_DELAY_MS: Long = 15L * 60L * 1_000L

internal data class ScheduledTaskWakeCommand(
  val scheduleId: String,
  val scheduleRunId: String,
  val triggeredAtEpochMs: Long,
  val triggerReason: String,
  val targetSessionId: String? = null,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskWakeCommand scheduleId must not be blank." }
    require(scheduleRunId.isNotBlank()) { "ScheduledTaskWakeCommand scheduleRunId must not be blank." }
    require(triggerReason.isNotBlank()) { "ScheduledTaskWakeCommand triggerReason must not be blank." }
    require(triggeredAtEpochMs >= 0L) {
      "ScheduledTaskWakeCommand triggeredAtEpochMs must be >= 0."
    }
  }
}

internal data class ScheduledTaskDispatchOutcome(
  val result: ScheduledTaskRunResult,
  val scheduleId: String,
  val scheduleRunId: String,
  val sessionId: String? = null,
  val createdRunId: String? = null,
  val createdTaskId: String? = null,
  val failureReason: String? = null,
)

internal interface ScheduledTriggerRegistrar {
  fun syncSpec(spec: ScheduledTaskSpec)

  fun syncAll(specs: List<ScheduledTaskSpec>)

  fun cancel(scheduleId: String)
}

internal object NoOpScheduledTriggerRegistrar : ScheduledTriggerRegistrar {
  override fun syncSpec(spec: ScheduledTaskSpec) = Unit

  override fun syncAll(specs: List<ScheduledTaskSpec>) = Unit

  override fun cancel(scheduleId: String) = Unit
}

internal fun resyncEnabledScheduledTasks(
  specStore: ScheduledTaskSpecStore,
  triggerRegistrar: ScheduledTriggerRegistrar,
  triggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
) {
  val allSpecs = specStore.list()
  val enabledSpecs = allSpecs.filter(ScheduledTaskSpec::enabled)
  val enabledScheduleIds = enabledSpecs
    .map(ScheduledTaskSpec::scheduleId)
    .toCollection(linkedSetOf())
  val previouslySyncedScheduleIds = triggerSyncStateStore.loadScheduleIds()
  val scheduleIdsToCancel = linkedSetOf<String>().apply {
    addAll(
      allSpecs
        .filterNot(ScheduledTaskSpec::enabled)
        .map(ScheduledTaskSpec::scheduleId),
    )
    addAll(previouslySyncedScheduleIds - enabledScheduleIds)
  }
  scheduleIdsToCancel.forEach(triggerRegistrar::cancel)
  triggerRegistrar.syncAll(enabledSpecs)
  if (enabledScheduleIds.isEmpty()) {
    triggerSyncStateStore.clear()
  } else {
    triggerSyncStateStore.replaceScheduleIds(enabledScheduleIds)
  }
}

internal data class ScheduledAlarmRequest(
  val scheduleId: String,
  val triggerAtEpochMs: Long,
  val exact: Boolean,
)

internal interface ScheduledAlarmScheduler {
  fun schedule(request: ScheduledAlarmRequest)

  fun cancel(scheduleId: String)
}

internal class DefaultScheduledTriggerRegistrar(
  private val alarmScheduler: ScheduledAlarmScheduler,
  private val workScheduler: ScheduledWorkScheduler,
  private val clock: () -> Long = System::currentTimeMillis,
) : ScheduledTriggerRegistrar {
  override fun syncSpec(spec: ScheduledTaskSpec) {
    cancel(spec.scheduleId)
    if (!spec.enabled) {
      return
    }
    val nowEpochMs = clock()
    val nextAtEpochMs = nextScheduledTriggerAtEpochMs(spec, nowEpochMs) ?: run {
      return
    }
    when (spec.trigger) {
      is ScheduledTrigger.At,
      is ScheduledTrigger.After,
      is ScheduledTrigger.Recurrence,
      -> {
        alarmScheduler.schedule(
          ScheduledAlarmRequest(
            scheduleId = spec.scheduleId,
            triggerAtEpochMs = nextAtEpochMs,
            exact = requiresExactScheduling(spec.trigger),
          ),
        )
      }
    }
  }

  override fun syncAll(specs: List<ScheduledTaskSpec>) {
    specs.forEach(::syncSpec)
  }

  override fun cancel(scheduleId: String) {
    alarmScheduler.cancel(scheduleId)
    workScheduler.cancel(scheduleId)
  }
}

internal class AlarmManagerScheduledAlarmScheduler(
  private val context: Context,
  private val alarmManager: AlarmManager,
) : ScheduledAlarmScheduler {
  override fun schedule(request: ScheduledAlarmRequest) {
    val pendingIntent = scheduledTaskWakeReceiverPendingIntent(
      context = context,
      scheduleId = request.scheduleId,
      scheduledForEpochMs = request.triggerAtEpochMs,
      createIfMissing = true,
    ) ?: return
    when {
      request.exact && canScheduleExactAlarms(alarmManager) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
        alarmManager.setExactAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          request.triggerAtEpochMs,
          pendingIntent,
        )

      request.exact && canScheduleExactAlarms(alarmManager) ->
        alarmManager.setExact(
          AlarmManager.RTC_WAKEUP,
          request.triggerAtEpochMs,
          pendingIntent,
        )

      Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
        alarmManager.setAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP,
          request.triggerAtEpochMs,
          pendingIntent,
        )

      else ->
        alarmManager.set(
          AlarmManager.RTC_WAKEUP,
          request.triggerAtEpochMs,
          pendingIntent,
        )
    }
  }

  override fun cancel(scheduleId: String) {
    val pendingIntent = scheduledTaskWakeReceiverPendingIntent(
      context = context,
      scheduleId = scheduleId,
      scheduledForEpochMs = NO_SCHEDULED_EPOCH_MS,
      createIfMissing = false,
    ) ?: return
    alarmManager.cancel(pendingIntent)
    pendingIntent.cancel()
  }

  companion object {
    fun fromContext(context: Context): AlarmManagerScheduledAlarmScheduler =
      AlarmManagerScheduledAlarmScheduler(
        context = context.applicationContext,
        alarmManager = checkNotNull(
          context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager,
        ) {
          "AlarmManager is unavailable."
        },
      )
  }
}

internal class ScheduledTaskDispatcher(
  private val hostAccess: RuntimeSessionDirectoryAccess,
  private val chatSessionStore: ChatSessionLocalStore,
  private val safetySettingsFacade: SafetySettingsFacade,
  private val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  private val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  private val localizedContext: Context,
  private val assistantPlaceholderTextProvider: () -> String = {
    localizedContext.getText(org.opencray.app.R.string.chat_agent_thinking).toString()
  },
  private val specStore: ScheduledTaskSpecStore,
  private val runRecordStore: ScheduledTaskRunRecordStore,
  private val triggerRegistrar: ScheduledTriggerRegistrar,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun dispatch(command: ScheduledTaskWakeCommand): ScheduledTaskDispatchOutcome {
    val existing = runRecordStore.get(command.scheduleRunId)
    if (existing != null &&
      existing.result != ScheduledTaskRunResult.TRIGGERED
    ) {
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.SKIPPED_DUPLICATE,
        scheduleId = command.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = existing.sessionId,
        createdRunId = existing.createdRunId,
        createdTaskId = existing.createdTaskId,
        failureReason = existing.failureReason,
      )
    }

    var spec = specStore.get(command.scheduleId) ?: run {
      recordFailure(
        command = command,
        sessionId = command.targetSessionId ?: UNRESOLVED_SESSION_ID,
        result = ScheduledTaskRunResult.FAILED_MISSING_SPEC,
        failureReason = "schedule_not_found",
      )
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.FAILED_MISSING_SPEC,
        scheduleId = command.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = command.targetSessionId,
        failureReason = "schedule_not_found",
      )
    }
    val nowForDispatchEpochMs = maxOf(clock(), command.triggeredAtEpochMs)
    spec = clearSnoozeForDispatchIfNeeded(
      spec = spec,
      command = command,
      nowEpochMs = nowForDispatchEpochMs,
    )
    if (!spec.enabled) {
      triggerRegistrar.cancel(spec.scheduleId)
      recordFailure(
        command = command,
        sessionId = spec.sessionId,
        result = ScheduledTaskRunResult.FAILED_DISABLED,
        failureReason = "schedule_disabled",
      )
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.FAILED_DISABLED,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        failureReason = "schedule_disabled",
      )
    }
    val snoozedUntilEpochMs = spec.snoozedUntilEpochMs
    if (snoozedUntilEpochMs != null && snoozedUntilEpochMs > nowForDispatchEpochMs) {
      triggerRegistrar.syncSpec(spec)
      val record = ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = spec.scheduleId,
        sessionId = spec.sessionId,
        triggerReason = command.triggerReason,
        triggeredAtEpochMs = command.triggeredAtEpochMs,
        result = ScheduledTaskRunResult.SKIPPED_SNOOZED,
        failureReason = "schedule_snoozed",
        updatedAtEpochMs = clock(),
      )
      runRecordStore.upsert(record)
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.SKIPPED_SNOOZED,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        failureReason = "schedule_snoozed",
      )
    }
    val targetSessionId = command.targetSessionId
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (targetSessionId != null && targetSessionId != spec.sessionId) {
      recordFailure(
        command = command,
        sessionId = spec.sessionId,
        result = ScheduledTaskRunResult.FAILED_SESSION_MISMATCH,
        failureReason = "target_session_mismatch",
      )
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.FAILED_SESSION_MISMATCH,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        failureReason = "target_session_mismatch",
      )
    }
    if (chatSessionStore.loadSession(spec.sessionId) == null) {
      recordFailure(
        command = command,
        sessionId = spec.sessionId,
        result = ScheduledTaskRunResult.FAILED_MISSING_SESSION,
        failureReason = "session_not_found",
      )
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.FAILED_MISSING_SESSION,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        failureReason = "session_not_found",
      )
    }

    val session = hostAccess.session(spec.sessionId)
    if (
      spec.policy.conflictPolicy == ScheduledConflictPolicy.SKIP_IF_SESSION_BUSY &&
      sessionIsBusy(session, spec.sessionId)
    ) {
      triggerRegistrar.syncSpec(spec)
      val record = ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = spec.scheduleId,
        sessionId = spec.sessionId,
        triggerReason = command.triggerReason,
        triggeredAtEpochMs = command.triggeredAtEpochMs,
        result = ScheduledTaskRunResult.SKIPPED_SESSION_BUSY,
        failureReason = "session_busy",
        updatedAtEpochMs = clock(),
      )
      runRecordStore.upsert(record)
      return ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.SKIPPED_SESSION_BUSY,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        failureReason = "session_busy",
      )
    }
    if (spec.policy.conflictPolicy == ScheduledConflictPolicy.CANCEL_OLDER_WAITING_TRIGGER) {
      cancelOlderWaitingScheduledRuns(session, spec.scheduleId)
    }

    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = spec.scheduleId,
        sessionId = spec.sessionId,
        triggerReason = command.triggerReason,
        triggeredAtEpochMs = command.triggeredAtEpochMs,
        result = ScheduledTaskRunResult.TRIGGERED,
        updatedAtEpochMs = clock(),
      ),
    )

    val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
    val now = maxOf(clock(), command.triggeredAtEpochMs)
    val task = AgentTask(
      id = "scheduled-${spec.sessionId}-${UUID.randomUUID().toString().take(8)}",
      type = AgentTaskType.PROMPT,
      input = spec.payload.prompt.trim(),
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "SCHEDULED_TASK_ALLOW",
      ),
      createdAtEpochMs = now,
      metadata = scheduledTaskMetadata(
        spec = spec,
        command = command,
        pendingMessageId = pendingMessageId,
      ),
    )

    return try {
      val submission = session.submitTask(task)
      try {
        chatSessionStore.appendSubmittedTurn(
          sessionId = spec.sessionId,
          userText = spec.payload.prompt.trim(),
          assistantMessageId = pendingMessageId,
          assistantPlaceholderText = assistantPlaceholderTextProvider(),
        )
      } catch (throwable: Throwable) {
        session.requestCancel(submission.taskId)
        throw throwable
      }
      session.ensureProcessing()
      triggerRegistrar.syncSpec(spec)
      runRecordStore.upsert(
        ScheduledTaskRunRecord(
          scheduleRunId = command.scheduleRunId,
          scheduleId = spec.scheduleId,
          sessionId = spec.sessionId,
          triggerReason = command.triggerReason,
          triggeredAtEpochMs = command.triggeredAtEpochMs,
          acceptedAtEpochMs = submission.acceptedAtEpochMs,
          createdRunId = submission.runId,
          createdTaskId = submission.taskId,
          result = ScheduledTaskRunResult.ACCEPTED,
          updatedAtEpochMs = submission.acceptedAtEpochMs,
        ),
      )
      ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.ACCEPTED,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        createdRunId = submission.runId,
        createdTaskId = submission.taskId,
      )
    } catch (throwable: Throwable) {
      triggerRegistrar.syncSpec(spec)
      val failureReason = throwable.message?.trim()?.takeIf(String::isNotBlank) ?: "dispatch_failed"
      runRecordStore.upsert(
        ScheduledTaskRunRecord(
          scheduleRunId = command.scheduleRunId,
          scheduleId = spec.scheduleId,
          sessionId = spec.sessionId,
          triggerReason = command.triggerReason,
          triggeredAtEpochMs = command.triggeredAtEpochMs,
          result = ScheduledTaskRunResult.FAILED_DISPATCH,
          failureReason = failureReason,
          updatedAtEpochMs = clock(),
        ),
      )
      ScheduledTaskDispatchOutcome(
        result = ScheduledTaskRunResult.FAILED_DISPATCH,
        scheduleId = spec.scheduleId,
        scheduleRunId = command.scheduleRunId,
        sessionId = spec.sessionId,
        failureReason = failureReason,
      )
    }
  }

  private fun sessionIsBusy(
    session: OpenCrayRuntimeSessionAccess,
    sessionId: String,
  ): Boolean = session.hasPendingWork() ||
    chatSessionStore.loadPendingUserInputs(sessionId).isNotEmpty()

  private fun cancelOlderWaitingScheduledRuns(
    session: OpenCrayRuntimeSessionAccess,
    scheduleId: String,
  ) {
    session.snapshot().tasks
      .filter { snapshot ->
        snapshot.task.metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID] == scheduleId &&
          snapshot.lifecycleState in WAITING_LIFECYCLE_STATES
      }
      .forEach { snapshot ->
        session.requestCancel(snapshot.task.id)
      }
  }

  private fun recordFailure(
    command: ScheduledTaskWakeCommand,
    sessionId: String,
    result: ScheduledTaskRunResult,
    failureReason: String,
  ) {
    runRecordStore.upsert(
      ScheduledTaskRunRecord(
        scheduleRunId = command.scheduleRunId,
        scheduleId = command.scheduleId,
        sessionId = sessionId,
        triggerReason = command.triggerReason,
        triggeredAtEpochMs = command.triggeredAtEpochMs,
        result = result,
        failureReason = failureReason,
        updatedAtEpochMs = clock(),
      ),
    )
  }

  private fun clearSnoozeForDispatchIfNeeded(
    spec: ScheduledTaskSpec,
    command: ScheduledTaskWakeCommand,
    nowEpochMs: Long,
  ): ScheduledTaskSpec {
    val snoozedUntilEpochMs = spec.snoozedUntilEpochMs ?: return spec
    val shouldClearSnooze = command.triggerReason == ScheduledTaskTriggerReasons.MANUAL ||
      snoozedUntilEpochMs <= nowEpochMs
    if (!shouldClearSnooze) {
      return spec
    }
    val updated = spec.copy(
      snoozedUntilEpochMs = null,
      updatedAtEpochMs = maxOf(nowEpochMs, spec.updatedAtEpochMs + 1L),
    )
    specStore.upsert(updated)
    return updated
  }

  private fun scheduledTaskMetadata(
    spec: ScheduledTaskSpec,
    command: ScheduledTaskWakeCommand,
    pendingMessageId: String,
  ): Map<String, String> = buildTaskSafetyMetadata(
    snapshot = safetySettingsFacade.load(),
    approvedReadRoots = approvedReadRootsProvider(),
  ) +
    lifecycleDescriptor.taskMetadata(
      submissionSource = RunSubmissionSources.SCHEDULED_TRIGGER,
    ) + buildMap {
      put(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID,
        "run-${spec.sessionId}-${UUID.randomUUID().toString().take(8)}",
      )
      put(AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID, spec.sessionId)
      put(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID, pendingMessageId)
      put(AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID, pendingMessageId)
      put(ScheduledTaskMetadataKeys.SCHEDULE_ID, spec.scheduleId)
      put(ScheduledTaskMetadataKeys.SCHEDULE_RUN_ID, command.scheduleRunId)
      put(ScheduledTaskMetadataKeys.SCHEDULE_TITLE, spec.title)
      put(ScheduledTaskMetadataKeys.SCHEDULE_TRIGGER_REASON, command.triggerReason)
      put(
        ScheduledTaskMetadataKeys.SCHEDULE_TRIGGERED_AT_EPOCH_MS,
        command.triggeredAtEpochMs.toString(),
      )
      spec.payload.workingDirectory
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { workingDirectory ->
          put(ScheduledTaskMetadataKeys.SCHEDULE_WORKING_DIRECTORY, workingDirectory)
        }
    }
}

internal data class ScheduledTaskDispatcherDependencies(
  val hostAccess: RuntimeSessionDirectoryAccess,
  val chatSessionStore: ChatSessionLocalStore,
  val safetySettingsFacade: SafetySettingsFacade,
  val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val localizedContext: Context,
  val assistantPlaceholderTextProvider: () -> String,
  val specStore: ScheduledTaskSpecStore,
  val runRecordStore: ScheduledTaskRunRecordStore,
  val triggerRegistrar: ScheduledTriggerRegistrar,
)

internal fun ScheduledTaskDispatcherDependencies.createScheduledTaskDispatcher(
  clock: () -> Long = System::currentTimeMillis,
): ScheduledTaskDispatcher = ScheduledTaskDispatcher(
  hostAccess = hostAccess,
  chatSessionStore = chatSessionStore,
  safetySettingsFacade = safetySettingsFacade,
  approvedReadRootsProvider = approvedReadRootsProvider,
  lifecycleDescriptor = lifecycleDescriptor,
  localizedContext = localizedContext,
  assistantPlaceholderTextProvider = assistantPlaceholderTextProvider,
  specStore = specStore,
  runRecordStore = runRecordStore,
  triggerRegistrar = triggerRegistrar,
  clock = clock,
)

internal data class ScheduledTaskRepairDependencies(
  val scheduledTaskDispatcherDependencies: ScheduledTaskDispatcherDependencies,
  val specStore: ScheduledTaskSpecStore,
  val triggerRegistrar: ScheduledTriggerRegistrar,
  val triggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
)

internal fun ScheduledTaskRepairDependencies.repairScheduledTasks(
  repairReason: String,
  nowEpochMs: Long = System.currentTimeMillis(),
): List<ScheduledTaskDispatchOutcome> {
  val enabledSpecs = specStore.listEnabled()
  val dispatcher = scheduledTaskDispatcherDependencies.createScheduledTaskDispatcher(
    clock = { nowEpochMs },
  )
  val outcomes = plannedRepairWakeCommands(
    enabledSpecs = enabledSpecs,
    nowEpochMs = nowEpochMs,
    repairReason = repairReason,
  ).map(dispatcher::dispatch)
  resyncEnabledScheduledTasks(
    specStore = specStore,
    triggerRegistrar = triggerRegistrar,
    triggerSyncStateStore = triggerSyncStateStore,
  )
  return outcomes
}

internal fun ScheduledTaskRepairDependencies.disableScheduledTask(
  scheduleId: String,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean = disableScheduledTask(
  scheduleId = scheduleId,
  specStore = specStore,
  triggerRegistrar = triggerRegistrar,
  triggerSyncStateStore = triggerSyncStateStore,
  nowEpochMs = nowEpochMs,
)

internal fun ScheduledTaskRepairDependencies.snoozeScheduledTask(
  scheduleId: String,
  snoozedUntilEpochMs: Long,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean = snoozeScheduledTask(
  scheduleId = scheduleId,
  snoozedUntilEpochMs = snoozedUntilEpochMs,
  specStore = specStore,
  triggerRegistrar = triggerRegistrar,
  triggerSyncStateStore = triggerSyncStateStore,
  nowEpochMs = nowEpochMs,
)

internal fun disableScheduledTask(
  scheduleId: String,
  specStore: ScheduledTaskSpecStore,
  triggerRegistrar: ScheduledTriggerRegistrar,
  triggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  val normalizedScheduleId = scheduleId.trim().takeIf(String::isNotBlank) ?: return false
  val existing = specStore.get(normalizedScheduleId) ?: return false
  if (!existing.enabled) {
    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = triggerRegistrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )
    return false
  }
  specStore.upsert(
    existing.copy(
      enabled = false,
      updatedAtEpochMs = maxOf(nowEpochMs, existing.updatedAtEpochMs + 1L),
    ),
  )
  resyncEnabledScheduledTasks(
    specStore = specStore,
    triggerRegistrar = triggerRegistrar,
    triggerSyncStateStore = triggerSyncStateStore,
  )
  return true
}

internal fun snoozeScheduledTask(
  scheduleId: String,
  snoozedUntilEpochMs: Long,
  specStore: ScheduledTaskSpecStore,
  triggerRegistrar: ScheduledTriggerRegistrar,
  triggerSyncStateStore: ScheduledTaskTriggerSyncStateStore,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  val normalizedScheduleId = scheduleId.trim().takeIf(String::isNotBlank) ?: return false
  if (snoozedUntilEpochMs <= nowEpochMs) {
    return false
  }
  val existing = specStore.get(normalizedScheduleId) ?: return false
  if (!existing.enabled) {
    resyncEnabledScheduledTasks(
      specStore = specStore,
      triggerRegistrar = triggerRegistrar,
      triggerSyncStateStore = triggerSyncStateStore,
    )
    return false
  }
  specStore.upsert(
    existing.copy(
      snoozedUntilEpochMs = maxOf(
        snoozedUntilEpochMs,
        existing.snoozedUntilEpochMs ?: 0L,
      ),
      updatedAtEpochMs = maxOf(nowEpochMs, existing.updatedAtEpochMs + 1L),
    ),
  )
  resyncEnabledScheduledTasks(
    specStore = specStore,
    triggerRegistrar = triggerRegistrar,
    triggerSyncStateStore = triggerSyncStateStore,
  )
  return true
}

internal fun plannedRepairWakeCommands(
  enabledSpecs: List<ScheduledTaskSpec>,
  nowEpochMs: Long,
  repairReason: String,
): List<ScheduledTaskWakeCommand> = enabledSpecs.mapNotNull { spec ->
  val dueEpochMs = dueScheduledTriggerAtEpochMs(spec, nowEpochMs) ?: return@mapNotNull null
  ScheduledTaskWakeCommand(
    scheduleId = spec.scheduleId,
    scheduleRunId = scheduledTaskRunId(spec.scheduleId, dueEpochMs),
    triggeredAtEpochMs = nowEpochMs,
    triggerReason = scheduledTaskTriggerReasonForRepair(repairReason),
  )
}

internal fun scheduledTaskTriggerReasonForRepair(
  repairReason: String,
): String = when (repairReason) {
  ScheduledTaskRepairReasons.WORK_MANAGER,
  ScheduledTaskRepairReasons.BOOT_COMPLETED,
  ScheduledTaskRepairReasons.PACKAGE_REPLACED,
  ScheduledTaskRepairReasons.APP_START,
  ScheduledTaskRepairReasons.PERIODIC,
  ScheduledTaskRepairReasons.BROADCAST,
  -> ScheduledTaskTriggerReasons.REPAIR
  else -> ScheduledTaskTriggerReasons.REPAIR
}

internal class ScheduledTaskWakeReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    val runtimeEnvironment = openCrayRuntimeServiceEnvironment(context.applicationContext)
    val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return
    val scheduledForEpochMs = intent.getLongExtra(EXTRA_SCHEDULED_FOR_EPOCH_MS, -1L)
      .takeIf { value -> value >= 0L }
      ?: return
    val wakeCommand = ScheduledTaskWakeCommand(
      scheduleId = scheduleId,
      scheduleRunId = scheduledTaskRunId(scheduleId, scheduledForEpochMs),
      triggeredAtEpochMs = System.currentTimeMillis(),
      triggerReason = ScheduledTaskTriggerReasons.ALARM,
    )
    runtimeEnvironment.runtimeServiceAccessGateway.startScheduledTask(
      context.applicationContext,
      wakeCommand,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
  }
}

internal fun parseScheduledTaskWakeCommand(intent: Intent?): ScheduledTaskWakeCommand? {
  return parseScheduledTaskWakeCommand(
    action = safeScheduledTaskWakeAction(intent),
    scheduleId = safeScheduledTaskWakeStringExtra(intent, EXTRA_SCHEDULE_ID),
    scheduleRunId = safeScheduledTaskWakeStringExtra(intent, EXTRA_SCHEDULE_RUN_ID),
    triggeredAtEpochMs = safeScheduledTaskWakeLongExtra(
      intent,
      EXTRA_TRIGGERED_AT_EPOCH_MS,
      -1L,
    )?.takeIf { value -> value >= 0L },
    triggerReason = safeScheduledTaskWakeStringExtra(intent, EXTRA_TRIGGER_REASON),
    targetSessionId = safeScheduledTaskWakeStringExtra(intent, EXTRA_TARGET_SESSION_ID),
  )
}

internal fun parseScheduledTaskWakeCommand(
  action: String?,
  scheduleId: String?,
  scheduleRunId: String?,
  triggeredAtEpochMs: Long?,
  triggerReason: String?,
  targetSessionId: String?,
): ScheduledTaskWakeCommand? {
  if (action != ACTION_RUN_SCHEDULED_TASK) {
    return null
  }
  val normalizedScheduleId = scheduleId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val normalizedScheduleRunId = scheduleRunId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val normalizedTriggeredAtEpochMs = triggeredAtEpochMs
    ?.takeIf { value -> value >= 0L }
    ?: return null
  val normalizedTriggerReason = triggerReason
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  return ScheduledTaskWakeCommand(
    scheduleId = normalizedScheduleId,
    scheduleRunId = normalizedScheduleRunId,
    triggeredAtEpochMs = normalizedTriggeredAtEpochMs,
    triggerReason = normalizedTriggerReason,
    targetSessionId = targetSessionId
      ?.trim()
      ?.takeIf(String::isNotBlank),
  )
  }

private fun safeScheduledTaskWakeAction(
  intent: Intent?,
): String? = runCatching {
  intent?.action
}.getOrNull()

private fun safeScheduledTaskWakeStringExtra(
  intent: Intent?,
  key: String,
): String? = runCatching {
  intent?.getStringExtra(key)
}.getOrNull()

private fun safeScheduledTaskWakeLongExtra(
  intent: Intent?,
  key: String,
  defaultValue: Long,
): Long? = runCatching {
  intent?.getLongExtra(key, defaultValue)
}.getOrNull()

internal fun scheduledTaskWakeReceiverPendingIntent(
  context: Context,
  scheduleId: String,
  scheduledForEpochMs: Long,
  createIfMissing: Boolean,
): PendingIntent? {
  val flags = PendingIntent.FLAG_IMMUTABLE or
    if (createIfMissing) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
  return PendingIntent.getBroadcast(
    context,
    scheduledTaskAlarmRequestCode(scheduleId),
    scheduledTaskWakeReceiverIntent(context, scheduleId, scheduledForEpochMs),
    flags,
  )
}

private fun scheduledTaskWakeReceiverIntent(
  context: Context,
  scheduleId: String,
  scheduledForEpochMs: Long,
): Intent = Intent(context, ScheduledTaskWakeReceiver::class.java)
  .setAction(ACTION_TRIGGER_SCHEDULED_TASK_ALARM)
  .setData(
    Uri.Builder()
      .scheme("opencray")
      .authority("scheduled-task")
      .appendPath(scheduleId)
      .build(),
  )
  .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
  .putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MS, scheduledForEpochMs)

internal fun nextScheduledTriggerAtEpochMs(
  spec: ScheduledTaskSpec,
  nowEpochMs: Long,
): Long? {
  val snoozedUntilEpochMs = spec.snoozedUntilEpochMs
    ?.takeIf { candidate -> candidate > nowEpochMs }
    ?: return nextTriggerIgnoringSnooze(spec, nowEpochMs)
  val dueEpochMs = dueTriggerIgnoringSnooze(spec, nowEpochMs)
  if (dueEpochMs != null) {
    return snoozedUntilEpochMs
  }
  val nextEpochMs = nextTriggerIgnoringSnooze(spec, nowEpochMs) ?: return null
  return maxOf(nextEpochMs, snoozedUntilEpochMs)
}

internal fun dueScheduledTriggerAtEpochMs(
  spec: ScheduledTaskSpec,
  nowEpochMs: Long,
): Long? {
  val snoozedUntilEpochMs = spec.snoozedUntilEpochMs
  if (snoozedUntilEpochMs != null && snoozedUntilEpochMs > nowEpochMs) {
    return null
  }
  val dueEpochMs = dueTriggerIgnoringSnooze(spec, nowEpochMs) ?: return null
  return if (snoozedUntilEpochMs != null) {
    maxOf(dueEpochMs, snoozedUntilEpochMs)
  } else {
    dueEpochMs
  }
}

private fun nextTriggerIgnoringSnooze(
  spec: ScheduledTaskSpec,
  nowEpochMs: Long,
): Long? = when (val trigger = spec.trigger) {
  is ScheduledTrigger.At ->
    trigger.atEpochMs.takeIf { triggerAtEpochMs -> triggerAtEpochMs > nowEpochMs }

  is ScheduledTrigger.After -> {
    val runAtEpochMs = trigger.createdAtEpochMs + trigger.delayMs
    runAtEpochMs.takeIf { candidate -> candidate > nowEpochMs }
  }

  is ScheduledTrigger.Recurrence ->
    nextScheduledRecurrenceTriggerAtEpochMs(
      trigger = trigger,
      afterEpochMs = nowEpochMs,
    )
}

private fun dueTriggerIgnoringSnooze(
  spec: ScheduledTaskSpec,
  nowEpochMs: Long,
): Long? = when (val trigger = spec.trigger) {
  is ScheduledTrigger.At ->
    trigger.atEpochMs.takeIf { triggerAtEpochMs -> triggerAtEpochMs <= nowEpochMs }

  is ScheduledTrigger.After -> {
    val runAtEpochMs = trigger.createdAtEpochMs + trigger.delayMs
    runAtEpochMs.takeIf { candidate -> candidate <= nowEpochMs }
  }

  is ScheduledTrigger.Recurrence ->
    dueScheduledRecurrenceTriggerAtEpochMs(
      trigger = trigger,
      nowEpochMs = nowEpochMs,
    )
}

internal fun scheduledTaskRunId(
  scheduleId: String,
  scheduledForEpochMs: Long,
): String = "schedule-run-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(scheduleId)}-$scheduledForEpochMs"

private fun scheduledTaskAlarmRequestCode(scheduleId: String): Int =
  scheduleId.hashCode()

private fun requiresExactScheduling(trigger: ScheduledTrigger): Boolean =
  when (trigger) {
    is ScheduledTrigger.At,
    is ScheduledTrigger.After,
    is ScheduledTrigger.Recurrence,
    -> true
  }

private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
    true
  } else {
    alarmManager.canScheduleExactAlarms()
  }

private val WAITING_LIFECYCLE_STATES: Set<QueueTaskLifecycleState> = setOf(
  QueueTaskLifecycleState.QUEUED,
  QueueTaskLifecycleState.RETRY_PENDING,
  QueueTaskLifecycleState.SUSPENDED,
)

private const val UNRESOLVED_SESSION_ID: String = "_unresolved_session_"

internal const val ACTION_RUN_SCHEDULED_TASK: String =
  "com.opencray.app.action.RUN_SCHEDULED_TASK"
internal const val ACTION_REPAIR_SCHEDULES: String =
  "com.opencray.app.action.REPAIR_SCHEDULES"
internal const val ACTION_TRIGGER_SCHEDULED_TASK_ALARM: String =
  "com.opencray.app.action.TRIGGER_SCHEDULED_TASK_ALARM"
internal const val EXTRA_SCHEDULE_ID: String = "scheduleId"
internal const val EXTRA_SCHEDULE_RUN_ID: String = "scheduleRunId"
internal const val EXTRA_TRIGGERED_AT_EPOCH_MS: String = "triggeredAtEpochMs"
internal const val EXTRA_TRIGGER_REASON: String = "triggerReason"
internal const val EXTRA_TARGET_SESSION_ID: String = "targetSessionId"
internal const val EXTRA_SCHEDULED_FOR_EPOCH_MS: String = "scheduledForEpochMs"
internal const val EXTRA_REPAIR_REASON: String = "repairReason"

private const val NO_SCHEDULED_EPOCH_MS: Long = -1L
