package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.context.ContextPruner
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
    assertEquals(1, summary.writtenRecords.size)
    assertEquals("Project uses Gradle Kotlin DSL", summary.writtenRecords.single().content)
    assertEquals("tool_observation", summary.writtenRecords.single().extensions[MemoryRecordExtensionKeys.SOURCE])
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
    assertTrue(store.list().isEmpty())
  }

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
