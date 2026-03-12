package com.opencray.persistence.store.file

import com.opencray.persistence.migration.JsonMigration
import com.opencray.persistence.migration.NoOpJsonMigration
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.ChatWorkspaceStore
import java.io.File

class JsonFileChatWorkspaceStore(
  directory: File,
  private val migration: JsonMigration = NoOpJsonMigration,
) : ChatWorkspaceStore {
  private val storage = DirectoryDurableTextStorage(directory)
  private val fileName = "chat-workspace.json"

  override fun load(): ChatWorkspaceRecord? = readRecord(
    name = fileName,
    serializer = ChatWorkspaceRecord.serializer(),
    migration = migration,
    storage = storage,
  )

  override fun save(record: ChatWorkspaceRecord) {
    writeRecord(
      name = fileName,
      serializer = ChatWorkspaceRecord.serializer(),
      value = record,
      storage = storage,
    )
  }

  override fun clear(): Boolean = storage.delete(fileName)
}
