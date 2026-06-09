package com.opencray.app

import com.opencray.runtime.defaultOpenCrayMediaArtifactRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal data class AppAgentWorkspaceMediaGcResult(
  val deletedFiles: Int,
  val removedEmptyDirectories: Int,
  val removedRegistryRecords: Int,
)

internal object AppAgentWorkspaceMediaGc {
  fun sweep(
    workspaceRoot: Path,
    chatSessionStore: ChatSessionLocalStore,
  ): AppAgentWorkspaceMediaGcResult {
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    val chatMediaRoot = normalizedWorkspaceRoot
      .resolve(".opencray")
      .resolve("chat-media")
      .normalize()
    val referencedPaths = chatSessionStore.referencedAttachmentLocalPaths()
      .filterTo(linkedSetOf()) { path -> isChatMediaPath(path) }

    var deletedFiles = 0
    var removedEmptyDirectories = 0
    if (Files.isDirectory(chatMediaRoot) && chatMediaRoot.startsWith(normalizedWorkspaceRoot)) {
      deletedFiles = deleteUnreferencedChatMediaFiles(
        chatMediaRoot = chatMediaRoot,
        referencedPaths = referencedPaths,
      )
      removedEmptyDirectories = deleteEmptyChatMediaDirectories(chatMediaRoot)
    }

    val registrySweep = defaultOpenCrayMediaArtifactRegistry(normalizedWorkspaceRoot)
      .sweep(normalizedWorkspaceRoot)
    return AppAgentWorkspaceMediaGcResult(
      deletedFiles = deletedFiles,
      removedEmptyDirectories = removedEmptyDirectories,
      removedRegistryRecords = registrySweep.removedRecords,
    )
  }

  private fun deleteUnreferencedChatMediaFiles(
    chatMediaRoot: Path,
    referencedPaths: Set<String>,
  ): Int {
    var deleted = 0
    Files.walk(chatMediaRoot).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path) }
        .forEach { file ->
          val relativePath = chatMediaRelativePath(chatMediaRoot = chatMediaRoot, path = file) ?: return@forEach
          if (relativePath !in referencedPaths && Files.deleteIfExists(file)) {
            deleted += 1
          }
        }
    }
    return deleted
  }

  private fun deleteEmptyChatMediaDirectories(chatMediaRoot: Path): Int {
    var removed = 0
    Files.walk(chatMediaRoot).use { stream ->
      stream
        .filter { path -> path != chatMediaRoot && Files.isDirectory(path) }
        .sorted(Comparator.reverseOrder())
        .forEach { directory ->
          if (isDirectoryEmpty(directory) && Files.deleteIfExists(directory)) {
            removed += 1
          }
        }
    }
    return removed
  }

  private fun chatMediaRelativePath(
    chatMediaRoot: Path,
    path: Path,
  ): String? {
    val normalizedPath = path.toAbsolutePath().normalize()
    if (!normalizedPath.startsWith(chatMediaRoot)) {
      return null
    }
    return "$CHAT_MEDIA_PREFIX/${chatMediaRoot.relativize(normalizedPath).toString().replace('\\', '/')}"
      .trimEnd('/')
  }

  private fun isDirectoryEmpty(directory: Path): Boolean =
    Files.list(directory).use { stream -> stream.findAny().isEmpty }

  private fun isChatMediaPath(path: String): Boolean =
    path == CHAT_MEDIA_PREFIX || path.startsWith("$CHAT_MEDIA_PREFIX/")

  private const val CHAT_MEDIA_PREFIX = ".opencray/chat-media"
}
