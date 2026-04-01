package com.opencray.app.agent

import android.content.Context
import com.opencray.persistence.PersistenceJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.SerializationException

internal class AgentConfigStore(
  private val pathResolver: AgentPathResolver,
) {
  fun load(agentId: String): AgentConfig? {
    val file = fileFor(agentId)
    if (!Files.exists(file)) {
      return null
    }
    val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    if (text.isBlank()) {
      return null
    }
    return try {
      PersistenceJson.instance.decodeFromString(AgentConfig.serializer(), text)
    } catch (error: SerializationException) {
      throw IllegalStateException("Failed to decode persisted agent config: $file", error)
    }
  }

  fun save(
    agentId: String,
    config: AgentConfig,
  ) {
    require(config.agentId == AgentPathResolver.normalizeAgentId(agentId)) {
      "AgentConfig must be saved under its owning agent id."
    }
    val file = fileFor(agentId)
    val parent = requireNotNull(file.parent) {
      "Agent config file must have a parent directory."
    }
    Files.createDirectories(parent)
    Files.write(
      file,
      PersistenceJson.instance.encodeToString(AgentConfig.serializer(), config).toByteArray(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
  }

  fun clear(agentId: String): Boolean = Files.deleteIfExists(fileFor(agentId))

  private fun fileFor(agentId: String): Path = pathResolver.resolve(agentId).privateConfigFile

  companion object {
    fun fromContext(context: Context): AgentConfigStore =
      AgentConfigStore(AgentPathResolver.fromContext(context))
  }
}
