package com.opencray.app

import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.FileBackedAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.ManagedProcessRuntimeIdentity
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
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

  @Test
  fun differentRuntimeControllersInSameProcessReattachLiveManagedProcessController() {
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
    )
    val firstFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            request.processId,
            request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
          awaitSnapshot = successSnapshot(
            request.processId,
            request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = ownerIdentity,
    )
    val secondFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            request.processId,
            request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "process-1",
        runtimeControllerId = "controller-2",
      ),
    )

    firstFactory.forChatSession("session/a").start(
      startRequest(
        processId = "proc-live",
        taskId = "task-live",
        ownerIdentity = ownerIdentity,
      ),
    )

    val restored = secondFactory.forChatSession("session/a").read("proc-live")

    assertEquals(ManagedProcessStatus.RUNNING, restored?.status)
    assertNull(restored?.errorCode)
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      restored?.metadata?.get(MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY),
    )
    assertEquals(
      ManagedProcessRestoreDecision.LIVE_CONTROLLER_REATTACHED.wireValue,
      restored?.metadata?.get(MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY),
    )
    assertEquals(
      "controller-2",
      restored?.metadata?.get(MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY),
    )
  }

  @Test
  fun projectionOnlyRestoreModeDoesNotRepairRunningManagedProcessSnapshotsDuringRead() {
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-owner",
      runtimeControllerId = "controller-owner",
    )
    val ownerFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            request.processId,
            request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = ownerIdentity,
    )

    ownerFactory.forChatSession("session/a").start(
      startRequest(
        processId = "proc-projection",
        taskId = "task-projection",
        ownerIdentity = ownerIdentity,
      ),
    )

    val projectionIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-ui",
      runtimeControllerId = "controller-ui",
    )
    val projectionFactory = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      runtimeIdentity = projectionIdentity,
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    )

    val firstProjectionRead = projectionFactory.forChatSession("session/a").read("proc-projection")
    val secondProjectionRead = projectionFactory.forChatSession("session/a").read("proc-projection")

    assertEquals(ManagedProcessStatus.RUNNING, firstProjectionRead?.status)
    assertNull(firstProjectionRead?.errorCode)
    assertEquals(ManagedProcessStatus.RUNNING, secondProjectionRead?.status)
    assertNull(secondProjectionRead?.errorCode)

    val restoredOwnerRead = FileBackedAgentProcessRegistryFactory(
      runtimeRootDirectory = temporaryFolder.root,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "process-runtime-2",
        runtimeControllerId = "controller-runtime-2",
      ),
    ).forChatSession("session/a").read("proc-projection")

    assertEquals(ManagedProcessStatus.FAILED, restoredOwnerRead?.status)
    assertEquals(FileBackedAgentProcessRegistry.ERROR_INTERRUPTED_ON_RESTORE, restoredOwnerRead?.errorCode)
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      restoredOwnerRead?.metadata?.get(MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY),
    )
  }

  private fun startRequest(
    processId: String,
    taskId: String,
    ownerIdentity: ManagedProcessRuntimeIdentity? = null,
  ): ManagedProcessStartRequest = ManagedProcessStartRequest(
    processId = processId,
    taskId = taskId,
    command = "npm",
    args = listOf("run", "dev"),
    workingDirectory = ".",
    timeoutMs = 120_000L,
    requestedAtEpochMs = 900L,
    ownerIdentity = ownerIdentity,
  )

  private fun runningSnapshot(
    processId: String,
    taskId: String,
    ownerIdentity: ManagedProcessRuntimeIdentity? = null,
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
    ownerIdentity = ownerIdentity,
  )

  private fun successSnapshot(
    processId: String,
    taskId: String,
    ownerIdentity: ManagedProcessRuntimeIdentity? = null,
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
    ownerIdentity = ownerIdentity,
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
