package com.opencray.app

import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBootstrapServiceAccessTest : AgentBootstrapTestBase() {
  @Test
  fun ensureStartedOnlyRequestsServiceStartWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    OpenCrayRuntimeServiceAccess.ensureStarted(context)

    val startedRequest = recordingStarter.startedRequests.single()
    assertSame(recordingEndpoint.baseIntentSentinel, startedRequest.intent)
    assertEquals(1, recordingEndpoint.baseIntentCallCount)
    assertFalse(startedRequest.foreground)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun ensureClientUsesInjectedProviderAndCachesInstance() {
    val context = MinimalContext()
    val expectedClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")
    }
    val provider = RecordingRuntimeServiceClientProvider(expectedClient)
    OpenCrayRuntimeServiceAccess.setRuntimeServiceClientProviderForTest(provider)

    val first = OpenCrayRuntimeServiceAccess.ensureClient(context)
    val second = OpenCrayRuntimeServiceAccess.ensureClient(context)

    assertSame(expectedClient, first)
    assertSame(first, second)
    assertEquals(1, provider.createCallCount)
    assertEquals(listOf(context), provider.createdContexts)
    assertTrue(recordingStarter.startedRequests.isEmpty())
    assertEquals(0, recordingEndpoint.baseIntentCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun scheduledTaskWakeReceiverUsesEnvironmentRuntimeServiceAccessGateway() {
    val recordedCommands = mutableListOf<ScheduledTaskWakeCommand>()
    val recordedTargets = mutableListOf<RuntimeServiceTarget>()
    val context = RuntimeEnvironmentContext(
      environment = OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        runtimeServiceAccessGateway = object : RuntimeServiceAccessGateway {
          override fun ensureClient(
            context: Context,
            target: RuntimeServiceTarget,
          ): OpenCrayRuntimeServiceClient = error("Unexpected client access.")

          override fun startScheduledTask(
            context: Context,
            command: ScheduledTaskWakeCommand,
            target: RuntimeServiceTarget,
          ): Boolean {
            recordedCommands += command
            recordedTargets += target
            return true
          }

          override fun repairSchedules(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Boolean = error("Unexpected schedule repair.")

          override fun resumeInterruptedRuns(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Boolean = error("Unexpected interrupted-run repair.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
            target: RuntimeServiceTarget,
          ): android.app.PendingIntent = error("Unexpected approval pending intent.")
        },
      ),
    )
    val receiver = ScheduledTaskWakeReceiver()

    receiver.onReceive(
      context,
      RecordingCommandIntent()
        .putExtra(EXTRA_SCHEDULE_ID, "schedule-env")
        .putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MS, 99L),
    )

    assertEquals(1, recordedCommands.size)
    assertEquals("schedule-env", recordedCommands.single().scheduleId)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, recordedTargets.single())
  }

  @Test
  fun ensureClientCachesInstancesPerTarget() {
    val context = MinimalContext()
    val createdClients = mutableListOf<DisposableRuntimeServiceClient>()
    val createdBootstraps = mutableListOf<RuntimeServiceClientBootstrap>()
    OpenCrayRuntimeServiceAccess.setRuntimeServiceClientProviderForTest(
      RuntimeServiceClientProvider { _, bootstrap ->
        createdBootstraps += bootstrap
        DisposableRuntimeServiceClient().also(createdClients::add)
      },
    )

    val detachedFirst = OpenCrayRuntimeServiceAccess.ensureClient(
      context,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    ) as DisposableRuntimeServiceClient
    val detachedSecond = OpenCrayRuntimeServiceAccess.ensureClient(
      context,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    ) as DisposableRuntimeServiceClient
    val interactiveFirst = OpenCrayRuntimeServiceAccess.ensureClient(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    ) as DisposableRuntimeServiceClient
    val interactiveSecond = OpenCrayRuntimeServiceAccess.ensureClient(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    ) as DisposableRuntimeServiceClient

    assertSame(detachedFirst, detachedSecond)
    assertSame(interactiveFirst, interactiveSecond)
    assertNotSame(detachedFirst, interactiveFirst)
    assertEquals(
      listOf(RuntimeServiceTarget.DETACHED_BACKGROUND, RuntimeServiceTarget.INTERACTIVE),
      createdBootstraps.map(RuntimeServiceClientBootstrap::target),
    )
    assertEquals(2, createdClients.size)
  }

  @Test
  fun resetDetachedRuntimeDisposesCachedClientAndClearsRetainedExecutionController() {
    val context = MinimalContext()
    val createdClients = mutableListOf<DisposableRuntimeServiceClient>()
    OpenCrayRuntimeServiceAccess.setRuntimeServiceClientProviderForTest(
      RuntimeServiceClientProvider { _, _ ->
        DisposableRuntimeServiceClient().also(createdClients::add)
      },
    )
    val firstClient = OpenCrayRuntimeServiceAccess.ensureClient(context) as DisposableRuntimeServiceClient
    val previousController =
      recordingRuntimeServiceExecutionControllerHandle(
        temporaryFolder.newFolder("runtime-access-reset-controller").toPath(),
      )
    replaceDefaultRuntimeServiceExecutionController(previousController.controller)

    val result = resetDetachedRuntimeForTest()
    val secondClient = OpenCrayRuntimeServiceAccess.ensureClient(context) as DisposableRuntimeServiceClient

    assertSame(firstClient, result.previousClient)
    assertSame(previousController.controller, result.previousExecutionController)
    assertEquals(1, firstClient.disposeCallCount)
    assertEquals(1, previousController.disposeCallCount)
    assertNull(currentDefaultRuntimeServiceExecutionController())
    assertNotSame(firstClient, secondClient)
    assertEquals(2, createdClients.size)
  }

  @Test
  fun replaceDetachedRuntimeExecutionControllerSwapsRetainedControllerAndDropsCachedClient() {
    val context = MinimalContext()
    val createdClients = mutableListOf<DisposableRuntimeServiceClient>()
    OpenCrayRuntimeServiceAccess.setRuntimeServiceClientProviderForTest(
      RuntimeServiceClientProvider { _, _ ->
        DisposableRuntimeServiceClient().also(createdClients::add)
      },
    )
    val firstClient = OpenCrayRuntimeServiceAccess.ensureClient(context) as DisposableRuntimeServiceClient
    val currentController =
      recordingRuntimeServiceExecutionControllerHandle(
        temporaryFolder.newFolder("runtime-access-replace-current").toPath(),
      )
    val replacementController =
      recordingRuntimeServiceExecutionControllerHandle(
        temporaryFolder.newFolder("runtime-access-replace-next").toPath(),
      )
    replaceDefaultRuntimeServiceExecutionController(currentController.controller)

    val result = replaceDetachedRuntimeExecutionControllerForTest(
      replacementController.controller,
    )
    val secondClient = OpenCrayRuntimeServiceAccess.ensureClient(context) as DisposableRuntimeServiceClient

    assertSame(firstClient, result.previousClient)
    assertSame(currentController.controller, result.previousExecutionController)
    assertEquals(1, firstClient.disposeCallCount)
    assertEquals(1, currentController.disposeCallCount)
    assertEquals(0, replacementController.disposeCallCount)
    assertSame(
      replacementController.controller,
      currentDefaultRuntimeServiceExecutionController(),
    )
    assertNotSame(firstClient, secondClient)
    assertEquals(2, createdClients.size)
  }

  @Test
  fun runtimeServiceAccessEndpointDrivesBaseAndScheduledIntents() {
    val context = MinimalContext()
    val expectedBaseIntent = Intent("runtime-service-base")
    val expectedScheduledIntent = Intent("runtime-service-scheduled")
    OpenCrayRuntimeServiceAccess.setAccessDependenciesForTest(
      RuntimeServiceAccessDependencies(
        runtimeServiceStarter = recordingStarter,
        runtimeServiceClientProvider = RuntimeServiceClientProvider { _, _ ->
          error("Client provider should not be used in this test.")
        },
        runtimeServiceEndpoint = object : RuntimeServiceEndpoint {
          override fun baseIntent(context: Context, target: RuntimeServiceTarget): Intent =
            expectedBaseIntent

          override fun scheduledTaskIntent(
            context: Context,
            command: ScheduledTaskWakeCommand,
            target: RuntimeServiceTarget,
          ): Intent = expectedScheduledIntent

          override fun scheduledRepairIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Repair intent should not be used in this test.")

          override fun resetRuntimeIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Reset intent should not be used in this test.")

          override fun resumeInterruptedRunsIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Resume intent should not be used in this test.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
            target: RuntimeServiceTarget,
          ): android.app.PendingIntent = error("Approval pending intent should not be used in this test.")
        },
      ),
    )

    val baseIntent = OpenCrayRuntimeServiceAccess.baseIntent(context)
    val scheduledIntent = OpenCrayRuntimeServiceAccess.scheduledTaskServiceIntent(
      context,
      ScheduledTaskWakeCommand(
        scheduleId = "schedule-1",
        scheduleRunId = "schedule-run-1",
        triggeredAtEpochMs = 100L,
        triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      ),
    )

    assertSame(expectedBaseIntent, baseIntent)
    assertSame(expectedScheduledIntent, scheduledIntent)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultRuntimeServiceAccessGatewayDelegatesChatWriteActionPendingIntent() {
    val context = MinimalContext()
    val endpoint = RecordingRuntimeServiceEndpoint()
    val gateway = DefaultRuntimeServiceAccessGateway(
      RuntimeServiceAccessDependencies(
        runtimeServiceStarter = recordingStarter,
        runtimeServiceClientProvider = RuntimeServiceClientProvider { _, _ ->
          error("Client provider should not be used in this test.")
        },
        runtimeServiceEndpoint = endpoint,
      ),
    )

    val failure = runCatching {
      gateway.chatWriteActionPendingIntent(
        context = context,
        command = OpenCrayChatWriteCommand.RetryChatRun("run-retry-action"),
        requestCode = 61_337,
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        terminalNotificationTaskId = "task-retry-action",
      )
    }.exceptionOrNull()

    assertEquals(
      "Chat write action pending intent should not be used in this test.",
      failure?.message,
    )
    assertEquals(
      listOf(
        RecordedChatWriteActionPendingIntent(
          contextPackageName = "org.opencray.app",
          command = OpenCrayChatWriteCommand.RetryChatRun("run-retry-action"),
          requestCode = 61_337,
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
          terminalNotificationTaskId = "task-retry-action",
        ),
      ),
      endpoint.chatWriteActionPendingIntentRequests,
    )
  }

  @Test
  fun ensureClientBootstrapUsesInjectedStarterAndEndpoint() {
    val context = MinimalContext()
    val expectedBaseIntent = Intent("runtime-service-base")
    val expectedClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")
    }
    val provider = RecordingRuntimeServiceClientProvider(expectedClient)
    OpenCrayRuntimeServiceAccess.setAccessDependenciesForTest(
      RuntimeServiceAccessDependencies(
        runtimeServiceStarter = recordingStarter,
        runtimeServiceClientProvider = provider,
        runtimeServiceEndpoint = object : RuntimeServiceEndpoint {
          override fun baseIntent(context: Context, target: RuntimeServiceTarget): Intent =
            expectedBaseIntent

          override fun scheduledTaskIntent(
            context: Context,
            command: ScheduledTaskWakeCommand,
            target: RuntimeServiceTarget,
          ): Intent = error("Scheduled task intent should not be used in this test.")

          override fun scheduledRepairIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Repair intent should not be used in this test.")

          override fun resetRuntimeIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Reset intent should not be used in this test.")

          override fun resumeInterruptedRunsIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Resume intent should not be used in this test.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
            target: RuntimeServiceTarget,
          ): android.app.PendingIntent = error("Approval pending intent should not be used in this test.")
        },
      ),
    )

    val actualClient = OpenCrayRuntimeServiceAccess.ensureClient(context)
    val bootstrap = provider.createdBootstraps.single()
    bootstrap.startRequester(context)
    val baseIntent = bootstrap.serviceIntentFactory(context)

    assertSame(expectedClient, actualClient)
    assertSame(expectedBaseIntent, baseIntent)
    assertEquals(1, recordingStarter.startedRequests.size)
    val startedRequest = recordingStarter.startedRequests.single()
    assertSame(expectedBaseIntent, startedRequest.intent)
    assertFalse(startedRequest.foreground)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun ensureClientRebuildsCachedBootstrapWhenInjectedEndpointChanges() {
    val context = MinimalContext()
    val firstClient = DisposableRuntimeServiceClient()
    val secondClient = DisposableRuntimeServiceClient()
    val firstEndpoint = RecordingRuntimeServiceEndpoint().apply {
      chatWriteIntentSentinel = Intent("chat-write-first")
    }
    val secondEndpoint = RecordingRuntimeServiceEndpoint().apply {
      chatWriteIntentSentinel = Intent("chat-write-second")
    }
    var providerIndex = 0
    OpenCrayRuntimeServiceAccess.setAccessDependenciesForTest(
      RuntimeServiceAccessDependencies(
        runtimeServiceStarter = recordingStarter,
        runtimeServiceClientProvider = RuntimeServiceClientProvider { _, _ ->
          if (providerIndex++ == 0) firstClient else secondClient
        },
        runtimeServiceEndpoint = firstEndpoint,
      ),
    )

    val firstResolved = OpenCrayRuntimeServiceAccess.ensureClient(context)

    OpenCrayRuntimeServiceAccess.setRuntimeServiceEndpointForTest(secondEndpoint)

    val secondResolved = OpenCrayRuntimeServiceAccess.ensureClient(context)

    assertSame(firstClient, firstResolved)
    assertSame(secondClient, secondResolved)
    assertEquals(1, firstClient.disposeCallCount)
    assertEquals(0, secondClient.disposeCallCount)
  }

  @Test
  fun ensureClientRebuildsCachedBootstrapWhenInjectedStarterChanges() {
    val context = MinimalContext()
    val firstClient = DisposableRuntimeServiceClient()
    val secondClient = DisposableRuntimeServiceClient()
    val firstStarter = RecordingRuntimeServiceStarter()
    val secondStarter = RecordingRuntimeServiceStarter()
    val provider = RecordingRuntimeServiceClientProvider(firstClient)
    OpenCrayRuntimeServiceAccess.setAccessDependenciesForTest(
      RuntimeServiceAccessDependencies(
        runtimeServiceStarter = firstStarter,
        runtimeServiceClientProvider = provider,
        runtimeServiceEndpoint = recordingEndpoint,
      ),
    )

    val firstResolved = OpenCrayRuntimeServiceAccess.ensureClient(context)
    provider.clientOverride = secondClient

    OpenCrayRuntimeServiceAccess.setRuntimeServiceStarterForTest(secondStarter)

    val secondResolved = OpenCrayRuntimeServiceAccess.ensureClient(context)
    val rebuiltBootstrap = provider.createdBootstraps.last()
    rebuiltBootstrap.startRequester(context)

    assertSame(firstClient, firstResolved)
    assertSame(secondClient, secondResolved)
    assertEquals(1, firstClient.disposeCallCount)
    assertEquals(0, secondClient.disposeCallCount)
    assertEquals(0, firstStarter.startedRequests.size)
    assertEquals(1, secondStarter.startedRequests.size)
  }

  @Test
  fun ensureClientBootstrapChatWriteWakeUsesForegroundServiceAndEndpointIntent() {
    val context = MinimalContext()
    val expectedBaseIntent = Intent("runtime-service-base")
    val expectedChatWriteIntent = Intent("runtime-service-chat-write")
    val expectedClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")
    }
    val provider = RecordingRuntimeServiceClientProvider(expectedClient)
    OpenCrayRuntimeServiceAccess.setAccessDependenciesForTest(
      RuntimeServiceAccessDependencies(
        runtimeServiceStarter = recordingStarter,
        runtimeServiceClientProvider = provider,
        runtimeServiceEndpoint = object : RuntimeServiceEndpoint {
          override fun baseIntent(context: Context, target: RuntimeServiceTarget): Intent =
            expectedBaseIntent

          override fun scheduledTaskIntent(
            context: Context,
            command: ScheduledTaskWakeCommand,
            target: RuntimeServiceTarget,
          ): Intent = error("Scheduled task intent should not be used in this test.")

          override fun scheduledRepairIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Repair intent should not be used in this test.")

          override fun resetRuntimeIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Reset intent should not be used in this test.")

          override fun resumeInterruptedRunsIntent(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Intent = error("Resume intent should not be used in this test.")

          override fun chatWriteIntent(
            context: Context,
            command: OpenCrayChatWriteCommand,
            target: RuntimeServiceTarget,
          ): Intent? = when (command) {
            is OpenCrayChatWriteCommand.InterruptChatRun -> expectedChatWriteIntent
            else -> null
          }

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
            target: RuntimeServiceTarget,
          ): android.app.PendingIntent = error("Approval pending intent should not be used in this test.")
        },
      ),
    )

    OpenCrayRuntimeServiceAccess.ensureClient(context)
    val bootstrap = provider.createdBootstraps.single()

    val started = bootstrap.chatWriteWakeRequester(
      context,
      OpenCrayChatWriteCommand.InterruptChatRun("run-alpha"),
    )
    val skipped = bootstrap.chatWriteWakeRequester(
      context,
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertTrue(started)
    assertFalse(skipped)
    assertEquals(1, recordingStarter.startedRequests.size)
    val startedRequest = recordingStarter.startedRequests.single()
    assertSame(expectedChatWriteIntent, startedRequest.intent)
    assertTrue(startedRequest.foreground)
  }

  @Test
  fun startScheduledTaskOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()
    val command = ScheduledTaskWakeCommand(
      scheduleId = "schedule-alpha",
      scheduleRunId = "schedule-run-alpha",
      triggeredAtEpochMs = 1234L,
      triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
      targetSessionId = "session-alpha",
    )

    val started = OpenCrayRuntimeServiceAccess.startScheduledTask(context, command)

    assertTrue(started)
    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(startedRequest.foreground)
    assertSame(recordingEndpoint.scheduledTaskIntentSentinel, startedRequest.intent)
    assertEquals(listOf(command), recordingEndpoint.scheduledCommands)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun startScheduledTaskReturnsFalseWhenServiceWakeIsRejected() {
    val context = MinimalContext()
    val command = ScheduledTaskWakeCommand(
      scheduleId = "schedule-rejected",
      scheduleRunId = "schedule-run-rejected",
      triggeredAtEpochMs = 5678L,
      triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
    )
    recordingStarter.throwOnStart = true

    val started = OpenCrayRuntimeServiceAccess.startScheduledTask(context, command)

    assertFalse(started)
    val startAttempt = recordingStarter.startAttempts.single()
    assertTrue(startAttempt.foreground)
    assertSame(recordingEndpoint.scheduledTaskIntentSentinel, startAttempt.intent)
    assertEquals(listOf(command), recordingEndpoint.scheduledCommands)
    assertTrue(recordingStarter.startedRequests.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun runtimeServiceAccessRoutesRequestedTargetThroughEndpoint() {
    val context = MinimalContext()
    val command = ScheduledTaskWakeCommand(
      scheduleId = "schedule-targeted",
      scheduleRunId = "schedule-run-targeted",
      triggeredAtEpochMs = 42L,
      triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
    )

    OpenCrayRuntimeServiceAccess.ensureStarted(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    OpenCrayRuntimeServiceAccess.startScheduledTask(
      context,
      command,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    OpenCrayRuntimeServiceAccess.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    OpenCrayRuntimeServiceAccess.resetRuntime(
      context = context,
      repairReason = ScheduledTaskRepairReasons.APP_START,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    OpenCrayRuntimeServiceAccess.resumeInterruptedRuns(
      context = context,
      repairReason = ScheduledTaskRepairReasons.APP_START,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    assertEquals(
      listOf(RuntimeServiceTarget.INTERACTIVE),
      recordingEndpoint.baseIntentTargets,
    )
    assertEquals(
      listOf(RuntimeServiceTarget.INTERACTIVE),
      recordingEndpoint.scheduledTaskTargets,
    )
    assertEquals(
      listOf(RuntimeServiceTarget.INTERACTIVE),
      recordingEndpoint.scheduledRepairTargets,
    )
    assertEquals(
      listOf(RuntimeServiceTarget.INTERACTIVE),
      recordingEndpoint.resetRuntimeTargets,
    )
    assertEquals(
      listOf(RuntimeServiceTarget.INTERACTIVE),
      recordingEndpoint.resumeInterruptedRunsTargets,
    )
  }

  @Test
  fun scheduledTaskWakeReceiverRoutesAlarmWakeToDetachedBackgroundTarget() {
    val recordedCommands = mutableListOf<ScheduledTaskWakeCommand>()
    val recordedTargets = mutableListOf<RuntimeServiceTarget>()
    val context = RuntimeEnvironmentContext(
      environment = OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        runtimeServiceAccessGateway = object : RuntimeServiceAccessGateway {
          override fun ensureClient(
            context: Context,
            target: RuntimeServiceTarget,
          ): OpenCrayRuntimeServiceClient = error("Unexpected client access.")

          override fun startScheduledTask(
            context: Context,
            command: ScheduledTaskWakeCommand,
            target: RuntimeServiceTarget,
          ): Boolean {
            recordedCommands += command
            recordedTargets += target
            return true
          }

          override fun repairSchedules(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Boolean = error("Unexpected schedule repair.")

          override fun resumeInterruptedRuns(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Boolean = error("Unexpected interrupted-run repair.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
            target: RuntimeServiceTarget,
          ): android.app.PendingIntent = error("Unexpected approval pending intent.")
        },
      ),
    )

    ScheduledTaskWakeReceiver().onReceive(
      context,
      RecordingCommandIntent()
        .putExtra(EXTRA_SCHEDULE_ID, "schedule-1")
        .putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MS, 55L),
    )

    assertEquals(
      listOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      recordedTargets,
    )
    assertEquals("schedule-1", recordedCommands.single().scheduleId)
    assertEquals(
      ScheduledTaskTriggerReasons.ALARM,
      recordedCommands.single().triggerReason,
    )
  }

  @Test
  fun scheduledTaskAlarmWakeRequeuesWorkFallbackWhenServiceStartIsRejected() {
    val recordedCommands = mutableListOf<ScheduledTaskWakeCommand>()
    val recordedTargets = mutableListOf<RuntimeServiceTarget>()
    val workScheduler = RecordingScheduledWorkScheduler()
    val context = RuntimeEnvironmentContext(
      environment = OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        runtimeServiceAccessGateway = object : RuntimeServiceAccessGateway {
          override fun ensureClient(
            context: Context,
            target: RuntimeServiceTarget,
          ): OpenCrayRuntimeServiceClient = error("Unexpected client access.")

          override fun startScheduledTask(
            context: Context,
            command: ScheduledTaskWakeCommand,
            target: RuntimeServiceTarget,
          ): Boolean {
            recordedCommands += command
            recordedTargets += target
            return false
          }

          override fun repairSchedules(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Boolean = error("Unexpected schedule repair.")

          override fun resumeInterruptedRuns(
            context: Context,
            repairReason: String,
            target: RuntimeServiceTarget,
          ): Boolean = error("Unexpected interrupted-run repair.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
            target: RuntimeServiceTarget,
          ): android.app.PendingIntent = error("Unexpected approval pending intent.")
        },
      ),
    )

    val started = dispatchScheduledTaskAlarmWake(
      context,
      RecordingCommandIntent()
        .putExtra(EXTRA_SCHEDULE_ID, "schedule-retry-fallback")
        .putExtra(EXTRA_SCHEDULED_FOR_EPOCH_MS, 55L),
      fallbackSchedulerProvider = { workScheduler },
      nowEpochMsProvider = { 1_234L },
    )

    assertFalse(started)
    assertEquals(
      listOf(RuntimeServiceTarget.DETACHED_BACKGROUND),
      recordedTargets,
    )
    assertEquals("schedule-retry-fallback", recordedCommands.single().scheduleId)
    assertEquals(
      ScheduledTaskTriggerReasons.ALARM,
      recordedCommands.single().triggerReason,
    )
    assertEquals(
      listOf("schedule-retry-fallback" to 1_234L),
      workScheduler.wakeRequests,
    )
  }

  @Test
  fun repairSchedulesOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    val started = OpenCrayRuntimeServiceAccess.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(started)
    assertTrue(startedRequest.foreground)
    assertSame(recordingEndpoint.scheduledRepairIntentSentinel, startedRequest.intent)
    assertEquals(listOf(ScheduledTaskRepairReasons.WORK_MANAGER), recordingEndpoint.scheduledRepairReasons)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resumeInterruptedRunsOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    val started = OpenCrayRuntimeServiceAccess.resumeInterruptedRuns(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(started)
    assertTrue(startedRequest.foreground)
    assertSame(recordingEndpoint.resumeInterruptedRunsIntentSentinel, startedRequest.intent)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.WORK_MANAGER),
      recordingEndpoint.resumeInterruptedRunsReasons,
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resetRuntimeOnlyRequestsServiceWakeWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    val started = OpenCrayRuntimeServiceAccess.resetRuntime(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(started)
    assertTrue(startedRequest.foreground)
    assertSame(recordingEndpoint.resetRuntimeIntentSentinel, startedRequest.intent)
    assertEquals(
      listOf(ScheduledTaskRepairReasons.WORK_MANAGER),
      recordingEndpoint.resetRuntimeReasons,
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  fun repairSchedulesReturnsFalseWhenServiceWakeFailsWithoutCreatingRuntimeHost() {
    val context = MinimalContext()
    recordingStarter.throwOnStart = true

    val started = OpenCrayRuntimeServiceAccess.repairSchedules(
      context = context,
      repairReason = ScheduledTaskRepairReasons.WORK_MANAGER,
    )

    assertFalse(started)
    assertEquals(1, recordingStarter.startAttempts.size)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }
}
