package com.opencray.app

import android.content.Context
import com.opencray.mcp.McpRegistryRecord
import com.opencray.mcp.McpRegistryStore
import com.opencray.mcp.McpRegistryStoreUpdate
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File

internal class AppMcpRegistryStore(
  directory: File,
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory),
) : McpRegistryStore {
  override fun load(): McpRegistryRecord? {
    return decodeRecord(storage.readText(FILE_NAME))
  }

  override fun save(record: McpRegistryRecord) {
    storage.updateText(FILE_NAME) {
      DurableTextUpdate(
        text = encodeRecord(record),
        result = Unit,
      )
    }
  }

  override fun clear(): Boolean = storage.updateText(FILE_NAME) { currentText ->
    DurableTextUpdate(
      text = null,
      result = currentText != null,
    )
  }

  override fun <T> update(
    transform: (McpRegistryRecord?) -> McpRegistryStoreUpdate<T>,
  ): T = storage.updateText(FILE_NAME) { currentText ->
    val updated = transform(decodeRecord(currentText))
    DurableTextUpdate(
      text = updated.record?.let(::encodeRecord),
      result = updated.result,
      write = updated.write,
    )
  }

  private fun decodeRecord(text: String?): McpRegistryRecord? {
    if (text.isNullOrBlank()) {
      return null
    }
    return PersistenceJson.instance.decodeFromString(McpRegistryRecord.serializer(), text)
  }

  private fun encodeRecord(record: McpRegistryRecord): String =
    PersistenceJson.instance.encodeToString(McpRegistryRecord.serializer(), record)

  companion object {
    private const val DIRECTORY_NAME = "mcp-settings-state"
    private const val FILE_NAME = "registry.json"

    fun fromContext(context: Context): AppMcpRegistryStore =
      AppMcpRegistryStore(File(context.filesDir, DIRECTORY_NAME))
  }
}
