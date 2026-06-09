package com.opencray.app

import android.content.Context
import com.opencray.app.agent.AgentPathResolver
import java.io.File
import java.nio.file.Path

internal object AppAgentWorkspace {
  internal const val DIRECTORY_NAME: String = "agent-workspace"

  fun directoryForContext(context: Context): File =
    directoryForFilesDir(context.applicationContext.filesDir)

  internal fun directoryForFilesDir(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

  fun ensureRootForContext(context: Context): Path =
    ensureRootForFilesDir(context.applicationContext.filesDir)

  fun directoryForAgent(
    context: Context,
    agentId: String,
    pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
  ): File = directoryForAgent(pathResolver, agentId)

  internal fun directoryForAgent(
    pathResolver: AgentPathResolver,
    agentId: String,
  ): File = pathResolver.resolve(agentId).workspaceRoot.toFile()

  fun ensureRootForAgent(
    context: Context,
    agentId: String,
    pathResolver: AgentPathResolver = AgentPathResolver.fromContext(context),
  ): Path = ensureRootForAgent(pathResolver, agentId)

  internal fun ensureRootForAgent(
    pathResolver: AgentPathResolver,
    agentId: String,
  ): Path = ensureRoot(directoryForAgent(pathResolver, agentId))

  internal fun ensureRootForFilesDir(filesDir: File): Path {
    val workspaceDirectory = directoryForFilesDir(filesDir)
    return ensureRoot(workspaceDirectory)
  }

  private fun ensureRoot(workspaceDirectory: File): Path {
    if (!workspaceDirectory.exists() && !workspaceDirectory.mkdirs() && !workspaceDirectory.isDirectory) {
      throw IllegalStateException("Failed to create agent workspace directory: $workspaceDirectory")
    }
    check(workspaceDirectory.isDirectory) {
      "Agent workspace path must resolve to a directory: $workspaceDirectory"
    }
    return workspaceDirectory.toPath()
  }
}
