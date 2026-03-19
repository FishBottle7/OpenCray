package com.opencray.runtime.soul

import kotlinx.serialization.Serializable

@Serializable
enum class InteractionPreferenceAxis {
  WARMTH,
  FORMALITY,
  INITIATIVE,
  PLAYFULNESS,
  REASSURANCE,
}

@Serializable
enum class InteractionPreferenceDirection {
  LOWER,
  HIGHER,
}

@Serializable
enum class PreferredAddressStyle {
  NEUTRAL,
  FRIENDLY,
  INTIMATE,
}

@Serializable
data class PreferenceAxisState(
  val offset: Int = 0,
  val higherSupport: Int = 0,
  val lowerSupport: Int = 0,
  val lastUpdatedAtEpochMs: Long? = null,
) {
  init {
    require(offset in MIN_OFFSET..MAX_OFFSET) { "PreferenceAxisState offset must stay within [-2, 2]." }
    require(higherSupport >= 0) { "PreferenceAxisState higherSupport must be >= 0." }
    require(lowerSupport >= 0) { "PreferenceAxisState lowerSupport must be >= 0." }
  }

  companion object {
    const val MIN_OFFSET: Int = -2
    const val MAX_OFFSET: Int = 2
    const val MAX_SUPPORT: Int = 12
  }
}

@Serializable
data class PreferredAddressState(
  val selectedStyle: PreferredAddressStyle = PreferredAddressStyle.NEUTRAL,
  val neutralSupport: Int = 0,
  val friendlySupport: Int = 0,
  val intimateSupport: Int = 0,
  val lastUpdatedAtEpochMs: Long? = null,
) {
  init {
    require(neutralSupport >= 0) { "PreferredAddressState neutralSupport must be >= 0." }
    require(friendlySupport >= 0) { "PreferredAddressState friendlySupport must be >= 0." }
    require(intimateSupport >= 0) { "PreferredAddressState intimateSupport must be >= 0." }
  }

  companion object {
    const val MAX_SUPPORT: Int = 12
  }
}

@Serializable
data class InteractionPreferenceSignal(
  val axis: InteractionPreferenceAxis? = null,
  val direction: InteractionPreferenceDirection? = null,
  val preferredAddressStyle: PreferredAddressStyle? = null,
  val preferredNaming: String? = null,
  val supportWeight: Int = 1,
  val occurredAtEpochMs: Long,
) {
  init {
    require(axis != null || preferredAddressStyle != null || !preferredNaming.isNullOrBlank()) {
      "InteractionPreferenceSignal must target an axis, an address style, or a preferred naming."
    }
    require((axis == null) == (direction == null)) {
      "InteractionPreferenceSignal axis and direction must either both be present or both be absent."
    }
    require(supportWeight >= 1) { "InteractionPreferenceSignal supportWeight must be >= 1." }
  }
}

@Serializable
data class InteractionPreferenceState(
  val warmth: PreferenceAxisState = PreferenceAxisState(),
  val formality: PreferenceAxisState = PreferenceAxisState(),
  val initiative: PreferenceAxisState = PreferenceAxisState(),
  val playfulness: PreferenceAxisState = PreferenceAxisState(),
  val reassurance: PreferenceAxisState = PreferenceAxisState(),
  val addressStyle: PreferredAddressState = PreferredAddressState(),
  val preferredNaming: String? = null,
  val preferredNamingSupport: Int = 0,
  val lastUpdatedAtEpochMs: Long? = null,
) {
  init {
    require(preferredNamingSupport >= 0) {
      "InteractionPreferenceState preferredNamingSupport must be >= 0."
    }
  }
}

@Serializable
enum class RelationshipDimension {
  FAMILIARITY,
  TRUST,
  SAFETY,
  INTIMACY_PERMISSION,
  PLAYFULNESS_PERMISSION,
  AFFECTION_TENDENCY,
  RECIPROCITY,
}

@Serializable
enum class RelationshipBand {
  GUARDED,
  FAMILIAR,
  WARM,
  HIGH_TRUST,
  DEEPLY_BONDED,
}

@Serializable
enum class RelationshipEventType {
  CONSISTENT_POSITIVE_INTERACTION,
  KEPT_PROMISE,
  RESPECTED_BOUNDARY,
  SUPPORTIVE_RESPONSE,
  REPAIR_AFTER_TENSION,
  RECIPROCAL_WARMTH,
  BOUNDARY_PRESSURE,
  IDENTITY_PRESSURE,
  COERCIVE_AFFECTION_DEMAND,
  INSTRUMENTAL_USE_PATTERN,
  PUNISHED_VULNERABILITY,
  VOLATILE_PUSH_PULL,
  APOLOGY_WITHOUT_REPAIR,
  WARM_REQUEST_WITHOUT_HISTORY,
}

@Serializable
enum class RelationshipEventValence {
  POSITIVE,
  NEGATIVE,
  MIXED,
}

@Serializable
enum class RelationshipEventConfidence {
  LOW,
  MEDIUM,
  HIGH,
}

@Serializable
enum class RelationshipEventScope {
  USER,
  WORKSPACE,
}

@Serializable
data class RelationshipDeltaHint(
  val dimension: RelationshipDimension,
  val delta: Int,
)

@Serializable
data class RelationshipEvent(
  val eventType: RelationshipEventType,
  val valence: RelationshipEventValence,
  val confidence: RelationshipEventConfidence,
  val scope: RelationshipEventScope = RelationshipEventScope.USER,
  val sourceSessionId: String? = null,
  val sourceTurnId: String? = null,
  val summary: String,
  val occurredAtEpochMs: Long,
  val deltaHints: List<RelationshipDeltaHint> = emptyList(),
) {
  init {
    require(summary.isNotBlank()) { "RelationshipEvent summary must not be blank." }
  }
}

@Serializable
data class RelationshipState(
  val familiarity: Int = 0,
  val trust: Int = 0,
  val safety: Int = 0,
  val intimacyPermission: Int = 0,
  val playfulnessPermission: Int = 0,
  val affectionTendency: Int = 0,
  val reciprocity: Int = 0,
  val lastPositiveEventAtEpochMs: Long? = null,
  val lastNegativeEventAtEpochMs: Long? = null,
  val lastUpdatedAtEpochMs: Long? = null,
) {
  init {
    require(familiarity in SCORE_RANGE) { "RelationshipState familiarity must stay within [0, 100]." }
    require(trust in SCORE_RANGE) { "RelationshipState trust must stay within [0, 100]." }
    require(safety in SCORE_RANGE) { "RelationshipState safety must stay within [0, 100]." }
    require(intimacyPermission in SCORE_RANGE) {
      "RelationshipState intimacyPermission must stay within [0, 100]."
    }
    require(playfulnessPermission in SCORE_RANGE) {
      "RelationshipState playfulnessPermission must stay within [0, 100]."
    }
    require(affectionTendency in SCORE_RANGE) {
      "RelationshipState affectionTendency must stay within [0, 100]."
    }
    require(reciprocity in SCORE_RANGE) { "RelationshipState reciprocity must stay within [0, 100]." }
  }

  fun valueFor(dimension: RelationshipDimension): Int = when (dimension) {
    RelationshipDimension.FAMILIARITY -> familiarity
    RelationshipDimension.TRUST -> trust
    RelationshipDimension.SAFETY -> safety
    RelationshipDimension.INTIMACY_PERMISSION -> intimacyPermission
    RelationshipDimension.PLAYFULNESS_PERMISSION -> playfulnessPermission
    RelationshipDimension.AFFECTION_TENDENCY -> affectionTendency
    RelationshipDimension.RECIPROCITY -> reciprocity
  }

  fun bandFor(dimension: RelationshipDimension): RelationshipBand = valueFor(dimension).toRelationshipBand()

  companion object {
    val SCORE_RANGE: IntRange = 0..100
  }
}

internal fun Int.toRelationshipBand(): RelationshipBand = when (this.coerceIn(RelationshipState.SCORE_RANGE)) {
  in 0..24 -> RelationshipBand.GUARDED
  in 25..49 -> RelationshipBand.FAMILIAR
  in 50..69 -> RelationshipBand.WARM
  in 70..84 -> RelationshipBand.HIGH_TRUST
  else -> RelationshipBand.DEEPLY_BONDED
}
