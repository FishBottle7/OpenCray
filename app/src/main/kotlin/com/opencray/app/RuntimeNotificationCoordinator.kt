package com.opencray.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import org.opencray.app.R

internal object RuntimeNotificationIntentActions {
  const val ACTION_APPROVE_RUNTIME_APPROVAL: String =
    "com.opencray.app.action.APPROVE_RUNTIME_APPROVAL"
  const val ACTION_REJECT_RUNTIME_APPROVAL: String =
    "com.opencray.app.action.REJECT_RUNTIME_APPROVAL"
  const val ACTION_RUN_SCHEDULE_NOW: String =
    "com.opencray.app.action.RUN_SCHEDULE_NOW"
  const val ACTION_DISABLE_SCHEDULE: String =
    "com.opencray.app.action.DISABLE_SCHEDULE"
  const val ACTION_SNOOZE_SCHEDULE: String =
    "com.opencray.app.action.SNOOZE_SCHEDULE"
  const val ACTION_CANCEL_SCHEDULED_RUN: String =
    "com.opencray.app.action.CANCEL_SCHEDULED_RUN"
}

internal object RuntimeNotificationIntentExtras {
  const val EXTRA_NOTIFICATION_SESSION_ID: String = "notificationSessionId"
  const val EXTRA_NOTIFICATION_TASK_ID: String = "notificationTaskId"
  const val EXTRA_NOTIFICATION_RUN_ID: String = "notificationRunId"
  const val EXTRA_NOTIFICATION_SCHEDULE_ID: String = "notificationScheduleId"
  const val EXTRA_NOTIFICATION_EXECUTION_ID: String = "notificationExecutionId"
  const val EXTRA_NOTIFICATION_EXECUTION_ORDINAL: String = "notificationExecutionOrdinal"
}

internal data class RuntimeApprovalExecutionBinding(
  val executionId: String? = null,
  val executionOrdinal: Int? = null,
) {
  val hasIdentity: Boolean
    get() = !normalizedExecutionId.isNullOrEmpty() || normalizedExecutionOrdinal != null

  val normalizedExecutionId: String?
    get() = executionId?.trim()?.takeIf(String::isNotBlank)

  val normalizedExecutionOrdinal: Int?
    get() = executionOrdinal?.takeIf { value -> value > 0 }

  fun identityToken(): String =
    normalizedExecutionId ?: normalizedExecutionOrdinal?.let { value -> "ordinal-$value" } ?: "unknown"

  fun matches(other: RuntimeApprovalExecutionBinding): Boolean {
    if (!hasIdentity && !other.hasIdentity) {
      return true
    }
    if (!hasIdentity || !other.hasIdentity) {
      return false
    }
    return normalizedExecutionId == other.normalizedExecutionId &&
      normalizedExecutionOrdinal == other.normalizedExecutionOrdinal
  }
}

internal data class RuntimeApprovalNotificationModel(
  val sessionId: String,
  val sessionTitle: String,
  val runId: String,
  val taskId: String,
  val runtimeTarget: RuntimeServiceTarget,
  val title: String,
  val body: String,
  val isHighRisk: Boolean,
  val executionBinding: RuntimeApprovalExecutionBinding = RuntimeApprovalExecutionBinding(),
) {
  val notificationKey: RuntimeNotificationKey =
    RuntimeNotificationKeys.approvalKey(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      executionBinding = executionBinding,
    )
}

internal data class RuntimeTerminalNotificationModel(
  val sessionId: String,
  val sessionTitle: String,
  val runId: String,
  val taskId: String,
  val runtimeTarget: RuntimeServiceTarget,
  val title: String,
  val body: String,
  val interrupted: Boolean,
)

internal data class RuntimeTerminalNotificationAction(
  val command: OpenCrayChatWriteCommand,
  val labelResId: Int,
  val runtimeTarget: RuntimeServiceTarget,
  val requestKey: String,
  val terminalNotificationTaskId: String? = null,
)

internal data class RuntimeScheduleNotificationModel(
  val sessionId: String?,
  val sessionTitle: String?,
  val scheduleId: String,
  val scheduleTitle: String,
  val title: String,
  val body: String,
  val actions: List<RuntimeScheduleNotificationAction> = emptyList(),
)

internal data class RuntimeScheduleNotificationAction(
  val action: String,
  val scheduleId: String,
  val sessionId: String?,
  val taskId: String? = null,
  val runId: String? = null,
  val labelResId: Int,
  val runtimeTarget: RuntimeServiceTarget,
)

internal fun scheduleNotificationActionsForOutcome(
  outcome: ScheduledTaskDispatchOutcome,
  spec: ScheduledTaskSpec?,
  sessionId: String?,
): List<RuntimeScheduleNotificationAction> {
  if (spec?.enabled != true) {
    return emptyList()
  }
  if (outcome.result == ScheduledTaskRunResult.ACCEPTED) {
    val createdTaskId = outcome.createdTaskId?.trim()?.takeIf(String::isNotBlank)
    val createdRunId = outcome.createdRunId?.trim()?.takeIf(String::isNotBlank)
    if (createdTaskId == null && createdRunId == null) {
      return emptyList()
    }
    return listOf(
      RuntimeScheduleNotificationAction(
        action = RuntimeNotificationIntentActions.ACTION_CANCEL_SCHEDULED_RUN,
        scheduleId = outcome.scheduleId,
        sessionId = sessionId ?: spec.sessionId,
        taskId = createdTaskId,
        runId = createdRunId,
        labelResId = R.string.runtime_notification_action_cancel_run,
        runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      ),
    )
  }
  val canRetry = when (outcome.result) {
    ScheduledTaskRunResult.SKIPPED_SESSION_BUSY,
    ScheduledTaskRunResult.FAILED_DISPATCH,
    -> true

    else -> false
  }
  if (!canRetry) {
    return emptyList()
  }
  val resolvedSessionId = sessionId ?: spec.sessionId
  return listOf(
    RuntimeScheduleNotificationAction(
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      scheduleId = outcome.scheduleId,
      sessionId = resolvedSessionId,
      labelResId = R.string.runtime_notification_action_retry,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    ),
    RuntimeScheduleNotificationAction(
      action = RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
      scheduleId = outcome.scheduleId,
      sessionId = resolvedSessionId,
      labelResId = R.string.runtime_notification_action_snooze_schedule,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    ),
    RuntimeScheduleNotificationAction(
      action = RuntimeNotificationIntentActions.ACTION_DISABLE_SCHEDULE,
      scheduleId = outcome.scheduleId,
      sessionId = resolvedSessionId,
      labelResId = R.string.runtime_notification_action_disable_schedule,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    ),
  )
}

internal data class RuntimeServiceRecoveredNotificationModel(
  val title: String,
  val body: String,
)

internal class RuntimeNotificationCoordinator(
  private val appContext: Context,
  private val localizedContext: Context,
  private val chatSessionStore: ChatSessionLocalStore,
  hostAccess: RuntimeNotificationHostAccess,
  private val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  private val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  private val terminalDeliveryStore: RuntimeNotificationDeliveryStore =
    FileBackedRuntimeNotificationDeliveryStoreFactory.fromContext(appContext).create(),
  private val notificationSettingsProvider: () -> RuntimeNotificationSettingsState = {
    RuntimeNotificationSettingsStore.fromContext(appContext).load()
  },
  private val notificationManager: NotificationManager = checkNotNull(
    appContext.getSystemService(NotificationManager::class.java),
  ) {
    "NotificationManager is unavailable."
  },
  private val runtimeServiceAccessGateway: RuntimeServiceAccessGateway,
  private val appVisibilitySignalAccess: AppVisibilitySignalAccess =
    defaultAppVisibilitySignalAccess(appContext),
) {
  private val lock = Any()
  private var started: Boolean = false
  private var hostAccess: RuntimeNotificationHostAccess = hostAccess
  private var hostObservationDisposer: (() -> Unit)? = null
  private var visibilityObservationDisposer: (() -> Unit)? = null
  private val activeApprovalNotifications = LinkedHashMap<String, TrackedApprovalNotification>()
  private val appVisibleProvider: () -> Boolean = appVisibilitySignalAccess::currentVisibility

  private data class TrackedApprovalNotification(
    val sessionId: String,
    val taskId: String,
    val runId: String,
    val key: RuntimeNotificationKey,
  )

  fun start() {
    val resolvedHostAccess: RuntimeNotificationHostAccess
    synchronized(lock) {
      if (started) {
        return
      }
      started = true
      resolvedHostAccess = hostAccess
      hostObservationDisposer = resolvedHostAccess.observe(hostListener)
      visibilityObservationDisposer = appVisibilitySignalAccess.observe(::onAppVisibilityChanged)
    }
    syncPendingApprovalNotifications()
    if (!appVisibleProvider()) {
      syncOutstandingTerminalNotifications()
    }
  }

  fun onServiceBootstrapCompleted(
    bootstrapResult: RuntimeServiceBootstrapResult,
    processStartId: String,
  ) {
    if (appVisibleProvider()) {
      return
    }
    val model = serviceRecoveredNotificationModel(bootstrapResult) ?: return
    publishServiceRecoveredNotification(
      model = model,
      processStartId = processStartId,
      bootstrapResult = bootstrapResult,
    )
  }

  fun dispose() {
    val hostDisposer: (() -> Unit)?
    val visibilityDisposer: (() -> Unit)?
    synchronized(lock) {
      started = false
      activeApprovalNotifications.clear()
      hostDisposer = hostObservationDisposer
      visibilityDisposer = visibilityObservationDisposer
      hostObservationDisposer = null
      visibilityObservationDisposer = null
    }
    hostDisposer?.invoke()
    visibilityDisposer?.invoke()
  }

  fun replaceHostAccess(hostAccess: RuntimeNotificationHostAccess) {
    val previousDisposer: (() -> Unit)?
    val shouldSync: Boolean
    synchronized(lock) {
      if (this.hostAccess === hostAccess) {
        return
      }
      this.hostAccess = hostAccess
      shouldSync = started
      previousDisposer = if (started) {
        hostObservationDisposer.also {
          hostObservationDisposer = hostAccess.observe(hostListener)
        }
      } else {
        null
      }
      activeApprovalNotifications.clear()
    }
    previousDisposer?.invoke()
    if (shouldSync) {
      syncPendingApprovalNotifications()
      if (!appVisibleProvider()) {
        syncOutstandingTerminalNotifications()
      }
    }
  }

  fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
    if (appVisibleProvider()) {
      return
    }
    val spec = scheduledTaskSpecStore.get(outcome.scheduleId)
    val model = scheduleNotificationModel(outcome = outcome, spec = spec) ?: return
    val key = RuntimeNotificationKeys.scheduleKey(
      scheduleId = model.scheduleId,
      outcome = outcome.result.name,
    )
    notificationManager.notify(key.tag, key.id, buildScheduleNotification(model))
  }

  private val hostListener = object : AgentSessionRuntimeListener {
    override fun onTaskFinished(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      handleTaskFinished(sessionId = sessionId, task = task, result = result)
    }

    override fun onRunEvent(
      sessionId: String,
      task: AgentTask,
      event: OpenCrayAgentRunEvent,
    ) {
      handleRunEvent(sessionId = sessionId, task = task, event = event)
    }
  }

  private fun handleTaskFinished(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    updateScheduledRunRecord(
      sessionId = sessionId,
      task = task,
      result = result,
    )
    if (isApprovalRequiredResult(result)) {
      val approvalModel = approvalNotificationModel(
        sessionId = sessionId,
        task = task,
        errorCode = result.errorCode,
        metadata = result.metadata,
        errorBody = result.errorMessage,
        toolReason = result.metadata["toolReason"],
      )
      if (approvalModel != null) {
        trackActiveApproval(approvalModel)
        if (!appVisibleProvider() && shouldNotifyApproval(task = task)) {
          val key = approvalModel.notificationKey
          notificationManager.notify(key.tag, key.id, buildApprovalNotification(approvalModel))
          cancelLegacyApprovalSlot(approvalModel.taskId)
        }
      }
      return
    }

    cancelApprovalsForTask(sessionId = sessionId, taskId = task.id)
    if (appVisibleProvider()) {
      return
    }
    val terminalModel = terminalNotificationModel(
      sessionId = sessionId,
      task = task,
      result = result,
    ) ?: return
    publishTerminalNotification(terminalModel, updatedAtEpochMs = result.finishedAtEpochMs)
  }

  private fun handleRunEvent(
    sessionId: String,
    task: AgentTask,
    event: OpenCrayAgentRunEvent,
  ) {
    when (event) {
      is OpenCrayApprovalEvent -> {
        if (event.phase != OpenCrayApprovalPhase.REQUIRED) {
          cancelApprovalsForTask(
            sessionId = sessionId,
            taskId = event.taskId,
            runId = event.runId,
          )
        }
      }

      is OpenCrayCancellationEvent -> {
        cancelApprovalsForTask(
          sessionId = sessionId,
          taskId = event.taskId,
          runId = event.runId,
        )
      }

      else -> Unit
    }
    if (task.metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID].isNullOrBlank()) {
      return
    }
    if (event is OpenCrayToolCallEvent) {
      updateScheduledRunRecordWaitingApproval(
        sessionId = sessionId,
        task = task,
      )
    }
  }

  private fun syncPendingApprovalNotifications() {
    val models = knownSessionIds()
      .flatMap(::pendingApprovalNotificationsForSession)
    val staleKeys: List<RuntimeNotificationKey> = synchronized(lock) {
      val currentTags = models.mapTo(linkedSetOf()) { model -> model.notificationKey.tag }
      val trackedByTag = activeApprovalNotifications.keys.toSet()
      val stale = (trackedByTag - currentTags).mapNotNull { tag ->
        activeApprovalNotifications.remove(tag)?.key
      }
      models.forEach { model -> trackActiveApprovalLocked(model) }
      stale
    }
    staleKeys.forEach { key -> notificationManager.cancel(key.tag, key.id) }
    if (appVisibleProvider()) {
      return
    }
    models.forEach { model ->
      val key = model.notificationKey
      notificationManager.notify(key.tag, key.id, buildApprovalNotification(model))
      cancelLegacyApprovalSlot(model.taskId)
    }
  }

  private fun syncOutstandingTerminalNotifications() {
    val hostAccess = currentHostAccess()
    for (sessionId in knownSessionIds()) {
      if (!hostAccess.ownsSession(sessionId)) {
        continue
      }
      val session = hostAccess.session(sessionId)
      val tasksById = session.snapshot().tasks.associateBy { taskSnapshot -> taskSnapshot.task.id }
      for (run in session.listRuns()) {
        if (!run.isTerminal || run.executionStatus == null || isApprovalRequiredError(run.errorCode)) {
          continue
        }
        val task = tasksById[run.taskId]?.task ?: continue
        val result = ExecutionResult(
          taskId = run.taskId,
          status = run.executionStatus,
          errorCode = run.errorCode,
          errorMessage = run.errorMessage,
          startedAtEpochMs = run.acceptedAtEpochMs,
          finishedAtEpochMs = run.updatedAtEpochMs,
          metadata = run.resultMetadata,
        )
        val terminalModel = terminalNotificationModel(
          sessionId = sessionId,
          task = task,
          result = result,
        ) ?: continue
        publishTerminalNotification(
          terminalModel = terminalModel,
          updatedAtEpochMs = run.updatedAtEpochMs,
        )
      }
    }
  }

  private fun pendingApprovalNotificationsForSession(
    sessionId: String,
  ): List<RuntimeApprovalNotificationModel> {
    val hostAccess = currentHostAccess()
    if (!hostAccess.ownsSession(sessionId)) {
      return emptyList()
    }
    return approvalRequiredTaskProjectionsForSession(
      sessionId = sessionId,
      hostAccess = hostAccess,
      approvalRequiredErrorCode = ERROR_APPROVAL_REQUIRED,
      highRiskApprovalRequiredErrorCode = ERROR_HIGH_RISK_APPROVAL_REQUIRED,
    )
      .asSequence()
      .filter { projection ->
        projection.isVisibleApprovalLifecycle() &&
          shouldNotifyApprovalReminder(projection.taskSnapshot.task)
      }
      .mapNotNull { projection ->
        approvalNotificationModel(
          sessionId = projection.sessionId,
          task = projection.taskSnapshot.task,
          errorCode = projection.errorCode,
          metadata = projection.metadata,
          errorBody = projection.errorBody,
          toolReason = projection.toolReason,
          runSnapshot = projection.runSnapshot,
        )
      }
      .toList()
  }

  private fun onAppVisibilityChanged(isVisible: Boolean) {
    if (isVisible) {
      val tracked: List<TrackedApprovalNotification> = synchronized(lock) {
        activeApprovalNotifications.values.toList().also { activeApprovalNotifications.clear() }
      }
      tracked.forEach { entry ->
        notificationManager.cancel(entry.key.tag, entry.key.id)
      }
      return
    }
    syncPendingApprovalNotifications()
    syncOutstandingTerminalNotifications()
  }

  private fun approvalNotificationModel(
    sessionId: String,
    task: AgentTask,
    errorCode: String?,
    metadata: Map<String, String>,
    errorBody: String?,
    toolReason: String?,
    runSnapshot: AgentRunSnapshot? = null,
  ): RuntimeApprovalNotificationModel? {
    val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: task.id
    val isHighRisk = approvalIsHighRisk(
      errorCode = errorCode,
      metadata = metadata,
    )
    val body = composeApprovalBody(
      body = sanitizeApprovalBody(
        body = errorBody,
        isHighRisk = isHighRisk,
      ),
      toolReason = toolReason,
      metadata = metadata,
    )
    return RuntimeApprovalNotificationModel(
      sessionId = sessionId,
      sessionTitle = sessionTitle(sessionId),
      runId = runId,
      taskId = task.id,
      runtimeTarget = runtimeServiceTargetForNotificationTask(task),
      title = if (isHighRisk) {
        localizedContext.getString(R.string.chat_high_risk_approval_required_title)
      } else {
        localizedContext.getString(R.string.chat_approval_required_title)
      },
      body = body,
      isHighRisk = isHighRisk,
      executionBinding = approvalExecutionBinding(
        task = task,
        runSnapshot = runSnapshot,
        resultMetadata = metadata,
      ),
    )
  }

  private fun approvalExecutionBinding(
    task: AgentTask,
    runSnapshot: AgentRunSnapshot?,
    resultMetadata: Map<String, String>,
  ): RuntimeApprovalExecutionBinding = RuntimeApprovalExecutionBinding(
    executionId = task.metadata[METADATA_EXECUTION_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: runSnapshot?.executionId
      ?: resultMetadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank),
    executionOrdinal = task.metadata[METADATA_EXECUTION_ORDINAL]?.trim()?.toIntOrNull()
      ?: runSnapshot?.executionOrdinal?.takeIf { value -> value > 0 }
      ?: resultMetadata[METADATA_EXECUTION_ORDINAL]?.trim()?.toIntOrNull(),
  )

  private fun terminalNotificationModel(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): RuntimeTerminalNotificationModel? {
    val spec = scheduledSpecFor(task)
    val interrupted = isInterruptedResult(result)
    if (!shouldNotifyTerminal(spec = spec, interrupted = interrupted, result = result)) {
      return null
    }
    val previewText = terminalPreviewText(
      sessionId = sessionId,
      task = task,
      result = result,
      interrupted = interrupted,
    )
    val titleResId = when {
      interrupted -> R.string.runtime_notification_interruption_title
      result.status == ExecutionStatus.SUCCESS -> R.string.runtime_notification_completion_title
      else -> R.string.runtime_notification_failure_title
    }
    return RuntimeTerminalNotificationModel(
      sessionId = sessionId,
      sessionTitle = sessionTitle(sessionId),
      runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: task.id,
      taskId = task.id,
      runtimeTarget = runtimeServiceTargetForNotificationTask(task),
      title = localizedContext.getString(titleResId),
      body = previewText,
      interrupted = interrupted,
    )
  }

  private fun publishTerminalNotification(
    terminalModel: RuntimeTerminalNotificationModel,
    updatedAtEpochMs: Long,
  ) {
    val fingerprint = terminalNotificationFingerprint(
      terminalModel = terminalModel,
      updatedAtEpochMs = updatedAtEpochMs,
    )
    val deliveryKey = terminalNotificationDeliveryKey(terminalModel.runId)
    if (terminalDeliveryStore.wasDelivered(deliveryKey, fingerprint)) {
      return
    }
    val key = RuntimeNotificationKeys.terminalKey(
      runId = terminalModel.runId,
      taskId = terminalModel.taskId,
      interrupted = terminalModel.interrupted,
    )
    notificationManager.notify(key.tag, key.id, buildTerminalNotification(terminalModel))
    cancelLegacyTerminalSlots(terminalModel.taskId)
    terminalDeliveryStore.markDelivered(deliveryKey, fingerprint)
  }

  private fun publishServiceRecoveredNotification(
    model: RuntimeServiceRecoveredNotificationModel,
    processStartId: String,
    bootstrapResult: RuntimeServiceBootstrapResult,
  ) {
    val fingerprint = listOf(
      processStartId,
      bootstrapResult.resumedSessionIds.sorted(),
      bootstrapResult.repairedSessionIds.sorted(),
    ).joinToString("|")
    val deliveryKey = "service_recovered:$processStartId"
    if (terminalDeliveryStore.wasDelivered(deliveryKey, fingerprint)) {
      return
    }
    val key = RuntimeNotificationKeys.recoveredKey(processStartId)
    notificationManager.notify(key.tag, key.id, buildServiceRecoveredNotification(model))
    terminalDeliveryStore.markDelivered(deliveryKey, fingerprint)
  }

  private fun scheduleNotificationModel(
    outcome: ScheduledTaskDispatchOutcome,
    spec: ScheduledTaskSpec?,
  ): RuntimeScheduleNotificationModel? {
    val userEvent = when (outcome.result) {
      ScheduledTaskRunResult.SKIPPED_SESSION_BUSY -> RuntimeNotificationUserEvent.BACKGROUND_TASK_PAUSED
      else -> RuntimeNotificationUserEvent.SCHEDULED_WAKE
    }
    val shouldNotify = when (outcome.result) {
      ScheduledTaskRunResult.ACCEPTED -> spec?.policy?.notifyOnQueued == true
      ScheduledTaskRunResult.SKIPPED_DUPLICATE,
      ScheduledTaskRunResult.SKIPPED_SNOOZED,
      -> false
      else -> true
    }
    if (!shouldNotify || !shouldDeliverUserNotification(userEvent)) {
      return null
    }
    val sessionId = outcome.sessionId ?: spec?.sessionId
    val sessionTitle = sessionId?.let(::sessionTitle)
    val titleResId = when (outcome.result) {
      ScheduledTaskRunResult.ACCEPTED -> R.string.runtime_notification_schedule_queued_title
      ScheduledTaskRunResult.SKIPPED_SESSION_BUSY -> R.string.runtime_notification_schedule_skipped_title
      else -> R.string.runtime_notification_schedule_failed_title
    }
    val body = when (outcome.result) {
      ScheduledTaskRunResult.ACCEPTED -> localizedContext.getString(
        R.string.runtime_notification_schedule_queued_body,
        sessionTitle ?: localizedContext.getString(R.string.chat_default_session_title),
      )
      ScheduledTaskRunResult.SKIPPED_SESSION_BUSY -> localizedContext.getString(
        R.string.runtime_notification_schedule_skipped_body,
      )
      else -> localizedContext.getString(
        R.string.runtime_notification_schedule_failed_body,
        outcome.failureReason?.trim()?.takeIf(String::isNotBlank)
          ?: localizedContext.getString(R.string.runtime_notification_schedule_failed_reason_unknown),
      )
    }
    return RuntimeScheduleNotificationModel(
      sessionId = sessionId,
      sessionTitle = sessionTitle,
      scheduleId = outcome.scheduleId,
      scheduleTitle = spec?.title?.trim()?.takeIf(String::isNotBlank)
        ?: localizedContext.getString(R.string.runtime_notification_schedule_default_title),
      title = localizedContext.getString(titleResId),
      body = body,
      actions = scheduleNotificationActions(
        outcome = outcome,
        spec = spec,
        sessionId = sessionId,
      ),
    )
  }

  private fun serviceRecoveredNotificationModel(
    bootstrapResult: RuntimeServiceBootstrapResult,
  ): RuntimeServiceRecoveredNotificationModel? {
    val resumedCount = bootstrapResult.resumedSessionIds.distinct().size
    val repairedCount = bootstrapResult.repairedSessionIds.distinct().size
    if (resumedCount <= 0 && repairedCount <= 0) {
      return null
    }
    if (!shouldDeliverUserNotification(RuntimeNotificationUserEvent.SERVICE_RECOVERED)) {
      return null
    }
    return RuntimeServiceRecoveredNotificationModel(
      title = localizedContext.getString(R.string.runtime_notification_service_recovered_title),
      body = localizedContext.getString(
        R.string.runtime_notification_service_recovered_body,
        resumedCount,
        repairedCount,
      ),
    )
  }

  internal fun buildApprovalNotification(
    model: RuntimeApprovalNotificationModel,
  ): Notification = NotificationCompat.Builder(
    localizedContext,
    RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_APPROVAL,
  )
    .setSmallIcon(android.R.drawable.stat_sys_warning)
    .setContentTitle(model.title)
    .setContentText(model.body)
    .setSubText(model.sessionTitle)
    .setContentIntent(
      openChatPendingIntent(
        sessionId = model.sessionId,
        approvalTaskId = model.taskId,
      ),
    )
    .setAutoCancel(false)
    .setOnlyAlertOnce(true)
    .setCategory(NotificationCompat.CATEGORY_REMINDER)
    .setPriority(NotificationCompat.PRIORITY_HIGH)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setStyle(NotificationCompat.BigTextStyle().bigText(model.body))
    .addAction(
      0,
      localizedContext.getString(R.string.runtime_notification_action_open),
      openChatPendingIntent(
        sessionId = model.sessionId,
        approvalTaskId = model.taskId,
      ),
    )
    .addAction(
      0,
      localizedContext.getString(R.string.runtime_notification_action_approve),
      approvalActionPendingIntent(
        action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
        model = model,
      ),
    )
    .addAction(
      0,
      localizedContext.getString(R.string.runtime_notification_action_reject),
      approvalActionPendingIntent(
        action = RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL,
        model = model,
      ),
    )
    .build()

  private fun buildTerminalNotification(
    model: RuntimeTerminalNotificationModel,
  ): Notification {
    val builder = NotificationCompat.Builder(
      localizedContext,
      RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_COMPLETION,
    )
      .setSmallIcon(
        if (model.interrupted) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_more,
      )
      .setContentTitle(model.title)
      .setContentText(model.body)
      .setSubText(model.sessionTitle)
      .setContentIntent(openChatPendingIntent(model.sessionId))
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setCategory(
        if (model.interrupted) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS,
      )
      .setPriority(
        if (model.interrupted) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
      )
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setStyle(NotificationCompat.BigTextStyle().bigText(model.body))
      .addAction(
        0,
        localizedContext.getString(
          if (model.interrupted) {
            R.string.runtime_notification_action_review
          } else {
            R.string.runtime_notification_action_open
          },
        ),
        openChatPendingIntent(model.sessionId),
      )
    terminalNotificationActionsForModel(model).forEach { action ->
      builder.addAction(
        0,
        localizedContext.getString(action.labelResId),
        terminalNotificationActionPendingIntent(action),
      )
    }
    return builder.build()
  }

  private fun buildScheduleNotification(
    model: RuntimeScheduleNotificationModel,
  ): Notification {
    val builder = NotificationCompat.Builder(
      localizedContext,
      RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_SCHEDULE,
    )
      .setSmallIcon(android.R.drawable.ic_popup_reminder)
      .setContentTitle(model.title)
      .setContentText(model.body)
      .setSubText(model.scheduleTitle)
      .setContentIntent(openScheduleDetailsPendingIntent(model))
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setStyle(NotificationCompat.BigTextStyle().bigText(model.body))
    model.actions.forEach { action ->
      builder.addAction(
        0,
        localizedContext.getString(action.labelResId),
        scheduleNotificationActionPendingIntent(action),
      )
    }
    return builder.build()
  }

  private fun scheduleNotificationActions(
    outcome: ScheduledTaskDispatchOutcome,
    spec: ScheduledTaskSpec?,
    sessionId: String?,
  ): List<RuntimeScheduleNotificationAction> = scheduleNotificationActionsForOutcome(
    outcome = outcome,
    spec = spec,
    sessionId = sessionId,
  )

  private fun buildServiceRecoveredNotification(
    model: RuntimeServiceRecoveredNotificationModel,
  ): Notification = NotificationCompat.Builder(
    localizedContext,
    RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_COMPLETION,
  )
    .setSmallIcon(android.R.drawable.stat_notify_sync)
    .setContentTitle(model.title)
    .setContentText(model.body)
    .setContentIntent(openChatPendingIntent(sessionId = null))
    .setAutoCancel(true)
    .setOnlyAlertOnce(true)
    .setCategory(NotificationCompat.CATEGORY_STATUS)
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setStyle(NotificationCompat.BigTextStyle().bigText(model.body))
    .addAction(
      0,
      localizedContext.getString(R.string.runtime_notification_action_open),
      openChatPendingIntent(sessionId = null),
    )
    .build()

  private fun shouldNotifyApproval(task: AgentTask): Boolean =
    (scheduledSpecFor(task)?.policy?.notifyOnApproval ?: true) &&
      shouldDeliverUserNotification(RuntimeNotificationUserEvent.APPROVAL_REQUEST)

  private fun shouldNotifyApprovalReminder(task: AgentTask): Boolean =
    (scheduledSpecFor(task)?.policy?.notifyOnApproval ?: true) &&
      shouldDeliverUserNotification(RuntimeNotificationUserEvent.APPROVAL_REMINDER)

  private fun shouldNotifyTerminal(
    spec: ScheduledTaskSpec?,
    interrupted: Boolean,
    result: ExecutionResult,
  ): Boolean {
    if (result.status == ExecutionStatus.CANCELLED) {
      return false
    }
    val scheduleAllows = if (interrupted) {
      spec?.policy?.notifyOnInterruption ?: true
    } else {
      spec?.policy?.notifyOnCompletion ?: true
    }
    val userEvent = if (interrupted || result.status != ExecutionStatus.SUCCESS) {
      RuntimeNotificationUserEvent.TASK_FAILED
    } else {
      RuntimeNotificationUserEvent.TASK_FINISHED
    }
    return scheduleAllows && shouldDeliverUserNotification(userEvent)
  }

  private fun shouldDeliverUserNotification(event: RuntimeNotificationUserEvent): Boolean =
    RuntimeNotificationUserPolicy.allows(
      settings = notificationSettingsProvider().sanitized(),
      event = event,
      minutesOfDay = currentLocalMinutesOfDay(),
    )

  private fun terminalPreviewText(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
    interrupted: Boolean,
  ): String {
    if (interrupted) {
      return sanitizePreviewText(
        result.errorMessage,
        fallback = localizedContext.getString(R.string.runtime_notification_interruption_body),
      )
    }
    val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val messagePreview = pendingMessageId
      ?.let { messageId ->
        chatSessionStore.loadSession(sessionId)
          ?.messages
          ?.firstOrNull { message -> message.messageId == messageId }
          ?.text
      }
    val successFallback = localizedContext.getString(R.string.runtime_notification_completion_body)
    val failureFallback = localizedContext.getString(R.string.runtime_notification_failure_body)
    return sanitizePreviewText(
      text = messagePreview ?: result.errorMessage,
      fallback = if (result.status == ExecutionStatus.SUCCESS) successFallback else failureFallback,
    )
  }

  private fun sessionTitle(sessionId: String): String =
    chatSessionStore.loadSession(sessionId)
      ?.title
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: localizedContext.getString(R.string.chat_default_session_title)

  private fun updateScheduledRunRecord(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    val scheduleRunId = task.metadata[ScheduledTaskMetadataKeys.SCHEDULE_RUN_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return
    val existing = scheduledTaskRunRecordStore.get(scheduleRunId) ?: return
    val nextResult = if (isApprovalRequiredResult(result)) {
      ScheduledTaskRunResult.WAITING_APPROVAL
    } else if (isInterruptedResult(result)) {
      ScheduledTaskRunResult.COMPLETED_INTERRUPTED
    } else {
      when (result.status) {
        ExecutionStatus.SUCCESS -> ScheduledTaskRunResult.COMPLETED_SUCCESS
        ExecutionStatus.CANCELLED -> ScheduledTaskRunResult.COMPLETED_CANCELLED
        else -> ScheduledTaskRunResult.COMPLETED_FAILED
      }
    }
    scheduledTaskRunRecordStore.upsert(
      existing.copy(
        sessionId = sessionId,
        result = nextResult,
        failureReason = result.errorMessage?.trim()?.takeIf(String::isNotBlank),
        updatedAtEpochMs = result.finishedAtEpochMs,
      ),
    )
  }

  private fun updateScheduledRunRecordWaitingApproval(
    sessionId: String,
    task: AgentTask,
  ) {
    val scheduleRunId = task.metadata[ScheduledTaskMetadataKeys.SCHEDULE_RUN_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return
    val existing = scheduledTaskRunRecordStore.get(scheduleRunId) ?: return
    if (existing.result != ScheduledTaskRunResult.ACCEPTED) {
      return
    }
    scheduledTaskRunRecordStore.upsert(
      existing.copy(
        sessionId = sessionId,
        result = ScheduledTaskRunResult.WAITING_APPROVAL,
        updatedAtEpochMs = System.currentTimeMillis(),
      ),
    )
  }

  private fun scheduledSpecFor(task: AgentTask): ScheduledTaskSpec? =
    task.metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(scheduledTaskSpecStore::get)

  private fun cancelApprovalsForTask(
    sessionId: String,
    taskId: String,
    runId: String? = null,
  ) {
    val normalizedRunId = runId?.trim()?.takeIf(String::isNotBlank)
    val keys: List<RuntimeNotificationKey> = synchronized(lock) {
      activeApprovalNotifications.values
        .filter { entry ->
          entry.sessionId == sessionId &&
            entry.taskId == taskId &&
            (normalizedRunId == null || entry.runId == normalizedRunId)
        }
        .map { entry -> entry.key }
        .also { matching ->
          matching.forEach { key -> activeApprovalNotifications.remove(key.tag) }
        }
    }
    keys.forEach { key -> notificationManager.cancel(key.tag, key.id) }
    cancelLegacyApprovalSlot(taskId)
  }

  private fun trackActiveApproval(model: RuntimeApprovalNotificationModel) {
    synchronized(lock) {
      trackActiveApprovalLocked(model)
    }
  }

  private fun trackActiveApprovalLocked(model: RuntimeApprovalNotificationModel) {
    activeApprovalNotifications[model.notificationKey.tag] = TrackedApprovalNotification(
      sessionId = model.sessionId,
      taskId = model.taskId,
      runId = model.runId,
      key = model.notificationKey,
    )
  }

  private fun cancelLegacyApprovalSlot(taskId: String) {
    notificationManager.cancel(RuntimeNotificationKeys.legacyApprovalId(taskId))
  }

  private fun cancelLegacyTerminalSlots(taskId: String) {
    notificationManager.cancel(RuntimeNotificationKeys.legacyTerminalCompletedId(taskId))
    notificationManager.cancel(RuntimeNotificationKeys.legacyTerminalInterruptedId(taskId))
  }

  private fun openChatPendingIntent(
    sessionId: String?,
    approvalTaskId: String? = null,
  ): PendingIntent {
    val intent = Intent(appContext, AppShellActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, AppShellTab.CHAT.routeKey)
      sessionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { resolvedSessionId ->
          putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID, resolvedSessionId)
        }
      approvalTaskId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { resolvedTaskId ->
          putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_TASK_ID, resolvedTaskId)
        }
    }
    return PendingIntent.getActivity(
      appContext,
      RuntimeNotificationKeys.stableRequestCode("open:$sessionId:$approvalTaskId"),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun openScheduleDetailsPendingIntent(
    model: RuntimeScheduleNotificationModel,
  ): PendingIntent {
    val destination = scheduleNotificationOpenDestination()
    val intent = Intent(appContext, AppShellActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(AppShellNavigationExtras.EXTRA_START_TAB, destination.selectedTab.routeKey)
      putExtra(
        AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE,
        destination.settingsSubpage.routeKey,
      )
      putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SCHEDULE_ID, model.scheduleId)
      model.sessionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { resolvedSessionId ->
          putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID, resolvedSessionId)
        }
    }
    return PendingIntent.getActivity(
      appContext,
      RuntimeNotificationKeys.stableRequestCode("open-schedule:${model.scheduleId}:${model.sessionId.orEmpty()}"),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun approvalActionPendingIntent(
    action: String,
    model: RuntimeApprovalNotificationModel,
  ): PendingIntent {
    return runtimeServiceAccessGateway.approvalActionPendingIntent(
      context = appContext,
      action = action,
      sessionId = model.sessionId,
      taskId = model.taskId,
      runId = model.runId,
      executionId = model.executionBinding.normalizedExecutionId,
      executionOrdinal = model.executionBinding.normalizedExecutionOrdinal,
      requestCode = RuntimeNotificationKeys.stableRequestCode(
        RuntimeNotificationKeys.approvalActionRequestKey(
          action = action,
          sessionId = model.sessionId,
          runId = model.runId,
          taskId = model.taskId,
          executionBinding = model.executionBinding,
        ),
      ),
      target = model.runtimeTarget,
    )
  }

  private fun scheduleNotificationActionPendingIntent(
    action: RuntimeScheduleNotificationAction,
  ): PendingIntent = runtimeServiceAccessGateway.scheduleNotificationActionPendingIntent(
    context = appContext,
    action = action.action,
    scheduleId = action.scheduleId,
    sessionId = action.sessionId,
    taskId = action.taskId,
    runId = action.runId,
    requestCode = RuntimeNotificationKeys.stableRequestCode(
      "${action.action}:${action.scheduleId}:${action.sessionId.orEmpty()}:${action.taskId.orEmpty()}:${action.runId.orEmpty()}",
    ),
    target = action.runtimeTarget,
  )

  private fun terminalNotificationActionPendingIntent(
    action: RuntimeTerminalNotificationAction,
  ): PendingIntent = runtimeServiceAccessGateway.chatWriteActionPendingIntent(
    context = appContext,
    command = action.command,
    requestCode = RuntimeNotificationKeys.stableRequestCode(action.requestKey),
    target = action.runtimeTarget,
    terminalNotificationTaskId = action.terminalNotificationTaskId,
  )

  private fun knownSessionIds(): List<String> {
    return knownChatSessionIds(chatSessionStore)
  }

  private fun currentHostAccess(): RuntimeNotificationHostAccess = synchronized(lock) { hostAccess }

  private fun approvalIsHighRisk(
    errorCode: String?,
    metadata: Map<String, String>,
  ): Boolean = approvalMetadataIsHighRisk(
    errorCode = errorCode,
    highRiskErrorCode = ERROR_HIGH_RISK_APPROVAL_REQUIRED,
    metadata = metadata,
  )

  private fun sanitizeApprovalBody(
    body: String?,
    isHighRisk: Boolean,
  ): String {
    val fallback = if (isHighRisk) {
      localizedContext.getString(R.string.chat_high_risk_approval_required_body)
    } else {
      localizedContext.getString(R.string.chat_summary_approval_required)
    }
    val candidate = body?.trim().orEmpty()
    if (candidate.isEmpty()) {
      return fallback
    }
    return if (looksLikeInternalToolPayload(candidate)) fallback else candidate
  }

  private fun composeApprovalBody(
    body: String,
    toolReason: String?,
    metadata: Map<String, String>,
  ): String = approvalSupportComposeBody(
    body = body,
    toolReason = toolReason,
    metadata = metadata,
    isChinese = isChineseNotificationLocale(),
  )

  private fun sanitizePotentialInternalAgentText(text: String, fallback: String): String =
    approvalSupportSanitizePotentialInternalAgentText(text = text, fallback = fallback)

  private fun sanitizePreviewText(
    text: String?,
    fallback: String,
  ): String {
    val normalized = sanitizePotentialInternalAgentText(text?.trim().orEmpty(), fallback)
      .replace(Regex("""\s+"""), " ")
      .trim()
    return normalized.take(160).ifBlank { fallback }
  }

  private fun looksLikeInternalToolPayload(text: String): Boolean =
    approvalSupportLooksLikeInternalToolPayload(text)

  private fun isChineseNotificationLocale(): Boolean =
    localizedContext.resources.configuration.locales[0]
      ?.toLanguageTag()
      ?.startsWith("zh", ignoreCase = true) == true

  private fun isApprovalRequiredResult(result: ExecutionResult): Boolean =
    isApprovalRequiredError(result.errorCode) && result.status == ExecutionStatus.DENIED

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun isInterruptedResult(result: ExecutionResult): Boolean =
    result.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      result.errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  companion object {
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"

    internal fun dismissApprovalNotification(
      context: Context,
      taskId: String?,
    ) {
      val normalizedTaskId = taskId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      manager.cancel(RuntimeNotificationKeys.legacyApprovalId(normalizedTaskId))
    }

    internal fun dismissApprovalNotification(
      context: Context,
      command: RuntimeServiceNotificationCommand,
    ) {
      val sessionId = command.sessionId?.trim()?.takeIf(String::isNotBlank)
      val taskId = command.taskId?.trim()?.takeIf(String::isNotBlank)
      val executionBinding = when (command) {
        is RuntimeServiceNotificationCommand.ApproveApproval -> RuntimeApprovalExecutionBinding(
          executionId = command.executionId,
          executionOrdinal = command.executionOrdinal,
        )
        is RuntimeServiceNotificationCommand.RejectApproval -> RuntimeApprovalExecutionBinding(
          executionId = command.executionId,
          executionOrdinal = command.executionOrdinal,
        )
        else -> null
      }
      if (sessionId == null || taskId == null || executionBinding == null) {
        dismissApprovalNotification(context, command.taskId)
        return
      }
      val key = RuntimeNotificationKeys.approvalKey(
        sessionId = sessionId,
        runId = command.runId?.trim()?.takeIf(String::isNotBlank) ?: taskId,
        taskId = taskId,
        executionBinding = executionBinding,
      )
      context.getSystemService(NotificationManager::class.java)
        ?.cancel(key.tag, key.id)
      dismissApprovalNotification(context, taskId)
    }

    internal fun dismissScheduleNotifications(
      context: Context,
      scheduleId: String?,
    ) {
      val normalizedScheduleId = scheduleId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      ScheduledTaskRunResult.entries.forEach { outcome ->
        val key = RuntimeNotificationKeys.scheduleKey(normalizedScheduleId, outcome.name)
        manager.cancel(key.tag, key.id)
      }
      RuntimeNotificationKeys.legacyScheduleIdsForAllOutcomes(normalizedScheduleId).forEach { legacyId ->
        manager.cancel(legacyId)
      }
    }

    private fun terminalNotificationDeliveryKey(runId: String): String = "terminal:$runId"

    internal fun dismissTerminalInterruptedNotification(
      context: Context,
      runId: String?,
      taskId: String?,
    ) {
      val normalizedTaskId = taskId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return
      val manager = context.getSystemService(NotificationManager::class.java) ?: return
      val normalizedRunId = runId?.trim()?.takeIf(String::isNotBlank) ?: normalizedTaskId
      val key = RuntimeNotificationKeys.terminalKey(
        runId = normalizedRunId,
        taskId = normalizedTaskId,
        interrupted = true,
      )
      manager.cancel(key.tag, key.id)
      manager.cancel(RuntimeNotificationKeys.legacyTerminalInterruptedId(normalizedTaskId))
    }

    internal fun dismissTerminalInterruptedNotification(
      context: Context,
      taskId: String?,
    ) {
      dismissTerminalInterruptedNotification(context, runId = null, taskId = taskId)
    }

    private fun terminalNotificationFingerprint(
      terminalModel: RuntimeTerminalNotificationModel,
      updatedAtEpochMs: Long,
    ): String = listOf(
      terminalModel.runId,
      terminalModel.interrupted,
      terminalModel.title,
      terminalModel.body,
      updatedAtEpochMs,
    ).joinToString("|")
  }
}

internal fun runtimeServiceTargetForNotificationTask(
  task: AgentTask,
): RuntimeServiceTarget = runtimeServiceTargetForTask(task)

internal fun terminalNotificationActionsForModel(
  model: RuntimeTerminalNotificationModel,
): List<RuntimeTerminalNotificationAction> {
  if (!model.interrupted) {
    return emptyList()
  }
  return listOf(
    RuntimeTerminalNotificationAction(
      command = OpenCrayChatWriteCommand.RetryChatRun(model.runId),
      labelResId = R.string.runtime_notification_action_retry,
      runtimeTarget = model.runtimeTarget,
      requestKey = "retry-interrupted:${model.sessionId}:${model.taskId}:${model.runId}",
      terminalNotificationTaskId = model.taskId,
    ),
  )
}

internal fun scheduleNotificationOpenDestination(): AppShellDestination =
  AppShellDestination(
    selectedTab = AppShellTab.SETTINGS,
    settingsSubpage = SettingsSubpage.SCHEDULED_TASKS,
  )
