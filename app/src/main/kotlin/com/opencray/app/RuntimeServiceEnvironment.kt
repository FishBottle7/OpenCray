package com.opencray.app

import android.content.Context

internal class OpenCrayRuntimeServiceEnvironment(
  val projectionHostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val runtimeServiceAccessGateway: RuntimeServiceAccessGateway =
    DefaultRuntimeServiceAccessGateway(defaultRuntimeServiceAccessDependencies()),
  executionControllerResolver: RuntimeServiceExecutionControllerResolver? = null,
  runtimeServiceBootstrapDependenciesProvider:
    (() -> RuntimeServiceBootstrapDependencies)? = null,
  val defaultClientRuntimeServiceTarget: RuntimeServiceTarget =
    DEFAULT_CLIENT_RUNTIME_SERVICE_TARGET,
  private val localHostGatewayProvider:
    ((Context) -> OpenCrayLocalHostGateway)? = null,
  private val projectionGatewayBundleFactoryProvider:
    (() -> OpenCrayProjectionGatewayBundleFactory)? = null,
  private val serviceBackedGatewayBundleFactoryProvider:
    (() -> OpenCrayServiceBackedGatewayBundleFactory)? = null,
  private val clientGatewayBundleFactoryProvider:
    (() -> OpenCrayClientGatewayBundleFactory)? = null,
  private val chatRuntimeWriteTargetResolverFactoryProvider:
    (() -> ChatRuntimeWriteTargetResolverFactory)? = null,
  runtimeExecutionDependenciesLoader: RuntimeExecutionDependenciesLoader? = null,
  localHostGatewayDependenciesLoader: LocalHostGatewayDependenciesLoader? = null,
  runtimeOwnerBootstrapProvider: RuntimeOwnerBootstrapProvider? = null,
  private val runtimeControllerIdentityStoreProvider:
    ((Context) -> RuntimeControllerIdentityStore)? = null,
) {
  private val executionControllerResolverOverride = executionControllerResolver
  private val runtimeServiceBootstrapDependenciesProviderOverride =
    runtimeServiceBootstrapDependenciesProvider
  private val runtimeExecutionDependenciesLoaderOverride = runtimeExecutionDependenciesLoader
  private val localHostGatewayDependenciesLoaderOverride = localHostGatewayDependenciesLoader
  private val runtimeOwnerBootstrapProviderOverride = runtimeOwnerBootstrapProvider

  val projectionGatewayBundleFactory: OpenCrayProjectionGatewayBundleFactory by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    projectionGatewayBundleFactoryProvider?.invoke()
      ?: openCrayProjectionGatewayBundleFactory(projectionHostLifecycleDescriptor)
  }

  val serviceBackedGatewayBundleFactory: OpenCrayServiceBackedGatewayBundleFactory by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    serviceBackedGatewayBundleFactoryProvider?.invoke()
      ?: ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
        runtimeServiceClientProvider = { context, target ->
          runtimeServiceAccessGateway.ensureClient(
            context = context.applicationContext,
            target = target,
          )
        },
        projectionGatewayBundleFactory = projectionGatewayBundleFactory,
      )
  }

  val chatRuntimeWriteTargetResolverFactory: ChatRuntimeWriteTargetResolverFactory by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    chatRuntimeWriteTargetResolverFactoryProvider?.invoke()
      ?: openCrayChatRuntimeWriteTargetResolverFactory(defaultClientRuntimeServiceTarget)
  }

  val runtimeExecutionDependenciesLoader: RuntimeExecutionDependenciesLoader by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    runtimeExecutionDependenciesLoaderOverride
      ?: defaultRuntimeExecutionDependenciesLoader(this)
  }

  val localHostGatewayDependenciesLoader: LocalHostGatewayDependenciesLoader by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    localHostGatewayDependenciesLoaderOverride
      ?: defaultLocalHostGatewayDependenciesLoader()
  }

  val runtimeOwnerBootstrapProvider: RuntimeOwnerBootstrapProvider by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    runtimeOwnerBootstrapProviderOverride
      ?: defaultRuntimeOwnerBootstrapProvider()
  }

  val executionControllerResolver: RuntimeServiceExecutionControllerResolver by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    executionControllerResolverOverride
      ?: defaultRuntimeServiceExecutionControllerResolver(
        runtimeExecutionDependenciesLoader = this.runtimeExecutionDependenciesLoader,
        runtimeOwnerBootstrapProvider = this.runtimeOwnerBootstrapProvider,
        runtimeControllerIdentityStoreProvider =
          runtimeControllerIdentityStoreProvider
            ?: defaultEnvironmentRuntimeControllerIdentityStoreProvider(),
      )
  }

  val runtimeServiceBootstrapDependencies: RuntimeServiceBootstrapDependencies by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    runtimeServiceBootstrapDependenciesProviderOverride?.invoke()
      ?: defaultRuntimeServiceBootstrapDependencies(
        runtimeEnvironment = this,
        executionControllerResolver = this.executionControllerResolver,
      )
  }

  val clientGatewayBundleFactory: OpenCrayClientGatewayBundleFactory by lazy(
    LazyThreadSafetyMode.NONE,
  ) {
    clientGatewayBundleFactoryProvider?.invoke()
      ?: ConfigurableOpenCrayClientGatewayBundleFactory(
        localHostGatewayProvider = { context ->
          localHostGateway(context.applicationContext)
        },
        serviceBackedGatewayBundleFactory = serviceBackedGatewayBundleFactory,
      )
  }

  @Volatile
  private var resolvedLocalHostGateway: OpenCrayLocalHostGateway? = null

  fun localHostGateway(appContext: Context): OpenCrayLocalHostGateway {
    resolvedLocalHostGateway?.let { existing ->
      return existing
    }
    return synchronized(this) {
      resolvedLocalHostGateway ?: (
        localHostGatewayProvider?.invoke(appContext.applicationContext)
          ?: createOpenCrayLocalHostGateway(
            context = appContext.applicationContext,
            dependencies = localHostGatewayDependenciesLoader.load(appContext.applicationContext),
          )
        ).also { created ->
          resolvedLocalHostGateway = created
        }
    }
  }
}

private fun defaultEnvironmentRuntimeControllerIdentityStoreProvider():
  (Context) -> RuntimeControllerIdentityStore {
  val store = inMemoryRuntimeControllerIdentityStore()
  return { store }
}

internal interface OpenCrayRuntimeServiceEnvironmentOwner {
  val openCrayRuntimeServiceEnvironment: OpenCrayRuntimeServiceEnvironment
}

internal fun openCrayRuntimeServiceEnvironment(
  context: Context,
): OpenCrayRuntimeServiceEnvironment {
  val appContext = context.applicationContext
  return when (appContext) {
    is OpenCrayRuntimeServiceEnvironmentOwner -> appContext.openCrayRuntimeServiceEnvironment
    is OpenCrayApplication -> appContext.openCrayRuntimeServiceEnvironment
    else -> error(
      "OpenCray runtime environment requires an application context that implements " +
        "OpenCrayRuntimeServiceEnvironmentOwner or OpenCrayApplication.",
    )
  }
}
