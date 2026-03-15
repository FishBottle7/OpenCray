package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal object AppAgentWorkspaceFileOperations {
  fun createDirectory(
    workspaceRoot: Path,
    parentRelativePath: String,
    name: String,
  ) {
    val normalizedName = validateName(name)
    val parentDirectory = resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = parentRelativePath,
      allowRoot = true,
    )
    require(Files.isDirectory(parentDirectory)) {
      "Destination directory does not exist: ${parentDirectory.fileName ?: parentDirectory}"
    }
    val destination = parentDirectory.resolve(normalizedName).normalize()
    require(!destination.exists()) {
      "An item named '$normalizedName' already exists."
    }
    Files.createDirectory(destination)
  }

  fun renameEntry(
    workspaceRoot: Path,
    targetRelativePath: String,
    newName: String,
  ) {
    val source = resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = targetRelativePath,
      allowRoot = false,
    )
    require(source.exists()) {
      "The selected item no longer exists."
    }
    val normalizedName = validateName(newName)
    val destination = source.parent.resolve(normalizedName).normalize()
    if (source == destination) {
      return
    }
    require(!destination.exists()) {
      "An item named '$normalizedName' already exists."
    }
    Files.move(source, destination)
  }

  fun deleteEntries(
    workspaceRoot: Path,
    relativePaths: List<String>,
  ) {
    val uniquePaths = relativePaths
      .filter(String::isNotBlank)
      .distinct()
      .map { relativePath ->
        resolvePath(
          workspaceRoot = workspaceRoot,
          relativePath = relativePath,
          allowRoot = false,
        )
      }
      .sortedByDescending { path -> path.nameCount }
    for (path in uniquePaths) {
      if (!path.exists()) {
        continue
      }
      deleteRecursively(path)
    }
  }

  fun pasteEntries(
    workspaceRoot: Path,
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ) {
    val destinationDirectory = resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = destinationRelativePath,
      allowRoot = true,
    )
    require(destinationDirectory.isDirectory()) {
      "Paste destination is unavailable."
    }
    val sources = sourceRelativePaths
      .filter(String::isNotBlank)
      .distinct()
      .map { relativePath ->
        resolvePath(
          workspaceRoot = workspaceRoot,
          relativePath = relativePath,
          allowRoot = false,
        )
      }
    require(sources.isNotEmpty()) { "Nothing is selected to paste." }

    for (source in sources) {
      val target = destinationDirectory.resolve(source.name).normalize()
      if (source == target && move) {
        continue
      }
      require(!target.exists()) {
        "An item named '${source.name}' already exists here."
      }
      if (source.isDirectory()) {
        require(!destinationDirectory.startsWith(source)) {
          "A folder cannot be moved into itself."
        }
      }
      if (move) {
        Files.move(source, target)
      } else {
        copyRecursively(source, target)
      }
    }
  }

  private fun resolvePath(
    workspaceRoot: Path,
    relativePath: String,
    allowRoot: Boolean,
  ): Path {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    val trimmed = relativePath.trim().replace('\\', '/').removePrefix("/")
    if (trimmed.isEmpty()) {
      require(allowRoot) { "Workspace root cannot be targeted here." }
      return normalizedRoot
    }
    val resolved = normalizedRoot.resolve(trimmed).normalize()
    require(resolved.startsWith(normalizedRoot)) {
      "Path escapes the workspace root."
    }
    return resolved
  }

  private fun validateName(rawName: String): String {
    val normalizedName = rawName.trim()
    require(normalizedName.isNotEmpty()) {
      "A name is required."
    }
    require(normalizedName != "." && normalizedName != "..") {
      "That name is not allowed."
    }
    require('/' !in normalizedName && '\\' !in normalizedName) {
      "Names cannot contain path separators."
    }
    return normalizedName
  }

  private fun deleteRecursively(path: Path) {
    if (!path.exists()) {
      return
    }
    if (!path.isDirectory()) {
      Files.deleteIfExists(path)
      return
    }
    Files.walk(path).use { stream ->
      stream
        .sorted(Comparator.reverseOrder())
        .forEach { current ->
          Files.deleteIfExists(current)
        }
    }
  }

  private fun copyRecursively(source: Path, target: Path) {
    if (!source.isDirectory()) {
      Files.copy(source, target)
      return
    }
    Files.walk(source).use { stream ->
      stream.forEach { current ->
        val relative = source.relativize(current)
        val destination = if (relative.nameCount == 0) {
          target
        } else {
          target.resolve(relative.toString())
        }
        if (Files.isDirectory(current)) {
          Files.createDirectories(destination)
        } else {
          Files.createDirectories(destination.parent)
          Files.copy(current, destination, StandardCopyOption.COPY_ATTRIBUTES)
        }
      }
    }
  }
}
