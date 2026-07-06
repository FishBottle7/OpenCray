package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteRtOnDeviceModelInstallStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun saveAndDeleteUseAtomicStorageUpdatePath() {
    val storage = UpdateOnlyDurableTextStorage()
    val store = LiteRtOnDeviceModelInstallStore(
      directory = temporaryFolder.newFolder("litert-install-store"),
      storage = storage,
    )

    store.save(installRecord("beta"))
    store.save(installRecord("alpha"))
    store.delete("beta")

    assertEquals(listOf("alpha"), store.loadAll().map { record -> record.modelId })
    assertEquals(3, storage.updateTextCallCount)
    assertEquals(0, storage.writeTextCallCount)
    assertTrue(storage.deletedNames.isEmpty())
  }

  private fun installRecord(modelId: String): LiteRtOnDeviceModelInstallRecord =
    LiteRtOnDeviceModelInstallRecord(
      modelId = modelId,
      versionTag = "v1",
      sourceUrl = "https://example.test/$modelId.bin",
      localFilePath = "/models/$modelId.bin",
      fileSizeBytes = 1024L,
      sha256 = "sha256-$modelId",
    )
}

private class UpdateOnlyDurableTextStorage : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  val deletedNames = mutableListOf<String>()

  private var textByName = linkedMapOf<String, String>()

  override fun readText(name: String): String? = textByName[name]

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("LiteRt install store should update the index through updateText.")
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
