package com.opencray.runtime.compaction

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
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

  private fun entry(
    text: String,
    compactedAtEpochMs: Long,
  ): DurableCompactionEntry = DurableCompactionEntry(
    text = text,
    compactedMessageCount = 4,
    compactedAtEpochMs = compactedAtEpochMs,
  )
}

private class UpdateOnlyCompactionTextStorage : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  val deletedNames = mutableListOf<String>()

  private var textByName = linkedMapOf<String, String>()

  override fun readText(name: String): String? = textByName[name]

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("Session compaction store should update through updateText.")
  }

  override fun delete(name: String): Boolean {
    deletedNames += name
    return textByName.remove(name) != null
  }

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
