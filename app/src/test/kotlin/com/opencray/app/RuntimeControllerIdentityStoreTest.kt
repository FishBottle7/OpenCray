package com.opencray.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File
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

  @Test
  fun defaultProviderUsesFileBackedStoreWhenFilesDirIsAvailable() {
    val filesDir = temporaryFolder.newFolder("runtime-controller-default-files")
    val context = FilesDirContext(filesDir)
    val firstProvider = defaultRuntimeControllerIdentityStoreProvider()
    val secondProvider = defaultRuntimeControllerIdentityStoreProvider()

    val firstId = firstProvider(context)
      .controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val secondId = secondProvider(context)
      .controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertEquals(firstId, secondId)
  }

  @Test
  fun defaultProviderFallsBackToInMemoryStoreWhenFilesDirIsUnavailable() {
    val provider = defaultRuntimeControllerIdentityStoreProvider()
    val context = MinimalContext()

    val firstId = provider(context)
      .controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)
    val secondId = provider(context)
      .controllerIdForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertEquals(firstId, secondId)
  }

  private class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
  }

  private class FilesDirContext(
    private val resolvedFilesDir: File,
  ) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = resolvedFilesDir
  }
}
