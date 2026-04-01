package com.opencray.app.agent

import kotlinx.serialization.Serializable

@Serializable
internal data class AgentDescriptor(
  val agentId: String,
  val displayName: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val presetName: String,
  val plasticity: String,
  val activeSessionId: String? = null,
  val isArchived: Boolean = false,
  val avatarSeed: String? = null,
  val customLabel: String? = null,
  val notes: String? = null,
) {
  init {
    require(agentId.isNotBlank()) { "AgentDescriptor agentId must not be blank." }
    require(displayName.isNotBlank()) { "AgentDescriptor displayName must not be blank." }
    require(createdAtEpochMs >= 0L) { "AgentDescriptor createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0L) { "AgentDescriptor updatedAtEpochMs must be >= 0." }
    require(presetName.isNotBlank()) { "AgentDescriptor presetName must not be blank." }
    require(plasticity.isNotBlank()) { "AgentDescriptor plasticity must not be blank." }
    require(activeSessionId == null || activeSessionId.isNotBlank()) {
      "AgentDescriptor activeSessionId must not be blank when present."
    }
  }
}
