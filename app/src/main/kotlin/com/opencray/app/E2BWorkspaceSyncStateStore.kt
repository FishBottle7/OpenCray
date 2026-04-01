package com.opencray.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class E2BWorkspaceSyncFileState(
  val relativePath: String,
  val sizeBytes: Long,
  val modifiedAtEpochMs: Long,
)

@Serializable
internal data class E2BWorkspaceSyncStateSnapshot(
  val sandboxId: String,
  val remoteWorkspaceRoot: String,
  val updatedAtEpochMs: Long,
  val files: List<E2BWorkspaceSyncFileState> = emptyList(),
)

internal class E2BWorkspaceSyncStateStore(
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
  fun load(workspaceRoot: Path): E2BWorkspaceSyncStateSnapshot? {
    val file = stateFile(workspaceRoot)
    if (!Files.isRegularFile(file)) {
      return null
    }
    return runCatching {
      json.decodeFromString(
        E2BWorkspaceSyncStateSnapshot.serializer(),
        String(Files.readAllBytes(file), StandardCharsets.UTF_8),
      )
    }.getOrNull()
  }

  fun save(
    workspaceRoot: Path,
    snapshot: E2BWorkspaceSyncStateSnapshot,
  ) {
    val file = stateFile(workspaceRoot)
    file.parent?.let(Files::createDirectories)
    val tempFile = file.resolveSibling("${file.fileName}.tmp-${UUID.randomUUID()}")
    Files.write(
      tempFile,
      json.encodeToString(E2BWorkspaceSyncStateSnapshot.serializer(), snapshot)
        .toByteArray(StandardCharsets.UTF_8),
    )
    try {
      Files.move(
        tempFile,
        file,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: Exception) {
      Files.move(
        tempFile,
        file,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  fun clear(workspaceRoot: Path) {
    runCatching {
      Files.deleteIfExists(stateFile(workspaceRoot))
    }
  }

  private fun stateFile(workspaceRoot: Path): Path =
    workspaceRoot
      .toAbsolutePath()
      .normalize()
      .resolve(".opencray")
      .resolve("sandbox-sync")
      .resolve("e2b-workspace-sync-state.json")
}
