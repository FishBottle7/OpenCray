package com.opencray.persistence.store.file

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    return synchronized(lockFor(file)) {
      withFileLock(file) {
        readExistingText(file)
      }
    }
  }

  override fun writeText(name: String, text: String) {
    val file = fileFor(name)
    ensureDirectory()

    synchronized(lockFor(file)) {
      withFileLock(file) {
        writeTextLocked(file, text)
      }
    }
  }

  override fun delete(name: String): Boolean {
    val file = fileFor(name)
    if (!directory.exists()) return false
    synchronized(lockFor(file)) {
      return withFileLock(file) {
        deleteLocked(file)
      }
    }
  }

  override fun <T> updateText(
    name: String,
    update: (String?) -> DurableTextUpdate<T>,
  ): T {
    val file = fileFor(name)
    ensureDirectory()
    return synchronized(lockFor(file)) {
      withFileLock(file) {
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
      tmp.writeText(text, Charsets.UTF_8)
      replaceAtomically(tmp = tmp, destination = file)
    } finally {
      if (tmp.exists()) {
        tmp.delete()
      }
    }
  }

  private fun deleteLocked(file: File): Boolean =
    file.exists() && file.delete()

  private fun <T> withFileLock(
    file: File,
    block: () -> T,
  ): T {
    ensureDirectory()
    val parent = requireNotNull(file.parentFile) {
      "DurableTextStorage file must have a parent directory: ${file.path}"
    }
    val lockFile = File(parent, "${file.name}.lock")
    RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
      randomAccessFile.channel.use { channel ->
        channel.lock().use {
          return block()
        }
      }
    }
  }

  private fun lockFor(file: File): Any =
    FILE_LOCKS.computeIfAbsent(file.absolutePath) { Any() }

  private companion object {
    private val VALID_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    private val FILE_LOCKS = ConcurrentHashMap<String, Any>()
  }
}
