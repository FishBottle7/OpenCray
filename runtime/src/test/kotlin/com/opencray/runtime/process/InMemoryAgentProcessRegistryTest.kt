package com.opencray.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryAgentProcessRegistryTest {
  @Test
  fun startReadWaitAndTerminateDelegateToTrackedController() {
    val requests = mutableListOf<ManagedProcessStartRequest>()
    val controller = FakeManagedProcessController(
      snapshot = ManagedProcessSnapshot(
        processId = "proc-1",
        taskId = "task-1",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
      ),
      awaitSnapshot = ManagedProcessSnapshot(
        processId = "proc-1",
        taskId = "task-1",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = ManagedProcessStatus.SUCCESS,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "server ready",
        exitCode = 0,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
        finishedAtEpochMs = 1_100L,
      ),
      terminateSnapshot = ManagedProcessSnapshot(
        processId = "proc-1",
        taskId = "task-1",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = ManagedProcessStatus.CANCELLED,
        processStarted = true,
        timeoutMs = 120_000L,
        exitCode = 137,
        errorCode = "CANCELLED",
        errorMessage = "Managed process terminated.",
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_150L,
        finishedAtEpochMs = 1_150L,
        cancelled = true,
      ),
    )
    val registry = InMemoryAgentProcessRegistry(
      controllerFactory = ManagedProcessControllerFactory { request ->
        requests += request
        controller
      },
    )

    val started = registry.start(
      ManagedProcessStartRequest(
        processId = "proc-1",
        taskId = "task-1",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 900L,
      ),
    )
    val listed = registry.list()
    val read = registry.read("proc-1")
    val waited = registry.wait("proc-1", 250L)
    val terminated = registry.terminate("proc-1")

    assertEquals(1, requests.size)
    assertEquals("npm", requests.single().command)
    assertEquals(ManagedProcessStatus.RUNNING, started.status)
    assertEquals(1, listed.size)
    assertEquals(ManagedProcessStatus.RUNNING, listed.single().status)
    assertNotNull(read)
    assertEquals(ManagedProcessStatus.RUNNING, read!!.status)
    assertEquals(listOf(250L), controller.awaitTimeouts)
    assertEquals(ManagedProcessStatus.SUCCESS, waited!!.status)
    assertEquals(1, controller.terminateCalls)
    assertEquals(ManagedProcessStatus.CANCELLED, terminated!!.status)
  }

  @Test
  fun registryDropsOldestTerminalProcessWhenCapacityIsExceeded() {
    val registry = InMemoryAgentProcessRegistry(
      controllerFactory = SequencedControllerFactory(
        controllers = ArrayDeque(
          listOf(
            FakeManagedProcessController(
              snapshot = snapshotFor(processId = "proc-terminal", status = ManagedProcessStatus.SUCCESS),
            ),
            FakeManagedProcessController(
              snapshot = snapshotFor(processId = "proc-running", status = ManagedProcessStatus.RUNNING),
            ),
          ),
        ),
      ),
      config = AgentProcessRegistryConfig(maxTrackedProcesses = 1),
    )

    registry.start(startRequestFor(processId = "proc-terminal"))
    registry.start(startRequestFor(processId = "proc-running"))

    val snapshots = registry.list()

    assertEquals(1, snapshots.size)
    assertEquals("proc-running", snapshots.single().processId)
    assertNull(registry.read("proc-terminal"))
    assertTrue(registry.read("proc-running") != null)
  }

  // [W-08] RUNNING 一律豁免淘汰；全活跃满载时拒绝新注册（fail-closed）
  @Test
  fun runningProcessesAreExemptFromTrimAndFullActiveRegistryRejectsNewStart() {
    val registry = InMemoryAgentProcessRegistry(
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = snapshotFor(processId = request.processId, status = ManagedProcessStatus.RUNNING),
          awaitSnapshot = snapshotFor(processId = request.processId, status = ManagedProcessStatus.SUCCESS),
        )
      },
      config = AgentProcessRegistryConfig(maxTrackedProcesses = 2),
    )

    registry.start(startRequestFor(processId = "proc-a"))
    registry.start(startRequestFor(processId = "proc-b"))
    val rejection = runCatching { registry.start(startRequestFor(processId = "proc-c")) }
      .exceptionOrNull()

    assertTrue(rejection is IllegalArgumentException)
    val rejectionMessage = requireNotNull(rejection!!.message)
    assertTrue(rejectionMessage.contains("(2/2 tracked)"))
    assertTrue(rejectionMessage.contains("still active"))
    assertEquals(setOf("proc-a", "proc-b"), registry.list().map { it.processId }.toSet())
    assertNull(registry.read("proc-c"))

    registry.wait("proc-a", 0L)
    registry.start(startRequestFor(processId = "proc-c"))

    assertEquals(
      setOf("proc-b", "proc-c"),
      registry.list().map { it.processId }.toSet(),
    )
    assertNull(registry.read("proc-a"))
  }

  // [W-09] 先查重后创建：ID 碰撞时第二次 start 不触发 factory，首个进程不受影响
  @Test
  fun duplicateProcessIdIsRejectedBeforeSecondControllerCreation() {
    val requestedProcessIds = mutableListOf<String>()
    val firstController = FakeManagedProcessController(
      snapshot = snapshotFor(processId = "proc-dup", status = ManagedProcessStatus.RUNNING),
    )
    val registry = InMemoryAgentProcessRegistry(
      controllerFactory = ManagedProcessControllerFactory { request ->
        requestedProcessIds += request.processId
        firstController
      },
    )

    registry.start(startRequestFor(processId = "proc-dup"))
    val rejection = runCatching { registry.start(startRequestFor(processId = "proc-dup")) }
      .exceptionOrNull()

    assertTrue(rejection is IllegalArgumentException)
    assertTrue(requireNotNull(rejection!!.message).contains("'proc-dup' already exists"))
    assertEquals(listOf("proc-dup"), requestedProcessIds)
    assertEquals(0, firstController.terminateCalls)
    assertEquals(
      ManagedProcessStatus.RUNNING,
      registry.read("proc-dup")?.status,
    )
  }

  private fun startRequestFor(processId: String): ManagedProcessStartRequest = ManagedProcessStartRequest(
    processId = processId,
    taskId = "task-$processId",
    command = "echo",
    timeoutMs = 30_000L,
    requestedAtEpochMs = 1_000L,
  )

  private fun snapshotFor(
    processId: String,
    status: ManagedProcessStatus,
  ): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = processId,
    taskId = "task-$processId",
    command = "echo",
    status = status,
    processStarted = true,
    timeoutMs = 30_000L,
    startedAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_000L,
    finishedAtEpochMs = if (status.isTerminal) 1_001L else null,
  )

  private class SequencedControllerFactory(
    private val controllers: ArrayDeque<FakeManagedProcessController>,
  ) : ManagedProcessControllerFactory {
    override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
      controllers.removeFirstOrNull() ?: error("No fake controller left for ${request.processId}.")
  }

  private class FakeManagedProcessController(
    snapshot: ManagedProcessSnapshot,
    private val awaitSnapshot: ManagedProcessSnapshot = snapshot,
    private val terminateSnapshot: ManagedProcessSnapshot = snapshot,
  ) : ManagedProcessController {
    private var currentSnapshot: ManagedProcessSnapshot = snapshot
    val awaitTimeouts = mutableListOf<Long>()
    var terminateCalls: Int = 0

    override fun snapshot(): ManagedProcessSnapshot = currentSnapshot

    override fun await(timeoutMs: Long): ManagedProcessSnapshot {
      awaitTimeouts += timeoutMs
      currentSnapshot = awaitSnapshot
      return currentSnapshot
    }

    override fun terminate(): ManagedProcessSnapshot {
      terminateCalls += 1
      currentSnapshot = terminateSnapshot
      return currentSnapshot
    }
  }
}
