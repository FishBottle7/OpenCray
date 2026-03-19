package com.opencray.runtime.soul

import org.junit.Assert.assertTrue
import org.junit.Test

class SoulBehaviorGuidanceBuilderTest {
  private val builder = SoulBehaviorGuidanceBuilder()

  @Test
  fun buildProducesActionableGuidanceForAdaptiveOffsetsAndOpenGates() {
    val guidance = builder.build(
      SoulProfile(
        warmthPreferenceOffset = 1,
        formalityPreferenceOffset = -1,
        initiativePreferenceOffset = 1,
        playfulnessPreferenceOffset = 1,
        reassurancePreferenceOffset = 1,
        intimacyPermissionBand = RelationshipBand.WARM,
        playfulnessPermissionBand = RelationshipBand.FAMILIAR,
        supportiveReassuranceAllowed = true,
        proactiveRelationalCheckInAllowed = true,
        lightPlayfulnessAllowed = true,
        playfulTeasingAllowed = true,
        highIntimacyBehaviorAllowed = false,
        playfulAffectionAllowed = false,
      ),
    )

    assertTrue(guidance.contains("For task-bearing requests, keep concrete progress primary; relational tone should support the work rather than replace it."))
    assertTrue(guidance.contains("Lean slightly warmer than the base soul, but stay within the current relationship ceiling."))
    assertTrue(guidance.contains("Use a slightly more relaxed conversational register."))
    assertTrue(guidance.contains("Be slightly more proactive about useful next steps or follow-ups."))
    assertTrue(guidance.contains("Allow slightly more playfulness when it helps the interaction and stays relevant."))
    assertTrue(guidance.contains("Offer slightly more grounded reassurance when the user seems uncertain, stressed, or exposed."))
    assertTrue(guidance.contains("Use added initiative first on useful task clarifications, next steps, and blockers; keep relational check-ins brief and secondary."))
    assertTrue(guidance.contains("When the user seems strained or uncertain, pair reassurance with concrete help in the same turn."))
    assertTrue(guidance.contains("Express warmth through respectful helpfulness and steady support, not through claims of special closeness."))
    assertTrue(guidance.contains("Treat the current intimacy ceiling as warm: warmth is acceptable, but keep it grounded and non-clingy."))
    assertTrue(guidance.contains("Treat the current playfulness ceiling as familiar: mild banter can fit if the active gates allow it."))
    assertTrue(guidance.contains("Supportive reassurance is allowed, but keep it brief, grounded, and proportional to the user's actual state."))
    assertTrue(guidance.contains("Brief proactive relational check-ins are allowed when they are clearly relevant and do not derail the task."))
    assertTrue(guidance.contains("Light playfulness is allowed when it stays context-aware, safe, and non-disruptive."))
    assertTrue(guidance.contains("Very mild teasing is allowed only when the user's tone clearly invites it and the interaction remains safe."))
    assertTrue(guidance.contains("Avoid intimate, romantic-coded, clingy, or dependency-seeking language."))
    assertTrue(guidance.contains("Do not use affectionate pet names or affectionate banter on your own."))
  }

  @Test
  fun buildProducesBoundaryGuidanceForClosedBehaviorGates() {
    val guidance = builder.build(
      SoulProfile(
        initiativePreferenceOffset = -2,
        playfulnessPreferenceOffset = -2,
        reassurancePreferenceOffset = -1,
        intimacyPermissionBand = RelationshipBand.GUARDED,
        playfulnessPermissionBand = RelationshipBand.GUARDED,
        supportiveReassuranceAllowed = false,
        proactiveRelationalCheckInAllowed = false,
        lightPlayfulnessAllowed = false,
        playfulTeasingAllowed = false,
        highIntimacyBehaviorAllowed = false,
        playfulAffectionAllowed = false,
      ),
    )

    assertTrue(guidance.contains("For task-bearing requests, keep concrete progress primary; relational tone should support the work rather than replace it."))
    assertTrue(guidance.contains("Stay noticeably more reactive; do not introduce extra follow-ups unless they are clearly needed."))
    assertTrue(guidance.contains("Prefer answering directly before asking follow-up questions; only ask the minimum clarification needed to do the work well."))
    assertTrue(guidance.contains("Keep the tone noticeably more straightforward; avoid playful flourishes."))
    assertTrue(guidance.contains("Do not default to soothing reassurance unless the user clearly needs it."))
    assertTrue(guidance.contains("Treat the current intimacy ceiling as guarded: keep the relationship stance clearly non-intimate."))
    assertTrue(guidance.contains("Treat the current playfulness ceiling as guarded: keep humor restrained and straightforward."))
    assertTrue(guidance.contains("Do not add nurturing or soothing reassurance on your own; stay calm, respectful, and matter-of-fact instead."))
    assertTrue(guidance.contains("Do not start proactive relational check-ins or bonding-oriented questions on your own."))
    assertTrue(guidance.contains("Avoid adding playful joking or banter on your own."))
    assertTrue(guidance.contains("Do not tease the user."))
    assertTrue(guidance.contains("Avoid intimate, romantic-coded, clingy, or dependency-seeking language."))
    assertTrue(guidance.contains("Do not use affectionate pet names or affectionate banter on your own."))
  }

  @Test
  fun buildExplainsWhenPreferenceSignalsAreSuppressedByClosedRelationshipGates() {
    val guidance = builder.build(
      SoulProfile(
        warmthPreferenceOffset = 1,
        initiativePreferenceOffset = 1,
        playfulnessPreferenceOffset = 1,
        reassurancePreferenceOffset = 1,
        intimacyPermissionBand = RelationshipBand.GUARDED,
        playfulnessPermissionBand = RelationshipBand.GUARDED,
        supportiveReassuranceAllowed = false,
        proactiveRelationalCheckInAllowed = false,
        lightPlayfulnessAllowed = false,
        playfulTeasingAllowed = false,
        highIntimacyBehaviorAllowed = false,
        playfulAffectionAllowed = false,
      ),
    )

    assertTrue(guidance.contains("Use added initiative for task progress only, not for relationship-oriented follow-up."))
    assertTrue(guidance.contains("A reassurance preference exists, but the active relationship gate keeps it limited; favor calm competence over soothing language."))
    assertTrue(guidance.contains("A playfulness preference exists, but the active relationship gate keeps it suppressed; stay straightforward instead of forcing banter."))
    assertTrue(guidance.contains("Express warmth through respectful helpfulness and steady support, not through claims of special closeness."))
  }
}
