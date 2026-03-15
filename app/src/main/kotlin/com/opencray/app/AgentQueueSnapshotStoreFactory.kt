package com.opencray.app

import android.content.Context
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.persistence.store.SessionStoreQueueSnapshotStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

internal interface AgentQueueSnapshotStoreFactory {
  fun forChatSession(sessionId: String): SessionQueueSnapshotStore
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

  companion object {
    internal const val DIRECTORY_NAME = "agent-runtime"

    internal fun encodeSessionId(sessionId: String): String {
      val normalized = sessionId.trim().ifBlank { "default-session" }
      val encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
      return "session-$encoded"
    }

    fun fromContext(context: Context): AgentQueueSnapshotStoreFactory =
      FileBackedAgentQueueSnapshotStoreFactory(
        runtimeRootDirectory = File(context.filesDir, DIRECTORY_NAME),
      )
  }
}
