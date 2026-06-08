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
