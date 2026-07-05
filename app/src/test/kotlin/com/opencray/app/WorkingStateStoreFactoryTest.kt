package com.opencray.app

import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkingStateStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreRestoresStateAcrossFactoryRecreation() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-working-state-root")
    val sessionId = "session-runtime-working-state"
    val workingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Persist runtime working state.",
        currentSubgoal = "Restore after runtime owner recreation.",
        status = "in_progress",
      ),
      findings = listOf(
        WorkingStateEntry(
          text = "Working state must survive detached runtime owner replacement.",
          sourceType = "runtime_store",
        ),
      ),
    )

    FileBackedWorkingStateStoreFactory(runtimeRoot)
      .forChatSession(sessionId)
      .replace(workingState)

    val restored = FileBackedWorkingStateStoreFactory(runtimeRoot)
      .forChatSession(sessionId)
      .snapshot()

    assertEquals(workingState, restored)
  }

  @Test
  fun fileBackedStoreClearsPersistedStateWhenReplacedWithEmptyState() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-working-state-clear-root")
    val sessionId = "session-runtime-working-state-clear"
    val factory = FileBackedWorkingStateStoreFactory(runtimeRoot)
    factory.forChatSession(sessionId).replace(
      WorkingState(
        objective = WorkingStateObjective(
          primaryGoal = "Clear runtime working state.",
          status = "done",
        ),
      ),
    )

    factory.forChatSession(sessionId).replace(WorkingState())

    assertTrue(
      FileBackedWorkingStateStoreFactory(runtimeRoot)
        .forChatSession(sessionId)
        .snapshot()
        .isEmpty,
    )
  }
}
