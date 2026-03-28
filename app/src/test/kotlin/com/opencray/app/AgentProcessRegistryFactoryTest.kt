package com.opencray.app

import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentProcessRegistryFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun sameChatSessionRestoresPersistedManagedProcessSnapshots() {
    val factory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(request.processId, request.taskId),
          awaitSnapshot = successSnapshot(request.processId, request.taskId),
        )
      },
    )

    val firstRegistry = factory.forChatSession("session/a")
    firstRegistry.start(startRequest(processId = "proc-1", taskId = "task-1"))
    firstRegistry.wait("proc-1", 250L)

    val restored = factory.forChatSession("session/a").read("proc-1")

    assertEquals(ManagedProcessStatus.SUCCESS, restored?.status)
    assertEquals("server ready", restored?.stdout)
  }

  @Test
  fun sameChatSessionReattachesLiveManagedProcessControllerWithinSameProcess() {
    val factory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(request.processId, request.taskId),
          awaitSnapshot = successSnapshot(request.processId, request.taskId),
        )
      },
    )

    val firstRegistry = factory.forChatSession("session/a")
    firstRegistry.start(startRequest(processId = "proc-live", taskId = "task-live"))

    val restoredRegistry = factory.forChatSession("session/a")
    val restored = restoredRegistry.read("proc-live")

    assertEquals(ManagedProcessStatus.RUNNING, restored?.status)
    assertEquals(null, restored?.errorCode)
    assertEquals(null, restored?.finishedAtEpochMs)

    val waited = restoredRegistry.wait("proc-live", 250L)

    assertEquals(ManagedProcessStatus.SUCCESS, waited?.status)
    assertEquals("server ready", waited?.stdout)
  }

  @Test
  fun differentChatSessionsDoNotSharePersistedManagedProcessSnapshots() {
    val factory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(request.processId, request.taskId),
        )
      },
    )

    factory.forChatSession("session-a").start(startRequest(processId = "proc-a", taskId = "task-a"))

    val sessionBProcess = factory.forChatSession("session-b").read("proc-a")

    assertNull(sessionBProcess)
  }

  private fun startRequest(
    processId: String,
    taskId: String,
  ): ManagedProcessStartRequest = ManagedProcessStartRequest(
    processId = processId,
    taskId = taskId,
    command = "npm",
    args = listOf("run", "dev"),
    workingDirectory = ".",
    timeoutMs = 120_000L,
    requestedAtEpochMs = 900L,
  )

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
