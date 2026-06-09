package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.persistence.store.SessionStoreQueueSnapshotStore
import com.opencray.persistence.store.file.JsonFileMemoryStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import java.io.File

internal class PersonalizationLocalStore(
  private val directory: File,
) {
  private val memoryStore = JsonFileMemoryStore(directory)
  private val sessionStore = JsonFileSessionStore(directory)
  private val queueSnapshotStore = SessionStoreQueueSnapshotStore(sessionStore)
  private val memoryDebugActionAuditStore = MemoryDebugActionAuditStore(directory)

  internal fun listMemoryRecords(): List<MemoryRecord> = memoryStore.list()

  internal fun upsertMemoryRecord(record: MemoryRecord) {
    memoryStore.upsert(record)
  }

  internal fun listMemoryDebugActionAudits(): List<MemoryDebugActionAuditEntry> =
    memoryDebugActionAuditStore.list()

  internal fun appendMemoryDebugActionAudit(entry: MemoryDebugActionAuditEntry) {
    memoryDebugActionAuditStore.append(entry)
  }

  internal fun asMemoryStore(): MemoryStore = memoryStore

  fun clearMemoryAndHistory() {
    memoryStore.clear()
    queueSnapshotStore.clear()
    memoryDebugActionAuditStore.clear()
  }

  companion object {
    internal const val DIRECTORY_NAME = "personalization-local-state"

    fun fromContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): PersonalizationLocalStore = PersonalizationLocalStore(directoryForContext(context, directoryName))

    fun fromAgent(
      context: Context,
      agentId: String,
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): PersonalizationLocalStore = fromAgent(pathResolver, agentId)

    fun directoryForContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): File = File(context.filesDir, directoryName)

    internal fun directoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).personalizationLocalStateRoot.toFile()

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): PersonalizationLocalStore = PersonalizationLocalStore(directoryForAgent(pathResolver, agentId))
  }
}
