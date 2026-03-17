package com.opencray.runtime.compaction

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

class FileBackedSessionCompactionStore(
  directory: File,
) : SessionCompactionStore {
  private val lock = Any()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

  override fun load(): DurableCompactionState = synchronized(lock) {
    loadRecord().toState()
  }

  override fun save(state: DurableCompactionState) {
    synchronized(lock) {
      val existing = loadRecord()
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = System.currentTimeMillis(),
          entries = state.entries,
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SessionCompactionRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return SessionCompactionRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SessionCompactionRecord.serializer(),
      string = encoded,
    )
  }

  private fun saveRecord(record: SessionCompactionRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = SessionCompactionRecord.serializer(),
        value = record,
      ),
    )
  }

  @Serializable
  private data class SessionCompactionRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val entries: List<DurableCompactionEntry> = emptyList(),
  ) {
    fun toState(): DurableCompactionState = DurableCompactionState(entries = entries)
  }

  private companion object {
    private const val FILE_NAME = "runtime-compaction.json"
  }
}
