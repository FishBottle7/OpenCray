package com.opencray.persistence.model

import com.opencray.persistence.PersistenceMigrationVersion
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.TermuxMetadataSchemaVersion
import kotlinx.serialization.Serializable

@Serializable
enum class ChatTranscriptRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

@Serializable
data class ChatPromptTemplateEntry(
  val templateId: String,
  val label: String,
  val body: String,
  val createdAtEpochMs: Long,
) {
  init {
    require(templateId.isNotBlank()) { "ChatPromptTemplateEntry templateId must not be blank." }
    require(label.isNotBlank()) { "ChatPromptTemplateEntry label must not be blank." }
    require(body.isNotBlank()) { "ChatPromptTemplateEntry body must not be blank." }
  }
}

@Serializable
data class ChatTranscriptMessageEntry(
  val messageId: String,
  val role: ChatTranscriptRole,
  val text: String? = null,
  val promptTemplateRefId: String? = null,
  val createdAtEpochMs: Long,
) {
  init {
    require(messageId.isNotBlank()) { "ChatTranscriptMessageEntry messageId must not be blank." }
    require(!text.isNullOrBlank() || !promptTemplateRefId.isNullOrBlank()) {
      "ChatTranscriptMessageEntry must contain text or promptTemplateRefId."
    }
  }
}

@Serializable
data class ChatTranscriptSessionEntry(
  val sessionId: String,
  val title: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val messages: List<ChatTranscriptMessageEntry> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "ChatTranscriptSessionEntry sessionId must not be blank." }
    require(title.isNotBlank()) { "ChatTranscriptSessionEntry title must not be blank." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "ChatTranscriptSessionEntry updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}

@Serializable
data class ChatWorkspaceRecord(
  val sessions: List<ChatTranscriptSessionEntry> = emptyList(),
  val promptTemplates: List<ChatPromptTemplateEntry> = emptyList(),
  val activeSessionId: String? = null,
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
    require(recordVersion >= 1) { "ChatWorkspaceRecord recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "ChatWorkspaceRecord updatedAtEpochMs must be >= createdAtEpochMs."
    }
    activeSessionId?.let { currentActiveSessionId ->
      require(sessions.any { it.sessionId == currentActiveSessionId }) {
        "ChatWorkspaceRecord activeSessionId must exist in sessions."
      }
    }
  }
}
