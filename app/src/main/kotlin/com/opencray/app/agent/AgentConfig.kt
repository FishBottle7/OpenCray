package com.opencray.app.agent

import kotlinx.serialization.Serializable

@Serializable
internal data class AgentLlmConfig(
  val provider: String,
  val protocol: String,
  val baseUrl: String? = null,
  val apiKey: String? = null,
  val model: String,
  val reasoningEffort: String? = null,
) {
  init {
    require(provider.isNotBlank()) { "AgentLlmConfig provider must not be blank." }
    require(protocol.isNotBlank()) { "AgentLlmConfig protocol must not be blank." }
    require(model.isNotBlank()) { "AgentLlmConfig model must not be blank." }
  }
}

@Serializable
internal data class AgentAvatarConfig(
  val source: String,
  val settingsAssetId: String? = null,
) {
  init {
    require(source.isNotBlank()) { "AgentAvatarConfig source must not be blank." }
    require(source != "custom" || !settingsAssetId.isNullOrBlank()) {
      "AgentAvatarConfig custom source requires settingsAssetId."
    }
  }
}

@Serializable
internal data class AgentImageReferenceConfig(
  val referenceId: String,
  val label: String,
  val settingsAssetId: String? = null,
) {
  init {
    require(referenceId.isNotBlank()) { "AgentImageReferenceConfig referenceId must not be blank." }
    require(label.isNotBlank()) { "AgentImageReferenceConfig label must not be blank." }
  }
}

@Serializable
internal data class AgentConfig(
  val agentId: String,
  val displayName: String,
  val presetName: String,
  val plasticity: String,
  val callsYou: String? = null,
  val addressStyle: String? = null,
  val mode: String,
  val voiceSummary: String? = null,
  val verbosity: String? = null,
  val relationshipStyle: String? = null,
  val riskTolerance: String? = null,
  val toolUseBias: String? = null,
  val baseDescription: String? = null,
  val collaborationGuidance: String? = null,
  val escalationRules: String? = null,
  val forbiddenBehaviors: String? = null,
  val llm: AgentLlmConfig? = null,
  val avatar: AgentAvatarConfig? = null,
  val imageReferences: List<AgentImageReferenceConfig> = emptyList(),
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(agentId.isNotBlank()) { "AgentConfig agentId must not be blank." }
    require(displayName.isNotBlank()) { "AgentConfig displayName must not be blank." }
    require(presetName.isNotBlank()) { "AgentConfig presetName must not be blank." }
    require(plasticity.isNotBlank()) { "AgentConfig plasticity must not be blank." }
    require(mode.isNotBlank()) { "AgentConfig mode must not be blank." }
    require(createdAtEpochMs >= 0L) { "AgentConfig createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0L) { "AgentConfig updatedAtEpochMs must be >= 0." }
  }
}
