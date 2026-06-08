package com.opencray.app

import android.content.Context
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.process.LocalManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessRuntimeIdentity
import com.opencray.runtime.process.RoutedManagedProcessControllerFactory
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.soul.SoulPlasticity
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors

internal class RuntimeOwnerLifecycleState(
  initialLifecycle: HostRuntimeLifecycleDescriptor,
) {
  private val lock = Any()
  private var currentLifecycle: HostRuntimeLifecycleDescriptor = initialLifecycle

  fun current(): HostRuntimeLifecycleDescriptor = synchronized(lock) {
    currentLifecycle
  }

  fun replace(nextLifecycle: HostRuntimeLifecycleDescriptor): HostRuntimeLifecycleDescriptor =
    synchronized(lock) {
      currentLifecycle = nextLifecycle
      nextLifecycle
    }
}

internal data class RetainedInProcessOpenCrayRuntimeOwnerCore(
  val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor?,
  private val runtimeOwnerLifecycleState: RuntimeOwnerLifecycleState,
  val sessionRuntimeManager: AgentSessionRuntimeManager,
  val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  val supplementStoreFactory: AgentSessionSupplementStoreFactory,
  val transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage>,
  val approvalRegistry: AgentTaskApprovalRegistry,
  val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator,
  val replayAccess: OpenCrayRuntimeReplayAccess,
  val sandboxPreviewEmbedConfigService: SandboxPreviewEmbedConfigService? = null,
  private val disposeHandler: () -> Unit = {},
) {
  private val disposeLock = Any()
  private var disposed: Boolean = false

  fun currentLifecycleDescriptor(): HostRuntimeLifecycleDescriptor =
    runtimeOwnerLifecycleState.current()

  fun replaceLifecycleDescriptor(): HostRuntimeLifecycleDescriptor =
    runtimeOwnerLifecycleState.replace(
      hostRuntimeLifecycleDescriptorFor(runtimeControllerLifecycle),
    )

  fun toRuntimeHostAccess(
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = currentLifecycleDescriptor(),
  ): OpenCrayRuntimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
    lifecycleDescriptor = lifecycleDescriptor,
    sessionRuntimeManager = sessionRuntimeManager,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    supplementStoreFactory = supplementStoreFactory,
    approvalRegistry = approvalRegistry,
  )

  fun toRuntimeOwnerBootstrap(
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = currentLifecycleDescriptor(),
  ): RuntimeOwnerBootstrap {
    val runtimeHostAccess = toRuntimeHostAccess(lifecycleDescriptor)
    return RuntimeOwnerBootstrap(
      runtimeOwnerLifecycle = lifecycleDescriptor,
      ownerObservationAccess = runtimeHostAccess,
      notificationHostAccess = runtimeHostAccess,
      approvalDecisionHostAccess = runtimeHostAccess,
      chatMutationAccess = runtimeHostAccess,
      chatSubmissionHostAccess = runtimeHostAccess,
      runtimeReplayAccess = replayAccess,
      retainedHandle = RetainedInProcessRuntimeOwnerHandle(this),
    )
  }

  fun dispose() {
    val handler = synchronized(disposeLock) {
      if (disposed) {
        null
      } else {
        disposed = true
        disposeHandler
      }
    } ?: return
    try {
      sessionRuntimeManager.releaseAllSessions()
    } finally {
      handler()
    }
  }
}

private class RetainedInProcessRuntimeOwnerHandle(
  private val core: RetainedInProcessOpenCrayRuntimeOwnerCore,
) : RetainedRuntimeOwnerHandle {
  override fun createReplacementBootstrap(): RuntimeOwnerBootstrap =
    core.toRuntimeOwnerBootstrap(
      lifecycleDescriptor = core.replaceLifecycleDescriptor(),
    )

  override fun disposeRetainedOwner() {
    core.dispose()
  }
}

private fun hostRuntimeLifecycleDescriptorFor(
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor?,
): HostRuntimeLifecycleDescriptor = runtimeControllerLifecycle
  ?.let { controller ->
    HostRuntimeLifecycleDescriptor(
      runtimeControllerId = controller.controllerInstanceId,
      durableRuntimeControllerId = controller.durableControllerId,
    )
  }
  ?: HostRuntimeLifecycleDescriptor()

internal fun createRetainedInProcessOpenCrayRuntimeOwnerCore(
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
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
): RetainedInProcessOpenCrayRuntimeOwnerCore {
  val runtimeOwnerLifecycleState = RuntimeOwnerLifecycleState(
    hostRuntimeLifecycleDescriptorFor(runtimeControllerLifecycle),
  )
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
  val processRegistryFactory = FileBackedAgentProcessRegistryFactory(
    runtimeRootDirectory = File(
      appContext.filesDir,
      FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
    ),
    controllerFactory = managedProcessControllerFactory,
    runtimeIdentity = ManagedProcessRuntimeIdentity(
      processStartId = runtimeOwnerLifecycleState.current().processStartId,
      runtimeControllerId = runtimeOwnerLifecycleState.current().runtimeControllerId,
    ),
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
  )
  val sessionRuntimeManager = DefaultAgentSessionRuntimeManager(
    agentId = "opencray-flutter-host",
    runtimeFactory = runtimeFactory,
    snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
    runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    executor = chatExecutor,
    subAgentRecoveryExecutor = subAgentRecoveryExecutor,
    runtimeLifecycleProvider = runtimeOwnerLifecycleState::current,
  )
  return RetainedInProcessOpenCrayRuntimeOwnerCore(
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    runtimeOwnerLifecycleState = runtimeOwnerLifecycleState,
    sessionRuntimeManager = sessionRuntimeManager,
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
    disposeHandler = {
      chatExecutor.shutdownNow()
      if (subAgentRecoveryExecutor !== chatExecutor) {
        subAgentRecoveryExecutor.shutdownNow()
      }
    },
  )
}
