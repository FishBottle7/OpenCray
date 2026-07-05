package com.opencray.app

import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStorePendingUserInputPersistenceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun enqueuePendingUserInputPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-pending-input-concurrent-extension")
    val rawStore = JsonFileChatWorkspaceStore(directory)
    var sessionId: String? = null
    var injectedConcurrentExtension = false
    var clock = 1_700_000_000_000L
    val store = ChatSessionLocalStore(
      directory = directory,
      nowEpochMs = {
        val now = clock++
        if (!injectedConcurrentExtension && sessionId != null) {
          injectedConcurrentExtension = true
          val current = requireNotNull(rawStore.load())
          rawStore.save(
            current.copy(
              extensions = current.extensions + ("concurrent.marker" to "foreground"),
              recordVersion = current.recordVersion + 1,
              updatedAtEpochMs = now,
            ),
          )
        }
        now
      },
    )
    sessionId = store.loadState().activeSession.sessionId
    val activeSessionId = requireNotNull(sessionId)

    store.enqueuePendingUserInput(sessionId = activeSessionId, text = "Queue a background follow-up")

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    assertEquals(
      listOf("Queue a background follow-up"),
      ChatSessionLocalStore(directory).loadPendingUserInputs(activeSessionId).map(PendingUserInputEntry::text),
    )
  }

  @Test
  fun appendPendingUserInputAsSubmittedTurnPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-submit-pending-concurrent-extension")
    val setupStore = ChatSessionLocalStore(directory)
    val sessionId = setupStore.loadState().activeSession.sessionId
    val queued = setupStore.enqueuePendingUserInput(sessionId = sessionId, text = "Continue after current run")
    val rawStore = JsonFileChatWorkspaceStore(directory)
    var injectedConcurrentExtension = false
    var clock = requireNotNull(rawStore.load()).updatedAtEpochMs + 1
    val store = ChatSessionLocalStore(
      directory = directory,
      nowEpochMs = {
        val now = clock++
        if (!injectedConcurrentExtension) {
          injectedConcurrentExtension = true
          val current = requireNotNull(rawStore.load())
          rawStore.save(
            current.copy(
              extensions = current.extensions + ("concurrent.marker" to "foreground"),
              recordVersion = current.recordVersion + 1,
              updatedAtEpochMs = now,
            ),
          )
        }
        now
      },
    )

    val consumed = store.appendPendingUserInputAsSubmittedTurn(
      sessionId = sessionId,
      queueId = queued.queueId,
      assistantMessageId = "assistant-placeholder",
      assistantPlaceholderText = "Working on it",
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals(queued, consumed)
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    assertTrue(ChatSessionLocalStore(directory).loadPendingUserInputs(sessionId).isEmpty())
    val restoredSession = requireNotNull(ChatSessionLocalStore(directory).loadSession(sessionId))
    assertEquals("assistant-placeholder", restoredSession.messages.last().messageId)
  }
}
