package com.opencray.persistence

/**
 * Persistence record schema version. Bumped when persisted JSON structure changes.
 *
 * v2: chat workspace transcripts moved out of `chat-workspace.json` into per-session
 * `chat-sessions/<encoded-session-id>/transcript.json` files. Workspace session entries
 * now carry denormalized `messageCount`/`lastMessagePreview`/`lastMessageAtEpochMs`
 * metadata and serialize `messages` as an empty array.
 */
object PersistenceSchemaVersion {
  const val CURRENT: Int = 2
}

/**
 * Migration protocol version for the persistence layer.
 *
 * This is separate from [PersistenceSchemaVersion] to allow future multi-step migrations.
 */
object PersistenceMigrationVersion {
  const val CURRENT: Int = 2
}

/**
 * Placeholder hook for future Termux phase metadata persisted alongside records.
 */
object TermuxMetadataSchemaVersion {
  const val NONE: Int = 0
  const val CURRENT: Int = NONE
}
