package com.opencray.runtime.process

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalManagedProcessControllerTest {

  @Test
  fun quickCommandCompletesWithSuccessAndNoSurvivorMetadata() {
    val baselineThreads = managedProcessThreadCount()
    val controller = LocalManagedProcessControllerFactory().start(quickEchoRequest())

    val snapshot = controller.await(30_000L)

    assertEquals(ManagedProcessStatus.SUCCESS, snapshot.status)
    assertEquals(0, snapshot.exitCode)
    assertTrue(snapshot.stdout.contains(ECHO_MARKER))
    assertTrue(snapshot.metadata["suspectedOrphanDescendants"] == null)
    assertTrue(processThreadsReclaimed(baselineThreads))
  }

  @Test
  fun timeoutDestroysSpawningShellReclaimsCollectorThreadsAndReportsTruthfully() {
    val baselineThreads = managedProcessThreadCount()
    val controller = LocalManagedProcessControllerFactory().start(
      loopingShellRequest(timeoutMs = 500L),
    )

    val snapshot = controller.await(30_000L)

    assertEquals(ManagedProcessStatus.TIMEOUT, snapshot.status)
    assertTrue(snapshot.timedOut)
    assertEquals("TIMEOUT", snapshot.errorCode)
    assertNotNull(snapshot.finishedAtEpochMs)
    assertTrue(snapshot.exitCode != null || snapshot.metadata["terminationUnconfirmed"] == "true")
    assertTrue(processThreadsReclaimed(baselineThreads))
  }

  @Test
  fun cancellationCheckStopsRunningProcessWithCancelledStatus() {
    val baselineThreads = managedProcessThreadCount()
    val cancelled = AtomicBoolean(false)
    val controller = LocalManagedProcessControllerFactory(
      cancellationCheckFor = { _ -> ManagedProcessCancellationCheck { cancelled.get() } },
    ).start(loopingShellRequest(timeoutMs = 120_000L))

    cancelled.set(true)
    val snapshot = controller.await(30_000L)

    assertEquals(ManagedProcessStatus.CANCELLED, snapshot.status)
    assertTrue(snapshot.cancelled)
    assertEquals("CANCELLED", snapshot.errorCode)
    assertNotNull(snapshot.finishedAtEpochMs)
    assertTrue(snapshot.exitCode != null || snapshot.metadata["terminationUnconfirmed"] == "true")
    assertTrue(processThreadsReclaimed(baselineThreads))
  }

  @Test
  fun ambientRegistryCancellationCheckStopsRunningProcess() {
    val baselineThreads = managedProcessThreadCount()
    val registration = ManagedProcessCancellationRegistry.register("task-ambient-cancel") { true }
    try {
      val controller = LocalManagedProcessControllerFactory().start(
        loopingShellRequest(
          processIdSuffix = "ambient",
          taskId = "task-ambient-cancel",
          timeoutMs = 120_000L,
        ),
      )

      val snapshot = controller.await(30_000L)

      assertEquals(ManagedProcessStatus.CANCELLED, snapshot.status)
      assertTrue(snapshot.cancelled)
      assertEquals("CANCELLED", snapshot.errorCode)
      assertTrue(processThreadsReclaimed(baselineThreads))
    } finally {
      registration.close()
      ManagedProcessCancellationRegistry.clearForTest()
    }
  }

  @Test
  fun terminateReturnsCancelledSnapshotWithDeadProcessAndReclaimedThreads() {
    val baselineThreads = managedProcessThreadCount()
    val controller = LocalManagedProcessControllerFactory().start(
      loopingShellRequest(processIdSuffix = "terminate", timeoutMs = 120_000L),
    )
    Thread.sleep(300L)

    val snapshot = controller.terminate()

    assertEquals(ManagedProcessStatus.CANCELLED, snapshot.status)
    assertTrue(snapshot.cancelled)
    assertNotNull(snapshot.exitCode)
    assertTrue(processThreadsReclaimed(baselineThreads))
  }

  private fun quickEchoRequest(): ManagedProcessStartRequest {
    val (command, args) = if (isWindowsPlatform()) {
      "powershell.exe" to listOf("-NoProfile", "-Command", "Write-Output '$ECHO_MARKER'")
    } else {
      "sh" to listOf("-lc", "echo $ECHO_MARKER")
    }
    return ManagedProcessStartRequest(
      processId = "proc-echo",
      taskId = "task-echo",
      command = command,
      args = args,
      timeoutMs = 30_000L,
      requestedAtEpochMs = System.currentTimeMillis(),
    )
  }

  private fun loopingShellRequest(
    processIdSuffix: String = "loop",
    taskId: String = "task-$processIdSuffix",
    timeoutMs: Long,
  ): ManagedProcessStartRequest {
    val (command, args) = loopingShellCommandLine()
    return ManagedProcessStartRequest(
      processId = "proc-$processIdSuffix",
      taskId = taskId,
      command = command,
      args = args,
      timeoutMs = timeoutMs,
      requestedAtEpochMs = System.currentTimeMillis(),
    )
  }

  private fun managedProcessThreadCount(): Int =
    Thread.getAllStackTraces().keys.count { thread ->
      thread.name.startsWith("managed-process-stdout-") ||
        thread.name.startsWith("managed-process-stderr-") ||
        thread.name.startsWith("managed-process-watch-")
    }

  private fun processThreadsReclaimed(baseline: Int): Boolean {
    val deadline = System.currentTimeMillis() + 10_000L
    while (System.currentTimeMillis() < deadline) {
      if (managedProcessThreadCount() <= baseline) {
        return true
      }
      Thread.sleep(50L)
    }
    val leaked = Thread.getAllStackTraces().filterKeys { thread ->
      thread.name.startsWith("managed-process-")
    }
    leaked.forEach { (thread, stack) ->
      println("LEAK_DIAG ${thread.name} state=${thread.state} daemon=${thread.isDaemon}")
      stack.take(5).forEach { println("  at $it") }
    }
    return managedProcessThreadCount() <= baseline
  }

  internal companion object {
    internal const val ECHO_MARKER: String = "controller-ok"

    internal fun isWindowsPlatform(): Boolean =
      System.getProperty("os.name").orEmpty().lowercase().contains("win")

    internal fun loopingShellCommandLine(): Pair<String, List<String>> = if (isWindowsPlatform()) {
      "cmd.exe" to listOf("/c", "ping -n 120 127.0.0.1")
    } else {
      "sh" to listOf("-lc", "while true; do echo tick; sleep 0.05; done")
    }
  }
}
