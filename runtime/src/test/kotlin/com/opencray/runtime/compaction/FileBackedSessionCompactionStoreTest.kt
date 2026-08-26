package com.opencray.runtime.compaction

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedSessionCompactionStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun saveAndUpdateUseAtomicStorageUpdatePath() {
    var now = 1_000L
    val storage = UpdateOnlyCompactionTextStorage()
    val store = FileBackedSessionCompactionStore(
      directory = temporaryFolder.newFolder("runtime-compaction-store"),
      storage = storage,
      clock = { now++ },
    )

    store.save(
      DurableCompactionState(
        entries = listOf(entry("first", compactedAtEpochMs = 100L)),
      ),
    )
    store.update { current ->
      DurableCompactionState(
        entries = current.entries + entry("second", compactedAtEpochMs = 200L),
      )
    }

    assertEquals(listOf("first", "second"), store.load().entries.map { item -> item.text })
    assertEquals(2, storage.updateTextCallCount)
    assertEquals(0, storage.writeTextCallCount)
    assertTrue(storage.deletedNames.isEmpty())
  }

  @Test
  fun corruptSnapshotLoadsAsEmptyAndNextUpdateRecoversFile() {
    val directory = temporaryFolder.newFolder("runtime-compaction-store-corrupt")
    val compactionFile = File(directory, "runtime-compaction.json")
    val corruptedBytes = "{ not-json"
    compactionFile.writeText(corruptedBytes)
    val store = FileBackedSessionCompactionStore(
      directory = directory,
      clock = { 2_000L },
    )

    assertEquals(emptyList<DurableCompactionEntry>(), store.load().entries)

    val updated = store.update {
      DurableCompactionState(
        entries = listOf(entry("recovered", compactedAtEpochMs = 2_000L)),
      )
    }
    val recoveredText = compactionFile.readText()
    val corruptBackups = directory
      .listFiles { file -> file.name.startsWith("runtime-compaction.json.corrupt-") }
      .orEmpty()
      .toList()

    assertEquals(listOf("recovered"), updated.entries.map { item -> item.text })
    assertTrue(recoveredText.contains("recovered"))
    assertTrue(!recoveredText.contains("not-json"))
    assertTrue(corruptBackups.isNotEmpty())
    corruptBackups.forEach { backup ->
      assertEquals(corruptedBytes, backup.readText())
    }
  }

  @Test
  fun corruptRecordBackupFailureRejectsLoadAndWriteWithoutDestroyingOriginalBytes() {
    val directory = temporaryFolder.newFolder("runtime-compaction-store-backup-failure")
    val storage = UpdateOnlyCompactionTextStorage(backupCorruptResult = false)
    storage.seed("runtime-compaction.json", "{ not-json")
    val store = FileBackedSessionCompactionStore(
      directory = directory,
      storage = storage,
      clock = { 2_000L },
    )

    val loadFailure = runCatching { store.load() }
    val updateFailure = runCatching { store.update { current -> current } }

    assertTrue(loadFailure.exceptionOrNull() is IllegalStateException)
    assertTrue(updateFailure.exceptionOrNull() is IllegalStateException)
    assertEquals("{ not-json", storage.storedText("runtime-compaction.json"))
    assertEquals(0, storage.writeTextCallCount)
  }

  @Test
  fun savedCoverageFingerprintSurvivesRestartRoundTrip() {
    val directory = temporaryFolder.newFolder("runtime-compaction-store-fingerprint")
    val fingerprint = coverageFingerprintOf(listOf(userMessage("User request 1")))
    val store = FileBackedSessionCompactionStore(
      directory = directory,
      clock = { 3_000L },
    )
    store.save(
      DurableCompactionState(
        entries = listOf(
          DurableCompactionEntry(
            text = "first",
            compactedMessageCount = 4,
            compactedAtEpochMs = 100L,
            coverageFingerprint = fingerprint,
          ),
        ),
      ),
    )
    val reopened = FileBackedSessionCompactionStore(
      directory = directory,
      clock = { 4_000L },
    )

    assertEquals(fingerprint, reopened.load().entries.single().coverageFingerprint)
  }

  private fun userMessage(content: String): RuntimeConversationMessage =
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = content,
    )

  private fun entry(
    text: String,
    compactedAtEpochMs: Long,
  ): DurableCompactionEntry = DurableCompactionEntry(
    text = text,
    compactedMessageCount = 4,
    compactedAtEpochMs = compactedAtEpochMs,
  )
}

private class UpdateOnlyCompactionTextStorage(
  private val backupCorruptResult: Boolean = true,
) : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  val deletedNames = mutableListOf<String>()

  private var textByName = linkedMapOf<String, String>()

  fun seed(name: String, text: String) {
    textByName[name] = text
  }

  fun storedText(name: String): String? = textByName[name]

  override fun readText(name: String): String? = textByName[name]

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("Session compaction store should update through updateText.")
  }

  override fun delete(name: String): Boolean {
    deletedNames += name
    return textByName.remove(name) != null
  }

  override fun backupCorrupt(name: String): Boolean = backupCorruptResult

  override fun <T> updateText(
    name: String,
    update: (String?) -> DurableTextUpdate<T>,
  ): T {
    updateTextCallCount += 1
    val updated = update(textByName[name])
    if (updated.write) {
      val updatedText = updated.text
      if (updatedText == null) {
        textByName.remove(name)
      } else {
        textByName[name] = updatedText
      }
    }
    return updated.result
  }
}
