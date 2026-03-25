package com.opencray.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellTab
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import java.util.Locale
import kotlin.math.absoluteValue
import org.opencray.app.R

internal object RuntimeNotificationIntentActions {
  const val ACTION_APPROVE_RUNTIME_APPROVAL: String =
    "com.opencray.app.action.APPROVE_RUNTIME_APPROVAL"
  const val ACTION_REJECT_RUNTIME_APPROVAL: String =
    "com.opencray.app.action.REJECT_RUNTIME_APPROVAL"
}

internal object RuntimeNotificationIntentExtras {
  const val EXTRA_NOTIFICATION_SESSION_ID: String = "notificationSessionId"
  const val EXTRA_NOTIFICATION_TASK_ID: String = "notificationTaskId"
  const val EXTRA_NOTIFICATION_RUN_ID: String = "notificationRunId"
  const val EXTRA_NOTIFICATION_SCHEDULE_ID: String = "notificationScheduleId"
}

internal data class RuntimeApprovalNotificationModel(
  val sessionId: String,
  val sessionTitle: String,
  val runId: String,
  val taskId: String,
  val title: String,
  val body: String,
  val isHighRisk: Boolean,
)

internal data class RuntimeTerminalNotificationModel(
  val sessionId: String,
  val sessionTitle: String,
  val runId: String,
  val taskId: String,
  val title: String,
  val body: String,
  val interrupted: Boolean,
)

internal data class RuntimeScheduleNotificationModel(
  val sessionId: String?,
  val sessionTitle: String?,
  val scheduleId: String,
  val scheduleTitle: String,
  val title: String,
  val body: String,
)

internal class RuntimeNotificationCoordinator(
  private val appContext: Context,
  private val localizedContext: Context,
  private val chatSessionStore: ChatSessionLocalStore,
  private val hostAccess: OpenCrayRuntimeHostAccess,
  private val scheduledTaskSpecStore: ScheduledTaskSpecStore,
  private val scheduledTaskRunRecordStore: ScheduledTaskRunRecordStore,
  private val terminalDeliveryStore: RuntimeNotificationDeliveryStore =
    FileBackedRuntimeNotificationDeliveryStoreFactory.fromContext(appContext).create(),
  private val notificationManager: NotificationManager = checkNotNull(
    appContext.getSystemService(NotificationManager::class.java),
  ) {
    "NotificationManager is unavailable."
  },
  private val appVisibleProvider: () -> Boolean = AppVisibilityMonitor::isAppVisible,
) {
  private val lock = Any()
  private var started: Boolean = false
  private var hostObservationDisposer: (() -> Unit)? = null
  private var visibilityObservationDisposer: (() -> Unit)? = null
  private var activeApprovalTaskIds: Set<String> = emptySet()

  fun start() {
    synchronized(lock) {
      if (started) {
        return
      }
      started = true
      hostObservationDisposer = hostAccess.observe(hostListener)
      visibilityObservationDisposer = AppVisibilityMonitor.observe(::onAppVisibilityChanged)
    }
    syncPendingApprovalNotifications()
    if (!appVisibleProvider()) {
      syncOutstandingTerminalNotifications()
    }
  }

  fun dispose() {
    val hostDisposer: (() -> Unit)?
    val visibilityDisposer: (() -> Unit)?
    synchronized(lock) {
      started = false
      activeApprovalTaskIds = emptySet()
      hostDisposer = hostObservationDisposer
      visibilityDisposer = visibilityObservationDisposer
      hostObservationDisposer = null
      visibilityObservationDisposer = null
    }
    hostDisposer?.invoke()
    visibilityDisposer?.invoke()
  }

  fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
    if (appVisibleProvider()) {
      return
    }
    val spec = scheduledTaskSpecStore.get(outcome.scheduleId)
    val model = scheduleNotificationModel(outcome = outcome, spec = spec) ?: return
    notificationManager.notify(
      scheduleNotificationId(scheduleId = model.scheduleId, outcome = outcome.result.name),
      buildScheduleNotification(model),
    )
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
        addActiveApprovalTaskId(approvalModel.taskId)
        if (!appVisibleProvider() && shouldNotifyApproval(task = task)) {
          notificationManager.notify(
            approvalNotificationId(approvalModel.taskId),
            buildApprovalNotification(approvalModel),
          )
        }
      }
      return
    }

    removeActiveApprovalTaskId(task.id)
    cancelApprovalNotification(task.id)
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
          removeActiveApprovalTaskId(event.taskId)
          cancelApprovalNotification(event.taskId)
        }
      }

      is OpenCrayCancellationEvent -> {
        removeActiveApprovalTaskId(event.taskId)
        cancelApprovalNotification(event.taskId)
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
    val taskIds = models.mapTo(linkedSetOf(), RuntimeApprovalNotificationModel::taskId)
    val staleTaskIds = synchronized(lock) {
      val stale = activeApprovalTaskIds - taskIds
      activeApprovalTaskIds = taskIds
      stale
    }
    staleTaskIds.forEach(::cancelApprovalNotification)
    if (appVisibleProvider()) {
      return
    }
    models.forEach { model ->
      notificationManager.notify(
        approvalNotificationId(model.taskId),
        buildApprovalNotification(model),
      )
    }
  }

  private fun syncOutstandingTerminalNotifications() {
    for (sessionId in knownSessionIds()) {
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
    val session = hostAccess.session(sessionId)
    val runsByTaskId = session.listRuns().associateBy(AgentRunSnapshot::taskId)
    return session.snapshot().tasks
      .asSequence()
      .filter { taskSnapshot ->
        (taskSnapshot.lifecycleState == QueueTaskLifecycleState.SUSPENDED ||
          taskSnapshot.lifecycleState == QueueTaskLifecycleState.FAILED) &&
          isApprovalRequiredError(taskSnapshot.lastErrorCode) &&
          shouldNotifyApproval(taskSnapshot.task)
      }
      .mapNotNull { taskSnapshot ->
        val runSnapshot = runsByTaskId[taskSnapshot.task.id]
        approvalNotificationModel(
          sessionId = sessionId,
          task = taskSnapshot.task,
          errorCode = taskSnapshot.lastErrorCode,
          metadata = runSnapshot?.resultMetadata.orEmpty(),
          errorBody = runSnapshot?.errorMessage ?: taskSnapshot.lastErrorMessage,
          toolReason = runSnapshot?.resultMetadata?.get("toolReason")
            ?: (runSnapshot?.lastEvent as? OpenCrayToolCallEvent)?.call?.reason,
        )
      }
      .toList()
  }

  private fun onAppVisibilityChanged(isVisible: Boolean) {
    if (isVisible) {
      synchronized(lock) {
        activeApprovalTaskIds.toList()
      }.forEach(::cancelApprovalNotification)
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
      title = if (isHighRisk) {
        localizedContext.getString(R.string.chat_high_risk_approval_required_title)
      } else {
        localizedContext.getString(R.string.chat_approval_required_title)
      },
      body = body,
      isHighRisk = isHighRisk,
    )
  }

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
    notificationManager.notify(
      terminalNotificationId(
        taskId = terminalModel.taskId,
        interrupted = terminalModel.interrupted,
      ),
      buildTerminalNotification(terminalModel),
    )
    terminalDeliveryStore.markDelivered(deliveryKey, fingerprint)
  }

  private fun scheduleNotificationModel(
    outcome: ScheduledTaskDispatchOutcome,
    spec: ScheduledTaskSpec?,
  ): RuntimeScheduleNotificationModel? {
    val shouldNotify = when (outcome.result) {
      ScheduledTaskRunResult.ACCEPTED -> spec?.policy?.notifyOnQueued == true
      ScheduledTaskRunResult.SKIPPED_DUPLICATE -> false
      else -> true
    }
    if (!shouldNotify) {
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
    )
  }

  private fun buildApprovalNotification(
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
        sessionId = model.sessionId,
        taskId = model.taskId,
        runId = model.runId,
      ),
    )
    .addAction(
      0,
      localizedContext.getString(R.string.runtime_notification_action_reject),
      approvalActionPendingIntent(
        action = RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL,
        sessionId = model.sessionId,
        taskId = model.taskId,
        runId = model.runId,
      ),
    )
    .build()

  private fun buildTerminalNotification(
    model: RuntimeTerminalNotificationModel,
  ): Notification = NotificationCompat.Builder(
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
    .build()

  private fun buildScheduleNotification(
    model: RuntimeScheduleNotificationModel,
  ): Notification = NotificationCompat.Builder(
    localizedContext,
    RuntimeNotificationChannelRegistry.CHANNEL_RUNTIME_SCHEDULE,
  )
    .setSmallIcon(android.R.drawable.ic_popup_reminder)
    .setContentTitle(model.title)
    .setContentText(model.body)
    .setSubText(model.scheduleTitle)
    .setContentIntent(openChatPendingIntent(model.sessionId))
    .setAutoCancel(true)
    .setOnlyAlertOnce(true)
    .setCategory(NotificationCompat.CATEGORY_STATUS)
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setStyle(NotificationCompat.BigTextStyle().bigText(model.body))
    .addAction(
      0,
      localizedContext.getString(R.string.runtime_notification_action_open),
      openChatPendingIntent(model.sessionId),
    )
    .build()

  private fun shouldNotifyApproval(task: AgentTask): Boolean =
    scheduledSpecFor(task)?.policy?.notifyOnApproval ?: true

  private fun shouldNotifyTerminal(
    spec: ScheduledTaskSpec?,
    interrupted: Boolean,
    result: ExecutionResult,
  ): Boolean {
    if (result.status == ExecutionStatus.CANCELLED) {
      return false
    }
    return if (interrupted) {
      spec?.policy?.notifyOnInterruption ?: true
    } else {
      spec?.policy?.notifyOnCompletion ?: true
    }
  }

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

  private fun cancelApprovalNotification(taskId: String) {
    dismissApprovalNotification(appContext, taskId)
  }

  private fun addActiveApprovalTaskId(taskId: String) {
    synchronized(lock) {
      activeApprovalTaskIds = activeApprovalTaskIds + taskId
    }
  }

  private fun removeActiveApprovalTaskId(taskId: String) {
    synchronized(lock) {
      activeApprovalTaskIds = activeApprovalTaskIds - taskId
    }
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
      stableRequestCode("open:$sessionId:$approvalTaskId"),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun approvalActionPendingIntent(
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
  ): PendingIntent {
    val intent = Intent(appContext, OpenCrayAgentRuntimeService::class.java).apply {
      setAction(action)
      putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID, sessionId)
      putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_TASK_ID, taskId)
      putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_RUN_ID, runId)
    }
    return PendingIntent.getService(
      appContext,
      stableRequestCode("$action:$sessionId:$taskId"),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun knownSessionIds(): List<String> {
    val state = chatSessionStore.loadState()
    return buildList {
      add(state.activeSession.sessionId)
      addAll(state.sessions.map(ChatSessionLocalStore.SessionSummary::sessionId))
    }.distinct()
  }

  private fun approvalIsHighRisk(
    errorCode: String?,
    metadata: Map<String, String>,
  ): Boolean =
    errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED ||
      metadata[SubAgentApprovalResumeMetadata.KEY_IS_HIGH_RISK]
        ?.trim()
        ?.equals("true", ignoreCase = true) == true

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
  ): String {
    val details = mutableListOf<String>()
    approvalPrimaryDetailLine(metadata)?.let(details::add)
    approvalPathDetailLines(metadata).forEach(details::add)
    approvalWorkingDirectoryLine(metadata)?.let(details::add)
    approvalReasonLine(toolReason)?.let(details::add)
    if (details.isEmpty()) {
      return body
    }
    return buildString {
      details.forEach { line -> appendLine(line) }
      appendLine()
      append(body)
    }.trim()
  }

  private fun approvalPrimaryDetailLine(metadata: Map<String, String>): String? =
    when {
      metadata["scriptPath"]?.isNotBlank() == true ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("script")}: $detail"
        }
      shellCommandSummary(metadata) != null ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("command")}: $detail"
        }
      metadata["query"]?.isNotBlank() == true ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("query")}: $detail"
        }
      metadata["requestedUrl"]?.isNotBlank() == true || metadata["finalUrl"]?.isNotBlank() == true ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("url")}: $detail"
        }
      metadata["processId"]?.isNotBlank() == true && metadata["targetKind"] == "process" ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("process")}: $detail"
        }
      else ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("request")}: $detail"
        }
    }

  private fun approvalPrimaryDetailValue(metadata: Map<String, String>): String? {
    metadata["scriptPath"]?.takeIf(String::isNotBlank)?.let { return it }
    shellCommandSummary(metadata)?.let { return it }
    metadata["query"]?.takeIf(String::isNotBlank)?.let { return it }
    metadata["requestedUrl"]?.takeIf(String::isNotBlank)?.let { return it }
    metadata["finalUrl"]?.takeIf(String::isNotBlank)?.let { return it }
    metadata["processId"]?.takeIf(String::isNotBlank)?.let { processId ->
      if (metadata["targetKind"] == "process") {
        return processId
      }
    }
    metadata["delegationDescription"]?.takeIf(String::isNotBlank)?.let { return it }
    metadata["targetSummary"]?.takeIf(String::isNotBlank)?.let { return it }
    return null
  }

  private fun approvalPathDetailLines(metadata: Map<String, String>): List<String> {
    val sourcePath = metadata["sourcePath"]?.trim().orEmpty()
    val destinationPath = metadata["destinationPath"]?.trim().orEmpty()
    val delegationPromptPreview = metadata["delegationPromptPreview"]?.trim().orEmpty()
    val delegationAllowedTools = metadata["delegationAllowedTools"]?.trim().orEmpty()
    if (sourcePath.isNotEmpty() || destinationPath.isNotEmpty()) {
      return buildList {
        if (sourcePath.isNotEmpty()) {
          add("${approvalLabel("from")}: $sourcePath")
        }
        if (destinationPath.isNotEmpty()) {
          add("${approvalLabel("to")}: $destinationPath")
        }
        if (delegationPromptPreview.isNotEmpty()) {
          add("${approvalLabel("prompt")}: $delegationPromptPreview")
        }
        if (delegationAllowedTools.isNotEmpty()) {
          add("${approvalLabel("allowed_tools")}: $delegationAllowedTools")
        }
      }
    }
    val primaryTargetPath = metadata["primaryTargetPath"]?.trim().orEmpty()
    val secondaryTargetPath = metadata["secondaryTargetPath"]?.trim().orEmpty()
    val scriptPath = metadata["scriptPath"]?.trim().orEmpty()
    val workingDirectory = metadata["workingDirectory"]?.trim().orEmpty()
    return buildList {
      if (
        primaryTargetPath.isNotEmpty() &&
        primaryTargetPath != scriptPath &&
        primaryTargetPath != workingDirectory
      ) {
        add("${approvalLabel("target")}: $primaryTargetPath")
      }
      if (secondaryTargetPath.isNotEmpty()) {
        add("${approvalLabel("to")}: $secondaryTargetPath")
      }
      if (delegationPromptPreview.isNotEmpty()) {
        add("${approvalLabel("prompt")}: $delegationPromptPreview")
      }
      if (delegationAllowedTools.isNotEmpty()) {
        add("${approvalLabel("allowed_tools")}: $delegationAllowedTools")
      }
    }
  }

  private fun approvalWorkingDirectoryLine(metadata: Map<String, String>): String? =
    metadata["workingDirectory"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { workingDirectory ->
        "${approvalLabel("working_directory")}: $workingDirectory"
      }

  private fun approvalReasonLine(toolReason: String?): String? =
    sanitizePotentialInternalAgentText(toolReason?.trim().orEmpty(), fallback = "")
      .trim()
      .takeIf(String::isNotBlank)
      ?.let { reason ->
        "${approvalLabel("reason")}: $reason"
      }

  private fun approvalLabel(kind: String): String {
    val isChinese = localizedContext.resources.configuration.locales[0]
      ?.toLanguageTag()
      ?.startsWith("zh", ignoreCase = true) == true
    return when (kind) {
      "command" -> if (isChinese) "命令" else "Command"
      "script" -> if (isChinese) "脚本" else "Script"
      "query" -> if (isChinese) "查询" else "Query"
      "url" -> if (isChinese) "地址" else "URL"
      "process" -> if (isChinese) "进程" else "Process"
      "request" -> if (isChinese) "操作" else "Request"
      "prompt" -> if (isChinese) "委派内容" else "Prompt"
      "allowed_tools" -> if (isChinese) "可用工具" else "Allowed tools"
      "from" -> if (isChinese) "来源" else "From"
      "to" -> if (isChinese) "目标" else "To"
      "target" -> if (isChinese) "目标" else "Target"
      "working_directory" -> if (isChinese) "工作目录" else "Working directory"
      "reason" -> if (isChinese) "理由" else "Agent reason"
      else -> if (isChinese) "详情" else "Details"
    }
  }

  private fun shellCommandSummary(metadata: Map<String, String>): String? {
    metadata["shellCommand"]?.takeIf(String::isNotBlank)?.let { return it }
    val command = metadata["command"]?.trim().orEmpty()
    if (command.isEmpty()) {
      return null
    }
    val args = metadata["args"]
      ?.split('\u0000')
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      .orEmpty()
    return buildString {
      append(command)
      if (args.isNotEmpty()) {
        append(' ')
        append(args.joinToString(separator = " "))
      }
    }.trim()
  }

  private fun sanitizePotentialInternalAgentText(text: String, fallback: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) {
      return fallback
    }
    return if (looksLikeInternalToolPayload(trimmed)) fallback else text
  }

  private fun sanitizePreviewText(
    text: String?,
    fallback: String,
  ): String {
    val normalized = sanitizePotentialInternalAgentText(text?.trim().orEmpty(), fallback)
      .replace(Regex("""\s+"""), " ")
      .trim()
    return normalized.take(160).ifBlank { fallback }
  }

  private fun looksLikeInternalToolPayload(text: String): Boolean {
    val jsonCandidate = extractEmbeddedJsonObject(text) ?: return false
    val normalized = jsonCandidate.lowercase(Locale.US)
    val explicitToolAction =
      "\"type\"" in normalized &&
        ("\"tool_call\"" in normalized || "\"tool\"" in normalized)
    val toolArgumentShape = "\"tool_name\"" in normalized && "\"arguments\"" in normalized
    return explicitToolAction || toolArgumentShape
  }

  private fun extractEmbeddedJsonObject(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return trimmed
    }
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
      when {
        inString && escaped -> escaped = false
        inString && character == '\\' -> escaped = true
        character == '"' -> inString = !inString
        !inString && character == '{' -> {
          if (depth == 0) {
            startIndex = index
          }
          depth += 1
        }

        !inString && character == '}' -> {
          depth -= 1
          if (depth == 0 && startIndex >= 0) {
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  private fun isApprovalRequiredResult(result: ExecutionResult): Boolean =
    isApprovalRequiredError(result.errorCode) && result.status == ExecutionStatus.DENIED

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun isInterruptedResult(result: ExecutionResult): Boolean =
    result.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE ||
      result.errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
      result.metadata[METADATA_RESTORED_TERMINAL_STATE] == RESTORED_TERMINAL_STATE_INTERRUPTED

  private fun approvalNotificationId(taskId: String): Int =
    approvalNotificationIdForTask(taskId)

  private fun terminalNotificationId(taskId: String, interrupted: Boolean): Int =
    (if (interrupted) 52_700 else 52_300) + notificationStableHash(taskId, modulo = 4_000)

  private fun scheduleNotificationId(scheduleId: String, outcome: String): Int =
    53_100 + notificationStableHash("$scheduleId:$outcome", modulo = 4_000)

  private fun stableRequestCode(key: String): Int = 60_000 + notificationStableHash(key, modulo = 30_000)

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
      context.getSystemService(NotificationManager::class.java)
        ?.cancel(approvalNotificationIdForTask(normalizedTaskId))
    }

    private fun approvalNotificationIdForTask(taskId: String): Int =
      52_100 + notificationStableHash(taskId, modulo = 5_000)

    private fun terminalNotificationDeliveryKey(runId: String): String = "terminal:$runId"

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

    private fun notificationStableHash(key: String, modulo: Int): Int =
      (key.hashCode().absoluteValue % modulo).coerceAtLeast(0)
  }
}
