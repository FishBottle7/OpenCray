package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.OpenCrayToolCallEvent

internal data class ApprovalRequiredTaskProjection(
  val sessionId: String,
  val taskSnapshot: SessionQueueTaskSnapshot,
  val runSnapshot: AgentRunSnapshot?,
  val checkpoint: PersistedPromptCheckpoint?,
) {
  val taskId: String
    get() = taskSnapshot.task.id

  val runId: String
    get() = runSnapshot?.runId
      ?: taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: taskId

  val errorCode: String?
    get() = runSnapshot?.errorCode ?: taskSnapshot.lastErrorCode

  val errorBody: String?
    get() = runSnapshot?.errorMessage ?: taskSnapshot.lastErrorMessage

  val metadata: Map<String, String>
    get() = runSnapshot?.resultMetadata.orEmpty()

  val pendingMessageId: String?
    get() = runSnapshot?.pendingMessageId
      ?: checkpoint?.pendingMessageId
      ?: taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)

  val toolReason: String?
    get() = runSnapshot?.resultMetadata?.get("toolReason")
      ?: (runSnapshot?.lastEvent as? OpenCrayToolCallEvent)?.call?.reason

  fun isVisibleApprovalLifecycle(): Boolean =
    taskSnapshot.lifecycleState == QueueTaskLifecycleState.SUSPENDED ||
      taskSnapshot.lifecycleState == QueueTaskLifecycleState.FAILED
}

internal fun approvalRequiredTaskProjectionsForSession(
  sessionId: String,
  hostAccess: RuntimeRunLookupAccess,
  approvalRequiredErrorCode: String = DEFAULT_APPROVAL_REQUIRED_ERROR_CODE,
  highRiskApprovalRequiredErrorCode: String = DEFAULT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE,
): List<ApprovalRequiredTaskProjection> {
  val session = hostAccess.session(sessionId)
  val queueSnapshot = session.snapshot()
  return approvalRequiredTaskProjections(
    sessionId = sessionId,
    queueTaskSnapshots = queueSnapshot.tasks,
    runSnapshots = session.listRuns(),
    checkpoints = hostAccess.promptCheckpointStore(sessionId).list(),
    approvalRequiredErrorCode = approvalRequiredErrorCode,
    highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
  )
}

internal fun approvalRequiredTaskProjections(
  sessionId: String,
  queueTaskSnapshots: Collection<SessionQueueTaskSnapshot>,
  runSnapshots: Collection<AgentRunSnapshot>,
  checkpoints: Collection<PersistedPromptCheckpoint>,
  approvalRequiredErrorCode: String = DEFAULT_APPROVAL_REQUIRED_ERROR_CODE,
  highRiskApprovalRequiredErrorCode: String = DEFAULT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE,
): List<ApprovalRequiredTaskProjection> {
  val runsByTaskId = runSnapshots.associateBy(AgentRunSnapshot::taskId)
  val queueTasksById = queueTaskSnapshots.associateBy { taskSnapshot -> taskSnapshot.task.id }
  val checkpointsByTaskId = checkpoints.associateBy(PersistedPromptCheckpoint::taskId)
  val candidateTaskIds = linkedSetOf<String>().apply {
    addAll(queueTasksById.keys)
    addAll(runsByTaskId.keys)
    addAll(checkpointsByTaskId.keys)
  }
  return candidateTaskIds.mapNotNull { taskId ->
    val runSnapshot = runsByTaskId[taskId]
    val checkpoint = checkpointsByTaskId[taskId]
    val taskSnapshot = queueTasksById[taskId]
      ?: approvalLookupSyntheticTaskSnapshot(
        sessionId = sessionId,
        taskId = taskId,
        runSnapshot = runSnapshot,
        checkpoint = checkpoint,
        approvalRequiredErrorCode = approvalRequiredErrorCode,
        highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
      )
    if (
      taskSnapshot == null ||
      !approvalLookupRequiresDecision(
        taskSnapshot = taskSnapshot,
        runSnapshot = runSnapshot,
        checkpoint = checkpoint,
        approvalRequiredErrorCode = approvalRequiredErrorCode,
        highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
      )
    ) {
      return@mapNotNull null
    }
    ApprovalRequiredTaskProjection(
      sessionId = sessionId,
      taskSnapshot = taskSnapshot,
      runSnapshot = runSnapshot,
      checkpoint = checkpoint,
    )
  }
}

internal fun findApprovalRequiredTaskProjection(
  sessionIds: List<String>,
  hostAccess: RuntimeRunLookupAccess,
  taskIdOrRunId: String,
  approvalRequiredErrorCode: String = DEFAULT_APPROVAL_REQUIRED_ERROR_CODE,
  highRiskApprovalRequiredErrorCode: String = DEFAULT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE,
): ApprovalRequiredTaskProjection? = sessionIds.firstNotNullOfOrNull { sessionId ->
  approvalRequiredTaskProjectionsForSession(
    sessionId = sessionId,
    hostAccess = hostAccess,
    approvalRequiredErrorCode = approvalRequiredErrorCode,
    highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
  ).firstOrNull { projection ->
    projection.taskId == taskIdOrRunId || projection.runId == taskIdOrRunId
  }
}

private fun approvalLookupIsRequiredError(
  errorCode: String?,
  approvalRequiredErrorCode: String,
  highRiskApprovalRequiredErrorCode: String,
): Boolean = errorCode == approvalRequiredErrorCode ||
  errorCode == highRiskApprovalRequiredErrorCode

private fun approvalLookupRequiresDecision(
  taskSnapshot: SessionQueueTaskSnapshot,
  runSnapshot: AgentRunSnapshot?,
  checkpoint: PersistedPromptCheckpoint?,
  approvalRequiredErrorCode: String,
  highRiskApprovalRequiredErrorCode: String,
): Boolean = approvalLookupIsRequiredError(
  errorCode = taskSnapshot.lastErrorCode,
  approvalRequiredErrorCode = approvalRequiredErrorCode,
  highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
) ||
  approvalLookupIsRequiredError(
    errorCode = runSnapshot?.errorCode,
    approvalRequiredErrorCode = approvalRequiredErrorCode,
    highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
  ) ||
  checkpoint?.checkpointKind == PromptCheckpointKind.WAITING_APPROVAL

private fun approvalLookupSyntheticTaskSnapshot(
  sessionId: String,
  taskId: String,
  runSnapshot: AgentRunSnapshot?,
  checkpoint: PersistedPromptCheckpoint?,
  approvalRequiredErrorCode: String,
  highRiskApprovalRequiredErrorCode: String,
): SessionQueueTaskSnapshot? {
  val syntheticErrorCode = runSnapshot?.errorCode
    ?: when {
      checkpoint?.checkpointKind != PromptCheckpointKind.WAITING_APPROVAL -> null
      checkpoint.isHighRisk -> highRiskApprovalRequiredErrorCode
      else -> approvalRequiredErrorCode
    }
  val requiresDecision = approvalLookupIsRequiredError(
    errorCode = syntheticErrorCode,
    approvalRequiredErrorCode = approvalRequiredErrorCode,
    highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
  ) || checkpoint?.checkpointKind == PromptCheckpointKind.WAITING_APPROVAL
  if (!requiresDecision) {
    return null
  }
  val createdAtEpochMs = runSnapshot?.acceptedAtEpochMs
    ?: checkpoint?.createdAtEpochMs
    ?: return null
  val updatedAtEpochMs = maxOf(
    createdAtEpochMs,
    runSnapshot?.updatedAtEpochMs ?: 0L,
    checkpoint?.updatedAtEpochMs ?: 0L,
  )
  val lifecycleState = when {
    checkpoint?.checkpointKind == PromptCheckpointKind.WAITING_APPROVAL ->
      QueueTaskLifecycleState.SUSPENDED
    runSnapshot?.executionStatus == ExecutionStatus.DENIED &&
      approvalLookupIsRequiredError(
        errorCode = runSnapshot.errorCode,
        approvalRequiredErrorCode = approvalRequiredErrorCode,
        highRiskApprovalRequiredErrorCode = highRiskApprovalRequiredErrorCode,
      ) -> QueueTaskLifecycleState.SUSPENDED
    runSnapshot?.lifecycleState != null -> runSnapshot.lifecycleState
    else -> QueueTaskLifecycleState.SUSPENDED
  }
  val taskState = runSnapshot?.taskState ?: when (lifecycleState) {
    QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
    QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
    QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
    QueueTaskLifecycleState.RUNNING,
    QueueTaskLifecycleState.CANCEL_REQUESTED,
    -> AgentTaskState.RUNNING

    QueueTaskLifecycleState.QUEUED,
    QueueTaskLifecycleState.RETRY_PENDING,
    -> AgentTaskState.QUEUED
  }
  val pendingMessageId = runSnapshot?.pendingMessageId
    ?: checkpoint?.pendingMessageId
  val task = AgentTask(
    id = taskId,
    type = AgentTaskType.PROMPT,
    input = runSnapshot?.errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "Approval required",
    state = taskState,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = APPROVAL_LOOKUP_SYNTHETIC_REASON_CODE,
    ),
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    metadata = buildMap {
      val runId = runSnapshot?.runId ?: checkpoint?.runId
      if (!runId.isNullOrBlank()) {
        put(AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID, runId)
      }
      if (!pendingMessageId.isNullOrBlank()) {
        put(AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID, pendingMessageId)
      }
      put("sessionId", sessionId)
    },
  )
  return SessionQueueTaskSnapshot(
    enqueueOrder = 0L,
    task = task,
    lifecycleState = lifecycleState,
    attempt = runSnapshot?.attempt ?: 0,
    executionOrdinal = runSnapshot?.executionOrdinal ?: 0,
    executionId = runSnapshot?.executionId,
    executionKind = runSnapshot?.executionKind,
    lastErrorCode = syntheticErrorCode,
    lastErrorMessage = runSnapshot?.errorMessage,
  )
}

private const val DEFAULT_APPROVAL_REQUIRED_ERROR_CODE: String = "APPROVAL_REQUIRED"
private const val DEFAULT_HIGH_RISK_APPROVAL_REQUIRED_ERROR_CODE: String =
  "HIGH_RISK_APPROVAL_REQUIRED"
private const val APPROVAL_LOOKUP_SYNTHETIC_REASON_CODE: String =
  "APPROVAL_LOOKUP_SYNTHETIC"
