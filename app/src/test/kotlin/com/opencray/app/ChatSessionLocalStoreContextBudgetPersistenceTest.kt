package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreContextBudgetPersistenceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadMaintainedContextWindowTokensRestoresPersistedValueAcrossStoreRecreation() {
    val directory = temporaryFolder.newFolder("chat-store-context-window-persist")
    val firstStore = ChatSessionLocalStore(directory)
    val sessionId = firstStore.loadState().activeSession.sessionId

    firstStore.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = 131072,
    )

    val restoredStore = ChatSessionLocalStore(directory)

    assertEquals(131072, restoredStore.loadMaintainedContextWindowTokens(sessionId))
  }

  @Test
  fun replaceMaintainedContextWindowTokensWithNullClearsPersistedValue() {
    val directory = temporaryFolder.newFolder("chat-store-context-window-clear")
    val store = ChatSessionLocalStore(directory)
    val sessionId = store.loadState().activeSession.sessionId
    store.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = 32768,
    )

    store.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = null,
    )

    assertNull(ChatSessionLocalStore(directory).loadMaintainedContextWindowTokens(sessionId))
  }

  @Test
  fun replaceMaintainedContextWindowTokensIgnoresUnknownSessionsAndNonPositiveValues() {
    val directory = temporaryFolder.newFolder("chat-store-context-window-invalid")
    val store = ChatSessionLocalStore(directory)
    val sessionId = store.loadState().activeSession.sessionId

    store.replaceMaintainedContextWindowTokens(
      sessionId = "session-does-not-exist",
      contextWindowTokens = 8192,
    )
    store.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = 0,
    )

    assertNull(store.loadMaintainedContextWindowTokens(sessionId))
  }

  @Test
  fun copyAndBranchCarryMaintainedContextWindowTokensWhileDeleteCleansSourceState() {
    val directory = temporaryFolder.newFolder("chat-store-context-window-copy-branch")
    val store = ChatSessionLocalStore(directory)
    val sourceSessionId = store.loadState().activeSession.sessionId
    store.appendUserMessage(sourceSessionId, "Keep the replay budget stable.")
    val branchMessageId = requireNotNull(store.loadSession(sourceSessionId))
      .messages
      .last()
      .messageId
    store.replaceMaintainedContextWindowTokens(
      sessionId = sourceSessionId,
      contextWindowTokens = 65536,
    )

    val copiedState = store.copySession(sourceSessionId)
    val copiedSessionId = copiedState.activeSession.sessionId
    val branchedState = store.branchSessionFromMessage(sourceSessionId, branchMessageId)
    val branchedSessionId = branchedState.activeSession.sessionId

    assertEquals(65536, store.loadMaintainedContextWindowTokens(copiedSessionId))
    assertEquals(65536, store.loadMaintainedContextWindowTokens(branchedSessionId))

    store.deleteSession(sourceSessionId)
    val restoredStore = ChatSessionLocalStore(directory)

    assertNull(restoredStore.loadMaintainedContextWindowTokens(sourceSessionId))
    assertEquals(65536, restoredStore.loadMaintainedContextWindowTokens(copiedSessionId))
    assertEquals(65536, restoredStore.loadMaintainedContextWindowTokens(branchedSessionId))
  }
}
