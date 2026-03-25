package com.opencray.app

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
}
