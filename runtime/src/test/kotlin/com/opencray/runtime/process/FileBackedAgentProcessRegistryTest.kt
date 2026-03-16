package com.opencray.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedAgentProcessRegistryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

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

    val restored = FileBackedAgentProcessRegistry(directory = directory).read("proc-running")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.FAILED, restored!!.status)
    assertEquals(FileBackedAgentProcessRegistry.ERROR_INTERRUPTED_ON_RESTORE, restored.errorCode)
    assertTrue(restored.finishedAtEpochMs != null)
    assertEquals("true", restored.metadata["restoredFromDurableStore"])
    assertEquals("interrupted", restored.metadata["restoredTerminalState"])
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
}
