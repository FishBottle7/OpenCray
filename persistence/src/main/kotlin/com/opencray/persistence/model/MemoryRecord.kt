package com.opencray.persistence.model

import com.opencray.persistence.PersistenceMigrationVersion
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.TermuxMetadataSchemaVersion
import kotlinx.serialization.Serializable

@Serializable
data class MemoryRecord(
  val id: String,
  val content: String,
  val tags: List<String> = emptyList(),

  override val recordVersion: Long = 1,
  override val createdAtEpochMs: Long,
  override val updatedAtEpochMs: Long = createdAtEpochMs,

  override val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  override val migrationVersion: Int = PersistenceMigrationVersion.CURRENT,
  override val termuxMetadataVersion: Int = TermuxMetadataSchemaVersion.CURRENT,
  override val termuxMetadata: Map<String, String> = emptyMap(),
  override val extensions: Map<String, String> = emptyMap(),
) : VersionedRecord {
  init {
    require(id.isNotBlank()) { "MemoryRecord id must not be blank." }
    require(content.isNotBlank()) { "MemoryRecord content must not be blank." }
    require(recordVersion >= 1) { "MemoryRecord recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "MemoryRecord updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}

@Serializable
data class MemoryStoreRecord(
  val records: List<MemoryRecord> = emptyList(),

  override val recordVersion: Long = 1,
  override val createdAtEpochMs: Long,
  override val updatedAtEpochMs: Long = createdAtEpochMs,

  override val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  override val migrationVersion: Int = PersistenceMigrationVersion.CURRENT,
  override val termuxMetadataVersion: Int = TermuxMetadataSchemaVersion.CURRENT,
  override val termuxMetadata: Map<String, String> = emptyMap(),
  override val extensions: Map<String, String> = emptyMap(),
) : VersionedRecord {
  init {
    require(recordVersion >= 1) { "MemoryStoreRecord recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "MemoryStoreRecord updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}
