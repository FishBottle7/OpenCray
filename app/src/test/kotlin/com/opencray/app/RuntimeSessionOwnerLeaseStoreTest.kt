package com.opencray.app

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeSessionOwnerLeaseStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreAllowsOnlyOneLiveOwnerAcrossStoreInstances() {
    val root = temporaryFolder.newFolder("runtime-session-owner-race")
    val firstStore = FileBackedRuntimeSessionOwnerLeaseStore.fromRootDirectory(root)
    val secondStore = FileBackedRuntimeSessionOwnerLeaseStore.fromRootDirectory(root)
    val interactive = sessionLease(
      target = RuntimeServiceTarget.INTERACTIVE,
      ownerId = "interactive",
      nowEpochMs = 1_000L,
    )
    val detached = sessionLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      ownerId = "detached",
      nowEpochMs = 1_001L,
    )

    assertEquals(interactive, firstStore.acquire(interactive))
    val rejected = secondStore.acquire(detached)

    assertTrue(rejected.sameRuntimeSessionOwnerAs(interactive))
    assertEquals(RuntimeServiceTarget.INTERACTIVE, rejected.target)
    assertEquals(RuntimeServiceTarget.INTERACTIVE, secondStore.load("session-1")?.target)
  }

  @Test
  fun expiredSessionLeaseStaysLiveWhileMatchingRuntimeServiceOwnerIsLive() {
    val root = temporaryFolder.newFolder("runtime-session-owner-service-liveness")
    val serviceStore = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(root)
    val sessionStore = FileBackedRuntimeSessionOwnerLeaseStore.fromRootDirectory(
      runtimeRootDirectory = root,
      runtimeServiceOwnerLeaseStore = serviceStore,
    )
    val sessionLease = sessionLease(
      target = RuntimeServiceTarget.INTERACTIVE,
      ownerId = "interactive",
      nowEpochMs = 1_000L,
      durationMs = 100L,
    )
    serviceStore.save(
      RuntimeServiceOwnerLease(
        target = RuntimeServiceTarget.INTERACTIVE,
        processStartId = sessionLease.processStartId,
        processStartedAtEpochMs = 900L,
        controllerInstanceId = sessionLease.runtimeControllerId,
        durableControllerId = sessionLease.durableRuntimeControllerId,
        runtimeOwnerId = sessionLease.runtimeOwnerId,
        runtimeControllerId = sessionLease.runtimeControllerId,
        durableRuntimeControllerId = sessionLease.durableRuntimeControllerId,
        acquiredAtEpochMs = 1_000L,
        heartbeatAtEpochMs = 2_000L,
        expiresAtEpochMs = 3_000L,
      ),
    )
    sessionStore.acquire(sessionLease)

    assertEquals(
      RuntimeServiceTarget.INTERACTIVE,
      sessionStore.loadLiveOwner("session-1", nowEpochMs = 2_500L)?.target,
    )
    assertFalse(
      sessionStore.acquire(
        sessionLease(
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
          ownerId = "detached",
          nowEpochMs = 2_500L,
        ),
      ).target == RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
  }

  @Test
  fun releasedOwnerAllowsImmediateTargetTransfer() {
    val root = temporaryFolder.newFolder("runtime-session-owner-release")
    val store = FileBackedRuntimeSessionOwnerLeaseStore.fromRootDirectory(root)
    val interactive = sessionLease(
      target = RuntimeServiceTarget.INTERACTIVE,
      ownerId = "interactive",
      nowEpochMs = 1_000L,
    )
    val detached = sessionLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      ownerId = "detached",
      nowEpochMs = 1_100L,
    )

    store.acquire(interactive)
    store.release(interactive.released(1_050L))

    assertEquals(detached, store.acquire(detached))
  }

  @Test
  fun corruptSessionLeaseFileFailsClosedIsQuarantinedAndIsNeverOverwritten() {
    val root = temporaryFolder.newFolder("runtime-session-owner-lease-corrupt")
    val store = FileBackedRuntimeSessionOwnerLeaseStore.fromRootDirectory(root)
    val fileName =
      "runtime-session-owner-lease-${FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId("session-1")}.json"
    val corruptContent = "{\"sessionId\":\"session-1\",\"phase\":"
    File(root, fileName).writeText(corruptContent)

    val loadFailure = assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.load("session-1")
    }
    assertTrue(loadFailure.quarantined)
    assertEquals(fileName, loadFailure.fileName)
    assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.loadLiveOwner("session-1", nowEpochMs = 10_000L)
    }

    val attempted = sessionLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      ownerId = "newcomer",
      nowEpochMs = 20_000L,
    )
    val acquireFailure = assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.acquire(attempted)
    }
    assertTrue(acquireFailure.quarantined)
    assertThrows(RuntimeLeaseStoreCorruptedException::class.java) {
      store.release(attempted.released(21_000L))
    }

    assertEquals(corruptContent, File(root, fileName).readText())
    assertEquals(4, corruptBackupCount(root, fileName))
    root.listFiles()!!
      .filter { file -> file.name.startsWith("$fileName.corrupt-") }
      .forEach { backup -> assertEquals(corruptContent, backup.readText()) }
  }

  private fun corruptBackupCount(
    root: File,
    fileName: String,
  ): Int = checkNotNull(root.listFiles()).count { file ->
    file.name.startsWith("$fileName.corrupt-")
  }

  private fun sessionLease(
    target: RuntimeServiceTarget,
    ownerId: String,
    nowEpochMs: Long,
    durationMs: Long = 1_000L,
  ): RuntimeSessionOwnerLease = RuntimeSessionOwnerLease(
    sessionId = "session-1",
    target = target,
    processStartId = "process-$ownerId",
    runtimeOwnerId = "owner-$ownerId",
    runtimeControllerId = "controller-$ownerId",
    durableRuntimeControllerId = "durable-$ownerId",
    acquiredAtEpochMs = nowEpochMs,
    heartbeatAtEpochMs = nowEpochMs,
    expiresAtEpochMs = nowEpochMs + durationMs,
  )
}
