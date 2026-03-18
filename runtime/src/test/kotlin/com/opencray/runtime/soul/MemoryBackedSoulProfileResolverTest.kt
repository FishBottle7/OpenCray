package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.MemoryTurnEvidence
import com.opencray.runtime.memory.MemoryWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBackedSoulProfileResolverTest {
  private val resolver = MemoryBackedSoulProfileResolver()

  @Test
  fun overlayUsesDurableDisplayNameAndSessionStylePreferenceWithoutMutatingProtectedBaseSoul() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        customGuidance = "Stay direct.",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.VERBOSITY to "balanced",
          SoulProfileExtensionKeys.RISK_TOLERANCE to "balanced",
          SoulProfileExtensionKeys.TOOL_USE_BIAS to "tool_forward",
        ),
      ),
      records = listOf(
        preferenceRecord(
          id = "durable-name",
          sessionId = "session-source",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          preferenceValue = "Xiao Bai",
          updatedAtEpochMs = 1_000L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
          ),
        ),
        preferenceRecord(
          id = "durable-style",
          sessionId = "session-source",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          preferenceValue = "warm",
          updatedAtEpochMs = 1_100L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "warm",
            MemorySoulExtensionKeys.VOICE to "warm and gentle",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
          ),
        ),
        preferenceRecord(
          id = "session-style",
          sessionId = "session-main",
          scope = MemoryScope.SESSION,
          preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 1_200L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
            MemorySoulExtensionKeys.RISK_TOLERANCE to "aggressive",
            MemorySoulExtensionKeys.TOOL_USE_BIAS to "avoid_tools",
          ),
        ),
        preferenceRecord(
          id = "durable-verbosity",
          sessionId = "session-source",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_VERBOSITY,
          preferenceValue = "expansive",
          updatedAtEpochMs = 1_150L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.VERBOSITY to "expansive",
          ),
        ),
      ),
      sessionId = "session-main",
    )

    assertEquals("Xiao Bai", profile?.displayName)
    assertEquals("BUILDER", profile?.presetName)
    assertEquals("serious and formal", profile?.voice)
    assertEquals("steady", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("balanced", profile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertEquals("direct", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
    assertEquals("balanced", profile?.extensions?.get(SoulProfileExtensionKeys.RISK_TOLERANCE))
    assertEquals("tool_forward", profile?.extensions?.get(SoulProfileExtensionKeys.TOOL_USE_BIAS))
    assertEquals("Stay direct.", profile?.customGuidance)
  }

  @Test
  fun overlayKeepsDurableIdentityWhileSessionOnlySoulStylingStaysLocal() {
    val store = InMemoryMemoryStore()
    val extractor = MemoryCandidateExtractor()
    val writer = MemoryWriter(
      store = store,
      clock = IncrementingClock(start = 5_000L)::next,
    )
    writer.write(
      extractor.extract(
        MemoryTurnEvidence(
          sessionId = "session-1",
          taskId = "task-1",
          workspaceId = "workspace-main",
          userInput = """
            以后你的名字是小白。
            以后回答详细一点。
          """.trimIndent(),
        ),
      ),
    )
    writer.write(
      extractor.extract(
        MemoryTurnEvidence(
          sessionId = "session-2",
          taskId = "task-2",
          workspaceId = "workspace-main",
          userInput = "这次说话严肃一点。",
        ),
      ),
    )

    val baseProfile = RuntimeSoulProfile(
      presetName = "BUILDER",
      voice = "decisive and direct",
      extensions = mapOf(
        SoulProfileExtensionKeys.TONE to "builder",
        SoulProfileExtensionKeys.VERBOSITY to "balanced",
      ),
    )
    val sessionTwoProfile = resolver.overlay(
      baseProfile = baseProfile,
      records = store.list(),
      sessionId = "session-2",
      workspaceId = "workspace-main",
    )
    val sessionThreeProfile = resolver.overlay(
      baseProfile = baseProfile,
      records = store.list(),
      sessionId = "session-3",
      workspaceId = "workspace-main",
    )

    assertEquals("小白", sessionTwoProfile?.displayName)
    assertEquals("serious and formal", sessionTwoProfile?.voice)
    assertEquals("steady", sessionTwoProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("balanced", sessionTwoProfile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertEquals("小白", sessionThreeProfile?.displayName)
    assertEquals("decisive and direct", sessionThreeProfile?.voice)
    assertEquals("builder", sessionThreeProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("balanced", sessionThreeProfile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertNull(sessionThreeProfile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  @Test
  fun overlayIgnoresLegacyRelationshipStyleSignalsWithoutProjectedState() {
    val store = InMemoryMemoryStore()
    val extractor = MemoryCandidateExtractor()
    val writer = MemoryWriter(
      store = store,
      clock = IncrementingClock(start = 8_000L)::next,
    )
    writer.write(
      extractor.extract(
        MemoryTurnEvidence(
          sessionId = "session-10",
          taskId = "task-10",
          workspaceId = "workspace-main",
          userInput = "以后对我温柔一点。",
        ),
      ),
    )
    writer.write(
      extractor.extract(
        MemoryTurnEvidence(
          sessionId = "session-11",
          taskId = "task-11",
          workspaceId = "workspace-main",
          userInput = "以后对我温柔一点。",
        ),
      ),
    )

    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.PLASTICITY to "medium",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          SoulProfileExtensionKeys.RISK_TOLERANCE to "balanced",
          SoulProfileExtensionKeys.TOOL_USE_BIAS to "tool_forward",
        ),
      ),
      records = store.list(),
      sessionId = "session-12",
      workspaceId = "workspace-main",
    )

    assertEquals("decisive and direct", profile?.voice)
    assertEquals("builder", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("direct", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
    assertEquals("balanced", profile?.extensions?.get(SoulProfileExtensionKeys.RISK_TOLERANCE))
    assertEquals("tool_forward", profile?.extensions?.get(SoulProfileExtensionKeys.TOOL_USE_BIAS))
  }

  @Test
  fun overlayKeepsBaseStyleWhenLegacyRelationshipSignalsAreBalanced() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.PLASTICITY to "medium",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        preferenceRecord(
          id = "relationship-warm-1",
          sessionId = "session-a",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
          preferenceValue = "warm",
          updatedAtEpochMs = 1_000L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "warm",
            MemorySoulExtensionKeys.VOICE to "warm and gentle",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
          ),
        ),
        preferenceRecord(
          id = "relationship-warm-2",
          sessionId = "session-b",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
          preferenceValue = "warm",
          updatedAtEpochMs = 1_100L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "warm",
            MemorySoulExtensionKeys.VOICE to "warm and gentle",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
          ),
        ),
        preferenceRecord(
          id = "relationship-serious-1",
          sessionId = "session-c",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 1_200L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          ),
        ),
        preferenceRecord(
          id = "relationship-serious-2",
          sessionId = "session-d",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 1_300L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          ),
        ),
      ),
      sessionId = "session-main",
    )

    assertEquals("decisive and direct", profile?.voice)
    assertEquals("builder", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("direct", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  @Test
  fun overlayPrefersProjectedInteractionPreferenceStateOverLegacyRelationshipStyleRecords() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-user",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
          ),
          updatedAtEpochMs = 1_500L,
        ),
        preferenceRecord(
          id = "legacy-serious",
          sessionId = "session-legacy",
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 1_600L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          ),
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("warm and gentle", profile?.voice)
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("supportive", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  @Test
  fun overlayProjectsPreferredNamingAndAddressStyleFromInteractionPreferenceState() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-user-addressing",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            addressStyle = PreferredAddressState(
              selectedStyle = PreferredAddressStyle.FRIENDLY,
              friendlySupport = 2,
            ),
            preferredNaming = "阿澄",
            preferredNamingSupport = 1,
          ),
          updatedAtEpochMs = 1_700L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("阿澄", profile?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_NAMING))
    assertEquals("friendly", profile?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE))
    assertEquals("decisive and direct", profile?.voice)
    assertEquals("builder", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
  }

  @Test
  fun overlayLetsSessionStyleOverrideProjectedInteractionPreferenceForCurrentRun() {
    val baseProfile = RuntimeSoulProfile(
      presetName = "BUILDER",
      voice = "decisive and direct",
      extensions = mapOf(
        SoulProfileExtensionKeys.TONE to "builder",
        SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
      ),
    )
    val session32Profile = resolver.overlay(
      baseProfile = baseProfile,
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-user-warm",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
          ),
          updatedAtEpochMs = 10_200L,
        ),
        preferenceRecord(
          id = "session-style",
          sessionId = "session-32",
          scope = MemoryScope.SESSION,
          preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 10_300L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          ),
        ),
      ),
      sessionId = "session-32",
      workspaceId = "workspace-main",
    )
    val session33Profile = resolver.overlay(
      baseProfile = baseProfile,
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-user-warm",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
          ),
          updatedAtEpochMs = 10_200L,
        ),
      ),
      sessionId = "session-33",
      workspaceId = "workspace-main",
    )

    assertEquals("serious and formal", session32Profile?.voice)
    assertEquals("steady", session32Profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("direct", session32Profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))

    assertEquals("warm and gentle", session33Profile?.voice)
    assertEquals("warm", session33Profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("supportive", session33Profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  @Test
  fun overlayAppliesProjectedUserRelationshipStateIntoEffectiveSoul() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          SoulProfileExtensionKeys.RISK_TOLERANCE to "balanced",
        ),
      ),
      records = listOf(
        relationshipStateRecord(
          id = "relationship-user",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
          state = RelationshipState(
            familiarity = 32,
            trust = 56,
            safety = 58,
            intimacyPermission = 28,
            playfulnessPermission = 12,
            affectionTendency = 23,
            reciprocity = 30,
          ),
          updatedAtEpochMs = 4_000L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("warm and gentle", profile?.voice)
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("supportive", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
    assertEquals("balanced", profile?.extensions?.get(SoulProfileExtensionKeys.RISK_TOLERANCE))
  }

  @Test
  fun overlayProjectsRelationshipPermissionBandsAndFriendlyAddressStyle() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        relationshipStateRecord(
          id = "relationship-user-friendly",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
          state = RelationshipState(
            familiarity = 31,
            trust = 48,
            safety = 49,
            intimacyPermission = 28,
            playfulnessPermission = 16,
            affectionTendency = 18,
            reciprocity = 22,
          ),
          updatedAtEpochMs = 4_200L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("friendly", profile?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE))
    assertEquals("familiar", profile?.extensions?.get(SoulProfileExtensionKeys.INTIMACY_PERMISSION_BAND))
    assertEquals("guarded", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFULNESS_PERMISSION_BAND))
    assertEquals("false", profile?.extensions?.get(SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED))
    assertEquals("false", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFUL_AFFECTION_ALLOWED))
    assertEquals("supportive", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
    assertEquals("decisive and direct", profile?.voice)
  }

  @Test
  fun inspectOverlayExposesProjectedInteractionPreferenceDebugState() {
    val debug = resolver.inspectOverlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
        ),
      ),
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-user-friendly",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
            formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
            addressStyle = PreferredAddressState(
              selectedStyle = PreferredAddressStyle.FRIENDLY,
              friendlySupport = 2,
            ),
            preferredNaming = "A-Cheng",
            preferredNamingSupport = 2,
          ),
          updatedAtEpochMs = 4_210L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    val interactionPreferenceDebug = checkNotNull(debug.interactionPreferenceDebug)
    assertEquals(MemoryScope.USER, interactionPreferenceDebug.sourceScope)
    assertEquals("interaction-user-friendly", interactionPreferenceDebug.snapshotRecordId)
    assertEquals("A-Cheng", interactionPreferenceDebug.preferredNaming)
    assertEquals(PreferredAddressStyle.FRIENDLY, interactionPreferenceDebug.preferredAddressStyle)
    assertEquals("warm", interactionPreferenceDebug.derivedRelationshipStyle)
  }

  @Test
  fun overlayProjectsIntimateAddressStyleWhenRelationshipStateIsDeepEnough() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        relationshipStateRecord(
          id = "relationship-user-intimate",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
          state = RelationshipState(
            familiarity = 64,
            trust = 72,
            safety = 73,
            intimacyPermission = 59,
            playfulnessPermission = 41,
            affectionTendency = 31,
            reciprocity = 47,
          ),
          updatedAtEpochMs = 4_240L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("intimate", profile?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE))
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.INTIMACY_PERMISSION_BAND))
    assertEquals("familiar", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFULNESS_PERMISSION_BAND))
    assertEquals("true", profile?.extensions?.get(SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED))
    assertEquals("true", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFUL_AFFECTION_ALLOWED))
    assertEquals("warm and gentle", profile?.voice)
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
  }

  @Test
  fun overlayKeepsExplicitInteractionPreferenceAddressStyleOverDeeperRelationshipState() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-user-friendly",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            addressStyle = PreferredAddressState(
              selectedStyle = PreferredAddressStyle.FRIENDLY,
              friendlySupport = 2,
            ),
          ),
          updatedAtEpochMs = 4_250L,
        ),
        relationshipStateRecord(
          id = "relationship-user-intimate",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
          state = RelationshipState(
            familiarity = 62,
            trust = 72,
            safety = 74,
            intimacyPermission = 58,
            playfulnessPermission = 42,
            affectionTendency = 32,
            reciprocity = 46,
          ),
          updatedAtEpochMs = 4_300L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("friendly", profile?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE))
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.INTIMACY_PERMISSION_BAND))
    assertEquals("familiar", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFULNESS_PERMISSION_BAND))
    assertEquals("true", profile?.extensions?.get(SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED))
    assertEquals("true", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFUL_AFFECTION_ALLOWED))
    assertEquals("warm and gentle", profile?.voice)
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
  }

  @Test
  fun overlayBlocksHighIntimacyAndPlayfulAffectionWhenRecentNegativeGuardIsActive() {
    val guardedResolver = MemoryBackedSoulProfileResolver(clock = { 100_000L })
    val profile = guardedResolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        relationshipStateRecord(
          id = "relationship-user-guarded",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
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
          updatedAtEpochMs = 4_320L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("friendly", profile?.extensions?.get(SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE))
    assertEquals("warm", profile?.extensions?.get(SoulProfileExtensionKeys.INTIMACY_PERMISSION_BAND))
    assertEquals("familiar", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFULNESS_PERMISSION_BAND))
    assertEquals("false", profile?.extensions?.get(SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED))
    assertEquals("false", profile?.extensions?.get(SoulProfileExtensionKeys.PLAYFUL_AFFECTION_ALLOWED))
    assertEquals("warm and gentle", profile?.voice)
  }

  @Test
  fun inspectOverlayExposesRelationshipGateReasonsForRecentNegativeGuard() {
    val guardedResolver = MemoryBackedSoulProfileResolver(clock = { 100_000L })
    val debug = guardedResolver.inspectOverlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        relationshipStateRecord(
          id = "relationship-user-guarded",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
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
          updatedAtEpochMs = 4_320L,
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    val relationshipDebug = checkNotNull(debug.relationshipStateDebug)
    assertEquals(MemoryScope.USER, relationshipDebug.sourceScope)
    assertTrue(relationshipDebug.recentNegativeGuardActive)
    assertEquals(PreferredAddressStyle.FRIENDLY, relationshipDebug.derivedAddressStyle)
    assertFalse(relationshipDebug.highIntimacyBehaviorAllowed)
    assertFalse(relationshipDebug.playfulAffectionAllowed)
    assertTrue(
      relationshipDebug.highIntimacyChecks.any { check ->
        check.key == "recent_negative_guard_inactive" && !check.passed
      },
    )
    assertTrue(
      relationshipDebug.intimateAddressChecks.any { check ->
        check.key == "recent_negative_guard_inactive" && !check.passed
      },
    )
  }

  @Test
  fun overlayPrefersWorkspaceRelationshipStateOverUserWideRelationshipState() {
    val baseProfile = RuntimeSoulProfile(
      presetName = "BUILDER",
      voice = "decisive and direct",
      extensions = mapOf(
        SoulProfileExtensionKeys.TONE to "builder",
        SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
      ),
    )
    val records = listOf(
      relationshipStateRecord(
        id = "relationship-user",
        scope = MemoryScope.USER,
        sourceSessionId = "session-user",
        state = RelationshipState(
          familiarity = 34,
          trust = 58,
          safety = 59,
          intimacyPermission = 30,
          affectionTendency = 24,
          reciprocity = 29,
        ),
        updatedAtEpochMs = 5_000L,
      ),
      relationshipStateRecord(
        id = "relationship-workspace",
        scope = MemoryScope.WORKSPACE,
        sourceSessionId = "session-workspace",
        workspaceId = "workspace-main",
        state = RelationshipState(
          familiarity = 8,
          trust = 10,
          safety = 12,
          intimacyPermission = 0,
          affectionTendency = 0,
          reciprocity = 6,
        ),
        updatedAtEpochMs = 5_100L,
      ),
    )

    val workspaceMainProfile = resolver.overlay(
      baseProfile = baseProfile,
      records = records,
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )
    val workspaceOtherProfile = resolver.overlay(
      baseProfile = baseProfile,
      records = records,
      sessionId = "session-main",
      workspaceId = "workspace-other",
    )

    assertEquals("decisive and direct", workspaceMainProfile?.voice)
    assertEquals("builder", workspaceMainProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("direct", workspaceMainProfile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))

    assertEquals("warm and gentle", workspaceOtherProfile?.voice)
    assertEquals("warm", workspaceOtherProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("supportive", workspaceOtherProfile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  @Test
  fun overlayLetsSessionStyleOverrideProjectedRelationshipStateWarmth() {
    val profile = resolver.overlay(
      baseProfile = RuntimeSoulProfile(
        presetName = "BUILDER",
        voice = "decisive and direct",
        extensions = mapOf(
          SoulProfileExtensionKeys.TONE to "builder",
          SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
        ),
      ),
      records = listOf(
        relationshipStateRecord(
          id = "relationship-user",
          scope = MemoryScope.USER,
          sourceSessionId = "session-old",
          state = RelationshipState(
            familiarity = 35,
            trust = 60,
            safety = 61,
            intimacyPermission = 30,
            affectionTendency = 22,
            reciprocity = 31,
          ),
          updatedAtEpochMs = 6_000L,
        ),
        preferenceRecord(
          id = "session-style",
          sessionId = "session-main",
          scope = MemoryScope.SESSION,
          preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          preferenceValue = "serious",
          updatedAtEpochMs = 6_100L,
          extraExtensions = mapOf(
            MemorySoulExtensionKeys.TONE to "steady",
            MemorySoulExtensionKeys.VOICE to "serious and formal",
            MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
          ),
        ),
      ),
      sessionId = "session-main",
      workspaceId = "workspace-main",
    )

    assertEquals("serious and formal", profile?.voice)
    assertEquals("steady", profile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("direct", profile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE))
  }

  private fun preferenceRecord(
    id: String,
    sessionId: String,
    scope: MemoryScope,
    preferenceKey: String,
    preferenceValue: String,
    updatedAtEpochMs: Long,
    extraExtensions: Map<String, String> = emptyMap(),
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "$preferenceKey=$preferenceValue",
    tags = listOf(
      "kind:user_preference",
      "scope:${scope.name.lowercase()}",
      "status:${MemoryStatus.ACTIVE.name.lowercase()}",
    ),
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to MemoryKind.USER_PREFERENCE.name.lowercase(),
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
      MemoryRecordExtensionKeys.PREFERENCE_KEY to preferenceKey,
      MemoryRecordExtensionKeys.PREFERENCE_VALUE to preferenceValue,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + extraExtensions,
  )

  private fun relationshipStateRecord(
    id: String,
    scope: MemoryScope,
    sourceSessionId: String,
    state: RelationshipState,
    updatedAtEpochMs: Long,
    workspaceId: String? = null,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal relationship snapshot",
    tags = listOf(
      "kind:project_fact",
      "scope:${scope.name.lowercase()}",
      "status:${MemoryStatus.ACTIVE.name.lowercase()}",
    ),
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to MemoryKind.PROJECT_FACT.name.lowercase(),
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sourceSessionId,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + (workspaceId?.let { resolvedWorkspaceId ->
      mapOf(MemoryRecordExtensionKeys.WORKSPACE_ID to resolvedWorkspaceId)
    }.orEmpty()) + buildRelationshipStateMemoryExtensions(state),
  )

  private fun interactionPreferenceStateRecord(
    id: String,
    scope: MemoryScope,
    state: InteractionPreferenceState,
    updatedAtEpochMs: Long,
    workspaceId: String? = null,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal interaction preference snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + (workspaceId?.let { resolvedWorkspaceId ->
      mapOf(MemoryRecordExtensionKeys.WORKSPACE_ID to resolvedWorkspaceId)
    }.orEmpty()) + buildInteractionPreferenceStateMemoryExtensions(state),
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var value = start

    fun next(): Long = value++
  }
}
