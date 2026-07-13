package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
  fun fileBackedUpdateSerializesConcurrentSessionMergesAcrossInstances() {
    val directory = temporaryFolder.newFolder("e2b-sandbox-session-concurrent-update")
    val firstStore = fileBackedStore(directory, now = 100L)
    val secondStore = fileBackedStore(directory, now = 200L)
    firstStore.save(
      sampleSnapshot("sandbox-concurrent").copy(
        remoteWorkspaceRoot = null,
        lastPreviewUrl = null,
        lastPreviewPort = null,
      ),
    )
    val firstTransformEntered = CountDownLatch(1)
    val releaseFirstTransform = CountDownLatch(1)
    val secondUpdateStarted = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()

    val firstThread = thread(name = "e2b-session-remote-root-update") {
      runCatching {
        firstStore.update { current ->
          firstTransformEntered.countDown()
          check(releaseFirstTransform.await(5, TimeUnit.SECONDS))
          requireNotNull(current).copy(remoteWorkspaceRoot = "/workspace/from-runtime")
        }
      }.exceptionOrNull()?.let { error -> failure.compareAndSet(null, error) }
    }
    assertTrue(firstTransformEntered.await(5, TimeUnit.SECONDS))
    val secondThread = thread(name = "e2b-session-preview-update") {
      runCatching {
        secondUpdateStarted.countDown()
        secondStore.update { current ->
          requireNotNull(current).copy(
            lastPreviewUrl = "https://preview.example.test",
            lastPreviewPort = 4173,
          )
        }
      }.exceptionOrNull()?.let { error -> failure.compareAndSet(null, error) }
    }
    assertTrue(secondUpdateStarted.await(5, TimeUnit.SECONDS))
    releaseFirstTransform.countDown()
    firstThread.join(5_000L)
    secondThread.join(5_000L)

    assertFalse(firstThread.isAlive)
    assertFalse(secondThread.isAlive)
    failure.get()?.let { error -> throw AssertionError("Concurrent session update failed", error) }
    val persisted = firstStore.load()
    assertEquals("/workspace/from-runtime", persisted?.remoteWorkspaceRoot)
    assertEquals("https://preview.example.test", persisted?.lastPreviewUrl)
    assertEquals(4173, persisted?.lastPreviewPort)
  }

  @Test
  fun conditionalUpdateDoesNotClearReplacementSession() {
    val directory = temporaryFolder.newFolder("e2b-sandbox-session-conditional-clear")
    val staleOwnerStore = fileBackedStore(directory, now = 300L)
    val replacementOwnerStore = fileBackedStore(directory, now = 400L)
    staleOwnerStore.save(sampleSnapshot("sandbox-old"))
    replacementOwnerStore.save(sampleSnapshot("sandbox-new"))

    staleOwnerStore.update { current ->
      if (current?.sandboxId == "sandbox-old") null else current
    }

    assertEquals("sandbox-new", replacementOwnerStore.load()?.sandboxId)
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

  private fun fileBackedStore(
    directory: java.io.File,
    now: Long,
  ): E2BSandboxSessionStore = E2BSandboxSessionStore(
    FileBackedE2BSandboxSessionKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { now },
    ),
  )

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
