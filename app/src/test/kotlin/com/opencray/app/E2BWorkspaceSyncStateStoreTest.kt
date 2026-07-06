package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BWorkspaceSyncStateStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreRoundTripsAcrossInstancesThroughDurableStorage() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-sync-root").toPath()
    val firstStore = E2BWorkspaceSyncStateStore()
    val secondStore = E2BWorkspaceSyncStateStore()
    val snapshot = E2BWorkspaceSyncStateSnapshot(
      sandboxId = "sandbox-sync-store",
      remoteWorkspaceRoot = "/home/user/opencray",
      updatedAtEpochMs = 12_345L,
      files = listOf(
        E2BWorkspaceSyncFileState(
          relativePath = "src/main.py",
          sizeBytes = 42L,
          modifiedAtEpochMs = 12_300L,
        ),
      ),
    )

    firstStore.save(workspaceRoot = workspaceRoot, snapshot = snapshot)

    assertEquals(snapshot, secondStore.load(workspaceRoot))
    assertTrue(Files.isRegularFile(syncStateFile(workspaceRoot)))
    assertTrue(Files.isRegularFile(syncStateLockFile(workspaceRoot)))
  }

  @Test
  fun clearRemovesPersistedSnapshot() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-sync-clear").toPath()
    val store = E2BWorkspaceSyncStateStore()
    store.save(
      workspaceRoot = workspaceRoot,
      snapshot = E2BWorkspaceSyncStateSnapshot(
        sandboxId = "sandbox-clear",
        remoteWorkspaceRoot = "/workspace",
        updatedAtEpochMs = 1_000L,
      ),
    )
    assertNotNull(store.load(workspaceRoot))

    store.clear(workspaceRoot)

    assertNull(store.load(workspaceRoot))
    assertFalse(Files.exists(syncStateFile(workspaceRoot)))
  }

  private fun syncStateFile(workspaceRoot: Path): Path =
    workspaceRoot
      .resolve(".opencray")
      .resolve("sandbox-sync")
      .resolve("e2b-workspace-sync-state.json")

  private fun syncStateLockFile(workspaceRoot: Path): Path =
    syncStateFile(workspaceRoot).resolveSibling("e2b-workspace-sync-state.json.lock")
}
