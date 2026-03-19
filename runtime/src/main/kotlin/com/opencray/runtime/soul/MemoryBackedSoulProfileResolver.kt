package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.allowedSoulMemoryExtensionKeys
import com.opencray.runtime.memory.normalizePreferredAddressStyleValueOrNull
import com.opencray.runtime.memory.normalizeSoulMemoryExtensions
import com.opencray.runtime.memory.parseMemoryMetadata
import com.opencray.runtime.memory.shouldApplyDirectChatSoulPreference

data class SoulOverlayDebugInfo(
  val effectiveProfile: RuntimeSoulProfile?,
  val interactionPreferenceDebug: InteractionPreferenceDebugProjection? = null,
  val relationshipStateDebug: RelationshipStateDebugProjection? = null,
)

data class InteractionPreferenceDebugProjection(
  val sourceScope: MemoryScope,
  val snapshotRecordId: String?,
  val state: InteractionPreferenceState,
  val preferredNaming: String? = null,
  val preferredAddressStyle: PreferredAddressStyle? = null,
  val derivedRelationshipStyle: String? = null,
)

data class SoulGateCheck(
  val key: String,
  val passed: Boolean,
  val currentValue: Int? = null,
  val threshold: Int? = null,
  val actualBoolean: Boolean? = null,
  val expectedBoolean: Boolean? = null,
)

data class RelationshipStateDebugProjection(
  val sourceScope: MemoryScope,
  val snapshotRecordId: String?,
  val appliedEventRecordIds: List<String>,
  val state: RelationshipState,
  val recentNegativeGuardActive: Boolean,
  val supportiveStyleUnlocked: Boolean,
  val supportiveStyleChecks: List<SoulGateCheck>,
  val warmToneUnlocked: Boolean,
  val warmToneChecks: List<SoulGateCheck>,
  val derivedAddressStyle: PreferredAddressStyle? = null,
  val friendlyAddressChecks: List<SoulGateCheck>,
  val intimateAddressChecks: List<SoulGateCheck>,
  val intimacyPermissionBand: RelationshipBand,
  val playfulnessPermissionBand: RelationshipBand,
  val supportiveReassuranceAllowed: Boolean,
  val supportiveReassuranceChecks: List<SoulGateCheck>,
  val proactiveRelationalCheckInAllowed: Boolean,
  val proactiveRelationalCheckInChecks: List<SoulGateCheck>,
  val lightPlayfulnessAllowed: Boolean,
  val lightPlayfulnessChecks: List<SoulGateCheck>,
  val playfulTeasingAllowed: Boolean,
  val playfulTeasingChecks: List<SoulGateCheck>,
  val highIntimacyBehaviorAllowed: Boolean,
  val highIntimacyChecks: List<SoulGateCheck>,
  val playfulAffectionAllowed: Boolean,
  val playfulAffectionChecks: List<SoulGateCheck>,
)

class MemoryBackedSoulProfileResolver(
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val interactionPreferenceStateProjector: InteractionPreferenceStateProjector =
    InteractionPreferenceStateProjector(clock = clock)
  private val relationshipStateProjector: RelationshipStateProjector =
    RelationshipStateProjector(clock = clock)

  fun inspectOverlay(
    baseProfile: RuntimeSoulProfile?,
    records: List<MemoryRecord>,
    sessionId: String,
    workspaceId: String? = null,
  ): SoulOverlayDebugInfo {
    val effectiveProfile = overlay(
      baseProfile = baseProfile,
      records = records,
      sessionId = sessionId,
      workspaceId = workspaceId,
    )
    val plasticity = resolvePlasticity(
      raw = baseProfile?.extensions?.get(SoulProfileExtensionKeys.PLASTICITY),
    )
    val interactionPreferenceProjection = resolveEffectiveInteractionPreferenceProjection(
      records = records,
      workspaceId = workspaceId,
    )
    val relationshipStateProjection = resolveEffectiveRelationshipStateProjection(
      records = records,
      plasticity = plasticity,
      workspaceId = workspaceId,
    )
    val interactionPreferenceDebug = interactionPreferenceProjection?.let(::buildInteractionPreferenceDebugProjection)
    val relationshipStateDebug = relationshipStateProjection?.let { projection ->
      buildRelationshipStateDebugProjection(
        projection = projection,
        interactionPreferenceState = interactionPreferenceProjection?.projection?.state,
      )
    }
    return SoulOverlayDebugInfo(
      effectiveProfile = effectiveProfile,
      interactionPreferenceDebug = interactionPreferenceDebug,
      relationshipStateDebug = relationshipStateDebug,
    )
  }

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
    val plasticity = resolvePlasticity(
      raw = baseProfile?.extensions?.get(SoulProfileExtensionKeys.PLASTICITY),
    )

    val overlayState = MutableSoulOverlay(
      displayName = baseProfile?.displayName,
      voice = baseProfile?.voice,
      extensions = baseProfile?.extensions.orEmpty().toMutableMap(),
    )
    val interactionPreferenceProjection = resolveEffectiveInteractionPreferenceProjection(
      records = records,
      workspaceId = workspaceId,
    )
    applyProjectedInteractionPreferenceState(
      projection = interactionPreferenceProjection,
      overlayState = overlayState,
    )
    applyProjectedRelationshipState(
      records = records,
      plasticity = plasticity,
      workspaceId = workspaceId,
      interactionPreferenceState = interactionPreferenceProjection?.projection?.state,
      overlayState = overlayState,
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
    val hasTypedPreferredNaming = applyScalarExtensionOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.PREFERRED_NAMING],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.PREFERRED_NAMING,
    )
    val hasTypedPreferredAddressStyle = applyEnumLikeOverlay(
      raw = filteredExtensions[MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE],
      extensions = overlayState.extensions,
      soulKey = SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE,
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

      MemoryPreferenceKeys.USER_PREFERRED_NAME -> {
        if (!hasTypedPreferredNaming) {
          applyScalarExtensionOverlay(
            raw = preference.value,
            extensions = overlayState.extensions,
            soulKey = SoulProfileExtensionKeys.PREFERRED_NAMING,
          )
        }
      }

      MemoryPreferenceKeys.USER_ADDRESS_STYLE -> {
        if (!hasTypedPreferredAddressStyle) {
          normalizePreferredAddressStyleValueOrNull(preference.value)?.let { normalized ->
            overlayState.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE] = normalized
          }
        }
      }
    }
  }

  private fun applyProjectedInteractionPreferenceState(
    projection: ScopedProjectedInteractionPreferenceState?,
    overlayState: MutableSoulOverlay,
  ): Boolean {
    val effectiveProjection = projection ?: return false
    applyInteractionPreferenceState(
      state = effectiveProjection.projection.state,
      overlayState = overlayState,
    )
    return true
  }

  private fun applyProjectedRelationshipState(
    records: List<MemoryRecord>,
    plasticity: SoulPlasticity,
    workspaceId: String?,
    interactionPreferenceState: InteractionPreferenceState?,
    overlayState: MutableSoulOverlay,
  ) {
    val projection = resolveEffectiveRelationshipStateProjection(
      records = records,
      plasticity = plasticity,
      workspaceId = workspaceId,
    ) ?: return
    val derivation = deriveRelationshipStateEffect(
      state = projection.projection.state,
      interactionPreferenceState = interactionPreferenceState,
    )

    if (derivation.supportiveStyleUnlocked) {
      overlayState.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE] = "supportive"
    }
    if (derivation.warmToneUnlocked) {
      overlayState.extensions[SoulProfileExtensionKeys.TONE] = "warm"
      overlayState.voice = "warm and gentle"
    }
    if (
      derivation.derivedAddressStyle != null &&
      normalizePreferredAddressStyleValueOrNull(overlayState.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE]) == null
    ) {
      overlayState.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE] =
        derivation.derivedAddressStyle.name.lowercase()
    }
    overlayState.extensions[SoulProfileExtensionKeys.INTIMACY_PERMISSION_BAND] =
      derivation.intimacyPermissionBand.name.lowercase()
    overlayState.extensions[SoulProfileExtensionKeys.PLAYFULNESS_PERMISSION_BAND] =
      derivation.playfulnessPermissionBand.name.lowercase()
    overlayState.extensions[SoulProfileExtensionKeys.SUPPORTIVE_REASSURANCE_ALLOWED] =
      derivation.supportiveReassuranceAllowed.toString()
    overlayState.extensions[SoulProfileExtensionKeys.PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED] =
      derivation.proactiveRelationalCheckInAllowed.toString()
    overlayState.extensions[SoulProfileExtensionKeys.LIGHT_PLAYFULNESS_ALLOWED] =
      derivation.lightPlayfulnessAllowed.toString()
    overlayState.extensions[SoulProfileExtensionKeys.PLAYFUL_TEASING_ALLOWED] =
      derivation.playfulTeasingAllowed.toString()
    overlayState.extensions[SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED] =
      derivation.highIntimacyBehaviorAllowed.toString()
    overlayState.extensions[SoulProfileExtensionKeys.PLAYFUL_AFFECTION_ALLOWED] =
      derivation.playfulAffectionAllowed.toString()
  }

  private fun applyInteractionPreferenceState(
    state: InteractionPreferenceState,
    overlayState: MutableSoulOverlay,
  ) {
    state.activePreferredNamingOrNull()?.let { preferredNaming ->
      overlayState.extensions[SoulProfileExtensionKeys.PREFERRED_NAMING] = preferredNaming
    }
    state.activePreferredAddressStyleOrNull()?.let { preferredAddressStyle ->
      overlayState.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE] =
        preferredAddressStyle.name.lowercase()
    }
    overlayPreferenceOffset(
      key = SoulProfileExtensionKeys.WARMTH_PREFERENCE_OFFSET,
      offset = state.warmth.offset,
      overlayState = overlayState,
    )
    overlayPreferenceOffset(
      key = SoulProfileExtensionKeys.FORMALITY_PREFERENCE_OFFSET,
      offset = state.formality.offset,
      overlayState = overlayState,
    )
    overlayPreferenceOffset(
      key = SoulProfileExtensionKeys.INITIATIVE_PREFERENCE_OFFSET,
      offset = state.initiative.offset,
      overlayState = overlayState,
    )
    overlayPreferenceOffset(
      key = SoulProfileExtensionKeys.PLAYFULNESS_PREFERENCE_OFFSET,
      offset = state.playfulness.offset,
      overlayState = overlayState,
    )
    overlayPreferenceOffset(
      key = SoulProfileExtensionKeys.REASSURANCE_PREFERENCE_OFFSET,
      offset = state.reassurance.offset,
      overlayState = overlayState,
    )
    when (state.activeRelationshipStyleOrNull()) {
      "warm" -> {
        overlayState.extensions[SoulProfileExtensionKeys.TONE] = "warm"
        overlayState.voice = "warm and gentle"
        overlayState.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE] = "supportive"
      }

      "serious" -> {
        overlayState.extensions[SoulProfileExtensionKeys.TONE] = "steady"
        overlayState.voice = "serious and formal"
        overlayState.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE] = "direct"
      }
    }
  }

  private fun overlayPreferenceOffset(
    key: String,
    offset: Int,
    overlayState: MutableSoulOverlay,
  ) {
    overlayState.extensions[key] = offset.toString()
  }

  private fun applyScalarOverlay(
    raw: String?,
    apply: (String) -> Unit,
  ): Boolean {
    val normalized = normalizeScalarOrNull(raw) ?: return false
    apply(normalized)
    return true
  }

  private fun applyScalarExtensionOverlay(
    raw: String?,
    extensions: MutableMap<String, String>,
    soulKey: String,
  ): Boolean {
    val normalized = normalizeScalarOrNull(raw) ?: return false
    extensions[soulKey] = normalized
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

  private fun resolvePlasticity(raw: String?): SoulPlasticity = when (normalizeExtensionKeyOrNull(raw)) {
    "high" -> SoulPlasticity.HIGH
    "medium" -> SoulPlasticity.MEDIUM
    else -> SoulPlasticity.LOW
  }

  private fun resolveEffectiveRelationshipStateProjection(
    records: List<MemoryRecord>,
    plasticity: SoulPlasticity,
    workspaceId: String?,
  ): ScopedProjectedRelationshipState? {
    val workspaceProjection = workspaceId
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedWorkspaceId ->
        ScopedProjectedRelationshipState(
          scope = MemoryScope.WORKSPACE,
          projection = relationshipStateProjector.project(
          records = records,
          scope = MemoryScope.WORKSPACE,
          plasticity = plasticity,
          workspaceId = resolvedWorkspaceId,
          ),
        )
      }
      ?.takeIf { projection -> projection.projection.hasPersistedRelationshipSignal() }
    if (workspaceProjection != null) {
      return workspaceProjection
    }
    return ScopedProjectedRelationshipState(
      scope = MemoryScope.USER,
      projection = relationshipStateProjector.project(
        records = records,
        scope = MemoryScope.USER,
        plasticity = plasticity,
      ),
    ).takeIf { projection -> projection.projection.hasPersistedRelationshipSignal() }
  }

  private fun resolveEffectiveInteractionPreferenceProjection(
    records: List<MemoryRecord>,
    workspaceId: String?,
  ): ScopedProjectedInteractionPreferenceState? {
    val workspaceProjection = workspaceId
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedWorkspaceId ->
        ScopedProjectedInteractionPreferenceState(
          scope = MemoryScope.WORKSPACE,
          projection = interactionPreferenceStateProjector.project(
          records = records,
          scope = MemoryScope.WORKSPACE,
          workspaceId = resolvedWorkspaceId,
          ),
        )
      }
      ?.takeIf { projection -> projection.projection.hasPersistedInteractionPreferenceSignal() }
    if (workspaceProjection != null) {
      return workspaceProjection
    }
    return ScopedProjectedInteractionPreferenceState(
      scope = MemoryScope.USER,
      projection = interactionPreferenceStateProjector.project(
        records = records,
        scope = MemoryScope.USER,
      ),
    ).takeIf { projection -> projection.projection.hasPersistedInteractionPreferenceSignal() }
  }

  private fun buildInteractionPreferenceDebugProjection(
    projection: ScopedProjectedInteractionPreferenceState,
  ): InteractionPreferenceDebugProjection = InteractionPreferenceDebugProjection(
    sourceScope = projection.scope,
    snapshotRecordId = projection.projection.snapshotRecordId,
    state = projection.projection.state,
    preferredNaming = projection.projection.state.activePreferredNamingOrNull(),
    preferredAddressStyle = projection.projection.state.activePreferredAddressStyleOrNull(),
    derivedRelationshipStyle = projection.projection.state.activeRelationshipStyleOrNull(),
  )

  private fun buildRelationshipStateDebugProjection(
    projection: ScopedProjectedRelationshipState,
    interactionPreferenceState: InteractionPreferenceState?,
  ): RelationshipStateDebugProjection {
    val derivation = deriveRelationshipStateEffect(
      state = projection.projection.state,
      interactionPreferenceState = interactionPreferenceState,
    )
    return RelationshipStateDebugProjection(
      sourceScope = projection.scope,
      snapshotRecordId = projection.projection.snapshotRecordId,
      appliedEventRecordIds = projection.projection.appliedEventRecordIds,
      state = projection.projection.state,
      recentNegativeGuardActive = derivation.recentNegativeGuardActive,
      supportiveStyleUnlocked = derivation.supportiveStyleUnlocked,
      supportiveStyleChecks = derivation.supportiveStyleChecks,
      warmToneUnlocked = derivation.warmToneUnlocked,
      warmToneChecks = derivation.warmToneChecks,
      derivedAddressStyle = derivation.derivedAddressStyle,
      friendlyAddressChecks = derivation.friendlyAddressChecks,
      intimateAddressChecks = derivation.intimateAddressChecks,
      intimacyPermissionBand = derivation.intimacyPermissionBand,
      playfulnessPermissionBand = derivation.playfulnessPermissionBand,
      supportiveReassuranceAllowed = derivation.supportiveReassuranceAllowed,
      supportiveReassuranceChecks = derivation.supportiveReassuranceChecks,
      proactiveRelationalCheckInAllowed = derivation.proactiveRelationalCheckInAllowed,
      proactiveRelationalCheckInChecks = derivation.proactiveRelationalCheckInChecks,
      lightPlayfulnessAllowed = derivation.lightPlayfulnessAllowed,
      lightPlayfulnessChecks = derivation.lightPlayfulnessChecks,
      playfulTeasingAllowed = derivation.playfulTeasingAllowed,
      playfulTeasingChecks = derivation.playfulTeasingChecks,
      highIntimacyBehaviorAllowed = derivation.highIntimacyBehaviorAllowed,
      highIntimacyChecks = derivation.highIntimacyChecks,
      playfulAffectionAllowed = derivation.playfulAffectionAllowed,
      playfulAffectionChecks = derivation.playfulAffectionChecks,
    )
  }

  private fun InteractionPreferenceState.activeRelationshipStyleOrNull(): String? {
    val warmScore = warmth.offset - formality.offset
    val seriousScore = (-warmth.offset) + formality.offset
    return when {
      warmScore > 0 && warmScore > seriousScore -> "warm"
      seriousScore > 0 && seriousScore > warmScore -> "serious"
      else -> null
    }
  }

  private fun InteractionPreferenceState.activePreferredNamingOrNull(): String? =
    preferredNaming
      ?.takeIf(String::isNotBlank)
      ?.takeIf { preferredNamingSupport > 0 }

  private fun InteractionPreferenceState.activePreferredAddressStyleOrNull(): PreferredAddressStyle? {
    val selectedSupport = addressStyle.supportFor(addressStyle.selectedStyle)
    if (selectedSupport <= 0) {
      return null
    }
    return when (addressStyle.selectedStyle) {
      PreferredAddressStyle.NEUTRAL -> {
        if (
          addressStyle.neutralSupport > addressStyle.friendlySupport &&
          addressStyle.neutralSupport > addressStyle.intimateSupport
        ) {
          PreferredAddressStyle.NEUTRAL
        } else {
          null
        }
      }

      else -> addressStyle.selectedStyle
    }
  }

  private fun relationshipDerivedAddressStyleOrNull(
    state: RelationshipState,
    recentNegativeGuardActive: Boolean,
  ): PreferredAddressStyle? = when {
    state.intimacyPermission >= INTIMATE_ADDRESS_INTIMACY_THRESHOLD &&
      state.playfulnessPermission >= INTIMATE_ADDRESS_PLAYFULNESS_THRESHOLD &&
      state.trust >= INTIMATE_ADDRESS_TRUST_THRESHOLD &&
      state.safety >= INTIMATE_ADDRESS_SAFETY_THRESHOLD &&
      state.reciprocity >= INTIMATE_ADDRESS_RECIPROCITY_THRESHOLD &&
      state.affectionTendency >= INTIMATE_ADDRESS_AFFECTION_THRESHOLD &&
      !recentNegativeGuardActive ->
      PreferredAddressStyle.INTIMATE

    state.intimacyPermission >= FRIENDLY_ADDRESS_INTIMACY_THRESHOLD &&
      state.trust >= FRIENDLY_ADDRESS_TRUST_THRESHOLD &&
      state.safety >= FRIENDLY_ADDRESS_SAFETY_THRESHOLD ->
      PreferredAddressStyle.FRIENDLY

    else -> null
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
  private fun PreferredAddressState.supportFor(style: PreferredAddressStyle): Int = when (style) {
    PreferredAddressStyle.NEUTRAL -> neutralSupport
    PreferredAddressStyle.FRIENDLY -> friendlySupport
    PreferredAddressStyle.INTIMATE -> intimateSupport
  }

  private fun hasRecentRelationshipNegativeGuard(
    state: RelationshipState,
    nowEpochMs: Long,
  ): Boolean {
    val lastNegativeEventAtEpochMs = state.lastNegativeEventAtEpochMs ?: return false
    return nowEpochMs - lastNegativeEventAtEpochMs in 0..RECENT_NEGATIVE_GUARD_WINDOW_MS
  }

  private fun deriveRelationshipStateEffect(
    state: RelationshipState,
    interactionPreferenceState: InteractionPreferenceState? = null,
  ): RelationshipStateDerivation {
    val recentNegativeGuardActive = hasRecentRelationshipNegativeGuard(
      state = state,
      nowEpochMs = clock(),
    )
    val initiativePreferenceOffset = interactionPreferenceState.preferenceOffsetFor(InteractionPreferenceAxis.INITIATIVE)
    val playfulnessPreferenceOffset =
      interactionPreferenceState.preferenceOffsetFor(InteractionPreferenceAxis.PLAYFULNESS)
    val reassurancePreferenceOffset =
      interactionPreferenceState.preferenceOffsetFor(InteractionPreferenceAxis.REASSURANCE)
    val supportiveStyleChecks = listOf(
      thresholdCheck("familiarity", state.familiarity, SUPPORTIVE_FAMILIARITY_THRESHOLD),
      thresholdCheck("trust", state.trust, SUPPORTIVE_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, SUPPORTIVE_SAFETY_THRESHOLD),
    )
    val warmToneChecks = listOf(
      thresholdCheck("trust", state.trust, WARM_TONE_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, WARM_TONE_SAFETY_THRESHOLD),
      thresholdCheck("reciprocity", state.reciprocity, WARM_TONE_RECIPROCITY_THRESHOLD),
      thresholdCheck("affection_tendency", state.affectionTendency, WARM_TONE_AFFECTION_THRESHOLD),
      thresholdCheck("intimacy_permission", state.intimacyPermission, WARM_TONE_INTIMACY_THRESHOLD),
    )
    val friendlyAddressChecks = listOf(
      thresholdCheck("intimacy_permission", state.intimacyPermission, FRIENDLY_ADDRESS_INTIMACY_THRESHOLD),
      thresholdCheck("trust", state.trust, FRIENDLY_ADDRESS_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, FRIENDLY_ADDRESS_SAFETY_THRESHOLD),
    )
    val supportiveReassuranceChecks = listOf(
      thresholdCheck("familiarity", state.familiarity, SUPPORTIVE_REASSURANCE_FAMILIARITY_THRESHOLD),
      thresholdCheck("trust", state.trust, SUPPORTIVE_REASSURANCE_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, SUPPORTIVE_REASSURANCE_SAFETY_THRESHOLD),
      thresholdCheck(
        "reassurance_preference_offset",
        reassurancePreferenceOffset,
        SUPPORTIVE_REASSURANCE_PREFERENCE_THRESHOLD,
      ),
    )
    val proactiveRelationalCheckInChecks = listOf(
      thresholdCheck("familiarity", state.familiarity, PROACTIVE_CHECK_IN_FAMILIARITY_THRESHOLD),
      thresholdCheck("trust", state.trust, PROACTIVE_CHECK_IN_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, PROACTIVE_CHECK_IN_SAFETY_THRESHOLD),
      thresholdCheck("reciprocity", state.reciprocity, PROACTIVE_CHECK_IN_RECIPROCITY_THRESHOLD),
      thresholdCheck(
        "initiative_preference_offset",
        initiativePreferenceOffset,
        PROACTIVE_CHECK_IN_PREFERENCE_THRESHOLD,
      ),
      booleanCheck(
        key = "recent_negative_guard_inactive",
        actual = !recentNegativeGuardActive,
      ),
    )
    val lightPlayfulnessChecks = listOf(
      thresholdCheck(
        "playfulness_permission",
        state.playfulnessPermission,
        LIGHT_PLAYFULNESS_PERMISSION_THRESHOLD,
      ),
      thresholdCheck("safety", state.safety, LIGHT_PLAYFULNESS_SAFETY_THRESHOLD),
      thresholdCheck(
        "playfulness_preference_offset",
        playfulnessPreferenceOffset,
        LIGHT_PLAYFULNESS_PREFERENCE_THRESHOLD,
      ),
      booleanCheck(
        key = "recent_negative_guard_inactive",
        actual = !recentNegativeGuardActive,
      ),
    )
    val playfulTeasingChecks = listOf(
      thresholdCheck(
        "playfulness_permission",
        state.playfulnessPermission,
        PLAYFUL_TEASING_PERMISSION_THRESHOLD,
      ),
      thresholdCheck("trust", state.trust, PLAYFUL_TEASING_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, PLAYFUL_TEASING_SAFETY_THRESHOLD),
      thresholdCheck("reciprocity", state.reciprocity, PLAYFUL_TEASING_RECIPROCITY_THRESHOLD),
      thresholdCheck(
        "playfulness_preference_offset",
        playfulnessPreferenceOffset,
        PLAYFUL_TEASING_PREFERENCE_THRESHOLD,
      ),
      booleanCheck(
        key = "recent_negative_guard_inactive",
        actual = !recentNegativeGuardActive,
      ),
    )
    val intimateAddressChecks = listOf(
      thresholdCheck("intimacy_permission", state.intimacyPermission, INTIMATE_ADDRESS_INTIMACY_THRESHOLD),
      thresholdCheck("playfulness_permission", state.playfulnessPermission, INTIMATE_ADDRESS_PLAYFULNESS_THRESHOLD),
      thresholdCheck("trust", state.trust, INTIMATE_ADDRESS_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, INTIMATE_ADDRESS_SAFETY_THRESHOLD),
      thresholdCheck("reciprocity", state.reciprocity, INTIMATE_ADDRESS_RECIPROCITY_THRESHOLD),
      thresholdCheck("affection_tendency", state.affectionTendency, INTIMATE_ADDRESS_AFFECTION_THRESHOLD),
      booleanCheck(
        key = "recent_negative_guard_inactive",
        actual = !recentNegativeGuardActive,
      ),
    )
    val highIntimacyChecks = listOf(
      thresholdCheck("trust", state.trust, HIGH_INTIMACY_TRUST_THRESHOLD),
      thresholdCheck("safety", state.safety, HIGH_INTIMACY_SAFETY_THRESHOLD),
      thresholdCheck("reciprocity", state.reciprocity, HIGH_INTIMACY_RECIPROCITY_THRESHOLD),
      thresholdCheck("intimacy_permission", state.intimacyPermission, HIGH_INTIMACY_PERMISSION_THRESHOLD),
      booleanCheck(
        key = "recent_negative_guard_inactive",
        actual = !recentNegativeGuardActive,
      ),
    )
    val playfulAffectionChecks = listOf(
      thresholdCheck(
        "playfulness_permission",
        state.playfulnessPermission,
        PLAYFUL_AFFECTION_PERMISSION_THRESHOLD,
      ),
      thresholdCheck("safety", state.safety, PLAYFUL_AFFECTION_SAFETY_THRESHOLD),
      thresholdCheck("reciprocity", state.reciprocity, PLAYFUL_AFFECTION_RECIPROCITY_THRESHOLD),
      thresholdCheck(
        "playfulness_preference_offset",
        playfulnessPreferenceOffset,
        PLAYFUL_AFFECTION_PREFERENCE_THRESHOLD,
      ),
      booleanCheck(
        key = "recent_negative_guard_inactive",
        actual = !recentNegativeGuardActive,
      ),
    )

    val supportiveStyleUnlocked = supportiveStyleChecks.all(SoulGateCheck::passed)
    val warmToneUnlocked = warmToneChecks.all(SoulGateCheck::passed)
    val derivedAddressStyle = when {
      intimateAddressChecks.all(SoulGateCheck::passed) -> PreferredAddressStyle.INTIMATE
      friendlyAddressChecks.all(SoulGateCheck::passed) -> PreferredAddressStyle.FRIENDLY
      else -> null
    }
    val intimacyPermissionBand = state.bandFor(RelationshipDimension.INTIMACY_PERMISSION)
    val playfulnessPermissionBand = state.bandFor(RelationshipDimension.PLAYFULNESS_PERMISSION)
    val supportiveReassuranceAllowed = supportiveReassuranceChecks.all(SoulGateCheck::passed)
    val proactiveRelationalCheckInAllowed = proactiveRelationalCheckInChecks.all(SoulGateCheck::passed)
    val lightPlayfulnessAllowed = lightPlayfulnessChecks.all(SoulGateCheck::passed)
    val playfulTeasingAllowed = playfulTeasingChecks.all(SoulGateCheck::passed)
    val highIntimacyBehaviorAllowed = highIntimacyChecks.all(SoulGateCheck::passed)
    val playfulAffectionAllowed = playfulAffectionChecks.all(SoulGateCheck::passed)

    return RelationshipStateDerivation(
      recentNegativeGuardActive = recentNegativeGuardActive,
      supportiveStyleUnlocked = supportiveStyleUnlocked,
      supportiveStyleChecks = supportiveStyleChecks,
      warmToneUnlocked = warmToneUnlocked,
      warmToneChecks = warmToneChecks,
      derivedAddressStyle = derivedAddressStyle,
      friendlyAddressChecks = friendlyAddressChecks,
      intimateAddressChecks = intimateAddressChecks,
      intimacyPermissionBand = intimacyPermissionBand,
      playfulnessPermissionBand = playfulnessPermissionBand,
      supportiveReassuranceAllowed = supportiveReassuranceAllowed,
      supportiveReassuranceChecks = supportiveReassuranceChecks,
      proactiveRelationalCheckInAllowed = proactiveRelationalCheckInAllowed,
      proactiveRelationalCheckInChecks = proactiveRelationalCheckInChecks,
      lightPlayfulnessAllowed = lightPlayfulnessAllowed,
      lightPlayfulnessChecks = lightPlayfulnessChecks,
      playfulTeasingAllowed = playfulTeasingAllowed,
      playfulTeasingChecks = playfulTeasingChecks,
      highIntimacyBehaviorAllowed = highIntimacyBehaviorAllowed,
      highIntimacyChecks = highIntimacyChecks,
      playfulAffectionAllowed = playfulAffectionAllowed,
      playfulAffectionChecks = playfulAffectionChecks,
    )
  }

  private fun InteractionPreferenceState?.preferenceOffsetFor(axis: InteractionPreferenceAxis): Int = when (axis) {
    InteractionPreferenceAxis.WARMTH -> this?.warmth?.offset ?: 0
    InteractionPreferenceAxis.FORMALITY -> this?.formality?.offset ?: 0
    InteractionPreferenceAxis.INITIATIVE -> this?.initiative?.offset ?: 0
    InteractionPreferenceAxis.PLAYFULNESS -> this?.playfulness?.offset ?: 0
    InteractionPreferenceAxis.REASSURANCE -> this?.reassurance?.offset ?: 0
  }

  private fun thresholdCheck(
    key: String,
    currentValue: Int,
    threshold: Int,
  ): SoulGateCheck = SoulGateCheck(
    key = key,
    currentValue = currentValue,
    threshold = threshold,
    passed = currentValue >= threshold,
  )

  private fun booleanCheck(
    key: String,
    actual: Boolean,
    expected: Boolean = true,
  ): SoulGateCheck = SoulGateCheck(
    key = key,
    actualBoolean = actual,
    expectedBoolean = expected,
    passed = actual == expected,
  )

  private fun ProjectedRelationshipState.hasPersistedRelationshipSignal(): Boolean =
    snapshotRecordId != null || appliedEventRecordIds.isNotEmpty()

  private fun ProjectedInteractionPreferenceState.hasPersistedInteractionPreferenceSignal(): Boolean =
    snapshotRecordId != null

  private data class ScopedProjectedInteractionPreferenceState(
    val scope: MemoryScope,
    val projection: ProjectedInteractionPreferenceState,
  )

  private data class ScopedProjectedRelationshipState(
    val scope: MemoryScope,
    val projection: ProjectedRelationshipState,
  )

  private data class RelationshipStateDerivation(
    val recentNegativeGuardActive: Boolean,
    val supportiveStyleUnlocked: Boolean,
    val supportiveStyleChecks: List<SoulGateCheck>,
    val warmToneUnlocked: Boolean,
    val warmToneChecks: List<SoulGateCheck>,
    val derivedAddressStyle: PreferredAddressStyle?,
    val friendlyAddressChecks: List<SoulGateCheck>,
    val intimateAddressChecks: List<SoulGateCheck>,
    val intimacyPermissionBand: RelationshipBand,
    val playfulnessPermissionBand: RelationshipBand,
    val supportiveReassuranceAllowed: Boolean,
    val supportiveReassuranceChecks: List<SoulGateCheck>,
    val proactiveRelationalCheckInAllowed: Boolean,
    val proactiveRelationalCheckInChecks: List<SoulGateCheck>,
    val lightPlayfulnessAllowed: Boolean,
    val lightPlayfulnessChecks: List<SoulGateCheck>,
    val playfulTeasingAllowed: Boolean,
    val playfulTeasingChecks: List<SoulGateCheck>,
    val highIntimacyBehaviorAllowed: Boolean,
    val highIntimacyChecks: List<SoulGateCheck>,
    val playfulAffectionAllowed: Boolean,
    val playfulAffectionChecks: List<SoulGateCheck>,
  )

  private companion object {
    const val SUPPORTIVE_FAMILIARITY_THRESHOLD: Int = 25
    const val SUPPORTIVE_TRUST_THRESHOLD: Int = 25
    const val SUPPORTIVE_SAFETY_THRESHOLD: Int = 25
    const val SUPPORTIVE_REASSURANCE_FAMILIARITY_THRESHOLD: Int = 20
    const val SUPPORTIVE_REASSURANCE_TRUST_THRESHOLD: Int = 30
    const val SUPPORTIVE_REASSURANCE_SAFETY_THRESHOLD: Int = 35
    const val SUPPORTIVE_REASSURANCE_PREFERENCE_THRESHOLD: Int = 0
    const val PROACTIVE_CHECK_IN_FAMILIARITY_THRESHOLD: Int = 35
    const val PROACTIVE_CHECK_IN_TRUST_THRESHOLD: Int = 45
    const val PROACTIVE_CHECK_IN_SAFETY_THRESHOLD: Int = 45
    const val PROACTIVE_CHECK_IN_RECIPROCITY_THRESHOLD: Int = 25
    const val PROACTIVE_CHECK_IN_PREFERENCE_THRESHOLD: Int = 0
    const val LIGHT_PLAYFULNESS_PERMISSION_THRESHOLD: Int = 18
    const val LIGHT_PLAYFULNESS_SAFETY_THRESHOLD: Int = 30
    const val LIGHT_PLAYFULNESS_PREFERENCE_THRESHOLD: Int = 0
    const val PLAYFUL_TEASING_PERMISSION_THRESHOLD: Int = 32
    const val PLAYFUL_TEASING_TRUST_THRESHOLD: Int = 40
    const val PLAYFUL_TEASING_SAFETY_THRESHOLD: Int = 50
    const val PLAYFUL_TEASING_RECIPROCITY_THRESHOLD: Int = 30
    const val PLAYFUL_TEASING_PREFERENCE_THRESHOLD: Int = 1
    const val FRIENDLY_ADDRESS_INTIMACY_THRESHOLD: Int = 25
    const val FRIENDLY_ADDRESS_TRUST_THRESHOLD: Int = 35
    const val FRIENDLY_ADDRESS_SAFETY_THRESHOLD: Int = 35
    const val INTIMATE_ADDRESS_INTIMACY_THRESHOLD: Int = 55
    const val INTIMATE_ADDRESS_PLAYFULNESS_THRESHOLD: Int = 35
    const val INTIMATE_ADDRESS_TRUST_THRESHOLD: Int = 60
    const val INTIMATE_ADDRESS_SAFETY_THRESHOLD: Int = 60
    const val INTIMATE_ADDRESS_RECIPROCITY_THRESHOLD: Int = 40
    const val INTIMATE_ADDRESS_AFFECTION_THRESHOLD: Int = 25
    const val HIGH_INTIMACY_TRUST_THRESHOLD: Int = 60
    const val HIGH_INTIMACY_SAFETY_THRESHOLD: Int = 60
    const val HIGH_INTIMACY_RECIPROCITY_THRESHOLD: Int = 40
    const val HIGH_INTIMACY_PERMISSION_THRESHOLD: Int = 50
    const val PLAYFUL_AFFECTION_PERMISSION_THRESHOLD: Int = 35
    const val PLAYFUL_AFFECTION_SAFETY_THRESHOLD: Int = 50
    const val PLAYFUL_AFFECTION_RECIPROCITY_THRESHOLD: Int = 35
    const val PLAYFUL_AFFECTION_PREFERENCE_THRESHOLD: Int = 0
    const val RECENT_NEGATIVE_GUARD_WINDOW_MS: Long = 48L * 60L * 60L * 1000L
    const val WARM_TONE_TRUST_THRESHOLD: Int = 50
    const val WARM_TONE_SAFETY_THRESHOLD: Int = 50
    const val WARM_TONE_RECIPROCITY_THRESHOLD: Int = 25
    const val WARM_TONE_AFFECTION_THRESHOLD: Int = 20
    const val WARM_TONE_INTIMACY_THRESHOLD: Int = 25
  }
}
