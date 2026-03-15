package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {
  @Test
  fun assembleBuildsNamedSystemAndTaskLayers() {
    val assembler = PromptAssembler(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 2,
          maxCharsPerMessage = 48,
        ),
      ),
    )

    val prompt = assembler.assemble(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(
          sessionPolicyText = "Keep the current session aligned with earlier decisions.",
          soulProfile = RuntimeSoulProfile(
            presetName = "BUILDER",
            displayName = "Night Shift",
            customGuidance = "Be terse and implementation-first.",
          ),
        ),
        toolDefinitions = listOf(
          AgentToolDefinition(
            name = "workspace_read_file",
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

    assertTrue(prompt.systemPrompt.contains("[Identity]"))
    assertTrue(prompt.systemPrompt.contains("[Runtime Rules]"))
    assertTrue(prompt.systemPrompt.contains("[Session Policy]"))
    assertTrue(prompt.systemPrompt.contains("[Personalization]"))
    assertTrue(prompt.systemPrompt.contains("display_name=Night Shift"))
    assertTrue(prompt.taskPrompt.contains("[Tool Protocol]"))
    assertTrue(prompt.taskPrompt.contains("On each turn, return exactly one JSON action"))
    assertTrue(prompt.taskPrompt.contains("the runtime will execute it, append the tool result"))
    assertTrue(prompt.taskPrompt.contains("If you need multiple tools, call only the next tool now"))
    assertTrue(prompt.taskPrompt.contains("reason or justification"))
    assertTrue(prompt.taskPrompt.contains("it must not include a final answer"))
    assertTrue(prompt.taskPrompt.contains("Available tools:"))
    assertTrue(prompt.taskPrompt.contains("[Task Context]"))
    assertTrue(prompt.taskPrompt.contains("task_id=task-context"))
    assertTrue(prompt.taskPrompt.contains("Omitted 1 older message(s)"))
    assertEquals(3, prompt.report.sourceTranscriptMessageCount)
    assertEquals(2, prompt.report.windowedTranscriptMessageCount)
    assertEquals(2, prompt.report.transcriptMessageCount)
    assertEquals(1, prompt.report.omittedTranscriptMessageCount)
    assertEquals(1, prompt.report.truncatedTranscriptMessageCount)
    assertEquals(0, prompt.report.injectedMemoryRecordCount)
  }

  @Test
  fun assembleOmitsOptionalLayersWhenEmpty() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "Base identity.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("[Identity]"))
    assertFalse(prompt.systemPrompt.contains("[Session Policy]"))
    assertFalse(prompt.systemPrompt.contains("[Personalization]"))
    assertTrue(prompt.taskPrompt.contains("No prior conversation context."))
    assertEquals(0, prompt.report.sourceTranscriptMessageCount)
    assertEquals(0, prompt.report.windowedTranscriptMessageCount)
    assertEquals(0, prompt.report.transcriptMessageCount)
    assertEquals(0, prompt.report.injectedMemoryRecordCount)
  }

  @Test
  fun assembleOmitsHostOnlyTaskMetadataFromPrompt() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      PromptAssemblyInput(
        task = promptTask().copy(
          metadata = mapOf(
            "chatMode" to "AUTO",
            "_host.pendingMessageId" to "assistant-1",
          ),
        ),
        baseSystemPrompt = "Base identity.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("chatMode=AUTO"))
    assertFalse(prompt.taskPrompt.contains("_host.pendingMessageId"))
    assertFalse(prompt.taskPrompt.contains("assistant-1"))
  }

  @Test
  fun assembleInjectsRetrievedMemoryAsDedicatedContextLayer() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "Base identity.",
        sessionContext = AgentRuntimeSessionContext(
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
            matchedRecordCount = 3,
            omittedRecordCount = 1,
          ),
        ),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Retrieved Memory]"))
    assertTrue(prompt.taskPrompt.contains("Default to concise Chinese replies."))
    assertTrue(prompt.taskPrompt.contains("Project uses the Gradle wrapper from the repo root."))
    assertTrue(prompt.taskPrompt.contains("Omitted 1 additional memory record(s) due to recall budget."))
    assertTrue(prompt.taskPrompt.indexOf("[Retrieved Memory]") < prompt.taskPrompt.indexOf("[Tool Protocol]"))
    assertEquals(3, prompt.report.matchedMemoryRecordCount)
    assertEquals(2, prompt.report.injectedMemoryRecordCount)
    assertEquals(1, prompt.report.omittedMemoryRecordCount)
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
