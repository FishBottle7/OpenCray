package com.opencray.app

import android.app.Service
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentBootstrapTransportTest : AgentBootstrapTestBase() {
  @Test
  fun resolveRuntimeServiceTransportBootstrapUsesInjectedFactoryWithoutTouchingDefaultTransportBootstrap() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-transport-bootstrap"))
    val expectedGatewayBundle = testServiceGatewayBundle()
    var startCallCount = 0
    val expectedBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = expectedGatewayBundle,
      ensureStarted = {
        startCallCount += 1
        true
      },
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
  fun defaultTransportBootstrapFactoryUsesInjectedFactoryAndLoopbackBootstrap() {
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
    var capturedRuntimeOwnerWriteGuard: (() -> Boolean)? = null
    var loopbackStartCallCount = 0
    var loopbackDisposeCallCount = 0
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val factory = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory {
          appContext,
          runtimeTarget,
          localGatewayProvider,
          gatewayBundle,
          resolvedTransportCoordinator,
          runtimeOwnerWriteGuard,
        ->
        capturedLoopbackContext = appContext
        capturedLoopbackTarget = runtimeTarget
        capturedLoopbackTransportCoordinator = resolvedTransportCoordinator
        capturedRuntimeOwnerWriteGuard = runtimeOwnerWriteGuard
        assertSame(expectedGatewayBundle, gatewayBundle)
        assertTrue(
          runCatching { resolvedTransportCoordinator.currentGatewayBundle() }.exceptionOrNull()
            is IllegalStateException,
        )
        assertTrue(localGatewayProvider() is OpenCrayLocalHostGateway)
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = {
            loopbackStartCallCount += 1
            true
          },
          dispose = { loopbackDisposeCallCount += 1 },
        )
      },
    )

    val resolved = factory.create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies.copy(
        runtimeServiceOwnerWriteGuard = projectionCoordinator::tryAcquireOwnerLease,
      ),
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
    assertSame(expectedGatewayBundle, transportCoordinator.currentGatewayBundle())
    resolved.dispose()

    assertSame(expectedGatewayBundle, resolved.gatewayBundle)
    assertSame(context, capturedGatewayContext)
    assertSame(expectedKeepAliveState, capturedKeepAliveState)
    assertTrue(keepAliveListenerRegistered)
    assertSame(context, capturedLoopbackContext)
    assertEquals(RuntimeServiceTarget.INTERACTIVE, capturedLoopbackTarget)
    assertSame(transportCoordinator, capturedLoopbackTransportCoordinator)
    assertEquals(false, capturedRuntimeOwnerWriteGuard?.invoke())
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
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
  fun defaultTransportBootstrapFactoryDoesNotReplaceRetainedGatewayUntilEnsureStarted() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(temporaryFolder.newFolder("service-transport-delayed-bind"))
    val disposedLabels = mutableListOf<String>()
    val retainedGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "retained" },
    )
    val contenderGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "contender" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
      bindGatewayBundle(retainedGatewayBundle)
    }
    var loopbackCreateCount = 0
    val bootstrap = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory { _, _, _, gatewayBundle, _, _ ->
        loopbackCreateCount += 1
        assertSame(contenderGatewayBundle, gatewayBundle)
        RuntimeServiceLoopbackBootstrap()
      },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory { _, _, _, _ ->
        contenderGatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { RuntimeServiceKeepAliveState() },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { ({ }) },
      transportCoordinator = transportCoordinator,
    )

    assertSame(retainedGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertTrue(disposedLabels.isEmpty())
    assertEquals(1, loopbackCreateCount)

    bootstrap.dispose()

    assertSame(retainedGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertEquals(listOf("contender"), disposedLabels)
  }

  @Test
  fun defaultTransportBootstrapFactoryReplacesRetainedGatewayOnEnsureStarted() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-transport-replace-on-start"),
    )
    val disposedLabels = mutableListOf<String>()
    val retainedGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "retained" },
    )
    val contenderGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "contender" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
      bindGatewayBundle(retainedGatewayBundle)
    }
    var loopbackStartCount = 0
    var loopbackDisposeCount = 0
    val bootstrap = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory { _, _, _, gatewayBundle, resolvedTransportCoordinator, _ ->
        assertSame(contenderGatewayBundle, gatewayBundle)
        assertSame(retainedGatewayBundle, resolvedTransportCoordinator.currentGatewayBundle())
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = {
            loopbackStartCount += 1
            true
          },
          dispose = { loopbackDisposeCount += 1 },
        )
      },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory { _, _, _, _ ->
        contenderGatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { RuntimeServiceKeepAliveState() },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { ({ }) },
      transportCoordinator = transportCoordinator,
    )

    bootstrap.ensureStarted()

    assertSame(contenderGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertEquals(listOf("retained"), disposedLabels)
    assertEquals(1, loopbackStartCount)

    bootstrap.dispose()

    assertEquals(listOf("retained", "contender"), disposedLabels)
    assertEquals(1, loopbackDisposeCount)
    assertTrue(
      runCatching { transportCoordinator.currentGatewayBundle() }.exceptionOrNull()
        is IllegalStateException,
    )
  }

  @Test
  fun defaultTransportBootstrapDisposeWaitsForConcurrentGatewayBinding() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-transport-dispose-during-bind"),
    )
    val gatewayDisposeCount = AtomicInteger()
    val gatewayBundle = testServiceGatewayBundle(
      dispose = { gatewayDisposeCount.incrementAndGet() },
    )
    val bindEntered = CountDownLatch(1)
    val allowBind = CountDownLatch(1)
    val loopbackDisposed = CountDownLatch(1)
    val disposeFinished = CountDownLatch(1)
    val coordinatorDelegate = DefaultRuntimeServiceTransportCoordinator()
    val transportCoordinator = object : RuntimeServiceTransportCoordinator {
      override fun bindGatewayBundle(gatewayBundle: OpenCrayRuntimeServiceGatewayBundle) {
        bindEntered.countDown()
        check(allowBind.await(2L, TimeUnit.SECONDS)) { "Timed out waiting to finish gateway bind." }
        coordinatorDelegate.bindGatewayBundle(gatewayBundle)
      }

      override fun releaseGatewayBundle(gatewayBundle: OpenCrayRuntimeServiceGatewayBundle) {
        coordinatorDelegate.releaseGatewayBundle(gatewayBundle)
      }

      override fun currentGatewayBundle(): OpenCrayRuntimeServiceGatewayBundle =
        coordinatorDelegate.currentGatewayBundle()

      override fun bindLocalRuntimeServerStateProvider(
        provider: () -> LocalRuntimeServerState?,
      ) {
        coordinatorDelegate.bindLocalRuntimeServerStateProvider(provider)
      }

      override fun currentLocalRuntimeServerState(): LocalRuntimeServerState? =
        coordinatorDelegate.currentLocalRuntimeServerState()

      override fun dispose() {
        coordinatorDelegate.dispose()
      }
    }
    val bootstrap = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory { _, _, _, _, _, _ ->
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = { true },
          dispose = { loopbackDisposed.countDown() },
        )
      },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.INTERACTIVE,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory { _, _, _, _ ->
        gatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { RuntimeServiceKeepAliveState() },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { ({ }) },
      transportCoordinator = transportCoordinator,
    )
    val startResult = AtomicBoolean(true)
    val startFailure = AtomicReference<Throwable?>()
    val disposeFailure = AtomicReference<Throwable?>()
    val startThread = Thread {
      runCatching { startResult.set(bootstrap.ensureStarted()) }
        .exceptionOrNull()
        ?.let(startFailure::set)
    }
    val disposeThread = Thread {
      runCatching { bootstrap.dispose() }
        .exceptionOrNull()
        ?.let(disposeFailure::set)
      disposeFinished.countDown()
    }

    startThread.start()
    try {
      assertTrue(bindEntered.await(2L, TimeUnit.SECONDS))
      disposeThread.start()
      assertTrue(loopbackDisposed.await(2L, TimeUnit.SECONDS))
      assertFalse(disposeFinished.await(100L, TimeUnit.MILLISECONDS))

      allowBind.countDown()
      assertTrue(disposeFinished.await(2L, TimeUnit.SECONDS))
    } finally {
      allowBind.countDown()
      startThread.join(2_000L)
      if (disposeThread.state != Thread.State.NEW) {
        disposeThread.join(2_000L)
      }
    }

    assertNull(startFailure.get())
    assertNull(disposeFailure.get())
    assertFalse(startResult.get())
    assertEquals(1, gatewayDisposeCount.get())
    assertTrue(
      runCatching { transportCoordinator.currentGatewayBundle() }.exceptionOrNull()
        is IllegalStateException,
    )
  }

  @Test
  fun defaultTransportBootstrapFactoryDoesNotReplaceRetainedGatewayWhenLoopbackStartFails() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-transport-start-failure"),
    )
    val disposedLabels = mutableListOf<String>()
    val retainedGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "retained" },
    )
    val contenderGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "contender" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
      bindGatewayBundle(retainedGatewayBundle)
    }
    var loopbackStartCount = 0
    val bootstrap = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory { _, _, _, gatewayBundle, resolvedTransportCoordinator, _ ->
        assertSame(contenderGatewayBundle, gatewayBundle)
        assertSame(retainedGatewayBundle, resolvedTransportCoordinator.currentGatewayBundle())
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = {
            loopbackStartCount += 1
            false
          },
        )
      },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory { _, _, _, _ ->
        contenderGatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { RuntimeServiceKeepAliveState() },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { ({ }) },
      transportCoordinator = transportCoordinator,
    )

    bootstrap.ensureStarted()

    assertSame(retainedGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertEquals(1, loopbackStartCount)
    assertTrue(disposedLabels.isEmpty())

    bootstrap.dispose()

    assertSame(retainedGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertEquals(listOf("contender"), disposedLabels)
  }

  @Test
  fun defaultTransportBootstrapFactoryDoesNotReplaceRetainedGatewayWhenLoopbackStartThrows() {
    val context = MinimalContext()
    val serviceHost = testServiceHost(
      temporaryFolder.newFolder("service-transport-start-throw"),
    )
    val disposedLabels = mutableListOf<String>()
    val retainedGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "retained" },
    )
    val contenderGatewayBundle = testServiceGatewayBundle(
      dispose = { disposedLabels += "contender" },
    )
    val transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
      bindGatewayBundle(retainedGatewayBundle)
    }
    var loopbackStartCount = 0
    val expectedFailure = IllegalStateException("loopback_start_failed")
    val bootstrap = DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
      loopbackBootstrapFactory = RuntimeServiceLoopbackBootstrapFactory { _, _, _, gatewayBundle, resolvedTransportCoordinator, _ ->
        assertSame(contenderGatewayBundle, gatewayBundle)
        assertSame(retainedGatewayBundle, resolvedTransportCoordinator.currentGatewayBundle())
        RuntimeServiceLoopbackBootstrap(
          ensureStarted = {
            loopbackStartCount += 1
            throw expectedFailure
          },
        )
      },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayDependencies = serviceHost.toRuntimeServiceBootstrapState().gatewayDependencies,
      runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory { _, _, _, _ ->
        contenderGatewayBundle
      },
      runtimeServiceKeepAliveStateProvider = { RuntimeServiceKeepAliveState() },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { ({ }) },
      transportCoordinator = transportCoordinator,
    )

    val failure = try {
      bootstrap.ensureStarted()
      fail("Expected loopback start failure to propagate.")
    } catch (throwable: IllegalStateException) {
      throwable
    }

    assertSame(expectedFailure, failure)
    assertSame(retainedGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertEquals(1, loopbackStartCount)
    assertTrue(disposedLabels.isEmpty())

    bootstrap.dispose()

    assertSame(retainedGatewayBundle, transportCoordinator.currentGatewayBundle())
    assertEquals(listOf("contender"), disposedLabels)
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
    val descriptorStore = RuntimeServiceLoopbackDescriptorStore(
      temporaryFolder.newFolder("loopback-descriptor-injected"),
    )
    val factory = DefaultRuntimeServiceLoopbackBootstrapFactory(
      ensureServerStarted = { resolvedContext, runtimeTarget, providers ->
        ensureServerStartedCallCount += 1
        capturedContext = resolvedContext
        capturedTarget = runtimeTarget
        capturedProviders = providers
        startedServer
      },
      descriptorStoreFactory = { descriptorStore },
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
      runtimeOwnerWriteGuard = { true },
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
    val descriptorStore = RuntimeServiceLoopbackDescriptorStore(
      temporaryFolder.newFolder("loopback-descriptor-production"),
    )
    val bootstrap = DefaultRuntimeServiceLoopbackBootstrapFactory(
      descriptorStoreFactory = { descriptorStore },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { NoOpLocalHostGateway() },
      gatewayBundle = gatewayBundle,
      transportCoordinator = transportCoordinator,
      runtimeOwnerWriteGuard = { true },
    )

    try {
      bootstrap.ensureStarted()

      assertEquals(0, registryProvidersFactoryCallCount)
      assertEquals(
        LocalRuntimeServerState.PHASE_LISTENING,
        transportCoordinator.currentLocalRuntimeServerState()?.phase,
      )
      assertEquals(0, transportCoordinator.currentLocalRuntimeServerState()?.requestedPort)
      assertEquals(
        transportCoordinator.currentLocalRuntimeServerState()?.listeningPort,
        descriptorStore.read(RuntimeServiceTarget.DETACHED_BACKGROUND)?.port,
      )
    } finally {
      bootstrap.dispose()
      assertNull(descriptorStore.read(RuntimeServiceTarget.DETACHED_BACKGROUND))
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
    val descriptorStore = RuntimeServiceLoopbackDescriptorStore(
      temporaryFolder.newFolder("loopback-descriptor-environment"),
    )
    val bootstrap = DefaultRuntimeServiceLoopbackBootstrapFactory(
      ensureServerStarted = { _, _, providers ->
        capturedProviders = providers
        startedServer
      },
      descriptorStoreFactory = { descriptorStore },
    ).create(
      appContext = context,
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localGatewayProvider = { localGateway },
      gatewayBundle = gatewayBundle,
      transportCoordinator = DefaultRuntimeServiceTransportCoordinator().apply {
        bindGatewayBundle(gatewayBundle)
      },
      runtimeOwnerWriteGuard = { true },
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
}
