package com.opencray.app

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal class MemoryDebugActionAuditStore(
  directory: File,
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
  private val lock = Any()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

  init {
    require(maxEntries >= 1) { "MemoryDebugActionAuditStore maxEntries must be >= 1." }
  }

  fun list(): List<MemoryDebugActionAuditEntry> = synchronized(lock) {
    loadRecord().entries.sortedWith(
      compareByDescending<MemoryDebugActionAuditEntry>(MemoryDebugActionAuditEntry::occurredAtEpochMs)
        .thenBy(MemoryDebugActionAuditEntry::entryId),
    )
  }

  fun append(entry: MemoryDebugActionAuditEntry) {
    synchronized(lock) {
      val existing = loadRecord()
      val retainedEntries = (
        existing.entries.filterNot { persisted -> persisted.entryId == entry.entryId } + entry
        )
        .sortedWith(
          compareByDescending<MemoryDebugActionAuditEntry>(MemoryDebugActionAuditEntry::occurredAtEpochMs)
            .thenBy(MemoryDebugActionAuditEntry::entryId),
        )
        .take(maxEntries)
        .sortedWith(
          compareBy<MemoryDebugActionAuditEntry>(MemoryDebugActionAuditEntry::occurredAtEpochMs)
            .thenBy(MemoryDebugActionAuditEntry::entryId),
        )
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          createdAtEpochMs = existing.createdAtEpochMs
            .takeIf { it > 0L }
            ?.let { createdAtEpochMs -> minOf(createdAtEpochMs, entry.occurredAtEpochMs) }
            ?: entry.occurredAtEpochMs,
          updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, entry.occurredAtEpochMs),
          entries = retainedEntries,
        ),
      )
    }
  }

  fun clear(): Boolean = synchronized(lock) {
    storage.delete(FILE_NAME)
  }

  private fun loadRecord(): MemoryDebugActionAuditRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return MemoryDebugActionAuditRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = MemoryDebugActionAuditRecord.serializer(),
      string = encoded,
    )
  }

  private fun saveRecord(record: MemoryDebugActionAuditRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = MemoryDebugActionAuditRecord.serializer(),
        value = record,
      ),
    )
  }

  @Serializable
  private data class MemoryDebugActionAuditRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val entries: List<MemoryDebugActionAuditEntry> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME: String = "memory-debug-action-audit.json"
    private const val DEFAULT_MAX_ENTRIES: Int = 256
  }
}

@Serializable
internal data class MemoryDebugActionAuditEntry(
  val entryId: String,
  val recordId: String,
  val action: String,
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val occurredAtEpochMs: Long,
) {
  init {
    require(entryId.isNotBlank()) { "MemoryDebugActionAuditEntry entryId must not be blank." }
    require(recordId.isNotBlank()) { "MemoryDebugActionAuditEntry recordId must not be blank." }
    require(action.isNotBlank()) { "MemoryDebugActionAuditEntry action must not be blank." }
    require(sessionId.isNotBlank()) { "MemoryDebugActionAuditEntry sessionId must not be blank." }
    require(runId.isNotBlank()) { "MemoryDebugActionAuditEntry runId must not be blank." }
    require(taskId.isNotBlank()) { "MemoryDebugActionAuditEntry taskId must not be blank." }
    require(occurredAtEpochMs >= 0L) {
      "MemoryDebugActionAuditEntry occurredAtEpochMs must be >= 0."
    }
  }
}
