package com.opencray.app

import android.content.Context

internal class RuntimeServiceExecutionController(
  val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor?,
  private val bootstrapAssembly: RuntimeServiceBootstrapAssembly,
  private val disposeHandler: () -> Unit = {},
) {
  private val disposeLock = Any()
  private var disposed: Boolean = false

  fun toRuntimeServiceBootstrapState(
    serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  ): RuntimeServiceBootstrapState = bootstrapAssembly.toRuntimeServiceBootstrapState(
    serviceLifecycle = serviceLifecycle,
  )

  fun replaceRuntimeOwner(): RuntimeOwnerBootstrap = bootstrapAssembly.replaceRuntimeOwner()

  fun localRuntimeServerState(): LocalRuntimeServerState? =
    bootstrapAssembly.localRuntimeServerStateProvider()

  fun projectionCoordinator(): RuntimeServiceProjectionCoordinator =
    bootstrapAssembly.projectionCoordinator

  fun serviceWorkStateTracker(): RuntimeServiceWorkStateTracker =
    bootstrapAssembly.serviceWorkStateTracker

  fun transportCoordinator(): RuntimeServiceTransportCoordinator =
    bootstrapAssembly.transportCoordinator

  fun retainedShellControl(): RuntimeServiceRetainedShellControl =
    bootstrapAssembly.retainedShellControl

  fun dispose() {
    val handler = synchronized(disposeLock) {
      if (disposed) {
        null
      } else {
        disposed = true
        disposeHandler
      }
    } ?: return
    try {
      bootstrapAssembly.dispose()
    } finally {
      handler()
    }
  }
}

internal fun interface RuntimeServiceExecutionControllerProvider {
  fun resolve(context: Context): RuntimeServiceExecutionController
}

internal fun interface RuntimeServiceExecutionControllerResolver {
  fun resolve(
    context: Context,
    target: RuntimeServiceTarget,
  ): RuntimeServiceExecutionController
}

internal fun createRuntimeServiceExecutionController(
  appContext: Context,
  runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  runtimeServiceProcessBootstrap: (Context) -> Unit = { context ->
    bootstrapOpenCrayRuntimeServiceProcessSupport(context)
  },
  localRuntimeServerStateProvider: (() -> LocalRuntimeServerState?)? = null,
  runtimeServiceRetainedShellControlFactory: (Context) -> RuntimeServiceRetainedShellControl =
    ::createRuntimeServiceRetainedShellControl,
  runtimeControllerIdentityStore: RuntimeControllerIdentityStore =
    inMemoryRuntimeControllerIdentityStore(),
  runtimeExecutionDependenciesLoader: RuntimeExecutionDependenciesLoader,
  runtimeOwnerBootstrapProvider: RuntimeOwnerBootstrapProvider,
  bootstrapFactory: RuntimeServiceBootstrapFactory =
    DefaultRuntimeServiceBootstrapFactory,
): RuntimeServiceExecutionController {
  val applicationContext = appContext.applicationContext
  val resolvedLocalRuntimeServerStateProvider = localRuntimeServerStateProvider
    ?: { defaultLocalRuntimeServerState(runtimeTarget) }
  runtimeServiceProcessBootstrap(applicationContext)
  val executionDependencies = runtimeExecutionDependenciesLoader.load(applicationContext)
  val runtimeOwnerDependencies = executionDependencies.runtimeOwnerBootstrapDependencies
  val runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
    durableControllerId = runtimeControllerIdentityStore.controllerIdForTarget(runtimeTarget),
  )
  val runtimeOwnerBootstrap = runtimeOwnerBootstrapProvider.resolve(
    runtimeOwnerDependencies,
    runtimeControllerLifecycle,
  )
  val retainedOwnerState = RuntimeServiceRetainedOwnerState(
    initialBootstrap = runtimeOwnerBootstrap,
    replacementBootstrapProvider = { currentBootstrap ->
      runtimeOwnerBootstrapProvider.replace(
        runtimeOwnerDependencies,
        runtimeControllerLifecycle,
        currentBootstrap,
      )
    },
    finalBootstrapDisposer = runtimeOwnerBootstrapProvider::disposeRetainedBootstrap,
  )
  return RuntimeServiceExecutionController(
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    bootstrapAssembly = createRuntimeServiceBootstrapAssembly(
      appContext = executionDependencies.appContext,
      bootstrapContext = executionDependencies.bootstrapContext,
      retainedOwnerState = retainedOwnerState,
      runtimeTarget = runtimeTarget,
      runtimeControllerLifecycle = runtimeControllerLifecycle,
      localRuntimeServerStateProvider = resolvedLocalRuntimeServerStateProvider,
      retainedShellControlFactory = runtimeServiceRetainedShellControlFactory,
      bootstrapFactory = bootstrapFactory,
    ),
    disposeHandler = retainedOwnerState::dispose,
  )
}

internal class ProcessScopedRuntimeServiceExecutionControllerProvider(
  private val runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  private val runtimeServiceProcessBootstrap: (Context) -> Unit = { context ->
    bootstrapOpenCrayRuntimeServiceProcessSupport(context)
  },
  private val localRuntimeServerStateProvider: (() -> LocalRuntimeServerState?)? = null,
  private val runtimeServiceRetainedShellControlFactory: (Context) -> RuntimeServiceRetainedShellControl =
    ::createRuntimeServiceRetainedShellControl,
  private val runtimeControllerIdentityStoreProvider: (Context) -> RuntimeControllerIdentityStore =
    defaultRuntimeControllerIdentityStoreProvider(),
  private val runtimeExecutionDependenciesLoader: RuntimeExecutionDependenciesLoader,
  private val runtimeOwnerBootstrapProvider: RuntimeOwnerBootstrapProvider,
  private val bootstrapFactory: RuntimeServiceBootstrapFactory =
    DefaultRuntimeServiceBootstrapFactory,
) : RuntimeServiceExecutionControllerProvider {
  @Volatile
  private var controller: RuntimeServiceExecutionController? = null

  override fun resolve(context: Context): RuntimeServiceExecutionController =
    controller ?: synchronized(this) {
      controller ?: createRuntimeServiceExecutionController(
        appContext = context.applicationContext,
        runtimeTarget = runtimeTarget,
        runtimeServiceProcessBootstrap = runtimeServiceProcessBootstrap,
        localRuntimeServerStateProvider = localRuntimeServerStateProvider
          ?: { defaultLocalRuntimeServerState(runtimeTarget) },
        runtimeServiceRetainedShellControlFactory = runtimeServiceRetainedShellControlFactory,
        runtimeControllerIdentityStore = runtimeControllerIdentityStoreProvider(
          context.applicationContext,
        ),
        runtimeExecutionDependenciesLoader = runtimeExecutionDependenciesLoader,
        runtimeOwnerBootstrapProvider = runtimeOwnerBootstrapProvider,
        bootstrapFactory = bootstrapFactory,
      ).also { created ->
        controller = created
      }
    }
}

internal class TargetScopedRuntimeServiceExecutionControllerResolver(
  private val providerFactory: (RuntimeServiceTarget) -> ProcessScopedRuntimeServiceExecutionControllerProvider,
) : RuntimeServiceExecutionControllerResolver {
  private val lock = Any()
  private val providers = linkedMapOf<RuntimeServiceTarget, ProcessScopedRuntimeServiceExecutionControllerProvider>()

  override fun resolve(
    context: Context,
    target: RuntimeServiceTarget,
  ): RuntimeServiceExecutionController = providerFor(target).resolve(context)

  private fun providerFor(
    target: RuntimeServiceTarget,
  ): ProcessScopedRuntimeServiceExecutionControllerProvider = synchronized(lock) {
    providers.getOrPut(target) { providerFactory(target) }
  }
}

internal fun defaultRuntimeServiceExecutionControllerResolver(
  runtimeExecutionDependenciesLoader: RuntimeExecutionDependenciesLoader,
  runtimeOwnerBootstrapProvider: RuntimeOwnerBootstrapProvider,
  runtimeControllerIdentityStoreProvider: (Context) -> RuntimeControllerIdentityStore =
    defaultRuntimeControllerIdentityStoreProvider(),
): RuntimeServiceExecutionControllerResolver =
  TargetScopedRuntimeServiceExecutionControllerResolver(
    providerFactory = { target ->
      ProcessScopedRuntimeServiceExecutionControllerProvider(
        runtimeTarget = target,
        runtimeControllerIdentityStoreProvider = runtimeControllerIdentityStoreProvider,
        runtimeExecutionDependenciesLoader = runtimeExecutionDependenciesLoader,
        runtimeOwnerBootstrapProvider = runtimeOwnerBootstrapProvider,
      )
    },
  )

private fun defaultRuntimeControllerIdentityStoreProvider():
  (Context) -> RuntimeControllerIdentityStore {
  val store = inMemoryRuntimeControllerIdentityStore()
  return { store }
}
