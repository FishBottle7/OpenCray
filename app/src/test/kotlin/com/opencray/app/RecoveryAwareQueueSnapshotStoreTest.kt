package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.METADATA_PREVIOUS_LIFECYCLE_STATE
import com.opencray.core.orchestrator.METADATA_QUEUE_RESTORE_EPOCH_MS
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryAwareQueueSnapshotStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { prettyPrint = false }

  @Test
  fun loadRewritesInterruptedTaskToQueuedWhenGeneralResumeCheckpointExists() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-general-resume")
    val sessionId = "session-general-resume"
    val taskId = "task-general-resume"
    val runId = "run-general-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Resume the interrupted run.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    promptCheckpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-general-resume",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_150L,
        updatedAtEpochMs = 1_150L,
        toolName = "Read",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 2,
          toolCallCount = 1,
        ),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.QUEUED, restoredTask.task.state)
    assertEquals("5000", restoredTask.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS])
    assertEquals("running", restoredTask.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE])
    assertEquals("durable_general_resume_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(5_000L, restoredTask.task.updatedAtEpochMs)
    assertNull(restoredTask.lastErrorCode)
    assertNull(restoredTask.lastErrorMessage)
  }

  @Test
  fun loadSynthesizesGeneralResumeCheckpointFromJournalTailWhenStoreMissing() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-synthetic-general-resume")
    val sessionId = "session-synthetic-general-resume"
    val taskId = "task-synthetic-general-resume"
    val runId = "run-synthetic-general-resume"
    val pendingMessageId = "pending-synthetic-general-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
    )

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Continue from journal tail.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runEventJournalStore.append(
      OpenCrayToolResultEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "journal tail checkpoint",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
            state = resumeState,
            json = json,
          ),
        ),
        emittedAtEpochMs = 1_150L,
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()
    val syntheticCheckpoint = promptCheckpointStore.get(taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.QUEUED, restoredTask.task.state)
    assertEquals("durable_general_resume_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertNotNull(syntheticCheckpoint)
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, syntheticCheckpoint?.checkpointKind)
    assertEquals("Read", syntheticCheckpoint?.toolName)
    assertEquals(pendingMessageId, syntheticCheckpoint?.pendingMessageId)
    assertEquals(resumeState, syntheticCheckpoint?.promptResumeState)
  }

  @Test
  fun loadSynthesizesProgressCheckpointFromAssistantJournalTailWhenStoreMissing() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-synthetic-progress-resume")
    val sessionId = "session-synthetic-progress-resume"
    val taskId = "task-synthetic-progress-resume"
    val runId = "run-synthetic-progress-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 0,
    )

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Continue after progress.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runEventJournalStore.append(
      OpenCrayAssistantEvent(
        runId = runId,
        taskId = taskId,
        turn = 0,
        text = "Working through the plan.",
        stage = "commentary",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED,
        ),
        emittedAtEpochMs = 1_150L,
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()
    val syntheticCheckpoint = promptCheckpointStore.get(taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals("durable_commentary_emitted_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(PromptCheckpointKind.COMMENTARY_EMITTED, syntheticCheckpoint?.checkpointKind)
    assertEquals(resumeState, syntheticCheckpoint?.promptResumeState)
  }

  @Test
  fun loadSynthesizesWaitingApprovalCheckpointWhenStoreMissing() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-synthetic-waiting-approval")
    val sessionId = "session-synthetic-waiting-approval"
    val taskId = "task-synthetic-waiting-approval"
    val runId = "run-synthetic-waiting-approval"
    val pendingMessageId = "pending-synthetic-waiting-approval"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Wait for approval.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runEventJournalStore.append(
      OpenCrayToolResultEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.DENIED,
          content = "Approval required.",
          errorCode = "APPROVAL_REQUIRED",
          errorMessage = "Approval is required before Read can run.",
          metadata = approvalMetadata(
            toolName = "Read",
            resumeState = resumeState,
          ),
        ),
        emittedAtEpochMs = 1_150L,
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()
    val syntheticCheckpoint = promptCheckpointStore.get(taskId)

    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.SUSPENDED, restoredTask.task.state)
    assertEquals("approval_waiting_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(PromptCheckpointKind.WAITING_APPROVAL, syntheticCheckpoint?.checkpointKind)
    assertEquals("Read", syntheticCheckpoint?.toolName)
    assertEquals(pendingMessageId, syntheticCheckpoint?.pendingMessageId)
    assertEquals(resumeState, syntheticCheckpoint?.promptResumeState)
  }

  @Test
  fun loadSynthesizesApprovedPendingResumeCheckpointWhenStoreMissing() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-synthetic-approved-resume")
    val sessionId = "session-synthetic-approved-resume"
    val taskId = "task-synthetic-approved-resume"
    val runId = "run-synthetic-approved-resume"
    val pendingMessageId = "pending-synthetic-approved-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Approval was already granted.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordStore.upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        lastResult = approvalRequiredResult(
          taskId = taskId,
          toolName = "Read",
          resumeState = resumeState,
        ),
        lastEvent = OpenCrayApprovalEvent(
          runId = runId,
          taskId = taskId,
          phase = OpenCrayApprovalPhase.APPROVED,
          toolName = "Read",
          text = "Approval granted.",
          emittedAtEpochMs = 1_160L,
        ).toPersistedRecord(),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()
    val syntheticCheckpoint = promptCheckpointStore.get(taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.QUEUED, restoredTask.task.state)
    assertEquals("approval_already_granted_resume_pending", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, syntheticCheckpoint?.checkpointKind)
    assertEquals("Read", syntheticCheckpoint?.toolName)
    assertEquals(pendingMessageId, syntheticCheckpoint?.pendingMessageId)
    assertEquals(resumeState, syntheticCheckpoint?.promptResumeState)
  }

  @Test
  fun loadSynthesizesRejectedPendingResumeCheckpointWhenStoreMissing() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-synthetic-rejected-resume")
    val sessionId = "session-synthetic-rejected-resume"
    val taskId = "task-synthetic-rejected-resume"
    val runId = "run-synthetic-rejected-resume"
    val pendingMessageId = "pending-synthetic-rejected-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Approval was rejected.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordStore.upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        lastResult = approvalRequiredResult(
          taskId = taskId,
          toolName = "Read",
          resumeState = resumeState,
        ),
        lastEvent = OpenCrayApprovalEvent(
          runId = runId,
          taskId = taskId,
          phase = OpenCrayApprovalPhase.REJECTED,
          toolName = "Read",
          text = "Approval rejected.",
          emittedAtEpochMs = 1_160L,
        ).toPersistedRecord(),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()
    val syntheticCheckpoint = promptCheckpointStore.get(taskId)

    assertEquals(QueueTaskLifecycleState.CANCELLED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.CANCELLED, restoredTask.task.state)
    assertEquals(
      "approval_already_rejected_waiting_for_instruction",
      restoredTask.task.metadata[METADATA_RECOVERY_REASON],
    )
    assertEquals(PromptCheckpointKind.REJECTED_PENDING_RESUME, syntheticCheckpoint?.checkpointKind)
    assertEquals("Read", syntheticCheckpoint?.toolName)
    assertEquals(pendingMessageId, syntheticCheckpoint?.pendingMessageId)
    assertEquals(resumeState, syntheticCheckpoint?.promptResumeState)
  }

  @Test
  fun loadSynthesizesGeneralResumeCheckpointFromRunRecordWhenJournalMissing() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-synthetic-from-run-record")
    val sessionId = "session-synthetic-from-run-record"
    val taskId = "task-synthetic-from-run-record"
    val runId = "run-synthetic-from-run-record"
    val pendingMessageId = "pending-synthetic-from-run-record"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 3,
      toolCallCount = 2,
    )

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Continue from run record tail.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordStore.upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        lastEvent = OpenCrayToolResultEvent(
          runId = runId,
          taskId = taskId,
          turn = 2,
          call = AgentToolCall(toolName = "Read"),
          result = AgentToolResult(
            toolName = "Read",
            status = AgentToolResultStatus.SUCCESS,
            content = "run-record checkpoint",
            metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
              state = resumeState,
              json = json,
            ),
          ),
          emittedAtEpochMs = 1_160L,
        ).toPersistedRecord(),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()
    val syntheticCheckpoint = promptCheckpointStore.get(taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.QUEUED, restoredTask.task.state)
    assertEquals("durable_general_resume_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, syntheticCheckpoint?.checkpointKind)
    assertEquals("Read", syntheticCheckpoint?.toolName)
    assertEquals(pendingMessageId, syntheticCheckpoint?.pendingMessageId)
    assertEquals(resumeState, syntheticCheckpoint?.promptResumeState)
  }

  @Test
  fun loadRewritesRejectedResumeCheckpointToCancelledAwaitingDirection() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-rejected-resume")
    val sessionId = "session-rejected-resume"
    val taskId = "task-rejected-resume"
    val runId = "run-rejected-resume"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Reject the blocked action and stop.",
              state = AgentTaskState.SUSPENDED,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            attempt = 1,
            lastErrorCode = "APPROVAL_REQUIRED",
            lastErrorMessage = "Approval is required before Write can run.",
          ),
        ),
      ),
    )
    promptCheckpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-rejected-resume",
        checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
        createdAtEpochMs = 1_150L,
        updatedAtEpochMs = 1_150L,
        toolName = "Write",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 2,
          toolCallCount = 1,
        ),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = { emptyList() },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()

    assertEquals(QueueTaskLifecycleState.CANCELLED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.CANCELLED, restoredTask.task.state)
    assertEquals("5000", restoredTask.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS])
    assertEquals("suspended", restoredTask.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE])
    assertEquals(
      "approval_already_rejected_waiting_for_instruction",
      restoredTask.task.metadata[METADATA_RECOVERY_REASON],
    )
    assertEquals(5_000L, restoredTask.task.updatedAtEpochMs)
    assertEquals("APPROVAL_REQUIRED", restoredTask.lastErrorCode)
    assertEquals("Approval is required before Write can run.", restoredTask.lastErrorMessage)
  }

  @Test
  fun loadRewritesLiveManagedProcessRecoveryToQueuedCheckpointResumeState() {
    val runtimeRoot = temporaryFolder.newFolder("recovery-aware-queue-managed-process-reconnect")
    val sessionId = "session-managed-process-reconnect"
    val taskId = "task-managed-process-reconnect"
    val runId = "run-managed-process-reconnect"
    val delegate = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory(runtimeRoot).forChatSession(sessionId)
    val runEventJournalStore = FileBackedRunEventJournalStoreFactory(runtimeRoot).forChatSession(sessionId)
    val promptCheckpointStore = inMemoryPromptCheckpointStoreFactoryForTest().forChatSession(sessionId)

    delegate.save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Reconnect to the live process.",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    promptCheckpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-general-resume",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_150L,
        updatedAtEpochMs = 1_150L,
        toolName = "ProcessStart",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 2,
          toolCallCount = 1,
        ),
      ),
    )

    val store = RecoveryAwareQueueSnapshotStore(
      sessionId = sessionId,
      delegate = delegate,
      runRecordStore = runRecordStore,
      runEventJournalStore = runEventJournalStore,
      promptCheckpointStore = promptCheckpointStore,
      managedProcessesProvider = {
        listOf(
          ManagedProcessSnapshot(
            processId = "proc-live",
            taskId = taskId,
            command = "npm",
            args = listOf("run", "dev"),
            workingDirectory = "/workspace",
            status = ManagedProcessStatus.RUNNING,
            processStarted = true,
            timeoutMs = 300_000L,
            startedAtEpochMs = 1_050L,
            updatedAtEpochMs = 1_300L,
          ),
        )
      },
      clock = { 5_000L },
    )

    val restored = requireNotNull(store.load())
    val restoredTask = restored.tasks.single()

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTask.lifecycleState)
    assertEquals(AgentTaskState.QUEUED, restoredTask.task.state)
    assertEquals("5000", restoredTask.task.metadata[METADATA_QUEUE_RESTORE_EPOCH_MS])
    assertEquals("running", restoredTask.task.metadata[METADATA_PREVIOUS_LIFECYCLE_STATE])
    assertEquals("durable_general_resume_checkpoint", restoredTask.task.metadata[METADATA_RECOVERY_REASON])
    assertEquals(5_000L, restoredTask.task.updatedAtEpochMs)
    assertNull(restoredTask.lastErrorCode)
    assertNull(restoredTask.lastErrorMessage)
  }

  private fun approvalRequiredResult(
    taskId: String,
    toolName: String,
    resumeState: OpenCrayPromptResumeState,
    errorCode: String = "APPROVAL_REQUIRED",
    errorMessage: String = "Approval is required before $toolName can run.",
  ): ExecutionResult = ExecutionResult(
    taskId = taskId,
    status = ExecutionStatus.DENIED,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = 1_150L,
    finishedAtEpochMs = 1_150L,
    metadata = approvalMetadata(toolName = toolName, resumeState = resumeState),
  )

  private fun approvalMetadata(
    toolName: String,
    resumeState: OpenCrayPromptResumeState,
  ): Map<String, String> = OpenCrayPromptResumeMetadata.encodeToMetadata(
    state = resumeState,
    json = json,
  ) + mapOf(
    "normalizedToolName" to toolName,
    OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to toolName,
  )
}
