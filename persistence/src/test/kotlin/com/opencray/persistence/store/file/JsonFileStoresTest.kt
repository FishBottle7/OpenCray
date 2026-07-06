package com.opencray.persistence.store.file

import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.migration.NoOpJsonMigration
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonFileStoresTest {
  @Test
  fun writeRecordUsesDurableUpdatePrimitive() {
    val storage = UpdateOnlyDurableTextStorage()
    val record = SessionRecord(
      sessionId = "session-1",
      agentId = "agent-1",
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )

    writeRecord(
      storage = storage,
      name = "session.json",
      serializer = SessionRecord.serializer(),
      value = record,
    )

    assertEquals(1, storage.updateTextCallCount)
    assertEquals(
      record,
      readRecord(
        storage = storage,
        name = "session.json",
        serializer = SessionRecord.serializer(),
        migration = NoOpJsonMigration,
      ),
    )
  }

  private class UpdateOnlyDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    var updateTextCallCount: Int = 0
      private set

    override fun readText(name: String): String? = text

    override fun writeText(name: String, text: String) {
      error("JSON record writes should use updateText.")
    }

    override fun delete(name: String): Boolean {
      error("JSON record writes should use updateText.")
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
