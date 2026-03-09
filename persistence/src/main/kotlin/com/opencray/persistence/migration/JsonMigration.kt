package com.opencray.persistence.migration

/**
 * Hook to migrate persisted JSON across schema versions.
 *
 * Implementations must be deterministic and side-effect free.
 */
fun interface JsonMigration {
  fun migrate(fromSchemaVersion: Int, json: String): String
}

object NoOpJsonMigration : JsonMigration {
  override fun migrate(fromSchemaVersion: Int, json: String): String = json
}
