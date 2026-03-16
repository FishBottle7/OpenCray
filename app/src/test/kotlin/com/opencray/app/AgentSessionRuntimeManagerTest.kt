package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSessionRuntimeManagerTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

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
              errorCode = ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE,
              errorMessage = "Managed process state was restored without a live controller; marking it interrupted.",
              updatedAtEpochMs = 1_002L,
              finishedAtEpochMs = 1_002L,
              metadata = mapOf(
                METADATA_RESTORED_FROM_DURABLE_STORE to "true",
                METADATA_RESTORED_TERMINAL_STATE to RESTORED_TERMINAL_STATE_INTERRUPTED,
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
    assertEquals(ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE, restoredRun?.errorCode)
    assertEquals(QueueTaskLifecycleState.FAILED, restoredRun?.lifecycleState)
    assertEquals(AgentTaskState.FAILED, restoredRun?.taskState)
    assertEquals(
      RESTORED_TERMINAL_STATE_INTERRUPTED,
      restoredRun?.resultMetadata?.get(METADATA_RESTORED_TERMINAL_STATE),
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
    assertEquals(ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE, persistedRun?.errorCode)
    assertEquals(listOf("proc-restored"), persistedRun?.managedProcessIds)
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
  ): DefaultAgentSessionRuntimeManager = DefaultAgentSessionRuntimeManager(
    agentId = "opencray-app",
    runtimeFactory = runtimeFactory,
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(temporaryFolder.root),
    executor = executor,
  )

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
    private val managedProcessesProvider: (String) -> List<ManagedProcessSnapshot> = { emptyList() },
    private val terminateManagedProcessHandler: (String, String) -> ManagedProcessSnapshot? = { _, _ -> null },
  ) : AgentSessionTaskRuntimeFactory {
    val executedInputs = mutableListOf<String>()

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
