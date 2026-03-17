package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.memory.SoulMemoryIntent
import com.opencray.runtime.memory.SoulMemoryIntentInterpretation
import com.opencray.runtime.memory.SoulMemoryIntentInterpreter
import com.opencray.runtime.memory.SoulMemoryIntentRequest
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentInterpreter
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryIngestionCoordinatorTest {
  @Test
  fun flushBeforeCompactionWritesDurableCandidatesOnlyOncePerTranscriptSignature() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
    )
    val conversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
    ) + (1..12).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-flush",
      taskId = "task-flush-1",
      conversation = conversation,
    )
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-flush",
      taskId = "task-flush-2",
      conversation = conversation,
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertTrue(first.writtenRecords.isNotEmpty())
    assertEquals(first.writtenRecords.size, first.trace.writtenRecordCount)
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "user_preference" })
    assertTrue(memoryStore.list().any { record -> record.extensions["kind"] == "project_fact" })
    assertEquals(MemoryFlushOutcome.ALREADY_FLUSHED, second.trace.outcome)
    assertTrue(second.writtenRecords.isEmpty())
    assertEquals(first.writtenRecords.size, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionSkipsExpandedWindowWhenNoNewDurableCandidatesAppear() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
    )
    val baseConversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
    ) + (1..12).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-flush",
      taskId = "task-expanded-1",
      conversation = baseConversation,
    )
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-flush",
      taskId = "task-expanded-2",
      conversation = baseConversation + conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message 13 to keep the active transcript window bounded.",
      ),
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertEquals(MemoryFlushOutcome.ALREADY_FLUSHED, second.trace.outcome)
    assertTrue(second.writtenRecords.isEmpty())
    assertEquals(first.writtenRecords.size, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionWritesOnlyDeltaWhenExpandedWindowAddsNewCandidate() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
    )
    val baseConversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses pnpm workspaces for package management.",
      ),
    ) + (1..11).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-delta",
      taskId = "task-delta-1",
      conversation = baseConversation,
    )
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-expanded-delta",
      taskId = "task-delta-2",
      conversation = baseConversation + conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message 12 to keep the active transcript window bounded.",
      ),
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertEquals(MemoryFlushOutcome.WRITTEN, second.trace.outcome)
    assertEquals(1, second.writtenRecords.size)
    assertTrue(second.writtenRecords.single().content.contains("pnpm workspaces"))
    assertEquals(first.writtenRecords.size + 1, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionAllowsRewriteAfterBackingMemoryIsCleared() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
    )
    val conversation = listOf(
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Please default to Simplified Chinese for explanations.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Do not use git reset --hard in this repo.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Project uses the Gradle wrapper from the repo root.",
      ),
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "以后都用 PowerShell 命令。",
      ),
    ) + (1..12).map { index ->
      conversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Padding user message $index to keep the active transcript window bounded.",
      )
    }

    val first = coordinator.flushBeforeCompaction(
      sessionId = "session-store-reset",
      taskId = "task-store-reset-1",
      conversation = conversation,
    )
    memoryStore.clear()
    val second = coordinator.flushBeforeCompaction(
      sessionId = "session-store-reset",
      taskId = "task-store-reset-2",
      conversation = conversation,
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, first.trace.outcome)
    assertEquals(MemoryFlushOutcome.WRITTEN, second.trace.outcome)
    assertTrue(second.writtenRecords.isNotEmpty())
    assertEquals(second.writtenRecords.size, memoryStore.list().size)
  }

  @Test
  fun flushBeforeCompactionSkipsWhenTranscriptWindowDoesNotOmitEnoughHistory() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(memoryStore = memoryStore)

    val summary = coordinator.flushBeforeCompaction(
      sessionId = "session-no-pressure",
      taskId = "task-no-pressure",
      conversation = listOf(
        conversationMessage(RuntimeConversationRole.USER, "Please keep responses short."),
        conversationMessage(RuntimeConversationRole.ASSISTANT, "I will keep them short."),
        conversationMessage(RuntimeConversationRole.USER, "Continue with the fix."),
      ),
    )

    assertEquals(MemoryFlushOutcome.NO_PRESSURE, summary.trace.outcome)
    assertTrue(summary.writtenRecords.isEmpty())
    assertTrue(memoryStore.list().isEmpty())
  }

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

  @Test
  fun ingestCompletedTurnReportsReaffirmedCommitmentWhenSemanticResolverKeepsItAlive() {
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "commitment-reaffirm",
          content = "stabilize the flaky runtime test",
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
      taskCommitmentResolver = TaskCommitmentResolver(
        store = memoryStore,
        clock = { 2_000L },
        intentInterpreter = FixedTaskCommitmentIntentInterpreter(
          TaskCommitmentIntentInterpretation.Success(
            decisions = listOf(
              TaskCommitmentIntentDecision(
                commitmentId = "commitment-reaffirm",
                action = TaskCommitmentIntentAction.REAFFIRM,
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-1",
      task = promptTask(
        id = "task-3",
        input = "Please continue.",
      ),
      result = successResult(taskId = "task-3"),
      assistantOutput = "The flaky runtime test still needs work; I am continuing on it next.",
      toolObservations = emptyList(),
    )

    assertEquals(listOf("commitment-reaffirm"), summary.reaffirmedRecords.map { record -> record.id })
    assertEquals("open", memoryStore.list().single { record -> record.id == "commitment-reaffirm" }.extensions["status"])
  }

  @Test
  fun ingestCompletedTurnUsesSemanticSoulInterpreterForMemoryWrites() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        soulIntentInterpreter = FixedSoulIntentInterpreter(
          SoulMemoryIntentInterpretation.Success(
            intents = listOf(
              SoulMemoryIntent(
                preferenceKey = "agent_display_name",
                preferenceValue = "小白",
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                soulExtensions = mapOf(
                  "soul_display_name" to "小白",
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-semantic",
      task = promptTask(
        id = "task-semantic",
        input = "以后叫你小白。",
      ),
      result = successResult(taskId = "task-semantic"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(1, summary.writtenRecords.size)
    val record = memoryStore.list().single()
    assertEquals("agent_display_name", record.extensions["preference_key"])
    assertEquals("小白", record.extensions["preference_value"])
    assertEquals("小白", record.extensions["soul_display_name"])
  }

  @Test
  fun ingestCompletedTurnUsesSemanticUserInterpreterForGeneralDurableMemories() {
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { "workspace-main" },
      candidateExtractor = MemoryCandidateExtractor(
        userIntentInterpreter = FixedUserIntentInterpreter(
          UserMemoryIntentInterpretation.Success(
            intents = listOf(
              UserMemoryIntent(
                kind = com.opencray.runtime.memory.MemoryKind.USER_PREFERENCE,
                scope = com.opencray.runtime.memory.MemoryScope.USER,
                content = "Default to PowerShell commands",
              ),
              UserMemoryIntent(
                kind = com.opencray.runtime.memory.MemoryKind.DURABLE_INSTRUCTION,
                scope = com.opencray.runtime.memory.MemoryScope.WORKSPACE,
                content = "Do not use git reset --hard in this repo",
              ),
            ),
          ),
        ),
      ),
    )

    val summary = coordinator.ingestCompletedTurn(
      sessionId = "session-user-semantic",
      task = promptTask(
        id = "task-user-semantic",
        input = "以后都用 PowerShell，这个仓库不要用 git reset --hard。",
      ),
      result = successResult(taskId = "task-user-semantic"),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    assertEquals(2, summary.writtenRecords.size)
    assertTrue(memoryStore.list().any { record ->
      record.content == "Default to PowerShell commands" &&
        record.extensions["kind"] == "user_preference"
    })
    assertTrue(memoryStore.list().any { record ->
      record.content == "Do not use git reset --hard in this repo" &&
        record.extensions["scope"] == "workspace"
    })
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

  private fun conversationMessage(
    role: RuntimeConversationRole,
    content: String,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = role,
    content = content,
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

  private class FixedSoulIntentInterpreter(
    private val interpretation: SoulMemoryIntentInterpretation,
  ) : SoulMemoryIntentInterpreter {
    override fun interpret(
      request: SoulMemoryIntentRequest,
    ): SoulMemoryIntentInterpretation = interpretation
  }

  private class FixedUserIntentInterpreter(
    private val interpretation: UserMemoryIntentInterpretation,
  ) : UserMemoryIntentInterpreter {
    override fun interpret(
      request: UserMemoryIntentRequest,
    ): UserMemoryIntentInterpretation = interpretation
  }

  private class FixedTaskCommitmentIntentInterpreter(
    private val interpretation: TaskCommitmentIntentInterpretation,
  ) : TaskCommitmentIntentInterpreter {
    override fun interpret(
      request: TaskCommitmentIntentRequest,
    ): TaskCommitmentIntentInterpretation = interpretation
  }
}
