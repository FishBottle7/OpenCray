package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.NoOpOpenCrayAgentRuntimeEventSink
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryToolCallTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun toolCallTaskDoesNotRequireConfiguredLlm() {
    val workspaceRoot = temporaryFolder.newFolder("workspace").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call"))
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "tool-call-without-llm",
      type = AgentTaskType.TOOL_CALL,
      input =
        """{"type":"tool_call","tool_name":"TodoWrite","arguments":{"todos":[{"content":"Ship update entry","status":"in_progress"}]}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for direct tool call test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("Ship update entry"))
    assertEquals(1, runtimeFactory.todoStoreForSession(chatStore.loadState().activeSession.sessionId).snapshot().size)
  }

  @Test
  fun prepareSessionContextDoesNotAppendToolCallPayloadAsUserMessage() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-tool-call-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val toolPayload =
      """{"type":"tool_call","tool_name":"SkillsFind","arguments":{"query":"android"}}"""

    val prepared = runtimeFactory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-tool-call-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.TOOL_CALL,
      taskId = "tool-call-context",
      taskInput = toolPayload,
      transcriptStore = runtimeFactory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertFalse(
      prepared.sessionContext.conversation.any { message ->
        message.role == RuntimeConversationRole.USER && message.content == toolPayload
      },
    )
    assertFalse(
      runtimeFactory.transcriptStoreForSession(sessionId).snapshot().any { message ->
        message.role == RuntimeConversationRole.USER && message.content == toolPayload
      },
    )
  }

  @Test
  fun prepareSessionContextCanSkipAppendingPromptInputDuringApprovalResume() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-prompt-resume-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-prompt-resume-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendMessage(
      sessionId = sessionId,
      role = com.opencray.persistence.model.ChatTranscriptRole.USER,
      text = "Write the note.",
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = runtimeFactory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-prompt-resume-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "prompt-resume-context",
      taskInput = "Write the note.",
      transcriptStore = runtimeFactory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
      appendTaskInputToTranscript = false,
    )

    assertEquals(
      1,
      prepared.sessionContext.conversation.count { message ->
        message.role == RuntimeConversationRole.USER && message.content == "Write the note."
      },
    )
  }
}
