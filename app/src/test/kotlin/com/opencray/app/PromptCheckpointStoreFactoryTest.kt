package com.opencray.app

import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySerializableGatewayMessage
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
  fun finalizationCheckpointKindIsTrackedButNotTreatedAsResumable() {
    assertEquals(false, PromptCheckpointKind.FINALIZATION_COMPLETE.isCheckpointResumeKind())
    assertEquals(false, PromptCheckpointKind.FINALIZATION_COMPLETE.isGeneralPromptResumeKind())
    assertEquals(
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
      PromptCheckpointKind.FINALIZATION_COMPLETE.toRuntimeCheckpointBoundaryOrNull(),
    )
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
  fun fileBackedStoreRepairsLegacyNestedResumeMetadataWhenReloaded() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-prompt-checkpoints-repair")
    val factory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val store = factory.forChatSession("session-1")
    store.upsert(
      PersistedPromptCheckpoint(
        sessionId = "session-1",
        runId = "run-1",
        taskId = "task-1",
        checkpointId = "checkpoint-1",
        checkpointKind = PromptCheckpointKind.TOOL_RESULT_COMMITTED,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "WebSearch",
        promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
          transcript = listOf(
            RuntimeConversationMessage(
              role = RuntimeConversationRole.TOOL,
              kind = RuntimeConversationMessageKind.TOOL_RESULT,
              content = sanitizedToolResultPayload(),
              toolResult = RuntimeConversationToolResult(
                toolName = "WebSearch",
                status = "success",
                isError = false,
              ),
            ),
          ),
        ),
      ),
    )

    val recordFile = factory.directoryForSession("session-1").resolve("runtime-prompt-checkpoints.json")
    recordFile.writeText(
      recordFile.readText().replace(
        encodedPayloadForRecord(sanitizedToolResultPayload()),
        encodedPayloadForRecord(legacyToolResultPayload()),
      ),
    )

    val restored = factory.forChatSession("session-1").get("task-1")

    assertNotNull(restored?.promptResumeState)
    assertFalse(
      restored?.promptResumeState
        ?.transcript
        ?.single()
        ?.content
        ?.contains(com.opencray.runtime.OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON)
        ?: true,
    )
    assertFalse(recordFile.readText().contains(encodedPayloadForRecord(legacyToolResultPayload())))
  }

  @Test
  fun fileBackedStoreListRepairUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val store = fileBackedPromptCheckpointStore(
      sessionId = "session-1",
      storage = storage,
    )
    val staleBeforeConcurrentCheckpoint = promptCheckpointsJson(
      recordVersion = 1L,
      updatedAtEpochMs = 1_000L,
      checkpointsJson = listOf(
        persistedCheckpointJson(
          sessionId = "session-1",
          runId = "run-older",
          taskId = "task-older",
          checkpointId = "checkpoint-older",
          checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_000L,
          toolName = " Read ",
          pendingMessageId = " pending-message ",
        ),
      ),
    )
    storage.writeText("runtime-prompt-checkpoints.json", staleBeforeConcurrentCheckpoint)
    storage.writeText(
      "runtime-prompt-checkpoints.json",
      promptCheckpointsJson(
        recordVersion = 2L,
        updatedAtEpochMs = 2_000L,
        checkpointsJson = listOf(
          persistedCheckpointJson(
            sessionId = "session-1",
            runId = "run-older",
            taskId = "task-older",
            checkpointId = "checkpoint-older",
            checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            toolName = " Read ",
            pendingMessageId = " pending-message ",
          ),
          persistedCheckpointJson(
            sessionId = "session-1",
            runId = "run-concurrent",
            taskId = "task-concurrent",
            checkpointId = "checkpoint-concurrent",
            checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
            createdAtEpochMs = 2_000L,
            updatedAtEpochMs = 2_000L,
            toolName = "Bash",
            pendingMessageId = null,
          ),
        ),
      ),
    )
    val updateCallsBeforeList = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentCheckpoint)
    val checkpoints = store.list()

    assertEquals(updateCallsBeforeList + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    assertEquals(
      listOf("task-concurrent", "task-older"),
      checkpoints.map(PersistedPromptCheckpoint::taskId),
    )
    val olderCheckpoint = checkpoints.single { checkpoint -> checkpoint.taskId == "task-older" }
    assertEquals("Read", olderCheckpoint.toolName)
    assertEquals("pending-message", olderCheckpoint.pendingMessageId)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("task-concurrent", "task-older"),
      store.list().map(PersistedPromptCheckpoint::taskId),
    )
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

  private fun sanitizedToolResultPayload(): String = """
    {"run_id":"run-1","task_id":"task-1","turn":1,"tool_name":"WebSearch","status":"success","content":"done","metadata":{"sourceUrls":"https://example.com"}}
  """.trimIndent()

  private fun legacyToolResultPayload(): String = """
    {"run_id":"run-1","task_id":"task-1","turn":1,"tool_name":"WebSearch","status":"success","content":"done","metadata":{"sourceUrls":"https://example.com","opencray_prompt_resume_json":"{\"turnIndex\":0,\"toolCallCount\":0}","opencray_prompt_checkpoint_boundary":"tool_result_committed"}}
  """.trimIndent()

  private fun encodedPayloadForRecord(payload: String): String = payload
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

  private fun promptCheckpointsJson(
    recordVersion: Long,
    updatedAtEpochMs: Long,
    checkpointsJson: List<String>,
  ): String =
    """
    {
      "schemaVersion": 1,
      "recordVersion": $recordVersion,
      "updatedAtEpochMs": $updatedAtEpochMs,
      "checkpoints": [
        ${checkpointsJson.joinToString(separator = ",\n        ")}
      ]
    }
    """.trimIndent()

  private fun persistedCheckpointJson(
    sessionId: String,
    runId: String,
    taskId: String,
    checkpointId: String,
    checkpointKind: PromptCheckpointKind,
    createdAtEpochMs: Long,
    updatedAtEpochMs: Long,
    toolName: String?,
    pendingMessageId: String?,
  ): String {
    val encodedToolName = toolName?.let { value -> "\"${escapeJson(value)}\"" } ?: "null"
    val encodedPendingMessageId = pendingMessageId
      ?.let { value -> "\"${escapeJson(value)}\"" }
      ?: "null"
    return """
      {
        "schemaVersion": 1,
        "sessionId": "${escapeJson(sessionId)}",
        "runId": "${escapeJson(runId)}",
        "taskId": "${escapeJson(taskId)}",
        "checkpointId": "${escapeJson(checkpointId)}",
        "checkpointKind": "${checkpointKind.name}",
        "createdAtEpochMs": $createdAtEpochMs,
        "updatedAtEpochMs": $updatedAtEpochMs,
        "toolName": $encodedToolName,
        "pendingMessageId": $encodedPendingMessageId,
        "isHighRisk": false,
        "promptCheckpointBoundary": null,
        "promptResumeState": null,
        "subAgentApprovedToolName": null,
        "subAgentPromptResumeState": null,
        "subAgentIsHighRisk": null,
        "subAgentAgentId": null,
        "subAgentChildRunId": null,
        "subAgentChildTaskId": null
      }
    """.trimIndent()
  }

  private fun escapeJson(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    fun returnStaleTextOnNextRead(staleText: String?) {
      this.staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }
}
