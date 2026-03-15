package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryIngestionCoordinatorTest {
  @Test
  fun ingestCompletedTurnWritesDeterministicCandidatesFromUserAssistantAndTools() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-1",
        input = """
          Please default to Simplified Chinese for explanations.
          Do not use git reset --hard in this repo.
        """.trimIndent(),
      ),
      result = successResult(taskId = "task-1"),
      assistantOutput = "Next I will run the targeted runtime tests.",
      toolObservations = listOf("Project uses the Gradle wrapper from the repo root."),
    )

    assertEquals(4, summary.writtenRecords.size)
    assertEquals(4, memoryStore.list().size)
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "user_preference" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "durable_instruction" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "project_fact" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "task_commitment" })
    assertTrue(memoryStore.list().any { record -> record.extensions["workspace_id"] == "workspace-main" })
  }

  @Test
  fun ingestCompletedTurnSkipsApprovalRequiredDenials() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-approval",
        input = "Please default to PowerShell commands.",
      ),
      result = ExecutionResult(
        taskId = "task-approval",
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
      assistantOutput = "Approval is required before Write can run.",
      toolObservations = listOf("Project uses Gradle."),
    )

    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun ingestCompletedTurnKeepsUserSideWritesButSkipsAssistantCommitmentsOnFailure() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-2",
      task = promptTask(
        id = "task-failure",
        input = "Please default to concise Chinese replies.",
      ),
      result = ExecutionResult(
        taskId = "task-failure",
        status = ExecutionStatus.FAILED,
        errorCode = "HTTP_500",
        errorMessage = "Gateway failed.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
      assistantOutput = "Next I will retry with the backup route.",
      toolObservations = emptyList(),
    )

    assertEquals(1, summary.writtenRecords.size)
    assertEquals(listOf("user_preference"), memoryStore.list().mapNotNull { record -> record.extensions["kind"] })
  }

  @Test
  fun ingestCompletedTurnResolvesOpenCommitmentWhenLaterEvidenceShowsCompletion() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "commitment-1",
          content = "run the targeted runtime tests",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_001L,
          tags = listOf("kind:task_commitment", "scope:session", "status:open"),
          extensions = mapOf(
            "kind" to "task_commitment",
            "scope" to "session",
            "status" to "open",
            "source_session_id" to "session-1",
            "ttl_ms" to (14L * 24L * 60L * 60L * 1000L).toString(),
            "last_confirmed_at_epoch_ms" to "1001",
          ),
        ),
      )
    }
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
      taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-2",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-2"),
      assistantOutput = "I ran the targeted runtime tests and updated the docs.",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("commitment-1"), summary.resolvedRecords.map { record -> record.id })
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "commitment-1" }.extensions["status"])
  }

  private fun promptTask(id: String, input: String): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1_000L,
  )

  private fun successResult(taskId: String): ExecutionResult = ExecutionResult(
    taskId = taskId,
    status = ExecutionStatus.SUCCESS,
    stdout = "Done.",
    startedAtEpochMs = 1_000L,
    finishedAtEpochMs = 1_001L,
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }
}
