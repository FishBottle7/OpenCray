package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.store.file.JsonFileMemoryStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalizationLocalStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun memoryHelpersRoundTripStructuredRecords() {
    val directory = temporaryFolder.newFolder("personalization-memory-roundtrip")
    val store = PersonalizationLocalStore(directory)
    store.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-1",
        content = "Default to concise Chinese replies.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          "kind" to "user_preference",
          "scope" to "user",
          "status" to "active",
          "source_session_id" to "session-1",
        ),
      ),
    )

    val records = store.listMemoryRecords()

    assertEquals(listOf("memory-1"), records.map { record -> record.id })
    assertEquals("Default to concise Chinese replies.", records.single().content)
  }

  @Test
  fun clearMemoryAndHistoryClearsOnlyMemoryAndSessionStores() {
    val directory = temporaryFolder.newFolder("personalization-clear-memory")
    val store = PersonalizationLocalStore(directory)
    store.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-1",
        content = "Remember to stay concise.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    JsonFileSessionStore(directory).save(
      SessionRecord(
        sessionId = "session-1",
        agentId = "agent-1",
        state = mapOf("queue_state" to "running"),
        createdAtEpochMs = 1_100L,
        updatedAtEpochMs = 1_101L,
      ),
    )

    store.clearMemoryAndHistory()

    assertEquals(emptyList<MemoryRecord>(), JsonFileMemoryStore(directory).list())
    assertNull(JsonFileSessionStore(directory).load())
  }
}
