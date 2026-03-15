package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWriterTest {
  @Test
  fun writeUpsertsUserScopedMemoryAcrossSessions() {
    val store = InMemoryMemoryStore()
    val clock = IncrementingClock(start = 1_000L)
    val writer = MemoryWriter(
      store = store,
      clock = clock::next,
    )

    val first = MemoryCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = MemoryScope.USER,
      status = MemoryStatus.ACTIVE,
      content = "Default to Simplified Chinese for explanations",
      source = MemoryEvidenceSource.USER_INPUT,
      sourceSessionId = "session-a",
      sourceTaskId = "task-a",
    )
    val second = first.copy(
      sourceSessionId = "session-b",
      sourceTaskId = "task-b",
    )

    val firstSummary = writer.write(listOf(first))
    val secondSummary = writer.write(listOf(second))

    assertEquals(1, store.list().size)
    assertEquals(firstSummary.writtenRecords.single().id, secondSummary.writtenRecords.single().id)
    val record = store.list().single()
    assertEquals(2L, record.recordVersion)
    assertEquals(1_000L, record.createdAtEpochMs)
    assertEquals(1_001L, record.updatedAtEpochMs)
    assertEquals("user_preference", record.extensions[MemoryRecordExtensionKeys.KIND])
    assertEquals("user", record.extensions[MemoryRecordExtensionKeys.SCOPE])
    assertEquals("session-b", record.extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID])
  }

  @Test
  fun writeKeepsSessionScopedCommitmentsSeparatedAcrossSessions() {
    val store = InMemoryMemoryStore()
    val writer = MemoryWriter(store = store)

    val first = MemoryCandidate(
      kind = MemoryKind.TASK_COMMITMENT,
      scope = MemoryScope.SESSION,
      status = MemoryStatus.OPEN,
      content = "run the targeted runtime tests",
      source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
      sourceSessionId = "session-a",
      sourceTaskId = "task-a",
    )
    val second = first.copy(
      sourceSessionId = "session-b",
      sourceTaskId = "task-b",
    )

    val firstId = writer.write(listOf(first)).writtenRecords.single().id
    val secondId = writer.write(listOf(second)).writtenRecords.single().id

    assertEquals(2, store.list().size)
    assertNotEquals(firstId, secondId)
  }

  @Test
  fun writeAddsStructuredTagsAndTtlForProjectFacts() {
    val store = InMemoryMemoryStore()
    val writer = MemoryWriter(store = store)

    val candidate = MemoryCandidate(
      kind = MemoryKind.PROJECT_FACT,
      scope = MemoryScope.WORKSPACE,
      status = MemoryStatus.ACTIVE,
      content = "Project uses Gradle Kotlin DSL",
      source = MemoryEvidenceSource.TOOL_OBSERVATION,
      sourceSessionId = "session-a",
      sourceTaskId = "task-a",
      workspaceId = "workspace-main",
      ttlMs = 90L * 24L * 60L * 60L * 1000L,
    )

    val record = writer.write(listOf(candidate)).writtenRecords.single()

    assertTrue(record.tags.contains("kind:project_fact"))
    assertTrue(record.tags.contains("scope:workspace"))
    assertTrue(record.tags.contains("source:tool_observation"))
    assertEquals("workspace-main", record.extensions[MemoryRecordExtensionKeys.WORKSPACE_ID])
    assertEquals("7776000000", record.extensions[MemoryRecordExtensionKeys.TTL_MS])
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

  private class IncrementingClock(
    start: Long,
  ) {
    private var value = start

    fun next(): Long = value++
  }
}
