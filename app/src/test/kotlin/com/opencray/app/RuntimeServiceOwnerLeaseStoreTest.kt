package com.opencray.app

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeServiceOwnerLeaseStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedOwnerLeaseStoreSeparatesRuntimeTargets() {
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(
      temporaryFolder.newFolder("runtime-owner-lease-targets"),
    )
    val interactiveLease = ownerLease(
      target = RuntimeServiceTarget.INTERACTIVE,
      runtimeOwnerId = "owner-interactive",
      acquiredAtEpochMs = 1_000L,
    )
    val detachedLease = ownerLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeOwnerId = "owner-detached",
      acquiredAtEpochMs = 2_000L,
    )

    store.save(interactiveLease)
    store.save(detachedLease)

    assertEquals(interactiveLease, store.load(RuntimeServiceTarget.INTERACTIVE))
    assertEquals(detachedLease, store.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
  }

  @Test
  fun fileBackedOwnerLeaseStoreDoesNotLetStaleOwnerOverwriteNewerLease() {
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(
      temporaryFolder.newFolder("runtime-owner-lease-stale"),
    )
    val olderLease = ownerLease(
      runtimeOwnerId = "owner-old",
      acquiredAtEpochMs = 1_000L,
      heartbeatAtEpochMs = 1_100L,
    )
    val newerLease = ownerLease(
      runtimeOwnerId = "owner-new",
      acquiredAtEpochMs = 2_000L,
      heartbeatAtEpochMs = 2_100L,
    )

    store.save(olderLease)
    store.save(newerLease)
    val staleReleaseResult = store.release(olderLease.released(3_000L))
    val staleHeartbeatResult = store.save(
      olderLease.copy(
        heartbeatAtEpochMs = 3_500L,
        expiresAtEpochMs = 4_500L,
      ),
    )

    assertEquals(newerLease, staleReleaseResult)
    assertEquals(newerLease, staleHeartbeatResult.copy(lastAcquireFailure = null))
    assertEquals(newerLease, store.load(RuntimeServiceTarget.DETACHED_BACKGROUND)?.copy(lastAcquireFailure = null))
    val failure = checkNotNull(staleHeartbeatResult.lastAcquireFailure)
    assertEquals("owner-old", failure.attemptedRuntimeOwnerId)
    assertEquals("owner-new", failure.holderRuntimeOwnerId)
    assertEquals(3_500L, failure.attemptedAtEpochMs)
  }

  @Test
  fun fileBackedOwnerLeaseStoreRejectsDifferentOwnerUntilLeaseExpiresOrReleases() {
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(
      temporaryFolder.newFolder("runtime-owner-lease-expiry"),
    )
    val activeLease = ownerLease(
      runtimeOwnerId = "owner-active",
      acquiredAtEpochMs = 1_000L,
      heartbeatAtEpochMs = 1_100L,
    )
    val competingLeaseBeforeExpiry = ownerLease(
      runtimeOwnerId = "owner-competing",
      acquiredAtEpochMs = 1_200L,
      heartbeatAtEpochMs = 1_500L,
    )
    val competingLeaseAfterExpiry = ownerLease(
      runtimeOwnerId = "owner-competing",
      acquiredAtEpochMs = 2_200L,
      heartbeatAtEpochMs = 2_200L,
    )

    store.save(activeLease)

    val blockedLease = store.save(competingLeaseBeforeExpiry)

    assertEquals(activeLease, blockedLease.copy(lastAcquireFailure = null))
    assertEquals(activeLease, store.load(RuntimeServiceTarget.DETACHED_BACKGROUND)?.copy(lastAcquireFailure = null))
    val failure = checkNotNull(blockedLease.lastAcquireFailure)
    assertEquals("owner-competing", failure.attemptedRuntimeOwnerId)
    assertEquals("owner-active", failure.holderRuntimeOwnerId)
    assertEquals("service-owner-competing", failure.attemptedServiceInstanceId)
    assertEquals("service-owner-active", failure.holderServiceInstanceId)
    assertEquals(1_500L, failure.attemptedAtEpochMs)
    assertEquals(1_100L, failure.holderHeartbeatAtEpochMs)

    assertEquals(competingLeaseAfterExpiry, store.save(competingLeaseAfterExpiry))
    assertEquals(competingLeaseAfterExpiry, store.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
  }

  @Test
  fun fileBackedOwnerLeaseStoreAllowsReplacementAfterRelease() {
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(
      temporaryFolder.newFolder("runtime-owner-lease-release"),
    )
    val activeLease = ownerLease(
      runtimeOwnerId = "owner-active",
      acquiredAtEpochMs = 1_000L,
      heartbeatAtEpochMs = 1_100L,
    )
    val replacementLease = ownerLease(
      runtimeOwnerId = "owner-replacement",
      acquiredAtEpochMs = 1_200L,
      heartbeatAtEpochMs = 1_200L,
    )

    store.save(activeLease)
    store.release(activeLease.released(1_150L))

    assertEquals(replacementLease, store.save(replacementLease))
    assertEquals(replacementLease, store.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
  }

  @Test
  fun corruptOwnerLeaseFileFailsClosedIsQuarantinedAndIsNeverOverwritten() {
    val root = temporaryFolder.newFolder("runtime-owner-lease-corrupt")
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(root)
    val fileName = "runtime-service-owner-lease-${RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue}.json"
    val corruptContent = "{\"schemaVersion\":1,\"target\":\"detached_background\",\"ph"
    File(root, fileName).writeText(corruptContent)

    val loadFailure = assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.load(RuntimeServiceTarget.DETACHED_BACKGROUND)
    }
    assertTrue(loadFailure.quarantined)
    assertEquals(fileName, loadFailure.fileName)
    assertEquals(corruptContent, File(root, fileName).readText())
    assertEquals(1, corruptBackupCount(root, fileName))

    val competingLease = ownerLease(
      runtimeOwnerId = "owner-competing",
      acquiredAtEpochMs = 5_000L,
    )
    val saveFailure = assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.save(competingLease)
    }
    assertTrue(saveFailure.quarantined)
    assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.release(competingLease.released(6_000L))
    }

    assertEquals(corruptContent, File(root, fileName).readText())
    assertEquals(3, corruptBackupCount(root, fileName))
    root.listFiles()!!
      .filter { file -> file.name.startsWith("$fileName.corrupt-") }
      .forEach { backup -> assertEquals(corruptContent, backup.readText()) }

    assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.load(RuntimeServiceTarget.DETACHED_BACKGROUND)
    }
    assertEquals(corruptContent, File(root, fileName).readText())
    assertEquals(4, corruptBackupCount(root, fileName))
    assertTrue(
      checkNotNull(root.listFiles()).none { it.name.endsWith(".tmp") },
    )
  }

  @Test
  fun ownerLeaseFileHoldingAnotherTargetIsTreatedAsCorruptAndQuarantined() {
    val root = temporaryFolder.newFolder("runtime-owner-lease-cross-target")
    val store = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(root)
    val interactiveLease = ownerLease(
      target = RuntimeServiceTarget.INTERACTIVE,
      runtimeOwnerId = "owner-interactive",
      acquiredAtEpochMs = 1_000L,
    )
    store.save(interactiveLease)
    val interactiveFileName =
      "runtime-service-owner-lease-${RuntimeServiceTarget.INTERACTIVE.wireValue}.json"
    val detachedFileName =
      "runtime-service-owner-lease-${RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue}.json"
    val misplacedContent = File(root, interactiveFileName).readText()
    File(root, detachedFileName).writeText(misplacedContent)

    assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.load(RuntimeServiceTarget.DETACHED_BACKGROUND)
    }
    assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.save(ownerLease(runtimeOwnerId = "owner-detached", acquiredAtEpochMs = 2_000L))
    }

    assertEquals(misplacedContent, File(root, detachedFileName).readText())
    assertEquals(2, corruptBackupCount(root, detachedFileName))
    root.listFiles()!!
      .filter { file -> file.name.startsWith("$detachedFileName.corrupt-") }
      .forEach { backup -> assertEquals(misplacedContent, backup.readText()) }
    assertEquals(interactiveLease, store.load(RuntimeServiceTarget.INTERACTIVE))
  }

  private fun corruptBackupCount(
    root: File,
    fileName: String,
  ): Int = checkNotNull(root.listFiles()).count { file ->
    file.name.startsWith("$fileName.corrupt-")
  }

  private fun ownerLease(
    target: RuntimeServiceTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    runtimeOwnerId: String,
    acquiredAtEpochMs: Long,
    heartbeatAtEpochMs: Long = acquiredAtEpochMs,
  ): RuntimeServiceOwnerLease = RuntimeServiceOwnerLease(
    target = target,
    processStartId = "process-$runtimeOwnerId",
    processStartedAtEpochMs = acquiredAtEpochMs - 100L,
    controllerInstanceId = "controller-$runtimeOwnerId",
    durableControllerId = "durable-controller-$target",
    runtimeOwnerId = runtimeOwnerId,
    runtimeControllerId = "controller-$runtimeOwnerId",
    durableRuntimeControllerId = "durable-controller-$target",
    serviceInstanceId = "service-$runtimeOwnerId",
    serviceProcessName = "org.opencray.app:runtime",
    acquiredAtEpochMs = acquiredAtEpochMs,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = heartbeatAtEpochMs + 1_000L,
  )
}
