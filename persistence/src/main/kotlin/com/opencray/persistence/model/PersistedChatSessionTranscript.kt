package com.opencray.persistence.model

import com.opencray.persistence.PersistenceMigrationVersion
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.TermuxMetadataSchemaVersion
import kotlinx.serialization.Serializable

/**
 * Per-session chat transcript stored at `chat-sessions/<encoded-session-id>/transcript.json`
 * next to the workspace record. Holds the message list that used to live inline in
 * `chat-workspace.json`, so session switching never deserializes unrelated sessions.
 */
@Serializable
data class PersistedChatSessionTranscript(
  val sessionId: String,
  val messages: List<ChatTranscriptMessageEntry> = emptyList(),
  override val recordVersion: Long = 1L,
  override val createdAtEpochMs: Long,
  override val updatedAtEpochMs: Long = createdAtEpochMs,
  override val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  override val migrationVersion: Int = PersistenceMigrationVersion.CURRENT,
  override val termuxMetadataVersion: Int = TermuxMetadataSchemaVersion.CURRENT,
  override val termuxMetadata: Map<String, String> = emptyMap(),
  override val extensions: Map<String, String> = emptyMap(),
) : VersionedRecord {
  init {
    require(sessionId.isNotBlank()) { "PersistedChatSessionTranscript sessionId must not be blank." }
    require(recordVersion >= 1) { "PersistedChatSessionTranscript recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "PersistedChatSessionTranscript updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}
