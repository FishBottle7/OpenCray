package com.opencray.app

import android.content.Context
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentLiveContextSnapshot
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import java.util.Locale
import org.opencray.app.R

internal enum class RuntimeApprovalDecisionOutcome {
  APPLIED,
  STALE,
  NOT_FOUND,
}

internal data class RuntimeApprovalDecisionResult(
  val outcome: RuntimeApprovalDecisionOutcome,
  val sessionId: String? = null,
  val taskId: String? = null,
)

internal data class RuntimeApprovalDecisionRequest(
  val sessionId: String?,
  val taskId: String?,
  val runId: String?,
  val executionId: String? = null,
  val executionOrdinal: Int? = null,
)

internal fun runtimeServiceApprovalDecisionAccess(
  dependencies: RuntimeServiceApprovalDecisionDependencies,
  nowEpochMsProvider: () -> Long = System::currentTimeMillis,
): RuntimeServiceApprovalDecisionAccess = RuntimeServiceApprovalDecisionAccess(
  dependencies = dependencies,
  nowEpochMsProvider = nowEpochMsProvider,
)

internal data class RuntimeServiceApprovalDecisionDependencies(
  val localizedContext: Context,
  val chatSessionStore: ChatSessionLocalStore,
  val runtimeHostAccess: RuntimeApprovalDecisionHostAccess,
  val runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
)

internal class RuntimeServiceApprovalDecisionAccess(
  private val dependencies: RuntimeServiceApprovalDecisionDependencies,
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) {
  fun approve(taskIdOrRunId: String) {
    approvalDecisionCoordinator(::resolvePendingApprovalById).approve(taskIdOrRunId)
  }

  fun approveForSession(taskIdOrRunId: String) {
    approvalDecisionCoordinator(::resolvePendingApprovalById).approveForSession(taskIdOrRunId)
  }

  fun reject(taskIdOrRunId: String) {
    approvalDecisionCoordinator(::resolvePendingApprovalById).reject(taskIdOrRunId)
  }

  fun approve(request: RuntimeApprovalDecisionRequest): RuntimeApprovalDecisionResult =
    decide(approve = true, request = request)

  fun reject(request: RuntimeApprovalDecisionRequest): RuntimeApprovalDecisionResult =
    decide(approve = false, request = request)

  private fun decide(
    approve: Boolean,
    request: RuntimeApprovalDecisionRequest,
  ): RuntimeApprovalDecisionResult {
    val hostAccess = dependencies.runtimeHostAccess
    val sessionId = request.sessionId?.trim()?.takeIf(String::isNotBlank)
    val taskId = request.taskId?.trim()?.takeIf(String::isNotBlank)
    val runId = request.runId?.trim()?.takeIf(String::isNotBlank)
    if (sessionId == null || taskId == null) {
      return RuntimeApprovalDecisionResult(RuntimeApprovalDecisionOutcome.STALE)
    }
    val lookup = findApprovalRequiredTaskProjectionInSession(
      sessionId = sessionId,
      hostAccess = hostAccess,
      taskId = taskId,
      runId = runId,
    )
    val projection = lookup.exact ?: return RuntimeApprovalDecisionResult(
      outcome = if (lookup.sameTaskPending) {
        RuntimeApprovalDecisionOutcome.STALE
      } else {
        RuntimeApprovalDecisionOutcome.NOT_FOUND
      },
      sessionId = sessionId,
      taskId = taskId,
    )
    if (
      hostAccess.isApprovalApproved(sessionId, projection.taskId) ||
      hostAccess.isApprovalRejected(sessionId, projection.taskId)
    ) {
      return RuntimeApprovalDecisionResult(
        outcome = RuntimeApprovalDecisionOutcome.STALE,
        sessionId = sessionId,
        taskId = taskId,
      )
    }
    if (!request.executionBinding().matches(approvalProjectionExecutionBinding(projection))) {
      return RuntimeApprovalDecisionResult(
        outcome = RuntimeApprovalDecisionOutcome.STALE,
        sessionId = sessionId,
        taskId = taskId,
      )
    }
    if (!hostAccess.tryBeginApprovalDecision(sessionId, projection.taskId)) {
      return RuntimeApprovalDecisionResult(
        outcome = RuntimeApprovalDecisionOutcome.STALE,
        sessionId = sessionId,
        taskId = taskId,
      )
    }
    return try {
      val applied = executeDecision(
        approve = approve,
        projection = projection,
      )
      RuntimeApprovalDecisionResult(
        outcome = if (applied) {
          RuntimeApprovalDecisionOutcome.APPLIED
        } else {
          RuntimeApprovalDecisionOutcome.STALE
        },
        sessionId = sessionId,
        taskId = projection.taskId,
      )
    } catch (failure: Throwable) {
      hostAccess.releaseUnresolvedApprovalDecision(sessionId, projection.taskId)
      throw failure
    }
  }

  private fun executeDecision(
    approve: Boolean,
    projection: ApprovalRequiredTaskProjection,
  ): Boolean {
    var cachedResolution: RuntimeServicePendingApprovalResolution? = null
    val coordinator = approvalDecisionCoordinator(resolver@ { _ ->
      cachedResolution ?: resolvePendingApproval(projection).also { resolution ->
        cachedResolution = resolution
      }
    })
    return if (approve) {
      coordinator.approve(projection.taskId)
    } else {
      coordinator.reject(projection.taskId)
    }
  }

  private fun RuntimeApprovalDecisionRequest.executionBinding(): RuntimeApprovalExecutionBinding =
    RuntimeApprovalExecutionBinding(
      executionId = executionId,
      executionOrdinal = executionOrdinal,
    )

  private fun resolvePendingApprovalById(
    taskIdOrRunId: String,
  ): RuntimeServicePendingApprovalResolution? {
    val hostAccess = dependencies.runtimeHostAccess
    val projection = findApprovalRequiredTaskProjection(
      sessionIds = knownChatSessionIds(dependencies.chatSessionStore),
      hostAccess = hostAccess,
      taskIdOrRunId = taskIdOrRunId,
      approvalRequiredErrorCode = SERVICE_ERROR_APPROVAL_REQUIRED,
      highRiskApprovalRequiredErrorCode = SERVICE_ERROR_HIGH_RISK_APPROVAL_REQUIRED,
    ) ?: return null
    return resolvePendingApproval(projection)
  }

  private fun resolvePendingApproval(
    projection: ApprovalRequiredTaskProjection,
  ): RuntimeServicePendingApprovalResolution? {
    val hostAccess = dependencies.runtimeHostAccess
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
      approvedRequestFingerprint = decisionRecord.approvedRequestFingerprint,
    )
  }

  private fun approvalDecisionCoordinator(
    resolveApproval: (String) -> RuntimeServicePendingApprovalResolution?,
  ): ChatApprovalDecisionCoordinator<RuntimeServicePendingApprovalResolution> {
    val nowEpochMs = nowEpochMsProvider()
    return ChatApprovalDecisionCoordinator(
      resolveApproval = resolveApproval,
      approvalSubject = { resolution ->
        ApprovalDecisionSubject(
          sessionId = resolution.sessionId,
          supportsSessionApproval = resolution.supportsSessionApproval,
          decisionRecord = resolution.toApprovalDecisionRecord(),
        )
      },
      shouldDeferDecisionUntilManualResume = ::shouldDeferApprovalDecisionUntilManualResume,
      submitDetachedApprovedRecovery = { resolution, emittedAtEpochMs ->
        submitSubAgentRecoveryTaskForApprovedResolution(
          resolution = resolution,
          nowEpochMs = emittedAtEpochMs,
        )
      },
      markApprovalApproved = { subject ->
        dependencies.runtimeHostAccess.markApprovalApproved(
          sessionId = subject.sessionId,
          taskId = subject.taskId,
          toolName = subject.decisionRecord.resumeToolName ?: subject.decisionRecord.toolName,
          promptResumeState = subject.decisionRecord.promptResumeState,
          subAgentApprovalResume = subject.decisionRecord.subAgentApprovalResume,
          approvedRequestFingerprint = subject.decisionRecord.approvedRequestFingerprint,
        )
      },
      markApprovalRejected = { subject ->
        dependencies.runtimeHostAccess.markApprovalRejected(
          sessionId = subject.sessionId,
          taskId = subject.taskId,
          toolName = subject.decisionRecord.resumeToolName ?: subject.decisionRecord.toolName,
          promptResumeState = subject.decisionRecord.promptResumeState,
          subAgentApprovalResume = subject.decisionRecord.subAgentApprovalResume,
        )
      },
      clearApproval = { sessionId, taskId ->
        dependencies.runtimeHostAccess.clearApproval(
          sessionId = sessionId,
          taskId = taskId,
        )
      },
      upsertCheckpoint = { sessionId, checkpoint ->
        dependencies.runtimeHostAccess.promptCheckpointStore(sessionId).upsert(checkpoint)
      },
      removeCheckpoint = { sessionId, taskId ->
        dependencies.runtimeHostAccess.promptCheckpointStore(sessionId).remove(taskId)
      },
      requestResumeTask = { sessionId, taskId ->
        dependencies.runtimeHostAccess.session(sessionId).requestResumeTask(taskId)
      },
      requestCancel = { sessionId, taskId ->
        dependencies.runtimeHostAccess.session(sessionId).requestCancel(taskId)
      },
      recordApprovalApprovedReplay = { subject ->
        dependencies.runtimeReplayAccess.approvalApprovedRecorder(
          subject.sessionId,
          subject.taskId,
          subject.runId,
          subject.decisionRecord.toolName,
          subject.decisionRecord.isHighRisk,
          subject.decisionRecord.replayExecutionContext(),
        )
      },
      recordApprovalRejectedReplay = { subject ->
        dependencies.runtimeReplayAccess.approvalRejectionRecorder(
          subject.sessionId,
          subject.taskId,
          subject.runId,
          subject.decisionRecord.toolName,
          subject.decisionRecord.isHighRisk,
          subject.decisionRecord.replayExecutionContext(),
        )
      },
      recordApprovalResultEvent = { sessionId, event ->
        dependencies.runtimeHostAccess.runEventJournalStore(sessionId).append(event)
      },
      recordSubAgentEvent = { sessionId, event ->
        dependencies.runtimeReplayAccess.subAgentReplayRecorder(sessionId, event)
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

  private fun shouldDeferApprovalDecisionUntilManualResume(
    resolution: RuntimeServicePendingApprovalResolution,
  ): Boolean {
    val runtimeEvents = dependencies.runtimeHostAccess
      .runEventJournalStore(resolution.sessionId)
      .listForRun(resolution.runId)
      .mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }
    val latestEvent = runtimeEvents.lastOrNull() ?: return false
    return latestEvent is com.opencray.runtime.OpenCrayCancellationEvent &&
      latestEvent.outcome == "user_interrupted"
  }

  private fun submitSubAgentRecoveryTaskForApprovedResolution(
    resolution: RuntimeServicePendingApprovalResolution,
    nowEpochMs: Long,
  ): Boolean {
    if (!resolution.usesExplicitSubAgentHandleControlPlane()) {
      return false
    }
    val session = dependencies.runtimeHostAccess.session(resolution.sessionId)
    val matchingHandles = session.listSubAgentHandles().filter { candidate ->
      subAgentApprovalResumeMatchesHandle(
        parentRunId = resolution.subAgentParentRunId,
        resume = resolution.subAgentApprovalResume,
        handle = candidate,
      )
    }
    val handle = if (resolution.subAgentParentRunId == null) {
      matchingHandles.singleOrNull()
    } else {
      matchingHandles.firstOrNull()
    } ?: return false
    val taskId = syntheticSubAgentRecoveryTaskId(
      sessionId = session.sessionId,
      agentId = handle.agentId,
      parentRunId = handle.parentRunId,
    )
    val runId = syntheticSubAgentRecoveryRunId(taskId)
    dependencies.runtimeHostAccess.markApprovalApproved(
      sessionId = resolution.sessionId,
      taskId = taskId,
      toolName = resolution.resumeToolName ?: resolution.toolName,
      promptResumeState = resolution.promptResumeState,
      subAgentApprovalResume = resolution.subAgentApprovalResume,
      approvedRequestFingerprint = resolution.approvedRequestFingerprint,
    )
    dependencies.runtimeHostAccess.promptCheckpointStore(resolution.sessionId).upsert(
      resolution.toApprovalDecisionRecord().decisionCheckpoint(
        sessionId = resolution.sessionId,
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        nowEpochMs = nowEpochMs,
        runIdOverride = runId,
        taskIdOverride = taskId,
        pendingMessageIdOverride = null,
      ),
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
  val agentId: String? = null,
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
  val liveContext: SubAgentLiveContextSnapshot? = null,
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
  val approvedRequestFingerprint: String?,
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
  approvedRequestFingerprint = approvedRequestFingerprint,
)

private fun ApprovalDecisionSubAgentLifecycle.toRuntimeServicePendingApprovalSubAgentLifecycle():
  RuntimeServicePendingApprovalSubAgentLifecycle = RuntimeServicePendingApprovalSubAgentLifecycle(
  agentId = agentId,
  childRunId = childRunId,
  childTaskId = childTaskId,
  label = label,
  subagentType = subagentType,
  contextMode = contextMode,
  depth = depth,
  liveContext = liveContext,
)

private fun RuntimeServicePendingApprovalSubAgentLifecycle.toApprovalDecisionSubAgentLifecycle():
  ApprovalDecisionSubAgentLifecycle = ApprovalDecisionSubAgentLifecycle(
  agentId = agentId,
  childRunId = childRunId,
  childTaskId = childTaskId,
  label = label,
  subagentType = subagentType,
  contextMode = contextMode,
  depth = depth,
  liveContext = liveContext,
)

private fun subAgentApprovalResumeMatchesHandle(
  parentRunId: String?,
  resume: SubAgentApprovalResume?,
  handle: SubAgentHandleState,
): Boolean {
  val candidate = resume ?: return false
  if (parentRunId != null && handle.parentRunId != parentRunId) {
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
