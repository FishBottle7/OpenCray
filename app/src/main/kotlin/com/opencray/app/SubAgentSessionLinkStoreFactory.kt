package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentSessionLink
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

internal interface SubAgentSessionLinkStoreFactory {
  fun forChatSession(sessionId: String): SubAgentSessionLinkStore
}

internal interface SubAgentSessionLinkStore {
  fun list(): List<SubAgentSessionLink>

  fun listForParentRun(parentRunId: String): List<SubAgentSessionLink>

  fun get(parentRunId: String, agentId: String): SubAgentSessionLink?

  fun upsert(link: SubAgentSessionLink)

  fun retainKnownParentRuns(parentRunIds: Set<String>)

  fun clear()
}

internal fun inMemorySubAgentSessionLinkStoreFactory(): SubAgentSessionLinkStoreFactory =
  InMemorySubAgentSessionLinkStoreFactory()

internal class InMemorySubAgentSessionLinkStoreFactory : SubAgentSessionLinkStoreFactory {
  private val lock = Any()
  private val stores = linkedMapOf<String, SubAgentSessionLinkStore>()

  override fun forChatSession(sessionId: String): SubAgentSessionLinkStore = synchronized(lock) {
    stores.getOrPut(sessionId) { InMemorySubAgentSessionLinkStore() }
  }
}

internal class FileBackedSubAgentSessionLinkStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: SubAgentSessionLinkStoreConfig = SubAgentSessionLinkStoreConfig(),
) : SubAgentSessionLinkStoreFactory {
  override fun forChatSession(sessionId: String): SubAgentSessionLinkStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedSubAgentSessionLinkStore(
      sessionId = sessionId,
      directory = sessionDirectory,
      config = config,
    )
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): SubAgentSessionLinkStoreFactory =
      FileBackedSubAgentSessionLinkStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal data class SubAgentSessionLinkStoreConfig(
  val maxTrackedLinks: Int = 256,
) {
  init {
    require(maxTrackedLinks >= 1) {
      "SubAgentSessionLinkStoreConfig maxTrackedLinks must be >= 1."
    }
  }
}

private class InMemorySubAgentSessionLinkStore : SubAgentSessionLinkStore {
  private val lock = Any()
  private val linksByKey = linkedMapOf<SubAgentExecutionKey, SubAgentSessionLink>()

  override fun list(): List<SubAgentSessionLink> = synchronized(lock) {
    linksByKey.values.sortedByDescending(SubAgentSessionLink::updatedAtEpochMs)
  }

  override fun listForParentRun(parentRunId: String): List<SubAgentSessionLink> = synchronized(lock) {
    linksByKey.values
      .filter { link -> link.parentRunId == parentRunId }
      .sortedByDescending(SubAgentSessionLink::updatedAtEpochMs)
  }

  override fun get(parentRunId: String, agentId: String): SubAgentSessionLink? = synchronized(lock) {
    linksByKey[SubAgentExecutionKey(parentRunId = parentRunId, agentId = agentId)]
  }

  override fun upsert(link: SubAgentSessionLink) {
    synchronized(lock) {
      linksByKey[SubAgentExecutionKey(parentRunId = link.parentRunId, agentId = link.agentId)] = link
    }
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      linksByKey.entries.removeIf { (_, link) -> link.parentRunId !in parentRunIds }
    }
  }

  override fun clear() {
    synchronized(lock) {
      linksByKey.clear()
    }
  }
}

private class FileBackedSubAgentSessionLinkStore(
  private val sessionId: String,
  directory: File,
  private val config: SubAgentSessionLinkStoreConfig,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : SubAgentSessionLinkStore {
  private val lock = lockFor(directory)
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)

  override fun list(): List<SubAgentSessionLink> = synchronized(lock) {
    loadNormalizedRecord().links
  }

  override fun listForParentRun(parentRunId: String): List<SubAgentSessionLink> = synchronized(lock) {
    loadNormalizedRecord().links
      .filter { link -> link.parentRunId == parentRunId }
  }

  override fun get(parentRunId: String, agentId: String): SubAgentSessionLink? = synchronized(lock) {
    loadNormalizedRecord().links.firstOrNull { link ->
      link.parentRunId == parentRunId && link.agentId == agentId
    }
  }

  override fun upsert(link: SubAgentSessionLink) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val normalizedLinks = normalizeLinks(
        existing.links.filterNot { stored ->
          stored.parentRunId == link.parentRunId && stored.agentId == link.agentId
        } + link,
      )
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          links = normalizedLinks,
        ),
      )
    }
  }

  override fun retainKnownParentRuns(parentRunIds: Set<String>) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val retained = existing.links.filter { link -> link.parentRunId in parentRunIds }
      if (retained.size == existing.links.size) {
        return
      }
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          links = retained,
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): SubAgentSessionLinkRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return SubAgentSessionLinkRecord(sessionId = sessionId)
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SubAgentSessionLinkRecord.serializer(),
      string = encoded,
    )
  }

  private fun loadNormalizedRecord(): SubAgentSessionLinkRecord {
    val existing = loadRecord()
    val normalizedLinks = normalizeLinks(existing.links)
    if (normalizedLinks == existing.links && existing.sessionId == sessionId) {
      return existing
    }
    val repaired = existing.copy(
      sessionId = sessionId,
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      links = normalizedLinks,
    )
    saveRecord(repaired)
    return repaired
  }

  private fun normalizeLinks(
    links: List<SubAgentSessionLink>,
  ): List<SubAgentSessionLink> {
    val deduped = linkedMapOf<SubAgentExecutionKey, SubAgentSessionLink>()
    links
      .sortedWith(
        compareByDescending<SubAgentSessionLink>(SubAgentSessionLink::updatedAtEpochMs)
          .thenByDescending(SubAgentSessionLink::createdAtEpochMs),
      )
      .forEach { link ->
        val key = SubAgentExecutionKey(parentRunId = link.parentRunId, agentId = link.agentId)
        if (key !in deduped) {
          deduped[key] = link.copy(
            parentSessionId = link.parentSessionId.trim(),
            parentRunId = link.parentRunId.trim(),
            agentId = link.agentId.trim(),
            childSessionId = link.childSessionId.trim(),
            childRootRunId = link.childRootRunId?.trim()?.takeIf(String::isNotBlank),
            childRootTaskId = link.childRootTaskId?.trim()?.takeIf(String::isNotBlank),
            subagentType = link.subagentType.trim(),
            contextMode = link.contextMode.trim(),
            label = link.label.trim(),
            status = link.status.trim(),
          )
        }
      }
    return deduped.values
      .sortedByDescending(SubAgentSessionLink::updatedAtEpochMs)
      .take(config.maxTrackedLinks)
  }

  private fun saveRecord(record: SubAgentSessionLinkRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = SubAgentSessionLinkRecord.serializer(),
        value = record,
      ),
    )
  }

  @Serializable
  private data class SubAgentSessionLinkRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val sessionId: String,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val links: List<SubAgentSessionLink> = emptyList(),
  )

  private companion object {
    private const val FILE_NAME: String = "runtime-subagent-session-links.json"

    private val FILE_LOCKS = ConcurrentHashMap<String, Any>()

    private fun lockFor(directory: File): Any =
      FILE_LOCKS.computeIfAbsent(File(directory, FILE_NAME).absolutePath) { Any() }
  }
}
