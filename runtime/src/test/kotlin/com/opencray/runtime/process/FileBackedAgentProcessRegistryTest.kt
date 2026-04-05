package com.opencray.runtime.process

import com.opencray.persistence.PersistenceSchemaVersion
import java.io.File
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
    val attemptCount =
      requireNotNull(thirdReadAfterBackoff.metadata["sandboxCommandReconnectAttemptCount"]).toInt()
    assertTrue(attemptCount >= 2)
    assertEquals(factory.reconnectCount, attemptCount)
    assertEquals("true", thirdReadAfterBackoff.metadata["reconnectedAfterRetry"])
    assertTrue(factory.reconnectCount >= 2)
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
