package com.opencray.app

import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.media.EmptyMediaSpeechSettingsFacade
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.notifications.EmptyNotificationSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.EmptyPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.shell.AppShellStateStore
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

internal fun OpenCrayHostRuntime.Companion.createForTest(
  stateStore: AppShellStateStore,
  chatSessionStore: ChatSessionLocalStore,
  settingsFacade: SettingsFacade,
  notificationSettingsFacade: NotificationSettingsFacade = EmptyNotificationSettingsFacade,
  networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
  mediaSpeechSettingsFacade: MediaSpeechSettingsFacade = EmptyMediaSpeechSettingsFacade,
  sandboxSettingsRepository: SandboxSettingsRepository? = null,
  llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
  personalizationFacade: PersonalizationFacade = EmptyPersonalizationFacade,
  personalizationLocalStore: PersonalizationLocalStore? = null,
  workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  mcpSettingsFacade: McpSettingsFacade = EmptyMcpSettingsFacade,
  safetySettingsFacade: SafetySettingsFacade = EmptySafetySettingsFacade,
  skillsFacade: SkillsFacade = EmptySkillsFacade,
  workspaceRootProvider: (() -> Path)? = null,
  workspaceEntryOpener: ((Path, String) -> Unit)? = null,
  externalUriOpener: ((String) -> Unit)? = null,
  approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
    workspaceRootProvider?.invoke()?.let { workspaceRoot ->
      val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
      ApprovedReadRootsSnapshot(
        roots = setOf(normalizedWorkspaceRoot),
        summary = "workspace=${normalizedWorkspaceRoot.toString().replace('\\', '/')}",
      )
    } ?: ApprovedReadRootsSnapshot(
      roots = emptySet(),
      summary = "workspace=unavailable",
    )
  },
  workspaceSnapshotProvider: () -> Map<String, Any?> = {
    WorkspaceTreeSnapshot(
      rootName = AppAgentWorkspace.DIRECTORY_NAME,
      rootPath = AppAgentWorkspace.DIRECTORY_NAME,
      availableBytes = 0L,
      directoryCount = 0,
      fileCount = 0,
      entryCount = 0,
      isTruncated = false,
      children = emptyList(),
    ).toMap()
  },
  strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
    NoOpStrongBackgroundSettingsAccess,
  voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
    AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null },
  voiceMetadataBackfillExecutor: Executor = inlineExecutorForHostRuntimeTest(),
  voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
  sessionRuntimeManager: AgentSessionRuntimeManager,
  runEventJournalStoreFactory: RunEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
  promptCheckpointStoreFactory: PromptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
  subAgentSessionLinkStoreFactory: SubAgentSessionLinkStoreFactory =
    inMemorySubAgentSessionLinkStoreFactory(),
  supplementStoreFactory: AgentSessionSupplementStoreFactory = inMemorySupplementStoreFactoryForHostRuntimeTest(),
  todoSnapshotProvider: (String) -> ChatSessionTodoPresentation = {
    ChatSessionTodoPresentation.empty()
  },
  transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
  approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
  directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
  memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  approvalReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
  approvalApprovedReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
  subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
  runCancellationReplayRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _ -> },
  terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
  strings: HostRuntimeStrings = HostRuntimeStrings(
    localeTag = "en",
    shellHostLabel = "HOST CONNECTED",
    shellHostSummary = "Android host bridge is attached to the live app runtime.",
    chatScreenTitle = "Chat",
    chatModeLabel = "AUTO",
    chatModeSafeLabel = "SAFE",
    chatModeDeveloperLabel = "DEV",
    chatSessionButtonLabel = "Sessions",
    chatRecentSessionsEyebrow = "Recent sessions",
    chatRecentSessionsTitle = "Recent sessions",
    chatNewSessionLabel = "New session",
    chatDefaultSessionTitle = "New chat",
    chatMessagesBadge = { count -> "$count messages" },
    chatSummaryReplyInProgress = "Reply in progress",
    chatSummaryAwaitingDirection = "Waiting for your next instruction.",
    chatSummarySupplementRecorded = "Recorded. This will be applied to the current run when it reaches the next safe checkpoint.",
    chatSummaryApprovalFollowUpRecorded = "Recorded. The current run is waiting for approval, so this message will be handled after that decision.",
    chatSummaryStartNewSession = "Start a new session",
    chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
    skillInstalled = { skillId -> "Installed $skillId." },
    skillRemoved = { skillId -> "Removed $skillId." },
    skillsReloaded = "Reloaded skills from local storage.",
    composerPlaceholder = "Message OpenCray",
    composerRejectedPlaceholder = "Tell OpenCray differently",
    agentThinking = "Thinking",
    agentCancelled = "Interrupted",
    agentMissingLlm = "Missing LLM",
    agentEmptyAnswer = "The model returned an empty answer.",
    agentFailed = { _, detail -> "Failed: $detail" },
    chatApprovalApproveForSessionLabel = "Allow session",
    chatApprovalApprovedForSession = "Approval granted for this session. The agent is resuming.",
  ),
  mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor = lifecycleDescriptor,
  runtimeControllerDescriptor: RuntimeControllerLifecycleDescriptor? = null,
  runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
  localRuntimeServerStateProvider: () -> LocalRuntimeServerState? =
    { defaultLocalRuntimeServerState() },
  runtimeServiceWorkState: RuntimeServiceWorkState? = null,
  runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState? = {
    runtimeServiceWorkState
  },
  runtimeServiceKeepAliveState: RuntimeServiceKeepAliveState? = null,
  runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = {
    runtimeServiceKeepAliveState
  },
  runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
  runtimeServiceConnectionState: RuntimeServiceConnectionState? = null,
  runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = {
    runtimeServiceConnectionState
  },
  runtimeServiceConnectionChangeRegistrar: RuntimeServiceConnectionChangeRegistrar? = null,
  onDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
  resumeActiveSessionOnInit: Boolean = true,
): OpenCrayHostRuntime {
  val runtimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
    lifecycleDescriptor = runtimeOwnerDescriptor,
    sessionRuntimeManager = sessionRuntimeManager,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    supplementStoreFactory = supplementStoreFactory,
    approvalRegistry = approvalRegistry,
  )
  val runtimeDiagnosticsBridge = HostRuntimeDiagnosticsBridge.create(
    runtimeOwnerDescriptor = runtimeOwnerDescriptor,
    runtimeControllerDescriptor = runtimeControllerDescriptor,
    runtimeServiceDescriptor = runtimeServiceDescriptor,
    localRuntimeServerStateProvider = localRuntimeServerStateProvider,
    runtimeServiceWorkState = runtimeServiceWorkState,
    runtimeServiceWorkStateProvider = runtimeServiceWorkStateProvider,
    runtimeServiceKeepAliveState = runtimeServiceKeepAliveState,
    runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
    runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
    runtimeServiceConnectionState = runtimeServiceConnectionState,
    runtimeServiceConnectionStateProvider = runtimeServiceConnectionStateProvider,
    runtimeServiceConnectionChangeRegistrar = runtimeServiceConnectionChangeRegistrar,
  )
  return createWithRuntimeAccess(
    appContext = null,
    stateStore = stateStore,
    chatSessionStore = chatSessionStore,
    settingsFacade = settingsFacade,
    notificationSettingsFacade = notificationSettingsFacade,
    networkSearchConfigFacade = networkSearchConfigFacade,
    mediaSpeechSettingsFacade = mediaSpeechSettingsFacade,
    sandboxSettingsRepository = sandboxSettingsRepository,
    llmConfigFacade = llmConfigFacade,
    personalizationFacade = personalizationFacade,
    personalizationLocalStore = personalizationLocalStore,
    workspaceSoulProfileStore = workspaceSoulProfileStore,
    mcpSettingsFacade = mcpSettingsFacade,
    safetySettingsFacade = safetySettingsFacade,
    skillsFacade = skillsFacade,
    workspaceRootProvider = workspaceRootProvider,
    workspaceEntryOpener = workspaceEntryOpener,
    externalUriOpener = externalUriOpener,
    approvedReadRootsProvider = approvedReadRootsProvider,
    workspaceSnapshotProvider = workspaceSnapshotProvider,
    strongBackgroundSettingsAccess = strongBackgroundSettingsAccess,
    voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    voiceMetadataBackfillExecutor = voiceMetadataBackfillExecutor,
    voiceMetadataCacheStore = voiceMetadataCacheStore,
    runtimeHostAccess = runtimeHostAccess,
    subAgentSessionLinkStoreFactory = subAgentSessionLinkStoreFactory,
    todoSnapshotProvider = todoSnapshotProvider,
    transcriptMessagesProvider = transcriptMessagesProvider,
    directTaskRuntimeFactory = directTaskRuntimeFactory,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    approvalReplayRecorder = approvalReplayRecorder,
    approvalApprovedReplayRecorder = approvalApprovedReplayRecorder,
    subAgentReplayRecorder = subAgentReplayRecorder,
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    terminalReplayRepairer = terminalReplayRepairer,
    strings = strings,
    mainThreadPoster = mainThreadPoster,
    lifecycleDescriptor = lifecycleDescriptor,
    onDeviceLlmWarmupController = onDeviceLlmWarmupController,
    runtimeDiagnosticsBridge = runtimeDiagnosticsBridge,
    resumeActiveSessionOnInit = resumeActiveSessionOnInit,
  )
}

private fun inMemorySupplementStoreFactoryForHostRuntimeTest(): AgentSessionSupplementStoreFactory =
  object : AgentSessionSupplementStoreFactory {
    private val stores = ConcurrentHashMap<String, SessionSupplementStore>()

    override fun forChatSession(sessionId: String): SessionSupplementStore =
      stores.computeIfAbsent(sessionId) { InMemorySessionSupplementStore() }
  }

private fun inlineExecutorForHostRuntimeTest(): Executor = Executor { command ->
  command.run()
}
