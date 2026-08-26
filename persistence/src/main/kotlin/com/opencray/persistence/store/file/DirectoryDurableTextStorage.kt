package com.opencray.persistence.store.file

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class DirectoryDurableTextStorage(
  private val directory: File,
) : DurableTextStorage {

  init {
    require(!directory.exists() || directory.isDirectory) {
      "DirectoryDurableTextStorage directory must be a directory: ${directory.path}"
    }
  }

  override fun readText(name: String): String? {
    val file = fileFor(name)
    if (!directory.exists()) return null
    return withProcessFileLock(file) {
      readExistingText(file)
    }
  }

  override fun writeText(name: String, text: String) {
    val file = fileFor(name)
    ensureDirectory()

    withProcessFileLock(file) {
      writeTextLocked(file, text)
    }
  }

  override fun delete(name: String): Boolean {
    val file = fileFor(name)
    if (!directory.exists()) return false
    return withProcessFileLock(file) {
      deleteLocked(file)
    }
  }

  override fun backupCorrupt(name: String): Boolean {
    val file = fileFor(name)
    if (!directory.exists()) return true
    return withProcessFileLock(file) {
      backupCorruptLocked(file)
    }
  }

  override fun <T> updateText(
    name: String,
    update: (String?) -> DurableTextUpdate<T>,
  ): T {
    val file = fileFor(name)
    ensureDirectory()
    return withProcessFileLock(file) {
      val updated = update(readExistingText(file))
      if (updated.write) {
        if (updated.text == null) {
          deleteLocked(file)
        } else {
          writeTextLocked(file, updated.text)
        }
      }
      updated.result
    }
  }

  private fun ensureDirectory() {
    if (!directory.exists()) {
      if (!directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Failed to create persistence directory: ${directory.path}")
      }
    }
  }

  private fun fileFor(name: String): File {
    require(name.matches(VALID_NAME)) {
      "DurableTextStorage name must be a safe filename segment: '$name'"
    }
    return File(directory, name)
  }

  private fun replaceAtomically(tmp: File, destination: File) {
    try {
      Files.move(
        tmp.toPath(),
        destination.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(
        tmp.toPath(),
        destination.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  private fun readExistingText(file: File): String? =
    if (!file.exists()) {
      null
    } else {
      file.readText(Charsets.UTF_8)
    }

  private fun writeTextLocked(file: File, text: String) {
    val parent = requireNotNull(file.parentFile) {
      "DurableTextStorage file must have a parent directory: ${file.path}"
    }
    val tmp = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp").toFile()
    try {
      FileChannel.open(
        tmp.toPath(),
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING,
      ).use { channel ->
        channel.write(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)))
        channel.force(true)
      }
      replaceAtomically(tmp = tmp, destination = file)
    } finally {
      if (tmp.exists()) {
        tmp.delete()
      }
    }
  }

  private fun deleteLocked(file: File): Boolean =
    file.exists() && file.delete()

  private fun backupCorruptLocked(file: File): Boolean {
    if (!file.isFile) return true
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    for (attempt in 0 until MAX_CORRUPT_BACKUP_ATTEMPTS) {
      val suffix = if (attempt == 0) "" else "-${attempt + 1}"
      val target = File(directory, "${file.name}.corrupt-$stamp$suffix")
      try {
        Files.copy(file.toPath(), target.toPath())
        return true
      } catch (_: FileAlreadyExistsException) {
      } catch (_: IOException) {
        return false
      }
    }
    return false
  }

  private fun <T> withFileLock(
    file: File,
    block: () -> T,
  ): T {
    ensureDirectory()
    val parent = requireNotNull(file.parentFile) {
      "DurableTextStorage file must have a parent directory: ${file.path}"
    }
    val lockFile = File(parent, "${file.name}.lock")
    return ProcessFileLockChannel.withLock(lockFile, block)
  }

  private fun <T> withProcessFileLock(
    file: File,
    block: () -> T,
  ): T {
    val jvmLock = lockFor(file)
    if (Thread.holdsLock(jvmLock)) {
      // The JVM monitor is reentrant, but reacquiring its OS sidecar lock is not.
      return block()
    }
    return synchronized(jvmLock) {
      withFileLock(file, block)
    }
  }

  private fun lockFor(file: File): Any =
    FILE_LOCKS.computeIfAbsent(file.absolutePath) { Any() }

  private companion object {
    private val VALID_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    private val FILE_LOCKS = ConcurrentHashMap<String, Any>()
    private const val MAX_CORRUPT_BACKUP_ATTEMPTS = 32
  }
}
