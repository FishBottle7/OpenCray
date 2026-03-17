package com.opencray.app

import android.content.Context
import com.opencray.runtime.compaction.FileBackedSessionCompactionStore
import com.opencray.runtime.compaction.SessionCompactionStore
import java.io.File

internal interface AgentSessionCompactionStoreFactory {
  fun forChatSession(sessionId: String): SessionCompactionStore
}

internal class FileBackedAgentSessionCompactionStoreFactory(
  private val runtimeRootDirectory: File,
) : AgentSessionCompactionStoreFactory {
  override fun forChatSession(sessionId: String): SessionCompactionStore {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedSessionCompactionStore(sessionDirectory)
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentSessionCompactionStoreFactory =
      FileBackedAgentSessionCompactionStoreFactory(
        runtimeRootDirectory = File(context.filesDir, FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME),
      )
  }
}
