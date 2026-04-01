package com.opencray.app

import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySerializableGatewayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromptCheckpointStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsLatestCheckpointPerTask() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-prompt-checkpoints")
    val firstStore = FileBackedPromptCheckpointStoreFactory(runtimeRoot).forChatSession("session-1")

    firstStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Read",
        promptResumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
      ),
    )
    firstStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-2",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 150L,
        updatedAtEpochMs = 200L,
        toolName = "Read",
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        promptResumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
      ),
    )
    firstStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-2",
        taskId = "task-2",
        checkpointId = "checkpoint-3",
        checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
        createdAtEpochMs = 300L,
        updatedAtEpochMs = 300L,
        toolName = "Bash",
      ),
    )

    val restoredStore = FileBackedPromptCheckpointStoreFactory(runtimeRoot).forChatSession("session-1")
    val checkpoints = restoredStore.list()

    assertEquals(2, checkpoints.size)
    assertEquals(
      listOf("task-2", "task-1"),
      checkpoints.map(PersistedPromptCheckpoint::taskId),
    )
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, restoredStore.get("task-1")?.checkpointKind)
    assertEquals(
      OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
      restoredStore.get("task-1")?.promptCheckpointBoundary,
    )
    assertEquals(PromptCheckpointKind.REJECTED_PENDING_RESUME, restoredStore.get("task-2")?.checkpointKind)
  }

  @Test
  fun fileBackedStoreRetainsAndRemovesKnownTasks() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-prompt-checkpoints-retain")
    val store = FileBackedPromptCheckpointStoreFactory(runtimeRoot).forChatSession("session-1")

    store.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
      ),
    )
    store.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-2",
        taskId = "task-2",
        checkpointId = "checkpoint-2",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 200L,
        updatedAtEpochMs = 200L,
      ),
    )

    store.retainKnownTasks(setOf("task-2"))

    assertNull(store.get("task-1"))
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, store.get("task-2")?.checkpointKind)
  }

  @Test
  fun fileBackedStorePersistsResponsesContinuationStateInsidePromptResumeState() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-prompt-checkpoints-responses")
    val store = FileBackedPromptCheckpointStoreFactory(runtimeRoot).forChatSession("session-1")

    store.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Write",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
          responsesPreviousResponseId = "resp_123",
          responsesProviderLineageId = "lineage_123",
          responsesLineageTrusted = true,
          responsesPendingMessages = listOf(
            OpenCraySerializableGatewayMessage(
              role = "TOOL",
              toolResult = com.opencray.runtime.OpenCraySerializableGatewayToolResult(
                toolCallId = "call_1",
                toolName = "Write",
                content = """{"status":"success"}""",
              ),
            ),
          ),
        ),
      ),
    )

    val restored = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
      .forChatSession("session-1")
      .get("task-1")

    assertNotNull(restored?.promptResumeState)
    val resumeState = restored?.promptResumeState!!
    assertEquals("resp_123", resumeState.responsesPreviousResponseId)
    assertEquals("lineage_123", resumeState.responsesProviderLineageId)
    assertEquals(true, resumeState.responsesLineageTrusted)
    assertEquals(1, resumeState.responsesPendingMessages.size)
    assertEquals("TOOL", resumeState.responsesPendingMessages.single().role)
    assertEquals("call_1", resumeState.responsesPendingMessages.single().toolResult?.toolCallId)
  }

  @Test
  fun fileBackedStoreConsumesCheckpointOnlyOnceAcrossStoreInstances() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-prompt-checkpoints-consume")
    val factory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val firstStore = factory.forChatSession("session-1")
    val secondStore = factory.forChatSession("session-1")

    firstStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Read",
      ),
    )

    val consumed = firstStore.consume(
      taskId = "task-1",
      checkpointKinds = setOf(PromptCheckpointKind.APPROVED_PENDING_RESUME),
    )

    assertEquals("checkpoint-1", consumed?.checkpointId)
    assertNull(
      secondStore.consume(
        taskId = "task-1",
        checkpointKinds = setOf(PromptCheckpointKind.APPROVED_PENDING_RESUME),
      ),
    )
    assertNull(factory.forChatSession("session-1").get("task-1"))
  }

  @Test
  fun fileBackedStoreRestoresSubAgentApprovalIdentityFromCheckpoint() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-prompt-checkpoints-subagent-identity")
    val store = FileBackedPromptCheckpointStoreFactory(runtimeRoot).forChatSession("session-1")

    store.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Read",
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED,
        promptResumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
        subAgentApprovedToolName = "Read",
        subAgentPromptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
        subAgentIsHighRisk = true,
        subAgentAgentId = "child-agent-1",
        subAgentChildRunId = "child-run-1",
        subAgentChildTaskId = "child-task-1",
      ),
    )

    val restored = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
      .forChatSession("session-1")
      .get("task-1")
      ?.toApprovalGrantOrNull()

    assertEquals(OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED, restored?.promptCheckpointBoundary)
    assertEquals("Read", restored?.subAgentApprovalResume?.approvedToolName)
    assertEquals(true, restored?.subAgentApprovalResume?.isHighRisk)
    assertEquals("child-agent-1", restored?.subAgentApprovalResume?.agentId)
    assertEquals("child-run-1", restored?.subAgentApprovalResume?.childRunId)
    assertEquals("child-task-1", restored?.subAgentApprovalResume?.childTaskId)
  }
}
