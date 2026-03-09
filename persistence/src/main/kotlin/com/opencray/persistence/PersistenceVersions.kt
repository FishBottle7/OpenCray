package com.opencray.persistence

/**
 * Persistence record schema version. Bumped when persisted JSON structure changes.
 */
object PersistenceSchemaVersion {
  const val CURRENT: Int = 1
}

/**
 * Migration protocol version for the persistence layer.
 *
 * This is separate from [PersistenceSchemaVersion] to allow future multi-step migrations.
 */
object PersistenceMigrationVersion {
  const val CURRENT: Int = 1
}

/**
 * Placeholder hook for future Termux phase metadata persisted alongside records.
 */
object TermuxMetadataSchemaVersion {
  const val NONE: Int = 0
  const val CURRENT: Int = NONE
}
