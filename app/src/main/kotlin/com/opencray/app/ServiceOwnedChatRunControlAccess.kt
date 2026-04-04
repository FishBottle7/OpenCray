package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState

internal class ChatRunControlCoordinator(
  private val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  private val findRunSnapshotForIdentifier: (String) -> AgentRunSnapshot?,
  private val pendingApprovalForRun: (AgentRunSnapshot) -> PendingApprovalSnapshot?,
  private val clearPendingApproval: (String, String) -> Unit,
  private val clearApproval: (String, String) -> Unit,
  private val clearPromptCheckpoint: (String, String) -> Unit,
  private val recordRuntimeEvent: (String, OpenCrayAgentRunEvent) -> Unit,
  private val runCancellationReplayRecorder:
    (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit,
  private val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
  private val subAgentTerminalEventFactory:
    (PendingApprovalSnapshot, String, Long) -> OpenCraySubAgentEvent? = { _, _, _ -> null },
  private val cancellationEventFactory:
    (AgentRunSnapshot, PendingApprovalSnapshot?, Long) -> OpenCrayCancellationEvent,
  private val delegatedChildCancelledWhileWaitingSummaryProvider: () -> String,
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) {
  fun interruptChatRun(taskIdOrRunId: String) {
    val run = requireNotNull(findRunSnapshotForIdentifier(taskIdOrRunId)) {
      "Run '$taskIdOrRunId' is unavailable."
    }
    val approval = pendingApprovalForRun(run)
    val interruptedWaitingApproval = approval != null && isApprovalWaitingRun(run)
    if (!interruptedWaitingApproval) {
      val cancelled = runtimeHostAccess.session(run.sessionId).requestCancel(run.taskId)
      check(cancelled) {
        "Unable to cancel run '$taskIdOrRunId'."
      }
    }
    val emittedAtEpochMs = nowEpochMsProvider()
    runCancellationReplayRecorder(
      run.sessionId,
      run.taskId,
      run.runId,
      approval?.toolName,
      run.replayExecutionContext(),
    )
    if (!interruptedWaitingApproval) {
      clearPendingApproval(run.sessionId, run.taskId)
      clearApproval(run.sessionId, run.taskId)
      clearPromptCheckpoint(run.sessionId, run.taskId)
      approval?.let { pendingApproval ->
        subAgentTerminalEventFactory(
          pendingApproval,
          delegatedChildCancelledWhileWaitingSummaryProvider(),
          emittedAtEpochMs,
        )?.let { event ->
          subAgentReplayRecorder(run.sessionId, event)
          recordRuntimeEvent(run.sessionId, event)
        }
      }
    }
    recordRuntimeEvent(
      run.sessionId,
      cancellationEventFactory(
        run,
        approval,
        emittedAtEpochMs,
      ),
    )
  }

  fun retryChatRun(taskIdOrRunId: String) {
    val run = requireNotNull(findRunSnapshotForIdentifier(taskIdOrRunId)) {
      "Run '$taskIdOrRunId' is unavailable."
    }
    val runtimeSession = runtimeHostAccess.session(run.sessionId)
    val resumed = if (chatRunIsLlmRetryPausedAwaitingResume(run)) {
      runtimeSession.requestResumeTask(
        taskId = run.taskId,
        executionKind = EXECUTION_KIND_CHECKPOINT_RESUME,
        taskMetadataUpdates = emptyMap(),
      )
    } else if (chatRunIsDeferredApprovalAwaitingResume(run, runtimeHostAccess)) {
      runtimeSession.requestResumeTask(run.taskId)
    } else {
      false
    }
    if (!resumed) {
      val retried = runtimeSession.requestRetry(run.taskId)
      check(retried) {
        "Unable to retry run '$taskIdOrRunId'."
      }
    }
  }

  private fun isApprovalWaitingRun(run: AgentRunSnapshot): Boolean =
    run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
      run.executionStatus == ExecutionStatus.DENIED &&
      isApprovalRequiredError(run.errorCode)

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun AgentRunSnapshot.replayExecutionContext(): RuntimeReplayExecutionContext =
    RuntimeReplayExecutionContext(
      executionId = executionId,
      executionOrdinal = executionOrdinal.takeIf { ordinal -> ordinal > 0 },
      executionKind = executionKind,
    )

  private companion object {
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
  }
}

internal class ServiceOwnedChatRunControlAccess(
  private val chatSessionStore: ChatSessionLocalStore,
  private val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  private val pendingApprovalState: ChatPendingApprovalState = ChatPendingApprovalState(),
  private val runtimeEventState: ChatRuntimeEventState = ChatRuntimeEventState(),
  private val runCancellationReplayRecorder:
    (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit =
    { _, _, _, _, _ -> },
  private val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
  private val isChineseLocale: () -> Boolean = { false },
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val coordinator = ChatRunControlCoordinator(
    runtimeHostAccess = runtimeHostAccess,
    findRunSnapshotForIdentifier = ::findRunSnapshotForIdentifier,
    pendingApprovalForRun = ::pendingApprovalForRun,
    clearPendingApproval = { sessionId, taskId ->
      pendingApprovalState.remove(sessionId, taskId)
    },
    clearApproval = { sessionId, taskId ->
      runtimeHostAccess.clearApproval(sessionId = sessionId, taskId = taskId)
    },
    clearPromptCheckpoint = { sessionId, taskId ->
      runtimeHostAccess.promptCheckpointStore(sessionId).remove(taskId)
    },
    recordRuntimeEvent = ::recordRuntimeEvent,
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    subAgentReplayRecorder = subAgentReplayRecorder,
    subAgentTerminalEventFactory = { approval, summary, emittedAtEpochMs ->
      approval.subAgentTerminalEvent(
        summary = summary,
        emittedAtEpochMs = emittedAtEpochMs,
      )
    },
    cancellationEventFactory = ::cancellationRuntimeEvent,
    delegatedChildCancelledWhileWaitingSummaryProvider = ::delegatedChildCancelledWhileWaitingSummary,
    nowEpochMsProvider = nowEpochMsProvider,
  )

  fun interruptChatRun(taskIdOrRunId: String) {
    synchronized(lock) {
      coordinator.interruptChatRun(taskIdOrRunId)
    }
  }

  fun retryChatRun(taskIdOrRunId: String) {
    synchronized(lock) {
      coordinator.retryChatRun(taskIdOrRunId)
    }
  }

  private fun findRunSnapshotForIdentifier(runIdOrTaskId: String): AgentRunSnapshot? {
    return findChatRunSnapshotForIdentifier(
      chatSessionStore = chatSessionStore,
      runtimeHostAccess = runtimeHostAccess,
      runIdOrTaskId = runIdOrTaskId,
    )
  }

  private fun pendingApprovalForRun(run: AgentRunSnapshot): PendingApprovalSnapshot? {
    val checkpointKind = runtimeHostAccess.promptCheckpointStore(run.sessionId)
      .get(run.taskId)
      ?.checkpointKind
    if (
      checkpointKind == PromptCheckpointKind.APPROVED_PENDING_RESUME ||
      checkpointKind == PromptCheckpointKind.REJECTED_PENDING_RESUME
    ) {
      return null
    }
    pendingApprovalState.approvalsForSession(run.sessionId).values.firstOrNull { approval ->
      approval.taskId == run.taskId || approval.runId == run.runId
    }?.let { approval ->
      return approval
    }
    if (!isApprovalWaitingRun(run)) {
      return null
    }
    return PendingApprovalSnapshot(
      runId = run.runId,
      taskId = run.taskId,
      pendingMessageId = run.pendingMessageId,
      executionId = run.executionId,
      executionOrdinal = run.executionOrdinal.takeIf { ordinal -> ordinal > 0 },
      executionKind = run.executionKind,
      toolName = approvalToolName(run.resultMetadata),
      resumeToolName = null,
      promptCheckpointBoundary = null,
      promptResumeState = null,
      subAgentApprovalResume = null,
      requestSummary = null,
      primaryDetail = null,
      pathDetails = emptyList(),
      workingDirectory = null,
      reason = run.resultMetadata["toolReason"],
      message = run.errorMessage,
      isHighRisk = isHighRiskApproval(run.errorCode),
      supportsSessionApproval = false,
      approveForSessionLabel = null,
      subAgentLifecycle = pendingApprovalSubAgentLifecycle(run.resultMetadata),
      title = "Approval required",
      body = run.errorMessage.orEmpty(),
    )
  }

  private fun isApprovalWaitingRun(run: AgentRunSnapshot): Boolean =
    run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
      run.executionStatus == ExecutionStatus.DENIED &&
      isApprovalRequiredError(run.errorCode)

  private fun recordRuntimeEvent(sessionId: String, event: OpenCrayAgentRunEvent) {
    runtimeEventState.append(
      sessionId = sessionId,
      event = event,
      maxHistory = MAX_RUNTIME_EVENT_HISTORY,
    )
    runtimeHostAccess.runEventJournalStore(sessionId).append(event)
    maybeClearPromptCheckpointAfterRuntimeEvent(sessionId = sessionId, event = event)
  }

  private fun maybeClearPromptCheckpointAfterRuntimeEvent(
    sessionId: String,
    event: OpenCrayAgentRunEvent,
  ) {
    val checkpoint = runtimeHostAccess.promptCheckpointStore(sessionId).get(event.taskId) ?: return
    if (
      checkpoint.checkpointKind != PromptCheckpointKind.APPROVED_PENDING_RESUME &&
      checkpoint.checkpointKind != PromptCheckpointKind.REJECTED_PENDING_RESUME
    ) {
      return
    }
    when (event) {
      is OpenCrayApprovalEvent -> return
      is OpenCraySubAgentEvent -> if (event.phase == OpenCraySubAgentPhase.RESUMED) {
        return
      }

      else -> Unit
    }
    runtimeHostAccess.promptCheckpointStore(sessionId).remove(event.taskId)
  }

  private fun PendingApprovalSnapshot.subAgentTerminalEvent(
    summary: String,
    emittedAtEpochMs: Long,
  ): OpenCraySubAgentEvent? {
    val lifecycle = subAgentLifecycle ?: return null
    return OpenCraySubAgentEvent(
      runId = runId,
      taskId = taskId,
      phase = OpenCraySubAgentPhase.CANCELLED,
      childRunId = lifecycle.childRunId,
      childTaskId = lifecycle.childTaskId,
      label = lifecycle.label,
      subagentType = lifecycle.subagentType,
      contextMode = lifecycle.contextMode,
      depth = lifecycle.depth,
      summary = summary,
      executionState = SubAgentExecutionState.CANCELLED,
      continuationKind = SubAgentContinuationKind.NONE,
      resumable = false,
      requiresUserAction = false,
      isHighRisk = isHighRisk,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }

  private fun cancellationRuntimeEvent(
    run: AgentRunSnapshot,
    approval: PendingApprovalSnapshot?,
    emittedAtEpochMs: Long,
  ): OpenCrayCancellationEvent = OpenCrayCancellationEvent(
    runId = run.runId,
    taskId = run.taskId,
    executionId = run.executionId,
    executionOrdinal = run.executionOrdinal.takeIf { ordinal -> ordinal > 0 },
    executionKind = run.executionKind,
    toolName = approval?.toolName,
    outcome = "user_interrupted",
    text = cancellationTimelineText(toolName = approval?.toolName),
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun pendingApprovalSubAgentLifecycle(
    metadata: Map<String, String>,
  ): PendingApprovalSubAgentLifecycle? {
    val childRunId = metadata["childRunId"]?.trim()?.takeIf(String::isNotBlank) ?: return null
    val childTaskId = metadata["childTaskId"]?.trim()?.takeIf(String::isNotBlank) ?: return null
    val subagentType = metadata["subagentType"]?.trim()?.takeIf(String::isNotBlank)
      ?: return null
    return PendingApprovalSubAgentLifecycle(
      childRunId = childRunId,
      childTaskId = childTaskId,
      label = metadata["delegationDescription"]?.trim()?.takeIf(String::isNotBlank) ?: "Task",
      subagentType = subagentType,
      contextMode = metadata["subagentContextMode"]?.trim()?.takeIf(String::isNotBlank)
        ?: "delegated",
      depth = metadata["subagentDepth"]?.trim()?.toIntOrNull() ?: 1,
    )
  }

  private fun approvalToolName(metadata: Map<String, String>): String? =
    metadata["normalizedToolName"]
      ?.takeIf(String::isNotBlank)
      ?: metadata["canonicalToolName"]
        ?.takeIf(String::isNotBlank)
      ?: metadata["toolName"]
        ?.takeIf(String::isNotBlank)

  private fun isHighRiskApproval(errorCode: String?): Boolean =
    errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == "APPROVAL_REQUIRED" || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun delegatedChildCancelledWhileWaitingSummary(): String = if (isChineseLocale()) {
    "子任务在等待审批时已停止。"
  } else {
    "Delegated child was cancelled while waiting for approval."
  }

  private fun cancellationTimelineText(toolName: String?): String {
    val resolvedToolName = toolName?.trim()?.takeIf(String::isNotBlank)
    return if (isChineseLocale()) {
      if (resolvedToolName == null) {
        "本次运行已中断，等待你的下一步指示。"
      } else {
        "已中断待审批的 $resolvedToolName 请求，等待你的下一步指示。"
      }
    } else if (resolvedToolName == null) {
      "Run interrupted. The agent is waiting for your next instruction."
    } else {
      "Interrupted the pending $resolvedToolName request. The agent is waiting for your next instruction."
    }
  }

  private companion object {
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private const val MAX_RUNTIME_EVENT_HISTORY: Int = 24
  }
}
