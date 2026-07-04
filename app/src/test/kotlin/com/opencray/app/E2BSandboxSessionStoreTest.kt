package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BSandboxSessionStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun saveAndLoadRoundTripsSnapshot() {
    val store = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore())
    val snapshot = sampleSnapshot("sandbox-a")

    store.save(snapshot)

    assertEquals(snapshot, store.load())
  }

  @Test
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("e2b-sandbox-session-file-backed")
    val firstStore = E2BSandboxSessionStore(
      FileBackedE2BSandboxSessionKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )
    val snapshot = sampleSnapshot("sandbox-file")

    firstStore.save(snapshot)

    val secondStore = E2BSandboxSessionStore(
      FileBackedE2BSandboxSessionKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertEquals(snapshot, secondStore.load())

    secondStore.clear()

    assertNull(firstStore.load())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("e2b-sandbox-session-migration")
    val legacyKeyValueStore = InMemoryE2BSandboxSessionKeyValueStore()
    val legacyStore = E2BSandboxSessionStore(legacyKeyValueStore)
    val legacySnapshot = sampleSnapshot("sandbox-legacy")
    legacyStore.save(legacySnapshot)
    val fileBackedKeyValueStore = FileBackedE2BSandboxSessionKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = E2BSandboxSessionStore(fileBackedKeyValueStore)
    assertEquals(legacySnapshot, fileBackedStore.load())

    val durableSnapshot = sampleSnapshot("sandbox-durable")
    fileBackedStore.save(durableSnapshot)
    legacyStore.save(sampleSnapshot("sandbox-newer-legacy"))

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertEquals(durableSnapshot, fileBackedStore.load())
  }

  private fun sampleSnapshot(id: String): E2BSandboxSessionSnapshot = E2BSandboxSessionSnapshot(
    sandboxId = id,
    sandboxDomain = "$id.e2b.dev",
    envdAccessToken = "envd-token-$id",
    trafficAccessToken = "traffic-token-$id",
    workspaceRoot = "/workspace",
    templateId = "opencray-template",
    updatedAtEpochMs = 1234L,
    previewCandidatePorts = listOf(3000, 8080),
    remoteWorkspaceRoot = "/home/user/workspace",
    lastPreviewUrl = "https://$id.e2b.dev",
    lastPreviewPort = 3000,
    lastPreviewPath = "/",
    lastPreviewProbeStatus = "reachable",
    lastPreviewProbeHttpStatusCode = 200,
    lastPreviewProbeMessage = "ok",
    lastPreviewOpenedAtEpochMs = 2222L,
    lastPreviewProbeObservedAtEpochMs = 3333L,
    lastPreviewProbeSource = "test",
  )
}
