package com.opencray.app

import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryApprovalResumeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun approvalContinuationForExecutionFallsBackToDurableCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-approval-resume").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-resume-factory"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val checkpointFactory = com.opencray.app.inMemoryPromptCheckpointStoreFactoryForTest()
    val checkpointStore = checkpointFactory.forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Read",
        promptResumeState = resumeState,
      ),
    )

    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      approvalRegistry = AgentTaskApprovalRegistry(),
      promptCheckpointStoreProvider = checkpointFactory::forChatSession,
    )

    val continuation = factory.approvalContinuationForExecution(
      sessionId = sessionId,
      taskId = "task-1",
    )

    assertEquals("Read", continuation.grant?.toolName)
    assertEquals(resumeState, continuation.grant?.promptResumeState)
    assertNull(continuation.rejection)
    assertTrue(factory.promptCheckpointStoreForSession(sessionId).get("task-1") != null)
  }

  @Test
  fun generalPromptResumeStateForExecutionFallsBackToDurableCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-general-resume").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-general-resume-factory"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val checkpointFactory = com.opencray.app.inMemoryPromptCheckpointStoreFactoryForTest()
    val checkpointStore = checkpointFactory.forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 2, toolCallCount = 3)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "LS",
        promptResumeState = resumeState,
      ),
    )

    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      approvalRegistry = AgentTaskApprovalRegistry(),
      promptCheckpointStoreProvider = checkpointFactory::forChatSession,
    )

    assertEquals(
      resumeState,
      factory.generalPromptResumeStateForExecution(
        sessionId = sessionId,
        taskId = "task-1",
      ),
    )
  }
}
