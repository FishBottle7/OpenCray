package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentLiveContextSnapshot

enum class OpenCrayRunLifecyclePhase {
  START,
  END,
  ERROR,
  CANCELLED,
}

enum class OpenCrayApprovalPhase {
  REQUIRED,
  APPROVED,
  REJECTED,
}

enum class OpenCrayAssistantPhase {
  COMMENTARY,
  FINAL_ANSWER,
}

enum class OpenCraySubAgentPhase {
  STARTED,
  RESUMED,
  COMPLETED,
  FAILED,
  CANCELLED,
}

sealed interface OpenCrayAgentRunEvent {
  val runId: String
  val taskId: String
  val executionId: String?
  val executionOrdinal: Int?
  val executionKind: String?
  val turn: Int?
  val emittedAtEpochMs: Long
}

sealed interface OpenCrayAssistantPhaseEvent : OpenCrayAgentRunEvent {
  val text: String
  val responseFormat: String?
  val isFinal: Boolean
  val stage: String?
  val metadata: Map<String, String>
  val phase: OpenCrayAssistantPhase

  fun toAssistantEvent(): OpenCrayAssistantEvent
}

data class OpenCrayLifecycleEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  val phase: OpenCrayRunLifecyclePhase,
  val status: ExecutionStatus? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  override val turn: Int? = null,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayAssistantEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  override val turn: Int,
  override val text: String,
  override val responseFormat: String? = null,
  override val isFinal: Boolean = false,
  override val stage: String? = null,
  override val metadata: Map<String, String> = emptyMap(),
  override val emittedAtEpochMs: Long,
) : OpenCrayAssistantPhaseEvent {
  override val phase: OpenCrayAssistantPhase
    get() = if (isFinal) {
      OpenCrayAssistantPhase.FINAL_ANSWER
    } else {
      OpenCrayAssistantPhase.COMMENTARY
    }

  override fun toAssistantEvent(): OpenCrayAssistantEvent = this
}

data class OpenCraySupplementInput(
  val entryId: String,
  val text: String,
  val createdAtEpochMs: Long,
)

data class OpenCraySupplementEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  override val turn: Int,
  val entryId: String,
  val text: String,
  val checkpoint: String = "turn_start",
  val metadata: Map<String, String> = emptyMap(),
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayApprovalEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  val phase: OpenCrayApprovalPhase,
  val toolName: String? = null,
  val text: String,
  val isHighRisk: Boolean = false,
  override val turn: Int? = null,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCraySubAgentEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  val phase: OpenCraySubAgentPhase,
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
  val summary: String? = null,
  val executionState: SubAgentExecutionState? = null,
  val continuationKind: SubAgentContinuationKind? = null,
  val liveContext: SubAgentLiveContextSnapshot? = null,
  val resumable: Boolean = false,
  val requiresUserAction: Boolean = false,
  val isHighRisk: Boolean = false,
  override val turn: Int? = null,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayToolCallEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  override val turn: Int,
  val call: AgentToolCall,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayToolResultEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  override val turn: Int,
  val call: AgentToolCall,
  val result: AgentToolResult,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayMemoryWriteEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  val writtenRecordIds: List<String> = emptyList(),
  val writtenKinds: List<String> = emptyList(),
  val resolvedRecordIds: List<String> = emptyList(),
  val suppressedRecordIds: List<String> = emptyList(),
  val reaffirmedRecordIds: List<String> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
  override val turn: Int? = null,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayCancellationEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  val toolName: String? = null,
  val outcome: String? = null,
  val text: String,
  override val turn: Int? = null,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayMemoryRetrievalEvent(
  override val runId: String,
  override val taskId: String,
  override val executionId: String? = null,
  override val executionOrdinal: Int? = null,
  override val executionKind: String? = null,
  override val turn: Int,
  val toolName: String,
  val operation: String,
  val surface: String? = null,
  val query: String? = null,
  val queryTerms: List<String> = emptyList(),
  val resultCount: Int? = null,
  val corpusFileCount: Int? = null,
  val recordIds: List<String> = emptyList(),
  val paths: List<String> = emptyList(),
  val lineRanges: List<String> = emptyList(),
  val path: String? = null,
  val fromLine: Int? = null,
  val returnedLineCount: Int? = null,
  val totalLineCount: Int? = null,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

interface OpenCrayAgentRuntimeEventSink {
  fun onRunEvent(task: com.opencray.core.contracts.AgentTask, event: OpenCrayAgentRunEvent) = Unit

  fun onAssistantDraftUpdated(
    task: com.opencray.core.contracts.AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ) = Unit

  fun onAssistantDraftCleared(
    task: com.opencray.core.contracts.AgentTask,
    emittedAtEpochMs: Long,
  ) = Unit
}

object NoOpOpenCrayAgentRuntimeEventSink : OpenCrayAgentRuntimeEventSink
