package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.parseMemoryMetadata

class MemoryBackedSoulProfileResolver {
  fun overlay(
    baseProfile: RuntimeSoulProfile?,
    records: List<MemoryRecord>,
    sessionId: String,
    workspaceId: String? = null,
  ): RuntimeSoulProfile? {
    val applicablePreferences = records
      .mapNotNull { record ->
        val metadata = record.parseMemoryMetadata() ?: return@mapNotNull null
        if (metadata.status != MemoryStatus.ACTIVE) {
          return@mapNotNull null
        }
        val preferenceKey = metadata.preferenceKey ?: return@mapNotNull null
        val preferenceValue = metadata.preferenceValue ?: return@mapNotNull null
        if (!scopeMatches(metadata.scope, metadata.sourceSessionId, metadata.workspaceId, sessionId, workspaceId)) {
          return@mapNotNull null
        }
        ApplicableSoulPreference(
          key = preferenceKey,
          value = preferenceValue,
          extensions = record.extensions,
          scope = metadata.scope,
          confirmedAtEpochMs = metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs,
          recordId = record.id,
        )
      }

    if (baseProfile == null && applicablePreferences.isEmpty()) {
      return null
    }

    val overlayState = MutableSoulOverlay(
      displayName = baseProfile?.displayName,
      voice = baseProfile?.voice,
      extensions = baseProfile?.extensions.orEmpty().toMutableMap(),
    )
    applicablePreferences
      .sortedWith(
        compareBy<ApplicableSoulPreference> { preference ->
          preference.scopePriority
        }.thenBy { preference ->
          preference.confirmedAtEpochMs
        }.thenBy { preference ->
          preference.recordId
        },
      )
      .forEach { preference ->
        applyPreference(preference = preference, overlayState = overlayState)
      }

    return RuntimeSoulProfile(
      presetName = baseProfile?.presetName,
      displayName = overlayState.displayName,
      voice = overlayState.voice,
      customGuidance = baseProfile?.customGuidance,
      extensions = overlayState.extensions.toMap(),
    ).takeIf { profile ->
      profile.presetName != null ||
        profile.displayName != null ||
        profile.voice != null ||
        profile.customGuidance != null ||
        profile.extensions.isNotEmpty()
    }
  }

  private fun applyPreference(
    preference: ApplicableSoulPreference,
    overlayState: MutableSoulOverlay,
  ) {
    val hasTypedDisplayName = applyScalarOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.DISPLAY_NAME],
    ) { normalized ->
      overlayState.displayName = normalized
    }
    val hasTypedVoice = applyScalarOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.VOICE],
    ) { normalized ->
      overlayState.voice = normalized
    }
    val hasTypedTone = applyEnumLikeOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.TONE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.TONE,
    )
    val hasTypedVerbosity = applyEnumLikeOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.VERBOSITY],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.VERBOSITY,
    )
    applyEnumLikeOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE,
    )
    applyEnumLikeOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.RISK_TOLERANCE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.RISK_TOLERANCE,
    )
    applyEnumLikeOverlay(
      raw = preference.extensions[MemorySoulExtensionKeys.TOOL_USE_BIAS],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.TOOL_USE_BIAS,
    )

    when (preference.key) {
      MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> {
        if (!hasTypedDisplayName) {
          overlayState.displayName = preference.value
        }
      }

      MemoryPreferenceKeys.AGENT_STYLE_PROFILE -> {
        if (!hasTypedTone) {
          when (preference.value.lowercase()) {
            "warm" -> overlayState.extensions[SoulProfileExtensionKeys.TONE] = "warm"
            "serious" -> overlayState.extensions[SoulProfileExtensionKeys.TONE] = "steady"
          }
        }
        if (!hasTypedVoice) {
          overlayState.voice = when (preference.value.lowercase()) {
            "warm" -> "warm and gentle"
            "serious" -> "serious and formal"
            else -> overlayState.voice
          }
        }
      }

      MemoryPreferenceKeys.AGENT_VERBOSITY -> {
        if (!hasTypedVerbosity) {
          val normalizedVerbosity = normalizeExtensionKeyOrNull(preference.value)
          when (normalizedVerbosity) {
            "terse",
            "balanced",
            "expansive",
            -> overlayState.extensions[SoulProfileExtensionKeys.VERBOSITY] = normalizedVerbosity
          }
        }
      }
    }
  }

  private fun applyScalarOverlay(
    raw: String?,
    apply: (String) -> Unit,
  ): Boolean {
    val normalized = normalizeScalarOrNull(raw) ?: return false
    apply(normalized)
    return true
  }

  private fun applyEnumLikeOverlay(
    raw: String?,
    extensions: MutableMap<String, String>,
    soulKey: String,
  ): Boolean {
    val normalized = normalizeExtensionKeyOrNull(raw) ?: return false
    extensions[soulKey] = normalized
    return true
  }

  private fun normalizeScalarOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  private fun normalizeExtensionKeyOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
      ?.replace(Regex("[\\s\\-]+"), "_")
      ?.replace(Regex("_+"), "_")
      ?.trim('_')
      ?.lowercase()
      ?.takeIf(String::isNotEmpty)

  private fun scopeMatches(
    scope: MemoryScope,
    sourceSessionId: String?,
    recordWorkspaceId: String?,
    requestSessionId: String,
    requestWorkspaceId: String?,
  ): Boolean = when (scope) {
    MemoryScope.USER -> true
    MemoryScope.SESSION -> sourceSessionId == requestSessionId
    MemoryScope.WORKSPACE -> {
      val normalizedRecordWorkspaceId = recordWorkspaceId?.takeIf(String::isNotBlank)
      val normalizedRequestWorkspaceId = requestWorkspaceId?.takeIf(String::isNotBlank)
      when {
        normalizedRecordWorkspaceId == null && normalizedRequestWorkspaceId == null -> true
        normalizedRecordWorkspaceId != null && normalizedRequestWorkspaceId != null ->
          normalizedRecordWorkspaceId == normalizedRequestWorkspaceId
        else -> false
      }
    }
  }

  private data class ApplicableSoulPreference(
    val key: String,
    val value: String,
    val extensions: Map<String, String>,
    val scope: MemoryScope,
    val confirmedAtEpochMs: Long,
    val recordId: String,
  ) {
    val scopePriority: Int
      get() = when (scope) {
        MemoryScope.WORKSPACE -> 1
        MemoryScope.USER -> 2
        MemoryScope.SESSION -> 3
      }
  }

  private data class MutableSoulOverlay(
    var displayName: String?,
    var voice: String?,
    val extensions: MutableMap<String, String>,
  )
}
