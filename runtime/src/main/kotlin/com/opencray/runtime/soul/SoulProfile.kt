package com.opencray.runtime.soul

data class SoulProfile(
  val displayName: String? = null,
  val presetName: String? = null,
  val voice: String? = null,
  val preferredNaming: String? = null,
  val preferredAddressStyle: PreferredAddressStyle? = null,
  val warmthPreferenceOffset: Int? = null,
  val formalityPreferenceOffset: Int? = null,
  val initiativePreferenceOffset: Int? = null,
  val playfulnessPreferenceOffset: Int? = null,
  val reassurancePreferenceOffset: Int? = null,
  val intimacyPermissionBand: RelationshipBand? = null,
  val playfulnessPermissionBand: RelationshipBand? = null,
  val supportiveReassuranceAllowed: Boolean? = null,
  val proactiveRelationalCheckInAllowed: Boolean? = null,
  val lightPlayfulnessAllowed: Boolean? = null,
  val playfulTeasingAllowed: Boolean? = null,
  val highIntimacyBehaviorAllowed: Boolean? = null,
  val playfulAffectionAllowed: Boolean? = null,
  val tone: SoulTone = SoulTone.STEADY,
  val verbosity: SoulVerbosity = SoulVerbosity.BALANCED,
  val plasticity: SoulPlasticity = SoulPlasticity.LOW,
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
    requirePreferenceOffsetInRange(warmthPreferenceOffset, "warmthPreferenceOffset")
    requirePreferenceOffsetInRange(formalityPreferenceOffset, "formalityPreferenceOffset")
    requirePreferenceOffsetInRange(initiativePreferenceOffset, "initiativePreferenceOffset")
    requirePreferenceOffsetInRange(playfulnessPreferenceOffset, "playfulnessPreferenceOffset")
    requirePreferenceOffsetInRange(reassurancePreferenceOffset, "reassurancePreferenceOffset")
  }

  fun isMeaningful(): Boolean = !displayName.isNullOrBlank() ||
    !presetName.isNullOrBlank() ||
    !voice.isNullOrBlank() ||
    !preferredNaming.isNullOrBlank() ||
    preferredAddressStyle != null ||
    warmthPreferenceOffset != null ||
    formalityPreferenceOffset != null ||
    initiativePreferenceOffset != null ||
    playfulnessPreferenceOffset != null ||
    reassurancePreferenceOffset != null ||
    intimacyPermissionBand != null ||
    playfulnessPermissionBand != null ||
    supportiveReassuranceAllowed != null ||
    proactiveRelationalCheckInAllowed != null ||
    lightPlayfulnessAllowed != null ||
    playfulTeasingAllowed != null ||
    highIntimacyBehaviorAllowed != null ||
    playfulAffectionAllowed != null ||
    !customGuidance.isNullOrBlank() ||
    escalationRules.isNotEmpty() ||
    forbiddenBehaviors.isNotEmpty() ||
    collaborationPreferences.isNotEmpty() ||
    tone != SoulTone.STEADY ||
    verbosity != SoulVerbosity.BALANCED ||
    userRelationshipStyle != UserRelationshipStyle.COLLABORATIVE ||
    riskTolerance != RiskTolerance.CONSERVATIVE ||
    toolUseBias != ToolUseBias.VERIFY_FIRST

  private fun requirePreferenceOffsetInRange(
    offset: Int?,
    fieldName: String,
  ) {
    require(offset == null || offset in PreferenceAxisState.MIN_OFFSET..PreferenceAxisState.MAX_OFFSET) {
      "SoulProfile $fieldName must stay within [${PreferenceAxisState.MIN_OFFSET}, ${PreferenceAxisState.MAX_OFFSET}]."
    }
  }
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

enum class SoulPlasticity {
  LOW,
  MEDIUM,
  HIGH,
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
