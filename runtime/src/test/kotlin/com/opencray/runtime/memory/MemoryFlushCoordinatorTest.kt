package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.context.ContextPruner
import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.context.TranscriptWindowBuilder
import com.opencray.runtime.context.TranscriptWindowConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFlushCoordinatorTest {
  @Test
  fun flushBeforeCompactionUsesCanonicalStructuredToolResultContent() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 1,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
    )

    val summary = coordinator.flushBeforeCompaction(
      sessionId = "session-1",
      workspaceId = "workspace-main",
      conversation = listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Capture the project fact."),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"Project uses Gradle Kotlin DSL","metadata":{"filePath":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          toolResult = RuntimeConversationToolResult(
            toolCallId = "call-1",
            toolName = "Read",
            status = "success",
            isError = false,
          ),
        ),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest live turn."),
      ),
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-1",
    )

    assertTrue(summary.wasWritten)
    assertEquals("pre_compaction", summary.trace.triggerStage)
    assertEquals(64, summary.trace.contextWindowTokens)
    assertEquals(57, summary.trace.autoCompactTokenLimit)
    assertTrue(summary.trace.estimatedReplayTokens >= summary.trace.autoCompactTokenLimit)
    assertTrue(summary.trace.tokenThresholdTriggered)
    assertEquals(1, summary.writtenRecords.size)
    assertEquals("Project uses Gradle Kotlin DSL", summary.writtenRecords.single().content)
    assertEquals("tool_observation", summary.writtenRecords.single().extensions[MemoryRecordExtensionKeys.SOURCE])
  }

  @Test
  fun flushMidTurnUsesMidTurnTraceAndCandidateIds() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 1,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
    )

    val summary = coordinator.flushMidTurn(
      sessionId = "session-mid",
      workspaceId = "workspace-main",
      conversation = listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Capture the project fact."),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"Project uses mid-turn durable memory","metadata":{"filePath":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          toolResult = RuntimeConversationToolResult(
            toolCallId = "call-1",
            toolName = "Read",
            status = "success",
            isError = false,
          ),
        ),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest live turn."),
      ),
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-1",
    )

    assertTrue(summary.wasWritten)
    assertEquals("mid_turn", summary.trace.triggerStage)
    assertEquals("memory_flush:mid_turn", summary.trace.maintenanceTask)
    assertEquals("inline", summary.trace.executionMode)
    assertEquals(summary.writtenRecords.map(MemoryRecord::id), summary.trace.candidateRecordIds)
    assertEquals("Project uses mid-turn durable memory", summary.writtenRecords.single().content)
  }

  @Test
  fun flushBeforeCompactionWaitsForReplayTokenPressure() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 1,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
    )

    val summary = coordinator.flushBeforeCompaction(
      sessionId = "session-1",
      workspaceId = "workspace-main",
      conversation = listOf(
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Capture the project fact."),
        RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "Assistant reply that would have been omitted."),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest live turn."),
      ),
      llmMetadata = mapOf("context_window_tokens" to "4096"),
      taskId = "task-1",
    )

    assertFalse(summary.wasWritten)
    assertEquals(MemoryFlushOutcome.NO_PRESSURE, summary.trace.outcome)
    assertEquals("pre_compaction", summary.trace.triggerStage)
    assertEquals(4096, summary.trace.contextWindowTokens)
    assertEquals(3686, summary.trace.autoCompactTokenLimit)
    assertTrue(summary.trace.estimatedReplayTokens < summary.trace.autoCompactTokenLimit)
    assertFalse(summary.trace.tokenThresholdTriggered)
    assertTrue(store.list().isEmpty())
  }

  @Test
  fun flushBeforeCompactionCouplesTranscriptWindowToExpandedSourcePreset() {
    val balancedStore = InMemoryMemoryStore()
    val expandedStore = InMemoryMemoryStore()
    val balancedCoordinator = MemoryFlushCoordinator(
      writer = MemoryWriter(store = balancedStore),
      sourceBudgetPolicy = ContextSourceBudgetPolicy(),
    )
    val expandedCoordinator = MemoryFlushCoordinator(
      writer = MemoryWriter(store = expandedStore),
      sourceBudgetPolicy = ContextSourceBudgetPolicy(),
    )
    val conversation = buildList {
      add(RuntimeConversationMessage(RuntimeConversationRole.USER, "Capture the project fact."))
      add(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
      )
      add(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"Project uses Gradle Kotlin DSL and sharedtoken evidence from README","metadata":{"filePath":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          toolResult = RuntimeConversationToolResult(
            toolCallId = "call-1",
            toolName = "Read",
            status = "success",
            isError = false,
          ),
        ),
      )
      add(RuntimeConversationMessage(RuntimeConversationRole.USER, "Keep that sharedtoken fact in mind."))
      repeat(12) { index ->
        add(
          RuntimeConversationMessage(
            role = if (index % 2 == 0) RuntimeConversationRole.USER else RuntimeConversationRole.ASSISTANT,
            content = "Later conversation message ${index + 1} with enough content to keep replay pressure high and preserve current-turn context.",
          ),
        )
      }
    }

    val balancedSummary = balancedCoordinator.flushBeforeCompaction(
      sessionId = "session-balanced",
      workspaceId = "workspace-main",
      conversation = conversation,
      llmMetadata = mapOf("context_window_tokens" to "65536"),
      taskId = "task-balanced",
    )
    val expandedSummary = expandedCoordinator.flushBeforeCompaction(
      sessionId = "session-expanded",
      workspaceId = "workspace-main",
      conversation = conversation,
      llmMetadata = mapOf(
        "context_window_tokens" to "65536",
        "context_budget_preset" to "expanded",
      ),
      taskId = "task-expanded",
    )

    assertFalse(balancedSummary.wasWritten)
    assertEquals(MemoryFlushOutcome.NO_PRESSURE, balancedSummary.trace.outcome)
    assertEquals("pre_compaction", balancedSummary.trace.triggerStage)
    assertEquals(65536, balancedSummary.trace.contextWindowTokens)
    assertFalse(balancedSummary.trace.tokenThresholdTriggered)
    assertEquals(4, balancedSummary.trace.omittedMessageCount)
    assertFalse(expandedSummary.wasWritten)
    assertEquals(MemoryFlushOutcome.NO_PRESSURE, expandedSummary.trace.outcome)
    assertEquals("pre_compaction", expandedSummary.trace.triggerStage)
    assertEquals(0, expandedSummary.trace.omittedMessageCount)
  }

  @Test
  fun flushBeforeCompactionHonorsExplicitAutoCompactTokenLimit() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 1,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
    )

    val summary = coordinator.flushBeforeCompaction(
      sessionId = "session-1",
      workspaceId = "workspace-main",
      conversation = listOf(
        RuntimeConversationMessage(
          RuntimeConversationRole.USER,
          "Capture the project fact with enough replay body to cross the explicit compact limit. ".repeat(3).trim(),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"Project uses Gradle Kotlin DSL and ships a local runtime bridge for context-budget tests","metadata":{"filePath":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          toolResult = RuntimeConversationToolResult(
            toolCallId = "call-1",
            toolName = "Read",
            status = "success",
            isError = false,
          ),
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.USER,
          "Latest live turn that should remain after the window builder trims the omitted replay.",
        ),
      ),
      llmMetadata = mapOf(
        "context_window_tokens" to "4096",
        "auto_compact_token_limit" to "100",
      ),
      taskId = "task-1",
    )

    assertTrue(summary.wasWritten)
    assertEquals(MemoryFlushOutcome.WRITTEN, summary.trace.outcome)
    assertEquals(1, summary.writtenRecords.size)
    assertEquals(4096, summary.trace.contextWindowTokens)
    assertEquals(100, summary.trace.autoCompactTokenLimit)
    assertTrue(summary.trace.tokenThresholdTriggered)
    assertEquals(
      "Project uses Gradle Kotlin DSL and ships a local runtime bridge for context-budget tests",
      summary.writtenRecords.single().content,
    )
  }

  @Test
  fun flushBeforeCompactionCanCreatePressureDrivenOmittedPrefix() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 8,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
    )

    val summary = coordinator.flushBeforeCompaction(
      sessionId = "session-1",
      workspaceId = "workspace-main",
      conversation = listOf(
        RuntimeConversationMessage(
          RuntimeConversationRole.USER,
          "Capture the older project fact before replay pressure forces a narrower maintenance window.",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-1",
            toolName = "Read",
          ),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = """{"run_id":"run-1","task_id":"task-1","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"Project uses Gradle Kotlin DSL and keeps replay/canonical history separate under pressure-driven maintenance.","metadata":{"filePath":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          toolResult = RuntimeConversationToolResult(
            toolCallId = "call-1",
            toolName = "Read",
            status = "success",
            isError = false,
          ),
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.ASSISTANT,
          ("Older assistant explanation that should be compacted before the latest tail. ").repeat(5).trim(),
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.USER,
          ("Recent user follow-up one keeps the current task moving while replay stays large. ").repeat(5).trim(),
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.ASSISTANT,
          ("Recent assistant follow-up one keeps the current task moving while replay stays large. ").repeat(5).trim(),
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.USER,
          ("Recent user follow-up two keeps the current task moving while replay stays large. ").repeat(5).trim(),
        ),
        RuntimeConversationMessage(
          RuntimeConversationRole.USER,
          "Latest live turn should remain in the retained replay tail.",
        ),
      ),
      llmMetadata = mapOf(
        "context_window_tokens" to "4096",
        "auto_compact_token_limit" to "80",
      ),
      taskId = "task-1",
    )

    assertTrue(summary.wasWritten)
    assertTrue(summary.trace.omittedMessageCount >= 2)
    assertEquals(1, summary.writtenRecords.size)
    assertEquals(
      "Project uses Gradle Kotlin DSL and keeps replay/canonical history separate under pressure-driven maintenance",
      summary.writtenRecords.single().content,
    )
  }

  @Test
  fun flushShortCircuitsResurrectingCommitmentAfterTtlDeletion() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 1,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
      existingRecordIdsProvider = { store.list().mapTo(linkedSetOf(), MemoryRecord::id) },
    )
    store.upsert(unrelatedLiveRecord())
    val conversation = listOf(
      RuntimeConversationMessage(
        RuntimeConversationRole.USER,
        "Please remember the follow-up work for the runtime verification pass we discussed earlier today.",
      ),
      RuntimeConversationMessage(
        RuntimeConversationRole.ASSISTANT,
        "I will run the targeted runtime tests tomorrow morning and share the consolidated verification summary with everyone.",
      ),
      RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest live turn."),
    )

    val firstSummary = coordinator.flushMidTurn(
      sessionId = "session-ttl",
      workspaceId = "workspace-main",
      conversation = conversation,
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-ttl",
    )
    assertTrue(firstSummary.wasWritten)
    assertEquals(MemoryFlushOutcome.WRITTEN, firstSummary.trace.outcome)
    assertEquals(1, firstSummary.writtenRecords.size)
    assertEquals("task_commitment", firstSummary.writtenRecords.single().extensions[MemoryRecordExtensionKeys.KIND])
    val expiredRecordId = firstSummary.writtenRecords.single().id

    assertTrue(store.delete(expiredRecordId))

    val secondSummary = coordinator.flushMidTurn(
      sessionId = "session-ttl",
      workspaceId = "workspace-main",
      conversation = conversation,
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-ttl",
    )
    assertFalse(secondSummary.wasWritten)
    assertEquals(MemoryFlushOutcome.ALREADY_FLUSHED, secondSummary.trace.outcome)
    assertEquals(1, store.list().size)
  }

  @Test
  fun flushStillWritesNewEvidenceAfterTombstonedCommitmentDeletion() {
    val store = InMemoryMemoryStore()
    val coordinator = MemoryFlushCoordinator(
      contextPruner = ContextPruner(),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 1,
          maxCharsPerMessage = 240,
        ),
      ),
      policy = MemoryFlushPolicy(
        minOmittedMessages = 1,
        minOmittedChars = 120,
      ),
      writer = MemoryWriter(store = store),
      existingRecordIdsProvider = { store.list().mapTo(linkedSetOf(), MemoryRecord::id) },
    )
    store.upsert(unrelatedLiveRecord())
    val initialConversation = listOf(
      RuntimeConversationMessage(
        RuntimeConversationRole.USER,
        "Please remember the follow-up work for the runtime verification pass we discussed earlier today.",
      ),
      RuntimeConversationMessage(
        RuntimeConversationRole.ASSISTANT,
        "I will run the targeted runtime tests tomorrow morning and share the consolidated verification summary with everyone.",
      ),
      RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest live turn."),
    )

    val firstSummary = coordinator.flushMidTurn(
      sessionId = "session-tombstone",
      workspaceId = "workspace-main",
      conversation = initialConversation,
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-tombstone",
    )
    assertTrue(firstSummary.wasWritten)
    assertTrue(store.delete(firstSummary.writtenRecords.single().id))

    val secondSummary = coordinator.flushMidTurn(
      sessionId = "session-tombstone",
      workspaceId = "workspace-main",
      conversation = initialConversation,
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-tombstone",
    )
    assertEquals(MemoryFlushOutcome.ALREADY_FLUSHED, secondSummary.trace.outcome)

    val followUpConversation = initialConversation +
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = """{"run_id":"run-2","task_id":"task-tombstone","turn":1,"tool_call_id":"call-2","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_CALL,
          toolCall = RuntimeConversationToolCall(
            id = "call-2",
            toolName = "Read",
          ),
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = """{"run_id":"run-2","task_id":"task-tombstone","turn":1,"tool_call_id":"call-2","tool_name":"Read","status":"success","content":"Project uses a fresh tombstone regression evidence trail","metadata":{"filePath":"README.md"}}""",
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          toolResult = RuntimeConversationToolResult(
            toolCallId = "call-2",
            toolName = "Read",
            status = "success",
            isError = false,
          ),
        ),
        RuntimeConversationMessage(RuntimeConversationRole.USER, "Newest live turn after the follow-up."),
      )

    val thirdSummary = coordinator.flushMidTurn(
      sessionId = "session-tombstone",
      workspaceId = "workspace-main",
      conversation = followUpConversation,
      llmMetadata = mapOf("context_window_tokens" to "64"),
      taskId = "task-tombstone",
    )
    assertTrue(thirdSummary.wasWritten)
    assertEquals(1, thirdSummary.writtenRecords.size)
    assertEquals(
      "Project uses a fresh tombstone regression evidence trail",
      thirdSummary.writtenRecords.single().content,
    )
    assertTrue(
      store.list().none { record ->
        record.extensions[MemoryRecordExtensionKeys.KIND] == "task_commitment"
      },
    )
    assertEquals(2, store.list().size)
  }

  private fun unrelatedLiveRecord(): MemoryRecord = MemoryRecord(
    id = "record-unrelated-live",
    content = "Unrelated durable user preference kept alongside flushed commitments.",
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
    tags = listOf("kind:preference", "scope:session", "status:active"),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "preference",
      MemoryRecordExtensionKeys.SCOPE to "session",
      MemoryRecordExtensionKeys.STATUS to "active",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-unrelated",
    ),
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
