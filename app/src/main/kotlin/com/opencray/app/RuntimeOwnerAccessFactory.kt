package com.opencray.app

internal fun interface OpenCrayRuntimeOwnerAccessFactory {
  fun create(
    dependencies: OpenCrayRuntimeContextDependencies,
  ): OpenCrayRuntimeOwnerAccess
}

internal fun interface InProcessRuntimeOwnerProvider {
  fun getOrCreate(
    dependencies: OpenCrayRuntimeContextDependencies,
  ): InProcessOpenCrayRuntimeOwner
}

private object RegistryBackedInProcessRuntimeOwnerProvider : InProcessRuntimeOwnerProvider {
  override fun getOrCreate(
    dependencies: OpenCrayRuntimeContextDependencies,
  ): InProcessOpenCrayRuntimeOwner = InProcessOpenCrayRuntimeOwnerRegistry.getOrCreate {
    createInProcessOpenCrayRuntimeOwner(
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
    )
  }
}

internal data class RuntimeOwnerAccessDependencies(
  val inProcessRuntimeOwnerProvider: InProcessRuntimeOwnerProvider,
)

private fun defaultRuntimeOwnerAccessDependencies(): RuntimeOwnerAccessDependencies =
  RuntimeOwnerAccessDependencies(
    inProcessRuntimeOwnerProvider = RegistryBackedInProcessRuntimeOwnerProvider,
  )

internal object RuntimeOwnerAccessRegistry {
  @Volatile
  private var accessDependencies: RuntimeOwnerAccessDependencies =
    defaultRuntimeOwnerAccessDependencies()

  internal fun resolveAccessDependencies(): RuntimeOwnerAccessDependencies = accessDependencies

  internal fun resolveRuntimeOwnerAccess(
    dependencies: OpenCrayRuntimeContextDependencies,
  ): OpenCrayRuntimeOwnerAccess = resolveAccessDependencies()
    .inProcessRuntimeOwnerProvider
    .getOrCreate(dependencies)
    .toRuntimeOwnerAccess()

  internal fun setInProcessRuntimeOwnerProviderForTest(
    provider: InProcessRuntimeOwnerProvider?,
  ) {
    accessDependencies = accessDependencies.copy(
      inProcessRuntimeOwnerProvider =
        provider ?: defaultRuntimeOwnerAccessDependencies().inProcessRuntimeOwnerProvider,
    )
  }

  internal fun setAccessDependenciesForTest(
    dependencies: RuntimeOwnerAccessDependencies?,
  ) {
    accessDependencies = dependencies ?: defaultRuntimeOwnerAccessDependencies()
  }

  internal fun clearForTest() {
    accessDependencies = defaultRuntimeOwnerAccessDependencies()
  }
}

internal object DefaultOpenCrayRuntimeOwnerAccessFactory : OpenCrayRuntimeOwnerAccessFactory {
  override fun create(
    dependencies: OpenCrayRuntimeContextDependencies,
  ): OpenCrayRuntimeOwnerAccess =
    RuntimeOwnerAccessRegistry.resolveRuntimeOwnerAccess(dependencies)
}
