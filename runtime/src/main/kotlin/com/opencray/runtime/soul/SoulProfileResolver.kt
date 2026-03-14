package com.opencray.runtime.soul

class SoulProfileResolver {
  fun resolve(seed: SoulProfileSeed?): SoulProfile? {
    if (seed == null) {
      return null
    }

    val normalizedPreset = seed.presetName.normalizedOrNull()?.uppercase()
    val normalizedDisplayName = seed.displayName.normalizedScalarOrNull()
    val normalizedGuidance = seed.customGuidance.normalizedScalarOrNull()
    if (
      normalizedPreset == null &&
      normalizedDisplayName == null &&
      normalizedGuidance == null &&
      seed.extensions.isEmpty()
    ) {
      return null
    }

    val resolved = when (normalizedPreset) {
      "BUILDER" -> builderPreset(seed)
      "WARM" -> warmPreset(seed)
      "STEADY", null -> steadyPreset(seed)
      else -> customPreset(seed, presetName = normalizedPreset)
    }
    val overridden = applyExtensionOverrides(resolved, seed.extensions)

    return overridden.takeIf(SoulProfile::isMeaningful)?.copy(
      presetName = normalizedPreset,
      displayName = normalizedDisplayName,
      voice = seed.extensions[Extensions.VOICE].normalizedScalarOrNull() ?: overridden.voice,
      customGuidance = normalizedGuidance ?: overridden.customGuidance,
    )
  }

  private fun steadyPreset(seed: SoulProfileSeed): SoulProfile = SoulProfile(
    presetName = "STEADY",
    tone = SoulTone.STEADY,
    verbosity = SoulVerbosity.BALANCED,
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
    forbiddenBehaviors = forbiddenBehaviors(seed),
  )

  private fun builderPreset(seed: SoulProfileSeed): SoulProfile = SoulProfile(
    presetName = "BUILDER",
    tone = SoulTone.BUILDER,
    verbosity = SoulVerbosity.TERSE,
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
    forbiddenBehaviors = forbiddenBehaviors(seed),
  )

  private fun warmPreset(seed: SoulProfileSeed): SoulProfile = SoulProfile(
    presetName = "WARM",
    tone = SoulTone.WARM,
    verbosity = SoulVerbosity.BALANCED,
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
    forbiddenBehaviors = forbiddenBehaviors(seed),
  )

  private fun customPreset(
    seed: SoulProfileSeed,
    presetName: String,
  ): SoulProfile = SoulProfile(
    presetName = presetName,
    tone = SoulTone.CUSTOM,
    verbosity = SoulVerbosity.BALANCED,
    userRelationshipStyle = UserRelationshipStyle.COLLABORATIVE,
    riskTolerance = RiskTolerance.BALANCED,
    toolUseBias = ToolUseBias.BALANCED,
    escalationRules = listOf(
      "Reconfirm the operating style before high-impact actions when the preset is unfamiliar.",
    ),
    collaborationPreferences = listOf(
      "Preserve the custom profile while keeping runtime behavior testable.",
    ),
    forbiddenBehaviors = forbiddenBehaviors(seed),
  )

  private fun applyExtensionOverrides(
    profile: SoulProfile,
    extensions: Map<String, String>,
  ): SoulProfile {
    val tone = extensions[Extensions.TONE].parseEnumOrNull<SoulTone>() ?: profile.tone
    val verbosity = extensions[Extensions.VERBOSITY].parseEnumOrNull<SoulVerbosity>() ?: profile.verbosity
    val relationshipStyle =
      extensions[Extensions.USER_RELATIONSHIP_STYLE].parseEnumOrNull<UserRelationshipStyle>()
        ?: profile.userRelationshipStyle
    val riskTolerance = extensions[Extensions.RISK_TOLERANCE].parseEnumOrNull<RiskTolerance>() ?: profile.riskTolerance
    val toolUseBias = extensions[Extensions.TOOL_USE_BIAS].parseEnumOrNull<ToolUseBias>() ?: profile.toolUseBias
    val voice = extensions[Extensions.VOICE].normalizedScalarOrNull() ?: profile.voice
    val customGuidance = extensions[Extensions.CUSTOM_GUIDANCE].normalizedScalarOrNull() ?: profile.customGuidance
    val escalationRules = mergeDistinct(profile.escalationRules, extensions[Extensions.ESCALATION_RULES].parseList())
    val forbiddenBehaviors = mergeDistinct(
      profile.forbiddenBehaviors,
      extensions[Extensions.FORBIDDEN_BEHAVIORS].parseList(),
    )
    val collaborationPreferences = mergeDistinct(
      profile.collaborationPreferences,
      extensions[Extensions.COLLABORATION_PREFERENCES].parseList(),
    )

    return profile.copy(
      voice = voice,
      tone = tone,
      verbosity = verbosity,
      userRelationshipStyle = relationshipStyle,
      riskTolerance = riskTolerance,
      toolUseBias = toolUseBias,
      escalationRules = escalationRules,
      forbiddenBehaviors = forbiddenBehaviors,
      collaborationPreferences = collaborationPreferences,
      customGuidance = customGuidance,
    )
  }

  private fun forbiddenBehaviors(seed: SoulProfileSeed): List<String> {
    val fromExtensions = seed.extensions[Extensions.FORBIDDEN_BEHAVIORS].parseList()
    return (DEFAULT_FORBIDDEN_BEHAVIORS + fromExtensions).distinct()
  }

  private fun String?.normalizedOrNull(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

  private fun String?.normalizedScalarOrNull(): String? =
    this
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  private fun String?.parseList(): List<String> = this
    ?.split('|', '\n')
    ?.mapNotNull { value -> value.normalizedScalarOrNull() }
    ?.distinct()
    .orEmpty()

  private inline fun <reified T : Enum<T>> String?.parseEnumOrNull(): T? {
    val normalized = this
      ?.replace('-', '_')
      ?.replace(' ', '_')
      ?.trim()
      ?.uppercase()
      ?.takeIf(String::isNotEmpty)
      ?: return null
    return enumValues<T>().firstOrNull { value -> value.name == normalized }
  }

  private fun mergeDistinct(
    base: List<String>,
    additions: List<String>,
  ): List<String> = (base + additions)
    .mapNotNull { value -> value.normalizedScalarOrNull() }
    .distinct()

  private companion object {
    object Extensions {
      const val VOICE: String = "voice"
      const val TONE: String = "tone"
      const val VERBOSITY: String = "verbosity"
      const val USER_RELATIONSHIP_STYLE: String = "user_relationship_style"
      const val RISK_TOLERANCE: String = "risk_tolerance"
      const val TOOL_USE_BIAS: String = "tool_use_bias"
      const val ESCALATION_RULES: String = "escalation_rules"
      const val FORBIDDEN_BEHAVIORS: String = "forbidden_behaviors"
      const val COLLABORATION_PREFERENCES: String = "collaboration_preferences"
      const val CUSTOM_GUIDANCE: String = "custom_guidance"
    }

    val DEFAULT_FORBIDDEN_BEHAVIORS: List<String> = listOf(
      "Do not fabricate workspace facts when a tool or local evidence is required.",
      "Do not hide uncertainty behind confident wording.",
    )
  }
}
