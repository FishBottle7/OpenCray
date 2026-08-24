package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.OnDeviceLlmModelOptionSnapshot
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.skills.SkillInstallRequestResult
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentInterpreter
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import com.opencray.runtime.skills.SkillPackageBatchInstallAttempt
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillSourceInspectionAttempt
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile
import com.opencray.runtime.subagent.SubAgentHandleState
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class HostRuntimeTestBase {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  internal fun semanticUserCandidateExtractor(): MemoryCandidateExtractor =
    MemoryCandidateExtractor(
      userIntentInterpreter = object : UserMemoryIntentInterpreter {
        override fun interpret(
          request: UserMemoryIntentRequest,
        ): UserMemoryIntentInterpretation = UserMemoryIntentInterpretation.Success(
          intents = buildList {
            if (request.userInput.contains("Simplified Chinese", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to Simplified Chinese for explanations",
                ),
              )
            }
            if (request.userInput.contains("git reset --hard", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.DURABLE_INSTRUCTION,
                  scope = MemoryScope.WORKSPACE,
                  content = "Do not use git reset --hard in this repo",
                ),
              )
            }
          },
        )
      },
    )

  internal fun hostRuntime(
    chatStore: ChatSessionLocalStore,
    runtimeManager: AgentSessionRuntimeManager,
    networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
    mediaSpeechSettingsFacade: com.opencray.app.facade.media.MediaSpeechSettingsFacade =
      com.opencray.app.facade.media.EmptyMediaSpeechSettingsFacade,
    sandboxSettingsRepository: SandboxSettingsRepository? = null,
    llmConfigFacade: LlmConfigFacade = RecordingLlmConfigFacade(),
    onDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
    personalizationFacade: PersonalizationFacade = RecordingPersonalizationFacade(),
    personalizationLocalStore: PersonalizationLocalStore? = null,
    mcpSettingsFacade: McpSettingsFacade = RecordingMcpSettingsFacade(),
    safetySettingsFacade: SafetySettingsFacade = RecordingSafetySettingsFacade(),
    skillsFacade: SkillsFacade = TestSkillsFacade(),
    directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
    memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
    workspaceRootProvider: (() -> Path)? = null,
    approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
      ApprovedReadRootsSnapshot(roots = emptySet(), summary = "workspace=unavailable")
    },
    todoSnapshotProvider: (String) -> ChatSessionTodoPresentation = {
      ChatSessionTodoPresentation.empty()
    },
    transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
    approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
    approvalReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
    approvalApprovedReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
    subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
    runCancellationReplayRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _ -> },
    terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
    supplementStoreFactory: AgentSessionSupplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = mutableMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    },
    mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
    workspaceEntryOpener: ((Path, String) -> Unit)? = null,
    externalUriOpener: ((String) -> Unit)? = null,
    voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
      AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null },
    voiceMetadataBackfillExecutor: Executor = Executor { command -> command.run() },
    voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
    runEventJournalStoreFactory: RunEventJournalStoreFactory =
      hostRuntimeTestRunEventJournalStoreFactory(),
    promptCheckpointStoreFactory: PromptCheckpointStoreFactory =
      hostRuntimeTestPromptCheckpointStoreFactory(),
    subAgentSessionLinkStoreFactory: SubAgentSessionLinkStoreFactory =
      inMemorySubAgentSessionLinkStoreFactory(),
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor = lifecycleDescriptor,
    runtimeControllerDescriptor: RuntimeControllerLifecycleDescriptor? = null,
    runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
    localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = {
      defaultLocalRuntimeServerState()
    },
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
    resumeActiveSessionOnInit: Boolean = true,
  ): OpenCrayHostRuntime = OpenCrayHostRuntime.createForTest(
    stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
    chatSessionStore = chatStore,
    settingsFacade = NoOpSettingsFacade,
    networkSearchConfigFacade = networkSearchConfigFacade,
    mediaSpeechSettingsFacade = mediaSpeechSettingsFacade,
    sandboxSettingsRepository = sandboxSettingsRepository,
    llmConfigFacade = llmConfigFacade,
    personalizationFacade = personalizationFacade,
    personalizationLocalStore = personalizationLocalStore,
    mcpSettingsFacade = mcpSettingsFacade,
    safetySettingsFacade = safetySettingsFacade,
    skillsFacade = skillsFacade,
    sessionRuntimeManager = runtimeManager,
    directTaskRuntimeFactory = directTaskRuntimeFactory,
    supplementStoreFactory = supplementStoreFactory,
    workspaceRootProvider = workspaceRootProvider,
    workspaceEntryOpener = workspaceEntryOpener,
    externalUriOpener = externalUriOpener,
    approvedReadRootsProvider = approvedReadRootsProvider,
    voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    voiceMetadataBackfillExecutor = voiceMetadataBackfillExecutor,
    voiceMetadataCacheStore = voiceMetadataCacheStore,
    todoSnapshotProvider = todoSnapshotProvider,
    transcriptMessagesProvider = transcriptMessagesProvider,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    subAgentSessionLinkStoreFactory = subAgentSessionLinkStoreFactory,
    approvalRegistry = approvalRegistry,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    approvalReplayRecorder = approvalReplayRecorder,
    approvalApprovedReplayRecorder = approvalApprovedReplayRecorder,
    subAgentReplayRecorder = subAgentReplayRecorder,
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    terminalReplayRepairer = terminalReplayRepairer,
    mainThreadPoster = mainThreadPoster,
    lifecycleDescriptor = lifecycleDescriptor,
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
    resumeActiveSessionOnInit = resumeActiveSessionOnInit,
    onDeviceLlmWarmupController = onDeviceLlmWarmupController,
    strings = HostRuntimeStrings(
      localeTag = "en",
      shellHostLabel = "HOST CONNECTED",
      shellHostSummary = "Android host bridge is attached to the live app runtime.",
      chatScreenTitle = "Chat",
      chatModeLabel = "AUTO",
      chatSessionButtonLabel = "Sessions",
      chatRecentSessionsEyebrow = "Recent sessions",
      chatRecentSessionsTitle = "Recent sessions",
      chatNewSessionLabel = "New session",
      chatDefaultSessionTitle = "New chat",
      chatMessagesBadge = { count -> "$count messages" },
      chatSummaryReplyInProgress = "Reply in progress",
      chatSummaryOnDevicePreparing = "Preparing the on-device model.",
      chatSummaryAwaitingDirection = "Waiting for your next instruction.",
      chatSummaryStartNewSession = "Start a new session",
      chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
      skillInstalled = { skillId -> "Installed $skillId." },
      skillRemoved = { skillId -> "Removed $skillId." },
      skillsReloaded = "Reloaded skills from local storage.",
      composerPlaceholder = "Message OpenCray",
      chatMessageOnDevicePreparing = "Preparing on-device model",
      composerRejectedPlaceholder = "Tell OpenCray differently",
      agentThinking = "Thinking",
      agentCancelled = "Cancelled",
      agentMissingLlm = "Missing LLM",
      agentEmptyAnswer = "The model returned an empty answer.",
      agentFailed = { detail -> "Failed: $detail" },
    ),
  )

  internal fun managedProcessSnapshotForRun(
    runtimeSnapshot: Map<String, Any?>,
    runId: String,
    stdoutPreview: String? = null,
  ): Map<*, *> {
    val activeRuns = runtimeSnapshot["activeRuns"] as? List<*> ?: emptyList<Any?>()
    val activeRun = activeRuns
      .mapNotNull { item -> item as? Map<*, *> }
      .firstOrNull { run -> run["runId"] == runId }
      ?: throw AssertionError("Missing active run $runId.")
    val managedProcess = (activeRun["managedProcesses"] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
      .firstOrNull()
      ?: throw AssertionError("Missing managed process for run $runId.")
    if (
      stdoutPreview != null &&
      !(managedProcess["stdoutPreview"] as? String).orEmpty().contains(
        stdoutPreview,
      )
    ) {
      throw AssertionError(
        "Managed process for run $runId did not contain stdoutPreview $stdoutPreview.",
      )
    }
    return managedProcess
  }

  internal object NoOpSettingsFacade : SettingsFacade {
    override fun loadOverview(): SettingsOverviewSnapshot = SettingsOverviewSnapshot(
      eyebrow = "",
      title = "",
      subtitle = "",
      deviceTitle = "",
      deviceSummary = "",
      entries = emptyList(),
    )

    override fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot = SettingsDetailSnapshot(
      routeId = routeId,
      title = "",
      subtitle = "",
      sections = emptyList(),
    )
  }

  internal class TestSkillsFacade : SkillsFacade {
    var lastLoadedQuery: String? = null
    var lastSuggestedLimit: Int? = null
    var lastInstalledSourceRef: String? = null
    var lastInstalledSelectedSkillName: String? = null
    var lastBatchInstalledSourceRef: String? = null
    var lastBatchInstalledSkillNames: List<String>? = null
    var lastInspectedSourceRef: String? = null
    var lastDeletedSkillId: String? = null
    var lastCheckedSkillId: String? = null
    var lastUpdatedSkillId: String? = null
    var lastSuggestedInstructionsSourceRef: String? = null
    var lastSuggestedInstructionsSkillName: String? = null
    var snapshot: SkillsSnapshot = SkillsSnapshot(
      installedSkills = emptyList(),
      installSources = emptyList(),
      suggestedSkills = emptyList(),
    )
    var installResult: SkillInstallRequestResult = SkillInstallRequestResult(
      errorMessage = "Not configured.",
    )
    var batchInstallResult: SkillPackageBatchInstallAttempt = SkillPackageBatchInstallAttempt(
      errorCode = "NOT_CONFIGURED",
      errorMessage = "Not configured.",
    )
    var inspectResult: SkillSourceInspectionAttempt = SkillSourceInspectionAttempt(
      errorCode = "NOT_CONFIGURED",
      errorMessage = "Not configured.",
    )
    var deleteResult: Boolean = true
    var checkReport: SkillPackageCheckReport = SkillPackageCheckReport(results = emptyList())
    var updateReport: SkillPackageUpdateReport = SkillPackageUpdateReport(results = emptyList())
    var suggestedInstructions: SkillInstructionsSnapshot? = null

    override fun loadSnapshot(query: String, suggestedLimit: Int): SkillsSnapshot {
      lastLoadedQuery = query
      lastSuggestedLimit = suggestedLimit
      return snapshot
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = true

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): SkillInstallRequestResult {
      lastInstalledSourceRef = sourceRef
      lastInstalledSelectedSkillName = selectedSkillName
      return installResult
    }

    override fun installSuggestedSkill(skillId: String): Boolean =
      installSkillSource(skillId, "").succeeded

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): SkillPackageBatchInstallAttempt {
      lastBatchInstalledSourceRef = sourceRef
      lastBatchInstalledSkillNames = selectedSkillNames
      return batchInstallResult
    }

    override fun inspectSkillSource(sourceRef: String): SkillSourceInspectionAttempt {
      lastInspectedSourceRef = sourceRef
      return inspectResult
    }

    override fun deleteInstalledSkill(skillId: String): Boolean {
      lastDeletedSkillId = skillId
      return deleteResult
    }

    override fun refresh() = Unit

    override fun checkInstalledSkillUpdates(skillId: String): SkillPackageCheckReport {
      lastCheckedSkillId = skillId
      return checkReport
    }

    override fun updateInstalledSkill(skillId: String): SkillPackageUpdateReport {
      lastUpdatedSkillId = skillId
      return updateReport
    }

    override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

    override fun loadSuggestedInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): SkillInstructionsSnapshot? {
      lastSuggestedInstructionsSourceRef = sourceRef
      lastSuggestedInstructionsSkillName = selectedSkillName
      return suggestedInstructions
    }

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = sourceId
  }

  internal class NoOpRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = RecordingSessionHandle(
      sessionId = sessionId,
    )

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = {}

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  internal class RecordingDirectTaskRuntimeFactory(
    private val status: ExecutionStatus,
    private val stdout: String = "",
    private val errorMessage: String? = null,
  ) : AgentSessionTaskRuntimeFactory {
    var lastSessionId: String? = null
    val submittedTasks = mutableListOf<AgentTask>()
    var lastTask: AgentTask? = null

    override fun create(
      sessionId: String,
      eventSink: com.opencray.runtime.OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task, _ ->
      lastSessionId = sessionId
      submittedTasks += task
      lastTask = task
      ExecutionResult(
        taskId = task.id,
        status = status,
        stdout = if (status == ExecutionStatus.SUCCESS) stdout else "",
        stderr = if (status == ExecutionStatus.SUCCESS) "" else (errorMessage ?: stdout),
        errorMessage = if (status == ExecutionStatus.SUCCESS) null else errorMessage,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_000L,
      )
    }
  }

  internal class RecordingLlmConfigFacade(
    private val snapshot: LlmConfigSnapshot = EmptyLlmConfigFacade.load(),
    private val validationResult: LlmValidationResult = LlmValidationResult(
      isSuccess = false,
      message = "Not configured.",
    ),
    private val onValidate: (() -> Unit)? = null,
  ) : LlmConfigFacade {
    var lastSavedCustomRequest: SaveCustomLlmProviderRequest? = null

    override fun load(): LlmConfigSnapshot = snapshot

    override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
      throw UnsupportedOperationException("save is not used in this test")

    override fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot {
      lastSavedCustomRequest = request
      return LlmConfigSnapshot(
        localeTag = "en",
        enabled = true,
        providerId = "custom",
        selectedProviderOptionId = "custom-saved",
        protocol = request.protocol,
        providerOptions = emptyList(),
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        helperText = "Helper",
      )
    }

    override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult {
      onValidate?.invoke()
      return validationResult
    }

    override fun downloadOnDeviceModel(modelId: String): LlmConfigSnapshot = load()

    override fun cancelOnDeviceModelDownload(modelId: String): LlmConfigSnapshot = load()

    override fun deleteOnDeviceModel(modelId: String): LlmConfigSnapshot = load()
  }

  internal class RecordingOnDeviceLlmWarmupController(
    private val state: OnDeviceLlmWarmupState,
  ) : OnDeviceLlmWarmupController {
    var lastSpec: OnDeviceLlmWarmupSpec? = null
      private set

    override fun ensureWarm(spec: OnDeviceLlmWarmupSpec): OnDeviceLlmWarmupState {
      lastSpec = spec
      return state
    }

    override fun clear(): OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()
  }

  internal fun readyOnDeviceLlmConfigSnapshot(): LlmConfigSnapshot = EmptyLlmConfigFacade.load().copy(
    enabled = true,
    providerMode = LlmProviderModes.ON_DEVICE_MODEL,
    selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
    onDeviceModels = listOf(
      OnDeviceLlmModelOptionSnapshot(
        id = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        title = "Gemma 4 E2B",
        subtitle = "Ready",
        sizeLabel = "2.6 GB",
        fileSizeBytes = 2_580_000_000L,
        installState = OnDeviceLlmDownloadStates.READY,
        sha256Verified = true,
        isSelected = true,
      ),
    ),
    onDeviceAccelerator = OnDeviceLlmAccelerators.GPU,
    onDeviceMaxContextWindow = 32_768,
    onDeviceMaxTokens = 4_096,
    onDeviceTopK = 40,
    onDeviceTopP = 0.95,
    onDeviceTemperature = 0.7,
    onDeviceThinkingEnabled = true,
  )

  internal class RecordingPersonalizationFacade : PersonalizationFacade {
    var lastSaveRequest: SavePersonalizationConfigRequest? = null

    override fun load(): PersonalizationConfigSnapshot = snapshot()

    override fun save(request: SavePersonalizationConfigRequest): PersonalizationConfigSnapshot {
      lastSaveRequest = request
      return snapshot(
        selectedPresetId = request.presetId,
        customLabel = request.customLabel,
        customGuidance = request.customGuidance,
      )
    }

    override fun setAppLanguage(languageId: String): PersonalizationConfigSnapshot =
      snapshot(selectedAppLanguageId = languageId)

    override fun reset(scope: PersonalizationResetScope): PersonalizationConfigSnapshot =
      snapshot(lastResetMessage = scope.wireValue)

    private fun snapshot(
      selectedPresetId: String = "steady",
      customLabel: String = "",
      customGuidance: String = "",
      selectedAppLanguageId: String = "en",
      lastResetMessage: String? = null,
    ): PersonalizationConfigSnapshot = PersonalizationConfigSnapshot(
      title = "Personalization",
      subtitle = "Tune voice",
      introTitle = "Shape how OpenCray sounds",
      introBody = "Body",
      introHelper = "Helper",
      presetsTitle = "Presets",
      presetsHelper = "Presets helper",
      presets = listOf(
        PersonalizationPresetSnapshot(
          id = selectedPresetId,
          title = "Preset",
          summary = "Summary",
          voice = "Voice",
          status = "Selected",
          isSelected = true,
        ),
      ),
      selectedPresetId = selectedPresetId,
      customOverlayTitle = "Overlay",
      customOverlayHelper = "Overlay helper",
      customLabelHint = "Label hint",
      customLabelHelper = "Label helper",
      customGuidanceHint = "Guidance hint",
      customGuidanceHelper = "Guidance helper",
      customLabel = customLabel,
      customGuidance = customGuidance,
      behaviorDefaultsTitle = "Behavior defaults",
      appLanguageTitle = "App language",
      appLanguageOptions = listOf(
        PersonalizationLanguageOptionSnapshot(
          id = "en",
          title = "English",
          isSelected = selectedAppLanguageId == "en",
        ),
        PersonalizationLanguageOptionSnapshot(
          id = "zh-CN",
          title = "中文",
          isSelected = selectedAppLanguageId == "zh-CN",
        ),
      ),
      selectedAppLanguageId = selectedAppLanguageId,
      livePreviewTitle = "Preview",
      livePreviewName = customLabel.ifBlank { "Preset" },
      livePreviewSummary = customGuidance.ifBlank { "Preview summary" },
      queueTitle = "Idle",
      queueBody = "Queue body",
      queueIsIdle = true,
      lastResetTitle = "Latest reset result",
      lastResetMessage = lastResetMessage,
      resetActions = listOf(
        PersonalizationResetActionSnapshot(
          scope = PersonalizationResetScope.MEMORY,
          title = "Reset memory",
          scopeBody = "Memory scope",
          retainBody = "Retain",
          confirmationToken = "RESET MEMORY",
          inputHint = "Type RESET MEMORY",
          disabledGuidance = "Disabled",
          typeExactGuidance = "Type exact",
          armedGuidance = "Armed",
          isInputEnabled = true,
        ),
      ),
    )
  }

  internal class RecordingMcpSettingsFacade : McpSettingsFacade {
    var lastServerToggle: Pair<String, Boolean>? = null

    override fun load(): McpSettingsSnapshot = snapshot()

    override fun setMasterEnabled(enabled: Boolean): McpSettingsSnapshot = snapshot(
      masterEnabled = enabled,
    )

    override fun setServerEnabled(serverId: String, enabled: Boolean): McpSettingsSnapshot {
      lastServerToggle = serverId to enabled
      return snapshot(
        summaryLine = "Enabled 3 • Blocked 0 • Attention 1",
      )
    }

    override fun currentExposureReport() =
      com.opencray.mcp.McpClientExposureReport(
        activeClients = emptyList(),
        blockedClients = emptyList(),
      )

    private fun snapshot(
      masterEnabled: Boolean = true,
      summaryLine: String = "Enabled 2 • Blocked 1 • Attention 2",
    ): McpSettingsSnapshot = McpSettingsSnapshot(
      title = "MCP",
      subtitle = "Control server discovery",
      masterTitle = "Enable MCP integrations",
      masterSummary = "Trusted servers follow this master switch.",
      masterEnabled = masterEnabled,
      summaryLine = summaryLine,
      serversTitle = "Per-server controls live here",
      serversHelper = "This page lists per-server status and actions. Today the runtime only exposes server visibility through mcp_list_servers; remote MCP tools are not proxied into the agent yet.",
      masterDisabledTitle = null,
      masterDisabledBody = null,
      servers = listOf(
        McpServerSettingsSnapshot(
          id = "community-bridge",
          title = "Community Bridge",
          statusLabel = "Blocked",
          statusTone = "blocked",
          trustLine = "Trust: Requires manual enable",
          authLine = "Auth: Credential configured",
          readinessLine = "Readiness: Ready",
          transportLine = "Transport: Remote SSE",
          exposureLine = "Exposure: Blocked",
          guidance = "Blocked until you enable this server manually. Exposure stays hidden until you consent here, and remote MCP tools are not proxied yet.",
          actionLabel = "Enable server",
          actionTurnsOn = true,
          isActionEnabled = true,
        ),
      ),
    )
  }

  internal class RecordingSafetySettingsFacade(
    var snapshot: SafetySettingsSnapshot = defaultSafetySettingsSnapshot(),
  ) : SafetySettingsFacade {
    var lastSavedRequest: SaveSafetySettingsRequest? = null

    override fun load(): SafetySettingsSnapshot = snapshot

    override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
      lastSavedRequest = request
      snapshot = defaultSafetySettingsSnapshot().copy(
        automationMode = SafetyAutomationMode.fromWireValue(request.automationModeId),
        rollbackJournalEnabled = request.rollbackJournalEnabled,
        maxFilesPerBatch = request.maxFilesPerBatch,
        maxAgentTurns = request.maxAgentTurns,
        maxToolCalls = request.maxToolCalls,
        undoWindowHours = request.undoWindowHours,
        fileChangesPolicy = ToolPolicyOverride.fromWireValue(request.fileChangesPolicyId),
        fileDeletesPolicy = ToolPolicyOverride.fromWireValue(request.fileDeletesPolicyId),
        shellCommandsPolicy = ToolPolicyOverride.fromWireValue(request.shellCommandsPolicyId),
        externalAccessMode = ExternalAccessMode.fromWireValue(request.externalAccessModeId),
        locations = listOf(
          SafetySettingsLocationSnapshot("photo_library", request.photoLibraryEnabled),
          SafetySettingsLocationSnapshot("downloads", request.downloadsEnabled),
          SafetySettingsLocationSnapshot("documents", request.documentsEnabled),
          SafetySettingsLocationSnapshot("recordings", request.recordingsEnabled),
        ),
        workspaceAccessProfile = WorkspaceAccessProfile.fromWireValue(request.workspaceAccessProfileId),
        readOnlyOutsideWorkspace = request.readOnlyOutsideWorkspace,
        liveContextMode = LiveContextMode.fromWireValue(request.liveContextModeId),
        memoryToolsEnabled = request.memoryToolsEnabled,
      )
      return snapshot
    }
  }

  internal companion object {
    private val TOOL_NAME_REGEX: Regex = Regex("""\"tool_name\"\s*:\s*\"([^\"]+)\"""")

    fun defaultSafetySettingsSnapshot(): SafetySettingsSnapshot = SafetySettingsSnapshot(
      automationMode = SafetyAutomationMode.AUTO,
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
      maxToolCalls = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
      undoWindowHours = 24,
      fileChangesPolicy = ToolPolicyOverride.INHERIT,
      fileDeletesPolicy = ToolPolicyOverride.INHERIT,
      shellCommandsPolicy = ToolPolicyOverride.INHERIT,
      externalAccessMode = ExternalAccessMode.SELECT_PATHS,
      locations = listOf(
        SafetySettingsLocationSnapshot(id = "photo_library", enabled = true),
        SafetySettingsLocationSnapshot(id = "downloads", enabled = true),
        SafetySettingsLocationSnapshot(id = "documents", enabled = false),
        SafetySettingsLocationSnapshot(id = "recordings", enabled = false),
      ),
      workspaceAccessProfile = WorkspaceAccessProfile.WORK,
      readOnlyOutsideWorkspace = true,
      memoryToolsEnabled = true,
    )
  }

  internal class RecordingRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingSessionHandle>()
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val observerCount: Int
      get() = listeners.size
    val resumedSessionIds = mutableListOf<String>()
    val requestedSessionIds = mutableListOf<String>()
    val releasedSessionIds = mutableListOf<String>()
    var forceIdleWorkSummary: Boolean = false

    fun putHandle(handle: RecordingSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle {
      requestedSessionIds += sessionId
      return handlesBySession.getOrPut(sessionId) {
        RecordingSessionHandle(
          sessionId = sessionId,
          onResume = resumedSessionIds::add,
        )
      }
    }

    fun emitTaskStarted(
      sessionId: String,
      task: AgentTask,
    ) {
      listeners.forEach { listener ->
        listener.onTaskStarted(sessionId, task)
      }
    }

    fun emitTaskFinished(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      handlesBySession[sessionId]?.recordResult(task = task, result = result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId, task, result)
      }
    }

    fun emitTaskFinishedAfterListener(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId, task, result)
      }
      handlesBySession[sessionId]?.recordResult(task = task, result = result)
    }

    fun emitRunEvent(
      sessionId: String,
      task: AgentTask,
      event: com.opencray.runtime.OpenCrayAgentRunEvent,
    ) {
      handlesBySession[sessionId]?.recordEvent(event)
      listeners.forEach { listener ->
        listener.onRunEvent(sessionId, task, event)
        when (event) {
          is OpenCrayToolCallEvent -> listener.onToolCall(
            sessionId = sessionId,
            task = task,
            turn = event.turn,
            call = event.call,
          )
          is OpenCrayToolResultEvent -> listener.onToolResult(
            sessionId = sessionId,
            task = task,
            turn = event.turn,
            call = event.call,
            result = event.result,
          )
          else -> Unit
        }
      }
    }

    fun emitAssistantDraftUpdated(
      sessionId: String,
      task: AgentTask,
      text: String,
      emittedAtEpochMs: Long,
    ) {
      listeners.forEach { listener ->
        listener.onAssistantDraftUpdated(
          sessionId = sessionId,
          task = task,
          text = text,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    fun emitAssistantDraftCleared(
      sessionId: String,
      task: AgentTask,
      emittedAtEpochMs: Long,
    ) {
      listeners.forEach { listener ->
        listener.onAssistantDraftCleared(
          sessionId = sessionId,
          task = task,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary {
      if (forceIdleWorkSummary) {
        return RuntimeOwnerWorkSummary(trackedSessionCount = handlesBySession.size)
      }
      val activeSessionIds = linkedSetOf<String>()
      val pendingWorkSessionIds = mutableListOf<String>()
      val liveManagedProcessSessionIds = mutableListOf<String>()
      var activeRunCount = 0

      handlesBySession.values.forEach { handle ->
        val runs = handle.listRuns()
        val hasPendingWork = runs.any { snapshot -> !snapshot.isTerminal }
        val hasLiveManagedProcesses = runs.any(AgentRunSnapshot::hasLiveManagedProcesses)
        if (hasPendingWork) {
          pendingWorkSessionIds += handle.sessionId
          activeSessionIds += handle.sessionId
        }
        if (hasLiveManagedProcesses) {
          liveManagedProcessSessionIds += handle.sessionId
          activeSessionIds += handle.sessionId
        }
        activeRunCount += runs.count(AgentRunSnapshot::isActive)
      }

      return RuntimeOwnerWorkSummary(
        trackedSessionCount = handlesBySession.size,
        activeRunCount = activeRunCount,
        activeSessionIds = activeSessionIds.toList(),
        pendingWorkSessionIds = pendingWorkSessionIds.distinct(),
        liveManagedProcessSessionIds = liveManagedProcessSessionIds.distinct(),
      )
    }

    override fun release(sessionId: String) {
      releasedSessionIds += sessionId
      handlesBySession.remove(sessionId)
    }

    override fun releaseIdleSessions() = Unit
  }

  internal class SettlingRuntimeManager(
    private val sessionId: String,
  ) : AgentSessionRuntimeManager {
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val handle = SettlingSessionHandle(sessionId = sessionId)

    override fun forSession(sessionId: String): AgentSessionHandle {
      require(sessionId == this.sessionId) {
        "Unexpected sessionId: $sessionId"
      }
      return handle
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    fun emitTaskFinished(result: ExecutionResult) {
      val task = handle.requireSubmittedTask()
      handle.recordResult(result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId = sessionId, task = task, result = result)
      }
      handle.settleTerminalState()
    }
  }

  internal class SettlingSessionHandle(
    override val sessionId: String,
  ) : AgentSessionHandle {
    private var submittedTask: AgentTask? = null
    private var submission: AgentRunSubmission? = null
    private var lifecycleState: QueueTaskLifecycleState = QueueTaskLifecycleState.QUEUED
    private var result: ExecutionResult? = null

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      val runId = "settling-run"
      val task = AgentTask(
        id = "settling-task",
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecision,
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val createdSubmission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTask = task
      submission = createdSubmission
      lifecycleState = QueueTaskLifecycleState.QUEUED
      result = null
      return createdSubmission
    }

    override fun ensureProcessing() {
      lifecycleState = QueueTaskLifecycleState.RUNNING
    }

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> {
      val task = submittedTask ?: return emptyList()
      val createdSubmission = submission ?: return emptyList()
      return listOf(
        AgentRunSnapshot(
          sessionId = sessionId,
          runId = createdSubmission.runId,
          taskId = task.id,
          acceptedAtEpochMs = createdSubmission.acceptedAtEpochMs,
          updatedAtEpochMs = result?.finishedAtEpochMs ?: task.updatedAtEpochMs,
          lifecycleState = lifecycleState,
          taskState = taskStateFor(lifecycleState),
          attempt = 1,
          executionStatus = result?.status,
          errorCode = result?.errorCode,
          errorMessage = result?.errorMessage,
          responseFormat = result?.metadata?.get("responseFormat"),
          resultMetadata = result?.metadata.orEmpty(),
          pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        ),
      )
    }

    override fun findRun(runId: String): AgentRunSnapshot? =
      listRuns().firstOrNull { snapshot -> snapshot.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? =
      findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot {
      val task = submittedTask
      val updatedAtEpochMs = result?.finishedAtEpochMs ?: 1_000L
      if (task == null) {
        return SessionQueueSnapshot(
          sessionId = sessionId,
          agentId = "test-agent",
          updatedAtEpochMs = updatedAtEpochMs,
        )
      }
      return SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 0,
            task = task.copy(
              state = taskStateFor(lifecycleState),
              updatedAtEpochMs = updatedAtEpochMs,
            ),
            lifecycleState = lifecycleState,
            attempt = 1,
            lastErrorCode = result?.errorCode,
            lastErrorMessage = result?.errorMessage,
          ),
        ),
        updatedAtEpochMs = updatedAtEpochMs,
      )
    }

    override fun hasPendingWork(): Boolean =
      lifecycleState == QueueTaskLifecycleState.QUEUED ||
        lifecycleState == QueueTaskLifecycleState.RUNNING

    fun requireSubmittedTask(): AgentTask =
      checkNotNull(submittedTask) { "Expected task to be submitted." }

    fun recordResult(result: ExecutionResult) {
      this.result = result
    }

    fun settleTerminalState() {
      lifecycleState = when (result?.status) {
        com.opencray.core.contracts.ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
        com.opencray.core.contracts.ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
        else -> QueueTaskLifecycleState.FAILED
      }
    }

    private fun taskStateFor(
      lifecycleState: QueueTaskLifecycleState,
    ): AgentTaskState = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> AgentTaskState.QUEUED

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> AgentTaskState.RUNNING

      QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    }
  }

  internal class QueuedMainThreadPoster : MainThreadPoster {
    private val actions = ArrayDeque<() -> Unit>()

    override fun post(action: () -> Unit) {
      actions += action
    }

    fun flush() {
      while (actions.isNotEmpty()) {
        actions.removeFirst().invoke()
      }
    }
  }

  internal class QueuedExecutor : Executor {
    private val commands = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
      commands += command
    }

    fun pendingCount(): Int = commands.size

    fun runAll() {
      while (commands.isNotEmpty()) {
        commands.removeFirst().run()
      }
    }
  }

  internal class FixedTaskCommitmentIntentInterpreter(
    private val interpretation: TaskCommitmentIntentInterpretation,
  ) : TaskCommitmentIntentInterpreter {
    override fun interpret(
      request: TaskCommitmentIntentRequest,
    ): TaskCommitmentIntentInterpretation = interpretation
  }

  internal class RecordingSessionHandle(
    override val sessionId: String,
    private val onResume: ((String) -> Unit)? = null,
    private val submitFailure: Throwable? = null,
    private val resumeResult: Boolean = false,
    private val retryResult: Boolean = false,
    private val cancellationSettled: CountDownLatch? = null,
    private val onRequestCancel: ((String) -> Unit)? = null,
  ) : AgentSessionHandle {
    var queuedToolCompletion: QueuedToolCompletion? = null
    val queuedToolCompletions = mutableListOf<QueuedToolCompletion>()
    val submittedInputs = mutableListOf<String>()
    val submittedTasks = mutableListOf<AgentTask>()
    val submissions = mutableListOf<AgentRunSubmission>()
    val ensureProcessingTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()
    val cancelledPendingMessageIdSets = mutableListOf<Set<String>>()
    val resumedTaskIds = mutableListOf<String>()
    val resumedExecutionKinds = mutableListOf<String>()
    val resumedTaskMetadataUpdates = mutableListOf<Map<String, String>>()
    val retriedTaskIds = mutableListOf<String>()
    val terminatedProcessIds = mutableListOf<String>()
    val subAgentHandles = mutableListOf<SubAgentHandleState>()
    val closedSubAgentHandles = mutableListOf<SubAgentHandleState>()
    val retainedSubAgentParentRunIds = mutableListOf<Set<String>>()
    private var lastSubmittedTaskId: String? = null
    private val runSnapshotsById = linkedMapOf<String, AgentRunSnapshot>()
    private val activeSubAgentExecutions = mutableSetOf<Pair<String, String>>()
    private val managedProcessesById =
      linkedMapOf<String, com.opencray.runtime.process.ManagedProcessSnapshot>()

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      submitFailure?.let { throw it }
      submittedInputs += userText
      val taskId = "task-${submittedTasks.size + 1}"
      val runId = "run-${submittedTasks.size + 1}"
      lastSubmittedTaskId = taskId
      val task = AgentTask(
        id = taskId,
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecision,
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTasks += task
      submissions += submission
      runSnapshotsById[runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = pendingMessageId,
      )
      return submission
    }

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submitFailure?.let { throw it }
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.takeIf(String::isNotBlank)
        ?: "run-${submittedTasks.size + 1}"
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
      lastSubmittedTaskId = task.id
      submittedTasks += task
      submissions += submission
      runSnapshotsById[runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
        updatedAtEpochMs = task.createdAtEpochMs,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      val completion = queuedToolCompletion?.also {
        queuedToolCompletion = null
      } ?: if (queuedToolCompletions.isNotEmpty()) {
        queuedToolCompletions.removeAt(0)
      } else {
        null
      }
      completion?.also {
        completeQueuedToolCall(
          task = task,
          submission = submission,
          completion = it,
        )
      }
      return submission
    }

    override fun ensureProcessing() {
      lastSubmittedTaskId?.let(ensureProcessingTaskIds::add)
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      runSnapshotsById.entries.firstOrNull { (_, snapshot) -> snapshot.taskId == taskId }?.let { (runId, snapshot) ->
        runSnapshotsById[runId] = snapshot.copy(
          updatedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
          lifecycleState = if (onRequestCancel == null) {
            QueueTaskLifecycleState.CANCELLED
          } else {
            QueueTaskLifecycleState.CANCEL_REQUESTED
          },
          taskState = if (onRequestCancel == null) AgentTaskState.CANCELLED else AgentTaskState.RUNNING,
        )
      }
      onRequestCancel?.invoke(taskId)
      return true
    }

    override fun requestRetry(taskId: String): Boolean {
      retriedTaskIds += taskId
      return retryResult
    }

    override fun requestResumeTask(taskId: String): Boolean {
      return requestResumeTask(
        taskId = taskId,
        executionKind = com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME,
        taskMetadataUpdates = emptyMap(),
      )
    }

    override fun requestResumeTask(
      taskId: String,
      executionKind: String,
      taskMetadataUpdates: Map<String, String>,
    ): Boolean {
      resumedTaskIds += taskId
      resumedExecutionKinds += executionKind
      resumedTaskMetadataUpdates += taskMetadataUpdates
      if (taskMetadataUpdates.isNotEmpty()) {
        runSnapshotsById.entries.firstOrNull { (_, snapshot) -> snapshot.taskId == taskId }?.let { (runId, snapshot) ->
          runSnapshotsById[runId] = snapshot.copy(
            pendingMessageId =
              taskMetadataUpdates[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
                ?: snapshot.pendingMessageId,
          )
        }
      }
      return resumeResult
    }

    fun recordResult(
      task: AgentTask,
      result: ExecutionResult,
    ) {
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty()
      val existing = runSnapshotsById[runId] ?: return
      val lifecycleState = lifecycleStateForResult(result)
      runSnapshotsById[runId] = existing.copy(
        updatedAtEpochMs = result.finishedAtEpochMs,
        lifecycleState = lifecycleState,
        taskState = taskStateFor(lifecycleState),
        executionStatus = result.status,
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        responseFormat = result.metadata["responseFormat"],
        resultMetadata = result.metadata,
      )
    }

    fun recordEvent(event: com.opencray.runtime.OpenCrayAgentRunEvent) {
      val existing = runSnapshotsById[event.runId] ?: return
      runSnapshotsById[event.runId] = existing.copy(
        updatedAtEpochMs = event.emittedAtEpochMs,
        managedProcessIds = mergeManagedProcessIds(
          existing = existing.managedProcessIds,
          candidate = (event as? com.opencray.runtime.OpenCrayToolResultEvent)
            ?.result
            ?.metadata
            ?.get("processId"),
        ),
        lastEvent = event,
      )
    }

    fun updateRunSnapshot(
      runId: String,
      transform: (AgentRunSnapshot) -> AgentRunSnapshot,
    ) {
      val existing = runSnapshotsById[runId] ?: return
      runSnapshotsById[runId] = transform(existing)
    }

    private fun completeQueuedToolCall(
      task: AgentTask,
      submission: AgentRunSubmission,
      completion: QueuedToolCompletion,
    ) {
      val resolvedToolName = completion.toolName
        ?: TOOL_NAME_REGEX.find(task.input)?.groupValues?.getOrNull(1)
        ?: "UnknownTool"
      val toolResult = AgentToolResult(
        toolName = resolvedToolName,
        status = when (completion.status) {
          ExecutionStatus.SUCCESS -> AgentToolResultStatus.SUCCESS
          ExecutionStatus.DENIED -> AgentToolResultStatus.DENIED
          ExecutionStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
          ExecutionStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
          ExecutionStatus.FAILED -> AgentToolResultStatus.FAILED
        },
        content = completion.content,
        errorCode = completion.errorCode,
        errorMessage = completion.errorMessage,
        metadata = completion.metadata,
      )
      recordEvent(
        OpenCrayToolResultEvent(
          runId = submission.runId,
          taskId = task.id,
          turn = 0,
          call = AgentToolCall(toolName = resolvedToolName),
          result = toolResult,
          emittedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
      recordResult(
        task = task,
        result = ExecutionResult(
          taskId = task.id,
          status = completion.status,
          stdout = if (completion.status == ExecutionStatus.SUCCESS) completion.content else "",
          errorCode = completion.errorCode,
          errorMessage = completion.errorMessage,
          startedAtEpochMs = task.createdAtEpochMs,
          finishedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
    }

    override fun listRuns(): List<AgentRunSnapshot> = runSnapshotsById.values.map(::withManagedProcessState)

    override fun findRun(runId: String): AgentRunSnapshot? = runSnapshotsById[runId]?.let(::withManagedProcessState)

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? {
      cancellationSettled?.await(timeoutMs.coerceAtMost(500L), TimeUnit.MILLISECONDS)
      return findRun(runId)
    }

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int {
      val normalizedIds = pendingMessageIds
        .filterTo(linkedSetOf()) { pendingMessageId -> pendingMessageId.isNotBlank() }
      if (normalizedIds.isEmpty()) {
        return 0
      }
      cancelledPendingMessageIdSets += normalizedIds
      val matchingTaskIds = runSnapshotsById.values
        .filter { run -> !run.isTerminal && run.pendingMessageId in normalizedIds }
        .map { run -> run.taskId }
      matchingTaskIds.forEach(::requestCancel)
      return matchingTaskIds.size
    }

    override fun resume(): SessionLifecycleState {
      onResume?.invoke(sessionId)
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = emptyList<SessionQueueTaskSnapshot>(),
    )

    override fun hasPendingWork(): Boolean = false

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      managedProcessesById.values.toList()

    override fun listSubAgentHandles(): List<SubAgentHandleState> =
      (subAgentHandles + closedSubAgentHandles).toList()

    override fun listClosedSubAgentHandles(): List<SubAgentHandleState> =
      closedSubAgentHandles.toList()

    override fun hasActiveSubAgentExecution(agentId: String, parentRunId: String): Boolean =
      activeSubAgentExecutions.contains(agentId to parentRunId)

    fun markActiveSubAgentExecution(agentId: String, parentRunId: String) {
      activeSubAgentExecutions += agentId to parentRunId
    }

    override fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) {
      retainedSubAgentParentRunIds += parentRunIds
      subAgentHandles.removeAll { handle -> handle.parentRunId !in parentRunIds }
    }

    override fun terminateRunningManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      managedProcessesById.values
        .filter { snapshot ->
          snapshot.status == com.opencray.runtime.process.ManagedProcessStatus.RUNNING
        }
        .map { snapshot ->
          terminatedProcessIds += snapshot.processId
          snapshot.copy(
            status = com.opencray.runtime.process.ManagedProcessStatus.CANCELLED,
            cancelled = true,
            updatedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
            finishedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
          ).also { updated ->
            managedProcessesById[updated.processId] = updated
          }
        }

    fun putManagedProcess(snapshot: com.opencray.runtime.process.ManagedProcessSnapshot) {
      managedProcessesById[snapshot.processId] = snapshot
    }

    fun putRunSnapshot(snapshot: AgentRunSnapshot) {
      runSnapshotsById[snapshot.runId] = snapshot
    }

    private fun withManagedProcessState(snapshot: AgentRunSnapshot): AgentRunSnapshot {
      val managedProcessIds = (
        snapshot.managedProcessIds +
          managedProcessesById.values
            .asSequence()
            .filter { process -> process.taskId == snapshot.taskId }
            .map { process -> process.processId }
            .toList()
        ).distinct()
      val managedProcesses = managedProcessIds.mapNotNull(managedProcessesById::get)
      val runningManagedProcessCount = managedProcessIds.count { processId ->
        managedProcessesById[processId]?.status == com.opencray.runtime.process.ManagedProcessStatus.RUNNING
      }
      return snapshot.copy(
        managedProcessIds = managedProcessIds,
        managedProcesses = managedProcesses,
        runningManagedProcessCount = runningManagedProcessCount,
        hasLiveManagedProcesses = runningManagedProcessCount > 0,
      )
    }

    private fun lifecycleStateForResult(
      result: ExecutionResult,
    ): QueueTaskLifecycleState = when {
      result.status == ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
      result.status == ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
      result.status == ExecutionStatus.DENIED &&
        (
          result.errorCode == "APPROVAL_REQUIRED" ||
            result.errorCode == "HIGH_RISK_APPROVAL_REQUIRED" ||
            result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
          ) -> QueueTaskLifecycleState.SUSPENDED
      else -> QueueTaskLifecycleState.FAILED
    }

    private fun taskStateFor(
      lifecycleState: QueueTaskLifecycleState,
    ): AgentTaskState = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> AgentTaskState.QUEUED

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> AgentTaskState.RUNNING

      QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    }

    private fun mergeManagedProcessIds(
      existing: List<String>,
      candidate: String?,
    ): List<String> = candidate
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { processId -> if (processId in existing) existing else existing + processId }
      ?: existing
  }

  internal data class QueuedToolCompletion(
    val toolName: String? = null,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val status: ExecutionStatus = ExecutionStatus.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
  )

  internal class FailingChatSessionLocalStore(
    directory: java.io.File,
  ) : ChatSessionLocalStore(directory) {
    override fun appendSubmittedTurn(
      sessionId: String,
      userText: String,
      assistantMessageId: String,
      assistantPlaceholderText: String,
      attachments: List<ChatAttachmentEntry>,
    ): ChatSessionsState = error("transcript persistence failed")
  }

  internal class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  internal fun taskCommitmentRecord(
    id: String,
    content: String,
    sourceSessionId: String,
    updatedAtEpochMs: Long,
    ttlMs: Long = 14L * 24L * 60L * 60L * 1000L,
    lastConfirmedAtEpochMs: Long = updatedAtEpochMs,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:task_commitment",
      "scope:session",
      "status:open",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "task_commitment",
      MemoryRecordExtensionKeys.SCOPE to "session",
      MemoryRecordExtensionKeys.STATUS to "open",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sourceSessionId,
      MemoryRecordExtensionKeys.TTL_MS to ttlMs.toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to lastConfirmedAtEpochMs.toString(),
    ),
  )

  internal class FailingMemoryStore : MemoryStore {
    override fun list(): List<MemoryRecord> = emptyList()

    override fun upsert(record: MemoryRecord) {
      error("memory store unavailable")
    }

    override fun delete(id: String): Boolean = false

    override fun clear(): Boolean = false
  }

  internal fun hostRuntimeTestRunEventJournalStoreFactory(): RunEventJournalStoreFactory =
    hostRuntimeTestInvokeKtStatic(
      className = "com.opencray.app.RunEventJournalStoreFactoryKt",
      methodName = "inMemoryRunEventJournalStoreFactory",
    )

  internal fun hostRuntimeTestPromptCheckpointStoreFactory(): PromptCheckpointStoreFactory =
    hostRuntimeTestInvokeKtStatic(
      className = "com.opencray.app.PromptCheckpointStoreFactoryKt",
      methodName = "inMemoryPromptCheckpointStoreFactory",
    )

  @Suppress("UNCHECKED_CAST")
  internal fun <T> hostRuntimeTestInvokeKtStatic(
    className: String,
    methodName: String,
    args: Array<out Any?> = emptyArray(),
  ): T {
    val method = Class.forName(className)
      .methods
      .firstOrNull { candidate ->
        candidate.name == methodName && candidate.parameterCount == args.size
      }
      ?: error("Missing method $className::$methodName with ${args.size} args")
    return method.invoke(null, *args) as T
  }

  internal fun jsonObject(raw: String): JsonObject =
    Json.parseToJsonElement(raw).jsonObject
}
