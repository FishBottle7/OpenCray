package com.opencray.app.agent

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.nio.file.Path
import kotlinx.serialization.SerializationException

internal class AgentConfigStore(
  private val pathResolver: AgentPathResolver,
  private val storageFactory: (Path) -> DurableTextStorage = { privateRoot ->
    DirectoryDurableTextStorage(privateRoot.toFile())
  },
) {
  fun load(agentId: String): AgentConfig? {
    val paths = pathResolver.resolve(agentId)
    val text = storageFor(paths).readText(AgentPathResolver.PRIVATE_CONFIG_FILE_NAME)
      ?: return null
    if (text.isBlank()) {
      return null
    }
    return try {
      PersistenceJson.instance.decodeFromString(AgentConfig.serializer(), text)
    } catch (error: SerializationException) {
      throw IllegalStateException(
        "Failed to decode persisted agent config: ${paths.privateConfigFile}",
        error,
      )
    }
  }

  fun save(
    agentId: String,
    config: AgentConfig,
  ) {
    require(config.agentId == AgentPathResolver.normalizeAgentId(agentId)) {
      "AgentConfig must be saved under its owning agent id."
    }
    val paths = pathResolver.resolve(agentId)
    storageFor(paths).updateText(AgentPathResolver.PRIVATE_CONFIG_FILE_NAME) {
      DurableTextUpdate(
        text = PersistenceJson.instance.encodeToString(AgentConfig.serializer(), config),
        result = Unit,
      )
    }
  }

  fun clear(agentId: String): Boolean {
    val paths = pathResolver.resolve(agentId)
    return storageFor(paths).updateText(AgentPathResolver.PRIVATE_CONFIG_FILE_NAME) { currentText ->
      DurableTextUpdate(
        text = null,
        result = currentText != null,
      )
    }
  }

  private fun storageFor(paths: AgentStoragePaths): DurableTextStorage =
    storageFactory(paths.privateRoot)

  companion object {
    fun fromContext(context: Context): AgentConfigStore =
      AgentConfigStore(AgentPathResolver.fromContext(context))
  }
}
