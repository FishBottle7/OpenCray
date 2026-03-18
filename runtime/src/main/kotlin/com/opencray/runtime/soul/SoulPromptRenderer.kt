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
      normalizeSoulScalarOrNull(profile.preferredNaming)?.let { value ->
        appendLine("preferred_naming=$value")
      }
      profile.preferredAddressStyle?.let { style ->
        appendLine("preferred_address_style=${style.name.lowercase()}")
      }
      profile.intimacyPermissionBand?.let { band ->
        appendLine("intimacy_permission_band=${band.name.lowercase()}")
      }
      profile.playfulnessPermissionBand?.let { band ->
        appendLine("playfulness_permission_band=${band.name.lowercase()}")
      }
      profile.highIntimacyBehaviorAllowed?.let { allowed ->
        appendLine("high_intimacy_behavior_allowed=$allowed")
      }
      profile.playfulAffectionAllowed?.let { allowed ->
        appendLine("playful_affection_allowed=$allowed")
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
