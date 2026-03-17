package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
  @Test
  fun prepareSelectsTranscriptSoulAndBoundedMemoryBeforePromptRendering() {
    val manager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 2,
          maxCharsPerMessage = 48,
        ),
      ),
      config = ContextManagerConfig(
        maxInjectedMemoryRecords = 1,
      ),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(
          sessionPolicyText = "Keep the session coherent.",
          soulProfile = RuntimeSoulProfile(
            presetName = "BUILDER",
            displayName = "Night Shift",
            customGuidance = "Be terse and implementation-first.",
          ),
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-user",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Default to concise Chinese replies.",
                lastConfirmedAtEpochMs = 10L,
                score = 420,
              ),
              RetrievedMemory(
                id = "memory-project",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Project uses the Gradle wrapper from the repo root.",
                lastConfirmedAtEpochMs = 11L,
                score = 360,
              ),
            ),
            matchedRecordCount = 2,
            omittedRecordCount = 0,
            trace = MemoryRecallTrace(
              queryTerms = listOf("chinese", "gradle"),
              selected = listOf(
                MemoryRecallSelectedTrace(
                  id = "memory-user",
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  score = 420,
                  matchedTerms = listOf("chinese"),
                  contentPreview = "Default to concise Chinese replies.",
                ),
                MemoryRecallSelectedTrace(
                  id = "memory-project",
                  kind = MemoryKind.PROJECT_FACT,
                  scope = MemoryScope.WORKSPACE,
                  score = 360,
                  matchedTerms = listOf("gradle"),
                  contentPreview = "Project uses the Gradle wrapper from the repo root.",
                ),
              ),
            ),
          ),
        ),
        toolDefinitions = listOf(
          AgentToolDefinition(
            name = "Read",
            description = "Read a file from the workspace.",
          ),
        ),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Older request."),
          RuntimeConversationMessage(
            RuntimeConversationRole.ASSISTANT,
            "This assistant message is intentionally long so the transcript window has to truncate it before rendering.",
          ),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest request."),
        ),
      ),
    )

    assertEquals("You are OpenCray for testing.", managed.baseSystemPrompt)
    assertEquals("Keep the session coherent.", managed.sessionPolicyText)
    assertTrue(managed.personalizationText.contains("display_name=Night Shift"))
    assertTrue(managed.memoryText.contains("Default to concise Chinese replies."))
    assertFalse(managed.memoryText.contains("Project uses the Gradle wrapper from the repo root."))
    assertEquals(
      "Compacted 1 older message(s) outside the active transcript window.",
      managed.compactionSummary?.text?.lineSequence()?.first(),
    )
    assertEquals(3, managed.report.sourceTranscriptMessageCount)
    assertEquals(2, managed.report.windowedTranscriptMessageCount)
    assertEquals(1, managed.report.omittedTranscriptMessageCount)
    assertEquals(1, managed.report.truncatedTranscriptMessageCount)
    assertEquals(0, managed.report.prunedTranscriptMessageCount)
    assertEquals(0, managed.report.rewrittenTranscriptMessageCount)
    assertFalse(managed.report.pruningSummaryIncluded)
    assertEquals(1, managed.report.compactedTranscriptMessageCount)
    assertTrue(managed.report.compactionSummaryIncluded)
    assertEquals(2, managed.report.matchedMemoryRecordCount)
    assertEquals(1, managed.report.injectedMemoryRecordCount)
    assertEquals(1, managed.report.omittedMemoryRecordCount)
    assertEquals(listOf("memory-user"), managed.report.memoryRecallTrace.selected.map { trace -> trace.id })
    assertEquals(listOf("memory-project"), managed.report.memoryRecallTrace.omitted.map { trace -> trace.id })
  }

  @Test
  fun prepareBuildsCompactionSummaryForOmittedToolActivity() {
    val manager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 2,
          maxCharsPerMessage = 120,
        ),
      ),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Search the repo."),
          RuntimeConversationMessage(
            RuntimeConversationRole.ASSISTANT,
            """tool_call Read {"file_path":"README.md"}""",
          ),
          RuntimeConversationMessage(
            RuntimeConversationRole.TOOL,
            """tool_result {"run_id":"run-1","task_id":"task-1","turn":1,"tool_name":"Read","status":"success","content_preview":"intro"}""",
          ),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "What did you find?"),
        ),
      ),
    )

    val summary = requireNotNull(managed.compactionSummary)

    assertEquals(2, summary.compactedMessageCount)
    assertEquals(1, summary.omittedAssistantMessageCount)
    assertEquals(1, summary.omittedToolMessageCount)
    assertTrue(summary.text.contains("Omitted tool activity: discovery=2."))
  }

  @Test
  fun prepareBuildsPruningSummaryBeforeWindowing() {
    val manager = ContextManager(
      contextPruner = ContextPruner(
        ContextPrunerConfig(
          maxToolChars = 128,
          maxToolLines = 4,
          maxAttachmentChars = 64,
          maxPreviewChars = 64,
        ),
      ),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 4,
          maxCharsPerMessage = 120,
        ),
      ),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo."),
          RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Repeated note."),
          RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Repeated note."),
          RuntimeConversationMessage(
            RuntimeConversationRole.TOOL,
            "data:image/png;base64," + "A".repeat(160),
          ),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue."),
        ),
      ),
    )

    val summary = requireNotNull(managed.pruningSummary)

    assertEquals(1, summary.removedMessageCount)
    assertEquals(1, summary.rewrittenMessageCount)
    assertEquals(1, summary.duplicateBackgroundMessageCount)
    assertEquals(1, summary.attachmentLikeMessageCount)
    assertTrue(summary.text.contains("removed=1, rewritten=1"))
    assertTrue(managed.transcriptWindow.messages.any { message ->
      message.content.startsWith("Attachment-like payload pruned from prompt.")
    })
    assertEquals(1, managed.report.prunedTranscriptMessageCount)
    assertEquals(1, managed.report.rewrittenTranscriptMessageCount)
    assertEquals(1, managed.report.duplicateBackgroundTranscriptMessageCount)
    assertEquals(1, managed.report.attachmentLikeTranscriptRewriteCount)
    assertTrue(managed.report.pruningSummaryIncluded)
  }

  private fun promptTask(): AgentTask = AgentTask(
    id = "task-context",
    type = AgentTaskType.PROMPT,
    input = "Summarize the repo changes.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 100L,
  )
}
