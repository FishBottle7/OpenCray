package com.opencray.runtime.compaction

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

class FileBackedSessionCompactionStore(
  directory: File,
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory),
  private val clock: () -> Long = System::currentTimeMillis,
) : SessionCompactionStore {
  private val lock = Any()

  override fun load(): DurableCompactionState = synchronized(lock) {
    loadRecord().toState()
  }

  override fun save(state: DurableCompactionState) {
    update { state }
  }

  override fun update(transform: (DurableCompactionState) -> DurableCompactionState): DurableCompactionState =
    synchronized(lock) {
      storage.updateText(FILE_NAME) { currentText ->
        val existing = decodeRecord(currentText)
        val updatedState = transform(existing.toState())
        val updatedRecord = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          entries = updatedState.entries,
        )
        DurableTextUpdate(
          text = encodeRecord(updatedRecord),
          result = updatedState,
        )
      }
    }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SessionCompactionRecord {
    return decodeRecord(storage.readText(FILE_NAME))
  }

  private fun decodeRecord(text: String?): SessionCompactionRecord {
    val encoded = text.orEmpty().trim()
    if (encoded.isBlank()) {
      return SessionCompactionRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SessionCompactionRecord.serializer(),
      string = encoded,
    )
  }

  private fun encodeRecord(record: SessionCompactionRecord): String =
    PersistenceJson.instance.encodeToString(
      serializer = SessionCompactionRecord.serializer(),
      value = record,
    )

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
