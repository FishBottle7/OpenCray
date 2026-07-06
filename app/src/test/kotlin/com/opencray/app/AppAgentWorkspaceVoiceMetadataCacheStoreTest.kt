package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentWorkspaceVoiceMetadataCacheStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun putMergesCurrentSnapshotThroughAtomicUpdatePath() {
    var now = 100L
    val storage = UpdateOnlyVoiceMetadataCacheTextStorage()
    val directory = temporaryFolder.newFolder("voice-metadata-cache")
    val firstOwner = AppAgentWorkspaceVoiceMetadataCacheStore(
      directory = directory,
      storage = storage,
      nowEpochMs = { now++ },
    )
    val secondOwner = AppAgentWorkspaceVoiceMetadataCacheStore(
      directory = directory,
      storage = storage,
      nowEpochMs = { now++ },
    )

    firstOwner.put("HASH-A", metadata(durationMs = 1_000L, transcriptText = "alpha"))
    secondOwner.put("hash-b", metadata(durationMs = 2_000L, transcriptText = "beta"))
    firstOwner.put("hash-c", metadata(durationMs = 3_000L, transcriptText = "gamma"))

    assertEquals("alpha", firstOwner.get("hash-a")?.transcriptText)
    assertEquals("beta", firstOwner.get("hash-b")?.transcriptText)
    assertEquals("gamma", secondOwner.get("hash-c")?.transcriptText)
    assertEquals(3, storage.updateTextCallCount)
    assertEquals(0, storage.writeTextCallCount)
    assertTrue(storage.deletedNames.isEmpty())
  }

  private fun metadata(
    durationMs: Long,
    transcriptText: String,
  ): AppAgentWorkspaceVoiceMetadata = AppAgentWorkspaceVoiceMetadata(
    durationMs = durationMs,
    waveformBars = listOf(10, 40, 90),
    transcriptText = transcriptText,
  )
}

private class UpdateOnlyVoiceMetadataCacheTextStorage : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  val deletedNames = mutableListOf<String>()

  private var textByName = linkedMapOf<String, String>()

  override fun readText(name: String): String? = textByName[name]

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("Voice metadata cache should merge through updateText.")
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
