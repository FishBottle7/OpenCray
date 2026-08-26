package com.opencray.persistence.store.file

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.migration.NoOpJsonMigration
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.SessionStoreQueueSnapshotStore
import com.opencray.persistence.store.SessionStoreUpdate
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonFileStoresTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

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

  @Test
  fun updateRecordTreatsMalformedCurrentTextAsMissingAndRecoversFile() {
    val storage = UpdateOnlyDurableTextStorage()
    val record = SessionRecord(
      sessionId = "session-recovered",
      agentId = "agent-1",
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    storage.seed("""{"sessionId":"broken"}garbage""")

    val result = storage.updateRecord(
      name = "session.json",
      serializer = SessionRecord.serializer(),
      migration = NoOpJsonMigration,
    ) { current ->
      assertNull(current)
      RecordStorageUpdate(
        value = record,
        result = "recovered",
      )
    }

    assertEquals("recovered", result)
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

  @Test
  fun updateRecordBacksUpUndecodableSessionFileBeforeResetting() {
    val directory = temporaryFolder.newFolder("session-corrupt")
    val corruptContent = "{\"sessionId\":123}"
    File(directory, "session.json").writeText(corruptContent, Charsets.UTF_8)
    val store = JsonFileSessionStore(directory)
    val record = SessionRecord(
      sessionId = "session-new",
      agentId = "agent-1",
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )

    val result = store.update { current ->
      assertNull(current)
      SessionStoreUpdate(
        record = record,
        result = "reset",
      )
    }

    assertEquals("reset", result)
    assertEquals(record, store.load())
    val backups = directory.listFiles { file -> file.name.startsWith("session.json.corrupt-") }
      .orEmpty()
      .sortedBy { it.name }
    assertEquals(1, backups.size)
    assertArrayEquals(
      corruptContent.toByteArray(Charsets.UTF_8),
      backups.single().readBytes(),
    )
  }

  @Test
  fun updateRecordKeepsCorruptFileWhenBackupFails() {
    val directory = temporaryFolder.newFolder("session-backup-failure")
    val corruptContent = "{\"sessionId\":123}"
    val corruptFile = File(directory, "session.json")
    corruptFile.writeText(corruptContent, Charsets.UTF_8)
    val storage = NoBackupDurableTextStorage(directory)

    val result = storage.updateRecord(
      name = "session.json",
      serializer = SessionRecord.serializer(),
      migration = NoOpJsonMigration,
    ) { current ->
      assertNull(current)
      RecordStorageUpdate(
        value = SessionRecord(
          sessionId = "session-should-not-persist",
          agentId = "agent-1",
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
        ),
        result = "kept-corrupt",
      )
    }

    assertEquals("kept-corrupt", result)
    assertTrue(corruptFile.exists())
    assertEquals(corruptContent, corruptFile.readText(Charsets.UTF_8))
  }

  @Test
  fun sessionLoadBacksUpCorruptContentAndReturnsNull() {
    val directory = temporaryFolder.newFolder("session-load-corrupt")
    val corruptContent = "{\"sessionId\":\"session-1\",\"state\":{\"queue_state\":"
    File(directory, "session.json").writeText(corruptContent, Charsets.UTF_8)
    val store = JsonFileSessionStore(directory)

    assertNull(store.load())

    assertEquals(1, corruptBackupCount(directory, "session.json"))
    assertArrayEquals(
      corruptContent.toByteArray(Charsets.UTF_8),
      corruptBackups(directory, "session.json").single().readBytes(),
    )
    assertTrue(File(directory, "session.json").exists())

    val recovered = SessionRecord(
      sessionId = "session-recovered",
      agentId = "agent-1",
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    store.save(recovered)
    assertEquals(recovered, store.load())
  }

  @Test
  fun memoryListBacksUpCorruptContentAndReturnsEmptyList() {
    val directory = temporaryFolder.newFolder("memory-list-corrupt")
    val corruptContent = "{\"records\":[{\"id\":\"mem-1\"}"
    File(directory, "memory.json").writeText(corruptContent, Charsets.UTF_8)
    val store = JsonFileMemoryStore(directory)

    assertTrue(store.list().isEmpty())

    assertEquals(1, corruptBackupCount(directory, "memory.json"))
    assertArrayEquals(
      corruptContent.toByteArray(Charsets.UTF_8),
      corruptBackups(directory, "memory.json").single().readBytes(),
    )

    val record = MemoryRecord(
      id = "mem-recovered",
      content = "recovered memory",
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    store.upsert(record)
    assertEquals(listOf(record), store.list())
  }

  @Test
  fun queueSnapshotLoadBacksUpCorruptSessionAndReturnsNull() {
    val directory = temporaryFolder.newFolder("queue-snapshot-load-corrupt")
    val corruptContent = "{\"sessionId\":\"broken\"}trailing-garbage"
    File(directory, "session.json").writeText(corruptContent, Charsets.UTF_8)
    val store = SessionStoreQueueSnapshotStore(JsonFileSessionStore(directory))

    assertNull(store.load())

    assertEquals(1, corruptBackupCount(directory, "session.json"))
    assertArrayEquals(
      corruptContent.toByteArray(Charsets.UTF_8),
      corruptBackups(directory, "session.json").single().readBytes(),
    )
  }

  @Test
  fun readRecordKeepsFailingClosedWhenCorruptBackupFails() {
    val directory = temporaryFolder.newFolder("read-backup-failure")
    val corruptContent = "{\"sessionId\":123}"
    val corruptFile = File(directory, "session.json")
    corruptFile.writeText(corruptContent, Charsets.UTF_8)

    try {
      readRecord(
        storage = NoBackupDurableTextStorage(directory),
        name = "session.json",
        serializer = SessionRecord.serializer(),
        migration = NoOpJsonMigration,
      )
      fail("Expected readRecord to throw when the corrupt backup fails.")
    } catch (_: IllegalStateException) {
    }
    assertEquals(corruptContent, corruptFile.readText(Charsets.UTF_8))
  }

  private fun corruptBackups(directory: File, name: String): List<File> =
    directory.listFiles { file -> file.name.startsWith("$name.corrupt-") }
      .orEmpty()
      .sortedBy { it.name }

  private fun corruptBackupCount(directory: File, name: String): Int =
    corruptBackups(directory, name).size

  private class UpdateOnlyDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    var updateTextCallCount: Int = 0
      private set

    fun seed(text: String?) {
      this.text = text
    }

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

  private class NoBackupDurableTextStorage(directory: File) : DurableTextStorage {
    private val delegate = DirectoryDurableTextStorage(directory)

    override fun readText(name: String): String? = delegate.readText(name)

    override fun writeText(name: String, text: String) {
      delegate.writeText(name, text)
    }

    override fun delete(name: String): Boolean = delegate.delete(name)

    override fun backupCorrupt(name: String): Boolean = false
  }
}
