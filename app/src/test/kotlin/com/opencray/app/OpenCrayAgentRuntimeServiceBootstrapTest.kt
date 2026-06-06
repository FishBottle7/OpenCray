package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Handler
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.store.MemoryStore
import java.io.File
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayAgentRuntimeServiceBootstrapTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val recordingStarter = RecordingRuntimeServiceStarter()
  private val recordingEndpoint = RecordingRuntimeServiceEndpoint()
  private val defaultRuntimeEnvironment = OpenCrayRuntimeServiceEnvironment(
    projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  )
  private val defaultRuntimeBootstrapDependencies = defaultRuntimeServiceBootstrapDependencies(
    defaultRuntimeEnvironment,
  )

  @Before
  fun setUp() {
    clearRuntimeSingletons()
    OpenCrayRuntimeServiceAccess.clearForTest()
    OpenCrayRuntimeServiceAccess.setRuntimeServiceStarterForTest(recordingStarter)
    OpenCrayRuntimeServiceAccess.setRuntimeServiceEndpointForTest(recordingEndpoint)
  }

  @After
  fun tearDown() {
    OpenCrayRuntimeServiceAccess.clearForTest()
    clearRuntimeSingletons()
  }

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
  fun defaultRuntimeServiceBootstrapDependenciesUsesEnvironmentExecutionControllerResolver() {
    val expectedHost = testServiceHost(temporaryFolder.newFolder("env-bootstrap-resolver"))
    val expectedController = testRuntimeExecutionController(expectedHost)
    var resolverCallCount = 0
    lateinit var runtimeEnvironmentContext: RuntimeEnvironmentContext
    runtimeEnvironmentContext = RuntimeEnvironmentContext(
      environment = OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        executionControllerResolver = RuntimeServiceExecutionControllerResolver { resolvedContext, target ->
          resolverCallCount += 1
          assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
          assertSame(runtimeEnvironmentContext, resolvedContext)
          expectedController
        },
      ),
    )
    val serviceLifecycle = RuntimeServiceLifecycleDescriptor()
    val expectedState = expectedController.toRuntimeServiceBootstrapState(serviceLifecycle)

    val resolved = defaultRuntimeServiceBootstrapDependencies(
      runtimeEnvironment = runtimeEnvironmentContext.environment,
    ).resolveRuntimeServiceBootstrap(
      context = runtimeEnvironmentContext,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      serviceLifecycle = serviceLifecycle,
    )

    assertSame(expectedState.retainedShellControl, resolved.bootstrapState.retainedShellControl)
    assertSame(expectedState.transportCoordinator, resolved.bootstrapState.transportCoordinator)
    assertEquals(1, resolverCallCount)
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
          ) {
            recordedCommands += command
            recordedTargets += target
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
  fun createRuntimeOwnerBootstrapUsesInjectedBootstrapFactoryWithoutTouchingLegacyRegistry() {
    val root = temporaryFolder.newFolder("runtime-owner-access").toPath()
    val dependencies = testRuntimeDependencies(
      root = root,
      chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile()),
    )
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    var factoryCallCount = 0

    val bootstrap = createRuntimeOwnerBootstrap(
      dependencies = runtimeOwnerBootstrapDependencies(dependencies),
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
      runtimeOwnerBootstrapFactory = object : RuntimeOwnerBootstrapFactory {
        override fun create(
          dependencies: RuntimeOwnerBootstrapDependencies,
          runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
        ): RuntimeOwnerBootstrap {
          factoryCallCount += 1
          return runtimeOwnerBootstrapFor(
            runtimeAccess = testRuntimeAccess(
              lifecycleDescriptor = lifecycleDescriptor,
              sessionRuntimeManager = NoOpAgentSessionRuntimeManager(),
            ),
          )
        }
      },
    )

    assertEquals(1, factoryCallCount)
    assertSame(lifecycleDescriptor, bootstrap.runtimeOwnerLifecycle)
    assertSame(bootstrap.ownerObservationAccess, bootstrap.notificationHostAccess)
    assertSame(bootstrap.ownerObservationAccess, bootstrap.approvalDecisionHostAccess)
    assertSame(bootstrap.ownerObservationAccess, bootstrap.chatMutationAccess)
    assertSame(bootstrap.ownerObservationAccess, bootstrap.chatSubmissionHostAccess)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun createRuntimeOwnerBootstrapPreservesInjectedRetainedHandle() {
    val root = temporaryFolder.newFolder("runtime-owner-bootstrap-retained").toPath()
    val dependencies = testRuntimeDependencies(
      root = root,
      chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile()),
    )
    val retainedHandle = object : RetainedRuntimeOwnerHandle {
      override fun createReplacementBootstrap(): RuntimeOwnerBootstrap =
        error("Replacement should not be used in this test.")

      override fun disposeRetainedOwner() = Unit
    }
    val bootstrap = createRuntimeOwnerBootstrap(
      dependencies = runtimeOwnerBootstrapDependencies(dependencies),
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
      runtimeOwnerBootstrapFactory = RuntimeOwnerBootstrapFactory { _, _ ->
        runtimeOwnerBootstrapFor(
          runtimeAccess = testRuntimeAccess(),
          retainedHandle = retainedHandle,
        )
      },
    )

    assertSame(retainedHandle, bootstrap.retainedHandle)
  }

  @Test
  fun createRuntimeOwnerBootstrapDisposeReleasesInjectedBootstrapOnce() {
    val root = temporaryFolder.newFolder("runtime-owner-bootstrap-dispose").toPath()
    val dependencies = testRuntimeDependencies(
      root = root,
      chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile()),
    )
    var disposeCount = 0

    val bootstrap = createRuntimeOwnerBootstrap(
      dependencies = runtimeOwnerBootstrapDependencies(dependencies),
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
      runtimeOwnerBootstrapFactory = object : RuntimeOwnerBootstrapFactory {
        override fun create(
          dependencies: RuntimeOwnerBootstrapDependencies,
          runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
        ): RuntimeOwnerBootstrap = runtimeOwnerBootstrapFor(
          runtimeAccess = testRuntimeAccess(
            sessionRuntimeManager = NoOpAgentSessionRuntimeManager(),
          ),
          disposeHandler = { disposeCount += 1 },
        )
      },
    )

    bootstrap.dispose()
    bootstrap.dispose()

    assertEquals(1, disposeCount)
  }

  @Test
  fun runtimeServiceBootstrapAssemblyDisposeReleasesObserversTransportAndRetainedShellState() {
    val root = temporaryFolder.newFolder("bootstrap-assembly-dispose").toPath()
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile())
    val dependencies = testRuntimeDependencies(root = root, chatStore = chatStore)
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val runtimeAccess = testRuntimeAccess(sessionRuntimeManager = runtimeManager)
    val retainedShellControl = testRuntimeServiceRetainedShellControl()
    var gatewayDisposeCallCount = 0
    val bootstrapFactory = RuntimeServiceBootstrapFactory {
      RuntimeServiceBootstrapParts(
        scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
        scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
        scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
        scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
      )
    }
    val assembly = createRuntimeServiceBootstrapAssembly(
      appContext = dependencies.appContext,
      bootstrapContext = runtimeServiceBootstrapContext(dependencies),
      retainedOwnerState = retainedOwnerStateFor(runtimeAccess),
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
      retainedShellControlFactory = { retainedShellControl },
      bootstrapFactory = bootstrapFactory,
    )
    assembly.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
    )
    assembly.transportCoordinator.bindGatewayBundle(
      testServiceGatewayBundle(
        dispose = { gatewayDisposeCallCount += 1 },
      ),
    )

    assertEquals(2, runtimeManager.observerCount)

    assembly.dispose()
    assembly.dispose()

    assertEquals(0, runtimeManager.observerCount)
    assertEquals(1, gatewayDisposeCallCount)
    assertEquals(
      RuntimeServiceKeepAliveState.PHASE_DESTROYED,
      retainedShellControl.keepAliveController.currentState().phase,
    )
    assertEquals(
      RuntimeForegroundState.PHASE_DESTROYED,
      retainedShellControl.runtimeForegroundController.currentState().phase,
    )
  }

  @Test
  fun inProcessRuntimeOwnerDisposeStillRunsCleanupWhenSessionReleaseFails() {
    val runtimeManager = object : AgentSessionRuntimeManager {
      var releaseAllSessionsCallCount: Int = 0

      override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

      override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

      override fun release(sessionId: String) = Unit

      override fun releaseAllSessions() {
        releaseAllSessionsCallCount += 1
        error("session release failed")
      }

      override fun releaseIdleSessions() = Unit
    }
    var cleanupCallCount = 0
    val owner = RetainedInProcessOpenCrayRuntimeOwnerCore(
      runtimeControllerLifecycle = null,
      runtimeOwnerLifecycleState = RuntimeOwnerLifecycleState(
        HostRuntimeLifecycleDescriptor(),
      ),
      sessionRuntimeManager = runtimeManager,
      runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
      promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
      supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
        override fun forChatSession(sessionId: String): SessionSupplementStore =
          InMemorySessionSupplementStore()
      },
      transcriptMessagesProvider = { emptyList() },
      approvalRegistry = AgentTaskApprovalRegistry(),
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
      disposeHandler = { cleanupCallCount += 1 },
    )

    var failureMessage: String? = null
    try {
      owner.dispose()
    } catch (expected: IllegalStateException) {
      failureMessage = expected.message
    }

    assertEquals("session release failed", failureMessage)
    assertEquals(1, runtimeManager.releaseAllSessionsCallCount)
    assertEquals(1, cleanupCallCount)
  }

  @Test
  fun runtimeServiceExecutionControllerDisposeStillCallsOwnerDisposeWhenAssemblyDisposeFails() {
    val root = temporaryFolder.newFolder("execution-controller-dispose-finally").toPath()
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile())
    val dependencies = testRuntimeDependencies(root = root, chatStore = chatStore)
    val runtimeAccess = testRuntimeAccess()
    var ownerDisposeCallCount = 0
    val controller = RuntimeServiceExecutionController(
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
      bootstrapAssembly = RuntimeServiceBootstrapAssembly(
        bootstrapContext = runtimeServiceBootstrapContext(dependencies),
        retainedOwnerState = retainedOwnerStateFor(runtimeAccess),
        projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(),
        transportCoordinator = DefaultRuntimeServiceTransportCoordinator(),
        retainedShellControl = testRuntimeServiceRetainedShellControl(),
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
        bootstrapResult = RuntimeServiceBootstrapResult(
          scannedSessionIds = emptyList(),
          resumedSessionIds = emptyList(),
          repairedSessionIds = emptyList(),
        ),
        serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
          workSummaryProvider = { RuntimeOwnerWorkSummary() },
        ),
        scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
        scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
        scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory()
          .create(),
        scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        disposeHandler = { error("assembly dispose failed") },
      ),
      disposeHandler = { ownerDisposeCallCount += 1 },
    )

    var failureMessage: String? = null
    try {
      controller.dispose()
    } catch (expected: IllegalStateException) {
      failureMessage = expected.message
    }

    assertEquals("assembly dispose failed", failureMessage)
    assertEquals(1, ownerDisposeCallCount)
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

    OpenCrayRuntimeServiceAccess.startScheduledTask(context, command)

    val startedRequest = recordingStarter.startedRequests.single()
    assertTrue(startedRequest.foreground)
    assertSame(recordingEndpoint.scheduledTaskIntentSentinel, startedRequest.intent)
    assertEquals(listOf(command), recordingEndpoint.scheduledCommands)
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
          ) {
            recordedCommands += command
            recordedTargets += target
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

  @Test
  fun resolveRuntimeServiceBootstrapStateUsesInjectedExecutionControllerByDefault() {
    val context = MinimalContext()
    val expectedHost = testServiceHost(temporaryFolder.newFolder("service-bootstrap-state-default"))
    val expectedController = testRuntimeExecutionController(expectedHost)
    var providerCallCount = 0
    var capturedContext: Context? = null
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceExecutionControllerProvider =
        RuntimeServiceExecutionControllerProvider { resolvedContext ->
          providerCallCount += 1
          capturedContext = resolvedContext
          expectedController
        },
    )

    val resolved = bootstrapResolver.resolveRuntimeServiceBootstrapStateForTest(context) {
      RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-default",
        serviceCreatedAtEpochMs = 2_468L,
      )
    }

    assertSame(
      expectedHost.runtimeAccess.lifecycleDescriptor,
      resolved.gatewayDependencies.runtimeOwnerLifecycle,
    )
    assertSameGatewayRuntimeAccess(
      expected = expectedHost.runtimeAccess.hostAccess,
      actual = resolved.gatewayDependencies,
    )
    assertEquals(
      "runtime-service-default",
      resolved.gatewayDependencies.serviceLifecycle.serviceInstanceId,
    )
    assertEquals(
      2_468L,
      resolved.gatewayDependencies.serviceLifecycle.serviceCreatedAtEpochMs,
    )
    assertSame(
      expectedHost.runtimeAccess.lifecycleDescriptor,
      resolved.binderEndpointDependencies.bridgeSnapshotDependencies.runtimeOwnerLifecycle,
    )
    assertEquals(
      expectedHost.runtimeAccess.hostAccess.activeWorkSummary(),
      resolved.binderEndpointDependencies.bridgeSnapshotDependencies
        .runtimeOwnerWorkSummaryProvider(),
    )
    assertSame(context, capturedContext)
    assertEquals(1, providerCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceBootstrapUsesInjectedProviderWithoutTouchingDefaultProvider() {
    val context = MinimalContext()
    val expectedHost = testServiceHost(temporaryFolder.newFolder("service-execution-controller"))
    val expectedController = testRuntimeExecutionController(expectedHost)
    val serviceLifecycle = RuntimeServiceLifecycleDescriptor(
      serviceInstanceId = "runtime-service-controller",
      serviceCreatedAtEpochMs = 9_999L,
    )
    val expectedBootstrapState = expectedController.toRuntimeServiceBootstrapState(serviceLifecycle)
    var providerCallCount = 0
    var capturedContext: Context? = null
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceExecutionControllerProvider =
        RuntimeServiceExecutionControllerProvider { resolvedContext ->
          providerCallCount += 1
          capturedContext = resolvedContext
          expectedController
        },
    )

    val resolved = bootstrapResolver.resolveRuntimeServiceBootstrap(
      context = context,
      serviceLifecycle = serviceLifecycle,
    )

    assertSame(expectedBootstrapState.transportCoordinator, resolved.bootstrapState.transportCoordinator)
    assertSame(expectedBootstrapState.retainedShellControl, resolved.bootstrapState.retainedShellControl)
    assertSame(
      expectedBootstrapState.gatewayDependencies.runtimeOwnerLifecycle,
      resolved.bootstrapState.gatewayDependencies.runtimeOwnerLifecycle,
    )
    assertSame(context, capturedContext)
    assertEquals(1, providerCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun processScopedRuntimeServiceExecutionControllerProviderCachesExecutionAssemblyPerProcessAndBootstrapsRuntimeServiceProcessSupportOnce() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-provider").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val runtimeManager = RecordingAgentSessionRuntimeManager().apply {
      val activeSessionId = chatStore.loadState().activeSession.sessionId
      val pendingSnapshot = SessionQueueSnapshot(
        sessionId = activeSessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_000L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = "task-active",
              type = AgentTaskType.PROMPT,
              input = "resume me",
              state = AgentTaskState.RUNNING,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_000L,
            ),
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      )
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
          queueSnapshot = pendingSnapshot,
          hasPendingWorkResult = true,
        ),
      )
    }
    val runtimeAccess = testRuntimeAccess(sessionRuntimeManager = runtimeManager)
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    var bootstrapFactoryCallCount = 0
    var runtimeServiceProcessBootstrapCallCount = 0
    var runtimeServiceProcessBootstrapContext: Context? = null

    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { resolvedContext ->
        runtimeServiceProcessBootstrapCallCount += 1
        runtimeServiceProcessBootstrapContext = resolvedContext
      },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, _ ->
        RuntimeOwnerBootstrap(
          runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
          ownerObservationAccess = runtimeAccess.hostAccess,
          notificationHostAccess = runtimeAccess.hostAccess,
          approvalDecisionHostAccess = runtimeAccess.hostAccess,
          chatMutationAccess = runtimeAccess.hostAccess,
          chatSubmissionHostAccess = runtimeAccess.hostAccess,
          runtimeReplayAccess = runtimeAccess.replayAccess,
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        bootstrapFactoryCallCount += 1
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )
    val first = provider.resolve(context)
    val second = provider.resolve(context)

    assertSame(first, second)
    assertEquals(1, bootstrapFactoryCallCount)
    assertEquals(1, runtimeServiceProcessBootstrapCallCount)
    assertSame(context, runtimeServiceProcessBootstrapContext)
    assertEquals(first.runtimeControllerLifecycle, second.runtimeControllerLifecycle)
    assertEquals(1, runtimeManager.resumedSessionIds.size)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
  }

  @Test
  fun processScopedRuntimeServiceExecutionControllerProviderResumesDurableOnlySessionWithoutChatSessionSelection() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-provider-durable-only").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val durableOnlySessionId = "session-durable-only"
    val runtimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = durableOnlySessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val runtimeAccess = testRuntimeAccess(sessionRuntimeManager = runtimeManager)
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    FileBackedPromptCheckpointStoreFactory.fromContext(runtimeDependencies.appContext)
      .forChatSession(durableOnlySessionId)
      .upsert(
        PersistedPromptCheckpoint(
          sessionId = durableOnlySessionId,
          runId = "run-durable-only",
          taskId = "task-durable-only",
          checkpointId = "checkpoint-durable-only",
          checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
        ),
      )

    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, _ ->
        RuntimeOwnerBootstrap(
          runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
          ownerObservationAccess = runtimeAccess.hostAccess,
          notificationHostAccess = runtimeAccess.hostAccess,
          approvalDecisionHostAccess = runtimeAccess.hostAccess,
          chatMutationAccess = runtimeAccess.hostAccess,
          chatSubmissionHostAccess = runtimeAccess.hostAccess,
          runtimeReplayAccess = runtimeAccess.replayAccess,
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )

    provider.resolve(context)

    assertEquals(listOf(durableOnlySessionId), runtimeManager.resumedSessionIds)
  }

  @Test
  fun processScopedRuntimeServiceExecutionControllerProviderUsesInjectedLocalRuntimeServerStateProvider() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-provider-local-runtime-server").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val runtimeAccess = testRuntimeAccess(sessionRuntimeManager = runtimeManager)
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    val projectedServerState = LocalRuntimeServerState(
      phase = LocalRuntimeServerState.PHASE_LISTENING,
      bindAddress = "127.0.0.1",
      requestedPort = 8123,
      listeningPort = 9123,
      lastStartAttemptAtEpochMs = 10L,
      lastStartedAtEpochMs = 20L,
      changedAtEpochMs = 30L,
    )
    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { },
      localRuntimeServerStateProvider = { projectedServerState },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, _ ->
        RuntimeOwnerBootstrap(
          runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
          ownerObservationAccess = runtimeAccess.hostAccess,
          notificationHostAccess = runtimeAccess.hostAccess,
          approvalDecisionHostAccess = runtimeAccess.hostAccess,
          chatMutationAccess = runtimeAccess.hostAccess,
          chatSubmissionHostAccess = runtimeAccess.hostAccess,
          runtimeReplayAccess = runtimeAccess.replayAccess,
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )

    val controller = provider.resolve(context)
    OpenCrayLocalRuntimeServerRegistry.clearForTest()
    val bootstrapState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
    )

    assertEquals(
      projectedServerState,
      controller.localRuntimeServerState(),
    )
    assertEquals(
      projectedServerState,
      bootstrapState.gatewayDependencies.localRuntimeServerStateProvider(),
    )
    assertEquals(
      projectedServerState,
      bootstrapState.binderEndpointDependencies.bridgeSnapshotDependencies
        .localRuntimeServerStateProvider(),
    )
  }

  @Test
  fun processScopedRuntimeServiceExecutionControllerProviderResetDisposesCachedControllerAndAllowsRecreate() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-provider-reset").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val runtimeAccess = testRuntimeAccess(sessionRuntimeManager = runtimeManager)
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    var bootstrapFactoryCallCount = 0
    var runtimeServiceProcessBootstrapCallCount = 0
    var ownerDisposeCallCount = 0

    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = {
        runtimeServiceProcessBootstrapCallCount += 1
      },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, _ ->
        RuntimeOwnerBootstrap(
          runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
          ownerObservationAccess = runtimeAccess.hostAccess,
          notificationHostAccess = runtimeAccess.hostAccess,
          approvalDecisionHostAccess = runtimeAccess.hostAccess,
          chatMutationAccess = runtimeAccess.hostAccess,
          chatSubmissionHostAccess = runtimeAccess.hostAccess,
          runtimeReplayAccess = runtimeAccess.replayAccess,
          disposeHandler = { ownerDisposeCallCount += 1 },
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        bootstrapFactoryCallCount += 1
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )

    val first = provider.resolve(context)

    assertSame(first, provider.peek())
    assertEquals(1, bootstrapFactoryCallCount)
    assertEquals(1, runtimeServiceProcessBootstrapCallCount)
    assertEquals(1, runtimeManager.observerCount)

    val removed = provider.reset()

    assertSame(first, removed)
    assertNull(provider.peek())
    assertEquals(0, runtimeManager.observerCount)
    assertEquals(1, ownerDisposeCallCount)

    val second = provider.resolve(context)

    assertNotSame(first, second)
    assertSame(second, provider.peek())
    assertEquals(2, bootstrapFactoryCallCount)
    assertEquals(2, runtimeServiceProcessBootstrapCallCount)
    assertEquals(1, runtimeManager.observerCount)
  }

  @Test
  fun processScopedRuntimeServiceExecutionControllerProviderSwapPinsReplacementAndDisposesPreviousController() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-provider-swap").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val runtimeAccess = testRuntimeAccess(sessionRuntimeManager = runtimeManager)
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    var bootstrapFactoryCallCount = 0
    var ownerDisposeCallCount = 0

    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, _ ->
        RuntimeOwnerBootstrap(
          runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
          ownerObservationAccess = runtimeAccess.hostAccess,
          notificationHostAccess = runtimeAccess.hostAccess,
          approvalDecisionHostAccess = runtimeAccess.hostAccess,
          chatMutationAccess = runtimeAccess.hostAccess,
          chatSubmissionHostAccess = runtimeAccess.hostAccess,
          runtimeReplayAccess = runtimeAccess.replayAccess,
          disposeHandler = { ownerDisposeCallCount += 1 },
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        bootstrapFactoryCallCount += 1
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )
    val first = provider.resolve(context)
    val replacement = testRuntimeExecutionController(
      testServiceHost(
        temporaryFolder.newFolder("execution-controller-provider-swap-replacement"),
      ),
    )

    assertEquals(1, runtimeManager.observerCount)

    val replaced = provider.swap(replacement)

    assertSame(first, replaced)
    assertSame(replacement, provider.peek())
    assertEquals(0, runtimeManager.observerCount)
    assertEquals(1, ownerDisposeCallCount)
    assertSame(replacement, provider.resolve(context))
    assertEquals(1, bootstrapFactoryCallCount)
  }

  @Test
  fun runtimeServiceExecutionControllerRetainsProjectionCoordinatorAcrossServiceLifecycleRebinds() {
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-execution-controller-projection-retained"),
    )
    val controller = testRuntimeExecutionController(serviceHost)

    val firstState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-first",
        serviceCreatedAtEpochMs = 1_000L,
      ),
    )
    val secondState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-second",
        serviceCreatedAtEpochMs = 2_000L,
      ),
    )

    assertSame(
      firstState.executionCoordinatorDependencies.projectionCoordinator,
      secondState.executionCoordinatorDependencies.projectionCoordinator,
    )
    assertEquals(
      "runtime-service-first",
      firstState.gatewayDependencies.serviceLifecycle.serviceInstanceId,
    )
    assertEquals(
      "runtime-service-second",
      secondState.gatewayDependencies.serviceLifecycle.serviceInstanceId,
    )
  }

  @Test
  fun runtimeServiceExecutionControllerRetainsTransportCoordinatorAcrossServiceLifecycleRebinds() {
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-execution-controller-transport-retained"),
    )
    val controller = testRuntimeExecutionController(serviceHost)

    val firstState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-first",
        serviceCreatedAtEpochMs = 1_000L,
      ),
    )
    val secondState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-second",
        serviceCreatedAtEpochMs = 2_000L,
      ),
    )

    assertSame(firstState.transportCoordinator, secondState.transportCoordinator)
    assertEquals(
      "runtime-service-first",
      firstState.gatewayDependencies.serviceLifecycle.serviceInstanceId,
    )
    assertEquals(
      "runtime-service-second",
      secondState.gatewayDependencies.serviceLifecycle.serviceInstanceId,
    )
  }

  @Test
  fun runtimeServiceExecutionControllerRetainsShellControlAcrossServiceLifecycleRebinds() {
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-execution-controller-shell-control-retained"),
    )
    val controller = testRuntimeExecutionController(serviceHost)

    val firstState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-first",
        serviceCreatedAtEpochMs = 1_000L,
      ),
    )
    val secondState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-second",
        serviceCreatedAtEpochMs = 2_000L,
      ),
    )

    assertSame(
      firstState.retainedShellControl.keepAliveController,
      secondState.retainedShellControl.keepAliveController,
    )
    assertSame(
      firstState.retainedShellControl.runtimeForegroundController,
      secondState.retainedShellControl.runtimeForegroundController,
    )
  }

  @Test
  fun runtimeServiceExecutionControllerReplaceRuntimeOwnerRetainsDetachedRuntimePrimitivesAndRebindsObservers() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-replace-owner").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    val firstRuntimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val secondRuntimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val ownerDisposeLabels = mutableListOf<String>()
    var ownerBootstrapResolveCount = 0

    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, runtimeControllerLifecycle ->
        ownerBootstrapResolveCount += 1
        when (ownerBootstrapResolveCount) {
          1 -> {
            val firstRuntimeAccess = testRuntimeAccess(
              lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
                runtimeControllerId = runtimeControllerLifecycle.controllerInstanceId,
              ),
              sessionRuntimeManager = firstRuntimeManager,
            )
            runtimeOwnerBootstrapFor(
              runtimeAccess = firstRuntimeAccess,
              disposeHandler = { ownerDisposeLabels += "first" },
            )
          }

          2 -> {
            val secondRuntimeAccess = testRuntimeAccess(
              lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
                runtimeControllerId = runtimeControllerLifecycle.controllerInstanceId,
              ),
              sessionRuntimeManager = secondRuntimeManager,
            )
            runtimeOwnerBootstrapFor(
              runtimeAccess = secondRuntimeAccess,
              disposeHandler = { ownerDisposeLabels += "second" },
            )
          }

          else -> error("Unexpected owner bootstrap resolve count: $ownerBootstrapResolveCount")
        }
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )

    val controller = provider.resolve(context)
    val firstState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-owner-first",
        serviceCreatedAtEpochMs = 1_000L,
      ),
    )

    assertEquals(2, firstRuntimeManager.observerCount)
    assertEquals(0, secondRuntimeManager.observerCount)

    val replacementBootstrap = controller.replaceRuntimeOwner()
    val secondState = controller.toRuntimeServiceBootstrapState(
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-owner-second",
        serviceCreatedAtEpochMs = 2_000L,
      ),
    )

    assertEquals(2, ownerBootstrapResolveCount)
    assertEquals(listOf("first"), ownerDisposeLabels)
    assertEquals(0, firstRuntimeManager.observerCount)
    assertEquals(2, secondRuntimeManager.observerCount)
    assertEquals(
      controller.runtimeControllerLifecycle?.controllerInstanceId,
      replacementBootstrap.runtimeOwnerLifecycle.runtimeControllerId,
    )
    assertSame(
      firstState.executionCoordinatorDependencies.projectionCoordinator,
      secondState.executionCoordinatorDependencies.projectionCoordinator,
    )
    assertSame(firstState.transportCoordinator, secondState.transportCoordinator)
    assertSame(
      firstState.retainedShellControl.keepAliveController,
      secondState.retainedShellControl.keepAliveController,
    )
    assertSame(
      firstState.retainedShellControl.runtimeForegroundController,
      secondState.retainedShellControl.runtimeForegroundController,
    )
    assertSame(
      firstState.gatewayDependencies.runtimeControllerLifecycle,
      secondState.gatewayDependencies.runtimeControllerLifecycle,
    )
    assertSame(
      controller.runtimeControllerLifecycle,
      secondState.gatewayDependencies.runtimeControllerLifecycle,
    )
    assertNotSame(
      firstState.gatewayDependencies.runtimeOwnerLifecycle,
      secondState.gatewayDependencies.runtimeOwnerLifecycle,
    )
    assertSame(
      replacementBootstrap.runtimeOwnerLifecycle,
      secondState.gatewayDependencies.runtimeOwnerLifecycle,
    )
    assertSame(
      replacementBootstrap.runtimeOwnerLifecycle,
      secondState.binderEndpointDependencies.bridgeSnapshotDependencies.runtimeOwnerLifecycle,
    )
  }

  @Test
  fun runtimeServiceExecutionControllerUsesRetainedOwnerHandleForReplacementAndFinalDispose() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-retained-owner-handle").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    var resolveCount = 0
    var bootstrapDisposeCount = 0
    var retainedReplaceCount = 0
    var retainedDisposeCount = 0
    lateinit var replacementLifecycle: HostRuntimeLifecycleDescriptor
    val initialRuntimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val replacementRuntimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val retainedHandle = object : RetainedRuntimeOwnerHandle {
      override fun createReplacementBootstrap(): RuntimeOwnerBootstrap {
        retainedReplaceCount += 1
        val runtimeAccess = testRuntimeAccess(
          lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
            runtimeControllerId = "runtime-controller-retained",
          ).also { lifecycle ->
            replacementLifecycle = lifecycle
          },
          sessionRuntimeManager = replacementRuntimeManager,
        )
        return runtimeOwnerBootstrapFor(
          runtimeAccess = runtimeAccess,
          retainedHandle = this,
          disposeHandler = { bootstrapDisposeCount += 1 },
        )
      }

      override fun disposeRetainedOwner() {
        retainedDisposeCount += 1
      }
    }
    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, runtimeControllerLifecycle ->
        resolveCount += 1
        val runtimeAccess = testRuntimeAccess(
          lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
            runtimeControllerId = runtimeControllerLifecycle.controllerInstanceId,
          ),
          sessionRuntimeManager = initialRuntimeManager,
        )
        runtimeOwnerBootstrapFor(
          runtimeAccess = runtimeAccess,
          retainedHandle = retainedHandle,
          disposeHandler = { bootstrapDisposeCount += 1 },
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { _ ->
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )

    val controller = provider.resolve(context)
    controller.replaceRuntimeOwner()
    controller.dispose()

    assertEquals(1, resolveCount)
    assertEquals(1, retainedReplaceCount)
    assertEquals(1, bootstrapDisposeCount)
    assertEquals(1, retainedDisposeCount)
    assertEquals("runtime-controller-retained", replacementLifecycle.runtimeControllerId)
  }

  @Test
  fun createRuntimeServiceBootstrapStateUsesBootstrapFactoryWithoutTouchingHostRegistry() {
    val context = MinimalContext()
    val root = temporaryFolder.newFolder("service-bootstrap-state-direct")
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    runtimeManager.putHandle(
      RecordingAgentSessionHandle(
        sessionId = sessionId,
        resumedSessionIds = runtimeManager.resumedSessionIds,
      ),
    )
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val dependencies = testRuntimeDependencies(
      root = root.toPath(),
      chatStore = chatStore,
    )
    val lifecycle = RuntimeServiceLifecycleDescriptor(
      serviceInstanceId = "runtime-service-direct",
      serviceCreatedAtEpochMs = 4_321L,
    )
    var factoryCallCount = 0
    var capturedContext: Context? = null

    val resolved = createRuntimeServiceBootstrapState(
      appContext = context,
      serviceLifecycle = lifecycle,
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(dependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, _ ->
        RuntimeOwnerBootstrap(
          runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
          ownerObservationAccess = runtimeAccess.hostAccess,
          notificationHostAccess = runtimeAccess.hostAccess,
          approvalDecisionHostAccess = runtimeAccess.hostAccess,
          chatMutationAccess = runtimeAccess.hostAccess,
          chatSubmissionHostAccess = runtimeAccess.hostAccess,
          runtimeReplayAccess = runtimeAccess.replayAccess,
        )
      },
      bootstrapFactory = RuntimeServiceBootstrapFactory { appContext ->
        factoryCallCount += 1
        capturedContext = appContext
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore =
            inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
        )
      },
    )

    assertSame(
      runtimeAccess.lifecycleDescriptor,
      resolved.gatewayDependencies.runtimeOwnerLifecycle,
    )
    assertSameGatewayRuntimeAccess(
      expected = runtimeAccess.hostAccess,
      actual = resolved.gatewayDependencies,
    )
    assertSame(lifecycle, resolved.gatewayDependencies.serviceLifecycle)
    assertSame(
      runtimeAccess.lifecycleDescriptor,
      resolved.binderEndpointDependencies.bridgeSnapshotDependencies.runtimeOwnerLifecycle,
    )
    assertEquals(
      runtimeAccess.hostAccess.activeWorkSummary(),
      resolved.binderEndpointDependencies.bridgeSnapshotDependencies
        .runtimeOwnerWorkSummaryProvider(),
    )
    assertTrue(runtimeManager.resumedSessionIds.isEmpty())
    assertSame(dependencies.appContext, capturedContext)
    assertEquals(1, factoryCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceTransportBootstrapUsesInjectedFactoryWithoutTouchingDefaultTransportBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-transport-bootstrap"))
    val expectedGatewayBundle = testServiceGatewayBundle()
    var startCallCount = 0
    val expectedBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = expectedGatewayBundle,
      ensureStarted = { startCallCount += 1 },
    )
    var factoryCallCount = 0
    var capturedContext: Context? = null
    var capturedKeepAliveState: RuntimeServiceKeepAliveState? = null
    var capturedTransportCoordinator: RuntimeServiceTransportCoordinator? = null
    var keepAliveListenerRegistered = false
    val expectedKeepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 2_468L,
    )
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceTransportBootstrapFactory =
        OpenCrayRuntimeServiceTransportBootstrapFactory {
            appContext,
            runtimeTarget,
            localGatewayProvider,
            gatewayDependencies,
            runtimeServiceGatewayBundleFactory,
            runtimeServiceKeepAliveStateProvider,
            runtimeServiceKeepAliveChangeRegistrar,
            transportCoordinator,
          ->
          factoryCallCount += 1
          capturedContext = appContext
          assertEquals(RuntimeServiceTarget.INTERACTIVE, runtimeTarget)
          assertSame(serviceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
          assertSameGatewayRuntimeAccess(
            expected = serviceHost.runtimeAccess.hostAccess,
            actual = gatewayDependencies,
          )
          assertSame(
            DefaultRuntimeServiceGatewayBundleFactory,
            runtimeServiceGatewayBundleFactory,
          )
          assertTrue(localGatewayProvider() is OpenCrayLocalHostGateway)
          capturedTransportCoordinator = transportCoordinator
          capturedKeepAliveState = runtimeServiceKeepAliveStateProvider()
          runtimeServiceKeepAliveChangeRegistrar.register { keepAliveListenerRegistered = true }
          expectedBootstrap
        },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = bootstrapResolver.resolveRuntimeServiceTransportBootstrap(
      context = context,
      target = RuntimeServiceTarget.INTERACTIVE,
      bootstrapState = bootstrapState,
      runtimeServiceKeepAliveStateProvider = { expectedKeepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        listener()
        ({ })
      },
    )

    resolved.ensureStarted()

    assertSame(expectedBootstrap, resolved)
    assertSame(expectedGatewayBundle, resolved.gatewayBundle)
    assertSame(context, capturedContext)
    assertSame(expectedKeepAliveState, capturedKeepAliveState)
    assertSame(bootstrapState.transportCoordinator, capturedTransportCoordinator)
    assertTrue(keepAliveListenerRegistered)
    assertEquals(1, factoryCallCount)
    assertEquals(1, startCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultTransportBootstrapFactoryUsesInjectedGatewayBundleFactoryAndLoopbackBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-transport-default"))
    var gatewayDisposeCallCount = 0
    val expectedGatewayBundle = testServiceGatewayBundle(
      dispose = { gatewayDisposeCallCount += 1 },
    )
    val expectedKeepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 3_579L,
    )
    var capturedGatewayContext: Context? = null
    var capturedKeepAliveState: RuntimeServiceKeepAliveState? = null
    var keepAliveListenerRegistered = false
    var capturedLoopbackContext: Context? = null
    var capturedLoopbackTarget: RuntimeServiceTarget? = null
    var capturedLoopbackTransportCoordinator: RuntimeServiceTransportCoordinator? = null
    var loopbackStartCallCount = 0
    var loopbackDisposeCallCount = 0
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator()
    val factory = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory {
          appContext,
          runtimeTarget,
          localGatewayProvider,
          gatewayBundle,
          resolvedTransportCoordinator,
        ->
        capturedLoopbackContext = appContext
        capturedLoopbackTarget = runtimeTarget
        capturedLoopbackTransportCoordinator = resolvedTransportCoordinator
        assertSame(expectedGatewayBundle, gatewayBundle)
        assertSame(expectedGatewayBundle, resolvedTransportCoordinator.currentGatewayBundle())
        assertTrue(localGatewayProvider() is OpenCrayLocalHostGateway)
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = { loopbackStartCallCount += 1 },
          dispose = { loopbackDisposeCallCount += 1 },
        )
      },
    )

    val resolved = factory.create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory {
          appContext,
          gatewayDependencies,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
        ->
        capturedGatewayContext = appContext
        assertSame(serviceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSameGatewayRuntimeAccess(
          expected = serviceHost.runtimeAccess.hostAccess,
          actual = gatewayDependencies,
        )
        capturedKeepAliveState = runtimeServiceKeepAliveStateProvider()
        runtimeServiceKeepAliveChangeRegistrar.register { keepAliveListenerRegistered = true }
        expectedGatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { expectedKeepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        listener()
        ({ })
      },
      transportCoordinator = transportCoordinator,
    )

    resolved.ensureStarted()
    resolved.dispose()

    assertSame(expectedGatewayBundle, resolved.gatewayBundle)
    assertSame(context, capturedGatewayContext)
    assertSame(expectedKeepAliveState, capturedKeepAliveState)
    assertTrue(keepAliveListenerRegistered)
    assertSame(context, capturedLoopbackContext)
    assertEquals(RuntimeServiceTarget.INTERACTIVE, capturedLoopbackTarget)
    assertSame(transportCoordinator, capturedLoopbackTransportCoordinator)
    assertTrue(
      runCatching { transportCoordinator.currentGatewayBundle() }.exceptionOrNull()
        is IllegalStateException,
    )
    assertEquals(1, gatewayDisposeCallCount)
    assertEquals(1, loopbackStartCallCount)
    assertEquals(1, loopbackDisposeCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultLoopbackBootstrapFactoryUsesInjectedServerStarterAndGatewayProviders() {
    val context = MinimalContext()
    val gatewayBundle = testServiceGatewayBundle()
    val localGateway = NoOpLocalHostGateway()
    var localGatewayProviderResolveCount = 0
    var capturedContext: Context? = null
    var capturedTarget: RuntimeServiceTarget? = null
    var capturedProviders: OpenCrayLocalRuntimeServerProviders? = null
    var ensureServerStartedCallCount = 0
    val startedServer = OpenCrayLocalRuntimeServer(
      localGatewayProvider = { error("unused in test") },
      shellGatewayProvider = { error("unused in test") },
      chatRuntimeGatewayProvider = { error("unused in test") },
      skillsGatewayProvider = { error("unused in test") },
      settingsGatewayProvider = { error("unused in test") },
      requestedPort = localRuntimeLoopbackPortForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND),
      shutdownExecutorOnClose = true,
    )
    val factory = DefaultRuntimeServiceLoopbackBootstrapFactory(
      ensureServerStarted = { resolvedContext, runtimeTarget, providers ->
        ensureServerStartedCallCount += 1
        capturedContext = resolvedContext
        capturedTarget = runtimeTarget
        capturedProviders = providers
        startedServer
      },
    )

    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
      bindGatewayBundle(gatewayBundle)
    }
    val bootstrap = factory.create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = {
        localGatewayProviderResolveCount += 1
        localGateway
      },
      gatewayBundle = gatewayBundle,
      transportCoordinator = transportCoordinator,
    )
    try {
      bootstrap.ensureStarted()

      assertEquals(1, ensureServerStartedCallCount)
      assertSame(context, capturedContext)
      assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, capturedTarget)
      assertSame(localGateway, capturedProviders?.localGatewayProvider?.invoke())
      assertSame(gatewayBundle.shellGateway, capturedProviders?.shellGatewayProvider?.invoke())
      assertSame(
        gatewayBundle.chatRuntimeGateway,
        capturedProviders?.chatRuntimeGatewayProvider?.invoke(),
      )
      assertSame(gatewayBundle.skillsGateway, capturedProviders?.skillsGatewayProvider?.invoke())
      assertSame(
        gatewayBundle.settingsGateway,
        capturedProviders?.settingsGatewayProvider?.invoke(),
      )
      val reboundGatewayBundle = testServiceGatewayBundle()
      transportCoordinator.bindGatewayBundle(reboundGatewayBundle)
      assertSame(gatewayBundle.shellGateway, capturedProviders?.shellGatewayProvider?.invoke())
      assertSame(
        gatewayBundle.chatRuntimeGateway,
        capturedProviders?.chatRuntimeGatewayProvider?.invoke(),
      )
      assertSame(
        gatewayBundle.skillsGateway,
        capturedProviders?.skillsGatewayProvider?.invoke(),
      )
      assertSame(
        gatewayBundle.settingsGateway,
        capturedProviders?.settingsGatewayProvider?.invoke(),
      )
      assertEquals(1, localGatewayProviderResolveCount)
      assertEquals(
        startedServer.currentState(),
        transportCoordinator.currentLocalRuntimeServerState(),
      )
      assertEquals(
        localRuntimeLoopbackPortForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND),
        transportCoordinator.currentLocalRuntimeServerState()?.requestedPort,
      )
      assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
      assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
    } finally {
      startedServer.close()
    }
  }

  @Test
  fun defaultLoopbackBootstrapFactoryDoesNotConsultProcessRegistryProvidersFactory() {
    OpenCrayLocalRuntimeServerRegistry.clearForTest()
    val context = MinimalContext()
    val gatewayBundle = testServiceGatewayBundle()
    var registryProvidersFactoryCallCount = 0
    OpenCrayLocalRuntimeServerRegistry.setProvidersFactoryForTest(
      OpenCrayLocalRuntimeServerProvidersFactory {
        registryProvidersFactoryCallCount += 1
        error("Service loopback bootstrap should not consult process registry providers.")
      },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
      bindGatewayBundle(gatewayBundle)
    }
    val bootstrap = DefaultRuntimeServiceLoopbackBootstrapFactory(
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayBundle = gatewayBundle,
      transportCoordinator = transportCoordinator,
    )

    try {
      bootstrap.ensureStarted()

      assertEquals(0, registryProvidersFactoryCallCount)
      assertEquals(
        LocalRuntimeServerState.PHASE_LISTENING,
        transportCoordinator.currentLocalRuntimeServerState()?.phase,
      )
    } finally {
      bootstrap.dispose()
      OpenCrayLocalRuntimeServerRegistry.clearForTest()
    }
  }

  @Test
  fun defaultLoopbackBootstrapFactoryUsesEnvironmentLocalHostGatewayByDefault() {
    val localGateway = NoOpLocalHostGateway()
    val context = RuntimeEnvironmentContext(
      OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        localHostGatewayProvider = { localGateway },
      ),
    )
    val gatewayBundle = testServiceGatewayBundle()
    var capturedProviders: OpenCrayLocalRuntimeServerProviders? = null
    val startedServer = OpenCrayLocalRuntimeServer(
      localGatewayProvider = { error("unused in test") },
      shellGatewayProvider = { error("unused in test") },
      chatRuntimeGatewayProvider = { error("unused in test") },
      skillsGatewayProvider = { error("unused in test") },
      settingsGatewayProvider = { error("unused in test") },
      requestedPort = localRuntimeLoopbackPortForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND),
      shutdownExecutorOnClose = true,
    )
    val bootstrap = DefaultRuntimeServiceLoopbackBootstrapFactory(
      ensureServerStarted = { _, _, providers ->
        capturedProviders = providers
        startedServer
      },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { localGateway },
      gatewayBundle = gatewayBundle,
      transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
        bindGatewayBundle(gatewayBundle)
      },
    )

    try {
      bootstrap.ensureStarted()

      assertSame(localGateway, capturedProviders?.localGatewayProvider?.invoke())
    } finally {
      bootstrap.dispose()
      startedServer.close()
    }
  }

  @Test
  fun transportCoordinatorTracksBoundLocalRuntimeServerStateProvider() {
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator()
    val expectedState = LocalRuntimeServerState(
      phase = LocalRuntimeServerState.PHASE_LISTENING,
      bindAddress = "127.0.0.1",
      requestedPort = 8123,
      listeningPort = 9123,
      changedAtEpochMs = 30L,
    )

    assertEquals(defaultLocalRuntimeServerState(), transportCoordinator.currentLocalRuntimeServerState())

    transportCoordinator.bindLocalRuntimeServerStateProvider { expectedState }

    assertEquals(expectedState, transportCoordinator.currentLocalRuntimeServerState())

    transportCoordinator.dispose()

    assertEquals(defaultLocalRuntimeServerState(), transportCoordinator.currentLocalRuntimeServerState())
  }

  @Test
  fun transportCoordinatorUsesTargetScopedFallbackLocalRuntimeServerState() {
    val interactiveCoordinator = DefaultRuntimeServiceTransportCoordinator(
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
    )
    val detachedCoordinator = DefaultRuntimeServiceTransportCoordinator(
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val interactiveState = interactiveCoordinator.currentLocalRuntimeServerState()
    val detachedState = detachedCoordinator.currentLocalRuntimeServerState()
    val expectedInteractiveState = defaultLocalRuntimeServerState(RuntimeServiceTarget.INTERACTIVE)
    val expectedDetachedState = defaultLocalRuntimeServerState(RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertEquals(
      expectedInteractiveState?.copy(
        changedAtEpochMs = interactiveState?.changedAtEpochMs
          ?: expectedInteractiveState.changedAtEpochMs,
      ),
      interactiveState,
    )
    assertEquals(
      expectedDetachedState?.copy(
        changedAtEpochMs = detachedState?.changedAtEpochMs
          ?: expectedDetachedState.changedAtEpochMs,
      ),
      detachedState,
    )

    interactiveCoordinator.dispose()
    detachedCoordinator.dispose()

    assertEquals(
      localRuntimeLoopbackPortForTarget(RuntimeServiceTarget.INTERACTIVE),
      interactiveCoordinator.currentLocalRuntimeServerState()?.requestedPort,
    )
    assertEquals(
      localRuntimeLoopbackPortForTarget(RuntimeServiceTarget.DETACHED_BACKGROUND),
      detachedCoordinator.currentLocalRuntimeServerState()?.requestedPort,
    )
  }

  @Test
  fun transportCoordinatorDisposesPreviousGatewayBundleWhenBindingReplacement() {
    val disposedLabels = mutableListOf<String>()
    val firstGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "first" },
    )
    val secondGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "second" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator()

    transportCoordinator.bindGatewayBundle(firstGatewayBundle)
    assertTrue(disposedLabels.isEmpty())

    transportCoordinator.bindGatewayBundle(secondGatewayBundle)

    assertEquals(listOf("first"), disposedLabels)
    assertSame(secondGatewayBundle, transportCoordinator.currentGatewayBundle())

    transportCoordinator.bindGatewayBundle(secondGatewayBundle)

    assertEquals(listOf("first"), disposedLabels)
  }

  @Test
  fun transportCoordinatorReleaseGatewayBundleOnlyClearsMatchingCurrentBundle() {
    val disposedLabels = mutableListOf<String>()
    val firstGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "first" },
    )
    val secondGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "second" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator()

    transportCoordinator.bindGatewayBundle(firstGatewayBundle)
    transportCoordinator.bindGatewayBundle(secondGatewayBundle)

    assertEquals(listOf("first"), disposedLabels)

    transportCoordinator.releaseGatewayBundle(firstGatewayBundle)

    assertEquals(listOf("first"), disposedLabels)
    assertSame(secondGatewayBundle, transportCoordinator.currentGatewayBundle())

    transportCoordinator.releaseGatewayBundle(secondGatewayBundle)

    assertEquals(listOf("first", "second"), disposedLabels)
    assertTrue(
      runCatching { transportCoordinator.currentGatewayBundle() }.exceptionOrNull()
        is IllegalStateException,
    )
  }

  @Test
  fun transportCoordinatorDisposeDisposesCurrentGatewayBundle() {
    val disposedLabels = mutableListOf<String>()
    val gatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "bound" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator()

    transportCoordinator.bindGatewayBundle(gatewayBundle)
    transportCoordinator.dispose()
    transportCoordinator.dispose()

    assertEquals(listOf("bound"), disposedLabels)
  }

  @Test
  fun resolveRuntimeServiceExecutionCoordinatorUsesInjectedFactoryWithoutTouchingDefaultExecutionBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-execution-coordinator"))
    val keepAliveController = RuntimeServiceKeepAliveController(
      appVisibleProvider = { true },
      scheduler = object : RuntimeServiceDelayScheduler {
        override fun schedule(
          delayMs: Long,
          action: () -> Unit,
        ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
      },
      stopRequester = { false },
    )
    val runtimeForegroundController = RuntimeForegroundController(
      serviceAdapter = object : RuntimeForegroundServiceAdapter {
        override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

        override fun stopForeground(removeNotification: Boolean) = Unit
      },
      appVisibleProvider = { true },
    )
    val expectedCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    var factoryCallCount = 0
    var capturedContext: Context? = null
    var capturedProjectionCoordinator: RuntimeServiceProjectionCoordinator? = null
    var capturedServiceWorkStateTracker: RuntimeServiceWorkStateTracker? = null
    var capturedKeepAliveController: RuntimeServiceKeepAliveController? = null
    var capturedForegroundController: RuntimeForegroundController? = null
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceExecutionCoordinatorFactory =
        RuntimeServiceExecutionCoordinatorFactory {
            appContext,
            coordinatorDependencies,
            resolvedKeepAliveController,
            resolvedRuntimeForegroundController,
          ->
          factoryCallCount += 1
          capturedContext = appContext
          capturedProjectionCoordinator = coordinatorDependencies.projectionCoordinator
          capturedServiceWorkStateTracker = coordinatorDependencies.serviceWorkStateTracker
          capturedKeepAliveController = resolvedKeepAliveController
          capturedForegroundController = resolvedRuntimeForegroundController
          expectedCoordinator
        },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = bootstrapResolver.resolveRuntimeServiceExecutionCoordinator(
      context = context,
      bootstrapState = bootstrapState,
      keepAliveController = keepAliveController,
      runtimeForegroundController = runtimeForegroundController,
    )

    resolved.attach()
    resolved.onStartCommand(startId = 7)
    resolved.persistProjectionSnapshot()
    resolved.dispose()

    assertSame(expectedCoordinator, resolved)
    assertSame(context, capturedContext)
    assertSame(
      bootstrapState.executionCoordinatorDependencies.projectionCoordinator,
      capturedProjectionCoordinator,
    )
    assertSame(
      bootstrapState.executionCoordinatorDependencies.serviceWorkStateTracker,
      capturedServiceWorkStateTracker,
    )
    assertSame(keepAliveController, capturedKeepAliveController)
    assertSame(runtimeForegroundController, capturedForegroundController)
    assertEquals(1, factoryCallCount)
    assertEquals(1, expectedCoordinator.attachCallCount)
    assertEquals(listOf(7), expectedCoordinator.startIds)
    assertEquals(1, expectedCoordinator.persistCallCount)
    assertEquals(1, expectedCoordinator.disposeCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceShellControlBundleUsesInjectedFactoryWithoutTouchingDefaultShellBootstrap() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val retainedShellControl = RuntimeServiceRetainedShellControl(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )
    val expectedBundle = RuntimeServiceShellControlBundle(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )
    var factoryCallCount = 0
    var capturedService: android.app.Service? = null
    var capturedContext: Context? = null
    var capturedHandler: Handler? = null
    var capturedRetainedShellControl: RuntimeServiceRetainedShellControl? = null
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("shell-control-bootstrap-state"),
    )
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceShellControlBundleFactory =
        RuntimeServiceShellControlBundleFactory {
            resolvedService,
            appContext,
            resolvedMainHandler,
            resolvedRetainedShellControl,
          ->
          factoryCallCount += 1
          capturedService = resolvedService
          capturedContext = appContext
          capturedHandler = resolvedMainHandler
          capturedRetainedShellControl = resolvedRetainedShellControl
          expectedBundle
        },
    )

    val resolved = bootstrapResolver.resolveRuntimeServiceShellControlBundle(
      service = service,
      context = context,
      mainHandler = mainHandler,
      bootstrapState = serviceHost.toRuntimeServiceBootstrapState().copy(
        retainedShellControl = retainedShellControl,
      ),
    )

    assertSame(expectedBundle, resolved)
    assertSame(service, capturedService)
    assertSame(context, capturedContext)
    assertSame(mainHandler, capturedHandler)
    assertSame(retainedShellControl, capturedRetainedShellControl)
    assertEquals(1, factoryCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceWakeCommandDispatcherUsesInjectedFactoryWithoutTouchingDefaultDispatcherBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-wake-command-dispatcher"))
    val gatewayBundle = testServiceGatewayBundle()
    val expectedDispatcher = RecordingRuntimeServiceWakeCommandDispatcher()
    var factoryCallCount = 0
    var capturedContext: Context? = null
    var capturedScheduledTaskDispatcherDependencies: ScheduledTaskDispatcherDependencies? = null
    var capturedScheduledTaskRepairDependencies: ScheduledTaskRepairDependencies? = null
    var capturedResumeInterruptedRuns: (() -> Unit)? = null
    var capturedGatewayBundle: OpenCrayRuntimeServiceGatewayBundle? = null
    var capturedProjectionCoordinator: RuntimeServiceProjectionCoordinator? = null
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceWakeCommandDispatcherFactory =
        RuntimeServiceWakeCommandDispatcherFactory {
            appContext,
            dispatcherDependencies,
            resolvedGatewayBundle,
            resolvedProjectionCoordinator,
          ->
          factoryCallCount += 1
          capturedContext = appContext
          capturedScheduledTaskDispatcherDependencies =
            dispatcherDependencies.scheduledTaskDispatcherDependencies
          capturedScheduledTaskRepairDependencies =
            dispatcherDependencies.scheduledTaskRepairDependencies
          capturedResumeInterruptedRuns = dispatcherDependencies.resumeInterruptedRuns
          capturedGatewayBundle = resolvedGatewayBundle
          capturedProjectionCoordinator = resolvedProjectionCoordinator
          expectedDispatcher
        },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = bootstrapResolver.resolveRuntimeServiceWakeCommandDispatcher(
      context = context,
      bootstrapState = bootstrapState,
      gatewayBundle = gatewayBundle,
    )

    resolved.dispatch(null)

    assertSame(expectedDispatcher, resolved)
    assertSame(context, capturedContext)
    assertSame(
      serviceHost.runtimeAccess.hostAccess,
      capturedScheduledTaskDispatcherDependencies?.hostAccess,
    )
    assertSame(
      serviceHost.runtimeAccess.lifecycleDescriptor,
      capturedScheduledTaskDispatcherDependencies?.lifecycleDescriptor,
    )
    assertSame(
      serviceHost.scheduledTaskSpecStore,
      capturedScheduledTaskRepairDependencies?.specStore,
    )
    assertSame(
      capturedScheduledTaskDispatcherDependencies,
      capturedScheduledTaskRepairDependencies?.scheduledTaskDispatcherDependencies,
    )
    assertTrue(capturedResumeInterruptedRuns != null)
    assertSame(gatewayBundle, capturedGatewayBundle)
    assertSame(
      bootstrapState.executionCoordinatorDependencies.projectionCoordinator,
      capturedProjectionCoordinator,
    )
    assertEquals(1, factoryCallCount)
    assertEquals(1, expectedDispatcher.dispatchCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceBinderEndpointUsesInjectedFactoryWithoutTouchingDefaultBinderBootstrap() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-binder-endpoint"))
    val gatewayBundle = testServiceGatewayBundle()
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val expectedEndpoint = RecordingRuntimeServiceBinderEndpoint()
    var factoryCallCount = 0
    var capturedBridgeSnapshotDependencies: RuntimeServiceBridgeSnapshotDependencies? =
      null
    var capturedGatewayBundle: OpenCrayRuntimeServiceGatewayBundle? = null
    var capturedShellStateAccess: RuntimeServiceShellStateAccess? = null
    var capturedProjectionCoordinator: RuntimeServiceProjectionCoordinator? = null
    val bootstrapResolver = testRuntimeServiceBootstrapDependencies(
      runtimeServiceBinderEndpointFactory =
        RuntimeServiceBinderEndpointFactory {
            binderEndpointDependencies,
            resolvedGatewayBundle,
            resolvedShellStateAccess,
            resolvedProjectionCoordinator,
          ->
          factoryCallCount += 1
          capturedBridgeSnapshotDependencies = binderEndpointDependencies.bridgeSnapshotDependencies
          capturedGatewayBundle = resolvedGatewayBundle
          capturedShellStateAccess = resolvedShellStateAccess
          capturedProjectionCoordinator = resolvedProjectionCoordinator
          expectedEndpoint
        },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = bootstrapResolver.resolveRuntimeServiceBinderEndpoint(
      bootstrapState = bootstrapState,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
    )

    assertSame(expectedEndpoint, resolved)
    assertSame(
      serviceHost.runtimeAccess.lifecycleDescriptor,
      capturedBridgeSnapshotDependencies?.runtimeOwnerLifecycle,
    )
    assertEquals(
      serviceHost.runtimeAccess.hostAccess.activeWorkSummary(),
      capturedBridgeSnapshotDependencies?.runtimeOwnerWorkSummaryProvider?.invoke(),
    )
    assertSame(serviceHost.serviceLifecycle, capturedBridgeSnapshotDependencies?.serviceLifecycle)
    assertSame(gatewayBundle, capturedGatewayBundle)
    assertSame(shellStateAccess, capturedShellStateAccess)
    assertSame(
      bootstrapState.executionCoordinatorDependencies.projectionCoordinator,
      capturedProjectionCoordinator,
    )
    assertEquals(1, factoryCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun openCrayAgentRuntimeServiceBootstrapAssemblesInjectedDependenciesIntoSingleBundle() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val expectedShellControlBundle = RuntimeServiceShellControlBundle(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )
    val expectedServiceHost = testServiceHost(temporaryFolder.newFolder("service-bootstrap-bundle"))
    val expectedExecutionController = testRuntimeExecutionController(expectedServiceHost)
    val expectedGatewayBundle = testServiceGatewayBundle()
    val expectedTransportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = expectedGatewayBundle,
    )
    val expectedExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val expectedProjectionCoordinator =
      expectedExecutionController.projectionCoordinator()
    val expectedServiceWorkStateTracker =
      expectedExecutionController.serviceWorkStateTracker()
    val expectedWakeDispatcher = RecordingRuntimeServiceWakeCommandDispatcher()
    val expectedBinderEndpoint = RecordingRuntimeServiceBinderEndpoint()
    var capturedContext: Context? = null
    var capturedService: Service? = null
    var capturedHandler: Handler? = null
    var capturedLifecycle: RuntimeServiceLifecycleDescriptor? = null
    var capturedKeepAliveController: RuntimeServiceKeepAliveController? = null
    var capturedForegroundController: RuntimeForegroundController? = null
    var capturedKeepAliveState: RuntimeServiceKeepAliveState? = null
    var capturedRetainedShellControl: RuntimeServiceRetainedShellControl? = null
    var keepAliveListenerRegistered = false
    val bootstrapResolver = RuntimeServiceBootstrapDependencies(
      runtimeServiceBootstrapStateProvider = RuntimeServiceBootstrapStateProvider {
          resolvedContext,
          _,
          serviceLifecycle,
        ->
        capturedContext = resolvedContext
        RuntimeServiceResolvedBootstrap(
          bootstrapState = expectedExecutionController.toRuntimeServiceBootstrapState(
            serviceLifecycle = serviceLifecycle,
          ),
          resetRuntimeOwnerAction = {
            expectedExecutionController.replaceRuntimeOwner()
          },
        )
      },
      localHostGatewayProvider = { NoOpLocalHostGateway() },
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory {
          appContext,
          gatewayDependencies,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
        ->
        capturedContext = appContext
        assertSame(expectedServiceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSameGatewayRuntimeAccess(
          expected = expectedServiceHost.runtimeAccess.hostAccess,
          actual = gatewayDependencies,
        )
        capturedLifecycle = gatewayDependencies.serviceLifecycle
        capturedKeepAliveState = runtimeServiceKeepAliveStateProvider()
        runtimeServiceKeepAliveChangeRegistrar.register { }
        keepAliveListenerRegistered = true
        expectedGatewayBundle
      },
      runtimeServiceTransportBootstrapFactory = OpenCrayRuntimeServiceTransportBootstrapFactory {
          appContext,
          runtimeTarget,
          localGatewayProvider,
          gatewayDependencies,
          runtimeServiceGatewayBundleFactory,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
          transportCoordinator,
        ->
        capturedContext = appContext
        assertEquals(DEFAULT_RUNTIME_SERVICE_TARGET, runtimeTarget)
        assertSame(expectedServiceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSameGatewayRuntimeAccess(
          expected = expectedServiceHost.runtimeAccess.hostAccess,
          actual = gatewayDependencies,
        )
        val resolvedGatewayBundle = runtimeServiceGatewayBundleFactory.create(
          appContext = appContext,
          gatewayDependencies = gatewayDependencies,
          runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        )
        assertTrue(localGatewayProvider() is OpenCrayLocalHostGateway)
        assertSame(expectedGatewayBundle, resolvedGatewayBundle)
        assertSame(
          expectedExecutionController.transportCoordinator(),
          transportCoordinator,
        )
        expectedTransportBootstrap
      },
      runtimeServiceExecutionCoordinatorFactory = RuntimeServiceExecutionCoordinatorFactory {
          appContext,
          coordinatorDependencies,
          keepAliveController,
          runtimeForegroundController,
        ->
        capturedContext = appContext
        assertSame(expectedProjectionCoordinator, coordinatorDependencies.projectionCoordinator)
        assertSame(expectedServiceWorkStateTracker, coordinatorDependencies.serviceWorkStateTracker)
        capturedKeepAliveController = keepAliveController
        capturedForegroundController = runtimeForegroundController
        expectedExecutionCoordinator
      },
      runtimeServiceShellControlBundleFactory = RuntimeServiceShellControlBundleFactory {
          resolvedService,
          appContext,
          resolvedMainHandler,
          resolvedRetainedShellControl,
        ->
        capturedService = resolvedService
        capturedContext = appContext
        capturedHandler = resolvedMainHandler
        capturedRetainedShellControl = resolvedRetainedShellControl
        expectedShellControlBundle
      },
      runtimeServiceWakeCommandDispatcherFactory = RuntimeServiceWakeCommandDispatcherFactory {
          appContext,
          _,
          gatewayBundle,
          projectionCoordinator,
        ->
        capturedContext = appContext
        assertSame(expectedGatewayBundle, gatewayBundle)
        assertSame(expectedProjectionCoordinator, projectionCoordinator)
        expectedWakeDispatcher
      },
      runtimeServiceBinderEndpointFactory = RuntimeServiceBinderEndpointFactory {
          _,
          gatewayBundle,
          shellStateAccess,
          projectionCoordinator,
        ->
        assertSame(expectedGatewayBundle, gatewayBundle)
        assertSame(expectedExecutionCoordinator, shellStateAccess)
        assertSame(expectedProjectionCoordinator, projectionCoordinator)
        expectedBinderEndpoint
      },
    )

    val resolved = openCrayAgentRuntimeServiceBootstrap(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = bootstrapResolver,
    )

    assertSame(expectedShellControlBundle, resolved.shellControlBundle)
    assertSame(expectedTransportBootstrap, resolved.transportBootstrap)
    assertSame(expectedExecutionCoordinator, resolved.executionCoordinator)
    assertSame(expectedWakeDispatcher, resolved.wakeCommandDispatcher)
    assertSame(expectedBinderEndpoint, resolved.binderEndpoint)
    assertSame(service, capturedService)
    assertSame(context, capturedContext)
    assertSame(mainHandler, capturedHandler)
    assertSame(
      expectedExecutionController.retainedShellControl(),
      capturedRetainedShellControl,
    )
    assertSame(expectedShellControlBundle.keepAliveController, capturedKeepAliveController)
    assertSame(
      expectedShellControlBundle.runtimeForegroundController,
      capturedForegroundController,
    )
    assertEquals(
      expectedShellControlBundle.keepAliveController.currentState().phase,
      capturedKeepAliveState?.phase,
    )
    assertTrue(keepAliveListenerRegistered)
    assertTrue(capturedLifecycle?.serviceInstanceId?.isNotBlank() == true)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun runtimeServiceShellControllerDelegatesLifecycleWithinSingleShellInstance() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val expectedBinderEndpoint = RecordingRuntimeServiceBinderEndpoint(
      dispatchChatWriteCommandHandler = {
        OpenCrayChatWriteDispatchResult.Completed
      },
    )
    val steps = mutableListOf<String>()
    var capturedService: Service? = null
    var capturedContext: Context? = null
    var capturedHandler: Handler? = null
    val shellHost = testServiceHost(
      temporaryFolder.newFolder("runtime-service-shell-controller"),
    )
    val expectedBootstrap = OpenCrayAgentRuntimeServiceBootstrap(
      shellControlBundle = RuntimeServiceShellControlBundle(
        keepAliveController = RuntimeServiceKeepAliveController(
          appVisibleProvider = { true },
          scheduler = object : RuntimeServiceDelayScheduler {
            override fun schedule(
              delayMs: Long,
              action: () -> Unit,
            ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
          },
          stopRequester = { false },
        ),
        runtimeForegroundController = RuntimeForegroundController(
          serviceAdapter = object : RuntimeForegroundServiceAdapter {
            override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

            override fun stopForeground(removeNotification: Boolean) = Unit
          },
          appVisibleProvider = { true },
        ),
        attach = {
          steps += "shell_attach"
        },
        dispose = {
          steps += "shell_dispose"
        },
      ),
      transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
        gatewayBundle = testServiceGatewayBundle(),
        ensureStarted = {
          steps += "transport_started"
        },
        dispose = {
          steps += "transport_dispose"
        },
      ),
      executionCoordinator = object : RuntimeServiceExecutionCoordinator {
        override fun attach() {
          steps += "coordinator_attach"
        }

        override fun onStartCommand(startId: Int) {
          steps += "start:$startId"
        }

        override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
          RuntimeServiceKeepAliveState()

        override fun currentForegroundState(): RuntimeForegroundState = RuntimeForegroundState()

        override fun persistProjectionSnapshot(
          workState: RuntimeServiceWorkState?,
          keepAliveState: RuntimeServiceKeepAliveState?,
        ) = Unit

        override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

        override fun dispose() {
          steps += "dispose"
        }
      },
      wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
        override fun dispatch(intent: Intent?) {
          steps += "dispatch"
        }
      },
      binderEndpoint = expectedBinderEndpoint,
    )
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { resolvedService, resolvedContext, resolvedMainHandler, _ ->
        steps += "assemble_bootstrap"
        capturedService = resolvedService
        capturedContext = resolvedContext
        capturedHandler = resolvedMainHandler
        expectedBootstrap
      },
    )

    controller.attach()
    val startResult = controller.onStartCommand(
      intent = Intent("runtime-shell-start"),
      startId = 7,
    )
    val bound = controller.onBind(null) as RuntimeServiceBinderEndpoint
    val dispatch = bound.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )
    controller.dispose()

    assertEquals(Service.START_NOT_STICKY, startResult)
    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(
      listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      expectedBinderEndpoint.dispatchedChatWriteCommands,
    )
    assertSame(service, capturedService)
    assertSame(context, capturedContext)
    assertSame(mainHandler, capturedHandler)
    assertEquals(
      listOf(
        "assemble_bootstrap",
        "transport_started",
        "shell_attach",
        "coordinator_attach",
        "start:7",
        "dispatch",
        "shell_dispose",
        "transport_dispose",
        "dispose",
      ),
      steps,
    )
  }

  @Test
  fun runtimeServiceShellControllerAttachIsIdempotentWithinSingleShellInstance() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    val shellHost = testServiceHost(
      temporaryFolder.newFolder("runtime-service-shell-controller-idempotent"),
    )
    val expectedBootstrap = OpenCrayAgentRuntimeServiceBootstrap(
      shellControlBundle = RuntimeServiceShellControlBundle(
        keepAliveController = RuntimeServiceKeepAliveController(
          appVisibleProvider = { true },
          scheduler = object : RuntimeServiceDelayScheduler {
            override fun schedule(
              delayMs: Long,
              action: () -> Unit,
            ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
          },
          stopRequester = { false },
        ),
        runtimeForegroundController = RuntimeForegroundController(
          serviceAdapter = object : RuntimeForegroundServiceAdapter {
            override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

            override fun stopForeground(removeNotification: Boolean) = Unit
          },
          appVisibleProvider = { true },
        ),
        attach = {
          steps += "shell_attach"
        },
      ),
      transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
        gatewayBundle = testServiceGatewayBundle(),
        ensureStarted = {
          steps += "transport_started"
        },
      ),
      executionCoordinator = object : RuntimeServiceExecutionCoordinator {
        override fun attach() {
          steps += "coordinator_attach"
        }

        override fun onStartCommand(startId: Int) = Unit

        override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
          RuntimeServiceKeepAliveState()

        override fun currentForegroundState(): RuntimeForegroundState = RuntimeForegroundState()

        override fun persistProjectionSnapshot(
          workState: RuntimeServiceWorkState?,
          keepAliveState: RuntimeServiceKeepAliveState?,
        ) = Unit

        override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

        override fun dispose() = Unit
      },
      wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
        override fun dispatch(intent: Intent?) = Unit
      },
      binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
    )
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        steps += "assemble_bootstrap"
        expectedBootstrap
      },
    )

    controller.attach()
    controller.attach()

    assertEquals(
      listOf(
        "assemble_bootstrap",
        "transport_started",
        "shell_attach",
        "coordinator_attach",
      ),
      steps,
    )
  }

  @Test
  fun runtimeServiceShellControllerRetainsExistingShellWhenAdditionalTargetAttaches() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    val binders = linkedMapOf<RuntimeServiceTarget, RecordingRuntimeServiceBinderEndpoint>()
    var assembleCallCount = 0
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, target ->
        val label = target.wireValue
        assembleCallCount += 1
        val binder = RecordingRuntimeServiceBinderEndpoint(
          dispatchChatWriteCommandHandler = {
            OpenCrayChatWriteDispatchResult.Completed
          },
        )
        binders[target] = binder
        OpenCrayAgentRuntimeServiceBootstrap(
          shellControlBundle = RuntimeServiceShellControlBundle(
            keepAliveController = RuntimeServiceKeepAliveController(
              appVisibleProvider = { true },
              scheduler = object : RuntimeServiceDelayScheduler {
                override fun schedule(
                  delayMs: Long,
                  action: () -> Unit,
                ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
              },
              stopRequester = { false },
            ),
            runtimeForegroundController = RuntimeForegroundController(
              serviceAdapter = object : RuntimeForegroundServiceAdapter {
                override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

                override fun stopForeground(removeNotification: Boolean) = Unit
              },
              appVisibleProvider = { true },
            ),
            attach = {
              steps += "$label:shell_attach"
            },
            dispose = {
              steps += "$label:shell_dispose"
            },
          ),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
            ensureStarted = {
              steps += "$label:transport_started"
            },
            dispose = {
              steps += "$label:transport_dispose"
            },
          ),
          executionCoordinator = object : RuntimeServiceExecutionCoordinator {
            override fun attach() {
              steps += "$label:coordinator_attach"
            }

            override fun onStartCommand(startId: Int) = Unit

            override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
              RuntimeServiceKeepAliveState()

            override fun currentForegroundState(): RuntimeForegroundState =
              RuntimeForegroundState()

            override fun persistProjectionSnapshot(
              workState: RuntimeServiceWorkState?,
              keepAliveState: RuntimeServiceKeepAliveState?,
            ) = Unit

            override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

            override fun dispose() {
              steps += "$label:dispose"
            }
          },
          wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
            override fun dispatch(intent: Intent?) = Unit
          },
          binderEndpoint = binder,
        )
      },
      runtimeTargetReader = { intent ->
        if (runCatching { intent?.action }.getOrNull() == "interactive") {
          RuntimeServiceTarget.INTERACTIVE
        } else {
          RuntimeServiceTarget.DETACHED_BACKGROUND
        }
      },
    )

    controller.attach(RuntimeServiceTarget.DETACHED_BACKGROUND)
    steps.clear()
    controller.attach(RuntimeServiceTarget.INTERACTIVE)

    val bound = controller.onBind(
      RecordingCommandIntent().setAction("interactive"),
    ) as RuntimeServiceBinderEndpoint
    val dispatch = bound.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )
    controller.dispose()

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertTrue(
      binders[RuntimeServiceTarget.DETACHED_BACKGROUND]
        ?.dispatchedChatWriteCommands
        ?.isEmpty() == true,
    )
    assertEquals(
      listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      binders[RuntimeServiceTarget.INTERACTIVE]?.dispatchedChatWriteCommands,
    )
    assertEquals(2, assembleCallCount)
    assertEquals(
      listOf(
        "interactive:transport_started",
        "interactive:shell_attach",
        "interactive:coordinator_attach",
        "interactive:shell_dispose",
        "interactive:transport_dispose",
        "interactive:dispose",
        "detached_background:shell_dispose",
        "detached_background:transport_dispose",
        "detached_background:dispose",
      ),
      steps,
    )
  }

  @Test
  fun runtimeServiceShellControllerKeepsBoundBinderStableAcrossRuntimeReset() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val binders = mutableListOf<RecordingRuntimeServiceBinderEndpoint>()
    var assembleCallCount = 0
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        val binder = RecordingRuntimeServiceBinderEndpoint(
          dispatchChatWriteCommandHandler = {
            OpenCrayChatWriteDispatchResult.Completed
          },
        )
        binders += binder
        assembleCallCount += 1
        OpenCrayAgentRuntimeServiceBootstrap(
          resetRuntimeOwnerAction = { },
          shellControlBundle = defaultShellControlBundle(),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
          ),
          executionCoordinator = FixedStateRuntimeServiceExecutionCoordinator(),
          wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
            override fun dispatch(intent: Intent?) = Unit
          },
          binderEndpoint = binder,
        )
      },
      runtimeTargetReader = { DEFAULT_RUNTIME_SERVICE_TARGET },
      runtimeResetRequested = { true },
      bootstrapForegroundRequested = { false },
    )

    controller.attach()
    val firstBound = controller.onBind(null) as RuntimeServiceBinderEndpoint
    val firstDispatch = firstBound.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    controller.onStartCommand(
      intent = Intent(ACTION_RESET_RUNTIME),
      startId = 21,
    )

    val secondBound = controller.onBind(null) as RuntimeServiceBinderEndpoint
    val secondDispatch = firstBound.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertEquals(2, assembleCallCount)
    assertSame(firstBound, secondBound)
    assertEquals(OpenCrayChatWriteDispatchResult.Completed, firstDispatch)
    assertEquals(OpenCrayChatWriteDispatchResult.Completed, secondDispatch)
    assertEquals(
      listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      binders.first().dispatchedChatWriteCommands,
    )
    assertEquals(
      listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      binders.last().dispatchedChatWriteCommands,
    )
  }

  @Test
  fun runtimeServiceShellControllerRebuildsShellWhenRuntimeResetIsRequested() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    val shellHosts = listOf(
      testServiceHost(
        temporaryFolder.newFolder("runtime-service-shell-controller-reset-first"),
      ),
      testServiceHost(
        temporaryFolder.newFolder("runtime-service-shell-controller-reset-second"),
      ),
    )
    var assembleCallCount = 0
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        val label = if (assembleCallCount == 0) "first" else "second"
        val shellHost = shellHosts[assembleCallCount]
        assembleCallCount += 1
        steps += "assemble:$label"
        val runtimeForegroundController = RuntimeForegroundController(
          serviceAdapter = object : RuntimeForegroundServiceAdapter {
            override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
              steps += "$label:foreground:${model.keepAliveReason}"
            }

            override fun stopForeground(removeNotification: Boolean) = Unit
          },
          appVisibleProvider = { true },
        )
        val executionController = testRuntimeExecutionController(shellHost)
        OpenCrayAgentRuntimeServiceBootstrap(
          resetRuntimeOwnerAction = {
            steps += "runtime_reset:$label"
            executionController.replaceRuntimeOwner()
          },
          shellControlBundle = RuntimeServiceShellControlBundle(
            keepAliveController = RuntimeServiceKeepAliveController(
              appVisibleProvider = { true },
              scheduler = object : RuntimeServiceDelayScheduler {
                override fun schedule(
                  delayMs: Long,
                  action: () -> Unit,
                ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
              },
              stopRequester = { false },
            ),
            runtimeForegroundController = runtimeForegroundController,
            attach = {
              steps += "$label:shell_attach"
            },
            dispose = {
              steps += "$label:shell_dispose"
            },
          ),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
            ensureStarted = {
              steps += "$label:transport_started"
            },
            dispose = {
              steps += "$label:transport_dispose"
            },
          ),
          executionCoordinator = object : RuntimeServiceExecutionCoordinator {
            override fun attach() {
              steps += "$label:coordinator_attach"
            }

            override fun onStartCommand(startId: Int) {
              steps += "$label:start:$startId"
            }

            override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
              RuntimeServiceKeepAliveState()

            override fun currentForegroundState(): RuntimeForegroundState =
              runtimeForegroundController.currentState()

            override fun persistProjectionSnapshot(
              workState: RuntimeServiceWorkState?,
              keepAliveState: RuntimeServiceKeepAliveState?,
            ) = Unit

            override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

            override fun dispose() {
              steps += "$label:dispose"
            }
          },
          wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
            override fun dispatch(intent: Intent?) {
              steps += "$label:dispatch"
            }
          },
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
        )
      },
      runtimeResetRequested = { intent ->
        intent != null
      },
      bootstrapForegroundRequested = { true },
    )

    controller.attach()
    steps.clear()

    val startResult = controller.onStartCommand(
      intent = Intent(ACTION_RESET_RUNTIME),
      startId = 9,
    )

    assertEquals(Service.START_STICKY, startResult)
    assertEquals(2, assembleCallCount)
    assertEquals(
      listOf(
        "first:shell_dispose",
        "first:transport_dispose",
        "first:dispose",
        "runtime_reset:first",
        "assemble:second",
        "second:transport_started",
        "second:shell_attach",
        "second:coordinator_attach",
        "second:foreground:${RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP}",
        "second:start:9",
        "second:dispatch",
      ),
      steps,
    )
  }

  @Test
  fun runtimeServiceShellControllerDoesNotRebuildShellForResumeInterruptedRunsWake() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    var assembleCallCount = 0
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        assembleCallCount += 1
        val runtimeForegroundController = RuntimeForegroundController(
          serviceAdapter = object : RuntimeForegroundServiceAdapter {
            override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
              steps += "foreground:${model.keepAliveReason}"
            }

            override fun stopForeground(removeNotification: Boolean) = Unit
          },
          appVisibleProvider = { true },
        )
        OpenCrayAgentRuntimeServiceBootstrap(
          shellControlBundle = RuntimeServiceShellControlBundle(
            keepAliveController = RuntimeServiceKeepAliveController(
              appVisibleProvider = { true },
              scheduler = object : RuntimeServiceDelayScheduler {
                override fun schedule(
                  delayMs: Long,
                  action: () -> Unit,
                ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
              },
              stopRequester = { false },
            ),
            runtimeForegroundController = runtimeForegroundController,
            attach = {
              steps += "shell_attach"
            },
            dispose = {
              steps += "shell_dispose"
            },
          ),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
            ensureStarted = {
              steps += "transport_started"
            },
          ),
          executionCoordinator = object : RuntimeServiceExecutionCoordinator {
            override fun attach() {
              steps += "coordinator_attach"
            }

            override fun onStartCommand(startId: Int) {
              steps += "start:$startId"
            }

            override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
              RuntimeServiceKeepAliveState()

            override fun currentForegroundState(): RuntimeForegroundState =
              runtimeForegroundController.currentState()

            override fun persistProjectionSnapshot(
              workState: RuntimeServiceWorkState?,
              keepAliveState: RuntimeServiceKeepAliveState?,
            ) = Unit

            override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

            override fun dispose() {
              steps += "dispose"
            }
          },
          wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
            override fun dispatch(intent: Intent?) {
              steps += "dispatch"
            }
          },
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
        )
      },
      runtimeResetRequested = { intent ->
        intentRequestsRuntimeReset(
          intent = intent,
          actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
          forceRuntimeResetReader = { false },
        )
      },
      bootstrapForegroundRequested = { intent ->
        intentRequiresBootstrapForeground(
          intent = intent,
          actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
        )
      },
    )

    controller.attach()
    steps.clear()

    val startResult = controller.onStartCommand(
      intent = null,
      startId = 11,
    )

    assertEquals(Service.START_STICKY, startResult)
    assertEquals(1, assembleCallCount)
    assertEquals(
      listOf(
        "foreground:${RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP}",
        "start:11",
        "dispatch",
      ),
      steps,
    )
  }

  @Test
  fun runtimeServiceShellControllerBootstrapsForegroundForForegroundWakeActions() {
    val actions = listOf(
      ACTION_RUN_SCHEDULED_TASK,
      ACTION_REPAIR_SCHEDULES,
      ACTION_RESUME_INTERRUPTED_RUNS,
    )

    actions.forEachIndexed { index, action ->
      val context = MinimalContext()
      val mainHandler = Handler()
      val service = TestRuntimeService()
      val steps = mutableListOf<String>()
      val controller = runtimeServiceShellController(
        service = service,
        appContext = context,
        mainHandler = mainHandler,
        bootstrapDependencies = defaultRuntimeBootstrapDependencies,
        serviceBootstrapFactory = { _, _, _, _ ->
          val runtimeForegroundController = RuntimeForegroundController(
            serviceAdapter = object : RuntimeForegroundServiceAdapter {
              override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
                steps += "foreground:${model.keepAliveReason}"
              }

              override fun stopForeground(removeNotification: Boolean) = Unit
            },
            appVisibleProvider = { true },
          )
          OpenCrayAgentRuntimeServiceBootstrap(
            shellControlBundle = RuntimeServiceShellControlBundle(
              keepAliveController = RuntimeServiceKeepAliveController(
                appVisibleProvider = { true },
                scheduler = object : RuntimeServiceDelayScheduler {
                  override fun schedule(
                    delayMs: Long,
                    action: () -> Unit,
                  ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
                },
                stopRequester = { false },
              ),
              runtimeForegroundController = runtimeForegroundController,
            ),
            transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
              gatewayBundle = testServiceGatewayBundle(),
            ),
            executionCoordinator = object : RuntimeServiceExecutionCoordinator {
              override fun attach() = Unit

              override fun onStartCommand(startId: Int) {
                steps += "start:$startId"
              }

              override fun currentKeepAliveState(): RuntimeServiceKeepAliveState =
                RuntimeServiceKeepAliveState()

              override fun currentForegroundState(): RuntimeForegroundState =
                runtimeForegroundController.currentState()

              override fun persistProjectionSnapshot(
                workState: RuntimeServiceWorkState?,
                keepAliveState: RuntimeServiceKeepAliveState?,
              ) = Unit

              override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

              override fun dispose() = Unit
            },
            wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
              override fun dispatch(intent: Intent?) {
                steps += "dispatch:$action"
              }
            },
            binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
          )
        },
        bootstrapForegroundRequested = { true },
      )

      controller.attach()
      val startResult = controller.onStartCommand(
        intent = Intent(action),
        startId = index + 1,
      )

      assertEquals(Service.START_STICKY, startResult)
      assertEquals(
        listOf(
          "foreground:${RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP}",
          "start:${index + 1}",
          "dispatch:$action",
        ),
        steps,
      )
    }
  }

  @Test
  fun intentRequestsRuntimeResetDoesNotTreatResumeInterruptedRunsAsResetWithoutForceFlag() {
    val shouldReset = intentRequestsRuntimeReset(
      intent = null,
      actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
      forceRuntimeResetReader = { false },
    )

    assertFalse(shouldReset)
  }

  @Test
  fun intentRequestsRuntimeResetStillHonorsExplicitForceResetFlag() {
    val shouldReset = intentRequestsRuntimeReset(
      intent = null,
      actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
      forceRuntimeResetReader = { true },
    )

    assertTrue(shouldReset)
  }

  @Test
  fun runtimeServiceShellControllerReturnsStickyWhenKeepAliveRemainsInIdleGrace() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        OpenCrayAgentRuntimeServiceBootstrap(
          shellControlBundle = defaultShellControlBundle(),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
          ),
          executionCoordinator = FixedStateRuntimeServiceExecutionCoordinator(
            keepAliveState = RuntimeServiceKeepAliveState(
              phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
              stopScheduled = true,
              stopDeadlineEpochMs = 31_000L,
            ),
          ),
          wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
            override fun dispatch(intent: Intent?) = Unit
          },
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
        )
      },
    )

    controller.attach()
    val startResult = controller.onStartCommand(
      intent = Intent("runtime-shell-start"),
      startId = 5,
    )

    assertEquals(Service.START_STICKY, startResult)
  }

  @Test
  fun runtimeServiceShellControllerReturnsStickyWhenForegroundNotificationIsVisible() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        OpenCrayAgentRuntimeServiceBootstrap(
          shellControlBundle = defaultShellControlBundle(),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
          ),
          executionCoordinator = FixedStateRuntimeServiceExecutionCoordinator(
            foregroundState = RuntimeForegroundState(
              phase = RuntimeForegroundState.PHASE_FOREGROUND,
              notificationVisible = true,
              keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_SERVICE_STARTUP,
            ),
          ),
          wakeCommandDispatcher = object : RuntimeServiceWakeCommandDispatcher {
            override fun dispatch(intent: Intent?) = Unit
          },
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
        )
      },
    )

    controller.attach()
    val startResult = controller.onStartCommand(
      intent = Intent("runtime-shell-start"),
      startId = 6,
    )

    assertEquals(Service.START_STICKY, startResult)
  }

  @Test
  fun defaultRuntimeServiceShellControlBundleFactoryBindsInjectedStopRequesterWithStartId() {
    val context = MinimalContext()
    val service = TestRuntimeService()
    val scheduledActions = mutableListOf<() -> Unit>()
    val stopRequestStartIds = mutableListOf<Int>()
    val retainedShellControl = RuntimeServiceRetainedShellControl(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask {
            scheduledActions += action
            return RuntimeServiceDelayedTask { }
          }
        },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )
    val factory = DefaultRuntimeServiceShellControlBundleFactory(
      appVisibilitySignalAccessProvider = {
        object : AppVisibilitySignalAccess {
          override fun currentVisibility(): Boolean = true

          override fun observe(listener: (Boolean) -> Unit): () -> Unit = { }
        }
      },
      runtimeForegroundServiceAdapterFactory = { _, _ ->
        object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        }
      },
      stopRequesterProvider = { _ ->
        { startId ->
          stopRequestStartIds += startId
          false
        }
      },
    )

    val bundle = factory.create(
      service = service,
      appContext = context,
      mainHandler = Handler(),
      retainedShellControl = retainedShellControl,
    )

    bundle.attach()
    retainedShellControl.keepAliveController.onStartCommand(41)
    scheduledActions.single().invoke()

    assertEquals(listOf(41), stopRequestStartIds)
  }

  @Test
  fun runtimeServiceShellControllerRequiresAttachBeforeStartOrBind() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        error("service bootstrap should not be created before attach")
      },
    )

    val startFailure = runCatching {
      controller.onStartCommand(
        intent = Intent("runtime-shell-start"),
        startId = 1,
      )
    }.exceptionOrNull()
    val bindFailure = runCatching {
      controller.onBind(null)
    }.exceptionOrNull()

    assertTrue(startFailure is IllegalStateException)
    assertTrue(bindFailure is IllegalStateException)
  }

  fun defaultWakeDispatcherHandlesApprovalWakeWithoutTouchingDefaultRegistry() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-approval"),
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.ApproveApproval(
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
          ),
        )
      },
      approvalNotificationDismisser = { _, taskId -> dismissedTaskIds += taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesRejectApprovalWakeWithoutTouchingDefaultRegistry() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-reject"),
      cancelRequestResult = true,
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.RejectApproval(
            sessionId = fixture.sessionId,
            taskId = fixture.taskId,
            runId = fixture.runId,
          ),
        )
      },
      approvalNotificationDismisser = { _, taskId -> dismissedTaskIds += taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertEquals(listOf(fixture.taskId), fixture.handle.cancelledTaskIds)
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherUsesRunIdForApprovalRoutingWhileDismissingProvidedTaskId() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-run-id-routing"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.ApproveApproval(
            sessionId = fixture.sessionId,
            taskId = "notification-task-id",
            runId = fixture.runId,
          ),
        )
      },
      approvalNotificationDismisser = { _, taskId -> dismissedTaskIds += taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf("notification-task-id"), dismissedTaskIds)
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherForwardsScheduledWakeOutcomeAndPersistsProjection() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("wake-dispatcher-scheduled"))
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ScheduledTask(
          ScheduledTaskWakeCommand(
            scheduleId = "missing-schedule",
            scheduleRunId = "schedule-run-alpha",
            triggeredAtEpochMs = 1_234L,
            triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(1, projectionCoordinator.persistCallCount)
    assertEquals(1, projectionCoordinator.scheduledDispatchOutcomes.size)
    val outcome = projectionCoordinator.scheduledDispatchOutcomes.single()
    assertEquals(ScheduledTaskRunResult.FAILED_MISSING_SPEC, outcome.result)
    assertEquals("missing-schedule", outcome.scheduleId)
    assertEquals("schedule-run-alpha", outcome.scheduleRunId)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesResumeWakeAndPersistsProjection() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-resume"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf(fixture.sessionId), fixture.resumedSessionIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesRepairWakeAndPersistsProjection() {
    val context = MinimalContext()
    val fixture = scheduledRepairWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-repair"),
      nowEpochMs = 2_000L,
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.RepairSchedules(ScheduledTaskRepairReasons.WORK_MANAGER)
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    val runRecord = fixture.serviceHost.scheduledTaskRunRecordStore.list().single()
    assertEquals(fixture.scheduleId, runRecord.scheduleId)
    assertEquals(ScheduledTaskTriggerReasons.REPAIR, runRecord.triggerReason)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesScheduleNotificationActionAndPersistsProjection() {
    val context = MinimalContext()
    val nowEpochMs = 3_000L
    val fixture = scheduledRepairWakeDispatcherFixture(
      root = temporaryFolder.newFolder("wake-dispatcher-schedule-notification"),
      nowEpochMs = nowEpochMs,
    )
    val dispatcherDependencies = fixture.serviceHost
      .toRuntimeServiceBootstrapState()
      .wakeCommandDispatcherDependencies
      .let { dependencies ->
        dependencies.copy(
          scheduledTaskDispatcherDependencies = dependencies.scheduledTaskDispatcherDependencies.copy(
            assistantPlaceholderTextProvider = { "Thinking..." },
          ),
        )
      }
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = dispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.Notification(
          RuntimeServiceNotificationCommand.RunScheduleNow(
            sessionId = fixture.sessionId,
            scheduleId = fixture.scheduleId,
          ),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
      nowEpochMsProvider = { nowEpochMs },
    )

    dispatcher.dispatch(null)

    val runRecord = requireNotNull(
      fixture.serviceHost.scheduledTaskRunRecordStore.get(
        scheduledTaskRunId(fixture.scheduleId, nowEpochMs),
      ),
    )
    assertEquals(fixture.scheduleId, runRecord.scheduleId)
    assertEquals(fixture.sessionId, runRecord.sessionId)
    assertEquals(ScheduledTaskTriggerReasons.MANUAL, runRecord.triggerReason)
    assertEquals(ScheduledTaskRunResult.ACCEPTED, runRecord.result)
    assertEquals(1, fixture.handle.submittedTasks.size)
    assertEquals(
      fixture.scheduleId,
      fixture.handle.submittedTasks.single().metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID],
    )
    assertEquals(1, fixture.handle.ensureProcessingCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertEquals(1, projectionCoordinator.scheduledDispatchOutcomes.size)
    assertEquals(ScheduledTaskRunResult.ACCEPTED, projectionCoordinator.scheduledDispatchOutcomes.single().result)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesChatWriteWakeAndPersistsProjection() {
    val context = MinimalContext()
    var interruptedTaskIdOrRunId: String? = null
    val gatewayBundle = testServiceGatewayBundle(
      interruptChatRun = { identifier ->
        interruptedTaskIdOrRunId = identifier
      },
    )
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = testServiceHost(
        temporaryFolder.newFolder("wake-dispatcher-chat-write"),
      ).toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        RuntimeServiceWakeIntentCommand.ChatWrite(
          OpenCrayChatWriteCommand.InterruptChatRun("run-wake"),
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals("run-wake", interruptedTaskIdOrRunId)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherNoOpsForUnknownWakeAction() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-unknown"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser { null },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(0, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertTrue(fixture.resumedSessionIds.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherNoOpsForMalformedScheduledWake() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-malformed-scheduled"),
    )
    val gatewayBundle = testServiceGatewayBundle()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      projectionCoordinator = projectionCoordinator,
      wakeIntentParser = RuntimeServiceWakeIntentParser {
        parseScheduledTaskWakeCommand(
          action = ACTION_RUN_SCHEDULED_TASK,
          scheduleId = "   ",
          scheduleRunId = "schedule-run-alpha",
          triggeredAtEpochMs = 1_234L,
          triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
          targetSessionId = null,
        )?.let(RuntimeServiceWakeIntentCommand::ScheduledTask)
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(0, projectionCoordinator.persistCallCount)
    assertTrue(projectionCoordinator.scheduledDispatchOutcomes.isEmpty())
    assertTrue(fixture.resumedSessionIds.isEmpty())
    assertTrue(fixture.handle.cancelledTaskIds.isEmpty())
    assertTrue(fixture.handle.resumedTaskIds.isEmpty())
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointHandlesApprovalWriteWithoutTouchingDefaultRegistry() {
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("binder-endpoint-approval"),
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.ApproveChatApproval(fixture.taskId),
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointDelegatesGenericChatWriteAndPersistsProjection() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-chat"))
    var refreshSandboxSessionInfoCallCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      refreshSandboxSessionInfo = { refreshSandboxSessionInfoCallCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(1, refreshSandboxSessionInfoCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultBinderEndpointRoutesChatWriteThroughResolvedServiceTarget() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-forwarded-chat"))
    var localRefreshSandboxSessionInfoCallCount = 0
    val forwardedCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val forwardedClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.binderConnected(),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected()

      override fun dispatchChatWriteCommand(
        command: OpenCrayChatWriteCommand,
      ): OpenCrayChatWriteDispatchResult {
        forwardedCommands += command
        return OpenCrayChatWriteDispatchResult.Completed
      }
    }
    val gatewayBundle = testServiceGatewayBundle(
      refreshSandboxSessionInfo = { localRefreshSandboxSessionInfoCallCount += 1 },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies.copy(
        chatWriteTargetResolver = ChatRuntimeWriteTargetResolver {
          RuntimeServiceTarget.DETACHED_BACKGROUND
        },
        targetScopedServiceClientProvider = { forwardedClient },
      ),
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(0, localRefreshSandboxSessionInfoCallCount)
    assertEquals(
      listOf(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
      forwardedCommands,
    )
    assertEquals(1, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  fun defaultBinderEndpointDoesNotPersistProjectionForSkillsOrSettingsWrites() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("binder-endpoint-settings-skills"))
    var refreshSkillsCallCount = 0
    val notificationPayloads = mutableListOf<Map<String, Any?>>()
    val gatewayBundle = testServiceGatewayBundle(
      refreshSkills = {
        refreshSkillsCallCount += 1
        "skills refreshed"
      },
      saveNotificationSettings = { payload ->
        notificationPayloads += payload
        mapOf("saved" to true)
      },
    )
    val shellStateAccess = RecordingRuntimeServiceShellStateAccess()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      shellStateAccess = shellStateAccess,
      projectionCoordinator = projectionCoordinator,
    )

    val skillsDispatch = endpoint.dispatchSkillsWriteCommand(
      OpenCraySkillsWriteCommand.RefreshSkills,
    )
    val settingsDispatch = endpoint.dispatchSettingsWriteCommand(
      OpenCraySettingsWriteCommand.SaveNotificationSettings(
        payload = mapOf("enabled" to true),
      ),
    )

    assertEquals(OpenCraySkillsWriteDispatchResult.Message("skills refreshed"), skillsDispatch)
    assertEquals(
      OpenCraySettingsWriteDispatchResult.Payload(mapOf("saved" to true)),
      settingsDispatch,
    )
    assertEquals(1, refreshSkillsCallCount)
    assertEquals(listOf(mapOf("enabled" to true)), notificationPayloads)
    assertEquals(0, projectionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  private fun RuntimeServiceBootstrapDependencies.resolveRuntimeServiceBootstrapStateForTest(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor = {
      RuntimeServiceLifecycleDescriptor()
    },
  ): RuntimeServiceBootstrapState = resolveRuntimeServiceBootstrap(
    context = context,
    serviceLifecycle = serviceLifecycleFactory(),
  ).bootstrapState

  private fun clearRuntimeSingletons() {
    resetDefaultRuntimeServiceExecutionController()
    OpenCrayRuntimeServiceHostRegistry.clearForTest()
    InProcessOpenCrayRuntimeOwnerRegistry.clearForTest()
  }

  @Test
  fun runtimeServiceTargetForNotificationTaskDefaultsInteractiveForRegularTasks() {
    val target = runtimeServiceTargetForNotificationTask(notificationTargetTestTask())

    assertEquals(RuntimeServiceTarget.INTERACTIVE, target)
  }

  @Test
  fun runtimeServiceTargetForNotificationTaskUsesDetachedBackgroundForScheduledTasks() {
    val target = runtimeServiceTargetForNotificationTask(
      notificationTargetTestTask(
        metadata = mapOf(ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-1"),
      ),
    )

    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
  }

  @Test
  fun runtimeServiceTargetForNotificationTaskUsesDetachedBackgroundForDetachedControlTasks() {
    val target = runtimeServiceTargetForNotificationTask(
      notificationTargetTestTask(
        metadata = mapOf(
          METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
        ),
      ),
    )

    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
  }

  @Test
  fun defaultWakeIntentParserMapsResumeActionToCommand() {
    val parser = DefaultRuntimeServiceWakeIntentParser(
      descriptorParser = DefaultRuntimeServiceIntentDescriptorParser(
        notificationCommandParser = { null },
        scheduledTaskWakeCommandParser = { null },
        actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
      ),
    )

    val parsed = parser.parse(null)

    assertEquals(RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns, parsed)
  }

  @Test
  fun defaultIntentDescriptorParserMarksResetAsForegroundResetWithoutWakeCommand() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_RESET_RUNTIME },
      forceRuntimeResetReader = { false },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertTrue(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserPreservesResumeWakeWhileHonoringForceReset() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
      forceRuntimeResetReader = { true },
    ).parse(null)

    assertEquals(RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns, parsed.wakeCommand)
    assertTrue(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserReadsRuntimeTargetEnvelope() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
    ).parse(
      RecordingCommandIntent().putExtra(
        EXTRA_RUNTIME_SERVICE_TARGET,
        RuntimeServiceTarget.INTERACTIVE.wireValue,
      ),
    )

    assertEquals(RuntimeServiceTarget.INTERACTIVE, parsed.runtimeTarget)
  }

  @Test
  fun defaultWakeIntentParserDefaultsBlankRepairReason() {
    val parser = DefaultRuntimeServiceWakeIntentParser(
      descriptorParser = DefaultRuntimeServiceIntentDescriptorParser(
        notificationCommandParser = { null },
        scheduledTaskWakeCommandParser = { null },
        actionReader = { ACTION_REPAIR_SCHEDULES },
        repairReasonReader = { "   " },
      ),
    )

    val parsed = parser.parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.RepairSchedules(
        ScheduledTaskRepairReasons.WORK_MANAGER,
      ),
      parsed,
    )
  }

  @Test
  fun defaultIntentDescriptorParserDefaultsBlankRepairReason() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_REPAIR_SCHEDULES },
      repairReasonReader = { "   " },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.RepairSchedules(ScheduledTaskRepairReasons.WORK_MANAGER),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksChatWriteWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "run-transport" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ChatWrite(
        OpenCrayChatWriteCommand.InterruptChatRun("run-transport"),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserMarksScheduleNotificationWakeAsForegroundBootstrap() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_RUN_SCHEDULE_NOW },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW },
      scheduleIdReader = { "schedule-foreground" },
      notificationSessionIdReader = { "session-foreground" },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.RunScheduleNow(
          sessionId = "session-foreground",
          scheduleId = "schedule-foreground",
        ),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserPrefersExplicitCommandKindEnvelope() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_SCHEDULED_TASK },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_RESET_RUNTIME },
      scheduleIdReader = { "schedule-1" },
      scheduleRunIdReader = { "run-1" },
      triggeredAtEpochMsReader = { 5L },
      triggerReasonReader = { "alarm" },
      targetSessionIdReader = { null },
      forceRuntimeResetReader = { false },
    ).parse(null)

    assertEquals(
      RuntimeServiceWakeIntentCommand.ScheduledTask(
        ScheduledTaskWakeCommand(
          scheduleId = "schedule-1",
          scheduleRunId = "run-1",
          triggeredAtEpochMs = 5L,
          triggerReason = "alarm",
          targetSessionId = null,
        ),
      ),
      parsed.wakeCommand,
    )
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun runtimeServiceIntentFactoryWritesCommandEnvelopeMetadata() {
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider {
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val context = MinimalContext()

    val scheduledIntent = factory.scheduledTaskIntent(
      context = context,
      command = ScheduledTaskWakeCommand(
        scheduleId = "schedule-1",
        scheduleRunId = "run-1",
        triggeredAtEpochMs = 9L,
        triggerReason = "alarm",
        targetSessionId = "session-1",
      ),
    )
    val interactiveBaseIntent = factory.baseIntent(
      context = context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    val resetIntent = factory.resetRuntimeIntent(
      context = context,
      repairReason = "repair",
    )
    val chatWriteIntent = factory.chatWriteIntent(
      context = context,
      command = OpenCrayChatWriteCommand.InterruptChatRun("run-transport"),
      target = RuntimeServiceTarget.INTERACTIVE,
    )
    val approvalIntent = factory.approvalActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL,
      sessionId = "session-1",
      taskId = "task-1",
      runId = "run-1",
    )
    val scheduleActionIntent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      scheduleId = "schedule-1",
      sessionId = "session-1",
    )

    assertEquals(
      RUNTIME_SERVICE_COMMAND_VERSION_CURRENT,
      scheduledIntent.getIntExtra(EXTRA_RUNTIME_SERVICE_COMMAND_VERSION, 0),
    )
    assertEquals(
      COMMAND_KIND_SCHEDULED_TASK,
      scheduledIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_RESET_RUNTIME,
      resetIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_APPROVE_APPROVAL,
      approvalIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_RUN_SCHEDULE_NOW,
      scheduleActionIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
      chatWriteIntent?.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      "run-transport",
      chatWriteIntent?.getStringExtra(EXTRA_CHAT_WRITE_IDENTIFIER),
    )
    assertEquals(
      DEFAULT_RUNTIME_SERVICE_TARGET.wireValue,
      scheduledIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE.wireValue,
      interactiveBaseIntent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE.wireValue,
      chatWriteIntent?.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
    assertEquals(
      "schedule-1",
      scheduleActionIntent.getStringExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SCHEDULE_ID),
    )
  }

  @Test
  fun runtimeServiceIntentFactoryRoundTripsScheduleNotificationActions() {
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider {
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val parser = DefaultRuntimeServiceWakeIntentParser()
    val context = MinimalContext()

    val intent = factory.scheduleNotificationActionIntent(
      context = context,
      action = RuntimeNotificationIntentActions.ACTION_RUN_SCHEDULE_NOW,
      scheduleId = "schedule-action",
      sessionId = "session-action",
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )

    assertEquals(
      COMMAND_KIND_RUN_SCHEDULE_NOW,
      intent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
    )
    assertEquals(
      RuntimeServiceWakeIntentCommand.Notification(
        RuntimeServiceNotificationCommand.RunScheduleNow(
          sessionId = "session-action",
          scheduleId = "schedule-action",
        ),
      ),
      parser.parse(intent),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      intent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
    )
  }

  @Test
  fun runtimeServiceIntentFactoryRoundTripsSupportedChatWriteWakeCommands() {
    val factory = RuntimeServiceIntentFactory(
      componentProvider = RuntimeServiceComponentProvider {
        android.content.ComponentName("com.opencray.test", "RuntimeService")
      },
      intentBuilder = RuntimeServiceIntentBuilder { _, _ ->
        RecordingCommandIntent()
      },
    )
    val parser = DefaultRuntimeServiceWakeIntentParser()
    val context = MinimalContext()
    val supportedCommands = listOf(
      OpenCrayChatWriteCommand.ApproveChatApproval("task-1") to
        COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL,
      OpenCrayChatWriteCommand.ApproveChatApprovalForSession("run-2") to
        COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION,
      OpenCrayChatWriteCommand.RejectChatApproval("task-3") to
        COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL,
      OpenCrayChatWriteCommand.InterruptChatRun("run-4") to
        COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
      OpenCrayChatWriteCommand.RetryChatRun("run-5") to
        COMMAND_KIND_CHAT_WRITE_RETRY_RUN,
    )

    supportedCommands.forEach { (command, commandKind) ->
      val intent = requireNotNull(
        factory.chatWriteIntent(
          context = context,
          command = command,
          target = RuntimeServiceTarget.INTERACTIVE,
        ),
      )

      assertEquals(
        commandKind,
        intent.getStringExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND),
      )
      assertEquals(
        RuntimeServiceWakeIntentCommand.ChatWrite(command),
        parser.parse(intent),
      )
      assertEquals(
        RuntimeServiceTarget.INTERACTIVE.wireValue,
        intent.getStringExtra(EXTRA_RUNTIME_SERVICE_TARGET),
      )
    }

    assertNull(
      factory.chatWriteIntent(
        context = context,
        command = OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
        target = RuntimeServiceTarget.INTERACTIVE,
      ),
    )
  }

  @Test
  fun defaultIntentDescriptorParserRejectsChatWriteWakeWithBlankIdentifier() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "   " },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertFalse(parsed.requestsRuntimeReset)
    assertTrue(parsed.requiresBootstrapForeground)
  }

  @Test
  fun defaultIntentDescriptorParserRejectsChatWriteWakeWhenCommandVersionMismatches() {
    val parsed = DefaultRuntimeServiceIntentDescriptorParser(
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      commandKindReader = { COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN },
      commandVersionReader = { RUNTIME_SERVICE_COMMAND_VERSION_CURRENT + 1 },
      actionReader = { ACTION_DISPATCH_CHAT_WRITE },
      chatWriteIdentifierReader = { "run-version-mismatch" },
    ).parse(null)

    assertNull(parsed.wakeCommand)
    assertFalse(parsed.requestsRuntimeReset)
    assertFalse(parsed.requiresBootstrapForeground)
  }

  private data class PendingApprovalWakeDispatcherFixture(
    val serviceHost: OpenCrayRuntimeServiceHost,
    val sessionId: String,
    val runId: String,
    val taskId: String,
    val resumedSessionIds: MutableList<String>,
    val handle: RecordingAgentSessionHandle,
  )

  private data class ScheduledRepairWakeDispatcherFixture(
    val serviceHost: OpenCrayRuntimeServiceHost,
    val sessionId: String,
    val scheduleId: String,
    val handle: RecordingAgentSessionHandle,
  )

  private fun pendingApprovalWakeDispatcherFixture(
    root: java.io.File,
    cancelRequestResult: Boolean = false,
    resumeRequestResult: Boolean = true,
  ): PendingApprovalWakeDispatcherFixture {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "pending-approval-run-1"
    val taskId = "pending-approval-task-1"
    val pendingMessageId = "pending-message-1"
    val queueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.RUNNING,
      updatedAtEpochMs = 1_200L,
      tasks = listOf(
        SessionQueueTaskSnapshot(
          enqueueOrder = 1L,
          task = AgentTask(
            id = taskId,
            type = AgentTaskType.PROMPT,
            input = "Need approval",
            state = AgentTaskState.SUSPENDED,
            policyDecision = PolicyDecision(
              outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
              reasonCode = "test",
            ),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_200L,
            metadata = mapOf(
              AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
            ),
          ),
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          attempt = 1,
          lastErrorCode = "APPROVAL_REQUIRED",
          lastErrorMessage = "Approval is required before Bash can run.",
        ),
      ),
    )
    val runSnapshot = AgentRunSnapshot(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_200L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = AgentTaskState.SUSPENDED,
      attempt = 1,
      executionOrdinal = 1,
      executionId = "execution-1",
      executionKind = "initial",
      executionStatus = ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
      errorMessage = "Approval is required before Bash can run.",
      resultMetadata = mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
      ),
      pendingMessageId = pendingMessageId,
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val handle = RecordingAgentSessionHandle(
      sessionId = sessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      runs = listOf(runSnapshot),
      queueSnapshot = queueSnapshot,
      cancelRequestResult = cancelRequestResult,
      resumeRequestResult = resumeRequestResult,
    )
    runtimeManager.putHandle(handle)
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-waiting-approval",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
        toolName = "bash",
        pendingMessageId = pendingMessageId,
      ),
    )
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val serviceHost = OpenCrayRuntimeServiceHost(
      dependencies = testRuntimeDependencies(
        root = root.toPath(),
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-pending-approval-test",
        serviceCreatedAtEpochMs = 1_000L,
      ),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
    return PendingApprovalWakeDispatcherFixture(
      serviceHost = serviceHost,
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      handle = handle,
    )
  }

  private fun scheduledRepairWakeDispatcherFixture(
    root: java.io.File,
    nowEpochMs: Long,
  ): ScheduledRepairWakeDispatcherFixture {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val handle = RecordingAgentSessionHandle(
      sessionId = sessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
    )
    runtimeManager.putHandle(handle)
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val serviceHost = OpenCrayRuntimeServiceHost(
      dependencies = testRuntimeDependencies(
        root = root.toPath(),
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-repair-wake-test",
        serviceCreatedAtEpochMs = nowEpochMs,
      ),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
    val scheduleId = "repair-schedule-1"
    serviceHost.scheduledTaskSpecStore.upsert(
      ScheduledTaskSpec(
        scheduleId = scheduleId,
        sessionId = sessionId,
        title = "Repair wake schedule",
        enabled = true,
        trigger = ScheduledTrigger.RunAtTimestamp(triggerAtEpochMs = nowEpochMs - 500L),
        payload = ScheduledTaskPayload(prompt = "Run repaired scheduled task"),
        createdAtEpochMs = nowEpochMs - 1_000L,
        updatedAtEpochMs = nowEpochMs - 1_000L,
      ),
    )
    return ScheduledRepairWakeDispatcherFixture(
      serviceHost = serviceHost,
      sessionId = sessionId,
      scheduleId = scheduleId,
      handle = handle,
    )
  }

  private class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  private class RuntimeEnvironmentContext(
    val environment: OpenCrayRuntimeServiceEnvironment,
  ) : ContextWrapper(null), OpenCrayRuntimeServiceEnvironmentOwner {
    override val openCrayRuntimeServiceEnvironment: OpenCrayRuntimeServiceEnvironment
      get() = environment

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  private class FilesDirBackedContext(
    private val resolvedFilesDir: File,
  ) : ContextWrapper(null) {
    private val sharedPreferences = linkedMapOf<String, SharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"

    override fun getFilesDir(): File = resolvedFilesDir

    override fun getSharedPreferences(
      name: String?,
      mode: Int,
    ): SharedPreferences = sharedPreferences.getOrPut(name.orEmpty()) {
      InMemorySharedPreferences()
    }
  }

  private class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
      key: String?,
      defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
      key: String?,
      defValues: MutableSet<String>?,
    ): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(
      key: String?,
      defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
      key: String?,
      defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
      key: String?,
      defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
      key: String?,
      defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
      private val pendingValues = linkedMapOf<String, Any?>()
      private val removals = linkedSetOf<String>()
      private var clearRequested: Boolean = false

      override fun putString(
        key: String?,
        value: String?,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
      ): SharedPreferences.Editor = applyChange(key, values?.toSet())

      override fun putInt(
        key: String?,
        value: Int,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putLong(
        key: String?,
        value: Long,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putFloat(
        key: String?,
        value: Float,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putBoolean(
        key: String?,
        value: Boolean,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun remove(key: String?): SharedPreferences.Editor {
        if (key != null) {
          removals += key
        }
        return this
      }

      override fun clear(): SharedPreferences.Editor {
        clearRequested = true
        pendingValues.clear()
        removals.clear()
        return this
      }

      override fun commit(): Boolean {
        if (clearRequested) {
          values.clear()
        }
        removals.forEach(values::remove)
        pendingValues.forEach { (key, value) ->
          if (value == null) {
            values.remove(key)
          } else {
            values[key] = value
          }
        }
        return true
      }

      override fun apply() {
        commit()
      }

      private fun applyChange(
        key: String?,
        value: Any?,
      ): SharedPreferences.Editor {
        if (key != null) {
          pendingValues[key] = value
          removals.remove(key)
        }
        return this
      }
    }
  }

  private class RecordingCommandIntent : Intent() {
    private val extras: MutableMap<String, Any?> = linkedMapOf()
    private var storedAction: String? = null

    override fun setAction(action: String?): Intent {
      storedAction = action
      return this
    }

    override fun getAction(): String? = storedAction

    override fun putExtra(name: String?, value: String?): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(name: String?, value: Int): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(name: String?, value: Long): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(name: String?, value: Boolean): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun getStringExtra(name: String?): String? =
      name?.let(extras::get) as? String

    override fun getIntExtra(
      name: String?,
      defaultValue: Int,
    ): Int = (name?.let(extras::get) as? Int) ?: defaultValue

    override fun getLongExtra(
      name: String?,
      defaultValue: Long,
    ): Long = (name?.let(extras::get) as? Long) ?: defaultValue
  }

  private class RecordingRuntimeServiceStarter : RuntimeServiceStarter {
    var throwOnStart: Boolean = false
    val startAttempts = mutableListOf<RecordedStart>()
    val startedRequests = mutableListOf<RecordedStart>()

    override fun start(
      context: Context,
      intent: Intent,
      foreground: Boolean,
    ): Boolean {
      val attempt = RecordedStart(
        contextPackageName = context.packageName,
        intent = intent,
        foreground = foreground,
      )
      startAttempts += attempt
      if (throwOnStart) {
        return false
      }
      startedRequests += attempt
      return true
    }
  }

  private data class RecordedStart(
    val contextPackageName: String,
    val intent: Intent,
    val foreground: Boolean,
  )

  private class RecordingRuntimeServiceEndpoint : RuntimeServiceEndpoint {
    val baseIntentSentinel: Intent = Intent()
    val scheduledTaskIntentSentinel: Intent = Intent()
    val scheduledRepairIntentSentinel: Intent = Intent()
    val resetRuntimeIntentSentinel: Intent = Intent()
    val resumeInterruptedRunsIntentSentinel: Intent = Intent()
    val scheduleNotificationActionPendingIntentSentinel: android.app.PendingIntent? = null
    var chatWriteIntentSentinel: Intent? = null
    var baseIntentCallCount: Int = 0
      private set
    val baseIntentTargets = mutableListOf<RuntimeServiceTarget>()
    val scheduledCommands = mutableListOf<ScheduledTaskWakeCommand>()
    val scheduledTaskTargets = mutableListOf<RuntimeServiceTarget>()
    val scheduledRepairReasons = mutableListOf<String>()
    val scheduledRepairTargets = mutableListOf<RuntimeServiceTarget>()
    val resetRuntimeReasons = mutableListOf<String>()
    val resetRuntimeTargets = mutableListOf<RuntimeServiceTarget>()
    val resumeInterruptedRunsReasons = mutableListOf<String>()
    val resumeInterruptedRunsTargets = mutableListOf<RuntimeServiceTarget>()

    override fun baseIntent(
      context: Context,
      target: RuntimeServiceTarget,
    ): Intent {
      baseIntentCallCount += 1
      baseIntentTargets += target
      return baseIntentSentinel
    }

    override fun scheduledTaskIntent(
      context: Context,
      command: ScheduledTaskWakeCommand,
      target: RuntimeServiceTarget,
    ): Intent {
      scheduledCommands += command
      scheduledTaskTargets += target
      return scheduledTaskIntentSentinel
    }

    override fun scheduledRepairIntent(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Intent {
      scheduledRepairReasons += repairReason
      scheduledRepairTargets += target
      return scheduledRepairIntentSentinel
    }

    override fun resetRuntimeIntent(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Intent {
      resetRuntimeReasons += repairReason
      resetRuntimeTargets += target
      return resetRuntimeIntentSentinel
    }

    override fun resumeInterruptedRunsIntent(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Intent {
      resumeInterruptedRunsReasons += repairReason
      resumeInterruptedRunsTargets += target
      return resumeInterruptedRunsIntentSentinel
    }

    override fun chatWriteIntent(
      context: Context,
      command: OpenCrayChatWriteCommand,
      target: RuntimeServiceTarget,
    ): Intent? = chatWriteIntentSentinel

    override fun approvalActionPendingIntent(
      context: Context,
      action: String,
      sessionId: String,
      taskId: String,
      runId: String,
      requestCode: Int,
      target: RuntimeServiceTarget,
    ): android.app.PendingIntent = error("Approval pending intent should not be used in this test.")

    override fun scheduleNotificationActionPendingIntent(
      context: Context,
      action: String,
      scheduleId: String,
      sessionId: String?,
      requestCode: Int,
      target: RuntimeServiceTarget,
    ): android.app.PendingIntent =
      scheduleNotificationActionPendingIntentSentinel
        ?: error("Schedule notification pending intent should not be used in this test.")
  }

  private class RecordingRuntimeServiceClientProvider(
    initialClient: OpenCrayRuntimeServiceClient,
  ) : RuntimeServiceClientProvider {
    var clientOverride: OpenCrayRuntimeServiceClient = initialClient
    val createdContexts = mutableListOf<Context>()
    val createdBootstraps = mutableListOf<RuntimeServiceClientBootstrap>()
    var createCallCount: Int = 0
      private set

    override fun create(
      context: Context,
      bootstrap: RuntimeServiceClientBootstrap,
    ): OpenCrayRuntimeServiceClient {
      createCallCount += 1
      createdContexts += context
      createdBootstraps += bootstrap
      return clientOverride
    }
  }

  private class DisposableRuntimeServiceClient : OpenCrayRuntimeServiceClient {
    var disposeCallCount: Int = 0
      private set

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun dispose() {
      disposeCallCount += 1
    }
  }

  private fun notificationTargetTestTask(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-notification-target",
    type = AgentTaskType.PROMPT,
    input = "hello",
    state = AgentTaskState.QUEUED,
    policyDecision = PolicyDecision(
      outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
      reasonCode = "test",
    ),
    createdAtEpochMs = 1L,
    metadata = metadata,
  )

  private fun testServiceHost(root: java.io.File): OpenCrayRuntimeServiceHost {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val runtimeAccess = testRuntimeAccess()
    return OpenCrayRuntimeServiceHost(
      dependencies = testRuntimeDependencies(
        root = root.toPath(),
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-host-test",
        serviceCreatedAtEpochMs = 4321L,
      ),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
  }

  private fun testRuntimeDependencies(
    root: Path,
    chatStore: ChatSessionLocalStore,
  ): OpenCrayRuntimeContextDependencies {
    val appContext = FilesDirBackedContext(
      root.resolve("android-context-files").toFile().apply {
        mkdirs()
      },
    )
    return OpenCrayRuntimeContextDependencies(
      appContext = appContext,
      localizedContext = appContext,
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      sandboxSettingsRepository = testSandboxSettingsRepository(),
      personalizationStore = PersonalizationLocalStore(root.resolve("personalization").toFile()),
      chatSessionStore = chatStore,
      skillsFacade = EmptySkillsFacade,
      mcpSettingsFacade = EmptyMcpSettingsFacade,
      webSearchSettingsStore = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
      providerUserAgent = "OpenCrayAgentRuntimeServiceBootstrapTest",
      workspaceRootProvider = { root },
      workspaceRootsProvider = { setOf(root) },
      voiceMetadataCacheStore = null,
      soulProfileStore = WorkspaceSoulProfileStore(),
      liveContextModeStore = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore()),
      safetySettingsFacade = EmptySafetySettingsFacade,
      mediaSpeechSettingsStore = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore()),
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(root),
          summary = "workspace=${root.toString().replace('\\', '/')}",
        )
      },
      workspaceSnapshotProvider = { emptyMap() },
      runtimeServiceAccessGateway = DefaultRuntimeServiceAccessGateway(
        defaultRuntimeServiceAccessDependencies(),
      ),
      chatRuntimeWriteTargetResolverFactory = ChatRuntimeWriteTargetResolverFactory {
        object : ChatRuntimeWriteTargetResolver {
          override fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget =
            RuntimeServiceTarget.INTERACTIVE
        }
      },
    )
  }

  private fun testRuntimeAccess(
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    sessionRuntimeManager: AgentSessionRuntimeManager = NoOpAgentSessionRuntimeManager(),
  ): OpenCrayRuntimeOwnerAccess {
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    return OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = sessionRuntimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
  }

  private fun testRuntimeExecutionController(
    serviceHost: OpenCrayRuntimeServiceHost,
  ): RuntimeServiceExecutionController = RuntimeServiceExecutionController(
    runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
    bootstrapAssembly = serviceHost.toRuntimeServiceBootstrapAssembly(),
  )

  private fun recordingRuntimeServiceExecutionControllerHandle(
    root: Path,
  ): RecordingRuntimeServiceExecutionControllerHandle {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile())
    val dependencies = testRuntimeDependencies(root = root, chatStore = chatStore)
    val runtimeAccess = testRuntimeAccess()
    val disposeCallCount = intArrayOf(0)
    return RecordingRuntimeServiceExecutionControllerHandle(
      controller = RuntimeServiceExecutionController(
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
        bootstrapAssembly = RuntimeServiceBootstrapAssembly(
          bootstrapContext = runtimeServiceBootstrapContext(dependencies),
          retainedOwnerState = retainedOwnerStateFor(runtimeAccess),
          projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(),
          transportCoordinator = DefaultRuntimeServiceTransportCoordinator(),
          retainedShellControl = testRuntimeServiceRetainedShellControl(),
          runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
          bootstrapResult = RuntimeServiceBootstrapResult(
            scannedSessionIds = emptyList(),
            resumedSessionIds = emptyList(),
            repairedSessionIds = emptyList(),
          ),
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            workSummaryProvider = { RuntimeOwnerWorkSummary() },
          ),
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory()
            .create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
          disposeHandler = {
            disposeCallCount[0] += 1
          },
        ),
      ),
      disposeCallCountProvider = {
        disposeCallCount[0]
      },
    )
  }

  private fun retainedOwnerStateFor(
    runtimeAccess: OpenCrayRuntimeOwnerAccess,
    disposeHandler: () -> Unit = {},
  ): RuntimeServiceRetainedOwnerState = RuntimeServiceRetainedOwnerState(
    initialBootstrap = RuntimeOwnerBootstrap(
      runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
      ownerObservationAccess = runtimeAccess.hostAccess,
      notificationHostAccess = runtimeAccess.hostAccess,
      approvalDecisionHostAccess = runtimeAccess.hostAccess,
      chatMutationAccess = runtimeAccess.hostAccess,
      chatSubmissionHostAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      disposeHandler = disposeHandler,
    ),
    replacementBootstrapProvider = { _ ->
      error("Owner replacement is not configured for this test.")
    },
  )

  private fun runtimeOwnerBootstrapFor(
    runtimeAccess: OpenCrayRuntimeOwnerAccess,
    retainedHandle: RetainedRuntimeOwnerHandle? = null,
    disposeHandler: () -> Unit = {},
  ): RuntimeOwnerBootstrap = RuntimeOwnerBootstrap(
    runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
    ownerObservationAccess = runtimeAccess.hostAccess,
    notificationHostAccess = runtimeAccess.hostAccess,
    approvalDecisionHostAccess = runtimeAccess.hostAccess,
    chatMutationAccess = runtimeAccess.hostAccess,
    chatSubmissionHostAccess = runtimeAccess.hostAccess,
    runtimeReplayAccess = runtimeAccess.replayAccess,
    retainedHandle = retainedHandle,
    disposeHandler = disposeHandler,
  )

  private class RecordingRuntimeServiceExecutionControllerHandle(
    val controller: RuntimeServiceExecutionController,
    private val disposeCallCountProvider: () -> Int,
  ) {
    val disposeCallCount: Int
      get() = disposeCallCountProvider()
  }

  private fun testRuntimeServiceRetainedShellControl(): RuntimeServiceRetainedShellControl =
    RuntimeServiceRetainedShellControl(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )

  private fun testServiceGatewayBundle(
    notifyChatSnapshotsChanged: () -> Unit = {},
    refreshSandboxSessionInfo: () -> Unit = {},
    interruptChatRun: (String) -> Unit = {},
    refreshSkills: () -> String = { "" },
    saveNotificationSettings: (Map<String, Any?>) -> Map<String, Any?> = { emptyMap() },
    dispose: () -> Unit = {},
  ): OpenCrayRuntimeServiceGatewayBundle =
    OpenCrayRuntimeServiceGatewayBundle(
      shellGateway = object : OpenCrayShellGateway {
        override fun loadShellSnapshot(): Map<String, Any?> = emptyMap()

        override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
      },
      chatRuntimeGateway = object : OpenCrayRuntimeServiceChatGateway {
        override fun loadChatSnapshot(): Map<String, Any?> = emptyMap()

        override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun loadChatRuntimeSnapshot(): Map<String, Any?> = emptyMap()

        override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = null

        override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = null

        override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun refreshSandboxSessionInfo() = refreshSandboxSessionInfo()

        override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

        override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

        override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

        override fun searchMemoryDebug(
          query: String,
          maxResults: Int,
          minScore: Int,
        ): Map<String, Any?> = emptyMap()

        override fun getMemoryDebugSlice(
          path: String,
          fromLine: Int?,
          lines: Int,
        ): Map<String, Any?> = emptyMap()

        override fun applyMemoryDebugAction(
          recordId: String,
          actionId: String,
        ): Map<String, Any?> = emptyMap()

        override fun createChatSession() = Unit

        override fun copyChatSession(sessionId: String) = Unit

        override fun deleteChatSession(sessionId: String) = Unit

        override fun selectChatSession(sessionId: String) = Unit

        override fun branchChatSessionFromMessage(
          sessionId: String,
          messageId: String,
        ) = Unit

        override fun deleteChatMessage(
          sessionId: String,
          messageId: String,
        ) = Unit

        override fun recallChatMessage(
          sessionId: String,
          messageId: String,
        ) = Unit

        override fun submitChatMessage(
          text: String,
          attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
        ): Map<String, Any?>? = null

        override fun approveChatApproval(taskIdOrRunId: String) = Unit

        override fun approveChatApprovalForSession(taskIdOrRunId: String) = Unit

        override fun rejectChatApproval(taskIdOrRunId: String) = Unit

        override fun interruptChatRun(taskIdOrRunId: String) = interruptChatRun(taskIdOrRunId)

        override fun retryChatRun(taskIdOrRunId: String) = Unit

        override fun notifyChatSnapshotsChanged() = notifyChatSnapshotsChanged()
      },
      skillsGateway = object : OpenCraySkillsGateway {
        override fun loadSkillsSnapshot(
          query: String,
          suggestedLimit: Int,
        ): Map<String, Any?> = emptyMap()

        override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun setSkillEnabled(skillId: String, enabled: Boolean) = Unit

        override fun installSuggestedSkill(skillId: String): String = ""

        override fun installSkillSource(
          sourceRef: String,
          selectedSkillName: String,
        ): String = ""

        override fun installSkillSourceBatch(
          sourceRef: String,
          selectedSkillNames: List<String>,
        ): String = ""

        override fun inspectSkillSource(sourceRef: String): Map<String, Any?> = emptyMap()

        override fun deleteInstalledSkill(skillId: String): String = ""

        override fun refreshSkills(): String = refreshSkills()

        override fun checkInstalledSkillUpdates(skillId: String): String = ""

        override fun updateInstalledSkill(skillId: String): String = ""

        override fun loadSkillInstructions(skillId: String): Map<String, Any?> = emptyMap()

        override fun loadSuggestedSkillInstructions(
          sourceRef: String,
          selectedSkillName: String,
        ): Map<String, Any?> = emptyMap()

        override fun activateSkillsInstallSource(sourceId: String): String = ""
      },
      settingsGateway = object : OpenCraySettingsGateway {
        override fun loadSettingsOverview(): Map<String, Any?> = emptyMap()

        override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> = emptyMap()

        override fun loadNotificationSettings(): Map<String, Any?> = emptyMap()

        override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
          saveNotificationSettings(payload)

        override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = emptyMap()

        override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
          emptyMap()

        override fun loadNetworkSearchConfig(): Map<String, Any?> = emptyMap()

        override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
          emptyMap()

        override fun loadMediaSpeechConfig(): Map<String, Any?> = emptyMap()

        override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
          emptyMap()

        override fun loadSandboxSettings(): Map<String, Any?> = emptyMap()

        override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
          emptyMap()

        override fun loadLlmConfig(): Map<String, Any?> = emptyMap()

        override fun saveLlmConfig(
          enabled: Boolean,
          streamingEnabled: Boolean?,
          providerId: String,
          selectedProviderOptionId: String,
          protocol: String,
          providerName: String,
          providerNotes: String,
          baseUrl: String,
          apiKey: String,
          model: String,
          reasoningEffort: String,
          systemPrompt: String,
          openAiPromptCacheKeyStrategy: String?,
          openAiPromptCacheRetention: String?,
          anthropicPromptCachingEnabled: Boolean?,
          anthropicPromptCacheTtl: String?,
        ): Map<String, Any?> = emptyMap()

        override fun saveCustomLlmProvider(
          selectedProviderOptionId: String,
          streamingEnabled: Boolean?,
          protocol: String,
          providerName: String,
          providerNotes: String,
          baseUrl: String,
          apiKey: String,
          model: String,
          reasoningEffort: String,
          systemPrompt: String,
          openAiPromptCacheKeyStrategy: String?,
          openAiPromptCacheRetention: String?,
          anthropicPromptCachingEnabled: Boolean?,
          anthropicPromptCacheTtl: String?,
        ): Map<String, Any?> = emptyMap()

        override fun validateLlmConfig(
          providerId: String,
          protocol: String,
          baseUrl: String,
          apiKey: String,
          model: String,
          reasoningEffort: String,
        ): Map<String, Any?> = emptyMap()

        override fun loadPersonalizationConfig(): Map<String, Any?> = emptyMap()

        override fun savePersonalizationConfig(
          presetId: String,
          customLabel: String,
          customGuidance: String,
        ): Map<String, Any?> = emptyMap()

        override fun setAppLanguage(languageId: String): Map<String, Any?> = emptyMap()

        override fun runPersonalizationReset(scopeId: String): Map<String, Any?> = emptyMap()

        override fun loadMcpSettings(): Map<String, Any?> = emptyMap()

        override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> = emptyMap()

        override fun setMcpServerEnabled(
          serverId: String,
          enabled: Boolean,
        ): Map<String, Any?> = emptyMap()

        override fun loadSafetySettings(): Map<String, Any?> = emptyMap()

        override fun saveSafetySettings(
          automationModeId: String,
          rollbackJournalEnabled: Boolean,
          maxFilesPerBatch: Int,
          maxAgentTurns: Int,
          maxToolCalls: Int,
          undoWindowHours: Int,
          fileChangesPolicyId: String,
          fileDeletesPolicyId: String,
          shellCommandsPolicyId: String,
          externalAccessModeId: String,
          photoLibraryEnabled: Boolean,
          downloadsEnabled: Boolean,
          documentsEnabled: Boolean,
          recordingsEnabled: Boolean,
          workspaceAccessProfileId: String,
          readOnlyOutsideWorkspace: Boolean,
          liveContextModeId: String,
          memoryToolsEnabled: Boolean,
          subAgentContextDefaultModeId: String?,
          subAgentContextProfileOverrides: Map<String, String>,
        ): Map<String, Any?> = emptyMap()
      },
      disposeHandler = dispose,
    )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, com.opencray.persistence.model.MemoryRecord>()

    override fun list(): List<com.opencray.persistence.model.MemoryRecord> = records.values.toList()

    override fun upsert(record: com.opencray.persistence.model.MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private class NoOpAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    var releaseAllSessionsCallCount: Int = 0
      private set

    override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseAllSessions() {
      releaseAllSessionsCallCount += 1
    }

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingAgentSessionHandle>()
    private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
    val observerCount: Int
      get() = listeners.size
    val resumedSessionIds = mutableListOf<String>()

    fun putHandle(handle: RecordingAgentSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle =
      handlesBySession.getOrPut(sessionId) {
        RecordingAgentSessionHandle(
          sessionId = sessionId,
          resumedSessionIds = resumedSessionIds,
        )
      }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingAgentSessionHandle(
    override val sessionId: String,
    private val resumedSessionIds: MutableList<String>,
    private val runs: List<AgentRunSnapshot> = emptyList(),
    private val queueSnapshot: SessionQueueSnapshot? = null,
    private val hasPendingWorkResult: Boolean = false,
    private val cancelRequestResult: Boolean = false,
    private val resumeRequestResult: Boolean = false,
  ) : AgentSessionHandle {
    val submittedTasks = mutableListOf<AgentTask>()
    val cancelledTaskIds = mutableListOf<String>()
    val resumedTaskIds = mutableListOf<String>()
    var ensureProcessingCallCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submittedTasks += task
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = "submitted-run-${submittedTasks.size}",
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
    }

    override fun ensureProcessing() {
      ensureProcessingCallCount += 1
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return cancelRequestResult
    }

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean {
      resumedTaskIds += taskId
      return resumeRequestResult
    }

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? =
      runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumedSessionIds += sessionId
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = queueSnapshot ?: SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = hasPendingWorkResult
  }

  private fun assertSameGatewayRuntimeAccess(
    expected: OpenCrayRuntimeHostAccess,
    actual: RuntimeServiceGatewayBundleDependencies,
  ) {
    assertSame(expected, actual.runtimeServicePort.ownerObservationAccess)
    assertSame(expected, actual.runtimeServicePort.chatMutationAccess)
    assertSame(expected, actual.runtimeServicePort.chatSubmissionHostAccess)
  }

  private fun defaultShellControlBundle(): RuntimeServiceShellControlBundle =
    RuntimeServiceShellControlBundle(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )

  private class RecordingRuntimeServiceExecutionCoordinator : RuntimeServiceExecutionCoordinator {
    var attachCallCount: Int = 0
      private set
    val startIds = mutableListOf<Int>()
    var persistCallCount: Int = 0
      private set
    val scheduledDispatchOutcomes = mutableListOf<ScheduledTaskDispatchOutcome>()
    var disposeCallCount: Int = 0
      private set

    override fun attach() {
      attachCallCount += 1
    }

    override fun onStartCommand(startId: Int) {
      startIds += startId
    }

    override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState()

    override fun currentForegroundState(): RuntimeForegroundState = RuntimeForegroundState()

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) {
      persistCallCount += 1
    }

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
      scheduledDispatchOutcomes += outcome
    }

    override fun dispose() {
      disposeCallCount += 1
    }
  }

  private class FixedStateRuntimeServiceExecutionCoordinator(
    private val keepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(),
    private val foregroundState: RuntimeForegroundState = RuntimeForegroundState(),
  ) : RuntimeServiceExecutionCoordinator {
    override fun attach() = Unit

    override fun onStartCommand(startId: Int) = Unit

    override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = keepAliveState

    override fun currentForegroundState(): RuntimeForegroundState = foregroundState

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) = Unit

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

    override fun dispose() = Unit
  }

  private class RecordingRuntimeServiceShellStateAccess(
    private val keepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(),
    private val foregroundState: RuntimeForegroundState = RuntimeForegroundState(),
  ) : RuntimeServiceShellStateAccess {
    override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = keepAliveState

    override fun currentForegroundState(): RuntimeForegroundState = foregroundState
  }

  private class NoOpLocalHostGateway : OpenCrayLocalHostGateway {
    override fun loadFilesSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> = emptyMap()

    override fun openWorkspaceEntry(relativePath: String) = Unit

    override fun openExternalUri(uri: String) = Unit

    override fun copyRichTextToClipboard(plainText: String, htmlText: String?) = Unit

    override fun createWorkspaceFolder(parentRelativePath: String, name: String): Map<String, Any?> =
      emptyMap()

    override fun createWorkspaceTextFile(parentRelativePath: String, name: String): Map<String, Any?> =
      emptyMap()

    override fun renameWorkspaceEntry(targetRelativePath: String, newName: String): Map<String, Any?> =
      emptyMap()

    override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> = emptyMap()

    override fun saveWorkspaceTextDocument(
      targetRelativePath: String,
      content: String,
    ): Map<String, Any?> = emptyMap()

    override fun pasteWorkspaceEntries(
      sourceRelativePaths: List<String>,
      destinationRelativePath: String,
      move: Boolean,
    ): Map<String, Any?> = emptyMap()

    override fun shareWorkspaceEntries(relativePaths: List<String>) = Unit

    override fun showNativeToast(message: String) = Unit

    override fun importDraftChatAttachments(
      requestedKind: String,
      uriStrings: List<String>,
    ): List<Map<String, Any?>> = emptyList()

    override fun probeTwinImportSource(filePath: String): Map<String, Any?> = emptyMap()
  }

  private class RecordingRuntimeServiceProjectionCoordinator : RuntimeServiceProjectionCoordinator {
    var bindCallCount: Int = 0
      private set
    var startCallCount: Int = 0
      private set
    var persistCallCount: Int = 0
      private set
    val scheduledDispatchOutcomes = mutableListOf<ScheduledTaskDispatchOutcome>()

    override fun bindServiceLifecycle(serviceLifecycle: RuntimeServiceLifecycleDescriptor) {
      bindCallCount += 1
    }

    override fun start() {
      startCallCount += 1
    }

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) {
      persistCallCount += 1
    }

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
      scheduledDispatchOutcomes += outcome
    }
  }

  private class RecordingRuntimeServiceWakeCommandDispatcher : RuntimeServiceWakeCommandDispatcher {
    var dispatchCallCount: Int = 0
      private set

    override fun dispatch(intent: Intent?) {
      dispatchCallCount += 1
    }
  }

  private class RecordingRuntimeServiceBinderEndpoint(
    private val dispatchChatWriteCommandHandler: ((OpenCrayChatWriteCommand) -> OpenCrayChatWriteDispatchResult)? = null,
  ) : Binder(), RuntimeServiceBinderEndpoint {
    val dispatchedChatWriteCommands = mutableListOf<OpenCrayChatWriteCommand>()

    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = error("unused in test")

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult? {
      dispatchedChatWriteCommands += command
      return dispatchChatWriteCommandHandler?.invoke(command)
    }
  }

  private class TestRuntimeService : Service() {
    override fun onBind(intent: Intent?) = null
  }
}
