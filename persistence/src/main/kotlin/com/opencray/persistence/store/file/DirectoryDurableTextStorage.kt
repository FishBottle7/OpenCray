package com.opencray.persistence.store.file

import com.opencray.persistence.store.DurableTextStorage
import java.io.File
import java.io.IOException

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

    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text, Charsets.UTF_8)

    // Best-effort atomic replace within same directory.
    if (file.exists() && !file.delete()) {
      throw IOException("Failed to delete existing file for replace: ${file.path}")
    }
    if (!tmp.renameTo(file)) {
      // Fallback: copy then delete tmp.
      file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
      tmp.delete()
    }
  }

  override fun delete(name: String): Boolean {
    val file = fileFor(name)
    return file.exists() && file.delete()
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

  private companion object {
    private val VALID_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
  }
}
