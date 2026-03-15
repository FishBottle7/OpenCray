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
    assertEquals(MemoryScope.USER, durableVerbosity.scope)
    assertEquals("expansive", durableVerbosity.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    assertEquals("expansive", durableVerbosity.extensions[MemorySoulExtensionKeys.VERBOSITY])
  }

  private class FixedSoulIntentInterpreter(
    private val interpretation: SoulMemoryIntentInterpretation,
  ) : SoulMemoryIntentInterpreter {
    override fun interpret(
      request: SoulMemoryIntentRequest,
    ): SoulMemoryIntentInterpretation = interpretation
  }
}
