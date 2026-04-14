package com.opencray.app

import android.app.Service
import android.content.Context
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
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  @Before
  fun setUp() {
    clearRuntimeSingletons()
    OpenCrayRuntimeServiceAccess.clearForTest()
    RuntimeServiceBootstrapRegistry.clearForTest()
    RuntimeOwnerAccessRegistry.clearForTest()
    OpenCrayRuntimeServiceAccess.setRuntimeServiceStarterForTest(recordingStarter)
  }

  @After
  fun tearDown() {
    OpenCrayRuntimeServiceAccess.clearForTest()
    RuntimeServiceBootstrapRegistry.clearForTest()
    RuntimeOwnerAccessRegistry.clearForTest()
    clearRuntimeSingletons()
  }

  @Test
  fun ensureStartedOnlyRequestsServiceStartWithoutCreatingRuntimeHost() {
    val context = MinimalContext()

    OpenCrayRuntimeServiceAccess.ensureStarted(context)

    val startedRequest = recordingStarter.startedRequests.single()
    assertEquals(null, startedRequest.request.action)
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
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
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
          override fun baseIntent(context: Context): Intent = expectedBaseIntent

          override fun startRequestIntent(
            context: Context,
            request: RuntimeServiceStartRequest,
          ): Intent = error("Start request intent should not be used in this test.")

          override fun scheduledTaskIntent(
            context: Context,
            command: ScheduledTaskWakeCommand,
          ): Intent = expectedScheduledIntent

          override fun scheduledRepairIntent(
            context: Context,
            repairReason: String,
          ): Intent = error("Repair intent should not be used in this test.")

          override fun resumeInterruptedRunsIntent(
            context: Context,
            repairReason: String,
          ): Intent = error("Resume intent should not be used in this test.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
          ): android.app.PendingIntent = error("Approval pending intent should not be used in this test.")
        },
      ),
    )

    val baseIntent = OpenCrayRuntimeServiceAccess.baseIntent(context)
    val scheduledIntent = scheduledTaskServiceIntent(
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
  fun defaultRuntimeOwnerAccessFactoryUsesInjectedOwnerProviderWithoutTouchingRegistry() {
    val root = temporaryFolder.newFolder("runtime-owner-access").toPath()
    val dependencies = testRuntimeDependencies(
      root = root,
      chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile()),
    )
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    var providerCallCount = 0
    RuntimeOwnerAccessRegistry.setInProcessRuntimeOwnerProviderForTest(
      object : InProcessRuntimeOwnerProvider {
        override fun getOrCreate(
          dependencies: OpenCrayRuntimeContextDependencies,
        ): InProcessOpenCrayRuntimeOwner {
          providerCallCount += 1
          return InProcessOpenCrayRuntimeOwner(
            lifecycleDescriptor = lifecycleDescriptor,
            sessionRuntimeManager = NoOpAgentSessionRuntimeManager(),
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
          )
        }
      },
    )

    val access = DefaultOpenCrayRuntimeOwnerAccessFactory.create(dependencies)

    assertEquals(1, providerCallCount)
    assertSame(lifecycleDescriptor, access.lifecycleDescriptor)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
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
          override fun baseIntent(context: Context): Intent = expectedBaseIntent

          override fun startRequestIntent(
            context: Context,
            request: RuntimeServiceStartRequest,
          ): Intent = error("Start request intent should not be used in this test.")

          override fun scheduledTaskIntent(
            context: Context,
            command: ScheduledTaskWakeCommand,
          ): Intent = error("Scheduled task intent should not be used in this test.")

          override fun scheduledRepairIntent(
            context: Context,
            repairReason: String,
          ): Intent = error("Repair intent should not be used in this test.")

          override fun resumeInterruptedRunsIntent(
            context: Context,
            repairReason: String,
          ): Intent = error("Resume intent should not be used in this test.")

          override fun approvalActionPendingIntent(
            context: Context,
            action: String,
            sessionId: String,
            taskId: String,
            runId: String,
            requestCode: Int,
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
    assertEquals(null, startedRequest.request.action)
    assertFalse(startedRequest.foreground)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
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
    assertEquals(ACTION_RUN_SCHEDULED_TASK, startedRequest.request.action)
    assertEquals("schedule-alpha", startedRequest.request.extras[EXTRA_SCHEDULE_ID])
    assertEquals("schedule-run-alpha", startedRequest.request.extras[EXTRA_SCHEDULE_RUN_ID])
    assertEquals(1234L, startedRequest.request.extras[EXTRA_TRIGGERED_AT_EPOCH_MS])
    assertEquals(
      ScheduledTaskTriggerReasons.WORK_MANAGER,
      startedRequest.request.extras[EXTRA_TRIGGER_REASON],
    )
    assertEquals("session-alpha", startedRequest.request.extras[EXTRA_TARGET_SESSION_ID])
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
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
    assertEquals(ACTION_REPAIR_SCHEDULES, startedRequest.request.action)
    assertEquals(
      ScheduledTaskRepairReasons.WORK_MANAGER,
      startedRequest.request.extras[EXTRA_REPAIR_REASON],
    )
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
    assertEquals(ACTION_RESUME_INTERRUPTED_RUNS, startedRequest.request.action)
    assertEquals(
      ScheduledTaskRepairReasons.WORK_MANAGER,
      startedRequest.request.extras[EXTRA_REPAIR_REASON],
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
  fun resolveRuntimeServiceBootstrapStateUsesInjectedStateProviderWithoutTouchingDefaultRegistry() {
    val context = MinimalContext()
    val expectedHost = testServiceHost(temporaryFolder.newFolder("service-host-provider"))
    val expectedBootstrapState = expectedHost.toRuntimeServiceBootstrapState()
    var providerCallCount = 0
    var capturedContext: Context? = null
    var capturedLifecycle: RuntimeServiceLifecycleDescriptor? = null
    RuntimeServiceBootstrapRegistry.setRuntimeServiceBootstrapStateProviderForTest(
      RuntimeServiceBootstrapStateProvider { resolvedContext, serviceLifecycleFactory ->
        providerCallCount += 1
        capturedContext = resolvedContext
        capturedLifecycle = serviceLifecycleFactory()
        expectedBootstrapState
      },
    )

    val resolved = RuntimeServiceBootstrapRegistry.resolveRuntimeServiceBootstrapState(context) {
      RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-test",
        serviceCreatedAtEpochMs = 1_234L,
      )
    }

    assertSame(
      expectedHost.runtimeAccess.lifecycleDescriptor,
      resolved.gatewayDependencies.runtimeOwnerLifecycle,
    )
    assertSame(expectedHost.runtimeAccess.hostAccess, resolved.gatewayDependencies.runtimeHostAccess)
    assertSame(
      expectedHost.serviceLifecycle,
      resolved.executionCoordinatorDependencies.serviceLifecycle,
    )
    assertSame(
      expectedHost.runtimeAccess,
      resolved.binderEndpointDependencies.bridgeSnapshotDependencies.runtimeAccess,
    )
    assertSame(context, capturedContext)
    assertEquals(1, providerCallCount)
    assertEquals("runtime-service-test", capturedLifecycle?.serviceInstanceId)
    assertEquals(1_234L, capturedLifecycle?.serviceCreatedAtEpochMs)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
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
      bootstrapFactory = RuntimeServiceBootstrapFactory { appContext, _ ->
        factoryCallCount += 1
        capturedContext = appContext
        RuntimeServiceBootstrapParts(
          dependencies = dependencies,
          runtimeAccess = runtimeAccess,
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
    assertSame(runtimeAccess.hostAccess, resolved.gatewayDependencies.runtimeHostAccess)
    assertSame(lifecycle, resolved.executionCoordinatorDependencies.serviceLifecycle)
    assertSame(
      runtimeAccess,
      resolved.binderEndpointDependencies.bridgeSnapshotDependencies.runtimeAccess,
    )
    assertEquals(listOf(sessionId), runtimeManager.resumedSessionIds)
    assertSame(context, capturedContext)
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
    var keepAliveListenerRegistered = false
    val expectedKeepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 2_468L,
    )
    RuntimeServiceBootstrapRegistry.setRuntimeServiceTransportBootstrapFactoryForTest(
      OpenCrayRuntimeServiceTransportBootstrapFactory {
          appContext,
          gatewayDependencies,
          runtimeServiceGatewayBundleFactory,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
        ->
        factoryCallCount += 1
        capturedContext = appContext
        assertSame(serviceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSame(serviceHost.runtimeAccess.hostAccess, gatewayDependencies.runtimeHostAccess)
        assertSame(
          DefaultRuntimeServiceGatewayBundleFactory,
          runtimeServiceGatewayBundleFactory,
        )
        capturedKeepAliveState = runtimeServiceKeepAliveStateProvider()
        runtimeServiceKeepAliveChangeRegistrar.register { keepAliveListenerRegistered = true }
        expectedBootstrap
      },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = RuntimeServiceBootstrapRegistry.resolveRuntimeServiceTransportBootstrap(
      context = context,
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
    val expectedGatewayBundle = testServiceGatewayBundle()
    val expectedKeepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 3_579L,
    )
    var capturedGatewayContext: Context? = null
    var capturedKeepAliveState: RuntimeServiceKeepAliveState? = null
    var keepAliveListenerRegistered = false
    var capturedLoopbackContext: Context? = null
    var capturedLoopbackGatewayBundle: OpenCrayRuntimeServiceGatewayBundle? = null
    var loopbackStartCallCount = 0
    val factory = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory { appContext, gatewayBundle ->
        capturedLoopbackContext = appContext
        capturedLoopbackGatewayBundle = gatewayBundle
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = { loopbackStartCallCount += 1 },
        )
      },
    )

    val resolved = factory.create(
      appContext = context,
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory {
          appContext,
          gatewayDependencies,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
        ->
        capturedGatewayContext = appContext
        assertSame(serviceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSame(serviceHost.runtimeAccess.hostAccess, gatewayDependencies.runtimeHostAccess)
        capturedKeepAliveState = runtimeServiceKeepAliveStateProvider()
        runtimeServiceKeepAliveChangeRegistrar.register { keepAliveListenerRegistered = true }
        expectedGatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { expectedKeepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        listener()
        ({ })
      },
    )

    resolved.ensureStarted()

    assertSame(expectedGatewayBundle, resolved.gatewayBundle)
    assertSame(context, capturedGatewayContext)
    assertSame(expectedKeepAliveState, capturedKeepAliveState)
    assertTrue(keepAliveListenerRegistered)
    assertSame(context, capturedLoopbackContext)
    assertSame(expectedGatewayBundle, capturedLoopbackGatewayBundle)
    assertEquals(1, loopbackStartCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultLoopbackBootstrapFactoryUsesInjectedServerStarterAndGatewayProviders() {
    val context = MinimalContext()
    val gatewayBundle = testServiceGatewayBundle()
    var localGatewayProviderFactoryCallCount = 0
    var localGatewayProviderResolveCount = 0
    var capturedContext: Context? = null
    var capturedProviders: OpenCrayLocalRuntimeServerProviders? = null
    var ensureServerStartedCallCount = 0
    val factory = DefaultRuntimeServiceLoopbackBootstrapFactory(
      localGatewayProviderFactory = { resolvedContext ->
        localGatewayProviderFactoryCallCount += 1
        assertSame(context, resolvedContext)
        val provider: () -> OpenCrayLocalHostGateway = {
          localGatewayProviderResolveCount += 1
          error("unused in test")
        }
        provider
      },
      ensureServerStarted = { resolvedContext, providers ->
        ensureServerStartedCallCount += 1
        capturedContext = resolvedContext
        capturedProviders = providers
      },
    )

    val bootstrap = factory.create(
      appContext = context,
      gatewayBundle = gatewayBundle,
    )
    bootstrap.ensureStarted()

    assertEquals(1, localGatewayProviderFactoryCallCount)
    assertEquals(1, ensureServerStartedCallCount)
    assertSame(context, capturedContext)
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
    assertEquals(0, localGatewayProviderResolveCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceExecutionCoordinatorUsesInjectedFactoryWithoutTouchingDefaultExecutionBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-execution-coordinator"))
    val keepAliveController = RuntimeServiceKeepAliveController(
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
    )
    val expectedCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    var factoryCallCount = 0
    var capturedContext: Context? = null
    var capturedRuntimeHostAccess: OpenCrayRuntimeHostAccess? = null
    var capturedRuntimeOwnerLifecycle: HostRuntimeLifecycleDescriptor? = null
    var capturedKeepAliveController: RuntimeServiceKeepAliveController? = null
    var capturedForegroundController: RuntimeForegroundController? = null
    RuntimeServiceBootstrapRegistry.setRuntimeServiceExecutionCoordinatorFactoryForTest(
      RuntimeServiceExecutionCoordinatorFactory {
          appContext,
          coordinatorDependencies,
          resolvedKeepAliveController,
          resolvedRuntimeForegroundController,
        ->
        factoryCallCount += 1
        capturedContext = appContext
        capturedRuntimeHostAccess = coordinatorDependencies.runtimeHostAccess
        capturedRuntimeOwnerLifecycle = coordinatorDependencies.runtimeOwnerLifecycle
        capturedKeepAliveController = resolvedKeepAliveController
        capturedForegroundController = resolvedRuntimeForegroundController
        expectedCoordinator
      },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = RuntimeServiceBootstrapRegistry.resolveRuntimeServiceExecutionCoordinator(
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
    assertSame(serviceHost.runtimeAccess.hostAccess, capturedRuntimeHostAccess)
    assertSame(serviceHost.runtimeAccess.lifecycleDescriptor, capturedRuntimeOwnerLifecycle)
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
  fun resolveRuntimeServiceControllerBundleUsesInjectedFactoryWithoutTouchingDefaultControllerBootstrap() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val expectedBundle = RuntimeServiceControllerBundle(
      keepAliveController = RuntimeServiceKeepAliveController(
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
      ),
    )
    var factoryCallCount = 0
    var capturedService: android.app.Service? = null
    var capturedContext: Context? = null
    var capturedHandler: Handler? = null
    RuntimeServiceBootstrapRegistry.setRuntimeServiceControllerBundleFactoryForTest(
      RuntimeServiceControllerBundleFactory { resolvedService, appContext, resolvedMainHandler ->
        factoryCallCount += 1
        capturedService = resolvedService
        capturedContext = appContext
        capturedHandler = resolvedMainHandler
        expectedBundle
      },
    )

    val resolved = RuntimeServiceBootstrapRegistry.resolveRuntimeServiceControllerBundle(
      service = service,
      context = context,
      mainHandler = mainHandler,
    )

    assertSame(expectedBundle, resolved)
    assertSame(service, capturedService)
    assertSame(context, capturedContext)
    assertSame(mainHandler, capturedHandler)
    assertEquals(1, factoryCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceWakeCommandDispatcherUsesInjectedFactoryWithoutTouchingDefaultDispatcherBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-wake-command-dispatcher"))
    val gatewayBundle = testServiceGatewayBundle()
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val expectedDispatcher = RecordingRuntimeServiceWakeCommandDispatcher()
    var factoryCallCount = 0
    var capturedContext: Context? = null
    var capturedScheduledTaskDispatcherDependencies: ScheduledTaskDispatcherDependencies? = null
    var capturedScheduledTaskRepairDependencies: ScheduledTaskRepairDependencies? = null
    var capturedResumeInterruptedRuns: (() -> Unit)? = null
    var capturedGatewayBundle: OpenCrayRuntimeServiceGatewayBundle? = null
    var capturedExecutionCoordinator: RuntimeServiceExecutionCoordinator? = null
    RuntimeServiceBootstrapRegistry.setRuntimeServiceWakeCommandDispatcherFactoryForTest(
      RuntimeServiceWakeCommandDispatcherFactory {
          appContext,
          dispatcherDependencies,
          resolvedGatewayBundle,
          resolvedExecutionCoordinator,
        ->
        factoryCallCount += 1
        capturedContext = appContext
        capturedScheduledTaskDispatcherDependencies =
          dispatcherDependencies.scheduledTaskDispatcherDependencies
        capturedScheduledTaskRepairDependencies =
          dispatcherDependencies.scheduledTaskRepairDependencies
        capturedResumeInterruptedRuns = dispatcherDependencies.resumeInterruptedRuns
        capturedGatewayBundle = resolvedGatewayBundle
        capturedExecutionCoordinator = resolvedExecutionCoordinator
        expectedDispatcher
      },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = RuntimeServiceBootstrapRegistry.resolveRuntimeServiceWakeCommandDispatcher(
      context = context,
      bootstrapState = bootstrapState,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
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
    assertSame(serviceExecutionCoordinator, capturedExecutionCoordinator)
    assertEquals(1, factoryCallCount)
    assertEquals(1, expectedDispatcher.dispatchCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun resolveRuntimeServiceBinderEndpointUsesInjectedFactoryWithoutTouchingDefaultBinderBootstrap() {
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-binder-endpoint"))
    val gatewayBundle = testServiceGatewayBundle()
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val expectedEndpoint = RecordingRuntimeServiceBinderEndpoint()
    var factoryCallCount = 0
    var capturedBridgeSnapshotDependencies: RuntimeServiceBridgeSnapshotDependencies? =
      null
    var capturedGatewayBundle: OpenCrayRuntimeServiceGatewayBundle? = null
    var capturedExecutionCoordinator: RuntimeServiceExecutionCoordinator? = null
    RuntimeServiceBootstrapRegistry.setRuntimeServiceBinderEndpointFactoryForTest(
      RuntimeServiceBinderEndpointFactory {
          binderEndpointDependencies,
          resolvedGatewayBundle,
          resolvedExecutionCoordinator,
        ->
        factoryCallCount += 1
        capturedBridgeSnapshotDependencies = binderEndpointDependencies.bridgeSnapshotDependencies
        capturedGatewayBundle = resolvedGatewayBundle
        capturedExecutionCoordinator = resolvedExecutionCoordinator
        expectedEndpoint
      },
    )
    val bootstrapState = serviceHost.toRuntimeServiceBootstrapState()

    val resolved = RuntimeServiceBootstrapRegistry.resolveRuntimeServiceBinderEndpoint(
      bootstrapState = bootstrapState,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
    )

    assertSame(expectedEndpoint, resolved)
    assertSame(serviceHost.dependencies, capturedBridgeSnapshotDependencies?.dependencies)
    assertSame(serviceHost.runtimeAccess, capturedBridgeSnapshotDependencies?.runtimeAccess)
    assertSame(serviceHost.serviceLifecycle, capturedBridgeSnapshotDependencies?.serviceLifecycle)
    assertSame(gatewayBundle, capturedGatewayBundle)
    assertSame(serviceExecutionCoordinator, capturedExecutionCoordinator)
    assertEquals(1, factoryCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun openCrayAgentRuntimeServiceBootstrapAssemblesInjectedDependenciesIntoSingleBundle() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val expectedControllerBundle = RuntimeServiceControllerBundle(
      keepAliveController = RuntimeServiceKeepAliveController(
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
      ),
    )
    val expectedServiceHost = testServiceHost(temporaryFolder.newFolder("service-bootstrap-bundle"))
    val expectedGatewayBundle = testServiceGatewayBundle()
    val expectedTransportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = expectedGatewayBundle,
    )
    val expectedExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val expectedWakeDispatcher = RecordingRuntimeServiceWakeCommandDispatcher()
    val expectedBinderEndpoint = RecordingRuntimeServiceBinderEndpoint()
    var capturedContext: Context? = null
    var capturedService: Service? = null
    var capturedHandler: Handler? = null
    var capturedLifecycle: RuntimeServiceLifecycleDescriptor? = null
    var capturedKeepAliveController: RuntimeServiceKeepAliveController? = null
    var capturedForegroundController: RuntimeForegroundController? = null
    var capturedKeepAliveState: RuntimeServiceKeepAliveState? = null
    var keepAliveListenerRegistered = false
    val dependencies = RuntimeServiceBootstrapDependencies(
      runtimeServiceBootstrapStateProvider = RuntimeServiceBootstrapStateProvider {
          resolvedContext,
          lifecycleFactory,
        ->
        capturedContext = resolvedContext
        capturedLifecycle = lifecycleFactory()
        expectedServiceHost.toRuntimeServiceBootstrapState()
      },
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory {
          appContext,
          gatewayDependencies,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
        ->
        capturedContext = appContext
        assertSame(expectedServiceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSame(expectedServiceHost.runtimeAccess.hostAccess, gatewayDependencies.runtimeHostAccess)
        capturedKeepAliveState = runtimeServiceKeepAliveStateProvider()
        runtimeServiceKeepAliveChangeRegistrar.register { }
        keepAliveListenerRegistered = true
        expectedGatewayBundle
      },
      runtimeServiceTransportBootstrapFactory = OpenCrayRuntimeServiceTransportBootstrapFactory {
          appContext,
          gatewayDependencies,
          runtimeServiceGatewayBundleFactory,
          runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar,
        ->
        capturedContext = appContext
        assertSame(expectedServiceHost.runtimeAccess.lifecycleDescriptor, gatewayDependencies.runtimeOwnerLifecycle)
        assertSame(expectedServiceHost.runtimeAccess.hostAccess, gatewayDependencies.runtimeHostAccess)
        val resolvedGatewayBundle = runtimeServiceGatewayBundleFactory.create(
          appContext = appContext,
          gatewayDependencies = gatewayDependencies,
          runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
          runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        )
        assertSame(expectedGatewayBundle, resolvedGatewayBundle)
        expectedTransportBootstrap
      },
      runtimeServiceExecutionCoordinatorFactory = RuntimeServiceExecutionCoordinatorFactory {
          appContext,
          coordinatorDependencies,
          keepAliveController,
          runtimeForegroundController,
        ->
        capturedContext = appContext
        assertSame(expectedServiceHost.runtimeAccess.hostAccess, coordinatorDependencies.runtimeHostAccess)
        assertSame(
          expectedServiceHost.runtimeAccess.lifecycleDescriptor,
          coordinatorDependencies.runtimeOwnerLifecycle,
        )
        capturedKeepAliveController = keepAliveController
        capturedForegroundController = runtimeForegroundController
        expectedExecutionCoordinator
      },
      runtimeServiceControllerBundleFactory = RuntimeServiceControllerBundleFactory {
          resolvedService,
          appContext,
          resolvedMainHandler,
        ->
        capturedService = resolvedService
        capturedContext = appContext
        capturedHandler = resolvedMainHandler
        expectedControllerBundle
      },
      runtimeServiceWakeCommandDispatcherFactory = RuntimeServiceWakeCommandDispatcherFactory {
          appContext,
          _,
          gatewayBundle,
          serviceExecutionCoordinator,
        ->
        capturedContext = appContext
        assertSame(expectedGatewayBundle, gatewayBundle)
        assertSame(expectedExecutionCoordinator, serviceExecutionCoordinator)
        expectedWakeDispatcher
      },
      runtimeServiceBinderEndpointFactory = RuntimeServiceBinderEndpointFactory {
          _,
          gatewayBundle,
          serviceExecutionCoordinator,
        ->
        assertSame(expectedGatewayBundle, gatewayBundle)
        assertSame(expectedExecutionCoordinator, serviceExecutionCoordinator)
        expectedBinderEndpoint
      },
    )

    val resolved = openCrayAgentRuntimeServiceBootstrap(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = dependencies,
    )

    assertSame(expectedControllerBundle, resolved.controllerBundle)
    assertSame(expectedTransportBootstrap, resolved.transportBootstrap)
    assertSame(expectedExecutionCoordinator, resolved.executionCoordinator)
    assertSame(expectedWakeDispatcher, resolved.wakeCommandDispatcher)
    assertSame(expectedBinderEndpoint, resolved.binderEndpoint)
    assertSame(service, capturedService)
    assertSame(context, capturedContext)
    assertSame(mainHandler, capturedHandler)
    assertSame(expectedControllerBundle.keepAliveController, capturedKeepAliveController)
    assertSame(
      expectedControllerBundle.runtimeForegroundController,
      capturedForegroundController,
    )
    assertEquals(
      expectedControllerBundle.keepAliveController.currentState().phase,
      capturedKeepAliveState?.phase,
    )
    assertTrue(keepAliveListenerRegistered)
    assertTrue(capturedLifecycle?.serviceInstanceId?.isNotBlank() == true)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherHandlesApprovalWakeWithoutTouchingDefaultRegistry() {
    val context = MinimalContext()
    val fixture = pendingApprovalWakeDispatcherFixture(
      temporaryFolder.newFolder("wake-dispatcher-approval"),
    )
    var chatSnapshotNotificationCount = 0
    val gatewayBundle = testServiceGatewayBundle(
      notifyChatSnapshotsChanged = { chatSnapshotNotificationCount += 1 },
    )
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = {
        RuntimeServiceNotificationCommand.ApproveApproval(
          sessionId = fixture.sessionId,
          taskId = fixture.taskId,
          runId = fixture.runId,
        )
      },
      approvalNotificationDismisser = { _, taskId -> dismissedTaskIds += taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertTrue(serviceExecutionCoordinator.scheduledDispatchOutcomes.isEmpty())
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = {
        RuntimeServiceNotificationCommand.RejectApproval(
          sessionId = fixture.sessionId,
          taskId = fixture.taskId,
          runId = fixture.runId,
        )
      },
      approvalNotificationDismisser = { _, taskId -> dismissedTaskIds += taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), dismissedTaskIds)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertTrue(serviceExecutionCoordinator.scheduledDispatchOutcomes.isEmpty())
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dismissedTaskIds = mutableListOf<String?>()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = {
        RuntimeServiceNotificationCommand.ApproveApproval(
          sessionId = fixture.sessionId,
          taskId = "notification-task-id",
          runId = fixture.runId,
        )
      },
      approvalNotificationDismisser = { _, taskId -> dismissedTaskIds += taskId },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf("notification-task-id"), dismissedTaskIds)
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun defaultWakeDispatcherForwardsScheduledWakeOutcomeAndPersistsProjection() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("wake-dispatcher-scheduled"))
    val gatewayBundle = testServiceGatewayBundle()
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      scheduledTaskWakeCommandParser = {
        ScheduledTaskWakeCommand(
          scheduleId = "missing-schedule",
          scheduleRunId = "schedule-run-alpha",
          triggeredAtEpochMs = 1_234L,
          triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
        )
      },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertEquals(1, serviceExecutionCoordinator.scheduledDispatchOutcomes.size)
    val outcome = serviceExecutionCoordinator.scheduledDispatchOutcomes.single()
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_RESUME_INTERRUPTED_RUNS },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(listOf(fixture.sessionId), fixture.resumedSessionIds)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertTrue(serviceExecutionCoordinator.scheduledDispatchOutcomes.isEmpty())
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { ACTION_REPAIR_SCHEDULES },
      repairReasonReader = { "   " },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    val runRecord = fixture.serviceHost.scheduledTaskRunRecordStore.list().single()
    assertEquals(fixture.scheduleId, runRecord.scheduleId)
    assertEquals(ScheduledTaskTriggerReasons.REPAIR, runRecord.triggerReason)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertTrue(serviceExecutionCoordinator.scheduledDispatchOutcomes.isEmpty())
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = { null },
      actionReader = { "com.opencray.app.action.UNKNOWN" },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(0, serviceExecutionCoordinator.persistCallCount)
    assertTrue(serviceExecutionCoordinator.scheduledDispatchOutcomes.isEmpty())
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val dispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
      appContext = context,
      dispatcherDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().wakeCommandDispatcherDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
      notificationCommandParser = { null },
      scheduledTaskWakeCommandParser = {
        parseScheduledTaskWakeCommand(
          action = ACTION_RUN_SCHEDULED_TASK,
          scheduleId = "   ",
          scheduleRunId = "schedule-run-alpha",
          triggeredAtEpochMs = 1_234L,
          triggerReason = ScheduledTaskTriggerReasons.WORK_MANAGER,
          targetSessionId = null,
        )
      },
      actionReader = { ACTION_RUN_SCHEDULED_TASK },
      approvalNotificationDismisser = { _, _ -> },
    )

    dispatcher.dispatch(null)

    assertEquals(0, serviceExecutionCoordinator.persistCallCount)
    assertTrue(serviceExecutionCoordinator.scheduledDispatchOutcomes.isEmpty())
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = fixture.serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.ApproveChatApproval(fixture.taskId),
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(1, chatSnapshotNotificationCount)
    assertEquals(listOf(fixture.taskId), fixture.handle.resumedTaskIds)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
    )

    val dispatch = endpoint.dispatchChatWriteCommand(
      OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )

    assertEquals(OpenCrayChatWriteDispatchResult.Completed, dispatch)
    assertEquals(1, refreshSandboxSessionInfoCallCount)
    assertEquals(1, serviceExecutionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
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
    val serviceExecutionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val endpoint = DefaultRuntimeServiceBinderEndpoint(
      binderEndpointDependencies = serviceHost.toRuntimeServiceBootstrapState().binderEndpointDependencies,
      gatewayBundle = gatewayBundle,
      serviceExecutionCoordinator = serviceExecutionCoordinator,
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
    assertEquals(0, serviceExecutionCoordinator.persistCallCount)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  private fun clearRuntimeSingletons() {
    OpenCrayRuntimeServiceHostRegistry.clearForTest()
    InProcessOpenCrayRuntimeOwnerRegistry.clearForTest()
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
        trigger = ScheduledTrigger.At(atEpochMs = nowEpochMs - 500L),
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

  private class RecordingRuntimeServiceStarter : RuntimeServiceStarter {
    var throwOnStart: Boolean = false
    val startAttempts = mutableListOf<RecordedStart>()
    val startedRequests = mutableListOf<RecordedStart>()

    override fun start(
      context: Context,
      request: RuntimeServiceStartRequest,
      endpoint: RuntimeServiceEndpoint,
      foreground: Boolean,
    ): Boolean {
      val attempt = RecordedStart(
        contextPackageName = context.packageName,
        request = request,
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
    val request: RuntimeServiceStartRequest,
    val foreground: Boolean,
  )

  private class RecordingRuntimeServiceClientProvider(
    private val client: OpenCrayRuntimeServiceClient,
  ) : RuntimeServiceClientProvider {
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
      return client
    }
  }

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
  ): OpenCrayRuntimeContextDependencies = OpenCrayRuntimeContextDependencies(
    appContext = MinimalContext(),
    localizedContext = MinimalContext(),
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
  )

  private fun testRuntimeAccess(): OpenCrayRuntimeOwnerAccess {
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeManager = NoOpAgentSessionRuntimeManager()
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
  }

  private fun testServiceGatewayBundle(
    notifyChatSnapshotsChanged: () -> Unit = {},
    refreshSandboxSessionInfo: () -> Unit = {},
    refreshSkills: () -> String = { "" },
    saveNotificationSettings: (Map<String, Any?>) -> Map<String, Any?> = { emptyMap() },
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

        override fun interruptChatRun(taskIdOrRunId: String) = Unit

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
          providerMode: String,
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
          contextBudgetPreset: String?,
          contextBudgetReservedOutputTokens: Int?,
          contextBudgetSafetyMarginTokens: Int?,
          contextBudgetEffectiveInputPercent: Double?,
          selectedOnDeviceModelId: String,
          onDeviceMaxContextWindow: Int,
          onDeviceMaxTokens: Int,
          onDeviceTopK: Int,
          onDeviceTopP: Double,
          onDeviceTemperature: Double,
          onDeviceAccelerator: String,
          onDeviceThinkingEnabled: Boolean,
          onDeviceLiteModeEnabled: Boolean,
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
          contextBudgetPreset: String?,
          contextBudgetReservedOutputTokens: Int?,
          contextBudgetSafetyMarginTokens: Int?,
          contextBudgetEffectiveInputPercent: Double?,
        ): Map<String, Any?> = emptyMap()

        override fun validateLlmConfig(
          providerId: String,
          protocol: String,
          baseUrl: String,
          apiKey: String,
          model: String,
          reasoningEffort: String,
        ): Map<String, Any?> = emptyMap()

        override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> = emptyMap()

        override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
          emptyMap()

        override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> = emptyMap()

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
    override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingAgentSessionHandle>()
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

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingAgentSessionHandle(
    override val sessionId: String,
    private val resumedSessionIds: MutableList<String>,
    private val runs: List<AgentRunSnapshot> = emptyList(),
    private val queueSnapshot: SessionQueueSnapshot? = null,
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

    override fun hasPendingWork(): Boolean = false
  }

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

  private class RecordingRuntimeServiceWakeCommandDispatcher : RuntimeServiceWakeCommandDispatcher {
    var dispatchCallCount: Int = 0
      private set

    override fun dispatch(intent: Intent?) {
      dispatchCallCount += 1
    }
  }

  private class RecordingRuntimeServiceBinderEndpoint : Binder(), RuntimeServiceBinderEndpoint {
    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = error("unused in test")
  }

  private class TestRuntimeService : Service() {
    override fun onBind(intent: Intent?) = null
  }
}
