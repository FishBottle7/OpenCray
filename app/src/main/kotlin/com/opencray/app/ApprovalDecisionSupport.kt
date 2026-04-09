package com.opencray.app

import com.opencray.persistence.PersistenceJson
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import java.util.UUID
import kotlinx.serialization.json.Json

internal data class ApprovalDecisionSubAgentLifecycle(
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
)

internal data class ApprovalDecisionRecord(
  val runId: String,
  val taskId: String,
  val pendingMessageId: String?,
  val executionId: String?,
  val executionOrdinal: Int?,
  val executionKind: String?,
  val toolName: String?,
  val resumeToolName: String?,
  val promptCheckpointBoundary: OpenCrayPromptCheckpointBoundary?,
  val promptResumeState: OpenCrayPromptResumeState?,
  val subAgentApprovalResume: SubAgentApprovalResume?,
  val isHighRisk: Boolean,
  val subAgentLifecycle: ApprovalDecisionSubAgentLifecycle? = null,
) {
  fun replayExecutionContext(): RuntimeReplayExecutionContext =
    RuntimeReplayExecutionContext(
      executionId = executionId,
      executionOrdinal = executionOrdinal,
      executionKind = executionKind,
    )

  fun decisionCheckpoint(
    sessionId: String,
    checkpointKind: PromptCheckpointKind,
    nowEpochMs: Long,
    runIdOverride: String = runId,
    taskIdOverride: String = taskId,
    pendingMessageIdOverride: String? = pendingMessageId,
  ): PersistedPromptCheckpoint = PersistedPromptCheckpoint(
    sessionId = sessionId,
    runId = runIdOverride,
    taskId = taskIdOverride,
    checkpointId = "checkpoint-$nowEpochMs-${UUID.randomUUID().toString().take(8)}",
    checkpointKind = checkpointKind,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs,
    toolName = resumeToolName ?: toolName,
    pendingMessageId = pendingMessageIdOverride,
    isHighRisk = isHighRisk,
    promptCheckpointBoundary = promptCheckpointBoundary,
    promptResumeState = promptResumeState,
    subAgentApprovedToolName = subAgentApprovalResume?.approvedToolName,
    subAgentPromptResumeState = subAgentApprovalResume?.promptResumeState,
    subAgentIsHighRisk = subAgentApprovalResume?.isHighRisk,
    subAgentAgentId = subAgentApprovalResume?.agentId,
    subAgentChildRunId = subAgentApprovalResume?.childRunId,
    subAgentChildTaskId = subAgentApprovalResume?.childTaskId,
  )

  fun resultEvent(
    phase: OpenCrayApprovalPhase,
    emittedAtEpochMs: Long,
    text: String,
  ): OpenCrayApprovalEvent = OpenCrayApprovalEvent(
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
    phase = phase,
    toolName = toolName,
    text = text,
    isHighRisk = isHighRisk,
    emittedAtEpochMs = emittedAtEpochMs,
  )

  fun subAgentResumedEvent(
    summary: String,
    emittedAtEpochMs: Long,
  ): OpenCraySubAgentEvent? = subAgentLifecycle?.let { lifecycle ->
    OpenCraySubAgentEvent(
      runId = runId,
      taskId = taskId,
      phase = OpenCraySubAgentPhase.RESUMED,
      childRunId = lifecycle.childRunId,
      childTaskId = lifecycle.childTaskId,
      label = lifecycle.label,
      subagentType = lifecycle.subagentType,
      contextMode = lifecycle.contextMode,
      depth = lifecycle.depth,
      summary = summary,
      executionState = SubAgentExecutionState.RUNNING,
      continuationKind = SubAgentContinuationKind.NONE,
      resumable = false,
      requiresUserAction = false,
      isHighRisk = isHighRisk,
      emittedAtEpochMs = emittedAtEpochMs,
    )
  }

  fun subAgentTerminalEvent(
    summary: String,
    emittedAtEpochMs: Long,
  ): OpenCraySubAgentEvent? = subAgentLifecycle?.let { lifecycle ->
    OpenCraySubAgentEvent(
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
}

internal fun approvalDecisionState(
  approved: Boolean,
  rejected: Boolean,
  checkpoint: PersistedPromptCheckpoint?,
): AgentTaskApprovalState? = when {
  approved -> AgentTaskApprovalState.APPROVED
  rejected -> AgentTaskApprovalState.REJECTED
  else -> checkpointApprovalDecisionState(checkpoint)
}

internal fun checkpointApprovalDecisionState(
  checkpoint: PersistedPromptCheckpoint?,
): AgentTaskApprovalState? = when (checkpoint?.checkpointKind) {
  PromptCheckpointKind.APPROVED_PENDING_RESUME -> AgentTaskApprovalState.APPROVED
  PromptCheckpointKind.REJECTED_PENDING_RESUME -> AgentTaskApprovalState.REJECTED
  else -> null
}

internal fun approvalMetadataToolName(
  metadata: Map<String, String>,
): String? = metadata["normalizedToolName"]
  ?.takeIf(String::isNotBlank)
  ?: metadata[SubAgentApprovalResumeMetadata.KEY_APPROVED_TOOL_NAME]
    ?.takeIf(String::isNotBlank)
  ?: metadata["canonicalToolName"]
    ?.takeIf(String::isNotBlank)
  ?: metadata["toolName"]
    ?.takeIf(String::isNotBlank)

internal fun approvalMetadataResumeToolName(
  metadata: Map<String, String>,
): String? = metadata[OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME]
  ?.takeIf(String::isNotBlank)
  ?: metadata["canonicalToolName"]
    ?.takeIf(String::isNotBlank)
  ?: metadata["toolName"]
    ?.takeIf(String::isNotBlank)

internal fun approvalMetadataSupportsSessionScope(
  metadata: Map<String, String>,
): Boolean =
  metadata[ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND]
    ?.trim()
    ?.equals(ProviderNativeWebSearchSupport.APPROVAL_KIND, ignoreCase = true) == true &&
    metadata[ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL]
      ?.trim()
      ?.equals("true", ignoreCase = true) == true

internal fun approvalMetadataIsHighRisk(
  errorCode: String?,
  highRiskErrorCode: String,
  metadata: Map<String, String>,
): Boolean =
  errorCode == highRiskErrorCode ||
    metadata[SubAgentApprovalResumeMetadata.KEY_IS_HIGH_RISK]
      ?.trim()
      ?.equals("true", ignoreCase = true) == true

internal fun approvalMetadataSubAgentLifecycle(
  metadata: Map<String, String>,
): ApprovalDecisionSubAgentLifecycle? {
  val childRunId = metadata["childRunId"]?.trim()?.takeIf(String::isNotBlank) ?: return null
  val childTaskId = metadata["childTaskId"]?.trim()?.takeIf(String::isNotBlank) ?: return null
  val subagentType = metadata["subagentType"]?.trim()?.takeIf(String::isNotBlank)
    ?: return null
  return ApprovalDecisionSubAgentLifecycle(
    childRunId = childRunId,
    childTaskId = childTaskId,
    label = metadata["delegationDescription"]?.trim()?.takeIf(String::isNotBlank) ?: "Task",
    subagentType = subagentType,
    contextMode = metadata["subagentContextMode"]?.trim()?.takeIf(String::isNotBlank)
      ?: "delegated",
    depth = metadata["subagentDepth"]?.trim()?.toIntOrNull() ?: 1,
  )
}

internal fun mergeApprovalResumeMetadata(
  decoded: SubAgentApprovalResume?,
  metadata: Map<String, String>,
  lifecycle: ApprovalDecisionSubAgentLifecycle? = approvalMetadataSubAgentLifecycle(metadata),
): SubAgentApprovalResume? = decoded?.copy(
  agentId = decoded.agentId
    ?: metadata["agentId"]?.trim()?.takeIf(String::isNotBlank),
  childRunId = decoded.childRunId ?: lifecycle?.childRunId,
  childTaskId = decoded.childTaskId ?: lifecycle?.childTaskId,
)

internal fun ApprovalRequiredTaskProjection.toApprovalDecisionRecord(
  highRiskApprovalRequiredErrorCode: String,
  json: Json = PersistenceJson.instance,
): ApprovalDecisionRecord {
  val metadata = metadata
  val subAgentLifecycle = approvalMetadataSubAgentLifecycle(metadata)
  return ApprovalDecisionRecord(
    runId = runId,
    taskId = taskId,
    pendingMessageId = pendingMessageId,
    executionId = runSnapshot?.executionId,
    executionOrdinal = runSnapshot?.executionOrdinal?.takeIf { ordinal -> ordinal > 0 },
    executionKind = runSnapshot?.executionKind,
    toolName = approvalMetadataToolName(metadata),
    resumeToolName = checkpoint?.toolName ?: approvalMetadataResumeToolName(metadata),
    promptCheckpointBoundary = checkpoint?.runtimeCheckpointBoundaryOrNull()
      ?: OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata),
    promptResumeState = checkpoint?.promptResumeState
      ?: OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = metadata,
        json = json,
      ),
    subAgentApprovalResume = mergeApprovalResumeMetadata(
      decoded = checkpoint.restoredSubAgentApprovalResume()
        ?: SubAgentApprovalResumeMetadata.decodeFromMetadata(
          metadata = metadata,
          json = json,
        ),
      metadata = metadata,
      lifecycle = subAgentLifecycle,
    ),
    isHighRisk = checkpoint?.isHighRisk == true || approvalMetadataIsHighRisk(
      errorCode = errorCode,
      highRiskErrorCode = highRiskApprovalRequiredErrorCode,
      metadata = metadata,
    ),
    subAgentLifecycle = subAgentLifecycle,
  )
}

internal fun PersistedPromptCheckpoint?.restoredSubAgentApprovalResume(): SubAgentApprovalResume? {
  val checkpoint = this ?: return null
  val approvedToolName = checkpoint.subAgentApprovedToolName
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val promptResumeState = checkpoint.subAgentPromptResumeState ?: return null
  return SubAgentApprovalResume(
    approvedToolName = approvedToolName,
    promptResumeState = promptResumeState,
    isHighRisk = checkpoint.subAgentIsHighRisk == true,
    agentId = checkpoint.subAgentAgentId?.trim()?.takeIf(String::isNotBlank),
    childRunId = checkpoint.subAgentChildRunId?.trim()?.takeIf(String::isNotBlank),
    childTaskId = checkpoint.subAgentChildTaskId?.trim()?.takeIf(String::isNotBlank),
  )
}
