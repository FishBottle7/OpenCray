package com.opencray.app

import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreTodoPersistenceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadTodosRestoresPersistedEntriesAcrossStoreRecreation() {
    val directory = temporaryFolder.newFolder("chat-store-todos-persist")
    val firstStore = ChatSessionLocalStore(directory)
    val sessionId = firstStore.loadState().activeSession.sessionId

    firstStore.replaceTodos(
      sessionId = sessionId,
      todos = listOf(
        AgentTodoEntry(
          content = "Review chat composer layout",
          status = AgentTodoStatus.PENDING,
        ),
        AgentTodoEntry(
          content = "Ship todo persistence",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Shipping todo persistence",
        ),
      ),
    )

    val restoredStore = ChatSessionLocalStore(directory)
    val restoredTodos = restoredStore.loadTodos(sessionId)

    assertEquals(2, restoredTodos.size)
    assertEquals("Review chat composer layout", restoredTodos[0].content)
    assertEquals(AgentTodoStatus.PENDING, restoredTodos[0].status)
    assertEquals("Ship todo persistence", restoredTodos[1].content)
    assertEquals(AgentTodoStatus.IN_PROGRESS, restoredTodos[1].status)
    assertEquals("Shipping todo persistence", restoredTodos[1].activeForm)
  }

  @Test
  fun completedTodosArchiveAndRestoreAcrossStoreRecreation() {
    val directory = temporaryFolder.newFolder("chat-store-todos-archive")
    val firstStore = ChatSessionLocalStore(directory, nowEpochMs = { 1_700_000_000_000L })
    val sessionId = firstStore.loadState().activeSession.sessionId

    firstStore.replaceTodos(
      sessionId = sessionId,
      todos = listOf(
        AgentTodoEntry(
          content = "Review chat composer layout",
          status = AgentTodoStatus.COMPLETED,
        ),
        AgentTodoEntry(
          content = "Ship todo persistence",
          status = AgentTodoStatus.COMPLETED,
        ),
      ),
    )

    val restoredStore = ChatSessionLocalStore(directory, nowEpochMs = { 1_700_000_000_500L })
    val restoredSnapshot = restoredStore.loadTodoSnapshot(sessionId)
    val restoredPresentation = restoredStore.loadTodoPresentation(
      sessionId = sessionId,
      archivedVisibilityDurationMs = 4_000L,
    )

    assertTrue(restoredStore.loadTodos(sessionId).isEmpty())
    assertEquals(ChatSessionTodoState.ARCHIVED_COMPLETED, restoredSnapshot.state)
    assertEquals(2, restoredSnapshot.todos.size)
    assertEquals(1_700_000_000_000L, restoredSnapshot.completedAtEpochMs)
    assertEquals(ChatSessionTodoPresentationState.ARCHIVED_COMPLETED, restoredPresentation.state)
    assertEquals(3_500L, restoredPresentation.hideDelayMs)
    assertEquals(2, restoredPresentation.todos.size)
  }

  @Test
  fun replaceTodosRejectsInvalidPlanAndPreservesPreviouslyPersistedTodos() {
    val directory = temporaryFolder.newFolder("chat-store-todos-invalid-plan")
    val store = ChatSessionLocalStore(directory)
    val sessionId = store.loadState().activeSession.sessionId
    store.replaceTodos(
      sessionId = sessionId,
      todos = listOf(
        AgentTodoEntry(
          content = "Inspect runtime continuation",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Inspecting runtime continuation",
        ),
        AgentTodoEntry(
          content = "Write follow-up tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )

    val error = runCatching {
      store.replaceTodos(
        sessionId = sessionId,
        todos = listOf(
          AgentTodoEntry(
            content = "Inspect runtime continuation",
            status = AgentTodoStatus.IN_PROGRESS,
            activeForm = "Inspecting runtime continuation",
          ),
          AgentTodoEntry(
            content = "Write follow-up tests",
            status = AgentTodoStatus.IN_PROGRESS,
            activeForm = "Writing follow-up tests",
          ),
        ),
      )
    }.exceptionOrNull()
    val restoredTodos = ChatSessionLocalStore(directory).loadTodos(sessionId)

    assertNotNull(error)
    assertTrue(error?.message.orEmpty().contains("at most one in_progress"))
    assertEquals(2, restoredTodos.size)
    assertEquals(AgentTodoStatus.IN_PROGRESS, restoredTodos[0].status)
    assertEquals("Inspecting runtime continuation", restoredTodos[0].activeForm)
    assertEquals(AgentTodoStatus.PENDING, restoredTodos[1].status)
    assertEquals(null, restoredTodos[1].activeForm)
  }

  @Test
  fun replaceTodosRejectsDuplicateContentAndPreservesPreviouslyPersistedTodos() {
    val directory = temporaryFolder.newFolder("chat-store-todos-duplicate-plan")
    val store = ChatSessionLocalStore(directory)
    val sessionId = store.loadState().activeSession.sessionId
    store.replaceTodos(
      sessionId = sessionId,
      todos = listOf(
        AgentTodoEntry(
          content = "Keep current plan",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Keeping current plan",
        ),
        AgentTodoEntry(
          content = "Write follow-up tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )

    val error = runCatching {
      store.replaceTodos(
        sessionId = sessionId,
        todos = listOf(
          AgentTodoEntry(
            content = "Keep current plan",
            status = AgentTodoStatus.IN_PROGRESS,
            activeForm = "Keeping current plan",
          ),
          AgentTodoEntry(
            content = "Keep current plan",
            status = AgentTodoStatus.PENDING,
          ),
        ),
      )
    }.exceptionOrNull()
    val restoredTodos = ChatSessionLocalStore(directory).loadTodos(sessionId)

    assertNotNull(error)
    assertTrue(error?.message.orEmpty().contains("duplicates todo 1 content"))
    assertEquals(2, restoredTodos.size)
    assertEquals("Keep current plan", restoredTodos[0].content)
    assertEquals("Write follow-up tests", restoredTodos[1].content)
  }

  @Test
  fun copyBranchAndDeleteSessionCarryAndCleanTodoState() {
    val directory = temporaryFolder.newFolder("chat-store-todos-copy-branch")
    val store = ChatSessionLocalStore(directory)
    val sourceSessionId = store.loadState().activeSession.sessionId
    store.appendUserMessage(sourceSessionId, "Track todo persistence")
    val branchMessageId = requireNotNull(store.loadSession(sourceSessionId))
      .messages
      .last()
      .messageId
    val sourceTodos = listOf(
      AgentTodoEntry(
        content = "Persist todo state",
        status = AgentTodoStatus.IN_PROGRESS,
        activeForm = "Persisting todo state",
      ),
    )
    store.replaceTodos(sourceSessionId, sourceTodos)

    val copiedState = store.copySession(sourceSessionId)
    val copiedSessionId = copiedState.activeSession.sessionId
    val branchedState = store.branchSessionFromMessage(sourceSessionId, branchMessageId)
    val branchedSessionId = branchedState.activeSession.sessionId

    assertEquals(sourceTodos, store.loadTodos(copiedSessionId))
    assertEquals(sourceTodos, store.loadTodos(branchedSessionId))

    store.deleteSession(sourceSessionId)
    val restoredStore = ChatSessionLocalStore(directory)

    assertTrue(restoredStore.loadTodos(sourceSessionId).isEmpty())
    assertEquals(sourceTodos, restoredStore.loadTodos(copiedSessionId))
    assertEquals(sourceTodos, restoredStore.loadTodos(branchedSessionId))
  }

  @Test
  fun copyBranchAndDeleteSessionCarryAndCleanArchivedTodoState() {
    val directory = temporaryFolder.newFolder("chat-store-todos-archived-copy-branch")
    val store = ChatSessionLocalStore(directory, nowEpochMs = { 1_700_000_000_000L })
    val sourceSessionId = store.loadState().activeSession.sessionId
    store.appendUserMessage(sourceSessionId, "Archive todo state")
    val branchMessageId = requireNotNull(store.loadSession(sourceSessionId))
      .messages
      .last()
      .messageId
    store.replaceTodos(
      sourceSessionId,
      listOf(
        AgentTodoEntry(
          content = "Persist archived todo state",
          status = AgentTodoStatus.COMPLETED,
        ),
      ),
    )

    val copiedState = store.copySession(sourceSessionId)
    val copiedSessionId = copiedState.activeSession.sessionId
    val branchedState = store.branchSessionFromMessage(sourceSessionId, branchMessageId)
    val branchedSessionId = branchedState.activeSession.sessionId

    assertEquals(ChatSessionTodoState.ARCHIVED_COMPLETED, store.loadTodoSnapshot(copiedSessionId).state)
    assertEquals(ChatSessionTodoState.ARCHIVED_COMPLETED, store.loadTodoSnapshot(branchedSessionId).state)

    store.deleteSession(sourceSessionId)
    val restoredStore = ChatSessionLocalStore(directory)

    assertEquals(ChatSessionTodoState.EMPTY, restoredStore.loadTodoSnapshot(sourceSessionId).state)
    assertEquals(ChatSessionTodoState.ARCHIVED_COMPLETED, restoredStore.loadTodoSnapshot(copiedSessionId).state)
    assertEquals(
      ChatSessionTodoState.ARCHIVED_COMPLETED,
      restoredStore.loadTodoSnapshot(branchedSessionId).state,
    )
  }
}
