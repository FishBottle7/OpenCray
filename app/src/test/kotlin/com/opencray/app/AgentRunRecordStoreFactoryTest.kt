package com.opencray.app

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.memory.MemoryStewardshipAction
import com.opencray.runtime.memory.MemoryStewardshipPlanGraph
import com.opencray.runtime.memory.MemoryStewardshipPlanGraphEdge
import com.opencray.runtime.memory.MemoryStewardshipPlanGraphNode
import com.opencray.runtime.memory.MemoryStewardshipPlanStep
import com.opencray.runtime.memory.MemoryStewardshipPlanStepOutcome
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecordStoreFactoryTest {
  @Test
  fun memoryWriteEventRoundTripsStewardshipPlanGraph() {
    val event = OpenCrayMemoryWriteEvent(
      runId = "run-memory",
      taskId = "task-memory",
      resolvedRecordIds = listOf("fact-old"),
      stewardshipPlanSteps = listOf(
        MemoryStewardshipPlanStep(
          action = MemoryStewardshipAction.MERGE_RECORD_WITH_CANDIDATE,
          outcome = MemoryStewardshipPlanStepOutcome.APPLIED,
          recordId = "fact-old",
          candidateIndex = 0,
          producedRecordId = "fact-new",
          reason = "record_merged_into_candidate",
        ),
      ),
      stewardshipPlanGraph = MemoryStewardshipPlanGraph(
        nodes = listOf(
          MemoryStewardshipPlanGraphNode(
            id = "record:fact-old",
            kind = "record",
            label = "fact-old",
            recordId = "fact-old",
          ),
          MemoryStewardshipPlanGraphNode(
            id = "step:0",
            kind = "step",
            label = "merge_record_with_candidate",
            action = "merge_record_with_candidate",
            outcome = "applied",
            recordId = "fact-old",
            candidateIndex = 0,
            producedRecordId = "fact-new",
            reason = "record_merged_into_candidate",
          ),
          MemoryStewardshipPlanGraphNode(
            id = "record:fact-new",
            kind = "record",
            label = "fact-new",
            recordId = "fact-new",
          ),
        ),
        edges = listOf(
          MemoryStewardshipPlanGraphEdge(
            from = "record:fact-old",
            to = "step:0",
            kind = "input_record",
          ),
          MemoryStewardshipPlanGraphEdge(
            from = "step:0",
            to = "record:fact-new",
            kind = "produces_record",
          ),
        ),
      ),
      emittedAtEpochMs = 3_000L,
    )

    val restored = runtimeEventForTest(
      persistedRecordForTest(event),
    ) as OpenCrayMemoryWriteEvent

    assertEquals(listOf("fact-old"), restored.resolvedRecordIds)
    assertEquals(
      listOf(MemoryStewardshipAction.MERGE_RECORD_WITH_CANDIDATE),
      restored.stewardshipPlanSteps.map(MemoryStewardshipPlanStep::action),
    )
    assertEquals(
      setOf("record:fact-old", "step:0", "record:fact-new"),
      restored.stewardshipPlanGraph.nodes.map { node -> node.id }.toSet(),
    )
    assertEquals(
      setOf("input_record", "produces_record"),
      restored.stewardshipPlanGraph.edges.map { edge -> edge.kind }.toSet(),
    )
  }

  @Test
  fun successfulToolResultRoundTripsThroughPersistedRecordWithFullContent() {
    val event = OpenCrayToolResultEvent(
      runId = "run-tool",
      taskId = "task-tool",
      turn = 1,
      call = AgentToolCall(
        toolName = "Read",
        reason = "Inspect README",
      ),
      result = AgentToolResult(
        toolName = "Read",
        status = AgentToolResultStatus.SUCCESS,
        content = "README full content from durable storage.",
      ),
      emittedAtEpochMs = 1_234L,
    )

    val restored = runtimeEventForTest(
      persistedRecordForTest(event),
    ) as OpenCrayToolResultEvent

    assertEquals("run-tool", restored.runId)
    assertEquals("task-tool", restored.taskId)
    assertEquals("Read", restored.call.toolName)
    assertEquals("Inspect README", restored.call.reason)
    assertEquals(AgentToolResultStatus.SUCCESS, restored.result.status)
    assertEquals("README full content from durable storage.", restored.result.content)
  }

  @Test
  fun subagentEventRoundTripsThroughPersistedRecord() {
    val event = OpenCraySubAgentEvent(
      runId = "run-parent",
      taskId = "task-parent",
      phase = OpenCraySubAgentPhase.COMPLETED,
      childRunId = "run-child",
      childTaskId = "task-child",
      label = "Inspect README",
      subagentType = "researcher",
      contextMode = "minimal",
      depth = 1,
      summary = "README inspection finished.",
      executionState = SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
      continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
      resumable = true,
      requiresUserAction = true,
      isHighRisk = true,
      turn = 2,
      emittedAtEpochMs = 1_234L,
    )

    val restored = runtimeEventForTest(
      persistedRecordForTest(event),
    ) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.COMPLETED, restored.phase)
    assertEquals("run-parent", restored.runId)
    assertEquals("task-parent", restored.taskId)
    assertEquals("run-child", restored.childRunId)
    assertEquals("task-child", restored.childTaskId)
    assertEquals("Inspect README", restored.label)
    assertEquals("researcher", restored.subagentType)
    assertEquals("minimal", restored.contextMode)
    assertEquals(1, restored.depth)
    assertEquals("README inspection finished.", restored.summary)
    assertEquals(SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL, restored.executionState)
    assertEquals(SubAgentContinuationKind.PROMPT_RESUME, restored.continuationKind)
    assertTrue(restored.resumable)
    assertTrue(restored.requiresUserAction)
    assertTrue(restored.isHighRisk)
    assertEquals(2, restored.turn)
    assertEquals(1_234L, restored.emittedAtEpochMs)
  }

  @Test
  fun persistedSubagentEventFallsBackToSafeDefaults() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUBAGENT,
      runId = "run-parent",
      taskId = "task-parent",
      emittedAtEpochMs = 8L,
    ).let(::runtimeEventForTest) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.STARTED, restored.phase)
    assertEquals("run-parent", restored.childRunId)
    assertEquals("task-parent", restored.childTaskId)
    assertEquals("Task", restored.label)
    assertEquals("general-purpose", restored.subagentType)
    assertEquals("delegated", restored.contextMode)
    assertEquals(1, restored.depth)
    assertEquals(SubAgentExecutionState.RUNNING, restored.executionState)
    assertEquals(SubAgentContinuationKind.NONE, restored.continuationKind)
    assertFalse(restored.resumable)
    assertFalse(restored.requiresUserAction)
    assertFalse(restored.isHighRisk)
    assertTrue(restored.summary == null)
  }

  @Test
  fun resumedSubagentEventDefaultsToRunningExecutionState() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUBAGENT,
      runId = "run-parent",
      taskId = "task-parent",
      phase = OpenCraySubAgentPhase.RESUMED.name,
      emittedAtEpochMs = 9L,
    ).let(::runtimeEventForTest) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.RESUMED, restored.phase)
    assertEquals(SubAgentExecutionState.RUNNING, restored.executionState)
    assertEquals(SubAgentContinuationKind.NONE, restored.continuationKind)
    assertFalse(restored.resumable)
    assertFalse(restored.requiresUserAction)
  }

  @Test
  fun backgroundQueuedSubagentEventDefaultsToBackgroundResumeContinuation() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUBAGENT,
      runId = "run-parent",
      taskId = "task-parent",
      phase = OpenCraySubAgentPhase.STARTED.name,
      subAgentExecutionState = "background_queued",
      emittedAtEpochMs = 10L,
    ).let(::runtimeEventForTest) as OpenCraySubAgentEvent

    assertEquals(OpenCraySubAgentPhase.STARTED, restored.phase)
    assertEquals(SubAgentExecutionState.BACKGROUND_QUEUED, restored.executionState)
    assertEquals(SubAgentContinuationKind.BACKGROUND_RESUME, restored.continuationKind)
    assertTrue(restored.resumable)
    assertFalse(restored.requiresUserAction)
    assertFalse(restored.isHighRisk)
  }

  @Test
  fun supplementEventRoundTripsThroughPersistedRecordMetadata() {
    val event = OpenCraySupplementEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 3,
      entryId = "supplement-1",
      text = "Also inspect the docs.",
      checkpoint = "post_tool_pre_model",
      metadata = mapOf(
        "checkpointKind" to "general_resume",
        "promptResumeState" to "encoded-state",
      ),
      emittedAtEpochMs = 2_345L,
    )

    val restored = runtimeEventForTest(
      persistedRecordForTest(event),
    ) as OpenCraySupplementEvent

    assertEquals("run-1", restored.runId)
    assertEquals("task-1", restored.taskId)
    assertEquals(3, restored.turn)
    assertEquals("supplement-1", restored.entryId)
    assertEquals("Also inspect the docs.", restored.text)
    assertEquals("post_tool_pre_model", restored.checkpoint)
    assertEquals(event.metadata, restored.metadata)
    assertEquals(2_345L, restored.emittedAtEpochMs)
  }

  @Test
  fun persistedSupplementEventDefaultsMetadataToEmptyMap() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.SUPPLEMENT,
      runId = "run-1",
      taskId = "task-1",
      turn = 2,
      emittedAtEpochMs = 44L,
      supplementEntryId = "supplement-legacy",
      text = "Legacy supplement",
    ).let(::runtimeEventForTest) as OpenCraySupplementEvent

    assertEquals("supplement-legacy", restored.entryId)
    assertEquals("Legacy supplement", restored.text)
    assertEquals("turn_start", restored.checkpoint)
    assertTrue(restored.metadata.isEmpty())
  }

  @Test
  fun checkpointEventsDoNotProjectIntoRuntimeEvents() {
    val restored = PersistedAgentRunEvent(
      kind = PersistedAgentRunEventKind.CHECKPOINT,
      runId = "run-checkpoint",
      taskId = "task-checkpoint",
      emittedAtEpochMs = 55L,
    ).toRuntimeEventOrNull()

    assertNull(restored)
  }

  @Test
  fun listRepairUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val store = fileBackedAgentRunRecordStore(storage)

    val staleBeforeConcurrentRun = runtimeRunsJson(
      recordVersion = 1L,
      updatedAtEpochMs = 1_000L,
      runsJson = listOf(
        persistedRunJson(
          runId = "run-older",
          taskId = "task-older",
          acceptedAtEpochMs = 1_000L,
          pendingMessageId = " pending-message ",
          managedProcessIds = listOf(" proc-a ", "proc-a", ""),
        ),
      ),
    )
    storage.writeText("runtime-runs.json", staleBeforeConcurrentRun)
    storage.writeText(
      "runtime-runs.json",
      runtimeRunsJson(
        recordVersion = 2L,
        updatedAtEpochMs = 2_000L,
        runsJson = listOf(
          persistedRunJson(
            runId = "run-older",
            taskId = "task-older",
            acceptedAtEpochMs = 1_000L,
            pendingMessageId = " pending-message ",
            managedProcessIds = listOf(" proc-a ", "proc-a", ""),
          ),
          persistedRunJson(
            runId = "run-concurrent",
            taskId = "task-concurrent",
            acceptedAtEpochMs = 2_000L,
            pendingMessageId = null,
            managedProcessIds = listOf("proc-live"),
          ),
        ),
      ),
    )
    val updateCallsBeforeList = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentRun)
    val runs = store.list()

    assertEquals(updateCallsBeforeList + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    assertEquals(listOf("run-concurrent", "run-older"), runs.map(PersistedAgentRunRecord::runId))
    val olderRun = runs.single { run -> run.runId == "run-older" }
    assertEquals("pending-message", olderRun.pendingMessageId)
    assertEquals(listOf("proc-a"), olderRun.managedProcessIds)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("run-concurrent", "run-older"),
      store.list().map(PersistedAgentRunRecord::runId),
    )
  }

  private fun runtimeRunsJson(
    recordVersion: Long,
    updatedAtEpochMs: Long,
    runsJson: List<String>,
  ): String =
    """
    {
      "schemaVersion": 1,
      "recordVersion": $recordVersion,
      "updatedAtEpochMs": $updatedAtEpochMs,
      "runs": [
        ${runsJson.joinToString(separator = ",\n        ")}
      ]
    }
    """.trimIndent()

  private fun persistedRunJson(
    runId: String,
    taskId: String,
    acceptedAtEpochMs: Long,
    pendingMessageId: String?,
    managedProcessIds: List<String>,
  ): String {
    val encodedPendingMessageId = pendingMessageId
      ?.let { value -> "\"${escapeJson(value)}\"" }
      ?: "null"
    val encodedManagedProcessIds = managedProcessIds
      .joinToString(prefix = "[", postfix = "]") { value -> "\"${escapeJson(value)}\"" }
    return """
      {
        "runId": "${escapeJson(runId)}",
        "taskId": "${escapeJson(taskId)}",
        "acceptedAtEpochMs": $acceptedAtEpochMs,
        "pendingMessageId": $encodedPendingMessageId,
        "managedProcessIds": $encodedManagedProcessIds,
        "detachedTask": null,
        "lastResult": null,
        "lastEvent": null
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
