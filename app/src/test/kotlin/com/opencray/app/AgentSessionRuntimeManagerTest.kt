package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    handle.submitPrompt(
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
    firstManager.forSession(sessionId).submitPrompt(
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

    handle.submitPrompt(
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
  }

  private fun manager(
    runtimeFactory: AgentSessionTaskRuntimeFactory,
    executor: RecordingExecutorService,
  ): DefaultAgentSessionRuntimeManager = DefaultAgentSessionRuntimeManager(
    agentId = "opencray-app",
    runtimeFactory = runtimeFactory,
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(temporaryFolder.root),
    executor = executor,
  )

  private fun allowDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "TEST_ALLOW",
  )

  private class RecordingRuntimeFactory : AgentSessionTaskRuntimeFactory {
    val executedInputs = mutableListOf<String>()

    override fun create(
      sessionId: String,
      eventSink: OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task: AgentTask, _: RuntimeExecutionHooks ->
      executedInputs += task.input
      ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "ok:${task.input}",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      )
    }
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
