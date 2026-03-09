package com.opencray.persistence.model

import com.opencray.persistence.PersistenceMigrationVersion
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.TermuxMetadataSchemaVersion
import kotlinx.serialization.Serializable

@Serializable
data class SessionRecord(
  val sessionId: String,
  val agentId: String,

  /**
   * JSON-safe key/value bag reserved for downstream orchestration state.
   *
   * Task 6 will own queue/session lifecycle and can extend this payload.
   */
  val state: Map<String, String> = emptyMap(),

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
    require(sessionId.isNotBlank()) { "SessionRecord sessionId must not be blank." }
    require(agentId.isNotBlank()) { "SessionRecord agentId must not be blank." }
    require(recordVersion >= 1) { "SessionRecord recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "SessionRecord updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}
