package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCommitmentResolverTest {
  @Test
  fun maintainResolvesMatchingOpenSessionCommitmentFromCompletionEvidence() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-1",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "I ran the targeted runtime tests and updated the docs.",
        toolObservations = emptyList(),
      ),
    )

    assertEquals(listOf("commitment-1"), summary.resolvedRecords.map { record -> record.id })
    val record = store.list().single()
    assertEquals("resolved", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("completed", record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertEquals("2000", record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS])
    assertTrue(record.tags.contains("status:resolved"))
  }

  @Test
  fun maintainDeletesExpiredTaskCommitments() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-expired",
        content = "update the docs after queue repair",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
        ttlMs = 100L,
        lastConfirmedAtEpochMs = 1_050L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "No new work.",
      ),
    )

    assertEquals(listOf("commitment-expired"), summary.expiredRecordIds)
    assertTrue(store.list().isEmpty())
  }

  @Test
  fun maintainIgnoresCompletionEvidenceForDifferentSessionCommitment() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-other-session",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-other",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-main",
        taskId = "task-2",
        userInput = "Please continue.",
        toolObservations = listOf("Targeted runtime tests passed successfully."),
      ),
    )

    assertTrue(summary.isEmpty)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  private fun memoryRecord(
    id: String,
    content: String,
    sourceSessionId: String,
    updatedAtEpochMs: Long,
    ttlMs: Long = 14L * 24L * 60L * 60L * 1000L,
    lastConfirmedAtEpochMs: Long = updatedAtEpochMs,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:task_commitment",
      "scope:session",
      "status:open",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "task_commitment",
      MemoryRecordExtensionKeys.SCOPE to "session",
      MemoryRecordExtensionKeys.STATUS to "open",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sourceSessionId,
      MemoryRecordExtensionKeys.TTL_MS to ttlMs.toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to lastConfirmedAtEpochMs.toString(),
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
