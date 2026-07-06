package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.nio.file.Path
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
    val encoded = storage(workspaceRoot).readText(WORKSPACE_SYNC_STATE_FILE_NAME)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    return runCatching {
      json.decodeFromString(
        E2BWorkspaceSyncStateSnapshot.serializer(),
        encoded,
      )
    }.getOrNull()
  }

  fun save(
    workspaceRoot: Path,
    snapshot: E2BWorkspaceSyncStateSnapshot,
  ) {
    storage(workspaceRoot).writeText(
      WORKSPACE_SYNC_STATE_FILE_NAME,
      json.encodeToString(E2BWorkspaceSyncStateSnapshot.serializer(), snapshot),
    )
  }

  fun clear(workspaceRoot: Path) {
    storage(workspaceRoot).delete(WORKSPACE_SYNC_STATE_FILE_NAME)
  }

  private fun storage(workspaceRoot: Path): DirectoryDurableTextStorage =
    DirectoryDurableTextStorage(
      workspaceRoot
        .toAbsolutePath()
        .normalize()
        .resolve(".opencray")
        .resolve("sandbox-sync")
        .toFile(),
    )
}

private const val WORKSPACE_SYNC_STATE_FILE_NAME: String = "e2b-workspace-sync-state.json"
