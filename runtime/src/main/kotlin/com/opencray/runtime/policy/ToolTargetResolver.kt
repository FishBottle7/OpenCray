package com.opencray.runtime.policy

import com.opencray.runtime.WorkspaceBoundary
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal enum class ToolWorkspaceRelation(val wireValue: String) {
  INSIDE_WORKSPACE("inside_workspace"),
  OUTSIDE_WORKSPACE("outside_workspace"),
  MIXED("mixed"),
  NONE("none"),
}

internal class ToolTargetResolver(
  private val readBoundary: WorkspaceBoundary,
  private val writeBoundary: WorkspaceBoundary,
) {
  fun ensureReadableDirectory(
    candidate: String?,
    label: String,
    defaultToRoot: Boolean = true,
  ): Path = readBoundary.ensureDirectory(
    candidate = candidate,
    label = label,
    defaultToRoot = defaultToRoot,
  )

  fun ensureReadableFile(
    candidate: String,
    label: String,
  ): Path = readBoundary.ensureFile(candidate, label)

  fun resolveReadablePath(
    candidate: String?,
    label: String,
    defaultToRoot: Boolean = true,
  ): Path = readBoundary.resolve(
    candidate = candidate,
    label = label,
    defaultToRoot = defaultToRoot,
  )

  fun resolveWritablePath(
    candidate: String?,
    label: String,
    defaultToRoot: Boolean = true,
  ): Path = writeBoundary.resolve(
    candidate = candidate,
    label = label,
    defaultToRoot = defaultToRoot,
  )

  fun resolveSearchRoot(candidate: String?, label: String): Path {
    val resolved = resolveReadablePath(
      candidate = candidate,
      label = label,
      defaultToRoot = true,
    )
    require(Files.exists(resolved)) { "$label does not exist: $resolved" }
    require(Files.isDirectory(resolved) || Files.isRegularFile(resolved)) {
      "$label is not a file or directory: $resolved"
    }
    return resolved
  }

  fun displayModelPath(path: Path): String =
    runCatching {
      val normalized = path.toAbsolutePath().normalize()
      val writableRoot = writeBoundary.defaultRoot
      if (normalized.startsWith(writableRoot)) {
        writableRoot.relativize(normalized).toString().ifBlank { "." }
      } else {
        normalized.toString()
      }
    }.getOrDefault(path.toAbsolutePath().normalize().toString()).replace(File.separatorChar, '/')

  fun displayWritablePath(path: Path): String =
    runCatching {
      writeBoundary.defaultRoot
        .relativize(path.toAbsolutePath().normalize())
        .toString()
        .ifBlank { "." }
    }.getOrDefault(path.toAbsolutePath().normalize().toString()).replace(File.separatorChar, '/')

  fun displayPathForTool(
    toolName: String,
    path: Path,
  ): String = when (toolName) {
    "LS",
    "Read",
    "Write",
    "Grep",
    "Glob",
    "ImportFile",
    "Edit",
    "MultiEdit",
    "TodoWrite",
    -> displayModelPath(path)

    else -> displayWritablePath(path)
  }

  fun displayWorkingDirectory(rawWorkingDirectory: String?): String? {
    val normalized = rawWorkingDirectory?.trim()?.takeIf(String::isNotBlank) ?: return null
    val path = runCatching { Paths.get(normalized) }.getOrNull() ?: return normalized
    return runCatching { displayModelPath(path) }.getOrDefault(normalized)
  }

  fun workspaceRelation(
    primary: Path? = null,
    secondary: Path? = null,
  ): ToolWorkspaceRelation? {
    val candidates = listOfNotNull(primary, secondary)
      .map { it.toAbsolutePath().normalize() }
    if (candidates.isEmpty()) {
      return null
    }
    val workspaceRoot = writeBoundary.defaultRoot.toAbsolutePath().normalize()
    val insideWorkspace = candidates.map { it.startsWith(workspaceRoot) }
    return when {
      insideWorkspace.all { it } -> ToolWorkspaceRelation.INSIDE_WORKSPACE
      insideWorkspace.none { it } -> ToolWorkspaceRelation.OUTSIDE_WORKSPACE
      else -> ToolWorkspaceRelation.MIXED
    }
  }
}
