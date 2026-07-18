package com.opencray.app

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
