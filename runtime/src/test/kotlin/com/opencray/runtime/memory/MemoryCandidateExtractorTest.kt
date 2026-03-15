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
}
