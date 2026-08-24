package com.opencray.app

import android.app.Service
import android.content.Context
import android.os.Handler
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBootstrapAssemblyTest : AgentBootstrapTestBase() {
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

    assertEquals(1, runtimeManager.observerCount)

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
  fun runtimeServiceBootstrapAssemblySchedulesReconnectRetryWhenBootstrapScanFindsFutureDeadline() {
    val root = temporaryFolder.newFolder("bootstrap-assembly-reconnect-retry").toPath()
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile())
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val reconnectSessionId = chatStore.copySession(activeSessionId).activeSession.sessionId
    chatStore.selectSession(activeSessionId)
    val dependencies = testRuntimeDependencies(root = root, chatStore = chatStore)
    val retryAfterEpochMs = System.currentTimeMillis() + 60_000L
    FileBackedAgentQueueSnapshotStoreFactory.fromContext(dependencies.appContext)
      .forChatSession(reconnectSessionId)
      .save(
        SessionQueueSnapshot(
          sessionId = reconnectSessionId,
          agentId = "test-agent",
          lifecycleState = SessionLifecycleState.IDLE,
          nextEnqueueOrder = 2L,
          updatedAtEpochMs = retryAfterEpochMs - 1_000L,
          tasks = listOf(
            SessionQueueTaskSnapshot(
              enqueueOrder = 1L,
              lifecycleState = QueueTaskLifecycleState.SUSPENDED,
              task = AgentTask(
                id = "task-bootstrap-reconnect-retry",
                type = AgentTaskType.PROMPT,
                input = "Reconnect later.",
                state = AgentTaskState.SUSPENDED,
                policyDecision = PolicyDecision(
                  outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                  reasonCode = "test",
                ),
                createdAtEpochMs = retryAfterEpochMs - 2_000L,
                updatedAtEpochMs = retryAfterEpochMs - 1_000L,
                metadata = mapOf(
                  AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to
                    "run-bootstrap-reconnect-retry",
                  AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to reconnectSessionId,
                  RunLifecycleMetadataKeys.RECOVERY_ACTION to "resume_reconnect_process",
                  RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_PROCESS_IDS to
                    "process-bootstrap-reconnect-retry",
                  RunLifecycleMetadataKeys.MANAGED_PROCESS_RECONNECT_RETRY_AFTER_EPOCH_MS to
                    retryAfterEpochMs.toString(),
                ),
              ),
            ),
          ),
        ),
      )
    val runtimeManager = RecordingAgentSessionRuntimeManager().apply {
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = activeSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
      putHandle(
        RecordingAgentSessionHandle(
          sessionId = reconnectSessionId,
          resumedSessionIds = resumedSessionIds,
        ),
      )
    }
    val workScheduler = RecordingScheduledWorkScheduler()
    val assembly = createRuntimeServiceBootstrapAssembly(
      appContext = dependencies.appContext,
      bootstrapContext = runtimeServiceBootstrapContext(dependencies),
      retainedOwnerState = retainedOwnerStateFor(
        testRuntimeAccess(sessionRuntimeManager = runtimeManager),
      ),
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
      retainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      bootstrapFactory = RuntimeServiceBootstrapFactory {
        RuntimeServiceBootstrapParts(
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore =
            inMemoryScheduledTaskTriggerSyncStateStoreFactory().create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
          scheduledWorkScheduler = workScheduler,
        )
      },
    )

    try {
      assertTrue(reconnectSessionId in assembly.bootstrapResult.scannedSessionIds)
      assertEquals(emptyList<String>(), assembly.bootstrapResult.resumedSessionIds)
      assertEquals(emptyList<String>(), runtimeManager.resumedSessionIds)
      assertEquals(retryAfterEpochMs, assembly.bootstrapResult.nextRepairAfterEpochMs)
      assertEquals(
        ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT,
        assembly.bootstrapResult.nextRepairReason,
      )
      assertEquals(
        listOf(InterruptedRunRepairEvidenceKind.MANAGED_PROCESS_RECONNECT),
        assembly.bootstrapResult.repairEvidenceBySession
          .getValue(reconnectSessionId)
          .map { evidence -> evidence.kind },
      )
      val repairRequest = workScheduler.repairRequests.single()
      assertEquals(ScheduledTaskRepairReasons.MANAGED_PROCESS_RECONNECT, repairRequest.first)
      assertTrue(repairRequest.second > 0L)
      assertTrue(repairRequest.second <= 60_000L)
    } finally {
      assembly.dispose()
    }
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
    var capturedRuntimeTarget: RuntimeServiceTarget? = null
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
            resolvedRuntimeTarget,
            resolvedRetainedShellControl,
          ->
          factoryCallCount += 1
          capturedService = resolvedService
          capturedContext = appContext
          capturedHandler = resolvedMainHandler
          capturedRuntimeTarget = resolvedRuntimeTarget
          capturedRetainedShellControl = resolvedRetainedShellControl
          expectedBundle
        },
    )

    val resolved = bootstrapResolver.resolveRuntimeServiceShellControlBundle(
      service = service,
      context = context,
      mainHandler = mainHandler,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      bootstrapState = serviceHost.toRuntimeServiceBootstrapState().copy(
        retainedShellControl = retainedShellControl,
      ),
    )

    assertSame(expectedBundle, resolved)
    assertSame(service, capturedService)
    assertSame(context, capturedContext)
    assertSame(mainHandler, capturedHandler)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, capturedRuntimeTarget)
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
    var capturedResumeInterruptedRuns: ((String) -> RuntimeServiceInterruptedRunRepairResult)? = null
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
          resolvedRuntimeTarget,
          resolvedRetainedShellControl,
        ->
        capturedService = resolvedService
        capturedContext = appContext
        capturedHandler = resolvedMainHandler
        assertEquals(DEFAULT_RUNTIME_SERVICE_TARGET, resolvedRuntimeTarget)
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
      serviceProcessDescriptorProvider = { _, target ->
        runtimeServiceProcessDescriptorForTest(target)
      },
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
    assertEquals(
      "org.opencray.app:runtime_controller",
      capturedLifecycle?.serviceProcess?.expectedProcessName,
    )
    assertEquals(
      DETACHED_RUNTIME_SERVICE_PROCESS_SUFFIX,
      capturedLifecycle?.serviceProcess?.expectedProcessSuffix,
    )
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }

  @Test
  fun openCrayAgentRuntimeServiceBootstrapRejectsWrongProcessBeforeOwnerBootstrap() {
    val context = MinimalContext()
    val service = TestRuntimeService()
    var bootstrapStateProviderCalled = false
    val bootstrapDependencies = guardOnlyRuntimeServiceBootstrapDependencies {
      bootstrapStateProviderCalled = true
    }
    val processDescriptor = runtimeServiceProcessDescriptor(
      packageName = "org.opencray.app",
      processName = "org.opencray.app",
      expectedProcessSuffix = DETACHED_RUNTIME_SERVICE_PROCESS_SUFFIX,
    )
    var failureMessage: String? = null

    try {
      openCrayAgentRuntimeServiceBootstrap(
        service = service,
        appContext = context,
        mainHandler = Handler(),
        bootstrapDependencies = bootstrapDependencies,
        serviceProcessDescriptorProvider = { _, _ -> processDescriptor },
      )
    } catch (expected: IllegalStateException) {
      failureMessage = expected.message
    }

    assertEquals(
      "Runtime service must run in org.opencray.app:runtime_controller; " +
        "current process is org.opencray.app (main_process).",
      failureMessage,
    )
    assertFalse(bootstrapStateProviderCalled)
    assertNull(OpenCrayRuntimeServiceHostRegistry.peek())
    assertNull(InProcessOpenCrayRuntimeOwnerRegistry.peek())
  }
}
