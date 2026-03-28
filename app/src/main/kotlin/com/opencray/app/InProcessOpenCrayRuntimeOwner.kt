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
  val chatContextFactory = ChatRuntimeSessionContextFactory(
    chatSessionStore = chatSessionStore,
    workspaceRootProvider = workspaceRootProvider,
  )
  val approvalRegistry = AgentTaskApprovalRegistry()
  val localPythonRuntime = P4aPythonRuntime.fromContext(appContext)
  val e2bPythonRuntime = E2BCodeInterpreterPythonRuntime(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = E2BSandboxSessionStore.fromContext(appContext),
  )
  val e2bSandboxPreviewService = E2BSandboxPreviewService(
    settingsProvider = sandboxSettingsRepository::load,
    sessionStore = E2BSandboxSessionStore.fromContext(appContext),
    activeSessionProvider = e2bPythonRuntime::activeStickySessionSnapshot,
  )
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
  val commandExecutor = RoutingCommandExecutor(
    settingsProvider = sandboxSettingsRepository::load,
    localExecutor = CommandExecutor(),
    sandboxExecutorProvider = { settings ->
      when (SandboxProviderId.fromWireValue(settings.state.providerId)) {
        SandboxProviderId.E2B -> CommandExecutor(
          runner = PythonBackedCommandProcessRunner(
            workspaceRoot = workspaceRootProvider(),
            pythonRuntime = e2bPythonRuntime,
          ),
        )

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
        SandboxProviderId.E2B -> SandboxPythonManagedCommandControllerFactory(
          workspaceRoot = workspaceRootProvider(),
          pythonRuntime = e2bPythonRuntime,
        )

        null -> null
      }
    },
  )
  val compactionStoreFactory = FileBackedAgentSessionCompactionStoreFactory.fromContext(appContext)
  val transcriptStoreFactory = FileBackedAgentSessionTranscriptStoreFactory.fromContext(appContext)
  val supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory.fromContext(appContext)
  val promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(appContext)
  val processRegistryFactory = FileBackedAgentProcessRegistryFactory(
    runtimeRootDirectory = File(
      appContext.filesDir,
      FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
    ),
    controllerFactory = managedProcessControllerFactory,
  )
  val liteLlmProviderClient = OpenAiCompatibleLiteLlmProviderClient(
    userAgent = providerUserAgent,
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
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    soulTurnSemanticSignalInterpreter = soulTurnSignalInterpreter,
    commandExecutorProvider = { commandExecutor },
    pythonRuntimeProvider = { pythonRuntime },
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
  )
  return InProcessOpenCrayRuntimeOwner(
    lifecycleDescriptor = lifecycleDescriptor,
    sessionRuntimeManager = DefaultAgentSessionRuntimeManager(
      agentId = "opencray-flutter-host",
      runtimeFactory = runtimeFactory,
      snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
      runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
      runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      executor = chatExecutor,
      runtimeLifecycle = lifecycleDescriptor,
    ),
    runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory.fromContext(appContext),
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
  )
}
