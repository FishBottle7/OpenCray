package com.opencray.runtime.soul

object SoulProfileExtensionKeys {
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
