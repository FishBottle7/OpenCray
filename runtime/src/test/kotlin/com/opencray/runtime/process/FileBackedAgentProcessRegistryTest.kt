package com.opencray.runtime.process

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedAgentProcessRegistryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @After
  fun tearDown() {
    ManagedProcessControllerRegistry.clearForTest()
  }

  @Test
  fun sameDirectoryRestoresPersistedTerminalSnapshot() {
    val directory = temporaryFolder.newFolder("durable-process-registry")
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory {
        FakeManagedProcessController(
          snapshot = runningSnapshot(processId = it.processId, taskId = it.taskId),
          awaitSnapshot = successSnapshot(processId = it.processId, taskId = it.taskId),
        )
      },
    )

    registry.start(
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
    registry.wait("proc-1", 250L)

    val restored = FileBackedAgentProcessRegistry(directory = directory).read("proc-1")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.SUCCESS, restored!!.status)
    assertEquals("server ready", restored.stdout)
    assertEquals(0, restored.exitCode)
  }

  @Test
  fun restoredRunningSnapshotIsRepairedToInterruptedFailure() {
    val directory = temporaryFolder.newFolder("durable-process-registry-running")
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory {
        FakeManagedProcessController(
          snapshot = runningSnapshot(processId = it.processId, taskId = it.taskId),
        )
      },
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-running",
        taskId = "task-running",
        command = "python",
        args = listOf("script.py"),
        workingDirectory = ".",
        timeoutMs = 30_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )

    ManagedProcessControllerRegistry.clearForTest()

    val restored = FileBackedAgentProcessRegistry(directory = directory).read("proc-running")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.FAILED, restored!!.status)
    assertEquals(FileBackedAgentProcessRegistry.ERROR_INTERRUPTED_ON_RESTORE, restored.errorCode)
    assertTrue(restored.finishedAtEpochMs != null)
    assertEquals("true", restored.metadata["restoredFromDurableStore"])
    assertEquals("interrupted", restored.metadata["restoredTerminalState"])
  }

  @Test
  fun sameProcessRestoreReattachesLiveControllerInsteadOfMarkingInterrupted() {
    val directory = temporaryFolder.newFolder("durable-process-registry-reattach")
    val controller = FakeManagedProcessController(
      snapshot = runningSnapshot(processId = "proc-live", taskId = "task-live"),
      awaitSnapshot = successSnapshot(processId = "proc-live", taskId = "task-live"),
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { controller },
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-live",
        taskId = "task-live",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )

    val restoredRegistry = FileBackedAgentProcessRegistry(directory = directory)
    val restored = restoredRegistry.read("proc-live")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.RUNNING, restored!!.status)
    assertNull(restored.errorCode)
    assertNull(restored.finishedAtEpochMs)

    val waited = restoredRegistry.wait("proc-live", 250L)
    assertNotNull(waited)
    assertEquals(ManagedProcessStatus.SUCCESS, waited!!.status)

    val reopened = FileBackedAgentProcessRegistry(directory = directory).read("proc-live")

    assertNotNull(reopened)
    assertEquals(ManagedProcessStatus.SUCCESS, reopened!!.status)
    assertEquals("server ready", reopened.stdout)
  }

  @Test
  fun restoredRunningSnapshotReconnectsThroughReconnectableFactory() {
    val directory = temporaryFolder.newFolder("durable-process-registry-reconnectable")
    val factory = ReconnectableFakeManagedProcessControllerFactory()
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-remote",
        taskId = "task-remote",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )

    ManagedProcessControllerRegistry.clearForTest()

    val restoredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
    )
    val restored = restoredRegistry.read("proc-remote")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.RUNNING, restored!!.status)
    assertEquals("true", restored.metadata["reconnected"])
    assertEquals("attached_live", restored.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals(1, factory.reconnectCount)

    val waited = restoredRegistry.wait("proc-remote", 250L)

    assertNotNull(waited)
    assertEquals(ManagedProcessStatus.SUCCESS, waited!!.status)
    assertEquals("server ready", waited.stdout)
    assertEquals("true", waited.metadata["reconnected"])
    assertEquals("completed", waited.metadata["sandboxCommandReconnectRecoveryState"])
  }

  @Test
  fun retryableReconnectControllerIsReplacedAfterBackoffOnNextRead() {
    val directory = temporaryFolder.newFolder("durable-process-registry-retryable-reconnect")
    var nowEpochMs = 1_000L
    val factory = RetryableReconnectFakeManagedProcessControllerFactory(clock = { nowEpochMs })
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      clock = { nowEpochMs },
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-retryable",
        taskId = "task-retryable",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )

    ManagedProcessControllerRegistry.clearForTest()

    val restoredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      clock = { nowEpochMs },
    )
    val firstRead = restoredRegistry.read("proc-retryable")

    assertNotNull(firstRead)
    assertEquals(ManagedProcessStatus.RUNNING, firstRead!!.status)
    assertEquals("true", firstRead.metadata["sandboxCommandReconnectRetryable"])
    assertEquals("retry_scheduled", firstRead.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("1", firstRead.metadata["sandboxCommandReconnectAttemptCount"])
    assertEquals(1, factory.reconnectCount)

    nowEpochMs = 1_500L
    val secondReadBeforeBackoff = restoredRegistry.read("proc-retryable")

    assertNotNull(secondReadBeforeBackoff)
    assertEquals("1", secondReadBeforeBackoff!!.metadata["sandboxCommandReconnectAttemptCount"])
    assertEquals(1, factory.reconnectCount)

    nowEpochMs = 2_500L
    val thirdReadAfterBackoff = restoredRegistry.read("proc-retryable")

    assertNotNull(thirdReadAfterBackoff)
    assertEquals(ManagedProcessStatus.RUNNING, thirdReadAfterBackoff!!.status)
    assertEquals("false", thirdReadAfterBackoff.metadata["sandboxCommandReconnectRetryable"])
    assertEquals("attached_live", thirdReadAfterBackoff.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("2", thirdReadAfterBackoff.metadata["sandboxCommandReconnectAttemptCount"])
    assertEquals("true", thirdReadAfterBackoff.metadata["reconnectedAfterRetry"])
    assertEquals(2, factory.reconnectCount)
  }

  private fun runningSnapshot(
    processId: String,
    taskId: String,
  ): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = processId,
    taskId = taskId,
    command = "npm",
    args = listOf("run", "dev"),
    workingDirectory = ".",
    status = ManagedProcessStatus.RUNNING,
    processStarted = true,
    timeoutMs = 120_000L,
    startedAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_000L,
  )

  private fun successSnapshot(
    processId: String,
    taskId: String,
  ): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = processId,
    taskId = taskId,
    command = "npm",
    args = listOf("run", "dev"),
    workingDirectory = ".",
    status = ManagedProcessStatus.SUCCESS,
    processStarted = true,
    timeoutMs = 120_000L,
    stdout = "server ready",
    exitCode = 0,
    startedAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_250L,
    finishedAtEpochMs = 1_250L,
  )

  private class FakeManagedProcessController(
    snapshot: ManagedProcessSnapshot,
    private val awaitSnapshot: ManagedProcessSnapshot = snapshot,
  ) : ManagedProcessController {
    private var currentSnapshot: ManagedProcessSnapshot = snapshot

    override fun snapshot(): ManagedProcessSnapshot = currentSnapshot

    override fun await(timeoutMs: Long): ManagedProcessSnapshot {
      currentSnapshot = awaitSnapshot
      return currentSnapshot
    }

    override fun terminate(): ManagedProcessSnapshot = currentSnapshot
  }

  private inner class ReconnectableFakeManagedProcessControllerFactory : ReconnectableManagedProcessControllerFactory {
    var reconnectCount: Int = 0
      private set

    override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
      FakeManagedProcessController(
        snapshot = runningSnapshot(processId = request.processId, taskId = request.taskId),
      )

    override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController {
      reconnectCount += 1
      val running = snapshot.copy(
        metadata = snapshot.metadata + mapOf(
          "reconnected" to "true",
          "sandboxCommandReconnectRecoveryState" to "attached_live",
        ),
      )
      return FakeManagedProcessController(
        snapshot = running,
        awaitSnapshot = successSnapshot(
          processId = snapshot.processId,
          taskId = snapshot.taskId,
        ).copy(
          metadata = running.metadata + mapOf(
            "sandboxCommandReconnectRecoveryState" to "completed",
          ),
        ),
      )
    }
  }

  private inner class RetryableReconnectFakeManagedProcessControllerFactory(
    private val clock: () -> Long,
  ) : ReconnectableManagedProcessControllerFactory {
    var reconnectCount: Int = 0
      private set

    override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
      FakeManagedProcessController(
        snapshot = runningSnapshot(processId = request.processId, taskId = request.taskId),
      )

    override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController {
      reconnectCount += 1
      val updatedSnapshot = if (reconnectCount == 1) {
        snapshot.copy(
          metadata = snapshot.metadata + mapOf(
            "sandboxCommandReconnectRetryable" to "true",
            "sandboxCommandReconnectRecoveryState" to "retry_scheduled",
            "sandboxCommandReconnectRetryAfterEpochMs" to (clock() + 1_000L).toString(),
            "sandboxCommandReconnectAttemptCount" to "1",
          ),
        )
      } else {
        snapshot.copy(
          metadata = snapshot.metadata + mapOf(
            "sandboxCommandReconnectRetryable" to "false",
            "sandboxCommandReconnectRecoveryState" to "attached_live",
            "sandboxCommandReconnectAttemptCount" to reconnectCount.toString(),
            "reconnectedAfterRetry" to "true",
          ),
        )
      }
      return FakeManagedProcessController(snapshot = updatedSnapshot)
    }
  }
}
