package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE: String = "PROCESS_INTERRUPTED_ON_RESTORE"
private const val METADATA_RESTORED_FROM_DURABLE_STORE: String = "restoredFromDurableStore"
private const val METADATA_RESTORED_TERMINAL_STATE: String = "restoredTerminalState"
private const val RESTORED_TERMINAL_STATE_INTERRUPTED: String = "interrupted"

class AgentSessionRuntimeManagerTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { prettyPrint = false }

  @Test
  fun forSessionReturnsStableHandleForSameSessionId() {
    val manager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )

    val first = manager.forSession("session-1")
    val second = manager.forSession("session-1")

    assertSame(first, second)
  }

  @Test
  fun submitPromptQueuesMultipleTasksBeforeProcessingStarts() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory()
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )
    val handle = manager.forSession("session-queued")

    val firstRun = handle.submitPrompt(
      userText = "first prompt",
      pendingMessageId = "pending-1",
      visibleThroughMessageId = "pending-1",
      policyDecision = allowDecision(),
    )
    val secondRun = handle.submitPrompt(
      userText = "second prompt",
      pendingMessageId = "pending-2",
      visibleThroughMessageId = "pending-2",
      policyDecision = allowDecision(),
    )
    handle.ensureProcessing()
    handle.ensureProcessing()

    val queuedSnapshot = handle.snapshot()
    assertEquals(2, queuedSnapshot.tasks.size)
    assertEquals(
      listOf(QueueTaskLifecycleState.QUEUED, QueueTaskLifecycleState.QUEUED),
      queuedSnapshot.tasks.map { it.lifecycleState },
    )
    assertEquals(1, executor.pendingCount())

    executor.runNext()

    val completedSnapshot = handle.snapshot()
    assertEquals(
      listOf(QueueTaskLifecycleState.COMPLETED, QueueTaskLifecycleState.COMPLETED),
      completedSnapshot.tasks.map { it.lifecycleState },
    )
    assertEquals(listOf("first prompt", "second prompt"), runtimeFactory.executedInputs)
    assertEquals(
      linkedSetOf(firstRun.runId, secondRun.runId),
      handle.listRuns().mapTo(linkedSetOf(), AgentRunSnapshot::runId),
    )
  }

  @Test
  fun submitPromptCarriesLifecycleDiagnosticsIntoSubmissionAndRunSnapshot() {
    val manager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )
    val handle = manager.forSession("session-diagnostics")

    val submission = handle.submitPrompt(
      userText = "diagnose this run",
      pendingMessageId = "pending-diagnostics",
      visibleThroughMessageId = "pending-diagnostics",
      policyDecision = allowDecision(),
      metadata = mapOf(
        RunLifecycleMetadataKeys.SUBMISSION_SOURCE to "test_submit",
      ),
    )
    val run = requireNotNull(handle.findRun(submission.runId))

    assertTrue(submission.lifecycleDiagnostics.processStartId?.startsWith("process-") == true)
    assertTrue(submission.lifecycleDiagnostics.hostInstanceId?.startsWith("host-") == true)
    assertEquals(
      submission.lifecycleDiagnostics.hostInstanceId,
      submission.lifecycleDiagnostics.runtimeOwnerId,
    )
    assertEquals("test_submit", submission.lifecycleDiagnostics.submissionSource)
    assertEquals(
      submission.lifecycleDiagnostics.processStartId,
      run.lifecycleDiagnostics.processStartId,
    )
    assertEquals(
      submission.lifecycleDiagnostics.hostInstanceId,
      run.lifecycleDiagnostics.hostInstanceId,
    )
    assertEquals("test_submit", run.lifecycleDiagnostics.submissionSource)
  }

  @Test
  fun resumeRestoresPersistedQueuedTaskIntoSameOwnerPath() {
    val sessionId = "session-restored"
    val initialExecutor = RecordingExecutorService()
    val initialFactory = RecordingRuntimeFactory()
    val firstManager = manager(
      runtimeFactory = initialFactory,
      executor = initialExecutor,
    )
    val submission = firstManager.forSession(sessionId).submitPrompt(
      userText = "restored prompt",
      pendingMessageId = "pending-restored",
      visibleThroughMessageId = "pending-restored",
      policyDecision = allowDecision(),
    )

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    restoredHandle.resume()
    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    val snapshot = restoredHandle.snapshot()
    assertEquals(listOf(QueueTaskLifecycleState.COMPLETED), snapshot.tasks.map { it.lifecycleState })
    assertEquals(listOf("restored prompt"), restoredFactory.executedInputs)
    assertEquals(QueueTaskLifecycleState.COMPLETED, restoredHandle.findRun(submission.runId)?.lifecycleState)
  }

  @Test
  fun detachedControlTaskCanResumeByTaskIdWithoutQueueSnapshot() {
    val executor = RecordingExecutorService()
    val sessionId = "session-detached-control"
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val runtimeFactory = RecordingRuntimeFactory(
      detachedControlResultFactory = { task ->
        when (task.metadata[METADATA_EXECUTION_KIND]) {
          EXECUTION_KIND_APPROVAL_RESUME -> ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "resumed:${task.input}",
            startedAtEpochMs = 1_200L,
            finishedAtEpochMs = 1_201L,
          )

          else -> approvalRequiredResult(
            taskId = task.id,
            toolName = "Read",
            resumeState = resumeState,
          )
        }
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )
    val handle = manager.forSession(sessionId)

    val submission = handle.submitDetachedControlTask(
      AgentTask(
        id = "detached-control-task-1",
        type = AgentTaskType.SYSTEM,
        input = "internal:subagent_recovery_wait:child-1",
        policyDecision = allowDecision(),
        createdAtEpochMs = 1_000L,
        metadata = mapOf(
          METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
          METADATA_SUBAGENT_RECOVERY_AGENT_ID to "child-1",
          METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID to "parent-run-1",
        ),
      ),
    )

    assertTrue(handle.snapshot().tasks.isEmpty())
    assertEquals(1, executor.pendingCount())

    executor.runNext()

    val pausedRun = requireNotNull(handle.findRun(submission.runId))
    assertEquals(null, pausedRun.lifecycleState)
    assertEquals(ExecutionStatus.DENIED, pausedRun.executionStatus)
    assertEquals("APPROVAL_REQUIRED", pausedRun.errorCode)
    assertTrue(!pausedRun.isTerminal)
    assertEquals(1, handle.listDetachedControlTasks().size)

    val resumed = handle.requestResumeTask(submission.taskId)

    assertTrue(resumed)
    assertEquals(1, executor.pendingCount())

    executor.runNext()

    val completedRun = requireNotNull(handle.findRun(submission.runId))
    assertEquals(ExecutionStatus.SUCCESS, completedRun.executionStatus)
    assertTrue(completedRun.isTerminal)
    assertTrue(handle.listDetachedControlTasks().isEmpty())
    assertEquals(
      listOf(
        "internal:subagent_recovery_wait:child-1",
        "internal:subagent_recovery_wait:child-1",
      ),
      runtimeFactory.detachedControlInputs,
    )
    assertTrue(runtimeFactory.executedInputs.isEmpty())
  }

  @Test
  fun detachedControlTaskCanResumeAfterManagerRestart() {
    val sessionId = "session-detached-control-restart"
    val firstExecutor = RecordingExecutorService()
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val firstFactory = RecordingRuntimeFactory(
      detachedControlResultFactory = { task ->
        when (task.metadata[METADATA_EXECUTION_KIND]) {
          EXECUTION_KIND_APPROVAL_RESUME -> ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "resumed:${task.input}",
            startedAtEpochMs = 1_200L,
            finishedAtEpochMs = 1_201L,
          )

          else -> approvalRequiredResult(
            taskId = task.id,
            toolName = "Read",
            resumeState = resumeState,
          )
        }
      },
    )
    val firstManager = manager(
      runtimeFactory = firstFactory,
      executor = firstExecutor,
    )
    val firstHandle = firstManager.forSession(sessionId)
    val submission = firstHandle.submitDetachedControlTask(
      AgentTask(
        id = "detached-control-task-restart",
        type = AgentTaskType.SYSTEM,
        input = "internal:subagent_recovery_wait:child-restart",
        policyDecision = allowDecision(),
        createdAtEpochMs = 1_000L,
        metadata = mapOf(
          METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
          METADATA_SUBAGENT_RECOVERY_AGENT_ID to "child-restart",
          METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID to "parent-run-restart",
        ),
      ),
    )

    firstExecutor.runNext()
    assertEquals(1, firstHandle.listDetachedControlTasks().size)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory(
      detachedControlResultFactory = { task ->
        when (task.metadata[METADATA_EXECUTION_KIND]) {
          EXECUTION_KIND_APPROVAL_RESUME -> ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "resumed:${task.input}",
            startedAtEpochMs = 1_300L,
            finishedAtEpochMs = 1_301L,
          )

          else -> approvalRequiredResult(
            taskId = task.id,
            toolName = "Read",
            resumeState = resumeState,
          )
        }
      },
    )
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    assertEquals(1, restoredHandle.listDetachedControlTasks().size)
    assertTrue(restoredHandle.requestResumeTask(submission.taskId))
    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(ExecutionStatus.SUCCESS, restoredHandle.findRun(submission.runId)?.executionStatus)
    assertTrue(restoredHandle.listDetachedControlTasks().isEmpty())
    assertEquals(
      listOf("internal:subagent_recovery_wait:child-restart"),
      restoredFactory.detachedControlInputs,
    )
  }

  @Test
  fun detachedSubAgentRecoveryTaskCanResumeByTaskIdWithoutQueueSnapshot() {
    val executor = RecordingExecutorService()
    val sessionId = "session-detached-subagent-recovery"
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val runtimeFactory = RecordingRuntimeFactory(
      subAgentRecoveryResultFactory = { task ->
        when (task.metadata[METADATA_EXECUTION_KIND]) {
          EXECUTION_KIND_APPROVAL_RESUME -> ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "resumed:${task.input}",
            startedAtEpochMs = 1_200L,
            finishedAtEpochMs = 1_201L,
          )

          else -> approvalRequiredResult(
            taskId = task.id,
            toolName = "Read",
            resumeState = resumeState,
          )
        }
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )
    val handle = manager.forSession(sessionId)

    val submission = handle.submitDetachedSubAgentRecoveryTask(
      agentId = "child-1",
      parentRunId = "parent-run-1",
      taskId = "subagent-recovery-task-1",
      createdAtEpochMs = 1_000L,
      submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )

    assertTrue(handle.snapshot().tasks.isEmpty())
    assertEquals(1, executor.pendingCount())

    executor.runNext()

    val pausedRun = requireNotNull(handle.findRun(submission.runId))
    assertEquals(null, pausedRun.lifecycleState)
    assertEquals(ExecutionStatus.DENIED, pausedRun.executionStatus)
    assertEquals("APPROVAL_REQUIRED", pausedRun.errorCode)
    assertTrue(!pausedRun.isTerminal)
    assertEquals(1, handle.listDetachedControlTasks().size)

    val resumed = handle.requestResumeTask(submission.taskId)

    assertTrue(resumed)
    assertEquals(1, executor.pendingCount())

    executor.runNext()

    val completedRun = requireNotNull(handle.findRun(submission.runId))
    assertEquals(ExecutionStatus.SUCCESS, completedRun.executionStatus)
    assertTrue(completedRun.isTerminal)
    assertTrue(handle.listDetachedControlTasks().isEmpty())
    assertEquals(
      listOf(
        "internal:subagent_recovery_wait:child-1",
        "internal:subagent_recovery_wait:child-1",
      ),
      runtimeFactory.subAgentRecoveryInputs,
    )
    assertTrue(runtimeFactory.detachedControlInputs.isEmpty())
    assertTrue(runtimeFactory.executedInputs.isEmpty())
  }

  @Test
  fun detachedSubAgentRecoveryTaskCanResumeAfterManagerRestart() {
    val sessionId = "session-detached-subagent-recovery-restart"
    val firstExecutor = RecordingExecutorService()
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val firstFactory = RecordingRuntimeFactory(
      subAgentRecoveryResultFactory = { task ->
        when (task.metadata[METADATA_EXECUTION_KIND]) {
          EXECUTION_KIND_APPROVAL_RESUME -> ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "resumed:${task.input}",
            startedAtEpochMs = 1_200L,
            finishedAtEpochMs = 1_201L,
          )

          else -> approvalRequiredResult(
            taskId = task.id,
            toolName = "Read",
            resumeState = resumeState,
          )
        }
      },
    )
    val firstManager = manager(
      runtimeFactory = firstFactory,
      executor = firstExecutor,
    )
    val firstHandle = firstManager.forSession(sessionId)
    val submission = firstHandle.submitDetachedSubAgentRecoveryTask(
      agentId = "child-restart",
      parentRunId = "parent-run-restart",
      taskId = "subagent-recovery-task-restart",
      createdAtEpochMs = 1_000L,
      submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )

    firstExecutor.runNext()
    assertEquals(1, firstHandle.listDetachedControlTasks().size)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory(
      subAgentRecoveryResultFactory = { task ->
        when (task.metadata[METADATA_EXECUTION_KIND]) {
          EXECUTION_KIND_APPROVAL_RESUME -> ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "resumed:${task.input}",
            startedAtEpochMs = 1_300L,
            finishedAtEpochMs = 1_301L,
          )

          else -> approvalRequiredResult(
            taskId = task.id,
            toolName = "Read",
            resumeState = resumeState,
          )
        }
      },
    )
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    assertEquals(1, restoredHandle.listDetachedControlTasks().size)
    assertTrue(restoredHandle.requestResumeTask(submission.taskId))
    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(ExecutionStatus.SUCCESS, restoredHandle.findRun(submission.runId)?.executionStatus)
    assertTrue(restoredHandle.listDetachedControlTasks().isEmpty())
    assertEquals(
      listOf("internal:subagent_recovery_wait:child-restart"),
      restoredFactory.subAgentRecoveryInputs,
    )
  }

  @Test
  fun detachedSubAgentRecoveryTaskUsesDedicatedRecoveryExecutorWhenProvided() {
    val mainExecutor = RecordingExecutorService()
    val recoveryExecutor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      subAgentRecoveryResultFactory = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          stdout = "recovered:${task.input}",
          startedAtEpochMs = 1_200L,
          finishedAtEpochMs = 1_201L,
        )
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = mainExecutor,
      subAgentRecoveryExecutor = recoveryExecutor,
    )
    val handle = manager.forSession("session-dedicated-subagent-recovery-executor")

    handle.submitDetachedSubAgentRecoveryTask(
      agentId = "child-dedicated",
      parentRunId = "parent-run-dedicated",
      taskId = "subagent-recovery-task-dedicated",
      createdAtEpochMs = 1_000L,
      submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )

    assertEquals(0, mainExecutor.pendingCount())
    assertEquals(1, recoveryExecutor.pendingCount())
  }

  @Test
  fun detachedSubAgentRecoveryTaskDeduplicatesByHandleKey() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      subAgentRecoveryResultFactory = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          stdout = "recovered:${task.input}",
          startedAtEpochMs = 1_200L,
          finishedAtEpochMs = 1_201L,
        )
      },
    )
    val manager = manager(runtimeFactory = runtimeFactory, executor = executor)
    val handle = manager.forSession("session-deduplicated-subagent-recovery")

    val first = handle.submitDetachedSubAgentRecoveryTask(
      agentId = "child-deduplicated",
      parentRunId = "parent-run-deduplicated",
      taskId = "subagent-recovery-task-first",
      createdAtEpochMs = 1_000L,
      submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )
    val second = handle.submitDetachedSubAgentRecoveryTask(
      agentId = "child-deduplicated",
      parentRunId = "parent-run-deduplicated",
      taskId = "subagent-recovery-task-second",
      createdAtEpochMs = 1_001L,
      submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
    )

    assertEquals(first.taskId, second.taskId)
    assertEquals(first.runId, second.runId)
    assertEquals(1, executor.pendingCount())
    assertEquals(1, handle.listDetachedControlTasks().size)
    assertEquals("subagent-recovery-task-first", handle.listDetachedControlTasks().single().id)
  }

  @Test
  fun ensureRecoverableDetachedSubAgentTasksSchedulesQueuedChildOnce() {
    val executor = RecordingExecutorService()
    val queuedHandle = backgroundSubAgentHandle("child-recovery").copy(
      snapshot = SubAgentExecutionSnapshot.backgroundQueued(),
      updatedAtEpochMs = 1_200L,
    )
    val runtimeFactory = RecordingRuntimeFactory(
      detachedControlResultFactory = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          stdout = "recovered:${task.input}",
          startedAtEpochMs = 1_300L,
          finishedAtEpochMs = 1_301L,
        )
      },
      subAgentHandlesProvider = { _ -> listOf(queuedHandle) },
    )
    val manager = manager(runtimeFactory = runtimeFactory, executor = executor)
    val handle = manager.forSession("session-subagent-recovery")

    val firstScheduled = handle.ensureRecoverableDetachedSubAgentTasks()
    val secondScheduled = handle.ensureRecoverableDetachedSubAgentTasks()

    assertEquals(1, firstScheduled)
    assertEquals(0, secondScheduled)
    assertEquals(1, executor.pendingCount())
    assertEquals(1, handle.listDetachedControlTasks().size)
    val detachedTask = handle.listDetachedControlTasks().single()
    assertEquals(
      detachedSubAgentRecoveryTaskId(
        sessionId = "session-subagent-recovery",
        agentId = "child-recovery",
        parentRunId = "parent-run",
      ),
      detachedTask.id,
    )
    assertEquals(
      RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
      detachedTask.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals("child-recovery", detachedTask.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID])
    assertEquals("parent-run", detachedTask.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID])

    executor.runNext()

    assertTrue(handle.listDetachedControlTasks().isEmpty())
    assertEquals(
      listOf("internal:subagent_recovery_wait:child-recovery"),
      runtimeFactory.detachedControlInputs,
    )
  }

  @Test
  fun restoredApprovedCheckpointKeepsSameRunQueuedAndAutoResumes() {
    val sessionId = "session-approved-checkpoint-restore"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "continue after approval",
      pendingMessageId = "pending-approved-restore",
      visibleThroughMessageId = "pending-approved-restore",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = submission.taskId,
        checkpointId = "checkpoint-approved",
        checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Read",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
        ),
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(submission.taskId, restoredRun.taskId)
    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.QUEUED, restoredRun.lifecycleState)
    assertTrue(restoredRun.lifecycleDiagnostics.previousLifecycleState != null)
    assertTrue(restoredRun.lifecycleDiagnostics.queueRestoreEpochMs != null)
    assertEquals(null, restoredRun.executionStatus)
    assertEquals(null, restoredRun.errorCode)
    assertTrue(!restoredRun.isTerminal)
    assertTrue(restoredRun.isActive)

    restoredHandle.resume()

    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(listOf("continue after approval"), restoredFactory.executedInputs)
    assertEquals(0, restoredExecutor.pendingCount())
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredApprovedDecisionWithoutCheckpointKeepsSameRunQueuedAndAutoResumes() {
    val sessionId = "session-approved-synthetic-restore"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "continue after synthetic approval",
      pendingMessageId = "pending-approved-synthetic-restore",
      visibleThroughMessageId = "pending-approved-synthetic-restore",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = submission.runId,
        taskId = submission.taskId,
        acceptedAtEpochMs = submission.acceptedAtEpochMs,
        pendingMessageId = "pending-approved-synthetic-restore",
        lastResult = approvalRequiredResult(
          taskId = submission.taskId,
          toolName = "Read",
          resumeState = OpenCrayPromptResumeState(
            turnIndex = 1,
            toolCallCount = 1,
          ),
        ),
        lastEvent = OpenCrayApprovalEvent(
          runId = submission.runId,
          taskId = submission.taskId,
          phase = OpenCrayApprovalPhase.APPROVED,
          toolName = "Read",
          text = "Approval granted.",
          emittedAtEpochMs = 101L,
        ).toPersistedRecord(),
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))
    val synthesizedCheckpoint = promptCheckpointStoreFactory.forChatSession(sessionId).get(submission.taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.QUEUED, restoredRun.lifecycleState)
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, synthesizedCheckpoint?.checkpointKind)
    assertEquals("Read", synthesizedCheckpoint?.toolName)

    restoredHandle.resume()

    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(listOf("continue after synthetic approval"), restoredFactory.executedInputs)
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredLiveManagedProcessWithCheckpointAutoResumesFromCheckpoint() {
    val sessionId = "session-live-process-reconnect"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "keep the dev server alive",
      pendingMessageId = "pending-live-process-reconnect",
      visibleThroughMessageId = "pending-live-process-reconnect",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = submission.taskId,
        checkpointId = "checkpoint-live-process",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "ProcessStart",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
        ),
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { restoredSessionId ->
        if (restoredSessionId == sessionId) {
          listOf(
            managedProcessSnapshot(
              processId = "proc-live",
              taskId = submission.taskId,
              status = ManagedProcessStatus.RUNNING,
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.QUEUED, restoredRun.lifecycleState)
    assertTrue(restoredRun.hasLiveManagedProcesses)

    restoredHandle.resume()

    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(listOf("keep the dev server alive"), restoredFactory.executedInputs)
    assertEquals(QueueTaskLifecycleState.COMPLETED, restoredHandle.findRun(submission.runId)?.lifecycleState)
  }

  @Test
  fun restoredLiveManagedProcessWithoutCheckpointStaysSuspendedUntilExplicitFollowUp() {
    val sessionId = "session-live-process-reconnect-pending"
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "keep the watcher attached",
      pendingMessageId = "pending-live-process-reconnect-pending",
      visibleThroughMessageId = "pending-live-process-reconnect-pending",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { restoredSessionId ->
        if (restoredSessionId == sessionId) {
          listOf(
            managedProcessSnapshot(
              processId = "proc-live",
              taskId = submission.taskId,
              status = ManagedProcessStatus.RUNNING,
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredRun.lifecycleState)
    assertEquals(AgentTaskState.SUSPENDED, restoredRun.taskState)
    assertTrue(restoredRun.hasLiveManagedProcesses)
    assertEquals("live_managed_process_detected", restoredRun.lifecycleDiagnostics.recoveryReason)

    restoredHandle.resume()

    assertEquals(0, restoredExecutor.pendingCount())
    assertTrue(restoredFactory.executedInputs.isEmpty())
    assertEquals(
      QueueTaskLifecycleState.SUSPENDED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredInterruptedRunAutoResumesFromJournalSynthesizedCheckpoint() {
    val sessionId = "session-journal-synthesized-checkpoint-restore"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)
    val submission = firstHandle.submitPrompt(
      userText = "continue from journal boundary",
      pendingMessageId = "pending-journal-synthesized-checkpoint",
      visibleThroughMessageId = "pending-journal-synthesized-checkpoint",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    runEventJournalStoreFactory.forChatSession(sessionId).append(
      OpenCrayToolResultEvent(
        runId = submission.runId,
        taskId = submission.taskId,
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "checkpoint-ready",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
            state = OpenCrayPromptResumeState(
              turnIndex = 1,
              toolCallCount = 1,
            ),
            json = json,
          ),
        ),
        emittedAtEpochMs = 100L,
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))
    val synthesizedCheckpoint = promptCheckpointStoreFactory.forChatSession(sessionId).get(submission.taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.QUEUED, restoredRun.lifecycleState)
    assertEquals(PromptCheckpointKind.GENERAL_RESUME, synthesizedCheckpoint?.checkpointKind)
    assertEquals("Read", synthesizedCheckpoint?.toolName)
    assertTrue(restoredRun.lifecycleDiagnostics.previousLifecycleState != null)
    assertTrue(restoredRun.lifecycleDiagnostics.queueRestoreEpochMs != null)

    restoredHandle.resume()

    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(listOf("continue from journal boundary"), restoredFactory.executedInputs)
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredQueuedRunWithPriorProgressButNoCheckpointDoesNotAutoReplay() {
    val sessionId = "session-queued-progress-no-checkpoint"
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)
    val submission = firstHandle.submitPrompt(
      userText = "resume after unsafe queued progress",
      pendingMessageId = "pending-queued-progress-no-checkpoint",
      visibleThroughMessageId = "pending-queued-progress-no-checkpoint",
      policyDecision = allowDecision(),
    )
    val queueStore = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root).forChatSession(sessionId)
    val queuedSnapshot = firstHandle.snapshot()
    queueStore.save(
      queuedSnapshot.copy(
        tasks = queuedSnapshot.tasks.map { taskSnapshot ->
          if (taskSnapshot.task.id != submission.taskId) {
            taskSnapshot
          } else {
            taskSnapshot.copy(
              lifecycleState = QueueTaskLifecycleState.QUEUED,
              attempt = 1,
              executionOrdinal = 1,
              task = taskSnapshot.task.copy(
                state = AgentTaskState.QUEUED,
                updatedAtEpochMs = maxOf(taskSnapshot.task.updatedAtEpochMs, 2_000L),
                metadata = taskSnapshot.task.metadata + mapOf(
                  com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL to "1",
                  com.opencray.core.orchestrator.METADATA_PENDING_EXECUTION_KIND to
                    com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME,
                ),
              ),
            )
          }
        },
        updatedAtEpochMs = maxOf(queuedSnapshot.updatedAtEpochMs, 2_000L),
      ),
    )
    runEventJournalStoreFactory.forChatSession(sessionId).append(
      OpenCrayToolCallEvent(
        runId = submission.runId,
        taskId = submission.taskId,
        turn = 1,
        call = AgentToolCall(toolName = "Write"),
        emittedAtEpochMs = 100L,
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(QueueTaskLifecycleState.FAILED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.FAILED, restoredRun.lifecycleState)
    assertEquals(AgentTaskState.FAILED, restoredRun.taskState)
    assertEquals(ERROR_RESTART_REQUIRES_EXPLICIT_RETRY, restoredRun.errorCode)
    assertEquals("queued_progress_without_checkpoint", restoredRun.lifecycleDiagnostics.recoveryReason)
    assertTrue(restoredRun.isTerminal)
    assertTrue(!restoredRun.isActive)

    restoredHandle.resume()

    assertEquals(0, restoredExecutor.pendingCount())
    assertTrue(restoredFactory.executedInputs.isEmpty())
  }

  @Test
  fun restoredJournalOnlyPreModelCheckpointAutoResumesFromSameRun() {
    val sessionId = "session-pre-model-journal-restore"
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(temporaryFolder.root)
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "resume from pre-model checkpoint",
      pendingMessageId = "pending-pre-model-journal-restore",
      visibleThroughMessageId = "pending-pre-model-journal-restore",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    runEventJournalStoreFactory.forChatSession(sessionId).append(
      OpenCraySupplementEvent(
        runId = submission.runId,
        taskId = submission.taskId,
        turn = 0,
        entryId = "checkpoint-pre-model-request",
        text = "",
        checkpoint = "internal_prompt_checkpoint",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = OpenCrayPromptResumeState(
            turnIndex = 0,
            toolCallCount = 0,
          ),
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
        ),
        emittedAtEpochMs = 100L,
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))
    val synthesizedCheckpoint = promptCheckpointStoreFactory.forChatSession(sessionId).get(submission.taskId)

    assertEquals(QueueTaskLifecycleState.QUEUED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.QUEUED, restoredRun.lifecycleState)
    assertEquals(PromptCheckpointKind.PRE_MODEL_REQUEST, synthesizedCheckpoint?.checkpointKind)

    restoredHandle.resume()

    assertEquals(1, restoredExecutor.pendingCount())

    restoredExecutor.runNext()

    assertEquals(listOf("resume from pre-model checkpoint"), restoredFactory.executedInputs)
    assertEquals(
      QueueTaskLifecycleState.COMPLETED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredWaitingApprovalKeepsSameRunSuspendedWithoutRerun() {
    val sessionId = "session-waiting-approval-restore"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "needs approval",
      pendingMessageId = "pending-waiting-approval",
      visibleThroughMessageId = "pending-waiting-approval",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = submission.runId,
        taskId = submission.taskId,
        acceptedAtEpochMs = submission.acceptedAtEpochMs,
        pendingMessageId = "pending-waiting-approval",
        lastResult = ExecutionResult(
          taskId = submission.taskId,
          status = ExecutionStatus.DENIED,
          errorCode = "APPROVAL_REQUIRED",
          errorMessage = "Approval is required before Read can run.",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
      ),
    )
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = submission.taskId,
        checkpointId = "checkpoint-waiting",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Read",
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(submission.taskId, restoredRun.taskId)
    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredRun.lifecycleState)
    assertTrue(restoredRun.lifecycleDiagnostics.previousLifecycleState != null)
    assertTrue(restoredRun.lifecycleDiagnostics.queueRestoreEpochMs != null)
    assertEquals(ExecutionStatus.DENIED, restoredRun.executionStatus)
    assertEquals("APPROVAL_REQUIRED", restoredRun.errorCode)
    assertTrue(!restoredRun.isTerminal)
    assertTrue(restoredRun.isActive)

    restoredHandle.resume()

    assertEquals(0, restoredExecutor.pendingCount())
    assertTrue(restoredFactory.executedInputs.isEmpty())
    assertEquals(
      QueueTaskLifecycleState.SUSPENDED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredWaitingApprovalWithoutCheckpointStaysSuspendedWithoutRerun() {
    val sessionId = "session-waiting-approval-synthetic-restore"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "needs synthetic approval wait restore",
      pendingMessageId = "pending-waiting-approval-synthetic",
      visibleThroughMessageId = "pending-waiting-approval-synthetic",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
    )
    runRecordStoreFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = submission.runId,
        taskId = submission.taskId,
        acceptedAtEpochMs = submission.acceptedAtEpochMs,
        pendingMessageId = "pending-waiting-approval-synthetic",
        lastResult = approvalRequiredResult(
          taskId = submission.taskId,
          toolName = "Read",
          resumeState = OpenCrayPromptResumeState(
            turnIndex = 1,
            toolCallCount = 1,
          ),
        ),
        lastEvent = OpenCrayToolResultEvent(
          runId = submission.runId,
          taskId = submission.taskId,
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
              resumeState = OpenCrayPromptResumeState(
                turnIndex = 1,
                toolCallCount = 1,
              ),
            ),
          ),
          emittedAtEpochMs = 101L,
        ).toPersistedRecord(),
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))
    val synthesizedCheckpoint = promptCheckpointStoreFactory.forChatSession(sessionId).get(submission.taskId)

    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.SUSPENDED, restoredRun.lifecycleState)
    assertEquals(PromptCheckpointKind.WAITING_APPROVAL, synthesizedCheckpoint?.checkpointKind)
    assertEquals("Read", synthesizedCheckpoint?.toolName)

    restoredHandle.resume()

    assertEquals(0, restoredExecutor.pendingCount())
    assertTrue(restoredFactory.executedInputs.isEmpty())
    assertEquals(
      QueueTaskLifecycleState.SUSPENDED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun restoredRejectedApprovalCheckpointStopsRunWithoutRerun() {
    val sessionId = "session-rejected-approval-restore"
    val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root)
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "do not continue after reject",
      pendingMessageId = "pending-rejected-approval",
      visibleThroughMessageId = "pending-rejected-approval",
      policyDecision = allowDecision(),
    )
    overwriteQueueSnapshot(
      sessionId = sessionId,
      snapshot = firstHandle.snapshot(),
      taskId = submission.taskId,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
    )
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = submission.taskId,
        checkpointId = "checkpoint-rejected",
        checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        toolName = "Write",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
        ),
      ),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory()
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredTaskSnapshot = restoredHandle.snapshot().tasks.single()
    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(QueueTaskLifecycleState.CANCELLED, restoredTaskSnapshot.lifecycleState)
    assertEquals(QueueTaskLifecycleState.CANCELLED, restoredRun.lifecycleState)
    assertEquals(AgentTaskState.CANCELLED, restoredRun.taskState)
    assertEquals(
      "approval_already_rejected_waiting_for_instruction",
      restoredRun.lifecycleDiagnostics.recoveryReason,
    )
    assertTrue(restoredRun.isTerminal)
    assertTrue(!restoredRun.isActive)

    restoredHandle.resume()

    assertEquals(0, restoredExecutor.pendingCount())
    assertTrue(restoredFactory.executedInputs.isEmpty())
    assertEquals(
      QueueTaskLifecycleState.CANCELLED,
      restoredHandle.findRun(submission.runId)?.lifecycleState,
    )
  }

  @Test
  fun releaseIdleSessionsKeepsSessionWithLiveManagedProcesses() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { sessionId ->
        if (sessionId == "session-live-process") {
          listOf(
            managedProcessSnapshot(
              processId = "proc-live",
              taskId = "task-live",
              status = ManagedProcessStatus.RUNNING,
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )

    val first = manager.forSession("session-live-process")

    manager.releaseIdleSessions()

    val second = manager.forSession("session-live-process")

    assertSame(first, second)
  }

  @Test
  fun releaseIdleSessionsDropsSessionWhenOnlyTerminalManagedProcessesRemain() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { sessionId ->
        if (sessionId == "session-terminal-process") {
          listOf(
            managedProcessSnapshot(
              processId = "proc-done",
              taskId = "task-done",
              status = ManagedProcessStatus.SUCCESS,
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )

    val first = manager.forSession("session-terminal-process")

    manager.releaseIdleSessions()

    val second = manager.forSession("session-terminal-process")

    assertTrue(first !== second)
    assertEquals(listOf("session-terminal-process"), runtimeFactory.releasedSessions)
  }

  @Test
  fun releaseIdleSessionsKeepsSessionWithLiveSubAgents() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      subAgentHandlesProvider = { sessionId ->
        if (sessionId == "session-live-subagent") {
          listOf(backgroundSubAgentHandle(agentId = "child-live"))
        } else {
          emptyList()
        }
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )

    val first = manager.forSession("session-live-subagent")

    manager.releaseIdleSessions()

    val second = manager.forSession("session-live-subagent")

    assertSame(first, second)
    assertEquals(listOf("session-live-subagent"), manager.activeWorkSummary().activeSessionIds)
  }

  @Test
  fun releaseInvokesRuntimeFactorySessionCleanup() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory()
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )

    manager.forSession("session-release-hook")

    manager.release("session-release-hook")

    assertEquals(listOf("session-release-hook"), runtimeFactory.releasedSessions)
  }

  @Test
  fun completedRunRestoresDurableResultMetadataAndLastEventAfterManagerRecreation() {
    val sessionId = "session-durable-runs"
    val firstExecutor = RecordingExecutorService()
    val firstFactory = RecordingRuntimeFactory(
      onExecute = { task, eventSink ->
        eventSink.onRunEvent(
          task,
          OpenCrayToolResultEvent(
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty(),
            taskId = task.id,
            turn = 1,
            call = AgentToolCall(
              toolName = "Read",
            ),
            result = AgentToolResult(
              toolName = "Read",
              status = AgentToolResultStatus.SUCCESS,
              content = "README preview",
              metadata = mapOf("processId" to "proc-restore"),
            ),
            emittedAtEpochMs = 1_001L,
          ),
        )
      },
      executionResultFactory = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          stdout = "done",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_002L,
          metadata = task.metadata + mapOf(
            "responseFormat" to "json_final",
            "contextMatchedMemoryCount" to "3",
          ),
        )
      },
    )
    val firstManager = manager(
      runtimeFactory = firstFactory,
      executor = firstExecutor,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "restore completed run",
      pendingMessageId = "pending-durable",
      visibleThroughMessageId = "pending-durable",
      policyDecision = allowDecision(),
    )
    firstHandle.ensureProcessing()
    firstExecutor.runNext()
    firstManager.release(sessionId)

    val restoredManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )
    val restoredRun = restoredManager.forSession(sessionId).findRun(submission.runId)

    assertEquals(ExecutionStatus.SUCCESS, restoredRun?.executionStatus)
    assertEquals("json_final", restoredRun?.responseFormat)
    assertEquals("3", restoredRun?.resultMetadata?.get("contextMatchedMemoryCount"))
    assertEquals("pending-durable", restoredRun?.pendingMessageId)
    val restoredEvent = restoredRun?.lastEvent as? OpenCrayToolResultEvent
    assertEquals("Read", restoredEvent?.call?.toolName)
    assertEquals(AgentToolResultStatus.SUCCESS, restoredEvent?.result?.status)
    assertEquals("README preview", restoredEvent?.result?.content)
  }

  @Test
  fun restoredRunSnapshotReportsLiveManagedProcessLinkage() {
    val sessionId = "session-durable-process-linkage"
    val firstExecutor = RecordingExecutorService()
    val firstFactory = RecordingRuntimeFactory(
      onExecute = { task, eventSink ->
        eventSink.onRunEvent(
          task,
          OpenCrayToolResultEvent(
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty(),
            taskId = task.id,
            turn = 1,
            call = AgentToolCall(
              toolName = "ProcessStart",
            ),
            result = AgentToolResult(
              toolName = "ProcessStart",
              status = AgentToolResultStatus.SUCCESS,
              content = "Started dev server",
              metadata = mapOf("processId" to "proc-live"),
            ),
            emittedAtEpochMs = 1_001L,
          ),
        )
      },
      executionResultFactory = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          stdout = "server started",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_002L,
          metadata = task.metadata,
        )
      },
      managedProcessesProvider = { restoredSessionId ->
        if (restoredSessionId == sessionId) {
          listOf(
            managedProcessSnapshot(
              processId = "proc-live",
              taskId = "ignored-before-restore",
              status = ManagedProcessStatus.RUNNING,
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val firstManager = manager(
      runtimeFactory = firstFactory,
      executor = firstExecutor,
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "start dev server",
      pendingMessageId = "pending-process-linkage",
      visibleThroughMessageId = "pending-process-linkage",
      policyDecision = allowDecision(),
    )
    firstHandle.ensureProcessing()
    firstExecutor.runNext()
    firstManager.release(sessionId)

    val restoredFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { restoredSessionId ->
        if (restoredSessionId == sessionId) {
          listOf(
            managedProcessSnapshot(
              processId = "proc-live",
              taskId = submission.taskId,
              status = ManagedProcessStatus.RUNNING,
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = RecordingExecutorService(),
    )
    val restoredRun = restoredManager.forSession(sessionId).findRun(submission.runId)

    assertEquals(listOf("proc-live"), restoredRun?.managedProcessIds)
    assertEquals(1, restoredRun?.runningManagedProcessCount)
    assertTrue(restoredRun?.hasLiveManagedProcesses == true)
    assertTrue(restoredRun?.isTerminal == true)
    assertTrue(restoredRun?.isActive == true)
  }

  @Test
  fun restoredInterruptedManagedProcessRunSettlesAndDoesNotAutoResume() {
    val sessionId = "session-restored-interrupted-process"
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "wait for the background process",
      pendingMessageId = "pending-restored-process",
      visibleThroughMessageId = "pending-restored-process",
      policyDecision = allowDecision(),
    )
    firstManager.release(sessionId)

    val restoredExecutor = RecordingExecutorService()
    val restoredFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { restoredSessionId ->
        if (restoredSessionId == sessionId) {
          listOf(
            managedProcessSnapshot(
              processId = "proc-restored",
              taskId = submission.taskId,
              status = ManagedProcessStatus.FAILED,
            ).copy(
              errorCode = errorManagedProcessInterruptedOnRestoreForTest,
              errorMessage = "Managed process state was restored without a live controller; marking it interrupted.",
              updatedAtEpochMs = 1_002L,
              finishedAtEpochMs = 1_002L,
              metadata = mapOf(
                metadataRestoredFromDurableStoreForTest to "true",
                metadataRestoredTerminalStateForTest to restoredTerminalStateInterruptedForTest,
              ),
            ),
          )
        } else {
          emptyList()
        }
      },
    )
    val restoredManager = manager(
      runtimeFactory = restoredFactory,
      executor = restoredExecutor,
    )
    val restoredHandle = restoredManager.forSession(sessionId)

    val restoredRun = restoredHandle.findRun(submission.runId)

    assertEquals(ExecutionStatus.FAILED, restoredRun?.executionStatus)
    assertEquals(errorManagedProcessInterruptedOnRestoreForTest, restoredRun?.errorCode)
    assertEquals(QueueTaskLifecycleState.FAILED, restoredRun?.lifecycleState)
    assertEquals(AgentTaskState.FAILED, restoredRun?.taskState)
    assertEquals(
      restoredTerminalStateInterruptedForTest,
      restoredRun?.resultMetadata?.get(metadataRestoredTerminalStateForTest),
    )
    assertEquals(listOf("proc-restored"), restoredRun?.managedProcessIds)
    assertEquals(0, restoredRun?.runningManagedProcessCount)
    assertTrue(restoredRun?.hasLiveManagedProcesses == false)
    assertTrue(restoredRun?.isTerminal == true)
    assertTrue(restoredRun?.isActive == false)
    assertTrue(!restoredHandle.hasPendingWork())

    restoredHandle.resume()

    assertEquals(0, restoredExecutor.pendingCount())
    assertTrue(restoredFactory.executedInputs.isEmpty())

    restoredManager.release(sessionId)

    val persistedManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )
    val persistedRun = persistedManager.forSession(sessionId).findRun(submission.runId)

    assertEquals(ExecutionStatus.FAILED, persistedRun?.executionStatus)
    assertEquals(errorManagedProcessInterruptedOnRestoreForTest, persistedRun?.errorCode)
    assertEquals(listOf("proc-restored"), persistedRun?.managedProcessIds)
  }

  @Test
  fun restoredInterruptedManagedProcessRunRepairsFromArchivedSnapshotReadById() {
    val sessionId = "session-restored-archived-process-read"
    val firstManager = manager(
      runtimeFactory = RecordingRuntimeFactory(),
      executor = RecordingExecutorService(),
    )
    val firstHandle = firstManager.forSession(sessionId)

    val submission = firstHandle.submitPrompt(
      userText = "restore archived managed process by id",
      pendingMessageId = "pending-archived-process-read",
      visibleThroughMessageId = "pending-archived-process-read",
      policyDecision = allowDecision(),
    )
    FileBackedAgentRunRecordStoreFactory(temporaryFolder.root)
      .forChatSession(sessionId)
      .upsert(
        PersistedAgentRunRecord(
          runId = submission.runId,
          taskId = submission.taskId,
          acceptedAtEpochMs = submission.acceptedAtEpochMs,
          pendingMessageId = "pending-archived-process-read",
          managedProcessIds = listOf("proc-archived"),
        ),
      )
    firstManager.release(sessionId)

    val restoredFactory = RecordingRuntimeFactory(
      managedProcessesProvider = { emptyList() },
      readManagedProcessHandler = { restoredSessionId, processId ->
        if (restoredSessionId == sessionId && processId == "proc-archived") {
          managedProcessSnapshot(
            processId = processId,
            taskId = submission.taskId,
            status = ManagedProcessStatus.FAILED,
          ).copy(
            errorCode = errorManagedProcessInterruptedOnRestoreForTest,
            errorMessage = "Archived managed process restore should still repair the run.",
            updatedAtEpochMs = 1_005L,
            finishedAtEpochMs = 1_005L,
            metadata = mapOf(
              metadataRestoredFromDurableStoreForTest to "true",
              metadataRestoredTerminalStateForTest to restoredTerminalStateInterruptedForTest,
            ),
          )
        } else {
          null
        }
      },
    )
    val restoredHandle = manager(
      runtimeFactory = restoredFactory,
      executor = RecordingExecutorService(),
    ).forSession(sessionId)

    val restoredRun = requireNotNull(restoredHandle.findRun(submission.runId))

    assertEquals(listOf("proc-archived"), restoredRun.managedProcessIds)
    assertEquals(listOf("proc-archived"), restoredRun.managedProcesses.map(ManagedProcessSnapshot::processId))
    assertEquals(ExecutionStatus.FAILED, restoredRun.executionStatus)
    assertEquals(errorManagedProcessInterruptedOnRestoreForTest, restoredRun.errorCode)
    assertEquals(QueueTaskLifecycleState.FAILED, restoredRun.lifecycleState)
    assertEquals(AgentTaskState.FAILED, restoredRun.taskState)
    assertEquals(
      restoredTerminalStateInterruptedForTest,
      restoredRun.resultMetadata[metadataRestoredTerminalStateForTest],
    )
  }

  @Test
  fun requestRetryClearsPreviousFailureFromQueuedRunSnapshot() {
    val executor = RecordingExecutorService()
    var executionCount = 0
    val runtimeFactory = RecordingRuntimeFactory(
      executionResultFactory = { task ->
        executionCount += 1
        if (executionCount == 1) {
          ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.FAILED,
            errorCode = "TRANSIENT_FAILURE",
            errorMessage = "Try again.",
            startedAtEpochMs = 1_000L,
            finishedAtEpochMs = 1_001L,
          )
        } else {
          ExecutionResult(
            taskId = task.id,
            status = ExecutionStatus.SUCCESS,
            stdout = "ok",
            startedAtEpochMs = 1_002L,
            finishedAtEpochMs = 1_003L,
          )
        }
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )
    val handle = manager.forSession("session-manual-retry")

    val submission = handle.submitPrompt(
      userText = "retry this prompt",
      pendingMessageId = "pending-retry",
      visibleThroughMessageId = "pending-retry",
      policyDecision = allowDecision(),
    )
    handle.ensureProcessing()
    executor.runNext()

    val failedRun = handle.findRun(submission.runId)
    assertEquals(ExecutionStatus.FAILED, failedRun?.executionStatus)
    assertEquals("TRANSIENT_FAILURE", failedRun?.errorCode)
    assertTrue(failedRun?.isTerminal == true)

    assertTrue(handle.requestRetry(submission.taskId))

    val queuedRetryRun = handle.findRun(submission.runId)
    assertEquals(QueueTaskLifecycleState.QUEUED, queuedRetryRun?.lifecycleState)
    assertEquals(null, queuedRetryRun?.executionStatus)
    assertEquals(null, queuedRetryRun?.errorCode)
    assertEquals(null, queuedRetryRun?.errorMessage)
    assertTrue(queuedRetryRun?.isTerminal == false)

    executor.runNext()

    val completedRun = handle.findRun(submission.runId)
    assertEquals(ExecutionStatus.SUCCESS, completedRun?.executionStatus)
    assertEquals(QueueTaskLifecycleState.COMPLETED, completedRun?.lifecycleState)
  }

  @Test
  fun requestCancelForPendingMessageIdsMatchesHiddenHostMetadata() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory()
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
    )
    val handle = manager.forSession("session-cancel")

    val firstRun = handle.submitPrompt(
      userText = "first prompt",
      pendingMessageId = "pending-1",
      visibleThroughMessageId = "pending-1",
      policyDecision = allowDecision(),
    )
    handle.submitPrompt(
      userText = "second prompt",
      pendingMessageId = "pending-2",
      visibleThroughMessageId = "pending-2",
      policyDecision = allowDecision(),
    )

    assertEquals(1, handle.requestCancelForPendingMessageIds(setOf("pending-2")))

    val cancelledSnapshot = handle.snapshot()
    assertEquals(
      listOf(QueueTaskLifecycleState.QUEUED, QueueTaskLifecycleState.CANCELLED),
      cancelledSnapshot.tasks.map { it.lifecycleState },
    )

    handle.ensureProcessing()
    executor.runNext()

    val completedSnapshot = handle.snapshot()
    assertEquals(
      listOf(QueueTaskLifecycleState.COMPLETED, QueueTaskLifecycleState.CANCELLED),
      completedSnapshot.tasks.map { it.lifecycleState },
    )
    assertEquals(listOf("first prompt"), runtimeFactory.executedInputs)
    assertEquals(firstRun.runId, handle.listRuns().last().runId)
  }

  @Test
  fun listenersReceiveRunEventsFromUnderlyingRuntime() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      onExecute = { task, eventSink ->
        eventSink.onRunEvent(
          task,
          OpenCrayLifecycleEvent(
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty(),
            taskId = task.id,
            phase = OpenCrayRunLifecyclePhase.START,
            emittedAtEpochMs = 10L,
          ),
        )
        eventSink.onRunEvent(
          task,
          OpenCrayAssistantEvent(
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty(),
            taskId = task.id,
            turn = 0,
            text = "working",
            responseFormat = "json_final",
            isFinal = true,
            emittedAtEpochMs = 11L,
          ),
        )
      },
    )
    val manager = manager(runtimeFactory = runtimeFactory, executor = executor)
    val handle = manager.forSession("session-events")
    val observed = mutableListOf<OpenCrayAgentRunEvent>()
    manager.observe(
      object : AgentSessionRuntimeListener {
        override fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) {
          observed += event
        }
      },
    )

    val submission = handle.submitPrompt(
      userText = "event prompt",
      pendingMessageId = "pending-event",
      visibleThroughMessageId = "pending-event",
      policyDecision = allowDecision(),
    )
    handle.ensureProcessing()

    executor.runNext()

    assertEquals(listOf("lifecycle", "assistant"), observed.map { event ->
      when (event) {
        is OpenCrayLifecycleEvent -> "lifecycle"
        is OpenCrayAssistantEvent -> "assistant"
        else -> "other"
      }
    })
    assertEquals(submission.runId, observed.first().runId)
    assertEquals(submission.runId, handle.waitForRun(submission.runId, 0L)?.runId)
  }

  @Test
  fun runtimeEventsPersistToJournalForProjectionBackedSessions() {
    val sessionId = "session-runtime-event-journal"
    val executor = RecordingExecutorService()
    val journalFactory = FileBackedRunEventJournalStoreFactory(temporaryFolder.root)
    val runtimeFactory = RecordingRuntimeFactory(
      onExecute = { task, eventSink ->
        eventSink.onRunEvent(
          task,
          OpenCrayAssistantEvent(
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty(),
            taskId = task.id,
            turn = 0,
            text = "Scanning the latest release notes.",
            stage = "Planning",
            emittedAtEpochMs = 10L,
          ),
        )
      },
    )
    val manager = manager(
      runtimeFactory = runtimeFactory,
      executor = executor,
      runEventJournalStoreFactory = journalFactory,
    )
    val handle = manager.forSession(sessionId)

    val submission = handle.submitPrompt(
      userText = "check the release notes",
      pendingMessageId = "pending-runtime-event-journal",
      visibleThroughMessageId = "pending-runtime-event-journal",
      policyDecision = allowDecision(),
    )
    handle.ensureProcessing()

    executor.runNext()

    val persistedEvent = journalFactory.forChatSession(sessionId)
      .listRuntimeEvents()
      .filterIsInstance<OpenCrayAssistantEvent>()
      .single()
    assertEquals(submission.runId, persistedEvent.runId)
    assertEquals(submission.taskId, persistedEvent.taskId)
    assertEquals("Scanning the latest release notes.", persistedEvent.text)
    assertEquals("Planning", persistedEvent.stage)
  }

  @Test
  fun completedRunSnapshotRetainsResultMetadataForHostTraceProjection() {
    val executor = RecordingExecutorService()
    val runtimeFactory = RecordingRuntimeFactory(
      executionResultFactory = { task ->
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          stdout = "done",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
          metadata = task.metadata + mapOf(
            "responseFormat" to "json_final",
            "contextMatchedMemoryCount" to "2",
            "contextMemorySelectedSummary" to "memory-user@420[chinese]",
          ),
        )
      },
    )
    val manager = manager(runtimeFactory = runtimeFactory, executor = executor)
    val handle = manager.forSession("session-metadata")

    handle.submitPrompt(
      userText = "trace metadata",
      pendingMessageId = "pending-trace",
      visibleThroughMessageId = "pending-trace",
      policyDecision = allowDecision(),
    )
    handle.ensureProcessing()
    executor.runNext()

    val snapshot = handle.listRuns().single()

    assertEquals("json_final", snapshot.responseFormat)
    assertEquals("2", snapshot.resultMetadata["contextMatchedMemoryCount"])
    assertEquals("memory-user@420[chinese]", snapshot.resultMetadata["contextMemorySelectedSummary"])
  }

  @Test
  fun runSnapshotTreatsExecutionResultAsTerminalBeforeQueueLifecycleSettles() {
    val snapshot = AgentRunSnapshot(
      sessionId = "session-terminal-race",
      runId = "run-terminal-race",
      taskId = "task-terminal-race",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_001L,
      lifecycleState = null,
      taskState = null,
      executionStatus = ExecutionStatus.SUCCESS,
    )

    assertTrue(snapshot.isTerminal)
  }

  @Test
  fun runSnapshotTreatsRunningLifecycleAsTerminalWhenExecutionAlreadySucceeded() {
    val snapshot = AgentRunSnapshot(
      sessionId = "session-running-success",
      runId = "run-running-success",
      taskId = "task-running-success",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_001L,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
      taskState = AgentTaskState.RUNNING,
      executionStatus = ExecutionStatus.SUCCESS,
    )

    assertTrue(snapshot.isTerminal)
  }

  @Test
  fun runSnapshotKeepsApprovalRequiredDenialNonTerminalBeforeQueueSuspends() {
    val snapshot = AgentRunSnapshot(
      sessionId = "session-approval-race",
      runId = "run-approval-race",
      taskId = "task-approval-race",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_001L,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
      taskState = AgentTaskState.RUNNING,
      executionStatus = ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
    )

    assertTrue(!snapshot.isTerminal)
  }

  @Test
  fun runSnapshotKeepsRetryPendingLifecycleNonTerminalAfterFailureResult() {
    val snapshot = AgentRunSnapshot(
      sessionId = "session-retry-pending",
      runId = "run-retry-pending",
      taskId = "task-retry-pending",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_001L,
      lifecycleState = QueueTaskLifecycleState.RETRY_PENDING,
      taskState = AgentTaskState.QUEUED,
      executionStatus = ExecutionStatus.FAILED,
      errorCode = "TRANSIENT_FAILURE",
    )

    assertTrue(!snapshot.isTerminal)
  }

  @Test
  fun runSnapshotTreatsSuspendedLifecycleAsNonTerminal() {
    val snapshot = AgentRunSnapshot(
      sessionId = "session-suspended",
      runId = "run-suspended",
      taskId = "task-suspended",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_001L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = null,
      executionStatus = ExecutionStatus.DENIED,
    )

    assertTrue(!snapshot.isTerminal)
  }

  private fun manager(
    runtimeFactory: AgentSessionTaskRuntimeFactory,
    executor: RecordingExecutorService,
    subAgentRecoveryExecutor: RecordingExecutorService = executor,
    runEventJournalStoreFactory: RunEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(temporaryFolder.root),
    promptCheckpointStoreFactory: PromptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(temporaryFolder.root),
  ): DefaultAgentSessionRuntimeManager = DefaultAgentSessionRuntimeManager(
    agentId = "opencray-app",
    runtimeFactory = runtimeFactory,
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(temporaryFolder.root),
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    executor = executor,
    subAgentRecoveryExecutor = subAgentRecoveryExecutor,
  )

  private fun overwriteQueueSnapshot(
    sessionId: String,
    snapshot: SessionQueueSnapshot,
    taskId: String,
    lifecycleState: QueueTaskLifecycleState,
  ) {
    val taskState = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> AgentTaskState.QUEUED

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> AgentTaskState.RUNNING

      QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    }
    FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root)
      .forChatSession(sessionId)
      .save(
        snapshot.copy(
          tasks = snapshot.tasks.map { taskSnapshot ->
            if (taskSnapshot.task.id != taskId) {
              taskSnapshot
            } else {
              taskSnapshot.copy(
                lifecycleState = lifecycleState,
                task = taskSnapshot.task.copy(
                  state = taskState,
                  updatedAtEpochMs = maxOf(taskSnapshot.task.updatedAtEpochMs, 2_000L),
                ),
              )
            }
          },
          updatedAtEpochMs = maxOf(snapshot.updatedAtEpochMs, 2_000L),
        ),
      )
  }

  private fun allowDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "TEST_ALLOW",
  )

  private fun managedProcessSnapshot(
    processId: String,
    taskId: String,
    status: ManagedProcessStatus,
  ): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = processId,
    taskId = taskId,
    command = "npm",
    args = listOf("run", "dev"),
    workingDirectory = ".",
    status = status,
    processStarted = true,
    timeoutMs = 120_000L,
    startedAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_001L,
    finishedAtEpochMs = if (status.isTerminal) 1_001L else null,
  )

  private fun backgroundSubAgentHandle(
    agentId: String,
  ): SubAgentHandleState = SubAgentHandleState.queued(
    agentId = agentId,
    childRunId = "$agentId-run",
    childTaskId = "$agentId-task",
    description = "inspect readme",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run",
    parentTaskId = "parent-task",
    parentTurn = 0,
    depth = 1,
    activeSkillName = null,
    activeSkillActivationSource = null,
    createdAtEpochMs = 1_000L,
  ).copy(
    snapshot = SubAgentExecutionSnapshot.backgroundRunning(),
    updatedAtEpochMs = 1_100L,
  )

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
    startedAtEpochMs = 100L,
    finishedAtEpochMs = 100L,
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

  private class RecordingRuntimeFactory(
    private val onExecute: ((AgentTask, OpenCrayAgentRuntimeEventSink) -> Unit)? = null,
    private val executionResultFactory: (AgentTask) -> ExecutionResult = { task ->
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "ok:${task.input}",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      )
    },
    private val detachedControlResultFactory: ((AgentTask) -> ExecutionResult)? = null,
    private val subAgentRecoveryResultFactory: ((AgentTask) -> ExecutionResult)? = null,
    private val managedProcessesProvider: (String) -> List<ManagedProcessSnapshot> = { emptyList() },
    private val readManagedProcessHandler: (String, String) -> ManagedProcessSnapshot? = { _, _ -> null },
    private val subAgentHandlesProvider: (String) -> List<SubAgentHandleState> = { emptyList() },
    private val terminateManagedProcessHandler: (String, String) -> ManagedProcessSnapshot? = { _, _ -> null },
  ) : AgentSessionTaskRuntimeFactory {
    val executedInputs = mutableListOf<String>()
    val detachedControlInputs = mutableListOf<String>()
    val subAgentRecoveryInputs = mutableListOf<String>()
    val releasedSessions = mutableListOf<String>()

    override fun create(
      sessionId: String,
      eventSink: OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task: AgentTask, _: RuntimeExecutionHooks ->
      onExecute?.invoke(task, eventSink)
      executedInputs += task.input
      executionResultFactory(task)
    }

    override fun listManagedProcesses(sessionId: String): List<ManagedProcessSnapshot> =
      managedProcessesProvider(sessionId)

    override fun readManagedProcess(
      sessionId: String,
      processId: String,
    ): ManagedProcessSnapshot? = readManagedProcessHandler(sessionId, processId)

    override fun releaseSession(sessionId: String) {
      releasedSessions += sessionId
    }

    override fun executeDetachedControlTask(
      sessionId: String,
      task: AgentTask,
      hooks: RuntimeExecutionHooks,
      eventSink: OpenCrayAgentRuntimeEventSink,
    ): ExecutionResult? {
      val handler = detachedControlResultFactory ?: return null
      onExecute?.invoke(task, eventSink)
      detachedControlInputs += task.input
      return handler(task)
    }

    override fun executeDetachedSubAgentRecoveryTask(
      sessionId: String,
      task: AgentTask,
      hooks: RuntimeExecutionHooks,
      eventSink: OpenCrayAgentRuntimeEventSink,
      agentId: String,
      parentRunId: String,
    ): ExecutionResult? {
      val handler = subAgentRecoveryResultFactory
      if (handler != null) {
        onExecute?.invoke(task, eventSink)
        subAgentRecoveryInputs += task.input
        return handler(task)
      }
      return executeDetachedControlTask(
        sessionId = sessionId,
        task = task,
        hooks = hooks,
        eventSink = eventSink,
      )
    }

    override fun listSubAgentHandles(sessionId: String): List<SubAgentHandleState> =
      subAgentHandlesProvider(sessionId)

    override fun terminateManagedProcess(
      sessionId: String,
      processId: String,
    ): ManagedProcessSnapshot? = terminateManagedProcessHandler(sessionId, processId)
  }

  private class RecordingExecutorService : AbstractExecutorService() {
    private val runnables = ArrayDeque<Runnable>()
    private var shutdown = false

    override fun execute(command: Runnable) {
      check(!shutdown) { "Executor already shut down." }
      runnables += command
    }

    fun pendingCount(): Int = runnables.size

    fun runNext() {
      runnables.removeFirstOrNull()?.run()
    }

    override fun shutdown() {
      shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
      shutdown = true
      val pending = runnables.toMutableList()
      runnables.clear()
      return pending
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && runnables.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
  }
}
