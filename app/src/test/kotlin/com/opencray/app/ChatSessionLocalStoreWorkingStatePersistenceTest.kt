package com.opencray.app

import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreWorkingStatePersistenceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun loadWorkingStateRestoresPersistedStateAcrossStoreRecreation() {
    val directory = temporaryFolder.newFolder("chat-store-working-state-persist")
    val firstStore = ChatSessionLocalStore(directory)
    val sessionId = firstStore.loadState().activeSession.sessionId
    val workingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Ship working-state persistence.",
        currentSubgoal = "Wire the session store.",
        status = "in_progress",
      ),
      findings = listOf(
        WorkingStateEntry(
          text = "Chat session extensions already persist per-session todo state.",
          sourceType = "code_inspection",
        ),
      ),
      nextActions = listOf(
        WorkingStateEntry(text = "Add focused persistence tests."),
      ),
    )

    firstStore.replaceWorkingState(
      sessionId = sessionId,
      workingState = workingState,
    )

    val restoredStore = ChatSessionLocalStore(directory)

    assertEquals(workingState, restoredStore.loadWorkingState(sessionId))
  }

  @Test
  fun replaceWorkingStateWithEmptyStateClearsPersistedSnapshot() {
    val directory = temporaryFolder.newFolder("chat-store-working-state-clear")
    val store = ChatSessionLocalStore(directory)
    val sessionId = store.loadState().activeSession.sessionId
    store.replaceWorkingState(
      sessionId = sessionId,
      workingState = WorkingState(
        objective = WorkingStateObjective(primaryGoal = "Keep short-term state."),
      ),
    )

    store.replaceWorkingState(
      sessionId = sessionId,
      workingState = WorkingState(),
    )

    assertTrue(ChatSessionLocalStore(directory).loadWorkingState(sessionId).isEmpty)
  }

  @Test
  fun copyCarriesWorkingStateWhileBranchClearsItAndDeleteCleansSourceState() {
    val directory = temporaryFolder.newFolder("chat-store-working-state-copy-branch")
    val store = ChatSessionLocalStore(directory)
    val sourceSessionId = store.loadState().activeSession.sessionId
    store.appendUserMessage(sourceSessionId, "Track working-state persistence")
    val branchMessageId = requireNotNull(store.loadSession(sourceSessionId))
      .messages
      .last()
      .messageId
    val workingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Persist working state.",
        currentSubgoal = "Verify copy/branch semantics.",
        status = "in_progress",
      ),
      recentActions = listOf(
        WorkingStateEntry(
          text = "Edit ChatSessionLocalStore.kt",
          sourceType = "workspace_mutation",
        ),
      ),
    )
    store.replaceWorkingState(
      sessionId = sourceSessionId,
      workingState = workingState,
    )

    val copiedState = store.copySession(sourceSessionId)
    val copiedSessionId = copiedState.activeSession.sessionId
    val branchedState = store.branchSessionFromMessage(sourceSessionId, branchMessageId)
    val branchedSessionId = branchedState.activeSession.sessionId

    assertEquals(workingState, store.loadWorkingState(copiedSessionId))
    assertTrue(store.loadWorkingState(branchedSessionId).isEmpty)

    store.deleteSession(sourceSessionId)
    val restoredStore = ChatSessionLocalStore(directory)

    assertTrue(restoredStore.loadWorkingState(sourceSessionId).isEmpty)
    assertEquals(workingState, restoredStore.loadWorkingState(copiedSessionId))
    assertTrue(restoredStore.loadWorkingState(branchedSessionId).isEmpty)
  }
}
