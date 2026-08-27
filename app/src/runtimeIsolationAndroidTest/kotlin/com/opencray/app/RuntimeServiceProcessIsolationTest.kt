package com.opencray.app

import com.opencray.app.e2b.E2BCodeInterpreterPythonRuntime
import com.opencray.app.e2b.E2BEnvdProcessEvent
import com.opencray.app.e2b.E2BEnvdProcessProtoCodec
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.opencray.app.ipc.IRuntimeServiceController
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.EXECUTION_KIND_INITIAL
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.METADATA_RECOVERY_REASON
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySerializableModelAction
import com.opencray.runtime.process.FileBackedAgentProcessRegistry
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY
import com.opencray.runtime.process.MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessObservationState
import com.opencray.runtime.process.ManagedProcessRemoteHandle
import com.opencray.runtime.process.ManagedProcessRestoreDecision
import com.opencray.runtime.process.ManagedProcessRestoreMode
import com.opencray.runtime.process.ManagedProcessRestoreScope
import com.opencray.runtime.process.ManagedProcessRuntimeIdentity
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeServiceProcessIsolationTest {
  private val bindings = mutableListOf<ServiceBinding>()

  @After
  fun tearDown() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    bindings.toList().asReversed().forEach { binding ->
      runCatching { context.unbindService(binding) }
    }
    bindings.clear()
  }

  @Test(timeout = 90_000L)
  fun targetServicesExposeV2RemoteControllersFromIndependentProcesses() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val interactive = bindController(context, RuntimeServiceTarget.INTERACTIVE)
    val detached = bindController(context, RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertRemoteController(interactive, RuntimeServiceTarget.INTERACTIVE)
    assertRemoteController(detached, RuntimeServiceTarget.DETACHED_BACKGROUND)

    val interactiveSnapshot = loadProjection(interactive.controller)
    val detachedSnapshot = loadProjection(detached.controller)
    val interactiveProcessName = "${context.packageName}:runtime"
    val detachedProcessName = "${context.packageName}:runtime_controller"
    val interactivePid = awaitProcessPid(context, interactiveProcessName)
    val detachedPid = awaitProcessPid(context, detachedProcessName)

    assertEquals(
      interactiveProcessName,
      interactiveSnapshot.serviceLifecycle.serviceProcess?.processName,
    )
    assertEquals(
      detachedProcessName,
      detachedSnapshot.serviceLifecycle.serviceProcess?.processName,
    )
    assertTrue(interactivePid != detachedPid)
    assertTrue(interactivePid != Process.myPid())
    assertTrue(detachedPid != Process.myPid())

    val writeResponse = interactive.controller.dispatchWriteCommandJson(
      encodeRuntimeServiceWriteCommand(
        runtimeServiceWriteCommandEnvelope(
          OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
        ),
      ),
    )
    assertEquals(
      OpenCrayChatWriteDispatchResult.Completed,
      decodeRuntimeServiceChatWriteResult(writeResponse),
    )
  }

  @Test(timeout = 60_000L)
  fun v1RemoteControllerStaysProjectionOnlyAndFallsBackWrites() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.context
    val component = ComponentName(
      context,
      RuntimeServiceControllerV1FixtureService::class.java,
    )
    val serviceIntent = Intent().setComponent(component)
    val binding = ServiceBinding()
    assertTrue(
      "bindService returned false for the remote v1 controller fixture",
      context.bindService(serviceIntent, binding, Context.BIND_AUTO_CREATE),
    )
    val binder = binding.awaitBinder()
    var client: AndroidBindingOpenCrayRuntimeServiceClient? = null

    try {
      assertNull(
        binder.queryLocalInterface("com.opencray.app.ipc.IRuntimeServiceController"),
      )
      val access = requireNotNull(
        runtimeServiceBinderAccessForBinder(
          binder = binder,
          expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
        ),
      )
      assertEquals(
        RuntimeServiceControllerV1FixtureService.PROJECTION_ACTIVE_RUN_COUNT,
        access.loadSnapshot().runtimeOwnerWorkSummary.activeRunCount,
      )
      assertNull(
        access.dispatchChatWriteCommand(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      )
      assertNull(access.dispatchSkillsWriteCommand(OpenCraySkillsWriteCommand.RefreshSkills))
      assertNull(
        access.dispatchSettingsWriteCommand(
          OpenCraySettingsWriteCommand.PerformStrongBackgroundAction("repair"),
        ),
      )

      val fallbackCommands = mutableListOf<OpenCrayChatWriteCommand>()
      val fallbackResult = OpenCrayChatWriteDispatchResult.Payload(
        mapOf("transport" to "command_fallback"),
      )
      client = AndroidBindingOpenCrayRuntimeServiceClient(
        appContext = context,
        runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
        bindingAdapter = object : OpenCrayRuntimeServiceBindingAdapter {
          override fun bind(
            context: Context,
            intent: Intent,
            connection: ServiceConnection,
            flags: Int,
          ): Boolean {
            connection.onServiceConnected(component, binder)
            return true
          }

          override fun unbind(
            context: Context,
            connection: ServiceConnection,
          ) = Unit
        },
        startRequester = { },
        commandFallbackTransport = object : RuntimeServiceCommandFallbackTransport {
          override fun dispatchChatWriteCommand(
            command: OpenCrayChatWriteCommand,
          ): OpenCrayChatWriteDispatchResult {
            fallbackCommands += command
            return fallbackResult
          }
        },
        mainThreadPoster = ImmediateMainThreadPoster,
        serviceIntentFactory = { serviceIntent },
        isMainThread = { false },
      )

      assertEquals(
        fallbackResult,
        client.dispatchChatWriteCommand(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      )
      assertEquals(
        listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
        fallbackCommands,
      )
      assertEquals("bound", client.loadConnectionState().phase)
      assertEquals("binder", client.loadConnectionState().transport)
      assertEquals(
        RuntimeServiceControllerV1FixtureService.PROJECTION_ACTIVE_RUN_COUNT,
        client.peekProjectionSnapshot()?.runtimeOwnerWorkSummary?.activeRunCount,
      )
    } finally {
      client?.dispose()
      context.unbindService(binding)
    }
  }

  @Test(timeout = 120_000L)
  fun detachedControllerRebindsAfterProcessDeathAndOwnerLeaseExpiry() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val processName = "${context.packageName}:runtime_controller"
    val first = bindController(context, target)
    val firstSnapshot = loadProjection(first.controller)
    val firstPid = awaitProcessPid(context, processName)

    Process.killProcess(firstPid)
    unbind(context, first.binding)
    awaitProcessExit(context, processName, firstPid)

    val leaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context)
    val staleLease = awaitLease(
      store = leaseStore,
      target = target,
      processStartId = firstSnapshot.runtimeOwnerLifecycle.processStartId,
    )
    val waitMs = (staleLease.expiresAtEpochMs - System.currentTimeMillis() + 1_000L)
      .coerceAtLeast(0L)
    if (waitMs > 0L) {
      Thread.sleep(waitMs)
    }

    assertTrue(
      openCrayRuntimeServiceEnvironment(context)
        .runtimeServiceAccessGateway
        .resumeInterruptedRuns(
          context = context,
          repairReason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
          target = target,
        ),
    )

    val second = bindController(context, target)
    val secondSnapshot = loadProjection(second.controller)
    val secondPid = awaitProcessPid(context, processName)

    assertTrue(secondPid != firstPid)
    assertTrue(
      secondSnapshot.runtimeOwnerLifecycle.processStartId !=
        firstSnapshot.runtimeOwnerLifecycle.processStartId,
    )
    assertNotNull(firstSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId)
    assertEquals(
      firstSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
      secondSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
    )
    assertEquals(target.wireValue, second.controller.runtimeTarget)
  }

  @Test(timeout = 150_000L)
  fun detachedProcessDeathResumesSafeCheckpointUnderSameRunIdentity() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val processName = "${context.packageName}:runtime_controller"
    val first = bindController(context, target)
    val firstSnapshot = loadProjection(first.controller)
    val firstPid = awaitProcessPid(context, processName)
    val llmSettingsStore = LlmSettingsStore.fromContext(context)
    val previousLlmSettings = llmSettingsStore.load()
    val chatSessionStore = ChatSessionLocalStore.fromContext(context)
    val previousActiveSessionId = chatSessionStore.loadState().activeSession.sessionId
    val sessionId = chatSessionStore.copySession(previousActiveSessionId).activeSession.sessionId
    val uniqueSuffix = System.nanoTime().toString()
    val taskId = "runtime-isolation-checkpoint-task-$uniqueSuffix"
    val runId = "runtime-isolation-checkpoint-run-$uniqueSuffix"
    val scheduleId = "runtime-isolation-checkpoint-schedule-$uniqueSuffix"
    val expectedAnswer = "Recovered detached checkpoint $uniqueSuffix"
    var second: BoundController? = null

    try {
      llmSettingsStore.save(
        previousLlmSettings.copy(
          enabled = true,
          apiKey = "runtime-isolation-test-key",
        ),
      )
      seedRunningFinalActionCheckpoint(
        context = context,
        sessionId = sessionId,
        taskId = taskId,
        runId = runId,
        scheduleId = scheduleId,
        expectedAnswer = expectedAnswer,
      )

      Process.killProcess(firstPid)
      unbind(context, first.binding)
      awaitProcessExit(context, processName, firstPid)

      val leaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context)
      val staleLease = awaitLease(
        store = leaseStore,
        target = target,
        processStartId = firstSnapshot.runtimeOwnerLifecycle.processStartId,
      )
      val waitMs = (staleLease.expiresAtEpochMs - System.currentTimeMillis() + 1_000L)
        .coerceAtLeast(0L)
      if (waitMs > 0L) {
        Thread.sleep(waitMs)
      }

      assertTrue(
        openCrayRuntimeServiceEnvironment(context)
          .runtimeServiceAccessGateway
          .resumeInterruptedRuns(
            context = context,
            repairReason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
            target = target,
          ),
      )

      val rebuilt = bindController(context, target)
      second = rebuilt
      val secondSnapshot = loadProjection(rebuilt.controller)
      val secondPid = awaitProcessPid(context, processName)
      val completion = awaitCompletedCheckpointResume(
        context = context,
        sessionId = sessionId,
        taskId = taskId,
        runId = runId,
        expectedAnswer = expectedAnswer,
      )

      assertTrue(secondPid != firstPid)
      assertEquals(
        firstSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
        secondSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
      )
      assertEquals(QueueTaskLifecycleState.COMPLETED, completion.task.lifecycleState)
      assertEquals(AgentTaskState.COMPLETED, completion.task.task.state)
      assertEquals(2, completion.task.executionOrdinal)
      assertEquals(EXECUTION_KIND_CHECKPOINT_RESUME, completion.task.executionKind)
      assertEquals(runId, completion.runRecord.runId)
      assertEquals(taskId, completion.runRecord.taskId)
      assertEquals(ExecutionStatus.SUCCESS, completion.runRecord.lastResult?.status)
      assertEquals(expectedAnswer, completion.runRecord.lastResult?.stdout)
      assertEquals(
        EXECUTION_KIND_CHECKPOINT_RESUME,
        completion.runRecord.lastResult?.metadata?.get(METADATA_EXECUTION_KIND),
      )
      assertEquals(
        OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE.wireValue,
        completion.runRecord.lastResult
          ?.metadata
          ?.get(OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY),
      )
      assertNull(completion.checkpoint)
    } finally {
      second?.let { bound -> unbind(context, bound.binding) }
      awaitRuntimeServiceStop(context, target)
      llmSettingsStore.save(previousLlmSettings)
      runCatching { chatSessionStore.deleteSession(sessionId) }
      runCatching { chatSessionStore.selectSession(previousActiveSessionId) }
      deleteRuntimeSessionState(context, sessionId)
    }
  }

  @Test(timeout = 120_000L)
  fun detachedProcessDeathInterruptsUncheckpointedWorkWithoutReplay() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val processName = "${context.packageName}:runtime_controller"
    val first = bindController(context, target)
    val firstSnapshot = loadProjection(first.controller)
    val firstPid = awaitProcessPid(context, processName)
    val chatSessionStore = ChatSessionLocalStore.fromContext(context)
    val previousActiveSessionId = chatSessionStore.loadState().activeSession.sessionId
    val sessionId = chatSessionStore.copySession(previousActiveSessionId).activeSession.sessionId
    val uniqueSuffix = System.nanoTime().toString()
    val taskId = "runtime-isolation-uncheckpointed-task-$uniqueSuffix"
    val runId = "runtime-isolation-uncheckpointed-run-$uniqueSuffix"
    val scheduleId = "runtime-isolation-uncheckpointed-schedule-$uniqueSuffix"
    var second: BoundController? = null

    try {
      seedRunningTaskWithoutCheckpoint(
        context = context,
        sessionId = sessionId,
        taskId = taskId,
        runId = runId,
        scheduleId = scheduleId,
      )

      Process.killProcess(firstPid)
      unbind(context, first.binding)
      awaitProcessExit(context, processName, firstPid)

      val leaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context)
      val staleLease = awaitLease(
        store = leaseStore,
        target = target,
        processStartId = firstSnapshot.runtimeOwnerLifecycle.processStartId,
      )
      val waitMs = (staleLease.expiresAtEpochMs - System.currentTimeMillis() + 1_000L)
        .coerceAtLeast(0L)
      if (waitMs > 0L) {
        Thread.sleep(waitMs)
      }

      assertTrue(
        openCrayRuntimeServiceEnvironment(context)
          .runtimeServiceAccessGateway
          .resumeInterruptedRuns(
            context = context,
            repairReason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
            target = target,
          ),
      )

      val rebuilt = bindController(context, target)
      second = rebuilt
      val secondSnapshot = loadProjection(rebuilt.controller)
      val secondPid = awaitProcessPid(context, processName)
      val interrupted = awaitInterruptedUncheckpointedTask(
        context = context,
        sessionId = sessionId,
        taskId = taskId,
      )

      assertTrue(secondPid != firstPid)
      assertEquals(
        firstSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
        secondSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
      )
      assertEquals(QueueTaskLifecycleState.FAILED, interrupted.lifecycleState)
      assertEquals(AgentTaskState.FAILED, interrupted.task.state)
      assertEquals(1, interrupted.attempt)
      assertEquals(1, interrupted.executionOrdinal)
      assertEquals(EXECUTION_KIND_INITIAL, interrupted.executionKind)
      assertEquals(ERROR_RESTART_REQUIRES_EXPLICIT_RETRY, interrupted.lastErrorCode)
      assertEquals(
        RunRecoveryAction.INTERRUPT_RECOVERY_REQUIRED.name.lowercase(),
        interrupted.task.metadata[RunLifecycleMetadataKeys.RECOVERY_ACTION],
      )
      assertEquals(
        "no_recoverable_checkpoint_after_restore",
        interrupted.task.metadata[METADATA_RECOVERY_REASON],
      )
      assertEquals("2", interrupted.task.metadata[RunLifecycleMetadataKeys.RUN_ATTEMPT])
      assertNull(
        interrupted.task.metadata[RunLifecycleMetadataKeys.RECOVERED_FROM_CHECKPOINT_ID],
      )
      assertTrue(interrupted.lastErrorMessage.orEmpty().contains("stopped"))

      Thread.sleep(500L)
      val stableTask = FileBackedAgentQueueSnapshotStoreFactory.fromContext(context)
        .forChatSession(sessionId)
        .load()
        ?.tasks
        ?.firstOrNull { task -> task.task.id == taskId }
      assertEquals(QueueTaskLifecycleState.FAILED, stableTask?.lifecycleState)
      assertEquals(1, stableTask?.executionOrdinal)
      assertEquals(ERROR_RESTART_REQUIRES_EXPLICIT_RETRY, stableTask?.lastErrorCode)
      assertNull(
        FileBackedPromptCheckpointStoreFactory.fromContext(context)
          .forChatSession(sessionId)
          .get(taskId),
      )
    } finally {
      second?.let { bound -> unbind(context, bound.binding) }
      unbind(context, first.binding)
      awaitRuntimeServiceStop(context, target)
      runCatching { chatSessionStore.deleteSession(sessionId) }
      runCatching { chatSessionStore.selectSession(previousActiveSessionId) }
      deleteRuntimeSessionState(context, sessionId)
    }
  }

  @Test(timeout = 210_000L)
  fun detachedProcessDeathReconnectsE2BManagedProcessThroughExternalEndpoint() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue(
      "Runtime-isolation endpoint override requires a debuggable target build.",
      (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val processName = "${context.packageName}:runtime_controller"
    stopRuntimeServiceProcessIfRunning(
      context = context,
      target = target,
      processName = processName,
    )
    val uniqueSuffix = System.nanoTime().toString()
    val sessionId = "runtime-isolation-e2b-session-$uniqueSuffix"
    val taskId = "runtime-isolation-e2b-task-$uniqueSuffix"
    val processId = "runtime-isolation-e2b-process-$uniqueSuffix"
    val sandboxId = "runtime-isolation-e2b-sandbox-$uniqueSuffix"
    val remoteWorkspaceRoot = "/home/user/opencray/$sandboxId"
    val remotePid = 654
    val workspaceRoot = AppAgentWorkspace.ensureRootForContext(context)
      .toAbsolutePath()
      .normalize()
    val endpointOverrideStore = RuntimeIsolationE2BEnvdEndpointOverrideStore.fromContext(context)
    val sandboxSettingsStore = SandboxSettingsStore.fromContext(context)
    val sandboxSettingsRepository = SandboxSettingsRepository.fromContext(context)
    val previousSandboxSettings = sandboxSettingsRepository.load()
    val e2bSessionStore = E2BSandboxSessionStore.fromContext(context)
    val previousE2BSession = e2bSessionStore.load()
    val endpointServer = RuntimeIsolationEnvdEndpointServer(processPid = remotePid)
    var first: BoundController? = null
    var second: BoundController? = null
    var secondPid: Int? = null

    try {
      endpointOverrideStore.save(endpointServer.baseUrl)
      sandboxSettingsRepository.save(
        state = previousSandboxSettings.state.copy(
          enabled = true,
          providerId = SandboxProviderId.E2B.wireValue,
          defaultBackend = SandboxExecutionBackendPreference.SANDBOX.wireValue,
          sessionMode = SandboxSessionMode.STICKY.wireValue,
          autoResume = true,
          e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
        ),
        e2bApiKey = "runtime-isolation-e2b-api-key",
      )
      e2bSessionStore.save(
        E2BSandboxSessionSnapshot(
          sandboxId = sandboxId,
          sandboxDomain = "e2b.app",
          envdAccessToken = "runtime-isolation-envd-token",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = System.currentTimeMillis(),
          remoteWorkspaceRoot = remoteWorkspaceRoot,
        ),
      )
      seedRunningE2BManagedProcess(
        context = context,
        sessionId = sessionId,
        taskId = taskId,
        processId = processId,
        sandboxId = sandboxId,
        remotePid = remotePid,
        workspaceRoot = workspaceRoot.toString(),
        remoteWorkspaceRoot = remoteWorkspaceRoot,
      )

      val firstBound = bindController(context, target)
      first = firstBound
      val firstProjection = loadProjection(firstBound.controller)
      val firstPid = awaitProcessPid(context, processName)
      val firstRequest = endpointServer.awaitRequest(index = 0)
      val firstAttached = awaitManagedProcessAttached(
        context = context,
        sessionId = sessionId,
        processId = processId,
        expectedOutput = "attached before process death",
        expectedProcessStartId = firstProjection.runtimeOwnerLifecycle.processStartId,
      )

      assertEquals("POST", firstRequest.method)
      assertEquals("/process.Process/Connect", firstRequest.path)
      assertEquals(
        "runtime-isolation-envd-token",
        firstRequest.headers["x-access-token"],
      )
      assertEquals(
        remotePid,
        E2BEnvdProcessProtoCodec.decodeConnectRequest(
          grpcPayload(firstRequest.body),
        ).process.pid,
      )
      assertEquals(ManagedProcessStatus.RUNNING, firstAttached.status)
      assertEquals("attached", firstAttached.metadata["sandboxCommandReconnectStatus"])
      assertEquals(
        "attached_live",
        firstAttached.metadata["sandboxCommandReconnectRecoveryState"],
      )

      Process.killProcess(firstPid)
      unbind(context, firstBound.binding)
      first = null
      awaitProcessExit(context, processName, firstPid)
      endpointServer.releaseFirstConnection()

      val leaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context)
      val staleLease = awaitLease(
        store = leaseStore,
        target = target,
        processStartId = firstProjection.runtimeOwnerLifecycle.processStartId,
      )
      val waitMs = (staleLease.expiresAtEpochMs - System.currentTimeMillis() + 1_000L)
        .coerceAtLeast(0L)
      if (waitMs > 0L) {
        Thread.sleep(waitMs)
      }
      assertTrue(
        openCrayRuntimeServiceEnvironment(context)
          .runtimeServiceAccessGateway
          .resumeInterruptedRuns(
            context = context,
            repairReason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
            target = target,
          ),
      )

      val secondBound = bindController(context, target)
      second = secondBound
      val rebuiltPid = awaitProcessPid(context, processName)
      secondPid = rebuiltPid
      val rebuiltLease = awaitRebuiltLease(
        store = leaseStore,
        target = target,
        previousProcessStartId = firstProjection.runtimeOwnerLifecycle.processStartId,
      )
      val secondRequest = endpointServer.awaitRequest(index = 1)
      val secondAttached = awaitManagedProcessAttached(
        context = context,
        sessionId = sessionId,
        processId = processId,
        expectedOutput = "attached after process death",
        expectedProcessStartId = rebuiltLease.processStartId,
      )

      assertTrue(rebuiltPid != firstPid)
      assertEquals(
        firstProjection.runtimeOwnerLifecycle.durableRuntimeControllerId,
        rebuiltLease.durableRuntimeControllerId,
      )
      assertEquals("POST", secondRequest.method)
      assertEquals("/process.Process/Connect", secondRequest.path)
      assertEquals(
        remotePid,
        E2BEnvdProcessProtoCodec.decodeConnectRequest(
          grpcPayload(secondRequest.body),
        ).process.pid,
      )
      assertEquals(ManagedProcessStatus.RUNNING, secondAttached.status)
      assertEquals("attached", secondAttached.metadata["sandboxCommandReconnectStatus"])
      assertEquals(
        "attached_live",
        secondAttached.metadata["sandboxCommandReconnectRecoveryState"],
      )
      assertEquals(
        ManagedProcessRestoreScope.CROSS_PROCESS.wireValue,
        secondAttached.metadata[MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY],
      )
      assertEquals(
        ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED.wireValue,
        secondAttached.metadata[MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY],
      )
      assertTrue(
        (secondAttached.metadata["sandboxCommandReconnectAttemptCount"]?.toIntOrNull() ?: 0) >= 2,
      )
      assertEquals(SandboxProviderId.E2B.wireValue, secondAttached.remoteHandle?.provider)
      assertEquals(remotePid.toString(), secondAttached.remoteHandle?.liveSelectorValue)
    } finally {
      secondPid?.let { pid ->
        runCatching { Process.killProcess(pid) }
        runCatching { awaitProcessExit(context, processName, pid) }
      }
      second?.let { bound -> unbind(context, bound.binding) }
      first?.let { bound -> unbind(context, bound.binding) }
      runCatching { awaitRuntimeServiceStop(context, target) }
      endpointServer.close()
      endpointOverrideStore.clear()
      if (previousE2BSession == null) {
        e2bSessionStore.clear()
      } else {
        e2bSessionStore.save(previousE2BSession)
      }
      if (previousSandboxSettings.e2bApiKey == null) {
        sandboxSettingsRepository.clearE2bApiKey()
        sandboxSettingsStore.save(previousSandboxSettings.state)
      } else {
        sandboxSettingsRepository.save(
          state = previousSandboxSettings.state,
          e2bApiKey = previousSandboxSettings.e2bApiKey,
        )
      }
      FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context).clear(target)
      deleteRuntimeSessionState(context, sessionId)
    }
  }

  @Test(timeout = 90_000L)
  fun detachedScheduleMutationRoutesWorkToMainProcessWorkManager() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val scheduleId = "runtime-isolation-${System.nanoTime()}"
    val sessionId = "runtime-isolation-session"
    val nowEpochMs = System.currentTimeMillis()
    val specStore = FileBackedScheduledTaskSpecStoreFactory.fromContext(context).create()
    val workManager = WorkManager.getInstance(context)
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    grantNotificationPermission(context)
    RuntimeNotificationChannelRegistry.ensureRegistered(context)
    specStore.upsert(
      ScheduledTaskSpec(
        scheduleId = scheduleId,
        sessionId = sessionId,
        title = "Runtime isolation schedule",
        enabled = true,
        trigger = ScheduledTrigger.At(atEpochMs = nowEpochMs + TimeUnit.HOURS.toMillis(1L)),
        payload = ScheduledTaskPayload(prompt = "Verify detached scheduler routing"),
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
      ),
    )

    try {
      runtimeNotificationCoordinator(context).onScheduledDispatchOutcome(
        ScheduledTaskDispatchOutcome(
          result = ScheduledTaskRunResult.FAILED_DISPATCH,
          scheduleId = scheduleId,
          scheduleRunId = "schedule-run-$scheduleId",
          sessionId = sessionId,
          failureReason = "instrumentation_test",
        ),
      )
      val notification = awaitNotification(
        notificationManager = notificationManager,
        subText = "Runtime isolation schedule",
      )
      val snoozeAction = notification.notification.actions[1]
      assertServicePendingIntent(context, snoozeAction.actionIntent)

      snoozeAction.actionIntent.send()

      val snoozedSpec = awaitSnoozedSpec(specStore, scheduleId)
      assertTrue(requireNotNull(snoozedSpec.snoozedUntilEpochMs) > nowEpochMs)
      val detached = bindController(context, target)
      assertRemoteController(detached, target)
      assertTrue(
        awaitProcessPid(context, "${context.packageName}:runtime_controller") != Process.myPid(),
      )
      val workInfo = awaitScheduledWakeWork(workManager, scheduleId)
      assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)
      Thread.sleep(500L)
      assertEquals(
        WorkInfo.State.ENQUEUED,
        latestScheduledWakeWork(workManager, scheduleId)?.state,
      )
    } finally {
      notificationManager.cancelAll()
      specStore.remove(scheduleId)
      runCatching { resyncEnabledScheduledTasksFromContext(context) }
      runCatching {
        workManager.cancelUniqueWork(scheduleWakeWorkName(scheduleId))
          .result
          .get(5L, TimeUnit.SECONDS)
      }
      context.stopService(RuntimeServiceIntentFactory().baseIntent(context, target))
    }
  }

  @Test(timeout = 90_000L)
  fun approvalNotificationActionsSendImmutableServicePendingIntentsToDetachedRuntime() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val coordinator = runtimeNotificationCoordinator(context)
    grantNotificationPermission(context)
    RuntimeNotificationChannelRegistry.ensureRegistered(context)
    val suffix = System.nanoTime().toString()

    try {
      val approveTaskId = "approval-approve-$suffix"
      val approveNotificationId = RuntimeNotificationKeys.approvalId(approveTaskId)
      notificationManager.notify(
        approveNotificationId,
        coordinator.buildApprovalNotification(
          approvalNotificationModel(
            taskId = approveTaskId,
            runId = "run-$approveTaskId",
            target = target,
          ),
        ),
      )
      val approveNotification = awaitNotification(notificationManager, approveNotificationId)
      val approveAction = approveNotification.notification.actions[1]
      assertServicePendingIntent(context, approveAction.actionIntent)
      approveAction.actionIntent.send()
      awaitNotificationRemoved(notificationManager, approveNotificationId)

      val detached = bindController(context, target)
      assertRemoteController(detached, target)
      assertTrue(
        awaitProcessPid(context, "${context.packageName}:runtime_controller") != Process.myPid(),
      )

      val rejectTaskId = "approval-reject-$suffix"
      val rejectNotificationId = RuntimeNotificationKeys.approvalId(rejectTaskId)
      notificationManager.notify(
        rejectNotificationId,
        coordinator.buildApprovalNotification(
          approvalNotificationModel(
            taskId = rejectTaskId,
            runId = "run-$rejectTaskId",
            target = target,
          ),
        ),
      )
      val rejectNotification = awaitNotification(notificationManager, rejectNotificationId)
      val rejectAction = rejectNotification.notification.actions[2]
      assertServicePendingIntent(context, rejectAction.actionIntent)
      rejectAction.actionIntent.send()
      awaitNotificationRemoved(notificationManager, rejectNotificationId)
    } finally {
      notificationManager.cancelAll()
      context.stopService(RuntimeServiceIntentFactory().baseIntent(context, target))
    }
  }

  private fun runtimeNotificationCoordinator(
    context: Context,
  ): RuntimeNotificationCoordinator = RuntimeNotificationCoordinator(
    appContext = context,
    localizedContext = context,
    chatSessionStore = ChatSessionLocalStore.fromContext(context),
    hostAccess = NoOpRuntimeNotificationHostAccess,
    scheduledTaskSpecStore = FileBackedScheduledTaskSpecStoreFactory.fromContext(context).create(),
    scheduledTaskRunRecordStore =
      FileBackedScheduledTaskRunRecordStoreFactory.fromContext(context).create(),
    notificationSettingsProvider = {
      RuntimeNotificationSettingsState(quietHoursEnabled = false)
    },
    runtimeServiceAccessGateway = openCrayRuntimeServiceEnvironment(context)
      .runtimeServiceAccessGateway,
    appVisibilitySignalAccess = AlwaysBackgroundVisibilitySignalAccess,
  )

  private fun approvalNotificationModel(
    taskId: String,
    runId: String,
    target: RuntimeServiceTarget,
  ): RuntimeApprovalNotificationModel = RuntimeApprovalNotificationModel(
    sessionId = "notification-action-session",
    sessionTitle = "Notification action session",
    runId = runId,
    taskId = taskId,
    runtimeTarget = target,
    title = "Approval required",
    body = "Review the requested action.",
    isHighRisk = false,
  )

  private fun grantNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT < 33) {
      return
    }
    InstrumentationRegistry.getInstrumentation().uiAutomation
      .executeShellCommand(
        "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
      )
      .close()
  }

  private fun awaitNotification(
    notificationManager: NotificationManager,
    notificationId: Int,
    timeoutMs: Long = 10_000L,
  ): StatusBarNotification {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      notificationManager.activeNotifications
        .firstOrNull { notification -> notification.id == notificationId }
        ?.let { notification -> return notification }
      Thread.sleep(100L)
    }
    error("Timed out waiting for notification id '$notificationId'.")
  }

  private fun awaitNotification(
    notificationManager: NotificationManager,
    subText: String,
    timeoutMs: Long = 10_000L,
  ): StatusBarNotification {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      notificationManager.activeNotifications
        .firstOrNull { notification ->
          notification.notification.extras
            .getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?.toString() == subText
        }
        ?.let { notification -> return notification }
      Thread.sleep(100L)
    }
    error("Timed out waiting for notification '$subText'.")
  }

  private fun awaitNotificationRemoved(
    notificationManager: NotificationManager,
    notificationId: Int,
    timeoutMs: Long = 20_000L,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (notificationManager.activeNotifications.none { notification ->
          notification.id == notificationId
        }
      ) {
        return
      }
      Thread.sleep(100L)
    }
    error("Timed out waiting for notification id '$notificationId' to be dismissed.")
  }

  private fun assertServicePendingIntent(
    context: Context,
    pendingIntent: PendingIntent,
  ) {
    assertEquals(context.packageName, pendingIntent.creatorPackage)
    if (Build.VERSION.SDK_INT >= 31) {
      assertTrue(pendingIntent.isImmutable)
      assertTrue(pendingIntent.isService)
    }
  }

  private fun bindController(
    context: Context,
    target: RuntimeServiceTarget,
  ): BoundController {
    val binding = ServiceBinding()
    val bound = context.bindService(
      RuntimeServiceIntentFactory().baseIntent(context, target),
      binding,
      Context.BIND_AUTO_CREATE,
    )
    assertTrue("bindService returned false for ${target.wireValue}", bound)
    bindings += binding
    return BoundController(
      binding = binding,
      binder = binding.awaitBinder(),
    )
  }

  private fun unbind(context: Context, binding: ServiceBinding) {
    runCatching { context.unbindService(binding) }
    bindings.remove(binding)
  }

  private fun assertRemoteController(
    bound: BoundController,
    target: RuntimeServiceTarget,
  ) {
    assertNull(
      bound.binder.queryLocalInterface("com.opencray.app.ipc.IRuntimeServiceController"),
    )
    assertEquals(RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION, bound.controller.protocolVersion)
    assertEquals(target.wireValue, bound.controller.runtimeTarget)
    assertEquals(RuntimeServiceControllerCapabilities.ALL, bound.controller.capabilities)
  }

  private fun loadProjection(
    controller: IRuntimeServiceController,
  ): RuntimeServiceProjectionSnapshot = requireNotNull(
    decodeRuntimeServiceProjectionSnapshot(controller.loadProjectionSnapshotJson()),
  )

  private fun awaitProcessPid(
    context: Context,
    processName: String,
    timeoutMs: Long = 30_000L,
  ): Int {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val pid = activityManager.runningAppProcesses
        .orEmpty()
        .firstOrNull { process -> process.processName == processName }
        ?.pid
      if (pid != null && pid > 0) {
        return pid
      }
      Thread.sleep(100L)
    }
    error("Timed out waiting for process '$processName'.")
  }

  private fun awaitProcessExit(
    context: Context,
    processName: String,
    previousPid: Int,
    timeoutMs: Long = 15_000L,
  ) {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val previousProcessAlive = activityManager.runningAppProcesses
        .orEmpty()
        .any { process -> process.processName == processName && process.pid == previousPid }
      if (!previousProcessAlive) {
        return
      }
      Thread.sleep(100L)
    }
    error("Timed out waiting for process '$processName' pid=$previousPid to exit.")
  }

  private fun stopRuntimeServiceProcessIfRunning(
    context: Context,
    target: RuntimeServiceTarget,
    processName: String,
  ) {
    runCatching { awaitRuntimeServiceStop(context, target) }
    val activityManager = context.getSystemService(ActivityManager::class.java)
    activityManager.runningAppProcesses
      .orEmpty()
      .firstOrNull { process -> process.processName == processName }
      ?.pid
      ?.takeIf { pid -> pid > 0 }
      ?.let { pid ->
        Process.killProcess(pid)
        awaitProcessExit(context, processName, pid)
      }
    FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context).clear(target)
  }

  private fun awaitLease(
    store: RuntimeServiceOwnerLeaseStore,
    target: RuntimeServiceTarget,
    processStartId: String,
    timeoutMs: Long = 10_000L,
  ): RuntimeServiceOwnerLease {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      store.load(target)
        ?.takeIf { lease -> lease.processStartId == processStartId }
        ?.let { lease -> return lease }
      Thread.sleep(100L)
    }
    error(
      "Timed out waiting for ${target.wireValue} owner lease " +
        "from processStartId=$processStartId.",
    )
  }

  private fun awaitRebuiltLease(
    store: RuntimeServiceOwnerLeaseStore,
    target: RuntimeServiceTarget,
    previousProcessStartId: String,
    timeoutMs: Long = 10_000L,
  ): RuntimeServiceOwnerLease {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      store.load(target)
        ?.takeIf { lease ->
          lease.isHeld && lease.processStartId != previousProcessStartId
        }
        ?.let { lease -> return lease }
      Thread.sleep(100L)
    }
    error(
      "Timed out waiting for rebuilt ${target.wireValue} owner lease after " +
        "processStartId=$previousProcessStartId.",
    )
  }

  private fun seedRunningFinalActionCheckpoint(
    context: Context,
    sessionId: String,
    taskId: String,
    runId: String,
    scheduleId: String,
    expectedAnswer: String,
  ) {
    val nowEpochMs = System.currentTimeMillis()
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 0,
      toolCallCount = 0,
      pendingActions = listOf(
        OpenCraySerializableModelAction.Final(
          answer = expectedAnswer,
          responseFormat = "text",
        ),
      ),
    )
    FileBackedPromptCheckpointStoreFactory.fromContext(context)
      .forChatSession(sessionId)
      .upsert(
        PersistedPromptCheckpoint(
          sessionId = sessionId,
          runId = runId,
          taskId = taskId,
          checkpointId = "checkpoint-$taskId",
          checkpointKind = PromptCheckpointKind.ACTION_BATCH_PARSED,
          createdAtEpochMs = nowEpochMs,
          updatedAtEpochMs = nowEpochMs,
          promptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
          promptResumeState = resumeState,
        ),
      )
    FileBackedRunEventJournalStoreFactory.fromContext(context)
      .forChatSession(sessionId)
      .appendCheckpoint(
        runId = runId,
        taskId = taskId,
        emission = OpenCrayPromptCheckpointEmission(
          boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
          state = resumeState,
          emittedAtEpochMs = nowEpochMs,
        ),
      )
    FileBackedAgentQueueSnapshotStoreFactory.fromContext(context)
      .forChatSession(sessionId)
      .save(
        SessionQueueSnapshot(
          sessionId = sessionId,
          agentId = "opencray-flutter-host",
          lifecycleState = SessionLifecycleState.RUNNING,
          nextEnqueueOrder = 2L,
          tasks = listOf(
            SessionQueueTaskSnapshot(
              enqueueOrder = 1L,
              task = AgentTask(
                id = taskId,
                type = AgentTaskType.PROMPT,
                input = "Resume the detached checkpoint without replaying the prompt.",
                state = AgentTaskState.RUNNING,
                policyDecision = PolicyDecision(
                  outcome = PolicyDecisionOutcome.ALLOW,
                  reasonCode = "RUNTIME_ISOLATION_CHECKPOINT_TEST",
                ),
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
                metadata = mapOf(
                  AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                  AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
                  ScheduledTaskMetadataKeys.SCHEDULE_ID to scheduleId,
                  METADATA_EXECUTION_ID to "execution-before-process-death",
                  METADATA_EXECUTION_KIND to EXECUTION_KIND_INITIAL,
                  METADATA_EXECUTION_ORDINAL to "1",
                ),
              ),
              lifecycleState = QueueTaskLifecycleState.RUNNING,
              attempt = 1,
              executionOrdinal = 1,
              executionId = "execution-before-process-death",
              executionKind = EXECUTION_KIND_INITIAL,
            ),
          ),
          updatedAtEpochMs = nowEpochMs,
        ),
      )
  }

  private fun seedRunningTaskWithoutCheckpoint(
    context: Context,
    sessionId: String,
    taskId: String,
    runId: String,
    scheduleId: String,
  ) {
    val nowEpochMs = System.currentTimeMillis()
    FileBackedAgentQueueSnapshotStoreFactory.fromContext(context)
      .forChatSession(sessionId)
      .save(
        SessionQueueSnapshot(
          sessionId = sessionId,
          agentId = "opencray-flutter-host",
          lifecycleState = SessionLifecycleState.RUNNING,
          nextEnqueueOrder = 2L,
          tasks = listOf(
            SessionQueueTaskSnapshot(
              enqueueOrder = 1L,
              task = AgentTask(
                id = taskId,
                type = AgentTaskType.PROMPT,
                input = "Do not replay this uncheckpointed detached task.",
                state = AgentTaskState.RUNNING,
                policyDecision = PolicyDecision(
                  outcome = PolicyDecisionOutcome.ALLOW,
                  reasonCode = "RUNTIME_ISOLATION_UNCHECKPOINTED_TEST",
                ),
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
                metadata = mapOf(
                  AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                  AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId,
                  ScheduledTaskMetadataKeys.SCHEDULE_ID to scheduleId,
                  METADATA_EXECUTION_ID to "execution-before-uncheckpointed-process-death",
                  METADATA_EXECUTION_KIND to EXECUTION_KIND_INITIAL,
                  METADATA_EXECUTION_ORDINAL to "1",
                ),
              ),
              lifecycleState = QueueTaskLifecycleState.RUNNING,
              attempt = 1,
              executionOrdinal = 1,
              executionId = "execution-before-uncheckpointed-process-death",
              executionKind = EXECUTION_KIND_INITIAL,
            ),
          ),
          updatedAtEpochMs = nowEpochMs,
        ),
      )
  }

  private fun seedRunningE2BManagedProcess(
    context: Context,
    sessionId: String,
    taskId: String,
    processId: String,
    sandboxId: String,
    remotePid: Int,
    workspaceRoot: String,
    remoteWorkspaceRoot: String,
  ) {
    val sourceIdentity = ManagedProcessRuntimeIdentity(
      processStartId = "runtime-isolation-seed-process",
      runtimeControllerId = "runtime-isolation-seed-controller",
      durableRuntimeControllerId = "runtime-isolation-detached-controller",
    )
    val runtimeRoot = File(
      context.filesDir,
      FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
    )
    val registryDirectory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
      .directoryForSession(sessionId)
    val registry = FileBackedAgentProcessRegistry(
      directory = registryDirectory,
      controllerFactory = ManagedProcessControllerFactory { request ->
        SnapshotManagedProcessController(
          ManagedProcessSnapshot(
            processId = request.processId,
            taskId = request.taskId,
            command = request.command,
            args = request.args,
            workingDirectory = request.workingDirectory,
            status = ManagedProcessStatus.RUNNING,
            processStarted = true,
            timeoutMs = request.timeoutMs,
            stdout = "booting",
            startedAtEpochMs = request.requestedAtEpochMs,
            updatedAtEpochMs = request.requestedAtEpochMs,
            remoteHandle = ManagedProcessRemoteHandle(
              provider = SandboxProviderId.E2B.wireValue,
              sandboxId = sandboxId,
              sandboxDomain = "e2b.app",
              commandIdKind = "tag",
              commandId = request.processId,
              providerHandleKind = "envd_process",
              stableSelectorKind = "tag",
              stableSelectorValue = request.processId,
              liveSelectorKind = "pid",
              liveSelectorValue = remotePid.toString(),
              remoteWorkspaceRoot = remoteWorkspaceRoot,
              remoteWorkingDirectory = "$remoteWorkspaceRoot/repo",
              nativeProtocol = "envd_connect_process_v1",
            ),
            observationState = ManagedProcessObservationState(
              mode = "host_managed_snapshot",
              hostEventCount = 1L,
              hostCursor = "host_seq_1",
              stdoutBytes = 7L,
              stderrBytes = 0L,
              providerMode = "provider_event_stream_host_buffered",
              providerEventCount = 1L,
              providerCursor = "envd_seq_1",
              providerBackfillSupported = false,
              liveObservationSupported = true,
              cursorResumeSupported = false,
              backfillSupported = false,
            ),
            ownerIdentity = request.ownerIdentity,
            metadata = request.metadata,
          ),
        )
      },
      runtimeIdentity = sourceIdentity,
    )
    registry.start(
      ManagedProcessStartRequest(
        processId = processId,
        taskId = taskId,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = File(workspaceRoot, "repo").path,
        timeoutMs = TimeUnit.MINUTES.toMillis(5L),
        requestedAtEpochMs = System.currentTimeMillis(),
        ownerIdentity = sourceIdentity,
        metadata = mapOf(
          "executionBackend" to ResolvedExecutionBackend.SANDBOX_REMOTE.wireValue,
          "runtimeKind" to "command_exec",
          "runtimeBackend" to "e2b_envd_native_command",
          "runtimeTransport" to "connect_proto_minimal",
          "sandboxProvider" to SandboxProviderId.E2B.wireValue,
          "sandboxCommandBackendKind" to "provider_native",
          "sandboxCommandBackendResolvedKind" to "provider_native",
          "sandboxCommandSupportsReconnect" to "true",
          "sandboxCommandNativeProtocol" to "envd_connect_process_v1",
          "sandboxCommandPid" to remotePid.toString(),
          "remoteWorkspaceRoot" to remoteWorkspaceRoot,
          "remoteWorkingDirectory" to "$remoteWorkspaceRoot/repo",
        ),
      ),
    )
  }

  private fun awaitManagedProcessAttached(
    context: Context,
    sessionId: String,
    processId: String,
    expectedOutput: String,
    expectedProcessStartId: String,
    timeoutMs: Long = 30_000L,
  ): ManagedProcessSnapshot {
    val runtimeRoot = File(
      context.filesDir,
      FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
    )
    val registryDirectory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
      .directoryForSession(sessionId)
    val projectionRegistry = FileBackedAgentProcessRegistry(
      directory = registryDirectory,
      restoreMode = ManagedProcessRestoreMode.PROJECTION_ONLY,
    )
    val deadline = System.currentTimeMillis() + timeoutMs
    var latest: ManagedProcessSnapshot? = null
    while (System.currentTimeMillis() < deadline) {
      latest = projectionRegistry.read(processId)
      if (
        latest?.metadata?.get("sandboxCommandReconnectStatus") == "attached" &&
        latest.stdout.contains(expectedOutput) &&
        latest.metadata[MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY] ==
        expectedProcessStartId
      ) {
        return latest
      }
      Thread.sleep(100L)
    }
    error(
      "Timed out waiting for E2B managed process attach processId=$processId " +
        "status=${latest?.metadata?.get("sandboxCommandReconnectStatus")} " +
        "recoveryState=${latest?.metadata?.get("sandboxCommandReconnectRecoveryState")} " +
        "ownerProcessStartId=" +
        latest?.metadata?.get(MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY) +
        " stdout=${latest?.stdout}.",
    )
  }

  private fun grpcPayload(bodyBytes: ByteArray): ByteArray {
    require(bodyBytes.size >= 5)
    val length =
      ((bodyBytes[1].toInt() and 0xFF) shl 24) or
        ((bodyBytes[2].toInt() and 0xFF) shl 16) or
        ((bodyBytes[3].toInt() and 0xFF) shl 8) or
        (bodyBytes[4].toInt() and 0xFF)
    require(length >= 0 && bodyBytes.size >= length + 5)
    return bodyBytes.copyOfRange(5, length + 5)
  }

  private fun awaitCompletedCheckpointResume(
    context: Context,
    sessionId: String,
    taskId: String,
    runId: String,
    expectedAnswer: String,
    timeoutMs: Long = 45_000L,
  ): CompletedCheckpointResume {
    val queueStore = FileBackedAgentQueueSnapshotStoreFactory.fromContext(context)
      .forChatSession(sessionId)
    val runRecordStore = FileBackedAgentRunRecordStoreFactory.fromContext(context)
      .forChatSession(sessionId)
    val checkpointStore = FileBackedPromptCheckpointStoreFactory.fromContext(context)
      .forChatSession(sessionId)
    val deadline = System.currentTimeMillis() + timeoutMs
    var latestTask: SessionQueueTaskSnapshot? = null
    var latestRunRecord: PersistedAgentRunRecord? = null
    var latestCheckpoint: PersistedPromptCheckpoint? = null
    while (System.currentTimeMillis() < deadline) {
      latestTask = queueStore.load()?.tasks?.firstOrNull { task -> task.task.id == taskId }
      latestRunRecord = runRecordStore.list().firstOrNull { record -> record.runId == runId }
      latestCheckpoint = checkpointStore.get(taskId)
      val completedTask = latestTask
      val completedRunRecord = latestRunRecord
      if (
        completedTask?.lifecycleState == QueueTaskLifecycleState.COMPLETED &&
        completedTask.executionKind == EXECUTION_KIND_CHECKPOINT_RESUME &&
        completedRunRecord?.lastResult?.status == ExecutionStatus.SUCCESS &&
        completedRunRecord.lastResult?.stdout == expectedAnswer &&
        latestCheckpoint == null
      ) {
        return CompletedCheckpointResume(
          task = completedTask,
          runRecord = completedRunRecord,
          checkpoint = latestCheckpoint,
        )
      }
      Thread.sleep(100L)
    }
    error(
      "Timed out waiting for checkpoint resume task=$taskId " +
        "queueState=${latestTask?.lifecycleState} executionKind=${latestTask?.executionKind} " +
        "resultStatus=${latestRunRecord?.lastResult?.status} " +
        "resultError=${latestRunRecord?.lastResult?.errorCode} " +
        "checkpointKind=${latestCheckpoint?.checkpointKind}.",
    )
  }

  private fun awaitInterruptedUncheckpointedTask(
    context: Context,
    sessionId: String,
    taskId: String,
    timeoutMs: Long = 30_000L,
  ): SessionQueueTaskSnapshot {
    val queueStore = FileBackedAgentQueueSnapshotStoreFactory.fromContext(context)
      .forChatSession(sessionId)
    val checkpointStore = FileBackedPromptCheckpointStoreFactory.fromContext(context)
      .forChatSession(sessionId)
    val deadline = System.currentTimeMillis() + timeoutMs
    var latestTask: SessionQueueTaskSnapshot? = null
    var latestCheckpoint: PersistedPromptCheckpoint? = null
    while (System.currentTimeMillis() < deadline) {
      latestTask = queueStore.load()?.tasks?.firstOrNull { task -> task.task.id == taskId }
      latestCheckpoint = checkpointStore.get(taskId)
      if (
        latestTask?.lifecycleState == QueueTaskLifecycleState.FAILED &&
        latestTask.lastErrorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY &&
        latestCheckpoint == null
      ) {
        return latestTask
      }
      Thread.sleep(100L)
    }
    error(
      "Timed out waiting for uncheckpointed interruption task=$taskId " +
        "queueState=${latestTask?.lifecycleState} " +
        "executionOrdinal=${latestTask?.executionOrdinal} " +
        "errorCode=${latestTask?.lastErrorCode} " +
        "recoveryReason=${latestTask?.task?.metadata?.get(METADATA_RECOVERY_REASON)} " +
        "checkpointKind=${latestCheckpoint?.checkpointKind}.",
    )
  }

  private fun deleteRuntimeSessionState(context: Context, sessionId: String) {
    runCatching {
      val runtimeRoot = File(
        context.filesDir,
        FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
      ).canonicalFile
      val sessionDirectory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
        .directoryForSession(sessionId)
        .canonicalFile
      check(sessionDirectory.parentFile == runtimeRoot)
      sessionDirectory.deleteRecursively()
    }
  }

  @Suppress("DEPRECATION")
  private fun awaitRuntimeServiceStop(
    context: Context,
    target: RuntimeServiceTarget,
    timeoutMs: Long = 10_000L,
  ) {
    val intent = RuntimeServiceIntentFactory().baseIntent(context, target)
    val component = requireNotNull(intent.component)
    context.stopService(intent)
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val running = activityManager.getRunningServices(Int.MAX_VALUE)
        .any { service -> service.service == component }
      if (!running) {
        Thread.sleep(250L)
        return
      }
      Thread.sleep(100L)
    }
    error("Timed out waiting for runtime service '$component' to stop.")
  }

  private fun awaitSnoozedSpec(
    store: ScheduledTaskSpecStore,
    scheduleId: String,
    timeoutMs: Long = 20_000L,
  ): ScheduledTaskSpec {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      store.get(scheduleId)
        ?.takeIf { spec -> spec.snoozedUntilEpochMs != null }
        ?.let { spec -> return spec }
      Thread.sleep(100L)
    }
    error("Timed out waiting for schedule '$scheduleId' to be snoozed.")
  }

  private fun awaitScheduledWakeWork(
    workManager: WorkManager,
    scheduleId: String,
    timeoutMs: Long = 20_000L,
  ): WorkInfo {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      latestScheduledWakeWork(workManager, scheduleId)
        ?.takeIf { workInfo -> workInfo.state == WorkInfo.State.ENQUEUED }
        ?.let { workInfo -> return workInfo }
      Thread.sleep(100L)
    }
    error("Timed out waiting for main-process WorkManager wake for '$scheduleId'.")
  }

  private fun latestScheduledWakeWork(
    workManager: WorkManager,
    scheduleId: String,
  ): WorkInfo? = workManager
    .getWorkInfosForUniqueWork(scheduleWakeWorkName(scheduleId))
    .get(5L, TimeUnit.SECONDS)
    .lastOrNull()

  private object AlwaysBackgroundVisibilitySignalAccess : AppVisibilitySignalAccess {
    override fun currentVisibility(): Boolean = false

    override fun observe(listener: (Boolean) -> Unit): () -> Unit = { }
  }

  private object NoOpRuntimeNotificationHostAccess : RuntimeNotificationHostAccess {
    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
      HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary()

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess =
      error("Runtime notification host session access is unused.")

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      error("Runtime notification journal access is unused.")

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      error("Runtime notification checkpoint access is unused.")

    override fun supplementStore(sessionId: String): SessionSupplementStore =
      error("Runtime notification supplement access is unused.")
  }

  private data class BoundController(
    val binding: ServiceBinding,
    val binder: IBinder,
  ) {
    val controller: IRuntimeServiceController = requireNotNull(
      IRuntimeServiceController.Stub.asInterface(binder),
    )
  }

  private data class CompletedCheckpointResume(
    val task: SessionQueueTaskSnapshot,
    val runRecord: PersistedAgentRunRecord,
    val checkpoint: PersistedPromptCheckpoint?,
  )

  private class SnapshotManagedProcessController(
    private val snapshot: ManagedProcessSnapshot,
  ) : ManagedProcessController {
    override fun snapshot(): ManagedProcessSnapshot = snapshot

    override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot

    override fun terminate(): ManagedProcessSnapshot = snapshot
  }

  private class ServiceBinding : ServiceConnection {
    private val connected = CountDownLatch(1)

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var failure: String? = null

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
      binder = service
      connected.countDown()
    }

    override fun onNullBinding(name: ComponentName) {
      failure = "null_binding"
      connected.countDown()
    }

    override fun onServiceDisconnected(name: ComponentName) {
      failure = "service_disconnected"
      connected.countDown()
    }

    override fun onBindingDied(name: ComponentName) {
      failure = "binding_died"
      connected.countDown()
    }

    fun awaitBinder(timeoutSeconds: Long = 60L): IBinder {
      assertTrue(
        "Timed out waiting for runtime service binding.",
        connected.await(timeoutSeconds, TimeUnit.SECONDS),
      )
      assertNull("Runtime service binding failed: $failure", failure)
      assertNotNull(binder)
      return requireNotNull(binder)
    }
  }
}
