package com.opencray.filesystem

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Serializes one workspace mutation scope across runtime processes. */
class FileMutationLockCoordinator(
  lockDirectory: Path,
) {
  private val normalizedLockDirectory = lockDirectory.toAbsolutePath().normalize()

  fun <T> withScopes(
    scopes: Collection<Path>,
    action: () -> T,
  ): T {
    val lockFiles = scopes
      .map { scope -> scope.toAbsolutePath().normalize() }
      .distinct()
      .map(::lockFileForScope)
      .sortedBy(Path::toString)
    return withLockFiles(lockFiles, index = 0, action = action)
  }

  private fun <T> withLockFiles(
    lockFiles: List<Path>,
    index: Int,
    action: () -> T,
  ): T {
    if (index >= lockFiles.size) {
      return action()
    }
    val lockFile = lockFiles[index]
    val lockKey = lockFile.toString()
    val heldKeys = checkNotNull(HELD_LOCK_KEYS.get())
    if (lockKey in heldKeys) {
      return withLockFiles(lockFiles, index + 1, action)
    }
    val processLock = PROCESS_LOCKS.computeIfAbsent(lockKey) { ReentrantLock(true) }
    processLock.lock()
    try {
      Files.createDirectories(normalizedLockDirectory)
      RandomAccessFile(lockFile.toFile(), "rw").channel.use { channel ->
        channel.lock().use {
          heldKeys += lockKey
          try {
            return withLockFiles(lockFiles, index + 1, action)
          } finally {
            heldKeys -= lockKey
          }
        }
      }
    } finally {
      processLock.unlock()
    }
  }

  private fun lockFileForScope(scope: Path): Path = normalizedLockDirectory.resolve(
    "${sha256Hex(scope.toString())}.lck",
  )

  private fun sha256Hex(value: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private companion object {
    val PROCESS_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    val HELD_LOCK_KEYS = ThreadLocal.withInitial { mutableSetOf<String>() }
  }
}
