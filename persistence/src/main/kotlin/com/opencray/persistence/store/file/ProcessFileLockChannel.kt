package com.opencray.persistence.store.file

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.random.Random

/**
 * Acquires an OS-level exclusive lock on a sidecar lock file without turning transient
 * contention into a fatal failure.
 *
 * Durable JSON records are guarded by short read-modify-write critical sections that
 * run concurrently across threads and app processes (`:app`, `:runtime`,
 * `:runtime_controller`). A blocking `FileChannel.lock()` normally waits for those
 * sections to finish, but when wait edges would form a cycle, kernels answer with
 * `IOException: Resource deadlock would occur` (EDEADLK) instead of waiting. Letting
 * that propagate crashes the whole runtime process and interrupts every in-flight run.
 *
 * This helper treats deadlock-avoidance failures and busy locks the same way: retry
 * with jittered backoff until the holder's short critical section completes or a
 * generous deadline expires. Non-deadlock IO failures (including
 * [java.nio.channels.ClosedByInterruptException]) still fail fast.
 */
object ProcessFileLockChannel {

  private const val ACQUIRE_TIMEOUT_MS: Long = 20_000L
  private const val INITIAL_BACKOFF_MS: Long = 10L
  private const val MAX_BACKOFF_MS: Long = 200L

  fun <T> withLock(lockFile: File, block: () -> T): T {
    RandomAccessFile(lockFile, "rw").use { lockedFile ->
      lockedFile.channel.use { channel ->
        val lock = acquire({ channel.tryLock() }, ACQUIRE_TIMEOUT_MS)
        try {
          return block()
        } finally {
          runCatching { lock.release() }
        }
      }
    }
  }

  internal fun acquire(
    attemptLock: () -> FileLock?,
    timeoutMs: Long,
  ): FileLock {
    require(timeoutMs > 0) { "Process file lock acquire timeout must be positive." }
    val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
    var backoffMs = INITIAL_BACKOFF_MS
    var lastDeadlockFailure: IOException? = null
    while (true) {
      try {
        attemptLock()?.let { return it }
      } catch (failure: IOException) {
        if (!isDeadlockAvoidance(failure)) {
          throw failure
        }
        lastDeadlockFailure = failure
      } catch (_: OverlappingFileLockException) {
        // The same channel already holds this JVM's lock; treat as busy and retry.
      }
      if (System.nanoTime() >= deadlineNs) {
        throw lastDeadlockFailure
          ?: IOException("Timed out acquiring process file lock after $timeoutMs ms.")
      }
      Thread.sleep(jitteredBackoffMs(backoffMs))
      backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
    }
  }

  private fun isDeadlockAvoidance(failure: IOException): Boolean =
    failure.message?.contains("deadlock", ignoreCase = true) == true

  private fun jitteredBackoffMs(baseMs: Long): Long =
    (baseMs * (75L + Random.nextLong(51L)) / 100L).coerceAtLeast(1L)
}
