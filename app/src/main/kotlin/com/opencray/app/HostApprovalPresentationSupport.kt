package com.opencray.app

import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import kotlinx.serialization.json.Json

internal const val DRAWER_PREVIEW_MAX_CHARS: Int = 52

internal fun sanitizeApprovalBody(
  body: String?,
  isHighRisk: Boolean,
  strings: HostRuntimeStrings,
): String {
  val fallback = approvalFallbackBody(isHighRisk = isHighRisk, strings = strings)
  val resolved = body?.takeIf(String::isNotBlank) ?: return fallback
  return sanitizePotentialInternalAgentText(
    text = resolved,
    fallback = fallback,
  )
}

internal fun approvalFallbackBody(
  isHighRisk: Boolean,
  strings: HostRuntimeStrings,
): String = if (isHighRisk) {
  strings.chatHighRiskApprovalRequiredBody
} else {
  strings.chatSummaryApprovalRequired
}

internal fun sanitizeDrawerPreviewText(text: String, strings: HostRuntimeStrings): String {
  val restoredFallback = restoreKnownPreviewFallback(text = text.trim(), strings = strings)
  if (restoredFallback != null) {
    return restoredFallback
  }
  return sanitizePotentialInternalAgentText(
    text = text,
    fallback = strings.agentInternalPayloadHidden,
  )
}

internal fun restoreKnownPreviewFallback(text: String, strings: HostRuntimeStrings): String? {
  val knownFallbacks = listOf(
    strings.agentInternalPayloadHidden,
    strings.chatSummaryAwaitingDirection,
    strings.chatSummaryApprovalRequired,
    strings.chatHighRiskApprovalRequiredBody,
  )
  return knownFallbacks.firstOrNull { fallback ->
    text == fallback.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd()
  }
}

internal fun isDrawerPlaceholderPreview(text: String, strings: HostRuntimeStrings): Boolean {
  val normalized = normalizeDrawerPreviewWhitespace(text)
  return normalized.isBlank() ||
    normalized == strings.agentThinking.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd() ||
    normalized == strings.chatSummaryApprovalRequired.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd() ||
    normalized == strings.chatHighRiskApprovalRequiredBody.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd()
}

internal fun snapshotDrawerPreviewText(text: String, strings: HostRuntimeStrings): String =
  sanitizeDrawerPreviewText(
    text = normalizeDrawerPreviewWhitespace(text).take(DRAWER_PREVIEW_MAX_CHARS).trimEnd(),
    strings = strings,
  )

internal fun normalizeDrawerPreviewWhitespace(text: String): String =
  text.replace(Regex("""\s+"""), " ").trim()

internal fun pendingApprovalSnapshot(
  runId: String,
  taskId: String,
  pendingMessageId: String?,
  isHighRisk: Boolean,
  metadata: Map<String, String>,
  errorBody: String,
  toolReason: String?,
  strings: HostRuntimeStrings,
  localeIsChinese: Boolean,
  replayJson: Json,
): PendingApprovalSnapshot {
  val toolName = approvalMetadataToolName(metadata)
  val resumeToolName = approvalMetadataResumeToolName(metadata) ?: toolName
  val promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata)
  val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
    metadata = metadata,
    json = replayJson,
  )
  val subAgentLifecycle = approvalMetadataSubAgentLifecycle(metadata)
    ?.toPendingApprovalSubAgentLifecycle()
  val subAgentApprovalResume = mergeApprovalResumeMetadata(
    decoded = SubAgentApprovalResumeMetadata.decodeFromMetadata(
      metadata = metadata,
      json = replayJson,
    ),
    metadata = metadata,
    lifecycle = subAgentLifecycle?.toApprovalDecisionSubAgentLifecycle(),
  )
  val requestSummary = approvalRequestSummary(metadata)
  val primaryDetail = approvalPrimaryDetailValue(metadata)
  val pathDetails = approvalPathDetailLines(
    metadata = metadata,
    localeIsChinese = localeIsChinese,
  )
  val workingDirectory = approvalWorkingDirectoryValue(metadata)
  val reason = approvalReasonValue(toolReason)
  val supportsSessionApproval = approvalMetadataSupportsSessionScope(metadata)
  val executionId = executionIdFromMetadata(metadata)
  val executionOrdinal = executionOrdinalFromMetadata(metadata)
  val executionKind = executionKindFromMetadata(metadata)
  return PendingApprovalSnapshot(
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
    requestSummary = requestSummary,
    primaryDetail = primaryDetail,
    pathDetails = pathDetails,
    workingDirectory = workingDirectory,
    reason = reason,
    message = errorBody,
    isHighRisk = isHighRisk,
    supportsSessionApproval = supportsSessionApproval,
    approveForSessionLabel = if (supportsSessionApproval) {
      strings.chatApprovalApproveForSessionLabel
    } else {
      null
    },
    subAgentLifecycle = subAgentLifecycle,
    title = if (isHighRisk) {
      strings.chatHighRiskApprovalRequiredTitle
    } else {
      strings.chatApprovalRequiredTitle
    },
    body = composeApprovalBody(
      body = errorBody,
      toolReason = toolReason,
      metadata = metadata,
      localeIsChinese = localeIsChinese,
    ),
  )
}

internal fun approvalIsHighRisk(
  errorCode: String?,
  metadata: Map<String, String>,
): Boolean = approvalMetadataIsHighRisk(
  errorCode = errorCode,
  highRiskErrorCode = OpenCrayHostRuntime.ERROR_HIGH_RISK_APPROVAL_REQUIRED,
  metadata = metadata,
)

internal fun toolReasonFromEvent(event: OpenCrayAgentRunEvent): String? = when (event) {
  is OpenCrayToolCallEvent -> event.call.reason
  else -> null
}

internal fun composeApprovalBody(
  body: String,
  toolReason: String?,
  metadata: Map<String, String>,
  localeIsChinese: Boolean,
): String = approvalSupportComposeBody(
  body = body,
  toolReason = toolReason,
  metadata = metadata,
  isChinese = localeIsChinese,
)

internal fun approvalRequiredRuntimeEvent(
  approval: PendingApprovalSnapshot,
  emittedAtEpochMs: Long,
): OpenCrayApprovalEvent = OpenCrayApprovalEvent(
  runId = approval.runId,
  taskId = approval.taskId,
  executionId = approval.executionId,
  executionOrdinal = approval.executionOrdinal,
  executionKind = approval.executionKind,
  phase = OpenCrayApprovalPhase.REQUIRED,
  toolName = approval.toolName,
  text = approvalTimelineText(approval),
  isHighRisk = approval.isHighRisk,
  emittedAtEpochMs = emittedAtEpochMs,
)

internal fun cancellationRuntimeEvent(
  run: AgentRunSnapshot,
  approval: PendingApprovalSnapshot?,
  emittedAtEpochMs: Long,
  localeIsChinese: Boolean,
): OpenCrayCancellationEvent = OpenCrayCancellationEvent(
  runId = run.runId,
  taskId = run.taskId,
  executionId = run.executionId,
  executionOrdinal = run.executionOrdinal,
  executionKind = run.executionKind,
  toolName = approval?.toolName,
  outcome = "user_interrupted",
  text = cancellationTimelineText(
    toolName = approval?.toolName,
    localeIsChinese = localeIsChinese,
  ),
  emittedAtEpochMs = emittedAtEpochMs,
)

internal fun approvalTimelineText(approval: PendingApprovalSnapshot): String {
  val title = approval.title.trim()
  val body = approval.body.trim()
  return when {
    title.isEmpty() -> body
    body.isEmpty() -> title
    else -> "$title\n\n$body"
  }
}

internal fun cancellationTimelineText(toolName: String?, localeIsChinese: Boolean): String {
  val resolvedToolName = toolName?.trim()?.takeIf { value -> value.isNotBlank() }
  return if (localeIsChinese) {
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

internal fun deferredApprovalRecordedText(localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "审批已通过。此决定已记录；手动继续运行后才会生效。"
  } else {
    "Approval granted. The decision is recorded and will apply when you manually resume the run."
  }

internal fun deferredApprovalRecordedForSessionText(localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "本会话审批已通过。此决定已记录；手动继续运行后才会生效。"
  } else {
    "Session approval granted. The decision is recorded and will apply when you manually resume the run."
  }

internal fun deferredApprovalRejectedText(localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "审批已拒绝。此决定已记录；手动继续运行后才会生效。"
  } else {
    "Approval rejected. The decision is recorded and will apply when you manually resume the run."
  }

internal fun approvalRequestSummary(metadata: Map<String, String>): String? =
  approvalSupportRequestSummary(metadata)

internal fun approvalPrimaryDetailValue(metadata: Map<String, String>): String? =
  approvalSupportPrimaryDetailValue(metadata)

internal fun approvalPathDetailLines(
  metadata: Map<String, String>,
  localeIsChinese: Boolean,
): List<String> = approvalSupportPathDetailLines(
  metadata = metadata,
  isChinese = localeIsChinese,
)

internal fun PendingApprovalSnapshot.toApprovalDecisionRecord(): ApprovalDecisionRecord =
  ApprovalDecisionRecord(
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

internal fun ApprovalDecisionSubAgentLifecycle.toPendingApprovalSubAgentLifecycle():
  PendingApprovalSubAgentLifecycle = PendingApprovalSubAgentLifecycle(
  agentId = agentId,
  childRunId = childRunId,
  childTaskId = childTaskId,
  label = label,
  subagentType = subagentType,
  contextMode = contextMode,
  depth = depth,
  liveContext = liveContext,
)

internal fun PendingApprovalSubAgentLifecycle.toApprovalDecisionSubAgentLifecycle():
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

internal fun delegatedChildApprovalApprovedSummary(localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "子任务审批已通过，将继续执行。"
  } else {
    "Delegated child approval granted. The child will continue."
  }

internal fun delegatedChildApprovalRejectedStopSummary(localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "子任务审批被拒绝，子任务已停止。"
  } else {
    "Delegated child approval rejected. The child run was stopped."
  }

internal fun delegatedChildCancelledWhileWaitingSummary(localeIsChinese: Boolean): String =
  if (localeIsChinese) {
    "子任务在等待审批时被取消。"
  } else {
    "Delegated child run was cancelled while waiting for approval."
  }

internal fun approvalWorkingDirectoryValue(metadata: Map<String, String>): String? =
  approvalSupportWorkingDirectoryValue(metadata)

internal fun approvalReasonValue(toolReason: String?): String? =
  approvalSupportReasonValue(toolReason)

internal fun sanitizePotentialInternalAgentText(text: String, fallback: String): String {
  val trimmed = text.trim()
  if (trimmed.isBlank()) {
    return text
  }
  return approvalSupportSanitizePotentialInternalAgentText(
    text = text,
    fallback = fallback,
  )
}

internal fun executionIdFromMetadata(metadata: Map<String, String>): String? =
  metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)

internal fun executionOrdinalFromMetadata(metadata: Map<String, String>): Int? =
  metadata[METADATA_EXECUTION_ORDINAL]?.trim()?.toIntOrNull()

internal fun executionKindFromMetadata(metadata: Map<String, String>): String? =
  metadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)
