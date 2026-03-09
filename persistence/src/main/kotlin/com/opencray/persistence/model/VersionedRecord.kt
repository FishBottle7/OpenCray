package com.opencray.persistence.model

/**
 * Common metadata for restart-safe persisted records.
 */
interface VersionedRecord {
  val schemaVersion: Int
  val migrationVersion: Int
  val termuxMetadataVersion: Int

  /** Monotonic per-record version (incremented by caller on mutation). */
  val recordVersion: Long

  val createdAtEpochMs: Long
  val updatedAtEpochMs: Long

  /** Reserved hook for future Termux metadata. */
  val termuxMetadata: Map<String, String>

  /** Reserved hook for vendor/feature extensions. */
  val extensions: Map<String, String>
}
