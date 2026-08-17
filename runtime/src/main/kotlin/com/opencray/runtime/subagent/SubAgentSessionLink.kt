package com.opencray.runtime.subagent

import kotlinx.serialization.Serializable

@Serializable
data class SubAgentSessionLink(
  val parentSessionId: String,
  val parentRunId: String,
  val agentId: String,
  val childSessionId: String,
  val childRootRunId: String?,
  val childRootTaskId: String?,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
  val label: String,
  val status: String,
  val closed: Boolean,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(parentSessionId.isNotBlank()) { "SubAgentSessionLink parentSessionId must not be blank." }
    require(parentRunId.isNotBlank()) { "SubAgentSessionLink parentRunId must not be blank." }
    require(agentId.isNotBlank()) { "SubAgentSessionLink agentId must not be blank." }
    require(childSessionId.isNotBlank()) { "SubAgentSessionLink childSessionId must not be blank." }
    require(subagentType.isNotBlank()) { "SubAgentSessionLink subagentType must not be blank." }
    require(contextMode.isNotBlank()) { "SubAgentSessionLink contextMode must not be blank." }
    require(depth >= 1) { "SubAgentSessionLink depth must be >= 1." }
    require(label.isNotBlank()) { "SubAgentSessionLink label must not be blank." }
    require(status.isNotBlank()) { "SubAgentSessionLink status must not be blank." }
    require(createdAtEpochMs >= 0L) { "SubAgentSessionLink createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0L) { "SubAgentSessionLink updatedAtEpochMs must be >= 0." }
  }

  companion object {
    fun fromHandle(
      parentSessionId: String,
      handle: SubAgentHandleState,
      closed: Boolean = false,
    ): SubAgentSessionLink = SubAgentSessionLink(
      parentSessionId = parentSessionId,
      parentRunId = handle.parentRunId,
      agentId = handle.agentId,
      childSessionId = handle.childSessionId,
      childRootRunId = handle.childRunId,
      childRootTaskId = handle.childTaskId,
      subagentType = handle.subagentType,
      contextMode = handle.contextMode,
      depth = handle.depth,
      label = handle.description,
      status = handle.snapshot.state.wireValue,
      closed = closed,
      createdAtEpochMs = handle.createdAtEpochMs,
      updatedAtEpochMs = handle.updatedAtEpochMs,
    )
  }
}
