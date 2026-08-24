package com.opencray.app

import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceHostBindingTest : RuntimeServiceHostTestBase() {
  @Test
  fun androidBindingClientTransitionsFromProjectionBackedBindingToBinderConnection() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client"))
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      projectionStore = projectionStoreFor(expected),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )

    val initial = client.loadSnapshot()

    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
    assertEquals("binding", initial.connectionState.phase)
    assertEquals("in_process", initial.connectionState.transport)
    assertEquals(true, initial.connectionState.serviceStartRequested)
    assertEquals(true, initial.connectionState.bindingRequested)
    assertFalse(initial.connectionState.binderAvailable)
    assertNull(initial.bridgeSnapshot)
    assertEquals(
      expected.runtimeOwnerLifecycle.runtimeOwnerId,
      initial.diagnosticsSnapshot.runtimeOwnerLifecycle.runtimeOwnerId,
    )

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    val connectedState = client.loadConnectionState()
    val connectedSnapshot = client.loadSnapshot()

    assertEquals("bound", connectedState.phase)
    assertEquals("binder", connectedState.transport)
    assertTrue(connectedState.bindingRequested)
    assertTrue(connectedState.binderAvailable)
    assertSame(expected, connectedSnapshot.bridgeSnapshot)
    assertEquals(
      expected.runtimeOwnerLifecycle.runtimeOwnerId,
      connectedSnapshot.diagnosticsSnapshot.runtimeOwnerLifecycle.runtimeOwnerId,
    )
    assertEquals(
      expected.serviceWorkState.phase,
      connectedSnapshot.diagnosticsSnapshot.serviceWorkState.phase,
    )
    assertEquals(
      expected.serviceWorkState.keepAliveRequired,
      connectedSnapshot.diagnosticsSnapshot.serviceWorkState.keepAliveRequired,
    )
  }

  @Test
  fun androidBindingClientExposesBinderChatRuntimeGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-chat-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingChatRuntimeGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadChatRuntimeGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadChatRuntimeGateway())
  }

  @Test
  fun androidBindingClientExposesBinderShellGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-shell-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingShellGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadShellGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadShellGateway(): OpenCrayShellGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadShellGateway())
  }

  @Test
  fun androidBindingClientDefaultPeekSnapshotReturnsNullWithoutBinderOrProjectionSnapshot() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )

    val snapshot = client.peekSnapshot()

    assertNull(snapshot)
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
  }

  @Test
  fun runtimeServiceProjectionStoreRoundTripsSnapshot() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("runtime-service-projection-store"),
    ).copy(
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
        idleGraceMs = 60_000L,
        stopScheduled = true,
        stopDeadlineEpochMs = 61_000L,
        lastStartId = 9,
        changedAtEpochMs = 1_500L,
      ),
    ).toProjectionSnapshot()
    val store = FileBackedRuntimeServiceProjectionStoreFactory(
      temporaryFolder.newFolder("runtime-service-projection-store-files"),
    ).create()

    store.saveSnapshot(expected)

    val actual = store.loadSnapshot()

    assertNotNull(actual)
    assertEquals(
      expected.runtimeControllerLifecycle?.controllerInstanceId,
      actual?.runtimeControllerLifecycle?.controllerInstanceId,
    )
    assertEquals(
      expected.runtimeOwnerLifecycle.runtimeOwnerId,
      actual?.runtimeOwnerLifecycle?.runtimeOwnerId,
    )
    assertEquals(
      expected.runtimeOwnerWorkSummary.activeSessionIds,
      actual?.runtimeOwnerWorkSummary?.activeSessionIds,
    )
    assertEquals(
      expected.serviceLifecycle.serviceInstanceId,
      actual?.serviceLifecycle?.serviceInstanceId,
    )
    assertEquals(
      expected.serviceWorkState.keepAliveReason,
      actual?.serviceWorkState?.keepAliveReason,
    )
    assertEquals(
      expected.serviceKeepAliveState.stopDeadlineEpochMs,
      actual?.serviceKeepAliveState?.stopDeadlineEpochMs,
    )
    assertEquals(
      expected.localRuntimeServerState?.phase,
      actual?.localRuntimeServerState?.phase,
    )
    assertEquals(
      expected.localRuntimeServerState?.listeningPort,
      actual?.localRuntimeServerState?.listeningPort,
    )
  }

  @Test
  fun androidBindingClientUsesTargetScopedDurableProjectionStoreWhenProjectionStoreNotInjected() {
    val context = FilesDirBackedContext(
      temporaryFolder.newFolder("binding-client-target-scoped-files"),
    )
    val storeFactory = FileBackedRuntimeServiceProjectionStoreFactory.fromContext(context)
    val interactiveExpected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-interactive-projection"),
    ).copy(
      localRuntimeServerState = defaultLocalRuntimeServerState(RuntimeServiceTarget.INTERACTIVE),
    ).toProjectionSnapshot()
    val detachedExpected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-detached-projection"),
    ).copy(
      localRuntimeServerState = defaultLocalRuntimeServerState(RuntimeServiceTarget.DETACHED_BACKGROUND),
    ).toProjectionSnapshot()
    storeFactory.create(RuntimeServiceTarget.INTERACTIVE).saveSnapshot(interactiveExpected)
    storeFactory.create(RuntimeServiceTarget.DETACHED_BACKGROUND).saveSnapshot(detachedExpected)
    val bindingAdapter = RecordingBindingAdapter()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val snapshot = client.peekProjectionSnapshot()

    assertEquals(
      interactiveExpected.runtimeOwnerLifecycle.runtimeOwnerId,
      snapshot?.runtimeOwnerLifecycle?.runtimeOwnerId,
    )
    assertEquals(
      interactiveExpected.localRuntimeServerState?.requestedPort,
      snapshot?.localRuntimeServerState?.requestedPort,
    )
    assertEquals(
      localRuntimeLoopbackPortForTarget(RuntimeServiceTarget.INTERACTIVE),
      snapshot?.localRuntimeServerState?.requestedPort,
    )
    assertEquals(0, bindingAdapter.bindCount)
  }

  @Test
  fun androidBindingClientPeekProjectionSnapshotUsesDurableStoreWithoutStartingServiceOrBind() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-projection-peek"),
    ).toProjectionSnapshot()
    val bindingAdapter = RecordingBindingAdapter()
    val projectionStore = inMemoryRuntimeServiceProjectionStore().apply {
      saveSnapshot(expected)
    }
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      projectionStore = projectionStore,
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      isMainThread = { true },
    )

    val snapshot = client.peekProjectionSnapshot()

    assertEquals(
      expected.runtimeOwnerWorkSummary.activeSessionIds,
      snapshot?.runtimeOwnerWorkSummary?.activeSessionIds,
    )
    assertEquals(
      expected.serviceKeepAliveState.phase,
      snapshot?.serviceKeepAliveState?.phase,
    )
    assertEquals("fallback", client.loadConnectionState().phase)
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
  }

  @Test
  fun androidBindingClientLoadSnapshotUsesProjectionStoreWhenLiveBridgeSnapshotIsUnavailable() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-projection-load"),
    ).toProjectionSnapshot()
    val bindingAdapter = RecordingBindingAdapter()
    val projectionStore = inMemoryRuntimeServiceProjectionStore().apply {
      saveSnapshot(expected)
    }
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      projectionStore = projectionStore,
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val snapshot = client.loadSnapshot()

    assertNull(snapshot.bridgeSnapshot)
    assertEquals(
      expected.runtimeControllerLifecycle?.controllerInstanceId,
      snapshot.diagnosticsSnapshot.runtimeControllerLifecycle?.controllerInstanceId,
    )
    assertEquals(
      expected.runtimeOwnerLifecycle.runtimeOwnerId,
      snapshot.diagnosticsSnapshot.runtimeOwnerLifecycle.runtimeOwnerId,
    )
    assertEquals(
      expected.runtimeOwnerWorkSummary.activeSessionIds,
      snapshot.diagnosticsSnapshot.runtimeOwnerWorkSummary.activeSessionIds,
    )
    assertEquals(
      expected.serviceLifecycle.serviceInstanceId,
      snapshot.diagnosticsSnapshot.serviceLifecycle.serviceInstanceId,
    )
    assertEquals(
      expected.serviceWorkState.phase,
      snapshot.diagnosticsSnapshot.serviceWorkState.phase,
    )
    assertEquals(
      expected.serviceKeepAliveState.phase,
      snapshot.diagnosticsSnapshot.serviceKeepAliveState.phase,
    )
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
  }

  @Test
  fun androidBindingClientPeekSnapshotUsesProjectionStoreWithoutStartingServiceOrBind() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-projection-snapshot-peek"),
    ).toProjectionSnapshot()
    val bindingAdapter = RecordingBindingAdapter()
    val projectionStore = inMemoryRuntimeServiceProjectionStore().apply {
      saveSnapshot(expected)
    }
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      projectionStore = projectionStore,
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val snapshot = client.peekSnapshot()

    assertNull(snapshot?.bridgeSnapshot)
    assertEquals(
      expected.runtimeOwnerLifecycle.runtimeOwnerId,
      snapshot?.diagnosticsSnapshot?.runtimeOwnerLifecycle?.runtimeOwnerId,
    )
    assertEquals(
      expected.serviceLifecycle.serviceInstanceId,
      snapshot?.diagnosticsSnapshot?.serviceLifecycle?.serviceInstanceId,
    )
    assertEquals(0, startRequestCount)
    assertEquals(0, bindingAdapter.bindCount)
  }

  @Test
  fun androidBindingClientReportsInvalidBinderWhenBinderAccessIsUnsupported() {
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertNull(client.loadShellGateway())
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)

    bindingAdapter.connect(Binder())

    val connectionState = client.loadConnectionState()

    assertEquals("invalid_binder", connectionState.phase)
    assertEquals("in_process", connectionState.transport)
    assertTrue(connectionState.bindingRequested)
    assertEquals(0, bindingAdapter.unbindCount)

    assertNull(client.loadShellGateway())
    assertEquals(1, startRequestCount)
    assertEquals(1, bindingAdapter.bindCount)
  }

  @Test
  fun versionedRuntimeServiceBinderAccessKeepsV1ControllerReadOnly() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("versioned-runtime-controller-access"),
    )
    val wireAccess = RecordingRuntimeServiceControllerWireAccess(
      protocolVersion = RUNTIME_SERVICE_CONTROLLER_MIN_PROTOCOL_VERSION,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      projectionSnapshotJson = encodeRuntimeServiceProjectionSnapshot(
        expected.toProjectionSnapshot(),
      ),
    )

    val access = versionedRuntimeServiceBinderAccess(
      wireAccess = wireAccess,
      expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )

    assertNotNull(access)
    assertEquals(
      expected.toProjectionSnapshot(),
      access?.loadSnapshot()?.toProjectionSnapshot(),
    )
    assertNull(access?.loadShellGateway())
    assertNull(access?.loadChatRuntimeGateway())
    assertNull(
      access?.dispatchChatWriteCommand(
        OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
      ),
    )
    assertEquals(1, wireAccess.snapshotLoadCount)
    assertEquals(0, wireAccess.capabilityLoadCount)
    assertEquals(0, wireAccess.writeCommandJson.size)
  }

  @Test
  fun versionedRuntimeServiceBinderAccessDispatchesNegotiatedV2WriteCapabilities() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("versioned-runtime-controller-v2-writes"),
    )
    val wireAccess = RecordingRuntimeServiceControllerWireAccess(
      protocolVersion = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      projectionSnapshotJson = encodeRuntimeServiceProjectionSnapshot(
        expected.toProjectionSnapshot(),
      ),
      supportedCapabilities = RuntimeServiceControllerCapabilities.ALL,
      writeDispatcher = { commandJson ->
        when (val decoded = requireNotNull(decodeRuntimeServiceWriteCommand(commandJson))) {
          is DecodedRuntimeServiceWriteCommand.Chat -> {
            assertEquals(
              OpenCrayChatWriteCommand.SubmitChatMessage("remote chat", emptyList()),
              decoded.command,
            )
            encodeRuntimeServiceWriteResult(
              OpenCrayChatWriteDispatchResult.Payload(mapOf("runId" to "run-remote")),
            )
          }

          is DecodedRuntimeServiceWriteCommand.Skills -> {
            assertEquals(OpenCraySkillsWriteCommand.RefreshSkills, decoded.command)
            encodeRuntimeServiceWriteResult(
              OpenCraySkillsWriteDispatchResult.Message("skills-remote"),
            )
          }

          is DecodedRuntimeServiceWriteCommand.Settings -> {
            assertEquals(
              OpenCraySettingsWriteCommand.PerformStrongBackgroundAction("repair"),
              decoded.command,
            )
            encodeRuntimeServiceWriteResult(
              OpenCraySettingsWriteDispatchResult.Payload(mapOf("repaired" to true)),
            )
          }
        }
      },
    )

    val access = requireNotNull(
      versionedRuntimeServiceBinderAccess(
        wireAccess = wireAccess,
        expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      ),
    )

    assertEquals(
      OpenCrayChatWriteDispatchResult.Payload(mapOf("runId" to "run-remote")),
      access.dispatchChatWriteCommand(
        OpenCrayChatWriteCommand.SubmitChatMessage("remote chat", emptyList()),
      ),
    )
    assertEquals(
      OpenCraySkillsWriteDispatchResult.Message("skills-remote"),
      access.dispatchSkillsWriteCommand(OpenCraySkillsWriteCommand.RefreshSkills),
    )
    assertEquals(
      OpenCraySettingsWriteDispatchResult.Payload(mapOf("repaired" to true)),
      access.dispatchSettingsWriteCommand(
        OpenCraySettingsWriteCommand.PerformStrongBackgroundAction("repair"),
      ),
    )
    assertEquals(1, wireAccess.capabilityLoadCount)
    assertEquals(3, wireAccess.writeCommandJson.size)
  }

  @Test
  fun versionedRuntimeServiceBinderAccessSkipsUnnegotiatedV2Writes() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("versioned-runtime-controller-v2-read-only"),
    )
    val wireAccess = RecordingRuntimeServiceControllerWireAccess(
      protocolVersion = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      projectionSnapshotJson = encodeRuntimeServiceProjectionSnapshot(
        expected.toProjectionSnapshot(),
      ),
      supportedCapabilities = RuntimeServiceControllerCapabilities.PROJECTION_READ,
    )

    val access = requireNotNull(
      versionedRuntimeServiceBinderAccess(
        wireAccess = wireAccess,
        expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      ),
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
    assertEquals(1, wireAccess.capabilityLoadCount)
    assertEquals(0, wireAccess.writeCommandJson.size)
  }

  @Test
  fun versionedRuntimeServiceBinderAccessRejectsVersionOrTargetMismatch() {
    val expectedPayload = encodeRuntimeServiceProjectionSnapshot(
      bridgeSnapshot(
        temporaryFolder.newFolder("versioned-runtime-controller-mismatch"),
      ).toProjectionSnapshot(),
    )

    val versionMismatch = versionedRuntimeServiceBinderAccess(
      wireAccess = RecordingRuntimeServiceControllerWireAccess(
        protocolVersion = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION + 1,
        runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
        projectionSnapshotJson = expectedPayload,
      ),
      expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val targetMismatch = versionedRuntimeServiceBinderAccess(
      wireAccess = RecordingRuntimeServiceControllerWireAccess(
        protocolVersion = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION,
        runtimeTarget = RuntimeServiceTarget.INTERACTIVE.wireValue,
        projectionSnapshotJson = expectedPayload,
      ),
      expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )
    val missingProjectionCapability = versionedRuntimeServiceBinderAccess(
      wireAccess = RecordingRuntimeServiceControllerWireAccess(
        protocolVersion = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION,
        runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
        projectionSnapshotJson = expectedPayload,
        supportedCapabilities = RuntimeServiceControllerCapabilities.CHAT_WRITE,
      ),
      expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )

    assertNull(versionMismatch)
    assertNull(targetMismatch)
    assertNull(missingProjectionCapability)
  }

  @Test
  fun androidBindingClientAcceptsVersionedRemoteBinderAccess() {
    val expected = bridgeSnapshot(
      temporaryFolder.newFolder("binding-client-versioned-remote"),
    )
    val bindingAdapter = RecordingBindingAdapter()
    val remoteBinder = Binder()
    val resolvedTargets = mutableListOf<RuntimeServiceTarget>()
    val wireAccess = RecordingRuntimeServiceControllerWireAccess(
      protocolVersion = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue,
      projectionSnapshotJson = encodeRuntimeServiceProjectionSnapshot(
        expected.toProjectionSnapshot(),
      ),
    )
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
      binderAccessResolver = { binder, target ->
        assertSame(remoteBinder, binder)
        resolvedTargets += target
        versionedRuntimeServiceBinderAccess(wireAccess, target)
      },
    )

    assertNull(client.loadShellGateway())
    bindingAdapter.connect(remoteBinder)

    assertEquals("bound", client.loadConnectionState().phase)
    assertEquals("binder", client.loadConnectionState().transport)
    assertTrue(client.loadConnectionState().binderAvailable)
    assertEquals(
      expected.toProjectionSnapshot(),
      client.loadSnapshot().diagnosticsSnapshot,
    )
    assertEquals(listOf(RuntimeServiceTarget.DETACHED_BACKGROUND), resolvedTargets)
  }

  @Test
  fun androidBindingClientReportsNullBindingWhenServiceReturnsNoBinder() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-null-binding"))
    val bindingAdapter = RecordingBindingAdapter()
    var startRequestCount = 0
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      projectionStore = projectionStoreFor(expected),
      bindingAdapter = bindingAdapter,
      startRequester = { startRequestCount += 1 },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val initial = client.loadSnapshot()
    bindingAdapter.nullBind()

    val connectionState = client.loadConnectionState()
    val fallbackSnapshot = client.loadSnapshot()

    assertEquals("binding", initial.connectionState.phase)
    assertEquals("null_binding", connectionState.phase)
    assertEquals("in_process", connectionState.transport)
    assertTrue(connectionState.serviceStartRequested)
    assertFalse(connectionState.bindingRequested)
    assertFalse(connectionState.binderAvailable)
    assertEquals("null_binding", connectionState.fallbackReason)
    assertNull(fallbackSnapshot.bridgeSnapshot)
    assertEquals(
      expected.serviceLifecycle.serviceInstanceId,
      fallbackSnapshot.diagnosticsSnapshot.serviceLifecycle.serviceInstanceId,
    )
    assertEquals(1, startRequestCount)
    assertEquals(2, bindingAdapter.bindCount)
    assertEquals(0, bindingAdapter.unbindCount)
  }

  @Test
  fun androidBindingClientExposesBinderSkillsGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-skills-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSkillsGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadSkillsGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadSkillsGateway(): OpenCraySkillsGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadSkillsGateway())
  }

  @Test
  fun androidBindingClientExposesBinderSettingsGatewayWhenConnected() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-settings-gateway"))
    val bindingAdapter = RecordingBindingAdapter()
    val binderGateway = RecordingSettingsGateway("binder")
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    assertEquals(null, client.loadSettingsGateway())

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected

        override fun loadSettingsGateway(): OpenCraySettingsGateway = binderGateway
      },
    )

    assertSame(binderGateway, client.loadSettingsGateway())
  }

  @Test
  fun androidBindingClientReleasesIdleBindingAfterTransientUse() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-idle-release"))
    val bindingAdapter = RecordingBindingAdapter()
    val releaseScheduler = RecordingRuntimeServiceDelayScheduler()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      projectionStore = projectionStoreFor(expected),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      bindingReleaseDelayMs = 250L,
      bindingReleaseScheduler = releaseScheduler,
      serviceIntentFactory = { Intent() },
    )

    val initial = client.loadSnapshot()
    assertEquals("binding", initial.connectionState.phase)
    assertEquals(0, bindingAdapter.unbindCount)

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    releaseScheduler.runNext()

    val released = client.loadConnectionState()

    assertEquals(1, bindingAdapter.unbindCount)
    assertEquals("fallback", released.phase)
    assertEquals("binder_idle_released", released.fallbackReason)
    assertFalse(released.bindingRequested)
    assertFalse(released.binderAvailable)
  }

  @Test
  fun androidBindingClientKeepsBindingWhileConnectionObserverIsActive() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-observer-retain"))
    val bindingAdapter = RecordingBindingAdapter()
    val releaseScheduler = RecordingRuntimeServiceDelayScheduler()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      bindingReleaseDelayMs = 250L,
      bindingReleaseScheduler = releaseScheduler,
      serviceIntentFactory = { Intent() },
    )

    val dispose = client.observeConnectionState { }
    assertEquals(1, bindingAdapter.bindCount)

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    releaseScheduler.runNext()

    assertEquals(0, bindingAdapter.unbindCount)
    assertEquals("bound", client.loadConnectionState().phase)

    dispose()
    releaseScheduler.runNext()

    assertEquals(1, bindingAdapter.unbindCount)
    assertEquals("fallback", client.loadConnectionState().phase)
  }

  @Test
  fun androidBindingClientPublishesInvalidBinderConnectionStateToObservers() {
    val bindingAdapter = RecordingBindingAdapter()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )
    val observedStates = mutableListOf<RuntimeServiceConnectionState>()

    val dispose = client.observeConnectionState { state ->
      observedStates += state
    }

    try {
      bindingAdapter.connect(Binder())

      val invalidBinderState = observedStates.last()
      assertEquals("invalid_binder", invalidBinderState.phase)
      assertEquals("in_process", invalidBinderState.transport)
      assertFalse(invalidBinderState.binderAvailable)
    } finally {
      dispose()
    }
  }

  @Test
  fun androidBindingClientRebindsAfterServiceDisconnectWhileObserverIsActive() {
    val expected = bridgeSnapshot(temporaryFolder.newFolder("binding-client-disconnect-rebind"))
    val bindingAdapter = RecordingBindingAdapter()
    val client = AndroidBindingOpenCrayRuntimeServiceClient(
      appContext = ContextWrapper(null),
      bindingAdapter = bindingAdapter,
      startRequester = { },
      mainThreadPoster = ImmediateMainThreadPoster,
      serviceIntentFactory = { Intent() },
    )

    val dispose = client.observeConnectionState { }

    bindingAdapter.connect(
      object : Binder(), OpenCrayRuntimeServiceBinderAccess {
        override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = expected
      },
    )

    bindingAdapter.disconnect()

    assertEquals("binding", client.loadConnectionState().phase)
    assertTrue(client.loadConnectionState().bindingRequested)
    assertEquals(2, bindingAdapter.bindCount)

    dispose()
  }
}
