package com.opencray.app

import android.content.Context

internal fun interface RuntimeExecutionDependenciesLoader {
  fun load(appContext: Context): RuntimeExecutionDependencies
}

internal fun interface LocalHostGatewayDependenciesLoader {
  fun load(appContext: Context): LocalHostGatewayDependencies
}

internal fun interface RuntimeOwnerBootstrapFactory {
  fun create(
    dependencies: RuntimeOwnerBootstrapDependencies,
    runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
  ): RuntimeOwnerBootstrap
}

private object DefaultRuntimeOwnerBootstrapFactory : RuntimeOwnerBootstrapFactory {
  override fun create(
    dependencies: RuntimeOwnerBootstrapDependencies,
    runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
  ): RuntimeOwnerBootstrap = createRetainedInProcessOpenCrayRuntimeOwnerCore(
    appContext = dependencies.appContext,
    llmSettingsStore = dependencies.llmSettingsStore,
    sandboxSettingsRepository = dependencies.sandboxSettingsRepository,
    personalizationStore = dependencies.personalizationStore,
    chatSessionStore = dependencies.chatSessionStore,
    skillsFacade = dependencies.skillsFacade,
    mcpSettingsFacade = dependencies.mcpSettingsFacade,
    liveContextModeStore = dependencies.liveContextModeStore,
    mediaSpeechSettingsStore = dependencies.mediaSpeechSettingsStore,
    webSearchSettingsStore = dependencies.webSearchSettingsStore,
    providerUserAgent = dependencies.providerUserAgent,
    workspaceRootProvider = dependencies.workspaceRootProvider,
    workspaceRootsProvider = dependencies.workspaceRootsProvider,
    approvedReadRootsProvider = dependencies.approvedReadRootsProvider,
    soulProfileStore = dependencies.soulProfileStore,
    runtimeControllerLifecycle = runtimeControllerLifecycle,
  )
    .toRuntimeOwnerBootstrap()
}

internal fun interface RuntimeOwnerBootstrapProvider {
  fun resolve(
    dependencies: RuntimeOwnerBootstrapDependencies,
    runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
  ): RuntimeOwnerBootstrap

  fun replace(
    dependencies: RuntimeOwnerBootstrapDependencies,
    runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
    currentBootstrap: RuntimeOwnerBootstrap,
  ): RuntimeOwnerBootstrap = currentBootstrap.retainedHandle?.createReplacementBootstrap()
    ?: resolve(
      dependencies = dependencies,
      runtimeControllerLifecycle = runtimeControllerLifecycle,
    )

  fun disposeRetainedBootstrap(
    bootstrap: RuntimeOwnerBootstrap,
  ) {
    bootstrap.retainedHandle?.disposeRetainedOwner() ?: bootstrap.dispose()
  }
}

private object DefaultRuntimeOwnerBootstrapProvider : RuntimeOwnerBootstrapProvider {
  override fun resolve(
    dependencies: RuntimeOwnerBootstrapDependencies,
    runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
  ): RuntimeOwnerBootstrap = createRuntimeOwnerBootstrap(
    dependencies = dependencies,
    runtimeControllerLifecycle = runtimeControllerLifecycle,
  )
}

internal fun createRuntimeOwnerBootstrap(
  dependencies: RuntimeOwnerBootstrapDependencies,
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor,
  runtimeOwnerBootstrapFactory: RuntimeOwnerBootstrapFactory = DefaultRuntimeOwnerBootstrapFactory,
): RuntimeOwnerBootstrap = runtimeOwnerBootstrapFactory.create(
  dependencies = dependencies,
  runtimeControllerLifecycle = runtimeControllerLifecycle,
)

internal fun defaultRuntimeExecutionDependenciesLoader(
  runtimeEnvironment: OpenCrayRuntimeServiceEnvironment,
): RuntimeExecutionDependenciesLoader = RuntimeExecutionDependenciesLoader { appContext ->
  loadRuntimeExecutionDependencies(
    appContext = appContext,
    runtimeEnvironment = runtimeEnvironment,
  )
}

internal fun defaultLocalHostGatewayDependenciesLoader(
): LocalHostGatewayDependenciesLoader = LocalHostGatewayDependenciesLoader { appContext ->
  loadLocalHostGatewayDependencies(
    appContext = appContext,
  )
}

internal fun defaultRuntimeOwnerBootstrapProvider():
  RuntimeOwnerBootstrapProvider = DefaultRuntimeOwnerBootstrapProvider
