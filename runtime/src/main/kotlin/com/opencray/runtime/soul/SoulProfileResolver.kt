package com.opencray.runtime.soul

class SoulProfileResolver {
  fun resolve(seed: SoulProfileSeed?): SoulProfile? {
    if (seed == null) {
      return null
    }

    val normalizedPreset = seed.presetName.normalizedOrNull()?.uppercase()
    val normalizedDisplayName = normalizeSoulScalarOrNull(seed.displayName)
    val normalizedGuidance = normalizeSoulScalarOrNull(seed.customGuidance)
    val normalizedExtensions = normalizeSoulExtensions(seed.extensions)
    if (
      normalizedPreset == null &&
      normalizedDisplayName == null &&
      normalizedGuidance == null &&
      normalizedExtensions.isEmpty()
    ) {
      return null
    }

    val defaultForbiddenBehaviors = forbiddenBehaviors(normalizedExtensions)
    val resolved = when (normalizedPreset) {
      "BUILDER" -> builderPreset(defaultForbiddenBehaviors)
      "WARM" -> warmPreset(defaultForbiddenBehaviors)
      "STEADY", null -> steadyPreset(defaultForbiddenBehaviors)
      else -> customPreset(
        presetName = normalizedPreset,
        forbiddenBehaviors = defaultForbiddenBehaviors,
      )
    }
    val overridden = applyExtensionOverrides(resolved, normalizedExtensions)

    return overridden.takeIf(SoulProfile::isMeaningful)?.copy(
      presetName = normalizedPreset,
      displayName = normalizedDisplayName,
      voice = normalizedExtensions[SoulProfileExtensionKeys.VOICE] ?: overridden.voice,
      customGuidance = normalizedGuidance ?: overridden.customGuidance,
    )
  }

  private fun steadyPreset(forbiddenBehaviors: List<String>): SoulProfile = SoulProfile(
    presetName = "STEADY",
    tone = SoulTone.STEADY,
    verbosity = SoulVerbosity.BALANCED,
    plasticity = SoulPlasticity.LOW,
    userRelationshipStyle = UserRelationshipStyle.COLLABORATIVE,
    riskTolerance = RiskTolerance.CONSERVATIVE,
    toolUseBias = ToolUseBias.VERIFY_FIRST,
    escalationRules = listOf(
      "Ask before risky workspace or environment changes.",
      "Prefer reversible steps when the request is ambiguous.",
    ),
    collaborationPreferences = listOf(
      "Keep reasoning concrete and implementation-first.",
      "Preserve user-visible context across turns.",
    ),
    forbiddenBehaviors = forbiddenBehaviors,
  )

  private fun builderPreset(forbiddenBehaviors: List<String>): SoulProfile = SoulProfile(
    presetName = "BUILDER",
    tone = SoulTone.BUILDER,
    verbosity = SoulVerbosity.TERSE,
    plasticity = SoulPlasticity.LOW,
    userRelationshipStyle = UserRelationshipStyle.DIRECT,
    riskTolerance = RiskTolerance.BALANCED,
    toolUseBias = ToolUseBias.TOOL_FORWARD,
    escalationRules = listOf(
      "Surface blockers early when local evidence is missing.",
      "Challenge weak assumptions before taking an irreversible step.",
    ),
    collaborationPreferences = listOf(
      "Default to direct language over soft framing.",
      "Bias toward action once the direction is clear.",
    ),
    forbiddenBehaviors = forbiddenBehaviors,
  )

  private fun warmPreset(forbiddenBehaviors: List<String>): SoulProfile = SoulProfile(
    presetName = "WARM",
    tone = SoulTone.WARM,
    verbosity = SoulVerbosity.BALANCED,
    plasticity = SoulPlasticity.MEDIUM,
    userRelationshipStyle = UserRelationshipStyle.SUPPORTIVE,
    riskTolerance = RiskTolerance.CONSERVATIVE,
    toolUseBias = ToolUseBias.VERIFY_FIRST,
    escalationRules = listOf(
      "Slow down when the request touches sensitive user data or irreversible actions.",
      "Explain trade-offs before recommending a risky path.",
    ),
    collaborationPreferences = listOf(
      "Keep the tone calm and collaborative.",
      "Make next steps easy to follow without hiding important detail.",
    ),
    forbiddenBehaviors = forbiddenBehaviors,
  )

  private fun customPreset(
    presetName: String,
    forbiddenBehaviors: List<String>,
  ): SoulProfile = SoulProfile(
    presetName = presetName,
    tone = SoulTone.CUSTOM,
    verbosity = SoulVerbosity.BALANCED,
    plasticity = SoulPlasticity.LOW,
    userRelationshipStyle = UserRelationshipStyle.COLLABORATIVE,
    riskTolerance = RiskTolerance.BALANCED,
    toolUseBias = ToolUseBias.BALANCED,
    escalationRules = listOf(
      "Reconfirm the operating style before high-impact actions when the preset is unfamiliar.",
    ),
    collaborationPreferences = listOf(
      "Preserve the custom profile while keeping runtime behavior testable.",
    ),
    forbiddenBehaviors = forbiddenBehaviors,
  )

  private fun applyExtensionOverrides(
    profile: SoulProfile,
    extensions: Map<String, String>,
  ): SoulProfile {
    val tone = extensions[SoulProfileExtensionKeys.TONE].parseEnumOrNull<SoulTone>() ?: profile.tone
    val verbosity = extensions[SoulProfileExtensionKeys.VERBOSITY].parseEnumOrNull<SoulVerbosity>() ?: profile.verbosity
    val plasticity =
      extensions[SoulProfileExtensionKeys.PLASTICITY].parseEnumOrNull<SoulPlasticity>() ?: profile.plasticity
    val relationshipStyle =
      extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE].parseEnumOrNull<UserRelationshipStyle>()
        ?: profile.userRelationshipStyle
    val riskTolerance =
      extensions[SoulProfileExtensionKeys.RISK_TOLERANCE].parseEnumOrNull<RiskTolerance>() ?: profile.riskTolerance
    val toolUseBias =
      extensions[SoulProfileExtensionKeys.TOOL_USE_BIAS].parseEnumOrNull<ToolUseBias>() ?: profile.toolUseBias
    val voice = normalizeSoulScalarOrNull(extensions[SoulProfileExtensionKeys.VOICE]) ?: profile.voice
    val preferredNaming =
      normalizeSoulScalarOrNull(extensions[SoulProfileExtensionKeys.PREFERRED_NAMING]) ?: profile.preferredNaming
    val preferredAddressStyle =
      extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE].parseEnumOrNull<PreferredAddressStyle>()
        ?: profile.preferredAddressStyle
    val intimacyPermissionBand =
      extensions[SoulProfileExtensionKeys.INTIMACY_PERMISSION_BAND].parseEnumOrNull<RelationshipBand>()
        ?: profile.intimacyPermissionBand
    val playfulnessPermissionBand =
      extensions[SoulProfileExtensionKeys.PLAYFULNESS_PERMISSION_BAND].parseEnumOrNull<RelationshipBand>()
        ?: profile.playfulnessPermissionBand
    val highIntimacyBehaviorAllowed =
      extensions[SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED].parseBooleanOrNull()
        ?: profile.highIntimacyBehaviorAllowed
    val playfulAffectionAllowed =
      extensions[SoulProfileExtensionKeys.PLAYFUL_AFFECTION_ALLOWED].parseBooleanOrNull()
        ?: profile.playfulAffectionAllowed
    val customGuidance =
      normalizeSoulScalarOrNull(extensions[SoulProfileExtensionKeys.CUSTOM_GUIDANCE]) ?: profile.customGuidance
    val escalationRules =
      mergeDistinct(profile.escalationRules, extensions[SoulProfileExtensionKeys.ESCALATION_RULES].parseList())
    val forbiddenBehaviors = mergeDistinct(
      profile.forbiddenBehaviors,
      extensions[SoulProfileExtensionKeys.FORBIDDEN_BEHAVIORS].parseList(),
    )
    val collaborationPreferences = mergeDistinct(
      profile.collaborationPreferences,
      extensions[SoulProfileExtensionKeys.COLLABORATION_PREFERENCES].parseList(),
    )

    return profile.copy(
      voice = voice,
      preferredNaming = preferredNaming,
      preferredAddressStyle = preferredAddressStyle,
      intimacyPermissionBand = intimacyPermissionBand,
      playfulnessPermissionBand = playfulnessPermissionBand,
      highIntimacyBehaviorAllowed = highIntimacyBehaviorAllowed,
      playfulAffectionAllowed = playfulAffectionAllowed,
      tone = tone,
      verbosity = verbosity,
      plasticity = plasticity,
      userRelationshipStyle = relationshipStyle,
      riskTolerance = riskTolerance,
      toolUseBias = toolUseBias,
      escalationRules = escalationRules,
      forbiddenBehaviors = forbiddenBehaviors,
      collaborationPreferences = collaborationPreferences,
      customGuidance = customGuidance,
    )
  }

  private fun forbiddenBehaviors(extensions: Map<String, String>): List<String> {
    val fromExtensions = extensions[SoulProfileExtensionKeys.FORBIDDEN_BEHAVIORS].parseList()
    return (DEFAULT_FORBIDDEN_BEHAVIORS + fromExtensions).distinct()
  }

  private fun String?.normalizedOrNull(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

  private fun String?.parseList(): List<String> = this
    ?.split('|', '\n')
    ?.mapNotNull(::normalizeSoulScalarOrNull)
    ?.distinct()
    .orEmpty()

  private inline fun <reified T : Enum<T>> String?.parseEnumOrNull(): T? {
    val normalized = normalizeSoulExtensionKeyOrNull(this)
      ?.uppercase()
      ?: return null
    return enumValues<T>().firstOrNull { value -> value.name == normalized }
  }

  private fun String?.parseBooleanOrNull(): Boolean? = when (normalizeSoulExtensionKeyOrNull(this)) {
    "true" -> true
    "false" -> false
    else -> null
  }

  private fun mergeDistinct(
    base: List<String>,
    additions: List<String>,
  ): List<String> = (base + additions)
    .mapNotNull(::normalizeSoulScalarOrNull)
    .distinct()

  private companion object {
    val DEFAULT_FORBIDDEN_BEHAVIORS: List<String> = listOf(
      "Do not fabricate workspace facts when a tool or local evidence is required.",
      "Do not hide uncertainty behind confident wording.",
    )
  }
}
