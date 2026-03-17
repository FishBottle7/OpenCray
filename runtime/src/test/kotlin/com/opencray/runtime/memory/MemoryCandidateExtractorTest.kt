package com.opencray.runtime.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCandidateExtractorTest {
  private val extractor = MemoryCandidateExtractor()

  @Test
  fun extractBuildsUserPreferenceAndDurableInstructionFromUserInput() {
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
  fun extractDeduplicatesRepeatedStatementsAcrossEvidenceSources() {
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

    assertEquals(1, candidates.count { candidate -> candidate.kind == MemoryKind.USER_PREFERENCE })
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
  fun extractTurnsDurableStructuredStyleIntoRelationshipSignalAndDropsProtectedExtensions() {
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
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE
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
      "Relationship style should gradually move toward warm",
      style.content,
    )
    assertEquals("warm", style.extensions[MemorySoulExtensionKeys.TONE])
    assertEquals("warm and gentle", style.extensions[MemorySoulExtensionKeys.VOICE])
    assertEquals("supportive", style.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE])
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
  fun extractTurnsDurableUserIntentStyleIntoRelationshipSignal() {
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
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE
    }
    val verbosity = candidates.first { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_VERBOSITY
    }

    assertEquals(MemoryScope.USER, style.scope)
    assertEquals(MemoryScope.SESSION, verbosity.scope)
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
  fun extractDoesNotFallBackToLegacyUserParsingWhenUserIntentInterpreterHandlesTheTurn() {
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

    assertTrue(candidates.isEmpty())
  }

  @Test
  fun extractFallsBackToHeuristicSoulParsingOnlyWhenInterpreterExplicitlyAllowsIt() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Unavailable(
          allowHeuristicFallback = true,
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

    val durableVerbosity = candidates.single { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_VERBOSITY
    }
    assertEquals(MemoryScope.SESSION, durableVerbosity.scope)
    assertEquals("expansive", durableVerbosity.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertEquals("expansive", durableVerbosity.extensions[MemorySoulExtensionKeys.VERBOSITY])
    assertEquals(
      MemoryPreferenceKeys.TEMPORALITY_SESSION,
      durableVerbosity.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
    )
  }

  @Test
  fun extractFallsBackToRelationshipSignalForDurableStyleRequestsWhenInterpreterAllowsIt() {
    val extractor = MemoryCandidateExtractor(
      soulIntentInterpreter = FixedSoulIntentInterpreter(
        SoulMemoryIntentInterpretation.Unavailable(
          allowHeuristicFallback = true,
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

    val relationshipSignal = candidates.single { candidate ->
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.RELATIONSHIP_STYLE_PROFILE
    }
    assertEquals(MemoryScope.USER, relationshipSignal.scope)
    assertEquals(
      MemoryPreferenceKeys.TEMPORALITY_DURABLE,
      relationshipSignal.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
    )
    assertEquals("warm", relationshipSignal.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertEquals("warm", relationshipSignal.extensions[MemorySoulExtensionKeys.TONE])
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
