package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryFlushCoordinator
import com.opencray.runtime.memory.MemoryFlushSummary
import com.opencray.runtime.memory.TaskCommitmentResolver
import com.opencray.runtime.memory.MemoryTurnEvidence
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.context.RuntimeConversationMessage

internal data class MemoryIngestionSummary(
  val writtenRecords: List<MemoryRecord> = emptyList(),
  val resolvedRecords: List<MemoryRecord> = emptyList(),
  val reaffirmedRecords: List<MemoryRecord> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
) {
  val isEmpty: Boolean
    get() = writtenRecords.isEmpty() &&
      resolvedRecords.isEmpty() &&
      reaffirmedRecords.isEmpty() &&
      expiredRecordIds.isEmpty()
}

internal class ChatMemoryIngestionCoordinator(
  memoryStore: MemoryStore,
  private val workspaceIdProvider: () -> String? = { null },
  private val candidateExtractor: MemoryCandidateExtractor = MemoryCandidateExtractor(),
  private val writer: MemoryWriter = MemoryWriter(store = memoryStore),
  private val taskCommitmentResolver: TaskCommitmentResolver = TaskCommitmentResolver(store = memoryStore),
  private val flushCoordinator: MemoryFlushCoordinator = MemoryFlushCoordinator(
    candidateExtractor = candidateExtractor,
    writer = writer,
    existingRecordIdsProvider = { memoryStore.list().mapTo(linkedSetOf(), MemoryRecord::id) },
  ),
) {
  fun flushBeforeCompaction(
    sessionId: String,
    conversation: List<RuntimeConversationMessage>,
    taskId: String? = null,
  ): MemoryFlushSummary = flushCoordinator.flushBeforeCompaction(
    sessionId = sessionId,
    workspaceId = workspaceIdProvider(),
    conversation = conversation,
    taskId = taskId,
  )

  fun ingestCompletedTurn(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
    userInput: String? = null,
    assistantOutput: String?,
    toolObservations: List<String>,
  ): MemoryIngestionSummary {
    if (task.type != AgentTaskType.PROMPT) {
      return MemoryIngestionSummary()
    }
    if (result.status == ExecutionStatus.CANCELLED || isApprovalRequired(result)) {
      return MemoryIngestionSummary()
    }

    val evidence = MemoryTurnEvidence(
      sessionId = sessionId,
      taskId = task.id,
      workspaceId = workspaceIdProvider(),
      userInput = userInput?.trim()?.takeIf(String::isNotBlank) ?: task.input,
      assistantOutput = assistantOutput
        ?.trim()
        ?.takeIf { result.status == ExecutionStatus.SUCCESS }
        ?.takeIf(String::isNotBlank),
      toolObservations = toolObservations
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList(),
    )
    val maintenance = taskCommitmentResolver.maintain(evidence)
    val writeSummary = writer.write(candidateExtractor.extract(evidence))
    return MemoryIngestionSummary(
      writtenRecords = writeSummary.writtenRecords,
      resolvedRecords = maintenance.resolvedRecords,
      reaffirmedRecords = maintenance.reaffirmedRecords,
      expiredRecordIds = maintenance.expiredRecordIds,
    )
  }

  private fun isApprovalRequired(result: ExecutionResult): Boolean =
    result.status == ExecutionStatus.DENIED &&
      (result.errorCode == ERROR_APPROVAL_REQUIRED || result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED)

  private companion object {
    const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
  }
}
