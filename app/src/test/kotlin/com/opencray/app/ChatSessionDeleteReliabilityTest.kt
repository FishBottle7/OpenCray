package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.InMemorySessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.persistence.model.ChatTranscriptRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionDeleteReliabilityTest : RuntimeServiceHostTestBase() {
  @Test
  fun appendSubmittedTurnRejectsMissingSessionWithoutPollutingActiveSession() {
    val chatRoot = temporaryFolder.newFolder("fail-closed-append-store")
    val chatSessionStore = ChatSessionLocalStore(chatRoot.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(activeSessionId, "keep me")
    val messagesBefore =
      requireNotNull(chatSessionStore.loadSession(activeSessionId)).messages.size
    val sessionsBefore = chatSessionStore.loadState().sessions.size

    val thrown = runCatching {
      chatSessionStore.appendSubmittedTurn(
        sessionId = "session-deleted-elsewhere",
        userText = "late submit",
        assistantMessageId =
          chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT),
        assistantPlaceholderText = "Thinking",
      )
    }

    assertTrue(thrown.isFailure)
    assertEquals(activeSessionId, chatSessionStore.loadState().activeSession.sessionId)
    assertEquals(
      messagesBefore,
      requireNotNull(chatSessionStore.loadSession(activeSessionId)).messages.size,
    )
    assertEquals(sessionsBefore, chatSessionStore.loadState().sessions.size)
  }

  @Test
  fun deletedSessionTombstonesDropJournalRunRecordAndSnapshotWrites() {
    val sessionId = "session-tombstoned"
    val tombstones = inMemoryChatSessionTombstoneStore()
    val journalDelegate = inMemoryRunEventJournalStoreFactory().forChatSession(sessionId)
    val journal = TombstoneGuardedRunEventJournalStore(journalDelegate, sessionId, tombstones)
    val runRecordDelegate = RecordingAgentRunRecordStore()
    val runRecord = TombstoneGuardedAgentRunRecordStore(runRecordDelegate, sessionId, tombstones)
    val snapshotDelegate = InMemorySessionQueueSnapshotStore()
    val snapshotStore = TombstoneGuardedQueueSnapshotStore(snapshotDelegate, sessionId, tombstones)

    val event = OpenCrayLifecycleEventForTest(runId = "run-1", taskId = "task-1")
    val record = PersistedAgentRunRecord(
      runId = "run-1",
      taskId = "task-1",
      acceptedAtEpochMs = 0L,
    )
    val snapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
    )

    journal.append(event)
    runRecord.upsert(record)
    snapshotStore.save(snapshot)
    assertTrue(journalDelegate.hasEntries())
    assertEquals(1, runRecordDelegate.records.size)
    assertEquals(snapshot, snapshotDelegate.load())

    tombstones.tombstone(sessionId)
    journalDelegate.clear()
    runRecordDelegate.clear()

    journal.append(event)
    journal.appendRecovery(
      runId = "run-3",
      taskId = "task-3",
      emittedAtEpochMs = 0L,
      metadata = emptyMap(),
    )
    runRecord.upsert(record.copy(runId = "run-4", taskId = "task-4"))
    snapshotStore.save(snapshot.copy(updatedAtEpochMs = 2_000L))

    assertFalse(journalDelegate.hasEntries())
    assertTrue(runRecordDelegate.list().isEmpty())
    assertEquals(snapshot, snapshotDelegate.load())

    assertFalse(tombstones.isTombstoned("session-live"))
  }

  @Test
  fun deleteChatSessionCascadesScheduledSpecsAndBlocksLateSubmitIntoCurrentSession() {
    val chatRoot = temporaryFolder.newFolder("delete-cascade-store")
    val chatSessionStore = ChatSessionLocalStore(chatRoot.resolve("chat-session"))
    val sessionAId = chatSessionStore.loadState().activeSession.sessionId
    chatSessionStore.appendUserMessage(sessionAId, "session A content")
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val specStoreFactory = inMemoryScheduledTaskSpecStoreFactory()
    val specStore = specStoreFactory.create()
    specStore.upsert(
      ScheduledTaskSpec(
        scheduleId = "schedule-a",
        sessionId = sessionAId,
        title = "Nightly",
        enabled = true,
        trigger = ScheduledTrigger.At(atEpochMs = 0L),
        payload = ScheduledTaskPayload(prompt = "scheduled hello"),
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
      ),
    )
    val cleanupDependencies = ChatDeletedSessionCleanupDependencies(
      tombstoneStore = inMemoryChatSessionTombstoneStore(),
      scheduledTaskSpecStore = specStore,
    )
    val access = ServiceOwnedChatSessionMutationAccess(
      chatSessionStore = chatSessionStore,
      runtimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      chatUnreadMessageState = ChatUnreadMessageState(),
      deletedSessionCleanup = cleanupDependencies,
    )

    assertTrue(access.deleteChatSession(sessionAId))

    val currentSessionId = chatSessionStore.loadState().activeSession.sessionId
    assertTrue(currentSessionId != sessionAId)
    assertTrue(specStore.list().isEmpty())
    assertTrue(cleanupDependencies.tombstoneStore.isTombstoned(sessionAId))
    assertFalse(cleanupDependencies.tombstoneStore.isTombstoned(currentSessionId))

    val thrown = runCatching {
      chatSessionStore.appendSubmittedTurn(
        sessionId = sessionAId,
        userText = "late submit after delete",
        assistantMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT),
        assistantPlaceholderText = "Thinking",
      )
    }
    assertTrue(thrown.isFailure)
    assertEquals(
      setOf("system"),
      requireNotNull(chatSessionStore.loadSession(currentSessionId))
        .messages.map { message -> message.role.name.lowercase() }.toSet(),
    )
  }

  @Test
  fun recoveryCandidateSessionIdsOnlyAcceptsExistingChatWorkspaceSessions() {
    val chatRoot = temporaryFolder.newFolder("recovery-filter-chat")
    val runtimeRoot = temporaryFolder.newFolder("recovery-filter-runtime")
    val chatSessionStore = ChatSessionLocalStore(chatRoot)
    val keptSessionId = chatSessionStore.loadState().activeSession.sessionId
    listOf("ghost-session").forEach { sessionId ->
      File(
        runtimeRoot,
        FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId),
      ).mkdirs()
    }
    val snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)

    val candidates = recoveryCandidateSessionIds(
      chatSessionStore = chatSessionStore,
      snapshotStoreFactory = snapshotStoreFactory,
      promptCheckpointStoreFactory = null,
      subAgentHandleStoreFactory = null,
    )

    assertEquals(listOf(keptSessionId), candidates)
  }

  @Test
  fun bootstrapRecoverySkipsDeletedSessionsMissingFromChatWorkspace() {
    val chatRoot = temporaryFolder.newFolder("bootstrap-filter-chat")
    val runtimeRoot = temporaryFolder.newFolder("bootstrap-filter-runtime")
    val chatSessionStore = ChatSessionLocalStore(chatRoot)
    val keptSessionId = chatSessionStore.loadState().activeSession.sessionId
    listOf("ghost-session").forEach { sessionId ->
      File(
        runtimeRoot,
        FileBackedAgentQueueSnapshotStoreFactory.encodeSessionId(sessionId),
      ).mkdirs()
    }
    val requestedSessionIds = mutableListOf<String>()
    val directoryAccess = object : RuntimeSessionDirectoryAccess {
      override fun session(sessionId: String): OpenCrayRuntimeSessionAccess {
        requestedSessionIds += sessionId
        return UnavailableRuntimeSessionAccess(sessionId)
      }

      override fun releaseSession(sessionId: String) = Unit

      override fun releaseIdleSessions() = Unit
    }

    val result = bootstrapRuntimeServiceSessions(
      chatSessionStore = chatSessionStore,
      runtimeSessionDirectoryAccess = directoryAccess,
      runtimeReplayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
    )

    assertEquals(listOf(keptSessionId), result.scannedSessionIds)
    assertEquals(listOf(keptSessionId), requestedSessionIds)
    assertTrue(result.resumedSessionIds.isEmpty())
  }

  private class RecordingAgentRunRecordStore : AgentRunRecordStore {
    val records = mutableListOf<PersistedAgentRunRecord>()

    override fun list(): List<PersistedAgentRunRecord> = records.toList()

    override fun upsert(record: PersistedAgentRunRecord) {
      records += record
    }

    override fun clear() {
      records.clear()
    }
  }

  private class UnavailableRuntimeSessionAccess(
    override val sessionId: String,
  ) : OpenCrayRuntimeSessionAccess {
    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission = error("unused in test")

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = emptyList()

    override fun findRun(runId: String): AgentRunSnapshot? = null

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = null

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 0L,
    )

    override fun hasPendingWork(): Boolean = false

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      emptyList()

    override fun hasLiveManagedProcesses(): Boolean = false

    override fun terminateRunningManagedProcesses():
      List<com.opencray.runtime.process.ManagedProcessSnapshot> = emptyList()
  }
}

private fun OpenCrayLifecycleEventForTest(
  runId: String,
  taskId: String,
): com.opencray.runtime.OpenCrayLifecycleEvent =
  com.opencray.runtime.OpenCrayLifecycleEvent(
    runId = runId,
    taskId = taskId,
    phase = com.opencray.runtime.OpenCrayRunLifecyclePhase.START,
    emittedAtEpochMs = 1_000L,
  )
