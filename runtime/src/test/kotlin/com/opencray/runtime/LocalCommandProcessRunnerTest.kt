package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.process.LocalManagedProcessControllerTest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandProcessRunnerTest {

  @Test
  fun successCommandCompletesWithoutSurvivorMetadataOrLeakedThreads() {
    val baselineThreads = runnerThreadCount()
    val executor = CommandExecutor(config = CommandExecutionConfig(timeoutMs = 30_000L))
    val (command, args) = quickEchoCommandLine()

    val result = executor.execute(
      request = CommandExecutionRequest(
        taskId = "runner-success",
        command = command,
        args = args,
        requestedAtEpochMs = System.currentTimeMillis(),
      ),
      policyDecision = allowDecision(),
      hooks = hooksOf(cancelled = AtomicBoolean(false)),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains(ECHO_MARKER))
    assertTrue(result.metadata["suspectedOrphanDescendants"] == null)
    assertTrue(runnerThreadsReclaimed(baselineThreads))
  }

  @Test
  fun timeoutDestroysShellTreeReclaimsCollectorThreadsAndMapsTimeoutStatus() {
    val baselineThreads = runnerThreadCount()
    val executor = CommandExecutor(config = CommandExecutionConfig(timeoutMs = 500L))
    val (command, args) = loopingShellCommandLine()
    val startedAt = System.currentTimeMillis()

    val result = executor.execute(
      request = CommandExecutionRequest(
        taskId = "runner-timeout",
        command = command,
        args = args,
        requestedAtEpochMs = startedAt,
      ),
      policyDecision = allowDecision(),
      hooks = hooksOf(cancelled = AtomicBoolean(false)),
    )

    assertEquals(ExecutionStatus.TIMEOUT, result.status)
    assertEquals("TIMEOUT", result.errorCode)
    assertTrue(System.currentTimeMillis() - startedAt < 30_000L)
    assertTrue(runnerThreadsReclaimed(baselineThreads))
  }

  @Test
  fun midRunCancellationDestroysProcessAndMapsCancelledStatus() {
    val baselineThreads = runnerThreadCount()
    val cancelled = AtomicBoolean(false)
    Thread {
      Thread.sleep(400L)
      cancelled.set(true)
    }.apply {
      isDaemon = true
      start()
    }
    val executor = CommandExecutor(config = CommandExecutionConfig(timeoutMs = 60_000L))
    val (command, args) = loopingShellCommandLine()

    val result = executor.execute(
      request = CommandExecutionRequest(
        taskId = "runner-cancel",
        command = command,
        args = args,
        requestedAtEpochMs = System.currentTimeMillis(),
      ),
      policyDecision = allowDecision(),
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { cancelled.get() },
        requestRetry = { _ -> },
      ),
    )

    assertEquals(ExecutionStatus.CANCELLED, result.status)
    assertEquals("CANCELLED_BY_HOOK", result.errorCode)
    assertTrue(runnerThreadsReclaimed(baselineThreads))
  }

  private fun allowDecision(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "ALLOW_SAFE_COMMAND",
    detail = "Allowed for LocalCommandProcessRunner tests.",
  )

  private fun hooksOf(cancelled: AtomicBoolean): RuntimeExecutionHooks =
    RuntimeExecutionHooks(
      isCancellationRequested = { cancelled.get() },
      requestRetry = { _ -> },
    )

  private fun quickEchoCommandLine(): Pair<String, List<String>> =
    if (LocalManagedProcessControllerTest.isWindowsPlatform()) {
      "powershell.exe" to listOf(
        "-NoProfile",
        "-Command",
        "Write-Output '$ECHO_MARKER'",
      )
    } else {
      "sh" to listOf("-lc", "echo $ECHO_MARKER")
    }

  private fun loopingShellCommandLine(): Pair<String, List<String>> =
    LocalManagedProcessControllerTest.loopingShellCommandLine()

  private fun runnerThreadCount(): Int =
    Thread.getAllStackTraces().keys.count { thread ->
      thread.name == "command-runner-stdout" || thread.name == "command-runner-stderr"
    }

  private fun runnerThreadsReclaimed(baseline: Int): Boolean {
    val deadline = System.currentTimeMillis() + 10_000L
    while (System.currentTimeMillis() < deadline) {
      if (runnerThreadCount() <= baseline) {
        return true
      }
      Thread.sleep(50L)
    }
    return runnerThreadCount() <= baseline
  }

  internal companion object {
    internal const val ECHO_MARKER: String = "runner-ok"
  }
}
