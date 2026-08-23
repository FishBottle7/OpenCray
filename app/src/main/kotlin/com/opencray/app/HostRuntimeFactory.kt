package com.opencray.app

import android.content.Context
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.shell.AppShellStateStore
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import java.nio.file.Path
import java.util.concurrent.Executor

internal object HostRuntimeFactory {
  internal fun createWithRuntimeAccess(
    appContext: Context? = null,
    stateStore: AppShellStateStore,
    chatSessionStore: ChatSessionLocalStore,
    settingsFacade: SettingsFacade,
    notificationSettingsFacade: NotificationSettingsFacade,
    networkSearchConfigFacade: NetworkSearchConfigFacade,
    mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
    sandboxSettingsRepository: SandboxSettingsRepository?,
    llmConfigFacade: LlmConfigFacade,
    personalizationFacade: PersonalizationFacade,
    personalizationLocalStore: PersonalizationLocalStore?,
    workspaceSoulProfileStore: WorkspaceSoulProfileStore,
    mcpSettingsFacade: McpSettingsFacade,
    safetySettingsFacade: SafetySettingsFacade,
    skillsFacade: SkillsFacade,
    workspaceRootProvider: (() -> Path)? = null,
    workspaceEntryOpener: ((Path, String) -> Unit)? = null,
    externalUriOpener: ((String) -> Unit)? = null,
    approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
    workspaceSnapshotProvider: () -> Map<String, Any?>,
    strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess,
    voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer,
    voiceMetadataBackfillExecutor: Executor,
    voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
    runtimeHostAccess: OpenCrayRuntimeHostAccess,
    subAgentSessionLinkStoreFactory: SubAgentSessionLinkStoreFactory =
      inMemorySubAgentSessionLinkStoreFactory(),
    todoSnapshotProvider: (String) -> ChatSessionTodoPresentation,
    transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage>,
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
      chatSummaryOnDevicePreparing = "Preparing the on-device model.",
      chatSummaryAwaitingDirection = "Waiting for your next instruction.",
      chatSummarySupplementRecorded = "Recorded. This will be applied to the current run when it reaches the next safe checkpoint.",
      chatSummaryApprovalFollowUpRecorded = "Recorded. The current run is waiting for approval, so this message will be handled after that decision.",
      chatSummaryStartNewSession = "Start a new session",
      chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
      skillInstalled = { skillId -> "Installed $skillId." },
      skillRemoved = { skillId -> "Removed $skillId." },
      skillsReloaded = "Reloaded skills from local storage.",
      composerPlaceholder = "Message OpenCray",
      chatMessageOnDevicePreparing = "Preparing on-device model",
      composerRejectedPlaceholder = "Tell OpenCray differently",
      agentThinking = "Thinking",
      agentCancelled = "Interrupted",
      agentMissingLlm = "Missing LLM",
      agentEmptyAnswer = "The model returned an empty answer.",
      agentFailed = { detail -> "Failed: $detail" },
      chatApprovalApproveForSessionLabel = "Allow session",
      chatApprovalApprovedForSession = "Approval granted for this session. The agent is resuming.",
    ),
    mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    runtimeDiagnosticsBridge: HostRuntimeDiagnosticsBridge =
      HostRuntimeDiagnosticsBridge(runtimeOwnerDescriptor = lifecycleDescriptor),
    resumeActiveSessionOnInit: Boolean = true,
    onDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
  ): OpenCrayHostRuntime {
    return OpenCrayHostRuntime(
      appContext = appContext,
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
      providedOnDeviceLlmWarmupController = onDeviceLlmWarmupController,
      lifecycleDescriptor = lifecycleDescriptor,
      runtimeDiagnosticsBridge = runtimeDiagnosticsBridge,
      resumeActiveSessionOnInit = resumeActiveSessionOnInit,
    )
  }
}
