package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeServiceProjectionStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedProjectionStoreSeparatesSnapshotsByRuntimeTarget() {
    val factory = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection"),
    )
    val interactiveSnapshot = projectionSnapshot(activeRunCount = 1)
    val detachedSnapshot = projectionSnapshot(activeRunCount = 3)

    val interactiveStore = factory.create(RuntimeServiceTarget.INTERACTIVE)
    val detachedStore = factory.create(RuntimeServiceTarget.DETACHED_BACKGROUND)
    interactiveStore.saveSnapshot(interactiveSnapshot)
    detachedStore.saveSnapshot(detachedSnapshot)

    assertEquals(interactiveSnapshot, interactiveStore.loadSnapshot())
    assertEquals(detachedSnapshot, detachedStore.loadSnapshot())
  }

  @Test
  fun fileBackedProjectionStoreClearAffectsOnlyTargetBucket() {
    val factory = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection-clear"),
    )
    val interactiveStore = factory.create(RuntimeServiceTarget.INTERACTIVE)
    val detachedStore = factory.create(RuntimeServiceTarget.DETACHED_BACKGROUND)
    detachedStore.saveSnapshot(projectionSnapshot(activeRunCount = 2))

    interactiveStore.clear()

    assertNull(interactiveStore.loadSnapshot())
    assertEquals(2, detachedStore.loadSnapshot()?.runtimeOwnerWorkSummary?.activeRunCount)
  }

  @Test
  fun fileBackedProjectionStorePreservesRuntimeServiceProcessDescriptor() {
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection-process"),
    ).create()
    val expected = projectionSnapshot(activeRunCount = 1).copy(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-with-process",
        serviceProcess = runtimeServiceProcessDescriptor(
          packageName = "org.opencray.app",
          processName = "org.opencray.app:runtime",
        ),
      ),
    )

    store.saveSnapshot(expected)

    assertEquals(expected, store.loadSnapshot())
  }

  @Test
  fun fileBackedProjectionStorePreservesDurableRuntimeControllerIds() {
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection-durable-controller"),
    ).create()
    val expected = projectionSnapshot(activeRunCount = 1).copy(
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
        controllerInstanceId = "runtime-controller-instance",
        durableControllerId = "runtime-controller-durable",
      ),
      runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
        runtimeOwnerId = "runtime-owner",
        runtimeControllerId = "runtime-controller-instance",
        durableRuntimeControllerId = "runtime-controller-durable",
      ),
    )

    store.saveSnapshot(expected)

    assertEquals(expected, store.loadSnapshot())
  }

  @Test
  fun fileBackedProjectionStorePreservesInterruptedRunRepairProjection() {
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection-repair"),
    ).create(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val expectedRepair = RuntimeServiceInterruptedRunRepairProjection(
      scannedSessionIds = listOf("session-repair"),
      resumedSessionIds = listOf("session-repair"),
      repairedSessionIds = listOf("session-repair"),
      repairEvidenceBySession = mapOf(
        "session-repair" to listOf(
          InterruptedRunRepairEvidence(
            sessionId = "session-repair",
            kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
            target = RuntimeServiceTarget.DETACHED_BACKGROUND,
            runId = "run-repair",
            taskId = "task-repair",
            detailId = "managed-process-repair",
            repairAfterEpochMs = 4_500L,
          ),
        ),
      ),
      nextRepairAfterEpochMs = 4_500L,
      recordedAtEpochMs = 4_200L,
    )
    val expected = projectionSnapshot(activeRunCount = 1).copy(
      lastInterruptedRunRepair = expectedRepair,
    )

    store.saveSnapshot(expected)

    assertEquals(expected, store.loadSnapshot())
  }

  @Test
  fun fileBackedProjectionStorePreservesRuntimeServiceOwnerLease() {
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection-owner-lease"),
    ).create(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val expectedLease = RuntimeServiceOwnerLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      processStartId = "process-owner-lease",
      processStartedAtEpochMs = 1_000L,
      controllerInstanceId = "controller-owner-lease",
      durableControllerId = "durable-controller-owner-lease",
      runtimeOwnerId = "runtime-owner-lease",
      runtimeControllerId = "controller-owner-lease",
      durableRuntimeControllerId = "durable-controller-owner-lease",
      serviceInstanceId = "runtime-service-owner-lease",
      serviceProcessName = "org.opencray.app:runtime",
      acquiredAtEpochMs = 2_000L,
      heartbeatAtEpochMs = 2_500L,
      expiresAtEpochMs = 32_500L,
      lastAcquireFailure = RuntimeServiceOwnerLeaseAcquireFailure(
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        attemptedAtEpochMs = 2_750L,
        attemptedProcessStartId = "process-owner-lease-contender",
        attemptedControllerInstanceId = "controller-owner-lease-contender",
        attemptedDurableControllerId = "durable-controller-owner-lease",
        attemptedRuntimeOwnerId = "runtime-owner-lease-contender",
        attemptedRuntimeControllerId = "controller-owner-lease-contender",
        attemptedDurableRuntimeControllerId = "durable-controller-owner-lease",
        attemptedServiceInstanceId = "runtime-service-owner-lease-contender",
        attemptedServiceProcessName = "org.opencray.app:runtime",
        holderRuntimeOwnerId = "runtime-owner-lease",
        holderControllerInstanceId = "controller-owner-lease",
        holderDurableControllerId = "durable-controller-owner-lease",
        holderServiceInstanceId = "runtime-service-owner-lease",
        holderHeartbeatAtEpochMs = 2_500L,
        holderExpiresAtEpochMs = 32_500L,
      ),
    )
    val expected = projectionSnapshot(activeRunCount = 1).copy(
      runtimeServiceOwnerLease = expectedLease,
    )

    store.saveSnapshot(expected)

    assertEquals(expected, store.loadSnapshot())
  }

  private fun projectionSnapshot(
    activeRunCount: Int,
  ): RuntimeServiceProjectionSnapshot = RuntimeServiceProjectionSnapshot(
    runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(),
    runtimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = activeRunCount,
      activeRunCount = activeRunCount,
    ),
    serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
    serviceWorkState = RuntimeServiceWorkState(activeRunCount = activeRunCount),
    serviceKeepAliveState = RuntimeServiceKeepAliveState(),
  )
}
