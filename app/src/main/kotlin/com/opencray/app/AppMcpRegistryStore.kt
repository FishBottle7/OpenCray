package com.opencray.app

import android.content.Context
import com.opencray.mcp.McpRegistryRecord
import com.opencray.mcp.McpRegistryStore
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File

internal class AppMcpRegistryStore(
  directory: File,
) : McpRegistryStore {
  private val storage = DirectoryDurableTextStorage(directory)

  override fun load(): McpRegistryRecord? {
    val text = storage.readText(FILE_NAME) ?: return null
    if (text.isBlank()) {
      return null
    }
    return PersistenceJson.instance.decodeFromString(McpRegistryRecord.serializer(), text)
  }

  override fun save(record: McpRegistryRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(McpRegistryRecord.serializer(), record),
    )
  }

  override fun clear(): Boolean = storage.delete(FILE_NAME)

  companion object {
    private const val DIRECTORY_NAME = "mcp-settings-state"
    private const val FILE_NAME = "registry.json"

    fun fromContext(context: Context): AppMcpRegistryStore =
      AppMcpRegistryStore(File(context.filesDir, DIRECTORY_NAME))
  }
}
