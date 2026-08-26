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

  @Test
  fun writeSupersedesPreviousActivePreferenceForSameScopeAndPreferenceKey() {
    val store = InMemoryMemoryStore()
    val clock = IncrementingClock(start = 10_000L)
    val writer = MemoryWriter(
      store = store,
      clock = clock::next,
    )

    writer.write(
      listOf(
        MemoryCandidate(
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
          status = MemoryStatus.ACTIVE,
          content = "Agent style profile should be warm",
          source = MemoryEvidenceSource.USER_INPUT,
          sourceSessionId = "session-a",
          extensions = styleProfilePreferenceExtensions(
            styleProfile = "warm",
            scope = MemoryScope.USER,
          ),
        ),
      ),
    )
    writer.write(
      listOf(
        MemoryCandidate(
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
          status = MemoryStatus.ACTIVE,
          content = "Agent style profile should be serious",
          source = MemoryEvidenceSource.USER_INPUT,
          sourceSessionId = "session-b",
          extensions = styleProfilePreferenceExtensions(
            styleProfile = "serious",
            scope = MemoryScope.USER,
          ),
        ),
      ),
    )

    val records = store.list().sortedBy(MemoryRecord::createdAtEpochMs)
    assertEquals(2, records.size)
    val resolved = records.first { record ->
      record.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE] == "warm"
    }
    val active = records.first { record ->
      record.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE] == "serious"
    }

    assertEquals("resolved", resolved.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("superseded", resolved.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertEquals(active.id, resolved.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY])
    assertEquals("active", active.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("warm", resolved.extensions[MemorySoulExtensionKeys.TONE])
    assertEquals("serious and formal", active.extensions[MemorySoulExtensionKeys.VOICE])
    assertEquals("direct", active.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE])
  }

  @Test
  fun writeKeepsNewActivePreferenceWhenSupersedeResolutionIsInterrupted() {
    val store = SupersedeFailingMemoryStore()
    val clock = IncrementingClock(start = 10_000L)
    val writer = MemoryWriter(
      store = store,
      clock = clock::next,
    )
    val warmCandidate = MemoryCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = MemoryScope.USER,
      status = MemoryStatus.ACTIVE,
      content = "Agent style profile should be warm",
      source = MemoryEvidenceSource.USER_INPUT,
      sourceSessionId = "session-a",
      extensions = styleProfilePreferenceExtensions(
        styleProfile = "warm",
        scope = MemoryScope.USER,
      ),
    )
    val seriousCandidate = warmCandidate.copy(
      content = "Agent style profile should be serious",
      sourceSessionId = "session-b",
      extensions = styleProfilePreferenceExtensions(
        styleProfile = "serious",
        scope = MemoryScope.USER,
      ),
    )

    writer.write(listOf(warmCandidate))
    try {
      writer.write(listOf(seriousCandidate))
    } catch (_: IllegalStateException) {
    }

    val preferenceRecords = store.list().filter { record ->
      record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_STYLE_PROFILE
    }
    assertEquals(2, preferenceRecords.size)
    assertTrue(preferenceRecords.all { record ->
      record.extensions[MemoryRecordExtensionKeys.STATUS] == "active"
    })
    assertTrue(
      preferenceRecords.any { record ->
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE] == "serious"
      },
    )
    assertTrue(
      store.list().none { record ->
        val supersededBy = record.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY]
        !supersededBy.isNullOrBlank() && store.list().none { existing -> existing.id == supersededBy }
      },
    )
  }

  @Test
  fun writeResolvesPreExistingDuplicateActivePreferencesTowardLatestValue() {
    val store = SupersedeFailingMemoryStore()
    val interruptedClock = IncrementingClock(start = 10_000L)
    val interruptedWriter = MemoryWriter(
      store = store,
      clock = interruptedClock::next,
    )
    val warmCandidate = MemoryCandidate(
      kind = MemoryKind.USER_PREFERENCE,
      scope = MemoryScope.USER,
      status = MemoryStatus.ACTIVE,
      content = "Agent style profile should be warm",
      source = MemoryEvidenceSource.USER_INPUT,
      sourceSessionId = "session-a",
      extensions = styleProfilePreferenceExtensions(
        styleProfile = "warm",
        scope = MemoryScope.USER,
      ),
    )
    val seriousCandidate = warmCandidate.copy(
      content = "Agent style profile should be serious",
      sourceSessionId = "session-b",
      extensions = styleProfilePreferenceExtensions(
        styleProfile = "serious",
        scope = MemoryScope.USER,
      ),
    )

    interruptedWriter.write(listOf(warmCandidate))
    try {
      interruptedWriter.write(listOf(seriousCandidate))
    } catch (_: IllegalStateException) {
    }

    val healthyWriter = MemoryWriter(store = store)
    val playfulCandidate = warmCandidate.copy(
      content = "Agent style profile should be playful",
      sourceSessionId = "session-c",
      extensions = styleProfilePreferenceExtensions(
        styleProfile = "playful",
        scope = MemoryScope.USER,
      ),
    )

    healthyWriter.write(listOf(playfulCandidate))

    val activeRecords = store.list().filter { record ->
      record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] == MemoryPreferenceKeys.AGENT_STYLE_PROFILE &&
        record.extensions[MemoryRecordExtensionKeys.STATUS] == "active"
    }
    assertEquals(1, activeRecords.size)
    val active = activeRecords.single()
    assertEquals("playful", active.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE])
    store.list()
      .filter { record -> record.id != active.id }
      .forEach { record ->
        assertEquals("resolved", record.extensions[MemoryRecordExtensionKeys.STATUS])
        assertEquals("superseded", record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
        assertEquals(active.id, record.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY])
      }
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

  private class SupersedeFailingMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()
    private var interruptedOnce = false

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      if (
        !interruptedOnce &&
        record.extensions[MemoryRecordExtensionKeys.STATUS] == "resolved" &&
        record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON] == "superseded"
      ) {
        interruptedOnce = true
        throw IllegalStateException("Simulated crash between supersede steps.")
      }
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
