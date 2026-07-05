package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreTranscriptProcessSafetyTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun appendMessagePreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-append-message-concurrent-extension")
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

    store.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Background result",
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    val restoredSession = requireNotNull(ChatSessionLocalStore(directory).loadSession(activeSessionId))
    assertEquals("Background result", restoredSession.messages.last().text)
  }

  @Test
  fun appendSubmittedTurnPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-submitted-turn-concurrent-extension")
    val rawStore = JsonFileChatWorkspaceStore(directory)
    var sessionId: String? = null
    var injectedConcurrentExtension = false
    var clock = 1_700_000_010_000L
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
    val assistantMessageId = store.reserveMessageId(ChatTranscriptRole.ASSISTANT)

    store.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "Run while detached",
      assistantMessageId = assistantMessageId,
      assistantPlaceholderText = "Working",
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    val restoredSession = requireNotNull(ChatSessionLocalStore(directory).loadSession(activeSessionId))
    assertEquals(assistantMessageId, restoredSession.messages.last().messageId)
  }
}
