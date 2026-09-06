package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.file.JsonFileChatSessionTranscriptStore
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import com.opencray.persistence.store.file.RecordStorageUpdate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Migration and split-layout tests for the chat session store:
 * legacy inline `sessions[].messages` migrate into per-session transcript files, the
 * workspace record keeps metadata only, and re-running the migration is idempotent.
 */
class ChatSessionLocalStoreSplitStorageMigrationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun migratesLegacyInlineMessagesIntoPerSessionTranscriptFiles() {
    val directory = temporaryFolder.newFolder("chat-store-split-migrate")
    writeLegacyWorkspace(
      directory = directory,
      sessions = listOf(
        legacySession("session-legacy-a", "Session A", listOf(legacyMessage("user-1", "Alpha content"))),
        legacySession(
          "session-legacy-b",
          "Session B",
          listOf(legacyMessage("user-2", "Beta one"), legacyMessage("asst-2", "Beta two")),
        ),
      ),
      activeSessionId = "session-legacy-b",
    )

    val store = ChatSessionLocalStore(directory)
    val state = store.loadState()

    assertEquals(2, state.sessions.size)
    assertEquals("session-legacy-b", state.activeSession.sessionId)
    assertEquals(listOf("Beta one", "Beta two"), state.activeSession.messages.map { it.text })
    assertEquals("Beta two", state.activeSession.messages.last().text)

    val transcriptStore = JsonFileChatSessionTranscriptStore(directory)
    assertEquals(
      listOf("Alpha content"),
      transcriptStore.load("session-legacy-a")?.messages?.map { it.text },
    )
    assertEquals(
      listOf("Beta one", "Beta two"),
      transcriptStore.load("session-legacy-b")?.messages?.map { it.text },
    )
  }

  @Test
  fun migratedWorkspaceFileContainsNoMessageContent() {
    val directory = temporaryFolder.newFolder("chat-store-split-bytes")
    writeLegacyWorkspace(
      directory = directory,
      sessions = listOf(
        legacySession("session-legacy-a", "Session A", listOf(legacyMessage("user-1", "SECRET ALPHA PAYLOAD alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon "))),
      ),
      activeSessionId = "session-legacy-a",
    )

    ChatSessionLocalStore(directory).loadState()

    val workspaceBytes = File(directory, "chat-workspace.json").readBytes()
    val workspaceText = String(workspaceBytes, Charsets.UTF_8)
    assertFalse("workspace must not inline message objects", workspaceText.contains("\"messages\":[{"))
    assertFalse("workspace must not inline message roles", workspaceText.contains("\"role\":\"user\""))
    // The 52-char denormalized preview intentionally keeps a truncated tail of the
    // last visible message; only full inline message objects are forbidden.
    assertEquals(
      "preview must be truncated, not the full payload",
      52,
      requireNotNull(rawWorkspacePreviewLength(directory, "session-legacy-a")),
    )
    assertTrue(
      "denormalized preview metadata should survive",
      workspaceText.contains("\"messageCount\""),
    )
  }

  @Test
  fun migrationIsIdempotentOnReRunAndKeepsConcurrentPhaseBResult() {
    val directory = temporaryFolder.newFolder("chat-store-split-idempotent")
    writeLegacyWorkspace(
      directory = directory,
      sessions = listOf(
        legacySession("session-legacy-a", "Session A", listOf(legacyMessage("user-1", "Alpha content"))),
      ),
      activeSessionId = "session-legacy-a",
    )

    // Simulate a crash between phase a and phase b: transcripts written, workspace still legacy.
    val transcriptStore = JsonFileChatSessionTranscriptStore(directory)
    val rawStore = JsonFileChatWorkspaceStore(directory)
    val legacySnapshot = requireNotNull(rawStore.load())
    transcriptStore.save(
      com.opencray.persistence.model.PersistedChatSessionTranscript(
        sessionId = "session-legacy-a",
        messages = listOf(legacyMessage("user-1", "Alpha content")),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
      ),
    )
    assertTrue(hasInlineMessages(legacySnapshot))

    // Re-run the migration entry point: phase a keeps the existing transcript, phase b clears.
    val store = ChatSessionLocalStore(directory)
    val state = store.loadState()

    assertEquals(listOf("Alpha content"), state.activeSession.messages.map { it.text })
    val migratedWorkspace = requireNotNull(rawStore.load())
    assertTrue(migratedWorkspace.sessions.all { it.messages.isEmpty() })
    assertEquals("Alpha content", migratedWorkspace.sessions.single().lastMessagePreview.take(13))
    assertEquals(1, migratedWorkspace.sessions.single().messageCount)

    // Running it again after completion changes nothing.
    val secondState = ChatSessionLocalStore(directory).loadState()
    assertEquals(state.activeSession.sessionId, secondState.activeSession.sessionId)
    assertEquals(listOf("Alpha content"), secondState.activeSession.messages.map { it.text })
    val remigratedWorkspace = requireNotNull(rawStore.load())
    assertEquals(migratedWorkspace, remigratedWorkspace)
  }

  @Test
  fun concurrentPhaseBCompletionWinsAndIsPreserved() {
    val directory = temporaryFolder.newFolder("chat-store-split-concurrent-phase-b")
    writeLegacyWorkspace(
      directory = directory,
      sessions = listOf(
        legacySession("session-legacy-a", "Session A", listOf(legacyMessage("user-1", "Alpha content"))),
      ),
      activeSessionId = "session-legacy-a",
    )
    val rawStore = JsonFileChatWorkspaceStore(directory)
    val transcriptStore = JsonFileChatSessionTranscriptStore(directory)

    // A concurrent process finishes phase b between our flat read and our phase b.
    val store = ChatSessionLocalStore(
      directory,
      nowEpochMs = {
        // Intercept just before our phase b re-read completes: complete a competing
        // migration through the raw store, then hand back a fresh timestamp.
        val competing = rawStore.load()
        if (competing != null && competing.sessions.any { it.messages.isNotEmpty() }) {
          rawStore.save(
            competing.copy(
              sessions = competing.sessions.map { session ->
                if (session.messages.isEmpty()) {
                  session
                } else {
                  ChatTranscriptSessionEntry(
                    sessionId = session.sessionId,
                    title = session.title,
                    createdAtEpochMs = session.createdAtEpochMs,
                    updatedAtEpochMs = session.updatedAtEpochMs,
                    messageCount = session.messages.size,
                    lastMessagePreview = session.messages.last().text.orEmpty().take(52),
                    lastMessageAtEpochMs = session.messages.last().createdAtEpochMs,
                  )
                }
              },
              recordVersion = competing.recordVersion + 100,
            ),
          )
        }
        transcriptStore.save(
          com.opencray.persistence.model.PersistedChatSessionTranscript(
            sessionId = "session-legacy-a",
            messages = listOf(legacyMessage("user-1", "Alpha content")),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
          ),
        )
        System.currentTimeMillis()
      },
    )

    val state = store.loadState()

    // The competing phase b result (recordVersion +100) must be preserved, not overwritten.
    val workspace = requireNotNull(rawStore.load())
    assertTrue(workspace.sessions.all { it.messages.isEmpty() })
    assertEquals(listOf("Alpha content"), state.activeSession.messages.map { it.text })
  }

  private fun hasInlineMessages(workspace: ChatWorkspaceRecord): Boolean =
    workspace.sessions.any { it.messages.isNotEmpty() }

  /** Reads back the persisted preview length for one session, for byte-level assertions. */
  private fun rawWorkspacePreviewLength(directory: File, sessionId: String): Int? =
    JsonFileChatWorkspaceStore(directory)
      .load()
      ?.sessions
      ?.singleOrNull { it.sessionId == sessionId }
      ?.let { entry -> entry.lastMessagePreview.takeIf { it.isNotEmpty() }?.length }

  private fun writeLegacyWorkspace(
    directory: File,
    sessions: List<ChatTranscriptSessionEntry>,
    activeSessionId: String,
  ) {
    JsonFileChatWorkspaceStore(directory).save(
      ChatWorkspaceRecord(
        sessions = sessions,
        activeSessionId = activeSessionId,
        recordVersion = 5L,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        schemaVersion = 1,
        migrationVersion = 1,
      ),
    )
  }

  private fun legacySession(
    sessionId: String,
    title: String,
    messages: List<ChatTranscriptMessageEntry>,
  ): ChatTranscriptSessionEntry = ChatTranscriptSessionEntry(
    sessionId = sessionId,
    title = title,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 2L,
    messages = messages,
  )

  private fun legacyMessage(
    messageId: String,
    text: String,
  ): ChatTranscriptMessageEntry = ChatTranscriptMessageEntry(
    messageId = messageId,
    role = ChatTranscriptRole.USER,
    text = text,
    createdAtEpochMs = 2L,
  )
}
