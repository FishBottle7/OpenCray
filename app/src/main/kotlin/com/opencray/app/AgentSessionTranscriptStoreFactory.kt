package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
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
    return FileBackedSessionTranscriptStore(
      storage = DirectoryDurableTextStorage(sessionDirectory),
    )
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentSessionTranscriptStoreFactory =
      FileBackedAgentSessionTranscriptStoreFactory(
        runtimeRootDirectory = File(context.filesDir, FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME),
      )

    fun fromAgent(
      context: Context,
      agentId: String,
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): FileBackedAgentSessionTranscriptStoreFactory = fromAgent(pathResolver, agentId)

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): FileBackedAgentSessionTranscriptStoreFactory = FileBackedAgentSessionTranscriptStoreFactory(
      runtimeRootDirectory = rootDirectoryForAgent(pathResolver, agentId),
    )

    internal fun rootDirectoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).transcriptStoreRoot.toFile()
  }
}

internal fun fileBackedSessionTranscriptStore(
  storage: DurableTextStorage,
  clock: () -> Long = System::currentTimeMillis,
): SessionTranscriptStore = FileBackedSessionTranscriptStore(
  storage = storage,
  clock = clock,
)

private class FileBackedSessionTranscriptStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : SessionTranscriptStore {
  private val lock = Any()

  override fun snapshot(): List<RuntimeConversationMessage> = synchronized(lock) {
    loadNormalizedRecord().messages
  }

  override fun seedIfEmpty(messages: List<RuntimeConversationMessage>) {
    val normalized = SessionTranscriptRules.normalize(messages)
    if (normalized.isEmpty()) {
      return
    }
    synchronized(lock) {
      updateRecord { current ->
        val normalizedCurrentMessages = SessionTranscriptRules.normalize(current.messages)
        if (normalizedCurrentMessages.isNotEmpty()) {
          val normalizedCurrent = current.copy(
            recordVersion = current.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            messages = normalizedCurrentMessages,
          )
          return@updateRecord RecordStorageUpdate(
            value = normalizedCurrent,
            result = Unit,
            write = normalizedCurrentMessages != current.messages,
          )
        }
        RecordStorageUpdate(
          value = current.copy(
            recordVersion = current.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            messages = normalized,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun appendIfDistinct(message: RuntimeConversationMessage) {
    synchronized(lock) {
      updateRecord { existing ->
        val normalizedMessages = SessionTranscriptRules.normalize(existing.messages + message)
        if (normalizedMessages == existing.messages) {
          return@updateRecord RecordStorageUpdate(
            value = existing,
            result = Unit,
            write = false,
          )
        }
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            messages = normalizedMessages,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun replace(messages: List<RuntimeConversationMessage>) {
    val normalized = SessionTranscriptRules.normalize(messages)
    synchronized(lock) {
      updateRecord { existing ->
        if (normalized == existing.messages) {
          return@updateRecord RecordStorageUpdate(
            value = existing,
            result = Unit,
            write = false,
          )
        }
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            messages = normalized,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadNormalizedRecord(): SessionTranscriptRecord {
    return updateRecord { existing ->
      val normalizedMessages = SessionTranscriptRules.normalize(existing.messages)
      if (normalizedMessages == existing.messages) {
        return@updateRecord RecordStorageUpdate(
          value = existing,
          result = existing,
          write = false,
        )
      }
      val repaired = existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        messages = normalizedMessages,
      )
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
      )
    }
  }

  private fun <R> updateRecord(
    update: (SessionTranscriptRecord) -> RecordStorageUpdate<SessionTranscriptRecord, R>,
  ): R = storage.updateRecord(
    name = FILE_NAME,
    serializer = SessionTranscriptRecord.serializer(),
  ) { persisted ->
    update(persisted ?: SessionTranscriptRecord())
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
