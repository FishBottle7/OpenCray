package com.opencray.app

import com.opencray.runtime.subagent.BuiltInSubAgentProfiles
import com.opencray.runtime.subagent.SubAgentContextMode
import com.opencray.runtime.subagent.SubAgentContextPolicy
import java.util.Locale

internal object PublicSubAgentContextPolicySettings {
  private val publicModesByWireValue: Map<String, SubAgentContextMode> =
    SubAgentContextMode.publicModes().associateBy { mode -> mode.wireValue }

  fun publicModeFromWireValue(value: String?): SubAgentContextMode? {
    val normalized = value
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank)
      ?: return null
    return publicModesByWireValue[normalized]
  }

  fun normalizeProfileId(profileId: String?): String? =
    BuiltInSubAgentProfiles.resolve(profileId)?.id

  fun sanitizeOverrides(
    overrides: Map<String, SubAgentContextMode>,
  ): Map<String, SubAgentContextMode> {
    if (overrides.isEmpty()) {
      return emptyMap()
    }
    val sanitized = linkedMapOf<String, SubAgentContextMode>()
    overrides.forEach { (profileId, mode) ->
      val normalizedProfileId = normalizeProfileId(profileId) ?: return@forEach
      if (!mode.publicControlPlaneEnabled) {
        return@forEach
      }
      sanitized[normalizedProfileId] = mode
    }
    return sanitized
  }

  fun parseOverridesFromWireMap(raw: Map<String, String>): Map<String, SubAgentContextMode> {
    if (raw.isEmpty()) {
      return emptyMap()
    }
    val parsed = linkedMapOf<String, SubAgentContextMode>()
    raw.forEach { (profileId, modeId) ->
      val normalizedProfileId = normalizeProfileId(profileId) ?: return@forEach
      val mode = publicModeFromWireValue(modeId) ?: return@forEach
      parsed[normalizedProfileId] = mode
    }
    return parsed
  }

  fun encodeOverridesToPreferenceValue(overrides: Map<String, SubAgentContextMode>): String =
    sanitizeOverrides(overrides)
      .entries
      .sortedBy { entry -> entry.key }
      .joinToString(separator = ";") { entry -> "${entry.key}=${entry.value.wireValue}" }

  fun decodeOverridesFromPreferenceValue(value: String?): Map<String, SubAgentContextMode> {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) {
      return emptyMap()
    }
    val parsed = linkedMapOf<String, SubAgentContextMode>()
    normalized.split(';').forEach { token ->
      val separator = token.indexOf('=')
      if (separator <= 0 || separator == token.lastIndex) {
        return@forEach
      }
      val profileId = token.substring(0, separator)
      val modeId = token.substring(separator + 1)
      val normalizedProfileId = normalizeProfileId(profileId) ?: return@forEach
      val mode = publicModeFromWireValue(modeId) ?: return@forEach
      parsed[normalizedProfileId] = mode
    }
    return parsed
  }

  fun toWireMap(overrides: Map<String, SubAgentContextMode>): Map<String, String> =
    sanitizeOverrides(overrides)
      .entries
      .sortedBy { entry -> entry.key }
      .associate { entry -> entry.key to entry.value.wireValue }
}

internal fun SafetySettingsState.toRuntimeSubAgentContextPolicy(): SubAgentContextPolicy =
  SubAgentContextPolicy(
    defaultContextMode = subAgentContextDefaultMode,
    profileOverrides = subAgentContextProfileOverrides,
  )
