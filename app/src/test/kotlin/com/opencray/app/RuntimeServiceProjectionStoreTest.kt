package com.opencray.app

import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            managedProcessReconnectStatus = "connecting",
            managedProcessReconnectRecoveryState = "retry_scheduled",
            managedProcessReconnectAttemptCount = 5,
            runtimeExecutionOwnershipTier = "runtime_process",
            durableRuntimeControllerId = "durable-controller-repair",
            managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
            managedProcessRestoreScope = ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
            managedProcessRestoreDecision = ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
          ),
        ),
      ),
      nextRepairAfterEpochMs = 4_500L,
      nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
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

  @Test
  fun fileBackedProjectionStoreRetainsNewerCollateralFromDurableSnapshot() {
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      runtimeRootDirectory = temporaryFolder.newFolder("runtime-projection-retain-collateral"),
    ).create(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val newerLease = runtimeServiceProjectionLease(heartbeatAtEpochMs = 5_000L)
    val newerRepair = interruptedRunRepairProjection(recordedAtEpochMs = 5_100L)
    val olderLease = runtimeServiceProjectionLease(heartbeatAtEpochMs = 4_000L)
    val olderRepair = interruptedRunRepairProjection(recordedAtEpochMs = 4_100L)

    store.saveSnapshot(
      projectionSnapshot(activeRunCount = 1).copy(
        runtimeServiceOwnerLease = newerLease,
        lastInterruptedRunRepair = newerRepair,
      ),
    )
    store.saveSnapshot(
      projectionSnapshot(activeRunCount = 3).copy(
        runtimeServiceOwnerLease = olderLease,
        lastInterruptedRunRepair = olderRepair,
      ),
    )

    val loaded = checkNotNull(store.loadSnapshot())
    assertEquals(3, loaded.runtimeOwnerWorkSummary.activeRunCount)
    assertEquals(newerLease, loaded.runtimeServiceOwnerLease)
    assertEquals(newerRepair, loaded.lastInterruptedRunRepair)
  }

  @Test
  fun fileBackedProjectionStoreSaveUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    val store = fileBackedRuntimeServiceProjectionStore(
      storage = storage,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val newerRepair = interruptedRunRepairProjection(recordedAtEpochMs = 6_100L)
    store.saveSnapshot(projectionSnapshot(activeRunCount = 1))
    val staleBeforeConcurrentWrite = storage.currentText
    store.saveSnapshot(
      projectionSnapshot(activeRunCount = 2).copy(
        lastInterruptedRunRepair = newerRepair,
      ),
    )
    val updateCallsBeforeStaleSave = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentWrite)
    store.saveSnapshot(projectionSnapshot(activeRunCount = 4))

    assertEquals(updateCallsBeforeStaleSave + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    val loaded = checkNotNull(store.loadSnapshot())
    assertEquals(4, loaded.runtimeOwnerWorkSummary.activeRunCount)
    assertEquals(newerRepair, loaded.lastInterruptedRunRepair)
  }

  @Test
  fun fileBackedProjectionStoreTreatsCorruptSnapshotAsMissingAndCanRecover() {
    val storage = StaleReadDurableTextStorage()
    val store = fileBackedRuntimeServiceProjectionStore(
      storage = storage,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    storage.writeText("ignored", "{not-json")

    assertNull(store.loadSnapshot())

    val expected = projectionSnapshot(activeRunCount = 2)
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

  private fun runtimeServiceProjectionLease(
    heartbeatAtEpochMs: Long,
  ): RuntimeServiceOwnerLease = RuntimeServiceOwnerLease(
    target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    processStartId = "process-owner-projection",
    processStartedAtEpochMs = 1_000L,
    controllerInstanceId = "controller-owner-projection",
    durableControllerId = "durable-controller-projection",
    runtimeOwnerId = "runtime-owner-projection",
    runtimeControllerId = "controller-owner-projection",
    durableRuntimeControllerId = "durable-controller-projection",
    serviceInstanceId = "runtime-service-projection",
    serviceProcessName = "org.opencray.app:runtime",
    acquiredAtEpochMs = 2_000L,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = heartbeatAtEpochMs + 30_000L,
  )

  private fun interruptedRunRepairProjection(
    recordedAtEpochMs: Long,
  ): RuntimeServiceInterruptedRunRepairProjection = RuntimeServiceInterruptedRunRepairProjection(
    scannedSessionIds = listOf("session-repair"),
    resumedSessionIds = emptyList(),
    repairedSessionIds = emptyList(),
    repairEvidenceBySession = mapOf(
      "session-repair" to listOf(
        InterruptedRunRepairEvidence(
          sessionId = "session-repair",
          kind = InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT,
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
          runId = "run-repair",
          taskId = "task-repair",
          detailId = "managed-process-repair-$recordedAtEpochMs",
          repairAfterEpochMs = recordedAtEpochMs + 1_000L,
          managedProcessReconnectStatus = "connecting",
          managedProcessReconnectRecoveryState = "retry_scheduled",
          managedProcessReconnectAttemptCount = 2,
          runtimeExecutionOwnershipTier = "runtime_process",
          durableRuntimeControllerId = "durable-controller-projection",
          managedProcessContinuationBasis = ManagedProcessContinuationBases.RECONNECT_HOLD,
          managedProcessRestoreScope = ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
          managedProcessRestoreDecision = ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
        ),
      ),
    ),
    nextRepairAfterEpochMs = recordedAtEpochMs + 1_000L,
    nextRepairReason = ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
    recordedAtEpochMs = recordedAtEpochMs,
  )

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    fun returnStaleTextOnNextRead(staleText: String?) {
      this.staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
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
