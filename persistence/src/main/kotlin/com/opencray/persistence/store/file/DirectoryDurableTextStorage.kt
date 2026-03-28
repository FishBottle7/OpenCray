package com.opencray.persistence.store.file

import com.opencray.persistence.store.DurableTextStorage
import java.io.File
import java.io.IOException
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
    if (!file.exists()) return null
    return file.readText(Charsets.UTF_8)
  }

  override fun writeText(name: String, text: String) {
    val file = fileFor(name)
    ensureDirectory()

    synchronized(lockFor(file)) {
      val tmp = Files.createTempFile(file.parentFile.toPath(), "${file.name}.", ".tmp").toFile()
      try {
        tmp.writeText(text, Charsets.UTF_8)
        replaceAtomically(tmp = tmp, destination = file)
      } finally {
        if (tmp.exists()) {
          tmp.delete()
        }
      }
    }
  }

  override fun delete(name: String): Boolean {
    val file = fileFor(name)
    synchronized(lockFor(file)) {
      return file.exists() && file.delete()
    }
  }

  private fun ensureDirectory() {
    if (!directory.exists()) {
      if (!directory.mkdirs()) {
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

  private fun lockFor(file: File): Any =
    FILE_LOCKS.computeIfAbsent(file.absolutePath) { Any() }

  private companion object {
    private val VALID_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    private val FILE_LOCKS = ConcurrentHashMap<String, Any>()
  }
}
