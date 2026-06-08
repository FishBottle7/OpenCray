package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeControllerIdentityStoreTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreKeepsControllerIdStableAcrossStoreInstances() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-controller-identity")
    val firstStore = FileBackedRuntimeControllerIdentityStore.fromRootDirectory(runtimeRoot)
    val secondStore = FileBackedRuntimeControllerIdentityStore.fromRootDirectory(runtimeRoot)

    val firstId = firstStore.controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val secondId = secondStore.controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertEquals(firstId, secondId)
  }

  @Test
  fun fileBackedStoreSeparatesControllerIdsByRuntimeTarget() {
    val store = FileBackedRuntimeControllerIdentityStore.fromRootDirectory(
      temporaryFolder.newFolder("runtime-controller-identity-targets"),
    )

    val detachedId = store.controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val interactiveId = store.controllerIdForTarget(RuntimeServiceTarget.INTERACTIVE)

    assertNotEquals(detachedId, interactiveId)
  }
}
