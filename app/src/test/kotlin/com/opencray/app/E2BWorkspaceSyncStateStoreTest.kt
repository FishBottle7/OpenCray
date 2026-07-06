package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
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

  @Test
  fun saveAndClearUseDurableUpdatePrimitive() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-sync-update-only").toPath()
    val storage = UpdateOnlyDurableTextStorage()
    val store = E2BWorkspaceSyncStateStore(
      storageFactory = { storage },
    )
    val snapshot = E2BWorkspaceSyncStateSnapshot(
      sandboxId = "sandbox-update-only",
      remoteWorkspaceRoot = "/workspace",
      updatedAtEpochMs = 2_000L,
    )

    store.save(workspaceRoot = workspaceRoot, snapshot = snapshot)
    assertEquals(snapshot, store.load(workspaceRoot))
    assertEquals(1, storage.updateTextCallCount)

    store.clear(workspaceRoot)
    assertNull(store.load(workspaceRoot))
    assertEquals(2, storage.updateTextCallCount)
  }

  private fun syncStateFile(workspaceRoot: Path): Path =
    workspaceRoot
      .resolve(".opencray")
      .resolve("sandbox-sync")
      .resolve("e2b-workspace-sync-state.json")

  private fun syncStateLockFile(workspaceRoot: Path): Path =
    syncStateFile(workspaceRoot).resolveSibling("e2b-workspace-sync-state.json.lock")

  private class UpdateOnlyDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    var updateTextCallCount: Int = 0
      private set

    override fun readText(name: String): String? = text

    override fun writeText(name: String, text: String) {
      error("E2B workspace sync state mutations should use updateText.")
    }

    override fun delete(name: String): Boolean {
      error("E2B workspace sync state mutations should use updateText.")
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }
}
