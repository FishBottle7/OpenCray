package com.opencray.persistence.store.file

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.migration.JsonMigration
import com.opencray.persistence.migration.NoOpJsonMigration
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.MemoryStoreRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.model.SoulRecord
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.MemoryStore
import com.opencray.persistence.store.SessionStore
import com.opencray.persistence.store.SessionStoreUpdate
import com.opencray.persistence.store.SoulStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

class JsonFileSessionStore(
  directory: File,
  private val migration: JsonMigration = NoOpJsonMigration,
) : SessionStore {
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)
  private val fileName = "session.json"

  override fun load(): SessionRecord? = readRecord(
    name = fileName,
    serializer = SessionRecord.serializer(),
    migration = migration,
    storage = storage,
  )

  override fun save(record: SessionRecord) {
    writeRecord(
      name = fileName,
      serializer = SessionRecord.serializer(),
      value = record,
      storage = storage,
    )
  }

  override fun clear(): Boolean = storage.delete(fileName)

  override fun <T> update(update: (SessionRecord?) -> SessionStoreUpdate<T>): T =
    storage.updateRecord(
      name = fileName,
      serializer = SessionRecord.serializer(),
      migration = migration,
    ) { current ->
      val updated = update(current)
      RecordStorageUpdate(
        value = updated.record,
        result = updated.result,
      )
    }
}

class JsonFileSoulStore(
  directory: File,
  private val migration: JsonMigration = NoOpJsonMigration,
) : SoulStore {
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)
  private val fileName = "soul.json"

  override fun load(): SoulRecord? = readRecord(
    name = fileName,
    serializer = SoulRecord.serializer(),
    migration = migration,
    storage = storage,
  )

  override fun save(record: SoulRecord) {
    writeRecord(
      name = fileName,
      serializer = SoulRecord.serializer(),
      value = record,
      storage = storage,
    )
  }

  override fun clear(): Boolean = storage.delete(fileName)
}

class JsonFileMemoryStore(
  directory: File,
  private val migration: JsonMigration = NoOpJsonMigration,
) : MemoryStore {
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)
  private val fileName = "memory.json"

  override fun list(): List<MemoryRecord> {
    val state = readState() ?: return emptyList()
    return state.records.sortedWith(compareBy<MemoryRecord> { it.createdAtEpochMs }.thenBy { it.id })
  }

  override fun upsert(record: MemoryRecord) {
    storage.updateRecord(
      name = fileName,
      serializer = MemoryStoreRecord.serializer(),
      migration = migration,
    ) { state ->
      val now = record.updatedAtEpochMs
      val updatedRecords = (state?.records.orEmpty().filterNot { it.id == record.id } + record)
        .sortedWith(compareBy<MemoryRecord> { it.createdAtEpochMs }.thenBy { it.id })

      RecordStorageUpdate(
        value = MemoryStoreRecord(
          records = updatedRecords,
          createdAtEpochMs = state?.createdAtEpochMs ?: now,
          updatedAtEpochMs = now,
          recordVersion = (state?.recordVersion ?: 0L) + 1L,
          termuxMetadata = state?.termuxMetadata.orEmpty(),
          extensions = state?.extensions.orEmpty(),
        ),
        result = Unit,
      )
    }
  }

  override fun delete(id: String): Boolean {
    return storage.updateRecord(
      name = fileName,
      serializer = MemoryStoreRecord.serializer(),
      migration = migration,
    ) { state ->
      val existing = state ?: return@updateRecord RecordStorageUpdate(
        value = null,
        result = false,
        write = false,
      )
      val updated = existing.records.filterNot { it.id == id }
      if (updated.size == existing.records.size) {
        return@updateRecord RecordStorageUpdate(
          value = existing,
          result = false,
          write = false,
        )
      }

      RecordStorageUpdate(
        value = existing.copy(
          records = updated,
          updatedAtEpochMs = existing.updatedAtEpochMs,
          recordVersion = existing.recordVersion + 1,
        ),
        result = true,
      )
    }
  }

  override fun clear(): Boolean = storage.delete(fileName)

  private fun readState(): MemoryStoreRecord? {
    return readRecord(
      name = fileName,
      serializer = MemoryStoreRecord.serializer(),
      migration = migration,
      storage = storage,
    )
  }
}

internal fun extractSchemaVersion(json: String): Int {
  val element = PersistenceJson.instance.parseToJsonElement(json)
  val obj = element as? JsonObject ?: return 0
  val v = obj["schemaVersion"] ?: return 0
  return (v as? JsonPrimitive)?.intOrNull ?: 0
}

internal fun <T : Any> readRecord(
  storage: DurableTextStorage,
  name: String,
  serializer: KSerializer<T>,
  migration: JsonMigration,
): T? {
  val text = storage.readText(name) ?: return null
  if (text.isBlank()) return null

  val schemaVersion = extractSchemaVersion(text)
  val migrated = if (schemaVersion == PersistenceSchemaVersion.CURRENT) {
    text
  } else {
    migration.migrate(schemaVersion, text)
  }

  return try {
    PersistenceJson.instance.decodeFromString(serializer, migrated)
  } catch (e: SerializationException) {
    throw IllegalStateException("Failed to decode persisted record: $name", e)
  }
}

internal fun <T : Any> writeRecord(
  storage: DurableTextStorage,
  name: String,
  serializer: KSerializer<T>,
  value: T,
) {
  val encoded = PersistenceJson.instance.encodeToString(serializer, value)
  storage.writeText(name, encoded)
}

data class RecordStorageUpdate<T : Any, R>(
  val value: T?,
  val result: R,
  val write: Boolean = true,
)

fun <T : Any, R> DurableTextStorage.updateRecord(
  name: String,
  serializer: KSerializer<T>,
  migration: JsonMigration = NoOpJsonMigration,
  update: (T?) -> RecordStorageUpdate<T, R>,
): R = updateText(name) { current ->
  val record = decodeRecordOrNull(
    name = name,
    text = current,
    serializer = serializer,
    migration = migration,
  )
  val updated = update(record)
  DurableTextUpdate(
    text = if (updated.write) {
      updated.value?.let { value -> PersistenceJson.instance.encodeToString(serializer, value) }
    } else {
      current
    },
    result = updated.result,
    write = updated.write,
  )
}

private fun <T : Any> decodeRecordOrNull(
  name: String,
  text: String?,
  serializer: KSerializer<T>,
  migration: JsonMigration,
): T? {
  val encoded = text?.trim().orEmpty()
  if (encoded.isBlank()) return null

  val schemaVersion = extractSchemaVersion(encoded)
  val migrated = if (schemaVersion == PersistenceSchemaVersion.CURRENT) {
    encoded
  } else {
    migration.migrate(schemaVersion, encoded)
  }

  return try {
    PersistenceJson.instance.decodeFromString(serializer, migrated)
  } catch (e: SerializationException) {
    throw IllegalStateException("Failed to decode persisted record: $name", e)
  }
}
