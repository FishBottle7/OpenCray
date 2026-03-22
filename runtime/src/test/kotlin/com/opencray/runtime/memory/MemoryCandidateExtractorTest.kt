package com.opencray.runtime.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCandidateExtractorTest {
  @Test
  fun extractBuildsUserPreferenceAndDurableInstructionFromStructuredUserInput() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(
          intents = listOf(
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              content = "Default to Simplified Chinese for explanations",
            ),
            UserMemoryIntent(
              kind = MemoryKind.DURABLE_INSTRUCTION,
              scope = MemoryScope.WORKSPACE,
              content = "Do not use git reset --hard in this repo",
            ),
          ),
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-1",
        workspaceId = "workspace-main",
        userInput = """
          Please default to Simplified Chinese for explanations.
          Do not use git reset --hard in this repo.
        """.trimIndent(),
      ),
    )

    assertEquals(2, candidates.size)
    val preference = candidates.first { candidate -> candidate.kind == MemoryKind.USER_PREFERENCE }
    val instruction = candidates.first { candidate -> candidate.kind == MemoryKind.DURABLE_INSTRUCTION }

    assertEquals(MemoryScope.USER, preference.scope)
    assertEquals("Default to Simplified Chinese for explanations", preference.content)
    assertEquals(MemoryStatus.ACTIVE, preference.status)
    assertEquals(null, instruction.ttlMs)
    assertEquals(MemoryScope.WORKSPACE, instruction.scope)
    assertTrue(instruction.content.contains("git reset --hard"))
  }

  @Test
  fun extractBuildsProjectFactFromToolObservationAndTaskCommitmentFromAssistantOutput() {
    val extractor = MemoryCandidateExtractor()
    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-2",
        taskId = "task-2",
        workspaceId = "workspace-main",
        userInput = "Please inspect the repo.",
        assistantOutput = "Next I will run the targeted runtime tests.",
        toolObservations = listOf(
          "Project uses Gradle Kotlin DSL and runs on port 8080.",
        ),
      ),
    )

    val projectFact = candidates.first { candidate -> candidate.kind == MemoryKind.PROJECT_FACT }
    val commitment = candidates.first { candidate -> candidate.kind == MemoryKind.TASK_COMMITMENT }

    assertEquals(MemoryScope.WORKSPACE, projectFact.scope)
    assertTrue(projectFact.content.contains("Gradle Kotlin DSL"))
    assertEquals(MemoryEvidenceSource.TOOL_OBSERVATION, projectFact.source)
    assertEquals(MemoryScope.SESSION, commitment.scope)
    assertEquals("run the targeted runtime tests", commitment.content)
    assertEquals(MemoryStatus.OPEN, commitment.status)
  }

  @Test
  fun extractDeduplicatesRepeatedStructuredUserIntents() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(
          intents = listOf(
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              content = "Default to PowerShell commands",
            ),
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              content = "Default to PowerShell commands",
            ),
          ),
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-3",
        taskId = "task-3",
        workspaceId = "workspace-main",
        userInput = "Please default to PowerShell commands.",
        toolObservations = listOf(
          "Please default to PowerShell commands.",
        ),
      ),
    )

    assertEquals(1, candidates.size)
    assertEquals("Default to PowerShell commands", candidates.single().content)
  }

  @Test
  fun extractUsesSoulIntentInterpreterForStructuredPreferences() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(
          intents = listOf(
            SoulMemoryIntent(
              preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
              preferenceValue = "小白",
              scope = MemoryScope.USER,
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.DISPLAY_NAME to "小白",
              ),
            ),
            SoulMemoryIntent(
              preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
              preferenceValue = "warm",
              scope = MemoryScope.SESSION,
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.TONE to "warm",
                MemorySoulExtensionKeys.VOICE to "warm and gentle",
                MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
              ),
            ),
          ),
        ),
      ),
    )
    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-4",
        taskId = "task-4",
        workspaceId = "workspace-main",
        userInput = """
          以后叫你小白。
          这次温柔一点。
        """.trimIndent(),
      ),
    )

    assertEquals(2, candidates.size)
    val displayName = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_DISPLAY_NAME
    }
    val style = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_STYLE_PROFILE
    }

    assertEquals(MemoryScope.USER, displayName.scope)
    assertEquals("Agent display name is 小白", displayName.content)
    assertEquals("小白", displayName.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertEquals("小白", displayName.extensions[MemorySoulExtensionKeys.DISPLAY_NAME])
    assertEquals(MemoryScope.SESSION, style.scope)
    assertEquals("warm", style.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertEquals("warm", style.extensions[MemorySoulExtensionKeys.TONE])
    assertEquals("warm and gentle", style.extensions[MemorySoulExtensionKeys.VOICE])
    assertEquals("supportive", style.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE])
    assertEquals(
      MemoryPreferenceKeys.TEMPORALITY_SESSION,
      style.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
    )
  }

  @Test
  fun extractTurnsDurableStructuredStyleIntoInteractionPreferenceSignalAndDropsProtectedExtensions() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(
          intents = listOf(
            SoulMemoryIntent(
              preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
              preferenceValue = "warm",
              scope = MemoryScope.USER,
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.TONE to "warm",
                MemorySoulExtensionKeys.VOICE to "warm and gentle",
                MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
                MemorySoulExtensionKeys.RISK_TOLERANCE to "aggressive",
              ),
              preferenceExtensions = mapOf(
                MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "lower",
                MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "higher",
                MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION to "higher",
              ),
            ),
            SoulMemoryIntent(
              preferenceKey = MemoryPreferenceKeys.AGENT_VERBOSITY,
              preferenceValue = "expansive",
              scope = MemoryScope.WORKSPACE,
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.VERBOSITY to "expansive",
                MemorySoulExtensionKeys.TOOL_USE_BIAS to "tool_forward",
              ),
            ),
          ),
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-4c",
        taskId = "task-4c",
        workspaceId = "workspace-main",
        userInput = "以后温柔一点，以后回答详细一点。",
      ),
    )

    val style = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL
    }
    val verbosity = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_VERBOSITY
    }

    assertEquals(MemoryScope.USER, style.scope)
    assertEquals(
      MemoryPreferenceKeys.TEMPORALITY_DURABLE,
      style.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
    )
    assertEquals(
      "Interaction preference should gradually adapt: warmth lower, formality higher, initiative higher",
      style.content,
    )
    assertEquals(
      "warmth_lower__formality_higher__initiative_higher",
      style.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
    )
    assertEquals("lower", style.extensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION])
    assertEquals("higher", style.extensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION])
    assertEquals("higher", style.extensions[MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION])
    assertEquals(null, style.extensions[MemorySoulExtensionKeys.TONE])
    assertEquals(null, style.extensions[MemorySoulExtensionKeys.VOICE])
    assertEquals(null, style.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE])
    assertEquals(null, style.extensions[MemorySoulExtensionKeys.RISK_TOLERANCE])

    assertEquals(MemoryScope.SESSION, verbosity.scope)
    assertEquals(
      MemoryPreferenceKeys.TEMPORALITY_SESSION,
      verbosity.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
    )
    assertEquals("expansive", verbosity.extensions[MemorySoulExtensionKeys.VERBOSITY])
    assertEquals(null, verbosity.extensions[MemorySoulExtensionKeys.TOOL_USE_BIAS])
  }

  @Test
  fun extractAcceptsExplicitInteractionPreferenceSignalIntentAndCanonicalizesIt() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(
          intents = listOf(
            SoulMemoryIntent(
              preferenceKey = MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
              preferenceValue = "adaptive",
              scope = MemoryScope.USER,
              preferenceExtensions = mapOf(
                MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
                MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION to "lower",
              ),
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.TONE to "warm",
              ),
            ),
          ),
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-4e",
        taskId = "task-4e",
        workspaceId = "workspace-main",
        userInput = "以后主动一点，但语气也可以再温柔一点。",
      ),
    )

    val signal = candidates.single { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] ==
        MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL
    }

    assertEquals(MemoryScope.USER, signal.scope)
    assertEquals(
      "Interaction preference should gradually adapt: warmth higher, initiative lower",
      signal.content,
    )
    assertEquals(
      "warmth_higher__initiative_lower",
      signal.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
    )
    assertEquals("higher", signal.extensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION])
    assertEquals("lower", signal.extensions[MemoryInteractionPreferenceExtensionKeys.INITIATIVE_DIRECTION])
    assertEquals(null, signal.extensions[MemorySoulExtensionKeys.TONE])
  }

  @Test
  fun extractUsesUserIntentInterpreterForGenericAndSoulMemories() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(
          intents = listOf(
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              content = "Default to Simplified Chinese for explanations",
            ),
            UserMemoryIntent(
              kind = MemoryKind.DURABLE_INSTRUCTION,
              scope = MemoryScope.WORKSPACE,
              content = "Do not use git reset --hard in this repo",
            ),
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
              preferenceValue = "小白",
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.DISPLAY_NAME to "小白",
              ),
            ),
          ),
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-4b",
        taskId = "task-4b",
        workspaceId = "workspace-main",
        userInput = """
          以后解释都用简体中文。
          这个仓库不要用 git reset --hard。
          以后叫你小白。
        """.trimIndent(),
      ),
    )

    assertEquals(3, candidates.size)
    assertTrue(candidates.any { candidate ->
      candidate.kind == MemoryKind.USER_PREFERENCE &&
        candidate.content == "Default to Simplified Chinese for explanations"
    })
    assertTrue(candidates.any { candidate ->
      candidate.kind == MemoryKind.DURABLE_INSTRUCTION &&
        candidate.scope == MemoryScope.WORKSPACE &&
        candidate.content == "Do not use git reset --hard in this repo"
    })
    val displayName = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_DISPLAY_NAME
    }
    assertEquals("小白", displayName.extensions[MemorySoulExtensionKeys.DISPLAY_NAME])
  }

  @Test
  fun extractTurnsDurableUserIntentStyleIntoInteractionPreferenceSignal() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(
          intents = listOf(
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
              preferenceValue = "warm",
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.TONE to "warm",
                MemorySoulExtensionKeys.VOICE to "warm and gentle",
                MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
              ),
              preferenceExtensions = mapOf(
                MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "lower",
                MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "higher",
              ),
            ),
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              preferenceKey = MemoryPreferenceKeys.AGENT_VERBOSITY,
              preferenceValue = "expansive",
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.VERBOSITY to "expansive",
              ),
            ),
          ),
        ),
      ),
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(intents = emptyList()),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-4d",
        taskId = "task-4d",
        workspaceId = "workspace-main",
        userInput = "以后温柔一点，以后回答详细一点。",
      ),
    )

    val style = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL
    }
    val verbosity = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_VERBOSITY
    }

    assertEquals(MemoryScope.USER, style.scope)
    assertEquals(MemoryScope.SESSION, verbosity.scope)
    assertEquals(
      "warmth_lower__formality_higher",
      style.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
    )
    assertEquals("lower", style.extensions[MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION])
    assertEquals("higher", style.extensions[MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION])
    assertEquals(null, style.extensions[MemorySoulExtensionKeys.TONE])
  }

  @Test
  fun extractDoesNotFallBackToKeywordSoulParsingWhenInterpreterHandlesTheTurn() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(intents = emptyList()),
      ),
    )
    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-5",
        taskId = "task-5",
        workspaceId = "workspace-main",
        userInput = "以后叫你小白，这次温柔一点，回答详细一点。",
      ),
    )

    assertTrue(
      candidates.none { candidate ->
        candidate.extensions.containsKey(MemoryRecordExtensionKeys.PREFERENCE_KEY)
      },
    )
  }

  @Test
  fun extractStillConsultsSoulInterpreterWhenUserInterpreterReturnsNoCandidates() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(intents = emptyList()),
      ),
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(
          intents = listOf(
            SoulMemoryIntent(
              preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
              preferenceValue = "小白",
              scope = MemoryScope.USER,
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.DISPLAY_NAME to "小白",
              ),
            ),
          ),
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-5b",
        taskId = "task-5b",
        workspaceId = "workspace-main",
        userInput = "Please default to PowerShell commands and from now on call yourself Xiao Bai.",
      ),
    )

    val displayName = candidates.single { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_DISPLAY_NAME
    }
    assertEquals(MemoryScope.USER, displayName.scope)
    assertEquals("小白", displayName.extensions[MemorySoulExtensionKeys.DISPLAY_NAME])
  }

  @Test
  fun extractPrefersUserStructuredSoulCandidatesWithoutNeedingSoulFallback() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(
          intents = listOf(
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
              preferenceValue = "阿澄",
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.PREFERRED_NAMING to "阿澄",
              ),
            ),
          ),
        ),
      ),
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(intents = emptyList()),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-5c",
        taskId = "task-5c",
        workspaceId = "workspace-main",
        userInput = "以后叫我阿澄。",
      ),
    )

    val preferredNaming = candidates.filter { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    assertEquals(1, preferredNaming.size)
    assertEquals("阿澄", preferredNaming.single().extensions[MemorySoulExtensionKeys.PREFERRED_NAMING])
  }

  @Test
  fun extractDoesNotFallBackToHeuristicSoulParsingWhenSoulInterpreterFailsClosed() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Unavailable(
          allowHeuristicFallback = false,
          reason = "LLM unavailable in test.",
        ),
      ),
    )
    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-6",
        taskId = "task-6",
        workspaceId = "workspace-main",
        userInput = "以后回答详细一点。",
      ),
    )

    assertTrue(candidates.isEmpty())
  }

  @Test
  fun extractDoesNotFallBackToHeuristicInteractionPreferenceParsingWhenSoulInterpreterFailsClosed() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Unavailable(
          allowHeuristicFallback = false,
          reason = "LLM unavailable in test.",
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-6b",
        taskId = "task-6b",
        workspaceId = "workspace-main",
        userInput = "以后对我温柔一点。",
      ),
    )

    assertTrue(candidates.isEmpty())
  }

  @Test
  fun extractBuildsPreferredNamingAndAddressStyleCandidatesFromStructuredUserIntent() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Success(
          intents = listOf(
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
              preferenceValue = "阿澄",
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.PREFERRED_NAMING to "阿澄",
              ),
            ),
            UserMemoryIntent(
              kind = MemoryKind.USER_PREFERENCE,
              scope = MemoryScope.USER,
              preferenceKey = MemoryPreferenceKeys.USER_ADDRESS_STYLE,
              preferenceValue = "friendly",
              soulExtensions = mapOf(
                MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE to "friendly",
              ),
            ),
          ),
        ),
      ),
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Success(intents = emptyList()),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-6c",
        taskId = "task-6c",
        workspaceId = "workspace-main",
        userInput = "以后叫我阿澄，以后称呼我亲切一点。",
      ),
    )

    val preferredNaming = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    val addressStyle = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.USER_ADDRESS_STYLE
    }

    assertEquals(MemoryScope.USER, preferredNaming.scope)
    assertEquals("Preferred user naming is 阿澄", preferredNaming.content)
    assertEquals("阿澄", preferredNaming.extensions[MemorySoulExtensionKeys.PREFERRED_NAMING])
    assertEquals(MemoryScope.USER, addressStyle.scope)
    assertEquals("Address the user in a friendly style", addressStyle.content)
    assertEquals("friendly", addressStyle.extensions[MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE])
  }

  @Test
  fun extractDoesNotFallBackToHeuristicPreferredNamingAndAddressStyleWhenSoulInterpreterFailsClosed() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Unavailable(
          allowHeuristicFallback = false,
          reason = "LLM unavailable in test.",
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-6d",
        taskId = "task-6d",
        workspaceId = "workspace-main",
        userInput = "以后叫我阿澄。以后称呼我亲切一点。",
      ),
    )

    assertTrue(candidates.isEmpty())
  }

  @Test
  fun extractSuppressesLegacyUserParsingWhenUserIntentInterpreterFailsClosed() {
    val extractor = MemoryCandidateExtractor(
      userIntentInterpreter = FixedUserIntentInterpreter(
        UserMemoryIntentInterpretation.Unavailable(
          allowHeuristicFallback = false,
          reason = "Malformed model output.",
        ),
      ),
    )

    val candidates = extractor.extract(
      MemoryTurnEvidence(
        sessionId = "session-7",
        taskId = "task-7",
        workspaceId = "workspace-main",
        userInput = """
          Please default to PowerShell commands.
          Do not use git reset --hard in this repo.
          以后叫你小白。
        """.trimIndent(),
      ),
    )

    assertTrue(candidates.isEmpty())
  }

  private class FixedSoulIntentInterpreter(
    private val interpretation: SoulMemoryIntentInterpretation,
  ) : SoulMemoryIntentInterpreter {
    override fun interpret(
      request: SoulMemoryIntentRequest,
    ): SoulMemoryIntentInterpretation = interpretation
  }

  private class FixedUserIntentInterpreter(
    private val interpretation: UserMemoryIntentInterpretation,
  ) : UserMemoryIntentInterpreter {
    override fun interpret(
      request: UserMemoryIntentRequest,
    ): UserMemoryIntentInterpretation = interpretation
  }
}
