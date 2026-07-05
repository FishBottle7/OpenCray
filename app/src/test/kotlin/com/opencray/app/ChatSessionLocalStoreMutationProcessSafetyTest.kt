package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreMutationProcessSafetyTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun copySessionPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-copy-session-concurrent-extension")
    val setupStore = ChatSessionLocalStore(directory)
    val sessionId = setupStore.loadState().activeSession.sessionId
    setupStore.appendUserMessage(sessionId, "Copy this session")
    val rawStore = JsonFileChatWorkspaceStore(directory)
    val store = storeWithConcurrentExtension(directory, rawStore)

    val copiedState = store.copySession(sessionId)

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    assertNotNull(ChatSessionLocalStore(directory).loadSession(copiedState.activeSession.sessionId))
  }

  @Test
  fun branchSessionPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-branch-session-concurrent-extension")
    val setupStore = ChatSessionLocalStore(directory)
    val sessionId = setupStore.loadState().activeSession.sessionId
    setupStore.appendUserMessage(sessionId, "Branch this session")
    val branchMessageId = requireNotNull(setupStore.loadSession(sessionId)).messages.last().messageId
    val rawStore = JsonFileChatWorkspaceStore(directory)
    val store = storeWithConcurrentExtension(directory, rawStore)

    val branchedState = store.branchSessionFromMessage(sessionId, branchMessageId)

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    assertNotNull(ChatSessionLocalStore(directory).loadSession(branchedState.activeSession.sessionId))
  }

  @Test
  fun replaceMessagePreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-replace-message-concurrent-extension")
    val setupStore = ChatSessionLocalStore(directory)
    val sessionId = setupStore.loadState().activeSession.sessionId
    setupStore.appendUserMessage(sessionId, "Original text")
    val messageId = requireNotNull(setupStore.loadSession(sessionId)).messages.last().messageId
    val rawStore = JsonFileChatWorkspaceStore(directory)
    val store = storeWithConcurrentExtension(directory, rawStore)

    store.replaceMessage(
      sessionId = sessionId,
      messageId = messageId,
      role = ChatTranscriptRole.USER,
      text = "Edited text",
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    val restoredMessage = requireNotNull(ChatSessionLocalStore(directory).loadSession(sessionId)).messages.last()
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    assertEquals("Edited text", restoredMessage.text)
  }

  private fun storeWithConcurrentExtension(
    directory: File,
    rawStore: JsonFileChatWorkspaceStore,
  ): ChatSessionLocalStore {
    var injectedConcurrentExtension = false
    var clock = requireNotNull(rawStore.load()).updatedAtEpochMs + 1
    return ChatSessionLocalStore(
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
  }
}
