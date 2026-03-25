package com.opencray.runtime.subagent

import kotlinx.serialization.Serializable

@Serializable
data class SubAgentHandleState(
  val agentId: String,
  val childRunId: String,
  val childTaskId: String,
  val description: String,
  val prompt: String,
  val supplementalInputs: List<String> = emptyList(),
  val subagentType: String,
  val contextMode: String,
  val parentRunId: String,
  val parentTaskId: String,
  val parentTurn: Int,
  val depth: Int,
  val activeSkillName: String? = null,
  val snapshot: SubAgentExecutionSnapshot,
  val pendingApprovalResume: SubAgentApprovalResume? = null,
  val childExecutionStatus: String? = null,
  val childTurnCount: Int? = null,
  val childToolCallCount: Int? = null,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(agentId.isNotBlank()) { "SubAgentHandleState agentId must not be blank." }
    require(childRunId.isNotBlank()) { "SubAgentHandleState childRunId must not be blank." }
    require(childTaskId.isNotBlank()) { "SubAgentHandleState childTaskId must not be blank." }
    require(description.isNotBlank()) { "SubAgentHandleState description must not be blank." }
    require(prompt.isNotBlank()) { "SubAgentHandleState prompt must not be blank." }
    require(subagentType.isNotBlank()) { "SubAgentHandleState subagentType must not be blank." }
    require(contextMode.isNotBlank()) { "SubAgentHandleState contextMode must not be blank." }
    require(parentRunId.isNotBlank()) { "SubAgentHandleState parentRunId must not be blank." }
    require(parentTaskId.isNotBlank()) { "SubAgentHandleState parentTaskId must not be blank." }
    require(parentTurn >= 0) { "SubAgentHandleState parentTurn must be >= 0." }
    require(depth >= 1) { "SubAgentHandleState depth must be >= 1." }
    require(createdAtEpochMs >= 0) { "SubAgentHandleState createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0) { "SubAgentHandleState updatedAtEpochMs must be >= 0." }
  }

  fun effectivePrompt(): String {
    val normalizedPrompt = prompt.trim()
    val supplements = supplementalInputs
      .map(String::trim)
      .filter(String::isNotBlank)
    if (supplements.isEmpty()) {
      return normalizedPrompt
    }
    return buildString {
      append(normalizedPrompt)
      supplements.forEachIndexed { index, input ->
        appendLine()
        appendLine()
        appendLine("[Additional parent input ${index + 1}]")
        append(input)
      }
    }.trim()
  }

  fun resolvedContextMode(): SubAgentContextMode = requireNotNull(
    SubAgentContextMode.fromWireValue(contextMode),
  ) {
    "Unknown subagent context mode '$contextMode'."
  }

  fun toTask(): SubAgentTask = SubAgentTask(
    description = description,
    prompt = effectivePrompt(),
    subagentType = subagentType,
    contextMode = resolvedContextMode(),
    parentRunId = parentRunId,
    parentTaskId = parentTaskId,
    parentTurn = parentTurn,
    depth = depth,
    activeSkillName = activeSkillName,
  )

  companion object {
    fun queued(
      agentId: String,
      childRunId: String,
      childTaskId: String,
      description: String,
      prompt: String,
      subagentType: String,
      contextMode: String,
      parentRunId: String,
      parentTaskId: String,
      parentTurn: Int,
      depth: Int,
      activeSkillName: String?,
      createdAtEpochMs: Long,
    ): SubAgentHandleState = SubAgentHandleState(
      agentId = agentId,
      childRunId = childRunId,
      childTaskId = childTaskId,
      description = description,
      prompt = prompt,
      subagentType = subagentType,
      contextMode = contextMode,
      parentRunId = parentRunId,
      parentTaskId = parentTaskId,
      parentTurn = parentTurn,
      depth = depth,
      activeSkillName = activeSkillName,
      snapshot = SubAgentExecutionSnapshot.backgroundQueued(
        headline = "Queued delegated child run '$description'.",
      ),
      createdAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = createdAtEpochMs,
    )
  }
}
