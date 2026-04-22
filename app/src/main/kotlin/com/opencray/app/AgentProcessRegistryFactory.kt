package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.AgentProcessRegistryConfig
import com.opencray.runtime.process.FileBackedAgentProcessRegistry
import com.opencray.runtime.process.LocalManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessControllerFactory
import java.io.File

internal interface AgentProcessRegistryFactory {
  fun forChatSession(sessionId: String): AgentProcessRegistry
}

internal class FileBackedAgentProcessRegistryFactory(
  private val runtimeRootDirectory: File,
  private val controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
  private val config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
) : AgentProcessRegistryFactory {
  override fun forChatSession(sessionId: String): AgentProcessRegistry {
    val sessionDirectory = directoryForSession(sessionId).apply {
      if (!exists()) {
        mkdirs()
      }
    }
    return FileBackedAgentProcessRegistry(
      directory = sessionDirectory,
      controllerFactory = controllerFactory,
      config = config,
    )
  }

  internal fun directoryForSession(sessionId: String): File =
    File(runtimeRootDirectory, FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId))

  companion object {
    fun fromContext(context: Context): AgentProcessRegistryFactory =
      FileBackedAgentProcessRegistryFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )

    fun fromAgent(
      context: Context,
      agentId: String,
      controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
      config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
      pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
    ): FileBackedAgentProcessRegistryFactory = fromAgent(
      pathResolver = pathResolver,
      agentId = agentId,
      controllerFactory = controllerFactory,
      config = config,
    )

    internal fun fromAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
      controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
      config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
    ): FileBackedAgentProcessRegistryFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = rootDirectoryForAgent(pathResolver, agentId),
      controllerFactory = controllerFactory,
      config = config,
    )

    internal fun rootDirectoryForAgent(
      pathResolver: AgentPathResolver,
      agentId: String,
    ): File = pathResolver.resolve(agentId).processRegistryRoot.toFile()
  }
}
