package com.opencray.app

import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        promptResumeState = resumeState,
        subAgentApprovedToolName = "Read",
        subAgentPromptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
        subAgentIsHighRisk = true,
        subAgentAgentId = "child-agent-1",
        subAgentChildRunId = "child-run-1",
        subAgentChildTaskId = "child-task-1",
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
    assertEquals(
      OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
      continuation.grant?.promptCheckpointBoundary,
    )
    assertEquals(resumeState, continuation.grant?.promptResumeState)
    assertEquals("Read", continuation.grant?.subAgentApprovalResume?.approvedToolName)
    assertEquals(true, continuation.grant?.subAgentApprovalResume?.isHighRisk)
    assertEquals("child-agent-1", continuation.grant?.subAgentApprovalResume?.agentId)
    assertEquals("child-run-1", continuation.grant?.subAgentApprovalResume?.childRunId)
    assertEquals("child-task-1", continuation.grant?.subAgentApprovalResume?.childTaskId)
    assertNull(continuation.rejection)
    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      factory.promptCheckpointStoreForSession(sessionId).get("task-1")?.checkpointKind,
    )
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
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED,
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
    assertEquals(
      OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED,
      factory.promptResumeCheckpointBoundaryForExecution(
        sessionId = sessionId,
        taskId = "task-1",
      ),
    )
    assertEquals(
      PromptCheckpointKind.GENERAL_RESUME,
      factory.promptCheckpointStoreForSession(sessionId).get("task-1")?.checkpointKind,
    )
  }

  @Test
  fun preModelRequestResumeStateIsAlsoAcceptedForExecution() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-pre-model-resume").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-pre-model-resume-factory"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val checkpointFactory = com.opencray.app.inMemoryPromptCheckpointStoreFactoryForTest()
    val checkpointStore = checkpointFactory.forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 4, toolCallCount = 2)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-pre-model",
        checkpointKind = PromptCheckpointKind.PRE_MODEL_REQUEST,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
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
    assertEquals(
      OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
      factory.promptResumeCheckpointBoundaryForExecution(
        sessionId = sessionId,
        taskId = "task-1",
      ),
    )
  }

  @Test
  fun finalizationCheckpointIsNotReturnedAsResumeInput() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-finalization-non-resume").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-finalization-non-resume"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val checkpointFactory = com.opencray.app.inMemoryPromptCheckpointStoreFactoryForTest()
    val checkpointStore = checkpointFactory.forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 5, toolCallCount = 2)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-finalization",
        checkpointKind = PromptCheckpointKind.FINALIZATION_COMPLETE,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
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

    assertNull(
      factory.generalPromptResumeStateForExecution(
        sessionId = sessionId,
        taskId = "task-1",
      ),
    )
    assertNull(
      factory.promptResumeCheckpointBoundaryForExecution(
        sessionId = sessionId,
        taskId = "task-1",
      ),
    )
  }

  @Test
  fun rejectionContinuationForExecutionFallsBackToDurableCheckpointBoundary() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-rejected-resume").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-rejected-resume-factory"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val checkpointFactory = com.opencray.app.inMemoryPromptCheckpointStoreFactoryForTest()
    val checkpointStore = checkpointFactory.forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 3, toolCallCount = 2)

    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-rejected",
        checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Write",
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
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

    assertNull(continuation.grant)
    assertEquals("Write", continuation.rejection?.toolName)
    assertEquals(
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      continuation.rejection?.promptCheckpointBoundary,
    )
    assertEquals(resumeState, continuation.rejection?.promptResumeState)
  }
}
