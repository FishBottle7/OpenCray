package com.opencray.app

import android.content.Context
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.process.LocalManagedProcessControllerFactory
import com.opencray.runtime.process.RoutedManagedProcessControllerFactory
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.soul.SoulPlasticity
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors

internal data class InProcessOpenCrayRuntimeOwner(
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val sessionRuntimeManager: AgentSessionRuntimeManager,
  val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  val supplementStoreFactory: AgentSessionSupplementStoreFactory,
  val transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage>,
  val approvalRegistry: AgentTaskApprovalRegistry,
  val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator,
  val replayAccess: OpenCrayRuntimeReplayAccess,
  val sandboxPreviewEmbedConfigService: SandboxPreviewEmbedConfigService? = null,
)

internal object InProcessOpenCrayRuntimeOwnerRegistry {
  @Volatile
  private var instance: InProcessOpenCrayRuntimeOwner? = null

  fun peek(): InProcessOpenCrayRuntimeOwner? = instance

  fun clearForTest() {
    synchronized(this) {
      instance = null
    }
  }

  fun getOrCreate(factory: () -> InProcessOpenCrayRuntimeOwner): InProcessOpenCrayRuntimeOwner =
    instance ?: synchronized(this) {
      instance ?: factory().also { created ->
        instance = created
      }
    }
}

internal fun createInProcessOpenCrayRuntimeOwner(
  appContext: Context,
  llmSettingsStore: LlmSettingsStore,
  sandboxSettingsRepository: SandboxSettingsRepository,
  personalizationStore: PersonalizationLocalStore,
  chatSessionStore: ChatSessionLocalStore,
  skillsFacade: SkillsFacade,
  mcpSettingsFacade: McpSettingsFacade,
  liveContextModeStore: LiveContextModeStore,
  mediaSpeechSettingsStore: MediaSpeechSettingsStore,
  webSearchSettingsStore: WebSearchSettingsStore,
  providerUserAgent: String,
  workspaceRootProvider: () -> Path,
  workspaceRootsProvider: () -> Set<Path>,
  approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  soulProfileStore: WorkspaceSoulProfileStore,
): InProcessOpenCrayRuntimeOwner {
  val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
  val chatExecutor = Executors.newSingleThreadExecutor()
  val subAgentRecoveryExecutor = Executors.newCachedThreadPool()
  val chatContextFactory = ChatRuntimeSessionContextFactory(
    chatSessionStore = chatSessionStore,
    workspaceRootProvider = workspaceRootProvider,
  )
  val approvalRegistry = AgentTaskApprovalRegistry()
  val localPythonRuntime = P4aPythonRuntime.fromContext(appContext)
  val e2bSessionStore = E2BSandboxSessionStore.fromContext(appContext)
  val e2bPythonRuntime = E2BCodeInterpreterPythonRuntime(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = e2bSessionStore,
  )
  val e2bSandboxPreviewService = E2BSandboxPreviewService(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = e2bSessionStore,
    activeSessionProvider = e2bPythonRuntime::activeStickySessionSnapshot,
    activeSessionRecorder = e2bPythonRuntime::recordStickySessionSnapshot,
  )
  val e2bSandboxPreviewEmbedConfigService = E2BSandboxPreviewEmbedConfigService(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = e2bSessionStore,
    activeSessionProvider = e2bPythonRuntime::activeStickySessionSnapshot,
  )
  val e2bSandboxSessionControlService = E2BSandboxSessionControlService(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = e2bSessionStore,
    activeSessionProvider = e2bPythonRuntime::activeStickySessionSnapshot,
    sessionCloser = e2bPythonRuntime::closeReusableSession,
  )
  val e2bSandboxSessionInfoService = E2BSandboxSessionInfoService(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = e2bSessionStore,
    activeSessionProvider = e2bPythonRuntime::activeStickySessionSnapshot,
    activeSessionRecorder = e2bPythonRuntime::recordStickySessionSnapshot,
    runningRequestIdsProvider = e2bPythonRuntime::runningRequestIdsForSandbox,
    sessionCloser = e2bPythonRuntime::closeReusableSession,
  )
  val pythonRuntimeManifestProvider = PythonRuntimeManifestAssetProvider.fromContext(appContext)
  val pythonRuntime = RoutingPythonScriptRuntime(
    settingsProvider = sandboxSettingsRepository::load,
    localRuntime = localPythonRuntime,
    sandboxRuntimeProvider = { settings ->
      when (SandboxProviderId.fromWireValue(settings.state.providerId)) {
        SandboxProviderId.E2B -> e2bPythonRuntime
        null -> null
      }
    },
  )
  val e2bSandboxCommandBackend = E2BSandboxCommandExecutionBackendFactory.create(
    workspaceRootProvider = workspaceRootProvider,
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = e2bSessionStore,
    activeSessionProvider = e2bPythonRuntime::activeStickySessionSnapshot,
    pythonRuntime = e2bPythonRuntime,
  )
  val commandExecutor = RoutingCommandExecutor(
    settingsProvider = sandboxSettingsRepository::load,
    localExecutor = CommandExecutor(),
    sandboxExecutorProvider = { settings ->
      when (SandboxProviderId.fromWireValue(settings.state.providerId)) {
        SandboxProviderId.E2B -> e2bSandboxCommandBackend.createCommandExecutor()

        null -> null
      }
    },
  )
  val pythonManagedProcessFactory = RoutedManagedProcessControllerFactory(
    workspaceRoot = workspaceRootProvider(),
    pythonRuntime = pythonRuntime,
  )
  val managedProcessControllerFactory = RoutingManagedProcessControllerFactory(
    settingsProvider = sandboxSettingsRepository::load,
    pythonRuntimeFactory = pythonManagedProcessFactory,
    localFactory = LocalManagedProcessControllerFactory(),
    sandboxFactoryProvider = { settings ->
      when (SandboxProviderId.fromWireValue(settings.state.providerId)) {
        SandboxProviderId.E2B -> e2bSandboxCommandBackend.createManagedProcessControllerFactory()

        null -> null
      }
    },
  )
  val compactionStoreFactory = FileBackedAgentSessionCompactionStoreFactory.fromContext(appContext)
  val transcriptStoreFactory = FileBackedAgentSessionTranscriptStoreFactory.fromContext(appContext)
  val supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory.fromContext(appContext)
  val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext)
  val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext)
  val subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(appContext)
  val runtimeRootDirectory = File(
    appContext.filesDir,
    FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
  )
  val processRegistryFactory = FileBackedAgentProcessRegistryFactory(
    runtimeRootDirectory = runtimeRootDirectory,
    controllerFactory = managedProcessControllerFactory,
  )
  val onDeviceModelInstallStore = LiteRtOnDeviceModelInstallStore.fromContext(appContext)
  val liteLlmProviderClient = AppConfiguredLiteLlmProviderClient(
    cloudProviderClient = OpenAiCompatibleLiteLlmProviderClient(
      userAgent = providerUserAgent,
    ),
    onDeviceProviderClient = LiteRtOnDeviceLlmProviderClient(
      runtime = LiteRtOnDeviceRuntime.fromContext(
        context = appContext,
        installStore = onDeviceModelInstallStore,
      ),
    ),
  )
  val mediaProviderClient = OpenCrayConfigurableMediaProviderClient(
    userAgent = providerUserAgent,
  )
  val userMemoryIntentInterpreter = LiteLlmUserMemoryIntentInterpreter(
    llmSettingsProvider = { llmSettingsStore.load() },
    providerClient = liteLlmProviderClient,
  )
  val taskCommitmentIntentInterpreter = LiteLlmTaskCommitmentIntentInterpreter(
    llmSettingsProvider = { llmSettingsStore.load() },
    providerClient = liteLlmProviderClient,
  )
  val memoryStewardshipInterpreter = LiteLlmMemoryStewardshipInterpreter(
    llmSettingsProvider = { llmSettingsStore.load() },
    providerClient = liteLlmProviderClient,
  )
  val relationshipEventInterpreter = LiteLlmRelationshipEventInterpreter(
    llmSettingsProvider = { llmSettingsStore.load() },
    providerClient = liteLlmProviderClient,
  )
  val soulTurnSignalInterpreter = LiteLlmSoulTurnSignalInterpreter(
    llmSettingsProvider = { llmSettingsStore.load() },
    providerClient = liteLlmProviderClient,
  )
  val memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
    memoryStore = personalizationStore.asMemoryStore(),
    workspaceIdProvider = { AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()) },
    candidateExtractor = MemoryCandidateExtractor(
      userIntentInterpreter = userMemoryIntentInterpreter,
    ),
    taskCommitmentResolver = com.opencray.runtime.memory.TaskCommitmentResolver(
      store = personalizationStore.asMemoryStore(),
      intentInterpreter = taskCommitmentIntentInterpreter,
    ),
    memoryStewardshipService = com.opencray.runtime.memory.MemoryStewardshipService(
      interpreter = memoryStewardshipInterpreter,
      failClosedOnInterpreterUnavailable = true,
      candidateOnlyReviewKinds = setOf(
        com.opencray.runtime.memory.MemoryKind.USER_PREFERENCE,
        com.opencray.runtime.memory.MemoryKind.PROJECT_FACT,
        com.opencray.runtime.memory.MemoryKind.DURABLE_INSTRUCTION,
      ),
      recordOnlyReviewKinds = setOf(
        com.opencray.runtime.memory.MemoryKind.USER_PREFERENCE,
        com.opencray.runtime.memory.MemoryKind.PROJECT_FACT,
        com.opencray.runtime.memory.MemoryKind.DURABLE_INSTRUCTION,
      ),
    ),
    soulPlasticityProvider = {
      when (
        soulProfileStore.loadSoulProfile(workspaceRootProvider())
          ?.extensions
          ?.get(SoulProfileExtensionKeys.PLASTICITY)
          ?.trim()
          ?.lowercase(Locale.US)
      ) {
        "high" -> SoulPlasticity.HIGH
        "medium" -> SoulPlasticity.MEDIUM
        else -> SoulPlasticity.LOW
      }
    },
    relationshipEventInterpreter = relationshipEventInterpreter,
    sessionScopedStateMarker = { sessionId ->
      chatSessionStore.setSessionScopedStatePresent(
        sessionId = sessionId,
        present = true,
      )
    },
  )
  val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
    llmSettingsProvider = { llmSettingsStore.load() },
    safetySettingsProvider = { SafetySettingsStore.fromContext(appContext).load() },
    liveContextModeProvider = { liveContextModeStore.load() },
    sessionContextFactory = chatContextFactory,
    soulProfileProvider = { soulProfileStore.loadSoulProfile(workspaceRootProvider()) },
    workspaceRootsProvider = workspaceRootsProvider,
    readRootsProvider = { approvedReadRootsProvider().roots },
    skillsRootsProvider = { skillsFacade.enabledSkillRoots() },
    mcpReportProvider = { mcpSettingsFacade.currentExposureReport() },
    memoryRecordsProvider = personalizationStore::listMemoryRecords,
    providerUserAgent = providerUserAgent,
    approvalRegistry = approvalRegistry,
    promptCheckpointStoreProvider = promptCheckpointStoreFactory::forChatSession,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    todoStoreProvider = { sessionId ->
      ChatSessionBackedAgentTodoStore(
        chatSessionStore = chatSessionStore,
        sessionId = sessionId,
      )
    },
    processRegistryProvider = processRegistryFactory::forChatSession,
    transcriptStoreProvider = transcriptStoreFactory::forChatSession,
    supplementStoreProvider = supplementStoreFactory::forChatSession,
    compactionStoreProvider = compactionStoreFactory::forChatSession,
    subAgentHandleStoreProvider = subAgentHandleStoreFactory::forChatSession,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    soulTurnSemanticSignalInterpreter = soulTurnSignalInterpreter,
    providerClient = liteLlmProviderClient,
    onDeviceModelReadyProvider = { modelId ->
      LiteRtOnDeviceModelInstallStore.fromContext(appContext).hasReadyModel(modelId)
    },
    enableLiteRtDevAutomaticToolExecution =
      (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    commandExecutorProvider = { commandExecutor },
    pythonRuntimeProvider = { pythonRuntime },
    pythonRuntimeManifestProvider = pythonRuntimeManifestProvider::currentManifest,
    webSearchProviderFactory = {
      AppConfiguredWebSearchProviderFactory.create(
        slots = webSearchSettingsStore.load(),
        userAgent = providerUserAgent,
      )
    },
    mediaToolSettingsProvider = {
      mediaToolSettingsFor(
        mediaSettings = mediaSpeechSettingsStore.load(),
        llmSettings = llmSettingsStore.load(),
      )
    },
    imageGenerationClientProvider = { mediaProviderClient },
    speechSynthesisClientProvider = { mediaProviderClient },
    sandboxPreviewServiceProvider = {
      when (SandboxProviderId.fromWireValue(sandboxSettingsRepository.load().state.providerId)) {
        SandboxProviderId.E2B -> e2bSandboxPreviewService
        null -> null
      }
    },
    sandboxSessionControlServiceProvider = {
      when (SandboxProviderId.fromWireValue(sandboxSettingsRepository.load().state.providerId)) {
        SandboxProviderId.E2B -> e2bSandboxSessionControlService
        null -> null
      }
    },
    sandboxSessionInfoServiceProvider = {
      when (SandboxProviderId.fromWireValue(sandboxSettingsRepository.load().state.providerId)) {
        SandboxProviderId.E2B -> e2bSandboxSessionInfoService
        null -> null
      }
    },
    nativeWebSearchSessionApprovalProvider = { sessionId ->
      chatSessionStore.isNativeWebSearchSessionApproved(sessionId)
    },
    hiddenToolNamePrefixesProvider = {
      SandboxNativeToolVisibility.hiddenToolNamePrefixes(sandboxSettingsRepository.load())
    },
    skillPackageManagerProvider = {
      SkillPackageManager(
        managedRoot = AppSkillsStorage.managedSkillsRootForContext(appContext),
        catalogRoot = AppSkillsStorage.catalogSkillsRootForContext(appContext),
        manifestStore = SkillInstallManifestStore.fromFile(
          AppSkillsStorage.manifestFileForContext(appContext),
        ),
      )
    },
    scheduledTaskManagerProvider = {
      AppScheduledTaskManager(
        storageRootPath = runtimeRootDirectory.toPath(),
        chatSessionStore = chatSessionStore,
        specStore = FileBackedScheduledTaskSpecStoreFactory(runtimeRootDirectory).create(),
        runRecordStore = FileBackedScheduledTaskRunRecordStoreFactory(runtimeRootDirectory).create(),
        triggerRegistrar = DefaultScheduledTriggerRegistrar(
          alarmScheduler = AlarmManagerScheduledAlarmScheduler.fromContext(appContext),
          workScheduler = WorkManagerScheduledWorkScheduler.fromContext(appContext),
        ),
        triggerSyncStateStore = FileBackedScheduledTaskTriggerSyncStateStoreFactory(
          runtimeRootDirectory,
        ).create(),
      )
    },
  )
  return InProcessOpenCrayRuntimeOwner(
    lifecycleDescriptor = lifecycleDescriptor,
    sessionRuntimeManager = DefaultAgentSessionRuntimeManager(
      agentId = "opencray-flutter-host",
      runtimeFactory = runtimeFactory,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
      runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      executor = chatExecutor,
      subAgentRecoveryExecutor = subAgentRecoveryExecutor,
      runtimeLifecycle = lifecycleDescriptor,
    ),
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    supplementStoreFactory = supplementStoreFactory,
    transcriptMessagesProvider = { sessionId ->
      transcriptStoreFactory.forChatSession(sessionId).snapshot()
    },
    approvalRegistry = approvalRegistry,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    replayAccess = OpenCrayRuntimeReplayAccess(
      approvalRejectionRecorder = runtimeFactory::recordApprovalRejection,
      approvalApprovedRecorder = runtimeFactory::recordApprovalApproved,
      subAgentReplayRecorder = runtimeFactory::recordSubAgentReplayEvent,
      runCancellationRecorder = runtimeFactory::recordRunCancellation,
      terminalReplayRepairer = runtimeFactory::repairTerminalReplayFromRunSnapshots,
    ),
    sandboxPreviewEmbedConfigService = e2bSandboxPreviewEmbedConfigService,
  )
}
