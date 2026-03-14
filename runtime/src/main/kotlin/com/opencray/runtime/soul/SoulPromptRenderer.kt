package com.opencray.runtime.soul

class SoulPromptRenderer {
  fun render(profile: SoulProfile?): String {
    if (profile == null || !profile.isMeaningful()) {
      return ""
    }

    return buildString {
      profile.displayName.normalizedScalarOrNull()?.let { value ->
        appendLine("display_name=$value")
      }
      profile.presetName.normalizedScalarOrNull()?.let { value ->
        appendLine("preset=$value")
      }
      profile.voice.normalizedScalarOrNull()?.let { value ->
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
      profile.customGuidance.normalizedScalarOrNull()?.let { value ->
        appendLine("custom_guidance=$value")
      }
    }.trim()
  }

  private fun StringBuilder.appendList(
    name: String,
    values: List<String>,
  ) {
    val normalized = values.mapNotNull { value -> value.normalizedScalarOrNull() }
    if (normalized.isEmpty()) {
      return
    }
    appendLine("$name:")
    normalized.forEach { value ->
      appendLine("- $value")
    }
  }

  private fun String?.normalizedScalarOrNull(): String? =
    this
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)
}
