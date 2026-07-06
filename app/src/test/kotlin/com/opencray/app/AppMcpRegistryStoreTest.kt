package com.opencray.app

import com.opencray.mcp.McpRegistryRecord
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppMcpRegistryStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun saveAndClearUseDurableUpdatePrimitive() {
    val storage = UpdateOnlyDurableTextStorage()
    val store = AppMcpRegistryStore(
      directory = temporaryFolder.root,
      storage = storage,
    )
    val record = McpRegistryRecord(createdAtEpochMs = 1_000L)

    store.save(record)

    assertEquals(1, storage.updateTextCallCount)
    assertEquals(record, store.load())

    assertTrue(store.clear())

    assertEquals(2, storage.updateTextCallCount)
    assertNull(store.load())
  }

  private class UpdateOnlyDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    var updateTextCallCount: Int = 0
      private set

    override fun readText(name: String): String? = text

    override fun writeText(name: String, text: String) {
      error("MCP registry mutations should use updateText.")
    }

    override fun delete(name: String): Boolean {
      error("MCP registry mutations should use updateText.")
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
