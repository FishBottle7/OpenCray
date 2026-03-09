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
}
