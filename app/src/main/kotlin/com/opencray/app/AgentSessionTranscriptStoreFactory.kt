package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.session.SessionTranscriptStore
import com.opencray.runtime.session.SessionTranscriptRules
import java.io.File
import kotlinx.serialization.Serializable

internal interface AgentSessionTranscriptStoreFactory {
  fun forChatSession(sessionId: String): SessionTranscriptStore
}

internal class FileBackedAgentSessionTranscriptStoreFactory(
  private val runtimeRootDirectory: File,
) : AgentSessionTranscriptStoreFactory {
  override fun forChatSession(sessionId: String): SessionTranscriptStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedSessionTranscriptStore(sessionDirectory)
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentSessionTranscriptStoreFactory =
      FileBackedAgentSessionTranscriptStoreFactory(
        runtimeRootDirectory = File(context.filesDir, FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME),
      )
  }
}

private class FileBackedSessionTranscriptStore(
  directory: File,
) : SessionTranscriptStore {
  private val lock = Any()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

  override fun snapshot(): List<RuntimeConversationMessage> = synchronized(lock) {
    loadNormalizedRecord().messages
  }

  override fun seedIfEmpty(messages: List<RuntimeConversationMessage>) {
    val normalized = SessionTranscriptRules.normalize(messages)
    if (normalized.isEmpty()) {
      return
    }
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      if (existing.messages.isNotEmpty()) {
        return
      }
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = System.currentTimeMillis(),
          messages = normalized,
        ),
      )
    }
  }

  override fun appendIfDistinct(message: RuntimeConversationMessage) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val normalizedMessages = SessionTranscriptRules.normalize(existing.messages + message)
      if (normalizedMessages == existing.messages) {
        return
      }
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = System.currentTimeMillis(),
          messages = normalizedMessages,
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SessionTranscriptRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return SessionTranscriptRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SessionTranscriptRecord.serializer(),
      string = encoded,
    )
  }

  private fun loadNormalizedRecord(): SessionTranscriptRecord {
    val existing = loadRecord()
    val normalizedMessages = SessionTranscriptRules.normalize(existing.messages)
    if (normalizedMessages == existing.messages) {
      return existing
    }
    val repaired = existing.copy(
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = System.currentTimeMillis(),
      messages = normalizedMessages,
    )
    saveRecord(repaired)
    return repaired
  }

  private fun saveRecord(record: SessionTranscriptRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = SessionTranscriptRecord.serializer(),
        value = record,
      ),
    )
  }

  @Serializable
  private data class SessionTranscriptRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val messages: List<RuntimeConversationMessage> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME = "runtime-transcript.json"
  }
}
