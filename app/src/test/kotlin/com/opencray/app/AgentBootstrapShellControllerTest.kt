package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBootstrapShellControllerTest : AgentBootstrapTestBase() {
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
          true
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
      binderEndpointFactory = { _, endpointProvider ->
        TestDelegatingRuntimeServiceBinderEndpoint(endpointProvider)
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
  fun runtimeServiceShellControllerStartsForegroundBeforeBuildingStartedShell() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        steps += "assemble_bootstrap"
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
              appVisibleProvider = { true },
            ),
            attach = { steps += "shell_attach" },
          ),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
            ensureStarted = {
              steps += "transport_started"
              true
            },
          ),
          executionCoordinator = RecordingRuntimeServiceExecutionCoordinator(),
          wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
        )
      },
      bootstrapForegroundRequested = { true },
      bootstrapForegroundStarter = { resolvedTarget ->
        steps += "bootstrap_foreground:${resolvedTarget.wireValue}"
      },
    )

    val attached = controller.attachForStart(
      intent = Intent(ACTION_RESUME_INTERRUPTED_RUNS),
      target = target,
    )

    assertTrue(attached)
    assertEquals(
      listOf(
        "bootstrap_foreground:${target.wireValue}",
        "assemble_bootstrap",
        "transport_started",
        "shell_attach",
      ),
      steps,
    )
  }

  @Test
  fun runtimeServiceShellControllerDoesNotStartForegroundForBoundOnlyAttach() {
    val context = MinimalContext()
    val foregroundTargets = mutableListOf<RuntimeServiceTarget>()
    val controller = runtimeServiceShellController(
      service = TestRuntimeService(),
      appContext = context,
      mainHandler = Handler(),
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        OpenCrayAgentRuntimeServiceBootstrap(
          shellControlBundle = defaultShellControlBundle(),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
          ),
          executionCoordinator = RecordingRuntimeServiceExecutionCoordinator(),
          wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
        )
      },
      bootstrapForegroundRequested = { true },
      bootstrapForegroundStarter = foregroundTargets::add,
      binderEndpointFactory = { _, endpointProvider ->
        TestDelegatingRuntimeServiceBinderEndpoint(endpointProvider)
      },
    )

    val binder = controller.onBind(null)

    assertNotNull(binder)
    assertTrue(foregroundTargets.isEmpty())
  }

  @Test
  fun runtimeServiceBootstrapSkipsStartCommandWhenOwnerLeaseIsNotHeld() {
    val executionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val wakeDispatcher = RecordingRuntimeServiceWakeCommandDispatcher()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val bootstrap = OpenCrayAgentRuntimeServiceBootstrap(
      shellControlBundle = defaultShellControlBundle(),
      transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
        gatewayBundle = testServiceGatewayBundle(),
      ),
      executionCoordinator = executionCoordinator,
      wakeCommandDispatcher = wakeDispatcher,
      binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
      projectionCoordinator = projectionCoordinator,
    )

    bootstrap.onStartCommand(Intent("runtime-shell-start"), startId = 12)

    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertTrue(executionCoordinator.startIds.isEmpty())
    assertEquals(0, wakeDispatcher.dispatchCallCount)
  }

  @Test
  fun runtimeServiceShellControllerSchedulesOwnerLeaseRetryWhenStartCommandLosesLease() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val executionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val wakeDispatcher = RecordingRuntimeServiceWakeCommandDispatcher()
    val retryTargets = mutableListOf<RuntimeServiceTarget>()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquireResults = listOf(true, false, false),
    )
    val bootstrap = OpenCrayAgentRuntimeServiceBootstrap(
      shellControlBundle = defaultShellControlBundle(),
      transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
        gatewayBundle = testServiceGatewayBundle(),
      ),
      executionCoordinator = executionCoordinator,
      wakeCommandDispatcher = wakeDispatcher,
      binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
      projectionCoordinator = projectionCoordinator,
    )
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ -> bootstrap },
      ownerLeaseRetryScheduler = { target -> retryTargets += target },
    )

    val attached = controller.attach()
    val startResult = controller.onStartCommand(
      intent = Intent("runtime-shell-start"),
      startId = 12,
    )

    assertTrue(attached)
    assertEquals(Service.START_NOT_STICKY, startResult)
    assertEquals(listOf(RuntimeServiceTarget.DETACHED_BACKGROUND), retryTargets)
    assertEquals(3, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertEquals(1, executionCoordinator.attachCallCount)
    assertTrue(executionCoordinator.startIds.isEmpty())
    assertEquals(0, wakeDispatcher.dispatchCallCount)
  }

  @Test
  fun runtimeServiceBootstrapAttachFailsWhenOwnerLeaseIsNotHeld() {
    val executionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val steps = mutableListOf<String>()
    val bootstrap = OpenCrayAgentRuntimeServiceBootstrap(
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
        attach = { steps += "shell_attach" },
      ),
      transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
        gatewayBundle = testServiceGatewayBundle(),
        ensureStarted = {
          steps += "transport_started"
          true
        },
      ),
      executionCoordinator = executionCoordinator,
      wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
      binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
      projectionCoordinator = projectionCoordinator,
    )

    val attached = bootstrap.attach()

    assertEquals(RuntimeServiceShellAttachResult.OwnerLeaseDenied, attached)
    assertTrue(steps.isEmpty())
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertEquals(0, projectionCoordinator.startCallCount)
    assertEquals(0, executionCoordinator.attachCallCount)
  }

  @Test
  fun runtimeServiceBootstrapAttachFailsWhenTransportDoesNotStart() {
    val executionCoordinator = RecordingRuntimeServiceExecutionCoordinator()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val steps = mutableListOf<String>()
    val bootstrap = OpenCrayAgentRuntimeServiceBootstrap(
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
        attach = { steps += "shell_attach" },
      ),
      transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
        gatewayBundle = testServiceGatewayBundle(),
        ensureStarted = {
          steps += "transport_started"
          false
        },
      ),
      executionCoordinator = executionCoordinator,
      wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
      binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
      projectionCoordinator = projectionCoordinator,
    )

    val attached = bootstrap.attach()

    assertEquals(RuntimeServiceShellAttachResult.TransportStartFailed, attached)
    assertEquals(listOf("transport_started"), steps)
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertEquals(0, projectionCoordinator.startCallCount)
    assertEquals(0, executionCoordinator.attachCallCount)
    assertEquals(1, projectionCoordinator.persistCallCount)
  }

  @Test
  fun runtimeServiceShellControllerReturnsNullBinderWhenOwnerLeaseIsNotHeld() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    val retryTargets = mutableListOf<RuntimeServiceTarget>()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        steps += "assemble_bootstrap"
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
            attach = { steps += "shell_attach" },
            dispose = { steps += "shell_dispose" },
          ),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
            ensureStarted = {
              steps += "transport_started"
              true
            },
            dispose = { steps += "transport_dispose" },
          ),
          executionCoordinator = object : RuntimeServiceExecutionCoordinator {
            override fun attach() = Unit

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
              steps += "coordinator_dispose"
            }
          },
          wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
          projectionCoordinator = projectionCoordinator,
        )
      },
      ownerLeaseRetryScheduler = { target -> retryTargets += target },
    )

    val bound = controller.onBind(null)
    val startFailure = runCatching {
      controller.onStartCommand(Intent("runtime-shell-start"), startId = 9)
    }.exceptionOrNull()

    assertNull(bound)
    assertEquals(
      listOf(
        "assemble_bootstrap",
        "shell_dispose",
        "transport_dispose",
        "coordinator_dispose",
      ),
      steps,
    )
    assertEquals(listOf(RuntimeServiceTarget.DETACHED_BACKGROUND), retryTargets)
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertEquals(0, projectionCoordinator.startCallCount)
    assertTrue(startFailure is IllegalStateException)
  }

  @Test
  fun runtimeServiceShellControllerReturnsNullBinderWhenTransportDoesNotStart() {
    val context = MinimalContext()
    val mainHandler = Handler()
    val service = TestRuntimeService()
    val steps = mutableListOf<String>()
    val retryTargets = mutableListOf<RuntimeServiceTarget>()
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator()
    val controller = runtimeServiceShellController(
      service = service,
      appContext = context,
      mainHandler = mainHandler,
      bootstrapDependencies = defaultRuntimeBootstrapDependencies,
      serviceBootstrapFactory = { _, _, _, _ ->
        steps += "assemble_bootstrap"
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
            attach = { steps += "shell_attach" },
            dispose = { steps += "shell_dispose" },
          ),
          transportBootstrap = OpenCrayRuntimeServiceTransportBootstrap(
            gatewayBundle = testServiceGatewayBundle(),
            ensureStarted = {
              steps += "transport_started"
              false
            },
            dispose = { steps += "transport_dispose" },
          ),
          executionCoordinator = object : RuntimeServiceExecutionCoordinator {
            override fun attach() {
              steps += "coordinator_attach"
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
              steps += "coordinator_dispose"
            }
          },
          wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
          binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
          projectionCoordinator = projectionCoordinator,
        )
      },
      ownerLeaseRetryScheduler = { target -> retryTargets += target },
    )

    val bound = controller.onBind(null)
    val startFailure = runCatching {
      controller.onStartCommand(Intent("runtime-shell-start"), startId = 9)
    }.exceptionOrNull()

    assertNull(bound)
    assertEquals(
      listOf(
        "assemble_bootstrap",
        "transport_started",
        "shell_dispose",
        "transport_dispose",
        "coordinator_dispose",
      ),
      steps,
    )
    assertTrue(retryTargets.isEmpty())
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
    assertEquals(0, projectionCoordinator.startCallCount)
    assertTrue(startFailure is IllegalStateException)
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
          true
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
              true
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
      binderEndpointFactory = { _, endpointProvider ->
        TestDelegatingRuntimeServiceBinderEndpoint(endpointProvider)
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
      binderEndpointFactory = { _, endpointProvider ->
        TestDelegatingRuntimeServiceBinderEndpoint(endpointProvider)
      },
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
              true
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
              true
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
  fun runtimeServiceShellControllerReturnsNotStickyWhenOwnerLeaseIsNotHeld() {
    val projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(
      ownerLeaseAcquired = false,
    )
    val bootstrap = OpenCrayAgentRuntimeServiceBootstrap(
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
      wakeCommandDispatcher = RecordingRuntimeServiceWakeCommandDispatcher(),
      binderEndpoint = RecordingRuntimeServiceBinderEndpoint(),
      projectionCoordinator = projectionCoordinator,
    )

    val startResult = runtimeServiceStartResult(bootstrap)

    assertEquals(Service.START_NOT_STICKY, startResult)
    assertEquals(1, projectionCoordinator.ownerLeaseAcquireCallCount)
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
    val adapterTargets = mutableListOf<RuntimeServiceTarget>()
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
      runtimeForegroundServiceAdapterFactory = { _, _, target ->
        adapterTargets += target
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
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      retainedShellControl = retainedShellControl,
    )

    bundle.attach()
    retainedShellControl.keepAliveController.onStartCommand(41)
    scheduledActions.single().invoke()

    assertEquals(listOf(41), stopRequestStartIds)
    assertEquals(listOf(RuntimeServiceTarget.DETACHED_BACKGROUND), adapterTargets)
    assertNotEquals(
      runtimeActiveForegroundNotificationId(RuntimeServiceTarget.INTERACTIVE),
      runtimeActiveForegroundNotificationId(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
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
}
