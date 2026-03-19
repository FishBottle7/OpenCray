package com.opencray.runtime.soul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulTurnResponsePolicyBuilderTest {
  private val builder = SoulTurnResponsePolicyBuilder()

  @Test
  fun buildUsesShortSupportThenAnswerForTaskBearingStrainedTurnWhenSupportIsAllowed() {
    val policy = builder.build(
      profile = SoulProfile(
        warmthPreferenceOffset = 1,
        initiativePreferenceOffset = 1,
        reassurancePreferenceOffset = 1,
        supportiveReassuranceAllowed = true,
        proactiveRelationalCheckInAllowed = true,
        lightPlayfulnessAllowed = true,
        playfulTeasingAllowed = false,
        highIntimacyBehaviorAllowed = false,
      ),
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        userAffect = SoulTurnUserAffect.STRAINED,
        clarificationNeeded = true,
      ),
    )

    assertEquals(SoulTurnTaskPriority.TASK_FIRST, policy.taskPriority)
    assertEquals(SoulTurnResponseShape.SHORT_SUPPORT_THEN_ANSWER, policy.responseShape)
    assertEquals(SoulTurnClarificationMode.PROACTIVE_TASK_FOCUSED, policy.clarificationMode)
    assertEquals(SoulTurnReassuranceMode.BRIEF_GROUNDED, policy.reassuranceMode)
    assertEquals(SoulTurnRelationalCheckInMode.SECONDARY_ONLY, policy.relationalCheckInMode)
    assertEquals(SoulTurnPlayfulnessMode.WITHHOLD_BY_DEFAULT, policy.playfulnessMode)
    assertEquals(SoulTurnIntimacyMode.RESTRICTED, policy.intimacyMode)
    assertTrue(policy.directives.contains("Lead with useful task progress before optional relational add-ons."))
    assertTrue(policy.directives.contains("If you acknowledge emotion, keep it to one brief grounded line before moving into the answer."))
    assertTrue(policy.directives.contains("Reassurance may be brief and grounded; pair it with concrete help."))
    assertTrue(policy.directives.contains("Do not open a separate relational check-in before addressing the task."))
  }

  @Test
  fun buildKeepsSupportAndCheckInsClosedWhenRelationshipGateBlocksThem() {
    val policy = builder.build(
      profile = SoulProfile(
        warmthPreferenceOffset = 1,
        initiativePreferenceOffset = 1,
        playfulnessPreferenceOffset = 1,
        reassurancePreferenceOffset = 1,
        supportiveReassuranceAllowed = false,
        proactiveRelationalCheckInAllowed = false,
        lightPlayfulnessAllowed = false,
        playfulTeasingAllowed = false,
        highIntimacyBehaviorAllowed = false,
      ),
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        userAffect = SoulTurnUserAffect.DISTRESSED,
        userRequestsRelationalSupport = true,
      ),
    )

    assertEquals(SoulTurnResponseShape.ANSWER_FIRST, policy.responseShape)
    assertEquals(SoulTurnReassuranceMode.WITHHOLD_EXPLICIT, policy.reassuranceMode)
    assertEquals(SoulTurnRelationalCheckInMode.DISALLOWED, policy.relationalCheckInMode)
    assertEquals(SoulTurnPlayfulnessMode.DISALLOWED, policy.playfulnessMode)
    assertTrue(policy.directives.contains("Do not use overt soothing language in this turn; convey steadiness through calm competence instead."))
    assertTrue(policy.directives.contains("Do not introduce relationship-oriented follow-up on your own."))
    assertTrue(policy.directives.contains("A reassurance preference exists, but the active relationship gate keeps explicit soothing closed in this turn."))
    assertTrue(policy.directives.contains("Use added initiative for task progress, not for relationship-oriented follow-up."))
  }

  @Test
  fun buildAllowsCasualPlayfulReplyWhenUserInvitesPlayfulnessAndGateIsOpen() {
    val policy = builder.build(
      profile = SoulProfile(
        playfulnessPreferenceOffset = 1,
        lightPlayfulnessAllowed = true,
        playfulTeasingAllowed = true,
        proactiveRelationalCheckInAllowed = true,
        highIntimacyBehaviorAllowed = false,
      ),
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = false,
        userAffect = SoulTurnUserAffect.PLAYFUL,
        userInvitesPlayfulness = true,
      ),
    )

    assertEquals(SoulTurnTaskPriority.RELATIONAL_OPEN, policy.taskPriority)
    assertEquals(SoulTurnResponseShape.CASUAL_REPLY, policy.responseShape)
    assertEquals(SoulTurnReassuranceMode.NONE, policy.reassuranceMode)
    assertEquals(SoulTurnRelationalCheckInMode.BRIEF_IF_RELEVANT, policy.relationalCheckInMode)
    assertEquals(SoulTurnPlayfulnessMode.LIGHT_TEASING_ALLOWED, policy.playfulnessMode)
    assertTrue(policy.directives.contains("A more socially open reply is acceptable, but it should still stay coherent and bounded."))
    assertTrue(policy.directives.contains("Very mild teasing is acceptable only because the user is actively inviting playful tone."))
    assertTrue(policy.directives.contains("A brief relational check-in is allowed only if it clearly fits the live context."))
  }

  @Test
  fun buildPrefersMinimumClarificationWhenInitiativeIsLow() {
    val policy = builder.build(
      profile = SoulProfile(
        initiativePreferenceOffset = -1,
        highIntimacyBehaviorAllowed = false,
      ),
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        clarificationNeeded = true,
      ),
    )

    assertEquals(SoulTurnClarificationMode.MINIMUM_NEEDED, policy.clarificationMode)
    assertTrue(policy.directives.contains("If clarification is unavoidable, ask only the minimum needed question."))
  }
}
