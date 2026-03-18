package com.opencray.runtime.soul

class RelationshipStateUpdater {
  fun apply(
    state: RelationshipState,
    event: RelationshipEvent,
    plasticity: SoulPlasticity,
  ): RelationshipState {
    val policy = plasticity.relationshipPolicy()
    val recentNegativeActive = hasRecentNegativeInertia(
      lastNegativeEventAtEpochMs = state.lastNegativeEventAtEpochMs,
      eventEpochMs = event.occurredAtEpochMs,
      windowMs = policy.negativeInertiaWindowMs,
    )
    val deltaByDimension = aggregateDeltas(event).mapValues { (dimension, rawDelta) ->
      adjustDelta(
        dimension = dimension,
        delta = rawDelta,
        confidence = event.confidence,
        recentNegativeActive = recentNegativeActive,
        policy = policy,
      )
    }
    val nextState = RelationshipState(
      familiarity = state.familiarity.applyDelta(deltaByDimension[RelationshipDimension.FAMILIARITY] ?: 0),
      trust = state.trust.applyDelta(deltaByDimension[RelationshipDimension.TRUST] ?: 0),
      safety = state.safety.applyDelta(deltaByDimension[RelationshipDimension.SAFETY] ?: 0),
      intimacyPermission = state.intimacyPermission.applyDelta(
        deltaByDimension[RelationshipDimension.INTIMACY_PERMISSION] ?: 0,
      ),
      playfulnessPermission = state.playfulnessPermission.applyDelta(
        deltaByDimension[RelationshipDimension.PLAYFULNESS_PERMISSION] ?: 0,
      ),
      affectionTendency = state.affectionTendency.applyDelta(
        deltaByDimension[RelationshipDimension.AFFECTION_TENDENCY] ?: 0,
      ),
      reciprocity = state.reciprocity.applyDelta(deltaByDimension[RelationshipDimension.RECIPROCITY] ?: 0),
      lastPositiveEventAtEpochMs = if (deltaByDimension.values.any { delta -> delta > 0 }) {
        maxOfTimestamp(state.lastPositiveEventAtEpochMs, event.occurredAtEpochMs)
      } else {
        state.lastPositiveEventAtEpochMs
      },
      lastNegativeEventAtEpochMs = if (deltaByDimension.values.any { delta -> delta < 0 }) {
        maxOfTimestamp(state.lastNegativeEventAtEpochMs, event.occurredAtEpochMs)
      } else {
        state.lastNegativeEventAtEpochMs
      },
      lastUpdatedAtEpochMs = maxOfTimestamp(state.lastUpdatedAtEpochMs, event.occurredAtEpochMs),
    )
    return enforceGates(nextState)
  }

  private fun aggregateDeltas(event: RelationshipEvent): Map<RelationshipDimension, Int> = buildMap {
    defaultDeltaHintsFor(event.eventType)
      .plus(event.deltaHints)
      .forEach { hint ->
        put(hint.dimension, (get(hint.dimension) ?: 0) + hint.delta)
      }
  }

  private fun defaultDeltaHintsFor(eventType: RelationshipEventType): List<RelationshipDeltaHint> = when (eventType) {
    RelationshipEventType.CONSISTENT_POSITIVE_INTERACTION -> listOf(
      RelationshipDeltaHint(RelationshipDimension.FAMILIARITY, 2),
    )

    RelationshipEventType.KEPT_PROMISE -> listOf(
      RelationshipDeltaHint(RelationshipDimension.TRUST, 2),
      RelationshipDeltaHint(RelationshipDimension.FAMILIARITY, 1),
    )

    RelationshipEventType.RESPECTED_BOUNDARY -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, 3),
      RelationshipDeltaHint(RelationshipDimension.TRUST, 1),
    )

    RelationshipEventType.SUPPORTIVE_RESPONSE -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, 2),
      RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, 1),
    )

    RelationshipEventType.REPAIR_AFTER_TENSION -> listOf(
      RelationshipDeltaHint(RelationshipDimension.TRUST, 1),
      RelationshipDeltaHint(RelationshipDimension.SAFETY, 1),
    )

    RelationshipEventType.RECIPROCAL_WARMTH -> listOf(
      RelationshipDeltaHint(RelationshipDimension.RECIPROCITY, 2),
      RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, 1),
      RelationshipDeltaHint(RelationshipDimension.FAMILIARITY, 1),
    )

    RelationshipEventType.BOUNDARY_PRESSURE -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, -4),
      RelationshipDeltaHint(RelationshipDimension.TRUST, -1),
      RelationshipDeltaHint(RelationshipDimension.PLAYFULNESS_PERMISSION, -1),
    )

    RelationshipEventType.IDENTITY_PRESSURE -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, -3),
      RelationshipDeltaHint(RelationshipDimension.TRUST, -3),
      RelationshipDeltaHint(RelationshipDimension.RECIPROCITY, -1),
    )

    RelationshipEventType.COERCIVE_AFFECTION_DEMAND -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, -4),
      RelationshipDeltaHint(RelationshipDimension.RECIPROCITY, -3),
      RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, -2),
      RelationshipDeltaHint(RelationshipDimension.PLAYFULNESS_PERMISSION, -2),
      RelationshipDeltaHint(RelationshipDimension.INTIMACY_PERMISSION, -2),
    )

    RelationshipEventType.INSTRUMENTAL_USE_PATTERN -> listOf(
      RelationshipDeltaHint(RelationshipDimension.RECIPROCITY, -3),
      RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, -1),
    )

    RelationshipEventType.PUNISHED_VULNERABILITY -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, -5),
      RelationshipDeltaHint(RelationshipDimension.TRUST, -4),
      RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, -2),
      RelationshipDeltaHint(RelationshipDimension.INTIMACY_PERMISSION, -2),
    )

    RelationshipEventType.VOLATILE_PUSH_PULL -> listOf(
      RelationshipDeltaHint(RelationshipDimension.SAFETY, -4),
      RelationshipDeltaHint(RelationshipDimension.TRUST, -3),
      RelationshipDeltaHint(RelationshipDimension.RECIPROCITY, -2),
      RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, -1),
    )

    RelationshipEventType.APOLOGY_WITHOUT_REPAIR -> listOf(
      RelationshipDeltaHint(RelationshipDimension.TRUST, 1),
    )

    RelationshipEventType.WARM_REQUEST_WITHOUT_HISTORY -> emptyList()
  }

  private fun adjustDelta(
    dimension: RelationshipDimension,
    delta: Int,
    confidence: RelationshipEventConfidence,
    recentNegativeActive: Boolean,
    policy: RelationshipUpdatePolicy,
  ): Int {
    if (delta == 0) {
      return 0
    }
    val confidenceAdjusted = when (confidence) {
      RelationshipEventConfidence.LOW -> when {
        delta > 1 -> delta - 1
        delta < -1 -> delta + 1
        else -> delta
      }

      RelationshipEventConfidence.MEDIUM,
      RelationshipEventConfidence.HIGH,
      -> delta
    }
    if (confidenceAdjusted <= 0) {
      return confidenceAdjusted
    }
    val cappedPositive = confidenceAdjusted.coerceAtMost(policy.maxPositiveDeltaPerEvent)
    return when (dimension) {
      RelationshipDimension.INTIMACY_PERMISSION,
      RelationshipDimension.PLAYFULNESS_PERMISSION,
      RelationshipDimension.AFFECTION_TENDENCY,
      -> if (recentNegativeActive) {
        0
      } else {
        cappedPositive
      }

      RelationshipDimension.TRUST,
      RelationshipDimension.SAFETY,
      -> if (recentNegativeActive) {
        (cappedPositive - policy.rebuildResistance).coerceAtLeast(0)
      } else {
        cappedPositive
      }

      else -> cappedPositive
    }
  }

  private fun enforceGates(state: RelationshipState): RelationshipState {
    val intimacyPermission = minOf(state.intimacyPermission, minOf(state.trust, state.safety))
    val playfulnessPermission = minOf(state.playfulnessPermission, minOf(state.safety, state.reciprocity))
    val affectionCap = maxOf(0, minOf(state.trust, state.safety) - AFFECTION_MARGIN)
    return state.copy(
      intimacyPermission = intimacyPermission,
      playfulnessPermission = playfulnessPermission,
      affectionTendency = minOf(state.affectionTendency, affectionCap),
    )
  }

  private fun hasRecentNegativeInertia(
    lastNegativeEventAtEpochMs: Long?,
    eventEpochMs: Long,
    windowMs: Long,
  ): Boolean {
    val lastNegativeEventAt = lastNegativeEventAtEpochMs ?: return false
    return eventEpochMs - lastNegativeEventAt in 0..windowMs
  }

  private fun Int.applyDelta(delta: Int): Int = (this + delta).coerceIn(RelationshipState.SCORE_RANGE)

  private fun maxOfTimestamp(current: Long?, candidate: Long): Long = maxOf(current ?: candidate, candidate)

  private data class RelationshipUpdatePolicy(
    val maxPositiveDeltaPerEvent: Int,
    val rebuildResistance: Int,
    val negativeInertiaWindowMs: Long,
  )

  private fun SoulPlasticity.relationshipPolicy(): RelationshipUpdatePolicy = when (this) {
    SoulPlasticity.LOW -> RelationshipUpdatePolicy(
      maxPositiveDeltaPerEvent = 1,
      rebuildResistance = 1,
      negativeInertiaWindowMs = 72L * 60L * 60L * 1000L,
    )

    SoulPlasticity.MEDIUM -> RelationshipUpdatePolicy(
      maxPositiveDeltaPerEvent = 2,
      rebuildResistance = 1,
      negativeInertiaWindowMs = 48L * 60L * 60L * 1000L,
    )

    SoulPlasticity.HIGH -> RelationshipUpdatePolicy(
      maxPositiveDeltaPerEvent = 3,
      rebuildResistance = 0,
      negativeInertiaWindowMs = 24L * 60L * 60L * 1000L,
    )
  }

  companion object {
    private const val AFFECTION_MARGIN: Int = 10
  }
}
