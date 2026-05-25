package com.opencray.app

import android.content.Context
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillsFacade
import java.nio.file.Path

internal data class OpenCrayRuntimeContextDependencies(
  val appContext: Context,
  val localizedContext: Context,
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
  val voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore?,
  val soulProfileStore: WorkspaceSoulProfileStore,
  val liveContextModeStore: LiveContextModeStore,
  val safetySettingsFacade: SafetySettingsFacade,
  val mediaSpeechSettingsStore: MediaSpeechSettingsStore,
  val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  val workspaceSnapshotProvider: () -> Map<String, Any?>,
  val runtimeServiceAccessGateway: RuntimeServiceAccessGateway,
  val chatRuntimeWriteTargetResolverFactory: ChatRuntimeWriteTargetResolverFactory,
)

internal data class LocalHostGatewayDependencies(
  val workspaceRootProvider: () -> Path,
  val workspaceSnapshotProvider: () -> Map<String, Any?>,
)

internal fun localHostGatewayDependencies(
  dependencies: OpenCrayRuntimeContextDependencies,
): LocalHostGatewayDependencies = LocalHostGatewayDependencies(
  workspaceRootProvider = dependencies.workspaceRootProvider,
  workspaceSnapshotProvider = dependencies.workspaceSnapshotProvider,
)

internal fun loadLocalHostGatewayDependencies(
  appContext: Context,
): LocalHostGatewayDependencies {
  val workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(appContext) }
  val workspaceSnapshotProvider = {
    AppAgentWorkspaceSnapshotFactory.createSnapshot(
      workspaceRootProvider(),
    ).toMap()
  }
  return LocalHostGatewayDependencies(
    workspaceRootProvider = workspaceRootProvider,
    workspaceSnapshotProvider = workspaceSnapshotProvider,
  )
}

internal fun loadOpenCrayRuntimeContextDependencies(
  appContext: Context,
  runtimeEnvironment: OpenCrayRuntimeServiceEnvironment,
): OpenCrayRuntimeContextDependencies {
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
  val voiceMetadataCacheStore = AppAgentWorkspaceVoiceMetadataCacheStore.fromWorkspaceRoot(
    workspaceRootProvider(),
  )
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
  val workspaceSnapshotProvider = {
    AppAgentWorkspaceSnapshotFactory.createSnapshot(
      workspaceRootProvider(),
    ).toMap()
  }
  return OpenCrayRuntimeContextDependencies(
    appContext = appContext,
    localizedContext = localizedContext,
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
    voiceMetadataCacheStore = voiceMetadataCacheStore,
    soulProfileStore = soulProfileStore,
    liveContextModeStore = liveContextModeStore,
    safetySettingsFacade = safetySettingsFacade,
    mediaSpeechSettingsStore = mediaSpeechSettingsStore,
    approvedReadRootsProvider = approvedReadRootsProvider,
    workspaceSnapshotProvider = workspaceSnapshotProvider,
    runtimeServiceAccessGateway = runtimeEnvironment.runtimeServiceAccessGateway,
    chatRuntimeWriteTargetResolverFactory = runtimeEnvironment.chatRuntimeWriteTargetResolverFactory,
  )
}
