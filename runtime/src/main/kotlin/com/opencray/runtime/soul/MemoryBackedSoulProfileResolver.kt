package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.allowedSoulMemoryExtensionKeys
import com.opencray.runtime.memory.normalizeSoulMemoryExtensions
import com.opencray.runtime.memory.parseMemoryMetadata
import com.opencray.runtime.memory.shouldApplyDirectChatSoulPreference

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
          recordVersion = record.recordVersion,
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
    applyRelationshipEvolution(
      preferences = applicablePreferences,
      baseProfile = baseProfile,
      overlayState = overlayState,
    )
    applicablePreferences
      .filterNot { preference ->
        preference.key == MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE
      }
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
    if (!shouldApplyDirectChatSoulPreference(preference.key, preference.scope)) {
      return
    }
    val filteredExtensions = normalizeSoulMemoryExtensions(
      raw = preference.extensions,
      allowedKeys = allowedSoulMemoryExtensionKeys(
        preferenceKey = preference.key,
        scope = preference.scope,
      ),
    )
    val hasTypedDisplayName = applyScalarOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.DISPLAY_NAME],
    ) { normalized ->
      overlayState.displayName = normalized
    }
    val hasTypedVoice = applyScalarOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.VOICE],
    ) { normalized ->
      overlayState.voice = normalized
    }
    val hasTypedTone = applyEnumLikeOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.TONE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.TONE,
    )
    val hasTypedVerbosity = applyEnumLikeOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.VERBOSITY],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.VERBOSITY,
    )
    applyEnumLikeOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE,
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

  private fun applyRelationshipEvolution(
    preferences: List<ApplicableSoulPreference>,
    baseProfile: RuntimeSoulProfile?,
    overlayState: MutableSoulOverlay,
  ) {
    val plasticity = resolvePlasticity(
      raw = baseProfile?.extensions?.get(SoulProfileExtensionKeys.PLASTICITY),
    )
    val candidate = selectRelationshipStylePreference(
      preferences = preferences,
      plasticity = plasticity,
    ) ?: return
    val filteredExtensions = normalizeSoulMemoryExtensions(
      raw = candidate.extensions,
      allowedKeys = allowedSoulMemoryExtensionKeys(
        preferenceKey = candidate.key,
        scope = candidate.scope,
      ),
    )
    val hasTypedVoice = applyScalarOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.VOICE],
    ) { normalized ->
      overlayState.voice = normalized
    }
    val hasTypedTone = applyEnumLikeOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.TONE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.TONE,
    )
    val hasTypedRelationshipStyle = applyEnumLikeOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE,
    )

    when (candidate.value.lowercase()) {
      "warm" -> {
        if (!hasTypedTone) {
          overlayState.extensions[SoulProfileExtensionKeys.TONE] = "warm"
        }
        if (!hasTypedVoice) {
          overlayState.voice = "warm and gentle"
        }
        if (!hasTypedRelationshipStyle) {
          overlayState.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE] = "supportive"
        }
      }

      "serious" -> {
        if (!hasTypedTone) {
          overlayState.extensions[SoulProfileExtensionKeys.TONE] = "steady"
        }
        if (!hasTypedVoice) {
          overlayState.voice = "serious and formal"
        }
        if (!hasTypedRelationshipStyle) {
          overlayState.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE] = "direct"
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

  private fun selectRelationshipStylePreference(
    preferences: List<ApplicableSoulPreference>,
    plasticity: SoulPlasticity,
  ): ApplicableSoulPreference? {
    val relationshipSignals = preferences
      .filter { preference ->
        preference.key == MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE &&
          preference.scope != MemoryScope.SESSION
      }
    if (relationshipSignals.isEmpty()) {
      return null
    }
    val groupedSignals = relationshipSignals
      .groupBy { preference -> preference.value }
      .mapValues { (_, valuePreferences) ->
        RelationshipSignalAggregate(
          support = valuePreferences.sumOf { preference -> preference.signalWeight },
          latestConfirmedAtEpochMs = valuePreferences.maxOf { preference -> preference.confirmedAtEpochMs },
          representative = valuePreferences.maxWithOrNull(
            compareBy<ApplicableSoulPreference> { preference -> preference.confirmedAtEpochMs }
              .thenBy { preference -> preference.recordId },
          )!!,
        )
      }
      .values
      .sortedWith(
        compareByDescending<RelationshipSignalAggregate> { aggregate -> aggregate.support }
          .thenByDescending { aggregate -> aggregate.latestConfirmedAtEpochMs }
          .thenBy { aggregate -> aggregate.representative.recordId },
      )
    val winner = groupedSignals.firstOrNull() ?: return null
    val runnerUpSupport = groupedSignals.getOrNull(1)?.support ?: 0L
    val policy = plasticity.policy()
    if (winner.support < policy.minEvidence) {
      return null
    }
    if (winner.support - runnerUpSupport < policy.minLead) {
      return null
    }
    return winner.representative
  }

  private fun resolvePlasticity(raw: String?): SoulPlasticity = when (normalizeExtensionKeyOrNull(raw)) {
    "high" -> SoulPlasticity.HIGH
    "medium" -> SoulPlasticity.MEDIUM
    else -> SoulPlasticity.LOW
  }

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
    val recordVersion: Long,
    val recordId: String,
  ) {
    val signalWeight: Long
      get() = recordVersion.coerceAtLeast(1L)

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

  private data class RelationshipSignalAggregate(
    val support: Long,
    val latestConfirmedAtEpochMs: Long,
    val representative: ApplicableSoulPreference,
  )

  private data class SoulPlasticityPolicy(
    val minEvidence: Long,
    val minLead: Long,
  )

  private fun SoulPlasticity.policy(): SoulPlasticityPolicy = when (this) {
    SoulPlasticity.LOW -> SoulPlasticityPolicy(
      minEvidence = 3L,
      minLead = 2L,
    )

    SoulPlasticity.MEDIUM -> SoulPlasticityPolicy(
      minEvidence = 2L,
      minLead = 1L,
    )

    SoulPlasticity.HIGH -> SoulPlasticityPolicy(
      minEvidence = 1L,
      minLead = 1L,
    )
  }
}
