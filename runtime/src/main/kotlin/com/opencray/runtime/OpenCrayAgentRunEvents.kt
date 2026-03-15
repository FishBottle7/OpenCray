package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus

enum class OpenCrayRunLifecyclePhase {
  START,
  END,
  ERROR,
  CANCELLED,
}

sealed interface OpenCrayAgentRunEvent {
  val runId: String
  val taskId: String
  val turn: Int?
  val emittedAtEpochMs: Long
}

data class OpenCrayLifecycleEvent(
  override val runId: String,
  override val taskId: String,
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
  override val turn: Int,
  val text: String,
  val responseFormat: String,
  val isFinal: Boolean,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayToolCallEvent(
  override val runId: String,
  override val taskId: String,
  override val turn: Int,
  val call: AgentToolCall,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

data class OpenCrayToolResultEvent(
  override val runId: String,
  override val taskId: String,
  override val turn: Int,
  val call: AgentToolCall,
  val result: AgentToolResult,
  override val emittedAtEpochMs: Long,
) : OpenCrayAgentRunEvent

interface OpenCrayAgentRuntimeEventSink {
  fun onRunEvent(task: com.opencray.core.contracts.AgentTask, event: OpenCrayAgentRunEvent) = Unit
}

object NoOpOpenCrayAgentRuntimeEventSink : OpenCrayAgentRuntimeEventSink
