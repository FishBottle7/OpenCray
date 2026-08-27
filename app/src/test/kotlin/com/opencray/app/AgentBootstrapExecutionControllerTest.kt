package com.opencray.app

import android.content.Context
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AgentBootstrapExecutionControllerTest : AgentBootstrapTestBase() {
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
    val durableOnlySessionId = chatStore.loadState().activeSession.sessionId
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
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
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
  fun processScopedRuntimeServiceExecutionControllerProviderKeepsDurableControllerIdAcrossRecreate() {
    val context = MinimalContext()
    val runtimeRoot = temporaryFolder.newFolder("execution-controller-durable-identity").toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    val identityStore = FileBackedRuntimeControllerIdentityStore.fromRootDirectory(
      temporaryFolder.newFolder("execution-controller-durable-identity-store"),
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val provider = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeServiceProcessBootstrap = { },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeControllerIdentityStoreProvider = { identityStore },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, runtimeControllerLifecycle ->
        runtimeOwnerBootstrapFor(
          testRuntimeAccess(
            lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
              runtimeControllerId = runtimeControllerLifecycle.controllerInstanceId,
              durableRuntimeControllerId = runtimeControllerLifecycle.durableControllerId,
            ),
            sessionRuntimeManager = runtimeManager,
          ),
        )
      },
      bootstrapFactory = testRuntimeServiceBootstrapFactory(),
    )

    val first = provider.resolve(context)
    provider.reset()
    val second = provider.resolve(context)
    val interactive = ProcessScopedRuntimeServiceExecutionControllerProvider(
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      runtimeServiceProcessBootstrap = { },
      runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
      runtimeControllerIdentityStoreProvider = { identityStore },
      runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
        runtimeExecutionDependencies(runtimeDependencies)
      },
      runtimeOwnerBootstrapProvider = RuntimeOwnerBootstrapProvider { _, runtimeControllerLifecycle ->
        runtimeOwnerBootstrapFor(
          testRuntimeAccess(
            lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
              runtimeControllerId = runtimeControllerLifecycle.controllerInstanceId,
              durableRuntimeControllerId = runtimeControllerLifecycle.durableControllerId,
            ),
            sessionRuntimeManager = runtimeManager,
          ),
        )
      },
      bootstrapFactory = testRuntimeServiceBootstrapFactory(),
    ).resolve(context)

    val firstLifecycle = requireNotNull(first.runtimeControllerLifecycle)
    val secondLifecycle = requireNotNull(second.runtimeControllerLifecycle)
    val interactiveLifecycle = requireNotNull(interactive.runtimeControllerLifecycle)

    assertNotEquals(firstLifecycle.controllerInstanceId, secondLifecycle.controllerInstanceId)
    assertEquals(firstLifecycle.durableControllerId, secondLifecycle.durableControllerId)
    assertNotEquals(firstLifecycle.durableControllerId, interactiveLifecycle.durableControllerId)
  }

  @Test
  fun processScopedRuntimeServiceExecutionControllerProviderDefaultsToFilesDirBackedControllerIdentity() {
    val runtimeRoot = temporaryFolder
      .newFolder("execution-controller-default-durable-identity")
      .toPath()
    val chatStore = ChatSessionLocalStore(runtimeRoot.resolve("chat-session").toFile())
    val runtimeDependencies = testRuntimeDependencies(
      root = runtimeRoot,
      chatStore = chatStore,
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()

    fun createProvider(): ProcessScopedRuntimeServiceExecutionControllerProvider =
      ProcessScopedRuntimeServiceExecutionControllerProvider(
        runtimeServiceProcessBootstrap = { },
        runtimeServiceRetainedShellControlFactory = { testRuntimeServiceRetainedShellControl() },
        runtimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader {
          runtimeExecutionDependencies(runtimeDependencies)
        },
        runtimeOwnerBootstrapProvider =
          RuntimeOwnerBootstrapProvider { _, runtimeControllerLifecycle ->
            runtimeOwnerBootstrapFor(
              testRuntimeAccess(
                lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
                  runtimeControllerId = runtimeControllerLifecycle.controllerInstanceId,
                  durableRuntimeControllerId = runtimeControllerLifecycle.durableControllerId,
                ),
                sessionRuntimeManager = runtimeManager,
              ),
            )
          },
        bootstrapFactory = testRuntimeServiceBootstrapFactory(),
      )

    val first = createProvider().resolve(runtimeDependencies.appContext)
    val second = createProvider().resolve(runtimeDependencies.appContext)

    val firstLifecycle = requireNotNull(first.runtimeControllerLifecycle)
    val secondLifecycle = requireNotNull(second.runtimeControllerLifecycle)

    assertNotEquals(firstLifecycle.controllerInstanceId, secondLifecycle.controllerInstanceId)
    assertEquals(firstLifecycle.durableControllerId, secondLifecycle.durableControllerId)
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

    assertEquals(1, firstRuntimeManager.observerCount)
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
    assertEquals(1, secondRuntimeManager.observerCount)
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
}
