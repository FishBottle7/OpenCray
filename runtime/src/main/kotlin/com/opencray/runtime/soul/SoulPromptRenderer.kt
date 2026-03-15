package com.opencray.runtime.soul

class SoulPromptRenderer {
  fun render(profile: SoulProfile?): String {
    if (profile == null || !profile.isMeaningful()) {
      return ""
    }

    return buildString {
      normalizeSoulScalarOrNull(profile.displayName)?.let { value ->
        appendLine("display_name=$value")
      }
      normalizeSoulScalarOrNull(profile.presetName)?.let { value ->
        appendLine("preset=$value")
      }
      normalizeSoulScalarOrNull(profile.voice)?.let { value ->
        appendLine("voice=$value")
      }
      appendLine("tone=${profile.tone.name.lowercase()}")
      appendLine("verbosity=${profile.verbosity.name.lowercase()}")
      appendLine("user_relationship_style=${profile.userRelationshipStyle.name.lowercase()}")
      appendLine("risk_tolerance=${profile.riskTolerance.name.lowercase()}")
      appendLine("tool_use_bias=${profile.toolUseBias.name.lowercase()}")
      appendList("escalation_rules", profile.escalationRules)
      appendList("forbidden_behaviors", profile.forbiddenBehaviors)
      appendList("collaboration_preferences", profile.collaborationPreferences)
      normalizeSoulScalarOrNull(profile.customGuidance)?.let { value ->
        appendLine("custom_guidance=$value")
      }
    }.trim()
  }

  private fun StringBuilder.appendList(
    name: String,
    values: List<String>,
  ) {
    val normalized = values.mapNotNull(::normalizeSoulScalarOrNull)
    if (normalized.isEmpty()) {
      return
    }
    appendLine("$name:")
    normalized.forEach { value ->
      appendLine("- $value")
    }
  }
}
