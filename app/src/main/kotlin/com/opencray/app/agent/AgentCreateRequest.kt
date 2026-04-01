package com.opencray.app.agent

internal data class AgentCreateRequest(
  val displayName: String,
  val presetName: String,
  val plasticity: String,
  val callsYou: String = "",
  val addressStyle: String = "",
  val mode: String = "full",
  val voiceSummary: String = "",
  val verbosity: String = "",
  val relationshipStyle: String = "",
  val riskTolerance: String = "",
  val toolUseBias: String = "",
  val baseDescription: String = "",
  val collaborationGuidance: String = "",
  val escalationRules: String = "",
  val forbiddenBehaviors: String = "",
  val llm: AgentLlmConfig? = null,
  val avatar: AgentAvatarConfig? = null,
  val imageReferences: List<AgentImageReferenceConfig> = emptyList(),
  val activateOnCreate: Boolean = true,
) {
  init {
    require(displayName.isNotBlank()) { "AgentCreateRequest displayName must not be blank." }
    require(presetName.isNotBlank()) { "AgentCreateRequest presetName must not be blank." }
    require(plasticity.isNotBlank()) { "AgentCreateRequest plasticity must not be blank." }
    require(mode.isNotBlank()) { "AgentCreateRequest mode must not be blank." }
  }
}
