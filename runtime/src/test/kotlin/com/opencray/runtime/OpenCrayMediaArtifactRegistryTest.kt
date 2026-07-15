package com.opencray.runtime

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayMediaArtifactRegistryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun registerUsesAtomicCurrentRecordUpdateInsteadOfStaleReadWrite() {
    val workspaceRoot = temporaryFolder.newFolder("media-registry-atomic-update").toPath()
    writeArtifact(workspaceRoot, "media/first.png", byteArrayOf(1))
    writeArtifact(workspaceRoot, "media/second.png", byteArrayOf(2))
    val storage = StaleReadDurableTextStorage()
    val registryFile = workspaceRoot.resolve("registry.json")
    val first = FileBackedOpenCrayMediaArtifactRegistry(
      workspaceRoot = workspaceRoot,
      registryFile = registryFile,
      nowEpochMs = { 1_000L },
      storage = storage,
    )
    val second = FileBackedOpenCrayMediaArtifactRegistry(
      workspaceRoot = workspaceRoot,
      registryFile = registryFile,
      nowEpochMs = { 2_000L },
      storage = storage,
    )

    first.register(
      artifacts = listOf(artifact("artifact-first", "media/first.png")),
      source = OpenCrayMediaArtifactSource(runId = "run-first", toolName = "GenerateImage"),
    )
    storage.returnStaleTextOnNextRead(null)
    second.register(
      artifacts = listOf(artifact("artifact-second", "media/second.png")),
      source = OpenCrayMediaArtifactSource(runId = "run-second", toolName = "GenerateImage"),
    )

    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(2, storage.updateTextCallCount)
    assertEquals(0, storage.writeTextCallCount)
    assertEquals(setOf("run-first"), first.resolve("artifact-first")?.runIds)
    assertEquals(setOf("run-second"), second.resolve("artifact-second")?.runIds)
  }

  @Test
  fun concurrentRegistryInstancesRetainBothArtifactUpdates() {
    val workspaceRoot = temporaryFolder.newFolder("media-registry-concurrent-update").toPath()
    writeArtifact(workspaceRoot, "media/first.png", byteArrayOf(1, 2))
    writeArtifact(workspaceRoot, "media/second.png", byteArrayOf(3, 4))
    val registryFile = workspaceRoot.resolve("state/media-artifacts.json")
    val first = FileBackedOpenCrayMediaArtifactRegistry(workspaceRoot, registryFile)
    val second = FileBackedOpenCrayMediaArtifactRegistry(workspaceRoot, registryFile)
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val threads = listOf(
      Thread {
        runConcurrentRegistration(
          ready = ready,
          start = start,
          failure = failure,
        ) {
          first.register(
            artifacts = listOf(artifact("artifact-first", "media/first.png")),
            source = OpenCrayMediaArtifactSource(runId = "run-first"),
          )
        }
      },
      Thread {
        runConcurrentRegistration(
          ready = ready,
          start = start,
          failure = failure,
        ) {
          second.register(
            artifacts = listOf(artifact("artifact-second", "media/second.png")),
            source = OpenCrayMediaArtifactSource(runId = "run-second"),
          )
        }
      },
    )

    threads.forEach(Thread::start)
    assertTrue(ready.await(5L, TimeUnit.SECONDS))
    start.countDown()
    threads.forEach { thread -> thread.join(5_000L) }

    threads.forEach { thread -> assertFalse(thread.isAlive) }
    failure.get()?.let { throwable -> throw AssertionError("Concurrent registration failed", throwable) }
    val reopened = FileBackedOpenCrayMediaArtifactRegistry(workspaceRoot, registryFile)
    assertNotNull(reopened.resolve("artifact-first"))
    assertNotNull(reopened.resolve("artifact-second"))
  }

  @Test
  fun registerAtomicallyRebuildsMalformedRegistrySnapshot() {
    val workspaceRoot = temporaryFolder.newFolder("media-registry-malformed-update").toPath()
    writeArtifact(workspaceRoot, "media/recovered.png", byteArrayOf(5, 6, 7))
    val registryFile = workspaceRoot.resolve("state/media-artifacts.json")
    Files.createDirectories(requireNotNull(registryFile.parent))
    Files.write(registryFile, "{malformed".toByteArray(StandardCharsets.UTF_8))
    val registry = FileBackedOpenCrayMediaArtifactRegistry(workspaceRoot, registryFile)

    registry.register(
      artifacts = listOf(artifact("artifact-recovered", "media/recovered.png")),
      source = OpenCrayMediaArtifactSource(runId = "run-recovered"),
    )

    assertEquals(
      setOf("run-recovered"),
      FileBackedOpenCrayMediaArtifactRegistry(workspaceRoot, registryFile)
        .resolve("artifact-recovered")
        ?.runIds,
    )
  }

  private fun artifact(
    artifactId: String,
    relativePath: String,
  ): OpenCrayAttachmentArtifact = OpenCrayAttachmentArtifact(
    artifactId = artifactId,
    relativePath = relativePath,
    kindHint = "image",
    mimeType = "image/png",
  )

  private fun writeArtifact(
    workspaceRoot: Path,
    relativePath: String,
    bytes: ByteArray,
  ) {
    val path = workspaceRoot.resolve(relativePath)
    Files.createDirectories(requireNotNull(path.parent))
    Files.write(path, bytes)
  }

  private fun runConcurrentRegistration(
    ready: CountDownLatch,
    start: CountDownLatch,
    failure: AtomicReference<Throwable?>,
    registration: () -> Unit,
  ) {
    try {
      ready.countDown()
      check(start.await(5L, TimeUnit.SECONDS))
      registration()
    } catch (throwable: Throwable) {
      failure.compareAndSet(null, throwable)
    }
  }

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var currentText: String? = null
    private var pendingStaleText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set
    var writeTextCallCount: Int = 0
      private set

    override fun readText(name: String): String? {
      if (hasPendingStaleRead) {
        hasPendingStaleRead = false
        return pendingStaleText
      }
      return currentText
    }

    override fun writeText(name: String, text: String) {
      writeTextCallCount += 1
      currentText = text
    }

    override fun delete(name: String): Boolean {
      val existed = currentText != null
      currentText = null
      return existed
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(currentText)
      if (updated.write) {
        currentText = updated.text
      }
      return updated.result
    }

    fun returnStaleTextOnNextRead(text: String?) {
      pendingStaleText = text
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      hasPendingStaleRead = false
      pendingStaleText = null
    }
  }
}
