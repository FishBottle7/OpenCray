package com.opencray.app

import android.content.Context
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import java.util.Locale
import org.opencray.app.R

internal fun runtimeServiceApprovalDecisionAccess(
  dependencies: OpenCrayRuntimeContextDependencies,
  runtimeAccess: OpenCrayRuntimeOwnerAccess,
  nowEpochMsProvider: () -> Long = System::currentTimeMillis,
): RuntimeServiceApprovalDecisionAccess = RuntimeServiceApprovalDecisionAccess(
  dependencies = dependencies,
  runtimeAccess = runtimeAccess,
  nowEpochMsProvider = nowEpochMsProvider,
)

internal class RuntimeServiceApprovalDecisionAccess(
  private val dependencies: OpenCrayRuntimeContextDependencies,
  private val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) {
  fun approve(taskIdOrRunId: String) {
    approvalDecisionCoordinator().approve(taskIdOrRunId)
  }

  fun approveForSession(taskIdOrRunId: String) {
    approvalDecisionCoordinator().approveForSession(taskIdOrRunId)
  }

  fun reject(taskIdOrRunId: String) {
    approvalDecisionCoordinator().reject(taskIdOrRunId)
  }

  private fun approvalDecisionCoordinator():
    ChatApprovalDecisionCoordinator<RuntimeServicePendingApprovalResolution> {
    val nowEpochMs = nowEpochMsProvider()
    return ChatApprovalDecisionCoordinator(
      resolveApproval = ::resolvePendingApproval,
      approvalSubject = { resolution ->
        ApprovalDecisionSubject(
          sessionId = resolution.sessionId,
          supportsSessionApproval = resolution.supportsSessionApproval,
          decisionRecord = resolution.toApprovalDecisionRecord(),
        )
      },
      shouldDeferDecisionUntilManualResume = ::shouldDeferApprovalDecisionUntilManualResume,
      submitDetachedApprovedRecovery = { resolution, emittedAtEpochMs ->
        submitDetachedSubAgentRecoveryTaskForApprovedResolution(
          resolution = resolution,
          nowEpochMs = emittedAtEpochMs,
        )
      },
      markApprovalApproved = { subject ->
        runtimeAccess.hostAccess.markApprovalApproved(
          sessionId = subject.sessionId,
          taskId = subject.taskId,
          toolName = subject.decisionRecord.resumeToolName ?: subject.decisionRecord.toolName,
          promptResumeState = subject.decisionRecord.promptResumeState,
          subAgentApprovalResume = subject.decisionRecord.subAgentApprovalResume,
        )
      },
      markApprovalRejected = { subject ->
        runtimeAccess.hostAccess.markApprovalRejected(
          sessionId = subject.sessionId,
          taskId = subject.taskId,
          toolName = subject.decisionRecord.resumeToolName ?: subject.decisionRecord.toolName,
          promptResumeState = subject.decisionRecord.promptResumeState,
          subAgentApprovalResume = subject.decisionRecord.subAgentApprovalResume,
        )
      },
      clearApproval = { sessionId, taskId ->
        runtimeAccess.hostAccess.clearApproval(
          sessionId = sessionId,
          taskId = taskId,
        )
      },
      upsertCheckpoint = { sessionId, checkpoint ->
        runtimeAccess.hostAccess.promptCheckpointStore(sessionId).upsert(checkpoint)
      },
      removeCheckpoint = { sessionId, taskId ->
        runtimeAccess.hostAccess.promptCheckpointStore(sessionId).remove(taskId)
      },
      requestResumeTask = { sessionId, taskId ->
        runtimeAccess.hostAccess.session(sessionId).requestResumeTask(taskId)
      },
      requestCancel = { sessionId, taskId ->
        runtimeAccess.hostAccess.session(sessionId).requestCancel(taskId)
      },
      recordApprovalApprovedReplay = { subject ->
        runtimeAccess.replayAccess.approvalApprovedRecorder(
          subject.sessionId,
          subject.taskId,
          subject.runId,
          subject.decisionRecord.toolName,
          subject.decisionRecord.isHighRisk,
          subject.decisionRecord.replayExecutionContext(),
        )
      },
      recordApprovalRejectedReplay = { subject ->
        runtimeAccess.replayAccess.approvalRejectionRecorder(
          subject.sessionId,
          subject.taskId,
          subject.runId,
          subject.decisionRecord.toolName,
          subject.decisionRecord.isHighRisk,
          subject.decisionRecord.replayExecutionContext(),
        )
      },
      recordApprovalResultEvent = { sessionId, event ->
        runtimeAccess.hostAccess.runEventJournalStore(sessionId).append(event)
      },
      recordSubAgentEvent = { sessionId, event ->
        runtimeAccess.replayAccess.subAgentReplayRecorder(sessionId, event)
      },
      setSessionApprovalGranted = { sessionId, approved ->
        dependencies.chatSessionStore.setNativeWebSearchSessionApproved(
          sessionId = sessionId,
          approved = approved,
        )
      },
      replacePendingMessageWithThinking = { sessionId, pendingMessageId, text ->
        dependencies.chatSessionStore.replaceMessage(
          sessionId = sessionId,
          messageId = pendingMessageId,
          role = ChatTranscriptRole.ASSISTANT,
          text = text,
        )
      },
      appendToolMessage = { sessionId, text ->
        appendApprovalToolMessage(
          chatSessionStore = dependencies.chatSessionStore,
          sessionId = sessionId,
          text = text,
        )
      },
      stringsProvider = {
        runtimeApprovalCommandStrings(
          context = dependencies.localizedContext,
        ).let { commandStrings ->
          ApprovalDecisionStrings(
            agentThinking = commandStrings.agentThinking,
            approvalApproved = commandStrings.approvalApproved,
            approvalApprovedForSession = commandStrings.approvalApprovedForSession,
            approvalRejected = commandStrings.approvalRejected,
            deferredApprovalApproved = approvalRecordedText(
              context = dependencies.localizedContext,
            ),
            deferredApprovalApprovedForSession = approvalRecordedForSessionText(
              context = dependencies.localizedContext,
            ),
            deferredApprovalRejected = approvalRejectedRecordedText(
              context = dependencies.localizedContext,
            ),
            delegatedChildApprovalApprovedSummary = delegatedChildApprovalApprovedSummary(
              context = dependencies.localizedContext,
            ),
            delegatedChildApprovalApprovedText = delegatedChildApprovalApprovedText(
              context = dependencies.localizedContext,
            ),
            delegatedChildApprovalRejectedSummary = delegatedChildApprovalRejectedStopSummary(
              context = dependencies.localizedContext,
            ),
          )
        }
      },
      nowEpochMsProvider = { nowEpochMs },
    )
  }

  private fun resolvePendingApproval(
    taskIdOrRunId: String,
  ): RuntimeServicePendingApprovalResolution? {
    val hostAccess = runtimeAccess.hostAccess
    val projection = findApprovalRequiredTaskProjection(
      sessionIds = knownChatSessionIds(dependencies.chatSessionStore),
      hostAccess = hostAccess,
      taskIdOrRunId = taskIdOrRunId,
      approvalRequiredErrorCode = SERVICE_ERROR_APPROVAL_REQUIRED,
      highRiskApprovalRequiredErrorCode = SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED,
    ) ?: return null
    if (
      approvalDecisionState(
        approved = hostAccess.isApprovalApproved(projection.sessionId, projection.taskId),
        rejected = hostAccess.isApprovalRejected(projection.sessionId, projection.taskId),
        checkpoint = projection.checkpoint,
      ) != null
    ) {
      return null
    }
    val metadata = projection.metadata
    val decisionRecord = projection.toApprovalDecisionRecord(
      highRiskApprovalRequiredErrorCode = SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED,
    )
    return RuntimeServicePendingApprovalResolution(
      sessionId = projection.sessionId,
      runId = decisionRecord.runId,
      taskId = decisionRecord.taskId,
      pendingMessageId = decisionRecord.pendingMessageId,
      toolName = decisionRecord.toolName,
      resumeToolName = decisionRecord.resumeToolName,
      promptCheckpointBoundary = decisionRecord.promptCheckpointBoundary,
      promptResumeState = decisionRecord.promptResumeState,
      subAgentApprovalResume = decisionRecord.subAgentApprovalResume,
      isHighRisk = decisionRecord.isHighRisk,
      supportsSessionApproval = approvalMetadataSupportsSessionScope(metadata),
      subAgentParentRunId = metadata[com.opencray.runtime.subagent.SubAgentMetadataKeys.PARENT_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank),
      subAgentLifecycle = decisionRecord.subAgentLifecycle
        ?.toRuntimeServicePendingApprovalSubAgentLifecycle(),
      subAgentControlTool = metadata[SubAgentMetadataKeys.CONTROL_TOOL]
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank),
      executionId = decisionRecord.executionId,
      executionOrdinal = decisionRecord.executionOrdinal,
      executionKind = decisionRecord.executionKind,
    )
  }

  private fun shouldDeferApprovalDecisionUntilManualResume(
    resolution: RuntimeServicePendingApprovalResolution,
  ): Boolean {
    val runtimeEvents = runtimeAccess.hostAccess
      .runEventJournalStore(resolution.sessionId)
      .listForRun(resolution.runId)
      .mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }
    val latestEvent = runtimeEvents.lastOrNull() ?: return false
    return latestEvent is com.opencray.runtime.OpenCrayCancellationEvent &&
      latestEvent.outcome == "user_interrupted"
  }

  private fun submitDetachedSubAgentRecoveryTaskForApprovedResolution(
    resolution: RuntimeServicePendingApprovalResolution,
    nowEpochMs: Long,
  ): Boolean {
    if (!resolution.usesExplicitSubAgentHandleControlPlane()) {
      return false
    }
    val parentRunId = resolution.subAgentParentRunId ?: return false
    val session = runtimeAccess.hostAccess.session(resolution.sessionId)
    val handle = session.listSubAgentHandles().firstOrNull { candidate ->
      subAgentApprovalResumeMatchesHandle(
        parentRunId = parentRunId,
        resume = resolution.subAgentApprovalResume,
        handle = candidate,
      )
    } ?: return false
    val taskId = detachedSubAgentRecoveryTaskId(
      sessionId = session.sessionId,
      agentId = handle.agentId,
      parentRunId = handle.parentRunId,
    )
    val runId = detachedSubAgentRecoveryRunId(taskId)
    runtimeAccess.hostAccess.markApprovalApproved(
      sessionId = resolution.sessionId,
      taskId = taskId,
      toolName = resolution.resumeToolName ?: resolution.toolName,
      promptResumeState = resolution.promptResumeState,
      subAgentApprovalResume = resolution.subAgentApprovalResume,
    )
    runtimeAccess.hostAccess.promptCheckpointStore(resolution.sessionId).upsert(
      resolution.toApprovalDecisionRecord().decisionCheckpoint(
        sessionId = resolution.sessionId,
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        nowEpochMs = nowEpochMs,
        runIdOverride = runId,
        taskIdOverride = taskId,
        pendingMessageIdOverride = null,
      ),
    )
    session.submitDetachedSubAgentRecoveryTask(
      agentId = handle.agentId,
      parentRunId = handle.parentRunId,
      taskId = taskId,
      createdAtEpochMs = nowEpochMs,
      submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )
    return true
  }
}

private data class RuntimeServiceApprovalCommandStrings(
  val agentThinking: String,
  val approvalApproved: String,
  val approvalApprovedForSession: String,
  val approvalRejected: String,
)

private data class RuntimeServicePendingApprovalSubAgentLifecycle(
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
)

private data class RuntimeServicePendingApprovalResolution(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val pendingMessageId: String?,
  val toolName: String?,
  val resumeToolName: String?,
  val promptCheckpointBoundary: com.opencray.runtime.OpenCrayPromptCheckpointBoundary?,
  val promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
  val subAgentApprovalResume: SubAgentApprovalResume?,
  val isHighRisk: Boolean,
  val supportsSessionApproval: Boolean,
  val subAgentParentRunId: String?,
  val subAgentLifecycle: RuntimeServicePendingApprovalSubAgentLifecycle?,
  val subAgentControlTool: String?,
  val executionId: String?,
  val executionOrdinal: Int?,
  val executionKind: String?,
) {
  fun usesExplicitSubAgentHandleControlPlane(): Boolean =
    subAgentApprovalResume != null &&
      subAgentControlTool in RUNTIME_SERVICE_EXPLICIT_SUBAGENT_HANDLE_CONTROL_TOOLS
}

private fun runtimeApprovalCommandStrings(
  context: Context,
): RuntimeServiceApprovalCommandStrings = RuntimeServiceApprovalCommandStrings(
  agentThinking = runCatching {
    context.getString(R.string.chat_agent_thinking)
  }.getOrDefault("OpenCray is thinking..."),
  approvalApproved = runCatching {
    context.getString(R.string.chat_approval_approved)
  }.getOrDefault("Approval granted. The agent is resuming."),
  approvalApprovedForSession = runCatching {
    context.getString(R.string.chat_approval_approved_for_session)
  }.getOrDefault("Approval granted for this session. The agent is resuming."),
  approvalRejected = runCatching {
    context.getString(R.string.chat_approval_rejected)
  }.getOrDefault("Approval rejected. The requested action was not run."),
)

private fun approvalRecordedText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "审批已通过。此决定已记录；手动继续运行后才会生效。"
} else {
  "Approval granted. The decision is recorded and will apply when you manually resume the run."
}

private fun approvalRecordedForSessionText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "本会话审批已通过。此决定已记录；手动继续运行后才会生效。"
} else {
  "Session approval granted. The decision is recorded and will apply when you manually resume the run."
}

private fun approvalRejectedRecordedText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "审批已拒绝。此决定已记录；手动继续运行后才会生效。"
} else {
  "Approval rejected. The decision is recorded and will apply when you manually resume the run."
}

private fun delegatedChildApprovalApprovedSummary(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "子任务审批已通过，将继续执行。"
} else {
  "Delegated child approval granted. The child will continue."
}

private fun delegatedChildApprovalApprovedText(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "审批已通过，子任务正在后台继续执行。"
} else {
  "Approval granted. The delegated child is resuming in the background."
}

private fun delegatedChildApprovalRejectedStopSummary(
  context: Context,
): String = if (isChineseHostLocale(context)) {
  "子任务审批被拒绝，子任务已停止。"
} else {
  "Delegated child approval rejected. The child run was stopped."
}

private fun isChineseHostLocale(context: Context): Boolean =
  runCatching {
    context.resources.configuration.locales.toLanguageTags()
  }.getOrElse {
    Locale.getDefault().toLanguageTag()
  }.trim().lowercase(Locale.US).startsWith("zh")

private fun RuntimeServicePendingApprovalResolution.toApprovalDecisionRecord():
  ApprovalDecisionRecord = ApprovalDecisionRecord(
  runId = runId,
  taskId = taskId,
  pendingMessageId = pendingMessageId,
  executionId = executionId,
  executionOrdinal = executionOrdinal,
  executionKind = executionKind,
  toolName = toolName,
  resumeToolName = resumeToolName,
  promptCheckpointBoundary = promptCheckpointBoundary,
  promptResumeState = promptResumeState,
  subAgentApprovalResume = subAgentApprovalResume,
  isHighRisk = isHighRisk,
  subAgentLifecycle = subAgentLifecycle?.toApprovalDecisionSubAgentLifecycle(),
)

private fun ApprovalDecisionSubAgentLifecycle.toRuntimeServicePendingApprovalSubAgentLifecycle():
  RuntimeServicePendingApprovalSubAgentLifecycle = RuntimeServicePendingApprovalSubAgentLifecycle(
  childRunId = childRunId,
  childTaskId = childTaskId,
  label = label,
  subagentType = subagentType,
  contextMode = contextMode,
  depth = depth,
)

private fun RuntimeServicePendingApprovalSubAgentLifecycle.toApprovalDecisionSubAgentLifecycle():
  ApprovalDecisionSubAgentLifecycle = ApprovalDecisionSubAgentLifecycle(
  childRunId = childRunId,
  childTaskId = childTaskId,
  label = label,
  subagentType = subagentType,
  contextMode = contextMode,
  depth = depth,
)

private fun subAgentApprovalResumeMatchesHandle(
  parentRunId: String,
  resume: SubAgentApprovalResume?,
  handle: SubAgentHandleState,
): Boolean {
  val candidate = resume ?: return false
  if (handle.parentRunId != parentRunId) {
    return false
  }
  if (
    !candidate.childRunId.isNullOrBlank() &&
    candidate.childRunId != handle.childRunId
  ) {
    return false
  }
  if (
    !candidate.childTaskId.isNullOrBlank() &&
    candidate.childTaskId != handle.childTaskId
  ) {
    return false
  }
  return when {
    !candidate.agentId.isNullOrBlank() -> candidate.agentId == handle.agentId
    !candidate.childTaskId.isNullOrBlank() -> true
    !candidate.childRunId.isNullOrBlank() -> true
    else -> false
  }
}

private const val SERVICE_ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
private const val SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED: String =
  "HIGH_RISK_APPROVAL_REQUIRED"
private val RUNTIME_SERVICE_EXPLICIT_SUBAGENT_HANDLE_CONTROL_TOOLS: Set<String> = setOf(
  "spawn_agent",
  "wait_agent",
)
