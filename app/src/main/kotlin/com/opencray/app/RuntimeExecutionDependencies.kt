package com.opencray.app

import android.content.Context
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillsFacade
import java.nio.file.Path

internal data class RuntimeOwnerBootstrapDependencies(
  val appContext: Context,
  val llmSettingsStore: LlmSettingsStore,
  val sandboxSettingsRepository: SandboxSettingsRepository,
  val personalizationStore: PersonalizationLocalStore,
  val chatSessionStore: ChatSessionLocalStore,
  val skillsFacade: SkillsFacade,
  val mcpSettingsFacade: McpSettingsFacade,
  val webSearchSettingsStore: WebSearchSettingsStore,
  val providerUserAgent: String,
  val workspaceRootProvider: () -> Path,
  val workspaceRootsProvider: () -> Set<Path>,
  val soulProfileStore: WorkspaceSoulProfileStore,
  val liveContextModeStore: LiveContextModeStore,
  val mediaSpeechSettingsStore: MediaSpeechSettingsStore,
  val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
)

internal data class RuntimeExecutionDependencies(
  val appContext: Context,
  val bootstrapContext: RuntimeServiceBootstrapContext,
  val runtimeOwnerBootstrapDependencies: RuntimeOwnerBootstrapDependencies,
)

internal fun runtimeOwnerBootstrapDependencies(
  dependencies: OpenCrayRuntimeContextDependencies,
): RuntimeOwnerBootstrapDependencies = RuntimeOwnerBootstrapDependencies(
  appContext = dependencies.appContext,
  llmSettingsStore = dependencies.llmSettingsStore,
  sandboxSettingsRepository = dependencies.sandboxSettingsRepository,
  personalizationStore = dependencies.personalizationStore,
  chatSessionStore = dependencies.chatSessionStore,
  skillsFacade = dependencies.skillsFacade,
  mcpSettingsFacade = dependencies.mcpSettingsFacade,
  webSearchSettingsStore = dependencies.webSearchSettingsStore,
  providerUserAgent = dependencies.providerUserAgent,
  workspaceRootProvider = dependencies.workspaceRootProvider,
  workspaceRootsProvider = dependencies.workspaceRootsProvider,
  soulProfileStore = dependencies.soulProfileStore,
  liveContextModeStore = dependencies.liveContextModeStore,
  mediaSpeechSettingsStore = dependencies.mediaSpeechSettingsStore,
  approvedReadRootsProvider = dependencies.approvedReadRootsProvider,
)

internal fun runtimeExecutionDependencies(
  dependencies: OpenCrayRuntimeContextDependencies,
): RuntimeExecutionDependencies = RuntimeExecutionDependencies(
  appContext = dependencies.appContext,
  bootstrapContext = runtimeServiceBootstrapContext(dependencies),
  runtimeOwnerBootstrapDependencies = runtimeOwnerBootstrapDependencies(dependencies),
)

internal fun loadRuntimeExecutionDependencies(
  appContext: Context,
  runtimeEnvironment: OpenCrayRuntimeServiceEnvironment,
): RuntimeExecutionDependencies {
  val localizedContext = OpenCrayLocaleManager.wrap(appContext)
  val llmSettingsStore = LlmSettingsStore.fromContext(appContext)
  val sandboxSettingsRepository = SandboxSettingsRepository.fromContext(appContext)
  val personalizationStore = PersonalizationLocalStore.fromContext(appContext)
  val chatSessionStore = ChatSessionLocalStore.fromContext(appContext)
  val skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
  val mcpSettingsStore = McpSettingsStore.fromContext(appContext)
  val mcpRegistryStore = AppMcpRegistryStore.fromContext(appContext)
  val webSearchSettingsStore = WebSearchSettingsStore.fromContext(appContext)
  val providerUserAgent = OpenCrayUserAgent.fromContext(appContext)
  val workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(appContext) }
  val workspaceRootsProvider = { setOf(workspaceRootProvider()) }
  val soulProfileStore = WorkspaceSoulProfileStore()
  val liveContextModeStore = LiveContextModeStore.fromContext(appContext)
  val safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext)
  val mediaSpeechSettingsStore = MediaSpeechSettingsStore.fromContext(appContext)
  val approvedReadRootsProvider = {
    ApprovedReadRootsResolver.resolve(
      context = appContext,
      workspaceRoot = workspaceRootProvider(),
      safetySettings = safetySettingsFacade.load(),
    )
  }
  return RuntimeExecutionDependencies(
    appContext = appContext,
    bootstrapContext = RuntimeServiceBootstrapContext(
      localizedContext = localizedContext,
      chatSessionStore = chatSessionStore,
      safetySettingsFacade = safetySettingsFacade,
      workspaceRootProvider = workspaceRootProvider,
      approvedReadRootsProvider = approvedReadRootsProvider,
      runtimeServiceAccessGateway = runtimeEnvironment.runtimeServiceAccessGateway,
      chatRuntimeWriteTargetResolverFactory = runtimeEnvironment.chatRuntimeWriteTargetResolverFactory,
    ),
    runtimeOwnerBootstrapDependencies = RuntimeOwnerBootstrapDependencies(
      appContext = appContext,
      llmSettingsStore = llmSettingsStore,
      sandboxSettingsRepository = sandboxSettingsRepository,
      personalizationStore = personalizationStore,
      chatSessionStore = chatSessionStore,
      skillsFacade = skillsFacade,
      mcpSettingsFacade = LocalMcpSettingsFacade.create(
        context = localizedContext,
        settingsStore = mcpSettingsStore,
        registryStore = mcpRegistryStore,
      ),
      webSearchSettingsStore = webSearchSettingsStore,
      providerUserAgent = providerUserAgent,
      workspaceRootProvider = workspaceRootProvider,
      workspaceRootsProvider = workspaceRootsProvider,
      soulProfileStore = soulProfileStore,
      liveContextModeStore = liveContextModeStore,
      mediaSpeechSettingsStore = mediaSpeechSettingsStore,
      approvedReadRootsProvider = approvedReadRootsProvider,
    ),
  )
}
