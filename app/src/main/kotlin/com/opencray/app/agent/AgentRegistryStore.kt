package com.opencray.app.agent

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
internal data class AgentRegistryRecord(
  val agents: List<AgentDescriptor>,
  val activeAgentId: String? = null,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val recordVersion: Long,
) {
  init {
    require(createdAtEpochMs >= 0L) { "AgentRegistryRecord createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0L) { "AgentRegistryRecord updatedAtEpochMs must be >= 0." }
    require(recordVersion >= 0L) { "AgentRegistryRecord recordVersion must be >= 0." }
    require(activeAgentId == null || activeAgentId.isNotBlank()) {
      "AgentRegistryRecord activeAgentId must not be blank when present."
    }
  }
}

internal class AgentRegistryStore(
  directory: File,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  private val storage = DirectoryDurableTextStorage(directory)

  fun load(): AgentRegistryRecord? {
    val text = storage.readText(FILE_NAME) ?: return null
    if (text.isBlank()) {
      return null
    }
    return PersistenceJson.instance.decodeFromString(AgentRegistryRecord.serializer(), text)
      .normalizedRegistryRecord()
  }

  fun list(includeArchived: Boolean = false): List<AgentDescriptor> =
    load()
      ?.agents
      .orEmpty()
      .filter { descriptor -> includeArchived || !descriptor.isArchived }

  fun loadAgent(agentId: String): AgentDescriptor? {
    val normalizedAgentId = AgentPathResolver.normalizeAgentId(agentId)
    return load()?.agents?.firstOrNull { descriptor -> descriptor.agentId == normalizedAgentId }
  }

  fun activeAgentId(): String? = load()?.activeAgentId

  fun loadActiveAgent(): AgentDescriptor? {
    val record = load() ?: return null
    return record.activeAgentId?.let { activeAgentId ->
      record.agents.firstOrNull { descriptor -> descriptor.agentId == activeAgentId }
    }
  }

  fun create(
    descriptor: AgentDescriptor,
    makeActive: Boolean = false,
  ): AgentRegistryRecord {
    val current = loadOrEmpty()
    require(current.agents.none { existing -> existing.agentId == descriptor.agentId }) {
      "Agent already exists in registry: ${descriptor.agentId}"
    }
    val now = nowEpochMs().coerceAtLeast(descriptor.updatedAtEpochMs)
    val normalizedDescriptor = descriptor.copy(
      updatedAtEpochMs = now,
    )
    val updatedAgents = sortDescriptors(current.agents + normalizedDescriptor)
    val nextActiveAgentId = when {
      makeActive && !normalizedDescriptor.isArchived -> normalizedDescriptor.agentId
      current.activeAgentId != null -> current.activeAgentId
      normalizedDescriptor.isArchived -> null
      else -> normalizedDescriptor.agentId
    }
    return persist(
      current = current,
      agents = updatedAgents,
      activeAgentId = nextActiveAgentId,
      updatedAtEpochMs = now,
    )
  }

  fun update(
    descriptor: AgentDescriptor,
    makeActive: Boolean = false,
  ): AgentRegistryRecord {
    val current = loadOrEmpty()
    val existing = current.agents.firstOrNull { entry -> entry.agentId == descriptor.agentId }
      ?: throw IllegalArgumentException("Agent does not exist in registry: ${descriptor.agentId}")
    val now = nowEpochMs().coerceAtLeast(descriptor.updatedAtEpochMs)
    val normalizedDescriptor = descriptor.copy(
      createdAtEpochMs = existing.createdAtEpochMs,
      updatedAtEpochMs = now,
    )
    val updatedAgents = sortDescriptors(
      current.agents
        .filterNot { entry -> entry.agentId == normalizedDescriptor.agentId } + normalizedDescriptor,
    )
    val nextActiveAgentId = when {
      makeActive && normalizedDescriptor.isArchived ->
        throw IllegalArgumentException("Archived agent cannot become active: ${normalizedDescriptor.agentId}")

      makeActive -> normalizedDescriptor.agentId
      current.activeAgentId == normalizedDescriptor.agentId && normalizedDescriptor.isArchived ->
        selectFallbackActiveAgentId(updatedAgents, excludeAgentId = normalizedDescriptor.agentId)

      current.activeAgentId == null && !normalizedDescriptor.isArchived -> normalizedDescriptor.agentId
      else -> current.activeAgentId
    }
    return persist(
      current = current,
      agents = updatedAgents,
      activeAgentId = nextActiveAgentId,
      updatedAtEpochMs = now,
    )
  }

  fun select(agentId: String): AgentRegistryRecord {
    val normalizedAgentId = AgentPathResolver.normalizeAgentId(agentId)
    val current = loadOrEmpty()
    val descriptor = current.agents.firstOrNull { entry -> entry.agentId == normalizedAgentId }
      ?: throw IllegalArgumentException("Agent does not exist in registry: $normalizedAgentId")
    require(!descriptor.isArchived) {
      "Archived agent cannot become active: $normalizedAgentId"
    }
    if (current.activeAgentId == normalizedAgentId) {
      return current
    }
    val now = nowEpochMs().coerceAtLeast(current.updatedAtEpochMs)
    return persist(
      current = current,
      agents = current.agents,
      activeAgentId = normalizedAgentId,
      updatedAtEpochMs = now,
    )
  }

  fun archive(agentId: String): AgentRegistryRecord {
    val normalizedAgentId = AgentPathResolver.normalizeAgentId(agentId)
    val current = loadOrEmpty()
    val existing = current.agents.firstOrNull { entry -> entry.agentId == normalizedAgentId }
      ?: throw IllegalArgumentException("Agent does not exist in registry: $normalizedAgentId")
    if (existing.isArchived) {
      return current
    }
    val now = nowEpochMs().coerceAtLeast(existing.updatedAtEpochMs)
    val archivedDescriptor = existing.copy(
      isArchived = true,
      updatedAtEpochMs = now,
    )
    val updatedAgents = sortDescriptors(
      current.agents.filterNot { entry -> entry.agentId == normalizedAgentId } + archivedDescriptor,
    )
    val nextActiveAgentId = if (current.activeAgentId == normalizedAgentId) {
      selectFallbackActiveAgentId(updatedAgents, excludeAgentId = normalizedAgentId)
    } else {
      current.activeAgentId
    }
    return persist(
      current = current,
      agents = updatedAgents,
      activeAgentId = nextActiveAgentId,
      updatedAtEpochMs = now,
    )
  }

  fun clear(): Boolean = storage.delete(FILE_NAME)

  private fun loadOrEmpty(): AgentRegistryRecord {
    val loaded = load()
    if (loaded != null) {
      return loaded
    }
    val now = nowEpochMs().coerceAtLeast(0L)
    return AgentRegistryRecord(
      agents = emptyList(),
      activeAgentId = null,
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      recordVersion = 0L,
    )
  }

  private fun persist(
    current: AgentRegistryRecord,
    agents: List<AgentDescriptor>,
    activeAgentId: String?,
    updatedAtEpochMs: Long,
  ): AgentRegistryRecord {
    val normalizedActiveAgentId = activeAgentId?.takeIf { candidate ->
      agents.any { descriptor -> descriptor.agentId == candidate && !descriptor.isArchived }
    }
    val updated = AgentRegistryRecord(
      agents = sortDescriptors(agents),
      activeAgentId = normalizedActiveAgentId,
      createdAtEpochMs = current.createdAtEpochMs,
      updatedAtEpochMs = updatedAtEpochMs,
      recordVersion = current.recordVersion + 1L,
    )
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(AgentRegistryRecord.serializer(), updated),
    )
    return updated
  }

  private fun AgentRegistryRecord.normalizedRegistryRecord(): AgentRegistryRecord {
    val normalizedAgents = sortDescriptors(agents)
    val normalizedActiveAgentId = activeAgentId?.takeIf { candidate ->
      normalizedAgents.any { descriptor -> descriptor.agentId == candidate && !descriptor.isArchived }
    }
    return copy(
      agents = normalizedAgents,
      activeAgentId = normalizedActiveAgentId,
    )
  }

  private fun sortDescriptors(descriptors: List<AgentDescriptor>): List<AgentDescriptor> =
    descriptors.sortedWith(
      compareByDescending<AgentDescriptor> { descriptor -> descriptor.updatedAtEpochMs }
        .thenByDescending { descriptor -> descriptor.createdAtEpochMs }
        .thenBy { descriptor -> descriptor.agentId },
    )

  private fun selectFallbackActiveAgentId(
    descriptors: List<AgentDescriptor>,
    excludeAgentId: String,
  ): String? = descriptors.firstOrNull { descriptor ->
    descriptor.agentId != excludeAgentId && !descriptor.isArchived
  }?.agentId

  companion object {
    private const val FILE_NAME = "agents.json"

    fun fromContext(context: Context): AgentRegistryStore =
      AgentRegistryStore(AgentPathResolver.fromContext(context).registryDirectory().toFile())
  }
}
