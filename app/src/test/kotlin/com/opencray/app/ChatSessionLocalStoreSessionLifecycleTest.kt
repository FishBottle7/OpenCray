package com.opencray.app

import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreSessionLifecycleTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createSessionReusesExistingEmptySessionAndRefreshesCreatedAt() {
    var now = 1_000L
    val store = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-reuse-empty"),
      nowEpochMs = { now },
    )
    val originalSessionId = store.loadState().activeSession.sessionId

    now = 2_000L
    val createdState = store.createSession()
    val reusedSession = requireNotNull(store.loadSession(originalSessionId))

    assertEquals(originalSessionId, createdState.activeSession.sessionId)
    assertEquals(1, store.loadState().sessions.size)
    assertEquals(2_000L, reusedSession.createdAtEpochMs)
    assertEquals(2_000L, reusedSession.updatedAtEpochMs)
    assertTrue(store.isReusableEmptySession(originalSessionId))
  }

  @Test
  fun createSessionDoesNotReuseNonEmptySession() {
    var now = 1_000L
    val store = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-non-empty"),
      nowEpochMs = { now },
    )
    val originalSessionId = store.loadState().activeSession.sessionId
    store.appendUserMessage(originalSessionId, "Keep this transcript")

    now = 2_000L
    val createdState = store.createSession()

    assertTrue(createdState.activeSession.sessionId != originalSessionId)
    assertEquals(2, store.loadState().sessions.size)
    assertTrue(!store.isReusableEmptySession(originalSessionId))
  }

  @Test
  fun createSessionDoesNotReuseSessionWithPersistedTodoState() {
    var now = 1_000L
    val store = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-empty-with-todo"),
      nowEpochMs = { now },
    )
    val originalSessionId = store.loadState().activeSession.sessionId
    store.replaceTodos(
      sessionId = originalSessionId,
      todos = listOf(
        AgentTodoEntry(
          content = "Do not treat this as disposable",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )

    now = 2_000L
    val createdState = store.createSession()

    assertTrue(createdState.activeSession.sessionId != originalSessionId)
    assertEquals(2, store.loadState().sessions.size)
    assertTrue(!store.isReusableEmptySession(originalSessionId))
  }
}
