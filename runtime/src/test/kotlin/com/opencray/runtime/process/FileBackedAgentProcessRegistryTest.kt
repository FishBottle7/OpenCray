package com.opencray.runtime.process

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
  fun defaultRetentionKeepsMoreThanSixteenPersistedProcesses() {
    val directory = temporaryFolder.newFolder("durable-process-registry-default-retention")
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = successSnapshot(
            processId = request.processId,
            taskId = request.taskId,
          ).copy(
            startedAtEpochMs = request.requestedAtEpochMs,
            updatedAtEpochMs = request.requestedAtEpochMs + 1L,
            finishedAtEpochMs = request.requestedAtEpochMs + 1L,
          ),
        )
      },
    )

    repeat(17) { index ->
      registry.start(
        ManagedProcessStartRequest(
          processId = "proc-$index",
          taskId = "task-$index",
          command = "echo",
          timeoutMs = 30_000L,
          requestedAtEpochMs = 1_000L + index,
        ),
      )
    }

    val restored = FileBackedAgentProcessRegistry(directory = directory)

    assertEquals(17, restored.list().size)
    assertNotNull(restored.read("proc-0"))
    assertNotNull(restored.read("proc-16"))
  }

  @Test
  fun restoredRunningSnapshotIsRepairedToInterruptedFailure() {
    val directory = temporaryFolder.newFolder("durable-process-registry-running")
    val runtimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = runtimeIdentity,
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
        ownerIdentity = runtimeIdentity,
      ),
    )

    ManagedProcessControllerRegistry.clearForTest()

    val restored = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = runtimeIdentity,
    ).read("proc-running")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.FAILED, restored!!.status)
    assertEquals(FileBackedAgentProcessRegistry.ERROR_INTERRUPTED_ON_RESTORE, restored.errorCode)
    assertTrue(restored.finishedAtEpochMs != null)
    assertEquals("true", restored.metadata["restoredFromDurableStore"])
    assertEquals("interrupted", restored.metadata["restoredTerminalState"])
    assertEquals(
      ManagedProcessRestoreScope.SAME_CONTROLLER.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
    )
    assertEquals(
      "process-1",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY],
    )
    assertEquals(
      "controller-1",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY],
    )
  }

  @Test
  fun sameControllerRestoreReattachesLiveControllerInsteadOfMarkingInterrupted() {
    val directory = temporaryFolder.newFolder("durable-process-registry-reattach")
    val runtimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
    )
    val controller = FakeManagedProcessController(
      snapshot = runningSnapshot(
        processId = "proc-live",
        taskId = "task-live",
        ownerIdentity = runtimeIdentity,
      ),
      awaitSnapshot = successSnapshot(
        processId = "proc-live",
        taskId = "task-live",
        ownerIdentity = runtimeIdentity,
      ),
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { controller },
      runtimeIdentity = runtimeIdentity,
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
        ownerIdentity = runtimeIdentity,
      ),
    )

    val restoredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = runtimeIdentity,
    )
    val restored = restoredRegistry.read("proc-live")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.RUNNING, restored!!.status)
    assertNull(restored.errorCode)
    assertNull(restored.finishedAtEpochMs)

    val waited = restoredRegistry.wait("proc-live", 250L)
    assertNotNull(waited)
    assertEquals(ManagedProcessStatus.SUCCESS, waited!!.status)

    val reopened = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = runtimeIdentity,
    ).read("proc-live")

    assertNotNull(reopened)
    assertEquals(ManagedProcessStatus.SUCCESS, reopened!!.status)
    assertEquals("server ready", reopened.stdout)
  }

  @Test
  fun sameProcessNewControllerRestoreReattachesLiveControllerWithoutReconnectFactory() {
    val directory = temporaryFolder.newFolder("durable-process-registry-same-process-live-reattach")
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-live-reattach",
      runtimeControllerId = "controller-live-1",
      durableRuntimeControllerId = "durable-controller-live",
    )
    val restoredRuntimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-live-reattach",
      runtimeControllerId = "controller-live-2",
      durableRuntimeControllerId = "durable-controller-live",
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
          awaitSnapshot = successSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = ownerIdentity,
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-live-reattach",
        taskId = "task-live-reattach",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
        ownerIdentity = ownerIdentity,
      ),
    )

    val restoredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = restoredRuntimeIdentity,
    )
    val restored = restoredRegistry.read("proc-live-reattach")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.RUNNING, restored!!.status)
    assertNull(restored.errorCode)
    assertNull(restored.finishedAtEpochMs)
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
    )
    assertEquals(
      ManagedProcessRestoreDecision.LIVE_CONTROLLER_REATTACHED.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
    )
    assertEquals(
      "process-live-reattach",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY],
    )
    assertEquals(
      "controller-live-2",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY],
    )
    assertEquals(
      "durable-controller-live",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY],
    )

    val waited = restoredRegistry.wait("proc-live-reattach", 250L)

    assertNotNull(waited)
    assertEquals(ManagedProcessStatus.SUCCESS, waited!!.status)
    assertEquals("server ready", waited.stdout)
    assertEquals(
      ManagedProcessRestoreDecision.LIVE_CONTROLLER_REATTACHED.wireValue,
      waited.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
    )
  }

  @Test
  fun sameProcessNewControllerRestoreReconnectsAndStampsRestoreScopeMetadata() {
    val directory = temporaryFolder.newFolder("durable-process-registry-same-process-new-controller")
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
      durableRuntimeControllerId = "durable-controller-1",
    )
    val restoredRuntimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-2",
      durableRuntimeControllerId = "durable-controller-1",
    )
    val factory = ReconnectableFakeManagedProcessControllerFactory()
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      runtimeIdentity = ownerIdentity,
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
        ownerIdentity = ownerIdentity,
      ),
    )

    val restoredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      runtimeIdentity = restoredRuntimeIdentity,
    )
    val restored = restoredRegistry.read("proc-remote")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.RUNNING, restored!!.status)
    assertEquals("true", restored.metadata["reconnected"])
    assertEquals(
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
    )
    assertEquals(
      "process-1",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY],
    )
    assertEquals(
      "controller-2",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY],
    )
    assertEquals(
      "durable-controller-1",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY],
    )
    assertEquals(1, factory.reconnectCount)
  }

  @Test
  fun crossProcessRestoreRepairsInterruptedSnapshotAndStampsRestoreScopeMetadata() {
    val directory = temporaryFolder.newFolder("durable-process-registry-cross-process")
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
    )
    val restoredRuntimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-2",
      runtimeControllerId = "controller-2",
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = ownerIdentity,
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-cross-process",
        taskId = "task-cross-process",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
        ownerIdentity = ownerIdentity,
      ),
    )

    ManagedProcessControllerRegistry.clearForTest()

    val restored = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = restoredRuntimeIdentity,
    ).read("proc-cross-process")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.FAILED, restored!!.status)
    assertEquals(FileBackedAgentProcessRegistry.ERROR_INTERRUPTED_ON_RESTORE, restored.errorCode)
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
    )
    assertEquals(
      ManagedProcessRestoreDecision.INTERRUPTED_NO_CONTROLLER.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
    )
    assertEquals(
      "process-2",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY],
    )
    assertEquals(
      "controller-2",
      restored.metadata[MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY],
    )
  }

  @Test
  fun projectionOnlyRestoreModeReadsRunningSnapshotWithoutPersistingInterruptedRepair() {
    val directory = temporaryFolder.newFolder("durable-process-registry-projection-only")
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            ownerIdentity = request.ownerIdentity,
          ),
        )
      },
      runtimeIdentity = ownerIdentity,
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-projection",
        taskId = "task-projection",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
        ownerIdentity = ownerIdentity,
      ),
    )

    val projectionRead = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "process-ui",
        runtimeControllerId = "controller-ui",
      ),
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    ).read("proc-projection")

    val secondProjectionRead = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "process-ui",
        runtimeControllerId = "controller-ui",
      ),
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    ).read("proc-projection")

    assertNotNull(projectionRead)
    assertEquals(ManagedProcessStatus.RUNNING, projectionRead!!.status)
    assertNull(projectionRead.errorCode)
    assertNotNull(secondProjectionRead)
    assertEquals(ManagedProcessStatus.RUNNING, secondProjectionRead!!.status)
    assertNull(secondProjectionRead.errorCode)

    val activeRestore = FileBackedAgentProcessRegistry(
      directory = directory,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "process-2",
        runtimeControllerId = "controller-2",
      ),
    ).read("proc-projection")

    assertNotNull(activeRestore)
    assertEquals(ManagedProcessStatus.FAILED, activeRestore!!.status)
    assertEquals(FileBackedAgentProcessRegistry.ERROR_INTERRUPTED_ON_RESTORE, activeRestore.errorCode)
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
  fun restoredRunningSnapshotReconnectsThroughRoutedDefaultFactory() {
    val directory = temporaryFolder.newFolder("durable-process-registry-routed-reconnectable")
    val delegateFactory = ReconnectableFakeManagedProcessControllerFactory()
    val routedFactory = RoutedManagedProcessControllerFactory(
      workspaceRoot = directory.toPath(),
      pythonRuntime = CompletingPythonScriptRuntime(),
      defaultFactory = delegateFactory,
    )
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-1",
      runtimeControllerId = "controller-1",
    )
    val restoredRuntimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "process-2",
      runtimeControllerId = "controller-2",
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = routedFactory,
      runtimeIdentity = ownerIdentity,
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-routed-remote",
        taskId = "task-routed-remote",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
        ownerIdentity = ownerIdentity,
      ),
    )

    ManagedProcessControllerRegistry.clearForTest()

    val restored = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = routedFactory,
      runtimeIdentity = restoredRuntimeIdentity,
    ).read("proc-routed-remote")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.RUNNING, restored!!.status)
    assertEquals("true", restored.metadata["reconnected"])
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
      restored.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
    )
    assertEquals(1, delegateFactory.reconnectCount)
  }

  @Test
  fun routedManagedPythonRuntimeSnapshotDoesNotDelegateReconnect() {
    val directory = temporaryFolder.newFolder("durable-process-registry-routed-python-reconnect")
    val delegateFactory = ReconnectableFakeManagedProcessControllerFactory()
    val routedFactory = RoutedManagedProcessControllerFactory(
      workspaceRoot = directory.toPath(),
      pythonRuntime = CompletingPythonScriptRuntime(),
      defaultFactory = delegateFactory,
    )

    val controller = routedFactory.reconnect(
      runningSnapshot(
        processId = "proc-python-runtime",
        taskId = "task-python-runtime",
      ).copy(
        metadata = mapOf(
          "managedByPythonRuntime" to "true",
          "runtimeKind" to "python_exec",
          "scriptPath" to "scripts/run.py",
        ),
      ),
    )

    assertNull(controller)
    assertEquals(0, delegateFactory.reconnectCount)
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
    val attemptCount =
      requireNotNull(thirdReadAfterBackoff.metadata["sandboxCommandReconnectAttemptCount"]).toInt()
    assertTrue(attemptCount >= 2)
    assertEquals(factory.reconnectCount, attemptCount)
    assertEquals("true", thirdReadAfterBackoff.metadata["reconnectedAfterRetry"])
    assertTrue(factory.reconnectCount >= 2)
  }

  @Test
  fun restoredRetryScheduledSnapshotDefersReconnectUntilBackoffDeadline() {
    val directory = temporaryFolder.newFolder("durable-process-registry-retryable-restore-backoff")
    var nowEpochMs = 1_000L
    val factory = RetryableReconnectFakeManagedProcessControllerFactory(clock = { nowEpochMs })
    val ownerIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "owner-process-start",
      runtimeControllerId = "owner-runtime-controller",
    )
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      runtimeIdentity = ownerIdentity,
      clock = { nowEpochMs },
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-retryable-restore",
        taskId = "task-retryable-restore",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
        ownerIdentity = ownerIdentity,
      ),
    )
    ManagedProcessControllerRegistry.clearForTest()

    val firstRestore = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "restore-process-start-1",
        runtimeControllerId = "restore-runtime-controller-1",
      ),
      clock = { nowEpochMs },
    ).read("proc-retryable-restore")

    assertNotNull(firstRestore)
    assertEquals(ManagedProcessStatus.RUNNING, firstRestore!!.status)
    assertEquals("retry_scheduled", firstRestore.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("1", firstRestore.metadata["sandboxCommandReconnectAttemptCount"])
    assertEquals("2000", firstRestore.metadata["sandboxCommandReconnectRetryAfterEpochMs"])
    assertEquals(1, factory.reconnectCount)

    ManagedProcessControllerRegistry.clearForTest()
    nowEpochMs = 1_500L
    val deferredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = factory,
      runtimeIdentity = ManagedProcessRuntimeIdentity(
        processStartId = "restore-process-start-2",
        runtimeControllerId = "restore-runtime-controller-2",
      ),
      clock = { nowEpochMs },
    )
    val deferred = deferredRegistry.read("proc-retryable-restore")

    assertNotNull(deferred)
    assertEquals(ManagedProcessStatus.RUNNING, deferred!!.status)
    assertNull(deferred.errorCode)
    assertNull(deferred.finishedAtEpochMs)
    assertEquals("retry_scheduled", deferred.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("1", deferred.metadata["sandboxCommandReconnectAttemptCount"])
    assertEquals(
      ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
      deferred.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
    )
    assertEquals(
      ManagedProcessRestoreDecision.RECONNECT_DEFERRED.wireValue,
      deferred.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
    )
    assertEquals(1, factory.reconnectCount)

    nowEpochMs = 2_500L
    val attached = deferredRegistry.read("proc-retryable-restore")

    assertNotNull(attached)
    assertEquals(ManagedProcessStatus.RUNNING, attached!!.status)
    assertEquals("false", attached.metadata["sandboxCommandReconnectRetryable"])
    assertEquals("attached_live", attached.metadata["sandboxCommandReconnectRecoveryState"])
    assertEquals("true", attached.metadata["reconnectedAfterRetry"])
    assertEquals(2, factory.reconnectCount)
  }

  @Test
  fun metadataOnlyRemoteSnapshotIsNormalizedIntoTypedRemoteStateOnLoad() {
    val directory = temporaryFolder.newFolder("durable-process-registry-typed-normalize")
    File(directory, FileBackedAgentProcessRegistry.FILE_NAME).writeText(
      """
      {
        "schemaVersion": ${PersistenceSchemaVersion.CURRENT},
        "recordVersion": 1,
        "updatedAtEpochMs": 1100,
        "snapshots": [
          {
            "processId": "proc-legacy-remote",
            "taskId": "task-legacy-remote",
            "command": "npm",
            "args": ["run", "dev"],
            "workingDirectory": ".",
            "status": "SUCCESS",
            "processStarted": true,
            "timeoutMs": 120000,
            "stdout": "booting",
            "stderr": "",
            "exitCode": 0,
            "startedAtEpochMs": 1000,
            "updatedAtEpochMs": 1100,
            "finishedAtEpochMs": 1100,
            "timedOut": false,
            "cancelled": false,
            "outputLimitExceeded": false,
            "metadata": {
              "sandboxProvider": "e2b",
              "sandboxId": "sandbox-legacy-remote",
              "sandboxDomain": "e2b.app",
              "sandboxCommandNativeProtocol": "envd_connect_process_v1",
              "sandboxCommandProviderHandleKind": "envd_process",
              "sandboxCommandProviderStableSelectorKind": "tag",
              "sandboxCommandProviderStableSelectorValue": "proc-legacy-remote",
              "sandboxCommandProviderLiveSelectorKind": "pid",
              "sandboxCommandProviderLiveSelectorValue": "654",
              "sandboxCommandIdKind": "tag",
              "sandboxCommandId": "proc-legacy-remote",
              "remoteWorkspaceRoot": "/home/user/opencray/workspace-sticky/sandbox-legacy-remote",
              "remoteWorkingDirectory": "/home/user/opencray/workspace-sticky/sandbox-legacy-remote/repo",
              "sandboxCommandObservationMode": "host_managed_snapshot",
              "sandboxCommandObservationEventCount": "2",
              "sandboxCommandObservationCursor": "host_seq_2",
              "sandboxCommandObservationStdoutBytes": "7",
              "sandboxCommandObservationStderrBytes": "0",
              "sandboxCommandProviderObservationMode": "provider_event_stream_host_buffered",
              "sandboxCommandProviderObservationEventCount": "2",
              "sandboxCommandProviderObservationCursor": "envd_seq_2",
              "sandboxCommandProviderObservationBackfillSupported": "false",
              "sandboxCommandReconnectApi": "envd_process_connect",
              "sandboxCommandReconnectSource": "durable_registry_restore",
              "sandboxCommandReconnectStatus": "attached",
              "sandboxCommandReconnectRecoveryState": "attached_live",
              "sandboxCommandReconnectAttemptCount": "2",
              "sandboxCommandReconnectSelectorKind": "pid",
              "sandboxCommandReconnectSelectorValue": "654",
              "sandboxCommandReconnectSelectorSource": "snapshot_pid",
              "sandboxCommandReconnectSeedSource": "durable_snapshot_metadata",
              "sandboxCommandReconnectProviderObservationSeedConsumed": "true",
              "sandboxCommandReconnectProviderObservationSeedState": "consumed_live_attach",
              "sandboxCommandReconnectProviderObservationSeedSource": "observation_snapshot_metadata",
              "sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs": "1050",
              "sandboxCommandReconnectSeedObservationCursor": "host_seq_1",
              "sandboxCommandReconnectSeedEventCount": "1",
              "sandboxCommandReconnectSeededStdoutBytes": "7",
              "sandboxCommandReconnectSeededStderrBytes": "0",
              "sandboxCommandReconnectSeedProviderObservationCursor": "envd_seq_1",
              "sandboxCommandReconnectSeedProviderObservationEventCount": "1",
              "sandboxCommandReconnectProviderObservationResumeApplied": "false",
              "sandboxCommandReconnectProviderObservationResumeReason": "protocol_cursor_resume_unsupported"
            }
          }
        ]
      }
      """.trimIndent(),
    )

    val restored = FileBackedAgentProcessRegistry(directory = directory).read("proc-legacy-remote")

    assertNotNull(restored)
    assertEquals("e2b", restored!!.remoteHandle?.provider)
    assertEquals("sandbox-legacy-remote", restored.remoteHandle?.sandboxId)
    assertEquals("654", restored.remoteHandle?.liveSelectorValue)
    assertEquals(
      "/home/user/opencray/workspace-sticky/sandbox-legacy-remote/repo",
      restored.remoteHandle?.remoteWorkingDirectory,
    )
    assertEquals("host_managed_snapshot", restored.observationState?.mode)
    assertEquals(2L, restored.observationState?.hostEventCount)
    assertEquals("host_seq_2", restored.observationState?.hostCursor)
    assertEquals(7L, restored.observationState?.stdoutBytes)
    assertEquals("attached_live", restored.reconnectState?.recoveryState)
    assertEquals(2, restored.reconnectState?.attemptCount)
    assertEquals("654", restored.reconnectState?.selectorValue)
    assertEquals("durable_snapshot_metadata", restored.reconnectState?.seed?.source)
    assertEquals("host_seq_1", restored.reconnectState?.seed?.hostObservationCursor)
    assertEquals(7L, restored.reconnectState?.seed?.stdoutBytes)
    assertEquals(
      "observation_snapshot_metadata",
      restored.reconnectState?.seed?.providerObservationSeedSource,
    )
    assertEquals(false, restored.reconnectState?.providerObservationResumeApplied)
    assertEquals(
      "protocol_cursor_resume_unsupported",
      restored.reconnectState?.providerObservationResumeReason,
    )
  }

  @Test
  fun deliveredObservationStatePersistsAcrossReloadAndLiveSnapshotRefresh() {
    val directory = temporaryFolder.newFolder("durable-process-registry-delivered-observation")
    val runningSnapshot = runningSnapshot(
      processId = "proc-delivered-observation",
      taskId = "task-delivered-observation",
    ).copy(
      stdout = "booting\nready",
      observationState = ManagedProcessObservationState(
        mode = "host_managed_snapshot",
        hostEventCount = 2L,
        hostCursor = "host_seq_2",
        stdoutBytes = "booting\nready".toByteArray().size.toLong(),
        stderrBytes = 0L,
        providerMode = "provider_event_stream_host_buffered",
        providerEventCount = 2L,
        providerCursor = "envd_seq_2",
      ),
    )
    val controller = FakeManagedProcessController(snapshot = runningSnapshot)
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { controller },
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-delivered-observation",
        taskId = "task-delivered-observation",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )
    registry.recordObservationDelivery(
      processId = "proc-delivered-observation",
      deliveredObservationState = ManagedProcessDeliveredObservationState(
        mode = "host_managed_snapshot",
        cursor = "host_seq_1",
        stdoutBytes = 8L,
        stderrBytes = 0L,
        providerMode = "provider_event_stream_host_buffered",
        providerCursor = "envd_seq_1",
        providerEventCount = 1L,
        deliveredAtEpochMs = 1_050L,
      ),
    )

    val restoredRegistry = FileBackedAgentProcessRegistry(
      directory = directory,
      controllerFactory = ManagedProcessControllerFactory { controller },
    )
    val restored = restoredRegistry.read("proc-delivered-observation")

    assertNotNull(restored)
    assertEquals("host_managed_snapshot", restored!!.deliveredObservationState?.mode)
    assertEquals("host_seq_1", restored.deliveredObservationState?.cursor)
    assertEquals(8L, restored.deliveredObservationState?.stdoutBytes)
    assertEquals(0L, restored.deliveredObservationState?.stderrBytes)
    assertEquals(
      "provider_event_stream_host_buffered",
      restored.deliveredObservationState?.providerMode,
    )
    assertEquals("envd_seq_1", restored.deliveredObservationState?.providerCursor)
    assertEquals(1L, restored.deliveredObservationState?.providerEventCount)
    assertEquals(1_050L, restored.deliveredObservationState?.deliveredAtEpochMs)
    assertEquals(
      "host_managed_snapshot",
      restored.metadata["sandboxCommandLastDeliveredObservationMode"],
    )
    assertEquals("host_seq_1", restored.metadata["sandboxCommandLastDeliveredObservationCursor"])
    assertEquals("8", restored.metadata["sandboxCommandLastDeliveredStdoutBytes"])
    assertEquals("0", restored.metadata["sandboxCommandLastDeliveredStderrBytes"])
    assertEquals(
      "provider_event_stream_host_buffered",
      restored.metadata["sandboxCommandLastDeliveredProviderObservationMode"],
    )
    assertEquals(
      "envd_seq_1",
      restored.metadata["sandboxCommandLastDeliveredProviderObservationCursor"],
    )
    assertEquals("1", restored.metadata["sandboxCommandLastDeliveredProviderObservationEventCount"])
    assertEquals("1050", restored.metadata["sandboxCommandLastDeliveredAtEpochMs"])
  }

  @Test
  fun fileBackedRegistryPersistsSnapshotsThroughDurableUpdatePrimitive() {
    val directory = temporaryFolder.newFolder("durable-process-registry-update-primitive")
    val storage = UpdateOnlyDurableTextStorage()
    val registry = FileBackedAgentProcessRegistry(
      directory = directory,
      storage = storage,
      controllerFactory = ManagedProcessControllerFactory { request ->
        FakeManagedProcessController(
          snapshot = runningSnapshot(processId = request.processId, taskId = request.taskId),
          awaitSnapshot = successSnapshot(processId = request.processId, taskId = request.taskId),
        )
      },
    )

    registry.start(
      ManagedProcessStartRequest(
        processId = "proc-update",
        taskId = "task-update",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        timeoutMs = 120_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )

    assertEquals(1, storage.updateTextCallCount)
    assertTrue(storage.currentText.orEmpty().contains("proc-update"))
    val updateCallsAfterStart = storage.updateTextCallCount

    registry.wait("proc-update", 250L)

    assertTrue(storage.updateTextCallCount > updateCallsAfterStart)
    assertTrue(storage.currentText.orEmpty().contains("SUCCESS"))

    val restored = FileBackedAgentProcessRegistry(
      directory = directory,
      storage = storage,
    ).read("proc-update")

    assertNotNull(restored)
    assertEquals(ManagedProcessStatus.SUCCESS, restored!!.status)
  }

  @Test
  fun concurrentFileBackedOwnersDoNotLoseManagedProcessSnapshots() {
    val directory = temporaryFolder.newFolder("durable-process-registry-concurrent-owners")
    val ownerCount = 8
    val ready = CountDownLatch(ownerCount)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(ownerCount)
    val failures = mutableListOf<Throwable>()

    repeat(ownerCount) { index ->
      executor.execute {
        try {
          val processId = "proc-$index"
          ready.countDown()
          assertTrue(ready.await(5, TimeUnit.SECONDS))
          start.await(5, TimeUnit.SECONDS)
          FileBackedAgentProcessRegistry(
            directory = directory,
            controllerFactory = ManagedProcessControllerFactory { request ->
              FakeManagedProcessController(
                snapshot = successSnapshot(
                  processId = request.processId,
                  taskId = request.taskId,
                  ownerIdentity = request.ownerIdentity,
                ),
              )
            },
          ).start(
            ManagedProcessStartRequest(
              processId = processId,
              taskId = "task-$index",
              command = "npm",
              args = listOf("run", "dev"),
              workingDirectory = ".",
              timeoutMs = 120_000L,
              requestedAtEpochMs = 1_000L + index,
            ),
          )
        } catch (error: Throwable) {
          synchronized(failures) {
            failures += error
          }
        }
      }
    }

    assertTrue(ready.await(5, TimeUnit.SECONDS))
    start.countDown()
    executor.shutdown()
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
    synchronized(failures) {
      if (failures.isNotEmpty()) {
        throw AssertionError("Concurrent registry writes failed.", failures.first())
      }
    }

    val restored = FileBackedAgentProcessRegistry(directory = directory).list()

    assertEquals(ownerCount, restored.size)
    assertEquals(
      (0 until ownerCount).map { index -> "proc-$index" }.toSet(),
      restored.map { snapshot -> snapshot.processId }.toSet(),
    )
  }

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

  private class UpdateOnlyDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    override fun readText(name: String): String? = text

    override fun writeText(name: String, text: String) {
      error("Managed process registry mutations should use updateText.")
    }

    override fun delete(name: String): Boolean {
      error("Managed process registry mutations should use updateText.")
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }

  private class CompletingPythonScriptRuntime : PythonScriptRuntime {
    override fun exec(request: PythonExecRequest): ExecutionResult = ExecutionResult(
      taskId = request.taskId,
      status = ExecutionStatus.SUCCESS,
      exitCode = 0,
      stdout = "",
      stderr = "",
      startedAtEpochMs = 1_000L,
      finishedAtEpochMs = 1_000L,
    )
  }

  private inner class ReconnectableFakeManagedProcessControllerFactory : ReconnectableManagedProcessControllerFactory {
    var reconnectCount: Int = 0
      private set

    override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
      FakeManagedProcessController(
        snapshot = runningSnapshot(
          processId = request.processId,
          taskId = request.taskId,
          ownerIdentity = request.ownerIdentity,
        ),
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
          ownerIdentity = snapshot.ownerIdentity,
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
        snapshot = runningSnapshot(
          processId = request.processId,
          taskId = request.taskId,
          ownerIdentity = request.ownerIdentity,
        ),
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
