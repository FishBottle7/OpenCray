package com.opencray.runtime.soul

object SoulProfileExtensionKeys {
  const val VOICE: String = "voice"
  const val PREFERRED_NAMING: String = "preferred_naming"
  const val PREFERRED_ADDRESS_STYLE: String = "preferred_address_style"
  const val WARMTH_PREFERENCE_OFFSET: String = "warmth_preference_offset"
  const val FORMALITY_PREFERENCE_OFFSET: String = "formality_preference_offset"
  const val INITIATIVE_PREFERENCE_OFFSET: String = "initiative_preference_offset"
  const val PLAYFULNESS_PREFERENCE_OFFSET: String = "playfulness_preference_offset"
  const val REASSURANCE_PREFERENCE_OFFSET: String = "reassurance_preference_offset"
  const val INTIMACY_PERMISSION_BAND: String = "intimacy_permission_band"
  const val PLAYFULNESS_PERMISSION_BAND: String = "playfulness_permission_band"
  const val SUPPORTIVE_REASSURANCE_ALLOWED: String = "supportive_reassurance_allowed"
  const val PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED: String = "proactive_relational_check_in_allowed"
  const val LIGHT_PLAYFULNESS_ALLOWED: String = "light_playfulness_allowed"
  const val PLAYFUL_TEASING_ALLOWED: String = "playful_teasing_allowed"
  const val HIGH_INTIMACY_BEHAVIOR_ALLOWED: String = "high_intimacy_behavior_allowed"
  const val PLAYFUL_AFFECTION_ALLOWED: String = "playful_affection_allowed"
  const val TONE: String = "tone"
  const val VERBOSITY: String = "verbosity"
  const val PLASTICITY: String = "plasticity"
  const val USER_RELATIONSHIP_STYLE: String = "user_relationship_style"
  const val RISK_TOLERANCE: String = "risk_tolerance"
  const val TOOL_USE_BIAS: String = "tool_use_bias"
  const val ESCALATION_RULES: String = "escalation_rules"
  const val FORBIDDEN_BEHAVIORS: String = "forbidden_behaviors"
  const val COLLABORATION_PREFERENCES: String = "collaboration_preferences"
  const val CUSTOM_GUIDANCE: String = "custom_guidance"
  const val VISUAL_IDENTITY_JSON: String = "visual_identity_json"
}

internal fun normalizeSoulExtensionKeyOrNull(raw: String?): String? =
  raw
    ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    ?.replace(Regex("[\\s\\-]+"), "_")
    ?.replace(Regex("_+"), "_")
    ?.trim('_')
    ?.lowercase()
    ?.takeIf(String::isNotEmpty)

internal fun normalizeSoulScalarOrNull(raw: String?): String? =
  raw
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

internal fun normalizeSoulExtensions(raw: Map<String, String>): Map<String, String> = buildMap {
  raw.forEach { (rawKey, rawValue) ->
    val key = normalizeSoulExtensionKeyOrNull(rawKey) ?: return@forEach
    val value = normalizeSoulExtensionValueOrNull(key, rawValue) ?: return@forEach
    put(key, value)
  }
}

private fun normalizeSoulExtensionValueOrNull(
  key: String,
  raw: String?,
): String? = when (key) {
  SoulProfileExtensionKeys.ESCALATION_RULES,
  SoulProfileExtensionKeys.FORBIDDEN_BEHAVIORS,
  SoulProfileExtensionKeys.COLLABORATION_PREFERENCES,
  -> raw
    ?.split('|', '\n')
    ?.mapNotNull(::normalizeSoulScalarOrNull)
    ?.takeIf(List<String>::isNotEmpty)
    ?.joinToString(separator = "\n")

  else -> normalizeSoulScalarOrNull(raw)
}
