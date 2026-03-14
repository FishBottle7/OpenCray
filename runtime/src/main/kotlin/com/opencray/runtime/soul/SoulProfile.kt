package com.opencray.runtime.soul

data class SoulProfile(
  val displayName: String? = null,
  val presetName: String? = null,
  val voice: String? = null,
  val tone: SoulTone = SoulTone.STEADY,
  val verbosity: SoulVerbosity = SoulVerbosity.BALANCED,
  val userRelationshipStyle: UserRelationshipStyle = UserRelationshipStyle.COLLABORATIVE,
  val riskTolerance: RiskTolerance = RiskTolerance.CONSERVATIVE,
  val toolUseBias: ToolUseBias = ToolUseBias.VERIFY_FIRST,
  val escalationRules: List<String> = emptyList(),
  val forbiddenBehaviors: List<String> = emptyList(),
  val collaborationPreferences: List<String> = emptyList(),
  val customGuidance: String? = null,
) {
  init {
    require(escalationRules.none(String::isBlank)) { "SoulProfile escalationRules must not contain blank entries." }
    require(forbiddenBehaviors.none(String::isBlank)) { "SoulProfile forbiddenBehaviors must not contain blank entries." }
    require(collaborationPreferences.none(String::isBlank)) {
      "SoulProfile collaborationPreferences must not contain blank entries."
    }
  }

  fun isMeaningful(): Boolean = !displayName.isNullOrBlank() ||
    !presetName.isNullOrBlank() ||
    !voice.isNullOrBlank() ||
    !customGuidance.isNullOrBlank() ||
    escalationRules.isNotEmpty() ||
    forbiddenBehaviors.isNotEmpty() ||
    collaborationPreferences.isNotEmpty() ||
    tone != SoulTone.STEADY ||
    verbosity != SoulVerbosity.BALANCED ||
    userRelationshipStyle != UserRelationshipStyle.COLLABORATIVE ||
    riskTolerance != RiskTolerance.CONSERVATIVE ||
    toolUseBias != ToolUseBias.VERIFY_FIRST
}

enum class SoulTone {
  STEADY,
  BUILDER,
  WARM,
  CUSTOM,
}

enum class SoulVerbosity {
  TERSE,
  BALANCED,
  EXPANSIVE,
}

enum class UserRelationshipStyle {
  COLLABORATIVE,
  DIRECT,
  SUPPORTIVE,
}

enum class RiskTolerance {
  CONSERVATIVE,
  BALANCED,
  BOLD,
}

enum class ToolUseBias {
  VERIFY_FIRST,
  BALANCED,
  TOOL_FORWARD,
}

data class SoulProfileSeed(
  val presetName: String? = null,
  val displayName: String? = null,
  val customGuidance: String? = null,
  val extensions: Map<String, String> = emptyMap(),
)
