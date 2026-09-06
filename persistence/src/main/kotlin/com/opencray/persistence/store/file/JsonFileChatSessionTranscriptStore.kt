package com.opencray.persistence.store.file

import com.opencray.persistence.migration.NoOpJsonMigration
import com.opencray.persistence.model.PersistedChatSessionTranscript
import com.opencray.persistence.store.DurableTextStorage
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Stores one [PersistedChatSessionTranscript] per chat session under
 * `<root>/chat-sessions/<encoded-session-id>/transcript.json`.
 *
 * Each session directory gets its own [DirectoryDurableTextStorage], so every transcript
 * participates in the same process file lock protocol as the workspace record while the
 * lock file itself stays session-scoped. Session ids are base64url-encoded into a single
 * safe filename segment because arbitrary ids (including path separators) are allowed.
 *
 * The class is open with open accessor methods so tests can subclass it and count
 * per-session reads without changing lock or durability behavior.
 */
open class JsonFileChatSessionTranscriptStore(
  directory: File,
) {
  private val sessionsDirectory = File(directory, SESSIONS_DIRECTORY_NAME)

  open fun load(sessionId: String): PersistedChatSessionTranscript? =
    storageFor(sessionId).let { storage ->
      readRecord(
        name = TRANSCRIPT_FILE_NAME,
        serializer = PersistedChatSessionTranscript.serializer(),
        migration = NoOpJsonMigration,
        storage = storage,
      )
    }

  open fun save(record: PersistedChatSessionTranscript) {
    writeRecord(
      name = TRANSCRIPT_FILE_NAME,
      serializer = PersistedChatSessionTranscript.serializer(),
      value = record,
      storage = storageFor(record.sessionId),
    )
  }

  /**
   * Atomic read-modify-write of one session's transcript under that session's own lock.
   * The transform receives the persisted record or null when the transcript does not
   * exist yet, and returns the replacement (or the original with a no-write marker).
   */
  open fun <R> update(
    sessionId: String,
    transform: (PersistedChatSessionTranscript?) -> RecordStorageUpdate<PersistedChatSessionTranscript, R>,
  ): R = storageFor(sessionId).updateRecord(
    name = TRANSCRIPT_FILE_NAME,
    serializer = PersistedChatSessionTranscript.serializer(),
    migration = NoOpJsonMigration,
  ) { current ->
    val updated = transform(current)
    RecordStorageUpdate(
      value = updated.value,
      result = updated.result,
      write = updated.write,
    )
  }

  open fun exists(sessionId: String): Boolean =
    File(sessionDirectoryFor(sessionId), TRANSCRIPT_FILE_NAME).isFile

  /**
   * Removes the session's transcript file and its directory (lock file included) as a
   * best effort. The transcript delete itself runs under the session's lock; deleting
   * the lock file while another process holds it may fail, in which case remnants are
   * left behind as harmless orphans for future sweeps.
   */
  open fun deleteSessionTranscript(sessionId: String): Boolean {
    val deletedFile = storageFor(sessionId).delete(TRANSCRIPT_FILE_NAME)
    val sessionDirectory = sessionDirectoryFor(sessionId)
    val lockFile = File(sessionDirectory, "$TRANSCRIPT_FILE_NAME.lock")
    lockFile.delete()
    val remaining = sessionDirectory.listFiles().orEmpty()
    if (remaining.isEmpty()) {
      sessionDirectory.delete()
    }
    return deletedFile
  }

  private fun storageFor(sessionId: String): DurableTextStorage =
    DirectoryDurableTextStorage(sessionDirectoryFor(sessionId))

  private fun sessionDirectoryFor(sessionId: String): File =
    File(sessionsDirectory, encodeSessionId(sessionId))

  companion object {
    internal const val SESSIONS_DIRECTORY_NAME = "chat-sessions"
    internal const val TRANSCRIPT_FILE_NAME = "transcript.json"

    internal fun encodeSessionId(sessionId: String): String {
      val normalized = sessionId.trim().ifBlank { "default-session" }
      val encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
      return "session-$encoded"
    }

    internal fun decodeSessionId(directoryName: String): String? {
      val encoded = directoryName.removePrefix("session-")
        .takeIf { encodedValue -> encodedValue.isNotBlank() && directoryName.startsWith("session-") }
        ?: return null
      return runCatching {
        String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
      }.getOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
    }
  }
}
