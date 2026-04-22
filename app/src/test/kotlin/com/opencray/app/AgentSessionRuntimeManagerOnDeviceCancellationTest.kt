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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSessionRuntimeManagerOnDeviceCancellationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun requestCancelInterruptsActiveProcessingThreadForRunningPrompt() {
    val started = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val cancellationObserved = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()

    try {
      val root = temporaryFolder.newFolder("runtime-manager-on-device-cancel")
      val manager = DefaultAgentSessionRuntimeManager(
        agentId = "opencray-app",
        runtimeFactory = BlockingRuntimeFactory(
          started = started,
          interrupted = interrupted,
          cancellationObserved = cancellationObserved,
        ),
        snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(root),
        runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(root),
        executor = executor,
      )
      val handle = manager.forSession("session-on-device-cancel")
      val submission = handle.submitPrompt(
        userText = "interrupt local inference",
        pendingMessageId = "pending-on-device-cancel",
        visibleThroughMessageId = "pending-on-device-cancel",
        policyDecision = allowDecision(),
      )

      handle.ensureProcessing()

      assertTrue(started.await(2, TimeUnit.SECONDS))
      assertTrue(handle.requestCancel(submission.taskId))
      assertTrue(interrupted.await(2, TimeUnit.SECONDS))

      val cancelledRun = handle.waitForRun(submission.runId, 2_000L)

      assertNotNull(cancelledRun)
      assertTrue(cancellationObserved.get())
      assertEquals(ExecutionStatus.CANCELLED, cancelledRun?.executionStatus)
      assertEquals(QueueTaskLifecycleState.CANCELLED, cancelledRun?.lifecycleState)
    } finally {
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }

  private fun allowDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "TEST_ALLOW",
  )

  private class BlockingRuntimeFactory(
    private val started: CountDownLatch,
    private val interrupted: CountDownLatch,
    private val cancellationObserved: AtomicBoolean,
  ) : AgentSessionTaskRuntimeFactory {
    override fun create(
      sessionId: String,
      eventSink: OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task: AgentTask, hooks: RuntimeExecutionHooks ->
      started.countDown()
      try {
        while (true) {
          Thread.sleep(5_000L)
        }
        @Suppress("UNREACHABLE_CODE")
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.SUCCESS,
          startedAtEpochMs = 0L,
          finishedAtEpochMs = 0L,
        )
      } catch (_: InterruptedException) {
        cancellationObserved.set(hooks.isCancellationRequested())
        interrupted.countDown()
        val finishedAt = System.currentTimeMillis()
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.CANCELLED,
          errorCode = LiteRtOnDeviceFailureCodes.LOCAL_INFERENCE_CANCELLED,
          errorMessage = "LiteRT-LM inference was interrupted locally.",
          startedAtEpochMs = finishedAt,
          finishedAtEpochMs = finishedAt,
        )
      }
    }
  }
}
