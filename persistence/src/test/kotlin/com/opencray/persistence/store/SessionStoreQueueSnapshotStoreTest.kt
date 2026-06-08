package com.opencray.persistence.store

import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.store.file.JsonFileSessionStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreQueueSnapshotStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun malformedSessionRecordFallsBackToFreshSnapshotOnLoadAndSave() {
    val directory = temporaryFolder.newFolder("queue-snapshot-store")
    File(directory, "session.json").writeText(
      """{"sessionId":"broken","agentId":"agent-1"}garbage""",
      Charsets.UTF_8,
    )

    val store = SessionStoreQueueSnapshotStore(JsonFileSessionStore(directory))

    assertNull(store.load())

    val snapshot = queueSnapshot()

    store.save(snapshot)

    assertEquals(snapshot, store.load())
  }

  @Test
  fun saveMergesQueueSnapshotIntoExistingSessionState() {
    val directory = temporaryFolder.newFolder("queue-snapshot-store-merge")
    val sessionStore = JsonFileSessionStore(directory)
    sessionStore.save(
      SessionRecord(
        sessionId = "session-1",
        agentId = "agent-1",
        state = mapOf("custom_state" to "kept"),
        recordVersion = 7L,
        createdAtEpochMs = 500L,
        updatedAtEpochMs = 1_500L,
      ),
    )
    val store = SessionStoreQueueSnapshotStore(sessionStore)

    val snapshot = queueSnapshot()
    store.save(snapshot)

    val saved = sessionStore.load()
    assertEquals(snapshot, store.load())
    assertEquals("kept", saved?.state?.get("custom_state"))
    assertEquals("idle", saved?.state?.get(SessionStoreQueueSnapshotStore.StateKeys.QUEUE_STATE))
    assertEquals(8L, saved?.recordVersion)
    assertEquals(500L, saved?.createdAtEpochMs)
  }

  private fun queueSnapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
    sessionId = "session-1",
    agentId = "agent-1",
    lifecycleState = SessionLifecycleState.IDLE,
    nextEnqueueOrder = 2L,
    tasks = listOf(
      SessionQueueTaskSnapshot(
        enqueueOrder = 1L,
        task = com.opencray.core.contracts.AgentTask(
          id = "task-1",
          type = com.opencray.core.contracts.AgentTaskType.PROMPT,
          input = "hello",
          policyDecision = com.opencray.core.contracts.PolicyDecision(
            outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
            reasonCode = "TEST_ALLOW",
          ),
          createdAtEpochMs = 1_000L,
        ),
      ),
    ),
    updatedAtEpochMs = 2_000L,
  )
}
