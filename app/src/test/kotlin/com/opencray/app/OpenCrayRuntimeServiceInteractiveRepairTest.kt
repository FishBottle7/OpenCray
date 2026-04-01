package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayRuntimeServiceInteractiveRepairTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun resumeInterruptedRunsForRuntimeServiceHostResumesOnlySessionsWithActiveRuns() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val recoverableSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    val completedSessionId = chatSessionStore.copySession(recoverableSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)

    val recoverableRun = AgentRunSnapshot(
      sessionId = recoverableSessionId,
      runId = "run-recoverable",
      taskId = "task-recoverable",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
    )
    val completedRun = AgentRunSnapshot(
      sessionId = completedSessionId,
      runId = "run-completed",
      taskId = "task-completed",
      acceptedAtEpochMs = 2_000L,
      updatedAtEpochMs = 2_100L,
      lifecycleState = QueueTaskLifecycleState.COMPLETED,
      taskState = AgentTaskState.COMPLETED,
    )
    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val recoverableSession = RecordingRuntimeSessionAccess(
      recoverableSessionId,
      runs = listOf(recoverableRun),
    )
    val completedSession = RecordingRuntimeSessionAccess(
      completedSessionId,
      runs = listOf(completedRun),
    )
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          recoverableSessionId to recoverableSession,
          completedSessionId to completedSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRunsForRuntimeServiceHost(chatSessionStore, runtimeAccess)

    assertEquals(
      setOf(activeSessionId, recoverableSessionId, completedSessionId),
      result.scannedSessionIds.toSet(),
    )
    assertEquals(listOf(recoverableSessionId), result.resumedSessionIds)
    assertEquals(listOf(recoverableSessionId), result.repairedSessionIds)
    assertEquals(0, activeSession.resumeCallCount)
    assertEquals(1, recoverableSession.resumeCallCount)
    assertEquals(0, completedSession.resumeCallCount)
    assertEquals(listOf(recoverableSessionId), repairedSessions)
  }

  @Test
  fun resumeInterruptedRunsForRuntimeServiceHostAlsoResumesSessionsWithLiveSubAgents() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-subagent")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val subAgentSessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    val idleSessionId = chatSessionStore.copySession(subAgentSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val subAgentSession = RecordingRuntimeSessionAccess(
      subAgentSessionId,
      runs = emptyList(),
      subAgentHandles = listOf(backgroundSubAgentHandle(agentId = "child-live")),
    )
    val idleSession = RecordingRuntimeSessionAccess(idleSessionId, runs = emptyList())
    val repairedSessions = mutableListOf<String>()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          subAgentSessionId to subAgentSession,
          idleSessionId to idleSession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { sessionId, _ ->
          repairedSessions += sessionId
        },
      ),
    )

    val result = resumeInterruptedRunsForRuntimeServiceHost(chatSessionStore, runtimeAccess)

    assertEquals(
      setOf(activeSessionId, subAgentSessionId, idleSessionId),
      result.scannedSessionIds.toSet(),
    )
    assertEquals(listOf(subAgentSessionId), result.resumedSessionIds)
    assertEquals(emptyList<String>(), result.repairedSessionIds)
    assertEquals(0, activeSession.resumeCallCount)
    assertEquals(1, subAgentSession.resumeCallCount)
    assertEquals(0, idleSession.resumeCallCount)
    assertEquals(emptyList<String>(), repairedSessions)
  }

  @Test
  fun resumeInterruptedRunsForRuntimeServiceHostSubmitsRecoveryTaskForQueuedDetachedSubAgent() {
    val root = temporaryFolder.newFolder("runtime-service-interactive-repair-subagent-recovery")
    val chatSessionStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val recoverySessionId = chatSessionStore.copySession(activeSessionId).activeSession.sessionId
    chatSessionStore.selectSession(activeSessionId)

    val activeSession = RecordingRuntimeSessionAccess(activeSessionId, runs = emptyList())
    val recoveryHandle = queuedSubAgentHandle(agentId = "child-resume")
    val recoverySession = RecordingRuntimeSessionAccess(
      recoverySessionId,
      runs = emptyList(),
      subAgentHandles = listOf(recoveryHandle),
    )
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      hostAccess = RecordingRuntimeHostAccess(
        sessions = mapOf(
          activeSessionId to activeSession,
          recoverySessionId to recoverySession,
        ),
      ),
      transcriptMessagesProvider = { emptyList<RuntimeConversationMessage>() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )

    resumeInterruptedRunsForRuntimeServiceHost(chatSessionStore, runtimeAccess)

    assertEquals(1, recoverySession.submittedTasks.size)
    val recoveryTask = recoverySession.submittedTasks.single()
    assertEquals(AgentTaskType.TOOL_CALL, recoveryTask.type)
    assertTrue(recoveryTask.input.contains("wait_agent"))
    assertTrue(recoveryTask.input.contains("child-resume"))
    assertEquals(
      RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
      recoveryTask.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals(1, recoverySession.ensureProcessingCallCount)
  }

  private class RecordingRuntimeHostAccess(
    private val sessions: Map<String, RecordingRuntimeSessionAccess>,
  ) : OpenCrayRuntimeHostAccess {
    private val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    private val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    private val supplementStores = linkedMapOf<String, SessionSupplementStore>()

    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
      HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = sessions.size,
      activeRunCount = sessions.values.sumOf { session ->
        session.runs.count(AgentRunSnapshot::isActive)
      },
      activeSessionIds = sessions.values
        .filter { session -> session.runs.any(AgentRunSnapshot::isActive) }
        .map(RecordingRuntimeSessionAccess::sessionId),
    )

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess =
      requireNotNull(sessions[sessionId]) { "Session '$sessionId' is unavailable." }

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      runEventJournalStoreFactory.forChatSession(sessionId)

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      promptCheckpointStoreFactory.forChatSession(sessionId)

    override fun supplementStore(sessionId: String): SessionSupplementStore =
      supplementStores.getOrPut(sessionId) { InMemorySessionSupplementStore() }

    override fun markApprovalApproved(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun markApprovalRejected(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun clearApproval(sessionId: String, taskId: String) = Unit

    override fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) = Unit

    override fun isApprovalApproved(sessionId: String, taskId: String): Boolean = false

    override fun isApprovalRejected(sessionId: String, taskId: String): Boolean = false
  }

  private class RecordingRuntimeSessionAccess(
    override val sessionId: String,
    val runs: List<AgentRunSnapshot>,
    private val subAgentHandles: List<SubAgentHandleState> = emptyList(),
  ) : OpenCrayRuntimeSessionAccess {
    var resumeCallCount: Int = 0
      private set
    val submittedTasks = mutableListOf<AgentTask>()
    var ensureProcessingCallCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submittedTasks += task
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?: "run-${task.id}"
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
    }

    override fun ensureProcessing() {
      ensureProcessingCallCount += 1
    }

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? =
      runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumeCallCount += 1
      return SessionLifecycleState.RUNNING
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.IDLE,
      updatedAtEpochMs = 0L,
      tasks = (
        runs.mapIndexed { index, run ->
          SessionQueueTaskSnapshot(
            enqueueOrder = index.toLong(),
            task = AgentTask(
              id = run.taskId,
              type = com.opencray.core.contracts.AgentTaskType.PROMPT,
              input = "Test",
              state = run.taskState ?: AgentTaskState.QUEUED,
              policyDecision = PolicyDecision(
                outcome = PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = run.acceptedAtEpochMs,
              updatedAtEpochMs = run.updatedAtEpochMs,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to run.runId,
              ),
            ),
            lifecycleState = run.lifecycleState ?: QueueTaskLifecycleState.QUEUED,
          )
        } +
          submittedTasks.mapIndexed { index, task ->
            SessionQueueTaskSnapshot(
              enqueueOrder = (runs.size + index).toLong(),
              task = task,
              lifecycleState = QueueTaskLifecycleState.QUEUED,
            )
          }
        ),
    )

    override fun hasPendingWork(): Boolean = runs.any { run -> !run.isTerminal }

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      emptyList()

    override fun hasLiveManagedProcesses(): Boolean = runs.any(AgentRunSnapshot::hasLiveManagedProcesses)

    override fun terminateRunningManagedProcesses():
      List<com.opencray.runtime.process.ManagedProcessSnapshot> = emptyList()

    override fun listSubAgentHandles(): List<SubAgentHandleState> = subAgentHandles
  }

  private fun backgroundSubAgentHandle(agentId: String): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundRunning(),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
  )

  private fun queuedSubAgentHandle(agentId: String): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundQueued(),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadEntries = records.isNotEmpty()
      records.clear()
      return hadEntries
    }
  }
}
