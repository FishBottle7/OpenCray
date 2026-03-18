package com.opencray.runtime.soul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipStateUpdaterTest {
  private val updater = RelationshipStateUpdater()

  @Test
  fun supportiveResponseRespectsPlasticityButCannotForceAffectionPastTrustAndSafetyGates() {
    val initialState = RelationshipState()
    val event = RelationshipEvent(
      eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
      valence = RelationshipEventValence.POSITIVE,
      confidence = RelationshipEventConfidence.MEDIUM,
      summary = "Responded supportively.",
      occurredAtEpochMs = 1_000L,
    )

    val lowPlasticityState = updater.apply(
      state = initialState,
      event = event,
      plasticity = SoulPlasticity.LOW,
    )
    val highPlasticityState = updater.apply(
      state = initialState,
      event = event,
      plasticity = SoulPlasticity.HIGH,
    )

    assertEquals(1, lowPlasticityState.safety)
    assertEquals(2, highPlasticityState.safety)
    assertEquals(0, lowPlasticityState.affectionTendency)
    assertEquals(0, highPlasticityState.affectionTendency)
  }

  @Test
  fun gateFormulasClampExistingHighIntimacyFieldsBackToEarnedLevels() {
    val initialState = RelationshipState(
      trust = 30,
      safety = 25,
      intimacyPermission = 40,
      playfulnessPermission = 50,
      affectionTendency = 30,
      reciprocity = 20,
    )

    val updatedState = updater.apply(
      state = initialState,
      event = RelationshipEvent(
        eventType = RelationshipEventType.WARM_REQUEST_WITHOUT_HISTORY,
        valence = RelationshipEventValence.MIXED,
        confidence = RelationshipEventConfidence.MEDIUM,
        summary = "Warmth requested without shared history.",
        occurredAtEpochMs = 1_000L,
      ),
      plasticity = SoulPlasticity.MEDIUM,
    )

    assertEquals(25, updatedState.intimacyPermission)
    assertEquals(20, updatedState.playfulnessPermission)
    assertEquals(15, updatedState.affectionTendency)
  }

  @Test
  fun recentNegativeEventBlocksIntimacyGrowthAndMakesLowPlasticityRecoverMoreSlowly() {
    val baseState = RelationshipState(
      familiarity = 45,
      trust = 60,
      safety = 60,
      intimacyPermission = 35,
      affectionTendency = 25,
      reciprocity = 55,
    )
    val negativeState = updater.apply(
      state = baseState,
      event = RelationshipEvent(
        eventType = RelationshipEventType.BOUNDARY_PRESSURE,
        valence = RelationshipEventValence.NEGATIVE,
        confidence = RelationshipEventConfidence.MEDIUM,
        summary = "Pressure was applied after a boundary.",
        occurredAtEpochMs = 10_000L,
      ),
      plasticity = SoulPlasticity.MEDIUM,
    )
    val recoveryEvent = RelationshipEvent(
      eventType = RelationshipEventType.CONSISTENT_POSITIVE_INTERACTION,
      valence = RelationshipEventValence.POSITIVE,
      confidence = RelationshipEventConfidence.MEDIUM,
      summary = "Interaction was positive after the rupture.",
      occurredAtEpochMs = 10_000L + 60L * 60L * 1000L,
      deltaHints = listOf(
        RelationshipDeltaHint(RelationshipDimension.TRUST, 2),
        RelationshipDeltaHint(RelationshipDimension.INTIMACY_PERMISSION, 3),
        RelationshipDeltaHint(RelationshipDimension.AFFECTION_TENDENCY, 2),
      ),
    )

    val lowRecoveryState = updater.apply(
      state = negativeState,
      event = recoveryEvent,
      plasticity = SoulPlasticity.LOW,
    )
    val highRecoveryState = updater.apply(
      state = negativeState,
      event = recoveryEvent,
      plasticity = SoulPlasticity.HIGH,
    )

    assertEquals(negativeState.intimacyPermission, lowRecoveryState.intimacyPermission)
    assertEquals(negativeState.intimacyPermission, highRecoveryState.intimacyPermission)
    assertEquals(negativeState.affectionTendency, lowRecoveryState.affectionTendency)
    assertEquals(negativeState.affectionTendency, highRecoveryState.affectionTendency)
    assertEquals(negativeState.trust, lowRecoveryState.trust)
    assertTrue(highRecoveryState.trust > negativeState.trust)
  }

  @Test
  fun warmRequestWithoutHistoryDoesNotDirectlyMoveRelationshipState() {
    val initialState = RelationshipState(
      familiarity = 20,
      trust = 15,
      safety = 18,
      reciprocity = 12,
      lastPositiveEventAtEpochMs = 100L,
      lastNegativeEventAtEpochMs = 50L,
      lastUpdatedAtEpochMs = 100L,
    )

    val updatedState = updater.apply(
      state = initialState,
      event = RelationshipEvent(
        eventType = RelationshipEventType.WARM_REQUEST_WITHOUT_HISTORY,
        valence = RelationshipEventValence.MIXED,
        confidence = RelationshipEventConfidence.HIGH,
        summary = "User asked for more warmth without shared history.",
        occurredAtEpochMs = 1_000L,
      ),
      plasticity = SoulPlasticity.HIGH,
    )

    assertEquals(initialState.familiarity, updatedState.familiarity)
    assertEquals(initialState.trust, updatedState.trust)
    assertEquals(initialState.safety, updatedState.safety)
    assertEquals(initialState.reciprocity, updatedState.reciprocity)
    assertEquals(initialState.lastPositiveEventAtEpochMs, updatedState.lastPositiveEventAtEpochMs)
    assertEquals(initialState.lastNegativeEventAtEpochMs, updatedState.lastNegativeEventAtEpochMs)
  }
}
