package com.opencray.persistence.model

import com.opencray.persistence.PersistenceMigrationVersion
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.TermuxMetadataSchemaVersion
import kotlinx.serialization.Serializable

@Serializable
data class SoulRecord(
  val agentId: String,
  val displayName: String? = null,

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
    require(agentId.isNotBlank()) { "SoulRecord agentId must not be blank." }
    require(recordVersion >= 1) { "SoulRecord recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "SoulRecord updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}
