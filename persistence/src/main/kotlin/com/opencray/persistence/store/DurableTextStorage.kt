package com.opencray.persistence.store

/**
 * Durable, restart-safe storage abstraction for non-sensitive text payloads.
 *
 * IMPORTANT: Do not store secrets in this abstraction.
 */
interface DurableTextStorage {
  fun readText(name: String): String?
  fun writeText(name: String, text: String)
  fun delete(name: String): Boolean

  fun backupCorrupt(name: String): Boolean = true

  fun <T> updateText(
    name: String,
    update: (String?) -> DurableTextUpdate<T>,
  ): T {
    val current = readText(name)
    val updated = update(current)
    if (updated.write) {
      if (updated.text == null) {
        delete(name)
      } else {
        writeText(name, updated.text)
      }
    }
    return updated.result
  }
}

data class DurableTextUpdate<T>(
  val text: String?,
  val result: T,
  val write: Boolean = true,
)
