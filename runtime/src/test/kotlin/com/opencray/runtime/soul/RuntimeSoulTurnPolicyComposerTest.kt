package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSoulTurnPolicyComposerTest {
  private val composer = RuntimeSoulTurnPolicyComposer()
  private val overlayResolver = MemoryBackedSoulProfileResolver()

  @Test
  fun composeRendersDeterministicTurnPolicyFromRuntimeSoulAndSignal() {
    val rendered = composer.compose(
      profile = RuntimeSoulProfile(
        presetName = "WARM",
        extensions = mapOf(
          SoulProfileExtensionKeys.INITIATIVE_PREFERENCE_OFFSET to "1",
          SoulProfileExtensionKeys.REASSURANCE_PREFERENCE_OFFSET to "1",
          SoulProfileExtensionKeys.SUPPORTIVE_REASSURANCE_ALLOWED to "true",
          SoulProfileExtensionKeys.PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED to "true",
          SoulProfileExtensionKeys.LIGHT_PLAYFULNESS_ALLOWED to "true",
        ),
      ),
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        userAffect = SoulTurnUserAffect.STRAINED,
        clarificationNeeded = true,
      ),
    )

    assertTrue(rendered.contains("task_priority=task_first"))
    assertTrue(rendered.contains("response_shape=short_support_then_answer"))
    assertTrue(rendered.contains("clarification_mode=proactive_task_focused"))
    assertTrue(rendered.contains("reassurance_mode=brief_grounded"))
    assertTrue(rendered.contains("relational_check_in_mode=secondary_only"))
    assertTrue(rendered.contains("directives:"))
    assertTrue(rendered.contains("Lead with useful task progress before optional relational add-ons."))
  }

  @Test
  fun composeReturnsEmptyWhenSignalIsMissing() {
    assertEquals(
      "",
      composer.compose(
        profile = RuntimeSoulProfile(presetName = "STEADY"),
        signal = null,
      ),
    )
  }

  @Test
  fun composeCarriesMemoryBackedRelationshipGateDecisionsIntoTurnPolicy() {
    val effectiveProfile = overlayResolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "WARM",
        extensions = mapOf(
          SoulProfileExtensionKeys.PLASTICITY to "medium",
        ),
      ),
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-open",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            initiative = PreferenceAxisState(offset = 1, higherSupport = 2),
            playfulness = PreferenceAxisState(offset = 1, higherSupport = 2),
            reassurance = PreferenceAxisState(offset = 1, higherSupport = 2),
          ),
          updatedAtEpochMs = 1_000L,
        ),
        relationshipStateRecord(
          id = "relationship-open",
          scope = MemoryScope.USER,
          state = RelationshipState(
            familiarity = 70,
            trust = 76,
            safety = 78,
            intimacyPermission = 61,
            playfulnessPermission = 44,
            affectionTendency = 34,
            reciprocity = 50,
          ),
          updatedAtEpochMs = 1_100L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    val rendered = composer.compose(
      profile = effectiveProfile,
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = false,
        userAffect = SoulTurnUserAffect.PLAYFUL,
        userInvitesPlayfulness = true,
        clarificationNeeded = true,
      ),
    )

    assertTrue(rendered.contains("response_shape=casual_reply"))
    assertTrue(rendered.contains("clarification_mode=proactive_task_focused"))
    assertTrue(rendered.contains("playfulness_mode=light_teasing_allowed"))
    assertTrue(rendered.contains("intimacy_mode=contextual_only"))
    assertTrue(rendered.contains("- Very mild teasing is acceptable only because the user is actively inviting playful tone."))
    assertTrue(rendered.contains("- Even when closeness is permitted, keep intimacy contextual, reciprocal, and bounded."))
  }

  @Test
  fun composeKeepsCheckInsPlayfulnessAndIntimacyClosedWhileNegativeGuardStillAllowsGroundedSupport() {
    val guardedProfile = MemoryBackedSoulProfileResolver(clock = { 100_000L }).overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "WARM",
        extensions = mapOf(
          SoulProfileExtensionKeys.PLASTICITY to "medium",
        ),
      ),
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-guarded",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            initiative = PreferenceAxisState(offset = 1, higherSupport = 2),
            playfulness = PreferenceAxisState(offset = 1, higherSupport = 2),
            reassurance = PreferenceAxisState(offset = 1, higherSupport = 2),
          ),
          updatedAtEpochMs = 2_000L,
        ),
        relationshipStateRecord(
          id = "relationship-guarded",
          scope = MemoryScope.USER,
          state = RelationshipState(
            familiarity = 66,
            trust = 74,
            safety = 76,
            intimacyPermission = 61,
            playfulnessPermission = 44,
            affectionTendency = 34,
            reciprocity = 49,
            lastNegativeEventAtEpochMs = 99_000L,
          ),
          updatedAtEpochMs = 2_100L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    val rendered = composer.compose(
      profile = guardedProfile,
      signal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        userAffect = SoulTurnUserAffect.DISTRESSED,
        userRequestsRelationalSupport = true,
        userInvitesPlayfulness = true,
      ),
    )

    assertTrue(rendered.contains("response_shape=short_support_then_answer"))
    assertTrue(rendered.contains("reassurance_mode=brief_grounded"))
    assertTrue(rendered.contains("relational_check_in_mode=disallowed"))
    assertTrue(rendered.contains("playfulness_mode=disallowed"))
    assertTrue(rendered.contains("intimacy_mode=restricted"))
    assertTrue(rendered.contains("- Reassurance may be brief and grounded; pair it with concrete help."))
    assertTrue(rendered.contains("- A playfulness preference exists, but the active relationship gate keeps it closed in this turn."))
    assertFalse(rendered.contains("- Do not use overt soothing language in this turn; convey steadiness through calm competence instead."))
  }

  private fun relationshipStateRecord(
    id: String,
    scope: MemoryScope,
    state: RelationshipState,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal relationship snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + buildRelationshipStateMemoryExtensions(state),
  )

  private fun interactionPreferenceStateRecord(
    id: String,
    scope: MemoryScope,
    state: InteractionPreferenceState,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal interaction preference snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + buildInteractionPreferenceStateMemoryExtensions(state),
  )
}
