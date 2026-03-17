package com.opencray.runtime.memory

import java.util.Locale

object MemoryPreferenceKeys {
  const val AGENT_DISPLAY_NAME: String = "agent_display_name"
  const val AGENT_STYLE_PROFILE: String = "agent_style_profile"
  const val RELATIONSHIP_STYLE_PROFILE: String = "relationship_style_profile"
  const val AGENT_VERBOSITY: String = "agent_verbosity"

  const val TEMPORALITY_SESSION: String = "session"
  const val TEMPORALITY_DURABLE: String = "durable"
}

object MemorySoulExtensionKeys {
  const val DISPLAY_NAME: String = "soul_display_name"
  const val VOICE: String = "soul_voice"
  const val TONE: String = "soul_tone"
  const val VERBOSITY: String = "soul_verbosity"
  const val USER_RELATIONSHIP_STYLE: String = "soul_user_relationship_style"
  const val RISK_TOLERANCE: String = "soul_risk_tolerance"
  const val TOOL_USE_BIAS: String = "soul_tool_use_bias"
}

internal fun supportedSoulPreferenceKeys(): Set<String> = setOf(
  MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
  MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
  MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
  MemoryPreferenceKeys.AGENT_VERBOSITY,
)

internal fun directChatSoulPreferenceScope(
  preferenceKey: String,
  requestedScope: MemoryScope,
): MemoryScope = when (normalizeMemoryPreferenceKeyOrNull(preferenceKey)) {
  MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> requestedScope
  MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
  MemoryPreferenceKeys.AGENT_VERBOSITY,
  -> MemoryScope.SESSION

  else -> requestedScope
}

internal fun shouldApplyDirectChatSoulPreference(
  preferenceKey: String,
  scope: MemoryScope,
): Boolean = when (normalizeMemoryPreferenceKeyOrNull(preferenceKey)) {
  MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> true
  MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
  MemoryPreferenceKeys.AGENT_VERBOSITY,
  -> scope == MemoryScope.SESSION

  MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE -> false

  else -> false
}

internal fun allowedSoulMemoryExtensionKeys(
  preferenceKey: String,
  scope: MemoryScope,
): Set<String> = when (normalizeMemoryPreferenceKeyOrNull(preferenceKey)) {
  MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> setOf(
    MemorySoulExtensionKeys.DISPLAY_NAME,
  )

  MemoryPreferenceKeys.AGENT_STYLE_PROFILE -> {
    if (!shouldApplyDirectChatSoulPreference(preferenceKey, scope)) {
      emptySet()
    } else {
      setOf(
        MemorySoulExtensionKeys.VOICE,
        MemorySoulExtensionKeys.TONE,
        MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE,
      )
    }
  }

  MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE -> setOf(
    MemorySoulExtensionKeys.VOICE,
    MemorySoulExtensionKeys.TONE,
    MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE,
  )

  MemoryPreferenceKeys.AGENT_VERBOSITY -> {
    if (!shouldApplyDirectChatSoulPreference(preferenceKey, scope)) {
      emptySet()
    } else {
      setOf(
        MemorySoulExtensionKeys.VERBOSITY,
      )
    }
  }

  else -> emptySet()
}

internal fun buildSoulPreferenceExtensions(
  preferenceKey: String,
  preferenceValue: String,
  scope: MemoryScope,
  soulExtensions: Map<String, String> = emptyMap(),
): Map<String, String> {
  val normalizedKey = normalizeMemoryPreferenceKeyOrNull(preferenceKey) ?: return emptyMap()
  val normalizedValue = normalizeMemoryPreferenceValueOrNull(preferenceValue) ?: return emptyMap()
  val effectiveScope = directChatSoulPreferenceScope(
    preferenceKey = normalizedKey,
    requestedScope = scope,
  )
  val baseExtensions = when (normalizedKey) {
    MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> displayNamePreferenceExtensions(
      displayName = normalizedValue,
      scope = effectiveScope,
    )

    MemoryPreferenceKeys.AGENT_STYLE_PROFILE -> styleProfilePreferenceExtensions(
      styleProfile = normalizedValue,
      scope = effectiveScope,
    )

    MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE -> relationshipStylePreferenceExtensions(
      styleProfile = normalizedValue,
      scope = effectiveScope,
    )

    MemoryPreferenceKeys.AGENT_VERBOSITY -> verbosityPreferenceExtensions(
      verbosity = normalizedValue,
      scope = effectiveScope,
    )

    else -> return emptyMap()
  }
  return linkedMapOf<String, String>().apply {
    putAll(baseExtensions)
    putAll(
      normalizeSoulMemoryExtensions(
        raw = soulExtensions,
        allowedKeys = allowedSoulMemoryExtensionKeys(
          preferenceKey = normalizedKey,
          scope = effectiveScope,
        ),
      ),
    )
  }
}

internal fun displayNamePreferenceExtensions(
  displayName: String,
  scope: MemoryScope,
): Map<String, String> = linkedMapOf(
  MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
  MemoryRecordExtensionKeys.PREFERENCE_VALUE to displayName,
  MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityFor(scope),
  MemorySoulExtensionKeys.DISPLAY_NAME to displayName,
)

internal fun styleProfilePreferenceExtensions(
  styleProfile: String,
  scope: MemoryScope,
): Map<String, String> = linkedMapOf(
  MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
  MemoryRecordExtensionKeys.PREFERENCE_VALUE to styleProfile,
  MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityFor(scope),
).apply {
  when (styleProfile.lowercase(Locale.US)) {
    "warm" -> {
      put(MemorySoulExtensionKeys.TONE, "warm")
      put(MemorySoulExtensionKeys.VOICE, "warm and gentle")
      put(MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE, "supportive")
    }

    "serious" -> {
      put(MemorySoulExtensionKeys.TONE, "steady")
      put(MemorySoulExtensionKeys.VOICE, "serious and formal")
      put(MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE, "direct")
    }
  }
}

internal fun relationshipStylePreferenceExtensions(
  styleProfile: String,
  scope: MemoryScope,
): Map<String, String> = linkedMapOf(
  MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
  MemoryRecordExtensionKeys.PREFERENCE_VALUE to styleProfile,
  MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityFor(scope),
).apply {
  when (styleProfile.lowercase(Locale.US)) {
    "warm" -> {
      put(MemorySoulExtensionKeys.TONE, "warm")
      put(MemorySoulExtensionKeys.VOICE, "warm and gentle")
      put(MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE, "supportive")
    }

    "serious" -> {
      put(MemorySoulExtensionKeys.TONE, "steady")
      put(MemorySoulExtensionKeys.VOICE, "serious and formal")
      put(MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE, "direct")
    }
  }
}

internal fun verbosityPreferenceExtensions(
  verbosity: String,
  scope: MemoryScope,
): Map<String, String> = linkedMapOf(
  MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_VERBOSITY,
  MemoryRecordExtensionKeys.PREFERENCE_VALUE to verbosity,
  MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to preferenceTemporalityFor(scope),
).apply {
  when (verbosity.lowercase(Locale.US)) {
    "terse" -> put(MemorySoulExtensionKeys.VERBOSITY, "terse")
    "expansive" -> put(MemorySoulExtensionKeys.VERBOSITY, "expansive")
  }
}

internal fun preferenceTemporalityFor(scope: MemoryScope): String = when (scope) {
  MemoryScope.SESSION -> MemoryPreferenceKeys.TEMPORALITY_SESSION
  MemoryScope.USER,
  MemoryScope.WORKSPACE,
  -> MemoryPreferenceKeys.TEMPORALITY_DURABLE
}

internal fun normalizeSoulMemoryExtensions(
  raw: Map<String, String>,
  allowedKeys: Set<String>,
): Map<String, String> = buildMap {
  raw.forEach { (key, value) ->
    val normalizedKey = normalizeMemoryPreferenceKeyOrNull(key) ?: return@forEach
    if (normalizedKey !in allowedKeys) {
      return@forEach
    }
    val normalizedValue = normalizeSoulMemoryExtensionValueOrNull(
      key = normalizedKey,
      raw = value,
    ) ?: return@forEach
    put(normalizedKey, normalizedValue)
  }
}

internal fun preferenceSupportsSupersession(
  preferenceKey: String,
): Boolean = when (normalizeMemoryPreferenceKeyOrNull(preferenceKey)) {
  MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE -> false
  else -> true
}

private fun normalizeSoulMemoryExtensionValueOrNull(
  key: String,
  raw: String?,
): String? = when (key) {
  MemorySoulExtensionKeys.DISPLAY_NAME,
  MemorySoulExtensionKeys.VOICE,
  -> normalizeMemoryPreferenceValueOrNull(raw)

  MemorySoulExtensionKeys.TONE,
  MemorySoulExtensionKeys.VERBOSITY,
  MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE,
  MemorySoulExtensionKeys.RISK_TOLERANCE,
  MemorySoulExtensionKeys.TOOL_USE_BIAS,
  -> normalizeMemoryPreferenceKeyOrNull(raw)

  else -> null
}

internal fun normalizeMemoryPreferenceKeyOrNull(raw: String?): String? =
  raw
    ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    ?.replace(Regex("[\\s\\-]+"), "_")
    ?.replace(Regex("_+"), "_")
    ?.trim('_')
    ?.lowercase(Locale.US)
    ?.takeIf(String::isNotEmpty)

internal fun normalizeMemoryPreferenceValueOrNull(raw: String?): String? =
  raw
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.trim('"', '\'', '`', '“', '”', '‘', '’')
    ?.trim('.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？')
    ?.takeIf(String::isNotEmpty)
