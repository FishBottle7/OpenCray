package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.persistence.store.SessionStoreQueueSnapshotStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

internal interface AgentQueueSnapshotStoreFactory {
  fun forChatSession(sessionId: String): SessionQueueSnapshotStore

  fun knownSessionIds(): List<String> = emptyList()
}

internal class FileBackedAgentQueueSnapshotStoreFactory(
  private val runtimeRootDirectory: File,
) : AgentQueueSnapshotStoreFactory {
  override fun forChatSession(sessionId: String): SessionQueueSnapshotStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return SessionStoreQueueSnapshotStore(JsonFileSessionStore(sessionDirectory))
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, encodeSessionId(sessionId))

  override fun knownSessionIds(): List<String> = runtimeRootDirectory.listFiles()
    .orEmpty()
    .asSequence()
    .filter(File::isDirectory)
    .mapNotNull { directory -> decodeSessionId(directory.name) }
    .distinct()
    .toList()

  companion object {
    internal const val DIRECTORY_NAME = "agent-runtime"

    internal fun encodeSessionId(sessionId: String): String {
      val normalized = sessionId.trim().ifBlank { "default-session" }
      val encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
      return "session-$encoded"
    }

    internal fun decodeSessionId(directoryName: String): String? {
      val encoded = directoryName.removePrefix("session-")
        .takeIf { encodedValue -> encodedValue.isNotBlank() && directoryName.startsWith("session-") }
        ?: return null
      return runCatching {
        String(
          Base64.getUrlDecoder().decode(encoded),
          StandardCharsets.UTF_8,
        ).trim().takeIf(String::isNotBlank)
      }.getOrNull()
    }

    fun fromContext(context: Context): AgentQueueSnapshotStoreFactory =
      FileBackedAgentQueueSnapshotStoreFactory(
        runtimeRootDirectory = File(context.filesDir, DIRECTORY_NAME),
      )

    fun fromAgent(
      context: Context,
      agentId: String,
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): FileBackedAgentQueueSnapshotStoreFactory = fromAgent(pathResolver, agentId)

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): FileBackedAgentQueueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(
      runtimeRootDirectory = rootDirectoryForAgent(pathResolver, agentId),
    )

    internal fun rootDirectoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).queueSnapshotsRoot.toFile()
  }
}
