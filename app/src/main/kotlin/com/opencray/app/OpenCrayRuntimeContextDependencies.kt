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
)

internal fun loadOpenCrayRuntimeContextDependencies(
  appContext: Context,
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
    mcpSettingsFacade = LocalMcpSettingsFacade.createForTest(
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
  )
}

internal fun ensureInProcessRuntimeOwner(
  dependencies: OpenCrayRuntimeContextDependencies,
): InProcessOpenCrayRuntimeOwner =
  InProcessOpenCrayRuntimeOwnerRegistry.getOrCreate {
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
