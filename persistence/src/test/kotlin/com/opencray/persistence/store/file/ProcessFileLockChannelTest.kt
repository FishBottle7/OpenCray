package com.opencray.persistence.store.file

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessFileLockChannelTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun withLockRunsBlockAndReleasesSidecarLock() {
    val lockFile = temporaryFolder.newFolder("process-file-lock").resolve("record.json.lock")

    val result = ProcessFileLockChannel.withLock(lockFile) { "payload" }

    assertEquals("payload", result)
    assertTrue(lockFile.exists())
  }

  @Test
  fun withLockWaitsForConflictingChannelThenAcquires() {
    val directory = temporaryFolder.newFolder("process-file-lock-conflict")
    val lockFile = File(directory, "record.json.lock")
    RandomAccessFile(lockFile, "rw").use { holderStream ->
      val heldLock = requireNotNull(holderStream.channel.tryLock())
      val releaseLatch = CountDownLatch(1)
      val acquireThread = Executors.newSingleThreadExecutor()
      try {
        val future = acquireThread.submit<String> {
          ProcessFileLockChannel.withLock(lockFile) {
            releaseLatch.countDown()
            "acquired-after-conflict"
          }
        }
        // The second channel cannot take the lock while it is held elsewhere.
        assertEquals(false, releaseLatch.await(300, TimeUnit.MILLISECONDS))
        heldLock.release()
        assertEquals("acquired-after-conflict", future.get(10, TimeUnit.SECONDS))
      } finally {
        acquireThread.shutdownNow()
      }
    }
  }

  @Test
  fun acquireRetriesDeadlockAvoidanceFailureUntilLockSucceeds() {
    val deadlockFailures = AtomicInteger()
    val attempt = {
      if (deadlockFailures.incrementAndGet() <= 3) {
        throw IOException("Resource deadlock would occur")
      }
      fakeFileLock()
    }

    val lock = ProcessFileLockChannel.acquire(attempt, timeoutMs = 5_000L)

    assertNotNull(lock)
    assertEquals(4, deadlockFailures.get())
    runCatching { lock.release() }
  }

  @Test
  fun acquireRethrowsNonDeadlockIoFailureImmediately() {
    val attempts = AtomicInteger()

    try {
      ProcessFileLockChannel.acquire(
        attemptLock = {
          attempts.incrementAndGet()
          throw IOException("Permission denied")
        },
        timeoutMs = 5_000L,
      )
      fail("Expected the non-deadlock IO failure to propagate.")
    } catch (failure: IOException) {
      assertEquals("Permission denied", failure.message)
    }
    assertEquals(1, attempts.get())
  }

  @Test
  fun acquireTimesOutWithDeadlockFailureWhenLockNeverBecomesAvailable() {
    val deadlockFailure = IOException("Resource deadlock would occur")

    try {
      ProcessFileLockChannel.acquire(
        attemptLock = { throw deadlockFailure },
        timeoutMs = 120L,
      )
      fail("Expected the acquire to time out.")
    } catch (failure: IOException) {
      assertTrue(failure.message.orEmpty().contains("deadlock", ignoreCase = true))
    }
  }

  private fun fakeFileLock(): FileLock {
    val probeFile = File(temporaryFolder.root, "process-file-lock-probe-${System.nanoTime()}")
    RandomAccessFile(probeFile, "rw").use { stream ->
      return stream.channel.tryLock() ?: error("Probe file lock unexpectedly unavailable.")
    }
  }
}
