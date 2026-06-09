package com.opencray.app

import org.junit.Assert.assertEquals
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
    assertEquals(newerLease, staleHeartbeatResult)
    assertEquals(newerLease, store.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
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
