package com.opencray.app

import android.content.Context
import java.io.File
import java.nio.file.Path

internal object AppAgentWorkspace {
  internal const val DIRECTORY_NAME: String = "agent-workspace"

  fun directoryForContext(context: Context): File =
    directoryForFilesDir(context.applicationContext.filesDir)

  internal fun directoryForFilesDir(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

  fun ensureRootForContext(context: Context): Path =
    ensureRootForFilesDir(context.applicationContext.filesDir)

  internal fun ensureRootForFilesDir(filesDir: File): Path {
    val workspaceDirectory = directoryForFilesDir(filesDir)
    if (!workspaceDirectory.exists() && !workspaceDirectory.mkdirs() && !workspaceDirectory.isDirectory) {
      throw IllegalStateException("Failed to create agent workspace directory: $workspaceDirectory")
    }
    check(workspaceDirectory.isDirectory) {
      "Agent workspace path must resolve to a directory: $workspaceDirectory"
    }
    return workspaceDirectory.toPath()
  }
}
