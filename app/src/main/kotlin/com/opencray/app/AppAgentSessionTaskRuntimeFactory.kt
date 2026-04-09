package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.mcp.McpClientExposureReport
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.AgentTodoStore
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.HostProcessPythonRuntime
import com.opencray.runtime.InMemoryAgentTodoStore
import com.opencray.runtime.ManagedProcessObservationTracker
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayChatAttachmentSource
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayImageGenerationClient
import com.opencray.runtime.OpenCrayMediaToolSettings
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptSupplementMetadata
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySpeechSynthesisClient
import com.opencray.runtime.OpenCraySpeechSynthesisSettings
import com.opencray.runtime.OpenCrayImageGenerationSettings
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCraySupplementInput
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.PythonRuntimeManifestSnapshot
import com.opencray.runtime.SandboxPreviewService
import com.opencray.runtime.SandboxSessionControlService
import com.opencray.runtime.SandboxSessionInfoService
import com.opencray.runtime.bootstrap.BootstrapContextResolver
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.compaction.DurableCompactionCoordinator
import com.opencray.runtime.compaction.InMemorySessionCompactionStore
import com.opencray.runtime.compaction.SessionCompactionStore
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationAssistantPhase
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.memory.MemoryRecallRequest
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRetriever
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.session.InMemorySessionTranscriptStore
import com.opencray.runtime.session.SessionTranscriptStore
import com.opencray.runtime.skills.SkillCatalogResolver
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillInventoryResolver
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.NoOpSoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpretation
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalRequest
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.workingstate.InMemoryWorkingStateStore
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateStore
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AppAgentSessionTaskRuntimeFactory(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val safetySettingsProvider: () -> SafetySettingsState = { SafetySettingsState() },
  private val liveContextModeProvider: () -> LiveContextMode = { LiveContextMode.FULL },
  private val sessionContextFactory: ChatRuntimeSessionContextFactory,
  private val soulProfileProvider: () -> WorkspaceSoulProfile?,
  private val workspaceRootsProvider: () -> Set<Path>,
  private val readRootsProvider: () -> Set<Path> = workspaceRootsProvider,
  private val skillsRootsProvider: () -> List<File>,
  private val mcpReportProvider: () -> McpClientExposureReport?,
  private val memoryRecordsProvider: () -> List<MemoryRecord> = { emptyList() },
  private val providerUserAgent: String = OpenCrayUserAgent.providerApi("0"),
  private val approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
  private val promptCheckpointStoreProvider: (String) -> PromptCheckpointStore = { sessionId ->
    InMemoryPromptCheckpointStoreFactory().forChatSession(sessionId)
  },
  private val runEventJournalStoreFactory: RunEventJournalStoreFactory = InMemoryRunEventJournalStoreFactory(),
  private val todoStoreProvider: (String) -> AgentTodoStore = { InMemoryAgentTodoStore() },
  private val workingStateStoreProvider: (String) -> WorkingStateStore = { InMemoryWorkingStateStore() },
  private val processRegistryProvider: (String) -> AgentProcessRegistry = { InMemoryAgentProcessRegistry() },
  private val transcriptStoreProvider: (String) -> SessionTranscriptStore = { InMemorySessionTranscriptStore() },
  private val supplementStoreProvider: (String) -> SessionSupplementStore = { InMemorySessionSupplementStore() },
  private val compactionStoreProvider: (String) -> SessionCompactionStore = { InMemorySessionCompactionStore() },
  private val subAgentHandleStoreProvider: (String) -> SubAgentHandleStore = { sessionId ->
    InMemorySubAgentHandleStoreFactory().forChatSession(sessionId)
  },
  private val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  private val soulTurnSemanticSignalInterpreter: SoulTurnSemanticSignalInterpreter =
    NoOpSoulTurnSemanticSignalInterpreter,
  private val commandExecutorProvider: () -> CommandExecutor? = { null },
  private val pythonRuntimeProvider: () -> PythonScriptRuntime = { HostProcessPythonRuntime() },
  private val pythonRuntimeManifestProvider: (() -> PythonRuntimeManifestSnapshot?)? = null,
  private val webSearchProviderFactory: () -> WebSearchProvider = { UnconfiguredWebSearchProvider },
  private val sandboxPreviewServiceProvider: () -> SandboxPreviewService? = { null },
  private val sandboxSessionControlServiceProvider: () -> SandboxSessionControlService? = { null },
  private val sandboxSessionInfoServiceProvider: () -> SandboxSessionInfoService? = { null },
  private val skillPackageManagerProvider: () -> SkillPackageManager? = { null },
  private val mediaToolSettingsProvider: () -> OpenCrayMediaToolSettings? = { null },
  private val imageGenerationClientProvider: () -> OpenCrayImageGenerationClient? = { null },
  private val speechSynthesisClientProvider: () -> OpenCraySpeechSynthesisClient? = { null },
  private val nativeWebSearchSessionApprovalProvider: (String) -> Boolean = { false },
  private val hiddenToolNamePrefixesProvider: () -> Set<String> = { emptySet() },
  private val subAgentExecutionCoordinatorProvider: ((String) -> SubAgentExecutionCoordinator)? = null,
) : AgentSessionTaskRuntimeFactory {
  private val todoStoresBySession: ConcurrentMap<String, AgentTodoStore> = ConcurrentHashMap()
  private val promptCheckpointStoresBySession: ConcurrentMap<String, PromptCheckpointStore> =
    ConcurrentHashMap()
  private val processRegistriesBySession: ConcurrentMap<String, AgentProcessRegistry> = ConcurrentHashMap()
  private val managedProcessObservationTrackersBySession:
    ConcurrentMap<String, ManagedProcessObservationTracker> = ConcurrentHashMap()
  private val workingStateStoresBySession: ConcurrentMap<String, WorkingStateStore> = ConcurrentHashMap()
  private val transcriptStoresBySession: ConcurrentMap<String, SessionTranscriptStore> = ConcurrentHashMap()
  private val supplementStoresBySession: ConcurrentMap<String, SessionSupplementStore> = ConcurrentHashMap()
  private val compactionStoresBySession: ConcurrentMap<String, SessionCompactionStore> = ConcurrentHashMap()
  private val subAgentHandleStoresBySession: ConcurrentMap<String, SubAgentHandleStore> = ConcurrentHashMap()
  private val subAgentExecutionCoordinatorsBySession: ConcurrentMap<String, SubAgentExecutionCoordinator> =
    ConcurrentHashMap()
  private val memoryRetriever: MemoryRetriever = MemoryRetriever()
  private val memoryBackedSoulResolver: MemoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver()
  private val bootstrapContextResolver: BootstrapContextResolver = BootstrapContextResolver()
  private val durableCompactionCoordinator: DurableCompactionCoordinator = DurableCompactionCoordinator()
  private val skillCatalogResolver: SkillCatalogResolver = SkillCatalogResolver()
  private val skillInventoryResolver: SkillInventoryResolver = SkillInventoryResolver()
  private val replayJson: Json = Json { prettyPrint = false }

  override fun create(
    sessionId: String,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): SessionTaskRuntime = SessionTaskRuntime { task, hooks ->
    executeTask(
      sessionId = sessionId,
      task = task,
      hooks = hooks,
      eventSink = eventSink,
    )
  }

  override fun listManagedProcesses(sessionId: String): List<ManagedProcessSnapshot> =
    processRegistryForSession(sessionId).list()

  override fun terminateManagedProcess(
    sessionId: String,
    processId: String,
  ): ManagedProcessSnapshot? = processRegistryForSession(sessionId).terminate(processId)

  override fun listSubAgentHandles(sessionId: String): List<SubAgentHandleState> =
    subAgentExecutionCoordinatorsBySession[sessionId]?.allHandles()
      ?: subAgentHandleStoreForSession(sessionId).list()

  override fun retainKnownSubAgentParentRuns(
    sessionId: String,
    parentRunIds: Set<String>,
  ) {
    subAgentExecutionCoordinatorsBySession[sessionId]
      ?.retainKnownParentRuns(parentRunIds)
      ?: subAgentHandleStoreForSession(sessionId).retainKnownParentRuns(parentRunIds)
  }

  override fun releaseSession(sessionId: String) {
    todoStoresBySession.remove(sessionId)
    promptCheckpointStoresBySession.remove(sessionId)
    processRegistriesBySession.remove(sessionId)
    managedProcessObservationTrackersBySession.remove(sessionId)
    workingStateStoresBySession.remove(sessionId)
    transcriptStoresBySession.remove(sessionId)
    supplementStoresBySession.remove(sessionId)
    compactionStoresBySession.remove(sessionId)
    subAgentHandleStoresBySession.remove(sessionId)
    subAgentExecutionCoordinatorsBySession.remove(sessionId)
  }

  override fun executeDetachedControlTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): ExecutionResult? {
    val detachedControlTask = detachedControlTaskSpec(task) ?: return null
    return executeTask(
      sessionId = sessionId,
      task = task,
      hooks = hooks,
      eventSink = eventSink,
      detachedControlTask = detachedControlTask,
    )
  }

  override fun executeDetachedSubAgentRecoveryTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult =
    executeTask(
      sessionId = sessionId,
      task = task,
      hooks = hooks,
      eventSink = eventSink,
      detachedControlTask = DetachedSubAgentRecoveryWaitTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      ),
    )

  override fun cancelActiveSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean =
    subAgentExecutionCoordinatorForSession(sessionId)
      .cancelActiveExecution(
        key = SubAgentExecutionKey(
          parentRunId = parentRunId,
          agentId = agentId,
        ),
      ) != null

  private fun executeTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    detachedControlTask: DetachedControlTaskSpec? = null,
  ): ExecutionResult {
    val llmSettings = llmSettingsProvider().sanitized()
    val safetySettings = safetySettingsProvider().sanitized()
    val approvalContinuation = approvalContinuationForExecution(sessionId, task.id)
    val approvalGrant = approvalContinuation.grant
    val approvalRejection = approvalContinuation.rejection
    val approvedSubAgentResume = approvalGrant?.subAgentApprovalResume
    val rejectedSubAgentResume = approvalRejection?.subAgentApprovalResume
    val requiresLlmConfig = task.type == com.opencray.core.contracts.AgentTaskType.PROMPT ||
      detachedControlRequiresLlm(
        sessionId = sessionId,
        detachedControlTask = detachedControlTask,
        approvedSubAgentResume = approvedSubAgentResume,
        rejectedSubAgentResume = rejectedSubAgentResume,
      ) ||
      directToolCallRequiresLlm(
        sessionId = sessionId,
        task = task,
        approvedSubAgentResume = approvedSubAgentResume,
        rejectedSubAgentResume = rejectedSubAgentResume,
      )
    if (requiresLlmConfig && !llmSettings.isConfigured()) {
      return ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.FAILED,
        errorCode = ERROR_CODE_MISSING_LLM_CONFIG,
        errorMessage = "LLM configuration is incomplete.",
        startedAtEpochMs = System.currentTimeMillis(),
        finishedAtEpochMs = System.currentTimeMillis(),
        metadata = task.metadata,
      )
    }

    val routeProviderId = llmSettings.providerId.ifBlank { "openai-compatible" }
    val routeMetadata = if (requiresLlmConfig) {
      effectiveLlmRouteMetadata(settings = llmSettings)
    } else {
      emptyMap()
    }
    val gateway: LiteLlmGateway = if (requiresLlmConfig) {
      val route = ProviderRoute(
        id = "route-$routeProviderId",
        providerId = routeProviderId,
        baseUrl = llmSettings.baseUrl,
        model = llmSettings.model,
        timeoutMs = recommendedInteractiveProviderRouteTimeoutMs(llmSettings.model),
        metadata = routeMetadata,
      )
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(
          ProviderRouting(
            activeProfileId = "profile-default",
            profiles = listOf(
              ModelProfile(
                id = "profile-default",
                displayName = "Default",
                primaryRouteId = route.id,
                routes = listOf(route),
              ),
            ),
          ),
        ),
        providerClient = OpenAiCompatibleLiteLlmProviderClient(
          userAgent = providerUserAgent,
        ),
      )
    } else {
      NonPromptTaskLiteLlmGateway
    }
    val pendingMessageId = task.metadata[METADATA_PENDING_MESSAGE_ID].orEmpty()
    val visibleThroughMessageId = task.metadata[METADATA_VISIBLE_THROUGH_MESSAGE_ID].orEmpty()
    val hostUiTaskPreapproved = hostUiApprovedToolName(task) != null
    val nativeWebSearchRunApproved =
      approvalGrant?.toolName == ProviderNativeWebSearchSupport.RESUME_TOOL_NAME
    val nativeWebSearchSessionApproved =
      nativeWebSearchSessionApprovalProvider(sessionId)
    val promptResumeState = (
      approvalGrant?.promptResumeState
        ?: approvalRejection?.promptResumeState
        ?: generalPromptResumeStateForExecution(sessionId, task.id)
      )
      ?.takeIf { task.type == com.opencray.core.contracts.AgentTaskType.PROMPT }
    val promptResumeCheckpointBoundary = (
      approvalGrant?.promptCheckpointBoundary
        ?: approvalRejection?.promptCheckpointBoundary
        ?: promptResumeCheckpointBoundaryForExecution(sessionId, task.id)
      )
      ?.takeIf {
        promptResumeState != null && task.type == com.opencray.core.contracts.AgentTaskType.PROMPT
      }
    val transcriptStore = transcriptStoreForSession(sessionId)
    val supplementStore = supplementStoreForSession(sessionId)
    val memoryRecords = memoryRecordsProvider()
    val skillPackageManager = skillPackageManagerProvider()
    val skillPolicyReadRoots = skillPackageManager?.let { manager ->
      setOf(
        manager.managedRootPath().toPath(),
        manager.catalogRootPath().toPath(),
      )
    }.orEmpty()
    val skillPolicyWriteRoots = skillPackageManager?.let { manager ->
      buildSet {
        add(manager.managedRootPath().toPath())
        manager.compatStagingRootPath()?.let(::add)
      }
    }.orEmpty()
    val workspaceId = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider())
    val llmMetadata = buildRuntimeLlmMetadata(
      requiresLlmConfig = requiresLlmConfig,
      taskMetadata = task.metadata,
      sessionId = sessionId,
      nativeWebSearchRunApproved = nativeWebSearchRunApproved,
      nativeWebSearchSessionApproved = nativeWebSearchSessionApproved,
      llmSettings = llmSettings,
      routeMetadata = routeMetadata,
    )
    val preparedContext = prepareSessionContext(
      sessionId = sessionId,
      workspaceId = workspaceId,
      visibleThroughMessageId = visibleThroughMessageId.takeIf(String::isNotBlank),
      excludedMessageIds = pendingMessageId.takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
      soulProfile = soulProfileProvider(),
      taskType = task.type,
      taskId = task.id,
      taskInput = task.input,
      transcriptStore = transcriptStore,
      memoryRecords = memoryRecords,
      appendTaskInputToTranscript = task.type == com.opencray.core.contracts.AgentTaskType.PROMPT &&
        promptResumeState == null,
      llmMetadata = llmMetadata,
      liveContextMode = liveContextModeProvider(),
      memoryToolsEnabled = safetySettings.memoryToolsEnabled,
    )
    val sessionContext = preparedContext.sessionContext
    val effectiveMemoryRecords = preparedContext.effectiveMemoryRecords
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = workspaceRootsProvider(),
          readRoots = readRootsProvider(),
          hiddenToolNamePrefixes = hiddenToolNamePrefixesProvider(),
          extraPolicyReadRoots = skillPolicyReadRoots,
          extraPolicyWriteRoots = skillPolicyWriteRoots,
          skillsRoots = skillsRootsProvider(),
          skillPackageManager = skillPackageManager,
          mcpExposureReport = mcpReportProvider(),
          // Host UI tool actions are already user-initiated, so nested policy gates should not
          // bounce them back into chat approval just because the internal gate uses Read/WebFetch.
          approvedTaskId = task.id.takeIf {
            approvalGrant != null || hostUiTaskPreapproved
          },
          approvedToolName = approvalGrant?.toolName,
          rejectedTaskId = task.id.takeIf { approvalRejection != null },
          rejectedToolName = approvalRejection?.toolName,
          commandExecutor = commandExecutorProvider(),
          pythonRuntimeAdapter = pythonRuntimeProvider(),
          pythonRuntimeManifestProvider = pythonRuntimeManifestProvider,
          supportsManagedPythonProcessStart = true,
          managedPythonProcessUsesRuntimeAdapter = true,
          todoStore = todoStoreForSession(sessionId),
          processRegistry = processRegistryForSession(sessionId),
          webSearchProvider = webSearchProviderFactory(),
          sandboxPreviewService = sandboxPreviewServiceProvider(),
          sandboxSessionControlService = sandboxSessionControlServiceProvider(),
          sandboxSessionInfoService = sandboxSessionInfoServiceProvider(),
          mediaToolSettingsProvider = mediaToolSettingsProvider,
          imageGenerationClient = imageGenerationClientProvider(),
          speechSynthesisClient = speechSynthesisClientProvider(),
          chatAttachmentResolver = fun(chatAttachmentId: String): OpenCrayChatAttachmentSource? {
            val attachment = sessionContextFactory.resolveChatAttachmentEntry(
              sessionId = sessionId,
              attachmentId = chatAttachmentId,
            ) ?: return null
            val sourcePath = sessionContextFactory.resolveChatAttachmentFilePath(attachment) ?: return null
            return OpenCrayChatAttachmentSource(
              attachmentId = attachment.attachmentId,
              displayName = attachment.displayName,
              sourcePath = sourcePath,
              mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
            )
          },
          memoryToolContext = if (safetySettings.memoryToolsEnabled) {
            MemoryToolContext(
              sessionId = sessionId,
              workspaceId = workspaceId,
              records = effectiveMemoryRecords,
            )
          } else {
            null
          },
        ),
        managedProcessObservationTracker = managedProcessObservationTrackerForSession(sessionId),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = safetySettings.maxAgentTurns,
        maxToolCalls = safetySettings.maxToolCalls,
        systemPrompt = llmSettings.systemPrompt.ifBlank {
          OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT
        },
        sessionContext = sessionContext,
        promptResumeState = promptResumeState,
        promptResumeCheckpointBoundary = promptResumeCheckpointBoundary,
        approvedSubAgentResume = approvedSubAgentResume,
        rejectedSubAgentResume = rejectedSubAgentResume,
        workingStateStore = workingStateStoreForSession(sessionId),
        subAgentExecutionCoordinator = subAgentExecutionCoordinatorForSession(sessionId),
        seededSubAgentHandles = subAgentExecutionCoordinatorForSession(sessionId).allHandles(),
        supplementInputProvider = { runId, taskId ->
          supplementStore.consumeForRun(runId = runId, taskId = taskId)
            .map { entry ->
              OpenCraySupplementInput(
                entryId = entry.entryId,
                text = entry.text,
                createdAtEpochMs = entry.createdAtEpochMs,
              )
            }
        },
        promptCheckpointSink = { emission ->
          persistRuntimePromptCheckpoint(
            sessionId = sessionId,
            task = task,
            emission = emission,
          )
        },
        llmMetadata = llmMetadata,
        llmAuthHeaders = if (requiresLlmConfig) {
          LlmProviderProtocols.authHeaders(
            protocol = llmSettings.protocol,
            apiKey = llmSettings.apiKey,
          )
        } else {
          emptyMap()
        },
        subAgentContextPolicy = safetySettings.toRuntimeSubAgentContextPolicy(),
      ),
      eventSink = transcriptAwareEventSink(
        sessionId = sessionId,
        transcriptStore = transcriptStore,
        delegate = eventSink,
      ),
    )
    val result = when (detachedControlTask) {
      is DetachedSubAgentRecoveryWaitTaskSpec -> {
        runtime.ensureDetachedSubAgentRecoveryExecution(
          task = task,
          hooks = hooks,
          agentId = detachedControlTask.agentId,
          parentRunId = detachedControlTask.parentRunId,
        )
        runtime.executeDetachedSubAgentRecoveryWait(
          task = task,
          hooks = hooks,
          agentId = detachedControlTask.agentId,
          parentRunId = detachedControlTask.parentRunId,
        )
      }

      null -> runtime.execute(task, hooks)
    }
    recordSuccessfulAssistantTurn(
      sessionId = sessionId,
      task = task,
      result = result,
    )
    finalizeWorkingStateAfterTask(
      sessionId = sessionId,
      task = task,
      result = result,
    )
    return result
  }

  internal fun todoStoreForSession(sessionId: String): AgentTodoStore =
    todoStoresBySession.computeIfAbsent(sessionId, todoStoreProvider)

  internal fun workingStateStoreForSession(sessionId: String): WorkingStateStore =
    workingStateStoresBySession.computeIfAbsent(sessionId, workingStateStoreProvider)

  internal fun promptCheckpointStoreForSession(sessionId: String): PromptCheckpointStore =
    promptCheckpointStoresBySession.computeIfAbsent(sessionId, promptCheckpointStoreProvider)

  internal fun approvalContinuationForExecution(
    sessionId: String,
    taskId: String,
  ): ApprovalContinuation {
    val approvalGrant = approvalRegistry.consumeApproved(sessionId, taskId)
    if (approvalGrant != null) {
      return ApprovalContinuation(grant = approvalGrant)
    }
    val approvalRejection = approvalRegistry.consumeRejected(sessionId, taskId)
    if (approvalRejection != null) {
      return ApprovalContinuation(rejection = approvalRejection)
    }
    val durableCheckpoint = promptCheckpointStoreForSession(sessionId)
      .get(taskId)
      ?.takeIf { checkpoint ->
        checkpoint.checkpointKind == PromptCheckpointKind.APPROVED_PENDING_RESUME ||
          checkpoint.checkpointKind == PromptCheckpointKind.REJECTED_PENDING_RESUME
      }
      ?: return ApprovalContinuation()
    return when (durableCheckpoint.checkpointKind) {
      PromptCheckpointKind.APPROVED_PENDING_RESUME ->
        ApprovalContinuation(grant = durableCheckpoint.toApprovalGrantOrNull())

      PromptCheckpointKind.REJECTED_PENDING_RESUME ->
        ApprovalContinuation(rejection = durableCheckpoint.toApprovalRejectionOrNull())

      PromptCheckpointKind.PRE_MODEL_REQUEST,
      PromptCheckpointKind.ACTION_BATCH_PARSED,
      PromptCheckpointKind.COMMENTARY_EMITTED,
      PromptCheckpointKind.TOOL_RESULT_COMMITTED,
      PromptCheckpointKind.SUPPLEMENT_INGESTED,
      PromptCheckpointKind.WAITING_APPROVAL,
      PromptCheckpointKind.GENERAL_RESUME,
      -> ApprovalContinuation()
    }
  }

  private fun hostUiApprovedToolName(task: AgentTask): String? {
    if (task.type != com.opencray.core.contracts.AgentTaskType.TOOL_CALL) {
      return null
    }
    val submissionSource = task.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (submissionSource != RunSubmissionSources.HOST_UI_TOOL_ACTION) {
      return null
    }
    val metadataToolName = task.metadata[RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val payloadToolName = directToolNameFrom(task.input) ?: return null
    return metadataToolName.takeIf { it == payloadToolName }
  }

  private fun directToolCallRequiresLlm(
    sessionId: String,
    task: AgentTask,
    approvedSubAgentResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    rejectedSubAgentResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  ): Boolean {
    if (task.type != com.opencray.core.contracts.AgentTaskType.TOOL_CALL) {
      return false
    }
    val normalizedToolName = directToolNameFrom(task.input)
      ?.lowercase()
      ?: return false
    if (normalizedToolName != "spawn_agent" && normalizedToolName != "wait_agent") {
      return false
    }
    val agentId = directToolAgentIdFrom(task.input)
    val existingHandle = agentId?.let { resolvedAgentId ->
      subAgentExecutionCoordinatorForSession(sessionId)
        .allHandles()
        .firstOrNull { handle -> handle.agentId == resolvedAgentId }
    }
    val hasApprovalContinuation = approvedSubAgentResume != null || rejectedSubAgentResume != null
    return when {
      existingHandle == null -> normalizedToolName == "spawn_agent"
      existingHandle.snapshot.state in setOf(
        SubAgentExecutionState.COMPLETED,
        SubAgentExecutionState.CANCELLED,
        SubAgentExecutionState.FAILED,
      ) && existingHandle.pendingApprovalResume == null -> false
      existingHandle.pendingApprovalResume != null && !hasApprovalContinuation -> false
      else -> true
    }
  }

  private fun detachedControlRequiresLlm(
    sessionId: String,
    detachedControlTask: DetachedControlTaskSpec?,
    approvedSubAgentResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    rejectedSubAgentResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  ): Boolean {
    val recoveryTask = detachedControlTask as? DetachedSubAgentRecoveryWaitTaskSpec ?: return false
    val existingHandle = subAgentExecutionCoordinatorForSession(sessionId).currentHandle(
      com.opencray.runtime.subagent.SubAgentExecutionKey(
        parentRunId = recoveryTask.parentRunId,
        agentId = recoveryTask.agentId,
      ),
    ) ?: return false
    val hasApprovalContinuation = approvedSubAgentResume != null || rejectedSubAgentResume != null
    return when {
      existingHandle.snapshot.state in setOf(
        SubAgentExecutionState.COMPLETED,
        SubAgentExecutionState.CANCELLED,
        SubAgentExecutionState.FAILED,
      ) && existingHandle.pendingApprovalResume == null -> false

      existingHandle.pendingApprovalResume != null && !hasApprovalContinuation -> false
      else -> true
    }
  }

  private fun directToolNameFrom(taskInput: String): String? = runCatching {
    val payload = replayJson.parseToJsonElement(taskInput) as? JsonObject ?: return null
    (payload["tool_name"] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
  }.getOrNull()

  private fun directToolAgentIdFrom(taskInput: String): String? = runCatching {
    val payload = replayJson.parseToJsonElement(taskInput) as? JsonObject ?: return null
    val arguments = payload["arguments"] as? JsonObject ?: return null
    sequenceOf(
      (arguments["agent_id"] as? JsonPrimitive)?.content,
      (arguments["id"] as? JsonPrimitive)?.content,
      ((arguments["agent_ids"] as? kotlinx.serialization.json.JsonArray)
        ?.firstOrNull() as? JsonPrimitive)
        ?.content,
      ((arguments["ids"] as? kotlinx.serialization.json.JsonArray)
        ?.firstOrNull() as? JsonPrimitive)
        ?.content,
    ).mapNotNull { value ->
      value?.trim()?.takeIf(String::isNotBlank)
    }.firstOrNull()
  }.getOrNull()

  internal fun generalPromptResumeStateForExecution(
    sessionId: String,
    taskId: String,
  ): com.opencray.runtime.OpenCrayPromptResumeState? =
    promptCheckpointStoreForSession(sessionId)
      .get(taskId)
      ?.takeIf { checkpoint -> checkpoint.checkpointKind.isGeneralPromptResumeKind() }
      ?.promptResumeState

  internal fun promptResumeCheckpointBoundaryForExecution(
    sessionId: String,
    taskId: String,
  ): OpenCrayPromptCheckpointBoundary? =
    promptCheckpointStoreForSession(sessionId)
      .get(taskId)
      ?.takeIf { checkpoint -> checkpoint.checkpointKind.isCheckpointResumeKind() }
      ?.runtimeCheckpointBoundaryOrNull()

  private fun persistRuntimePromptCheckpoint(
    sessionId: String,
    task: AgentTask,
    emission: OpenCrayPromptCheckpointEmission,
  ) {
    if (task.type != com.opencray.core.contracts.AgentTaskType.PROMPT) {
      return
    }
    val nowEpochMs = emission.emittedAtEpochMs
    promptCheckpointStoreForSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = task.metadata[METADATA_RUN_ID]?.trim()?.takeIf(String::isNotBlank) ?: task.id,
        taskId = task.id,
        checkpointId = "checkpoint-$nowEpochMs-${emission.boundary.wireValue}",
        checkpointKind = emission.boundary.toPromptCheckpointKind(),
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
        toolName = emission.toolName?.trim()?.takeIf(String::isNotBlank),
        pendingMessageId = task.metadata[METADATA_PENDING_MESSAGE_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        promptCheckpointBoundary = emission.boundary,
        promptResumeState = emission.state,
      ),
    )
    runEventJournalStoreFactory.forChatSession(sessionId).appendCheckpoint(
      runId = task.metadata[METADATA_RUN_ID]?.trim()?.takeIf(String::isNotBlank) ?: task.id,
      taskId = task.id,
      emission = emission,
    )
  }

  private fun OpenCrayPromptCheckpointBoundary.toPromptCheckpointKind(): PromptCheckpointKind =
    when (this) {
      OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST -> PromptCheckpointKind.PRE_MODEL_REQUEST
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED -> PromptCheckpointKind.ACTION_BATCH_PARSED
      OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED -> PromptCheckpointKind.COMMENTARY_EMITTED
      OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED ->
        PromptCheckpointKind.TOOL_RESULT_COMMITTED

      OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED ->
        PromptCheckpointKind.SUPPLEMENT_INGESTED
    }

  private object NonPromptTaskLiteLlmGateway : LiteLlmGateway {
    override fun execute(request: LiteLlmGatewayRequest) =
      error("LiteLlmGateway is unavailable for non-prompt tasks.")
  }

  internal fun processRegistryForSession(sessionId: String): AgentProcessRegistry =
    processRegistriesBySession.computeIfAbsent(sessionId, processRegistryProvider)

  internal fun managedProcessObservationTrackerForSession(
    sessionId: String,
  ): ManagedProcessObservationTracker =
    managedProcessObservationTrackersBySession.computeIfAbsent(sessionId) {
      ManagedProcessObservationTracker()
    }

  internal fun transcriptStoreForSession(sessionId: String): SessionTranscriptStore =
    transcriptStoresBySession.computeIfAbsent(sessionId, transcriptStoreProvider)

  internal fun supplementStoreForSession(sessionId: String): SessionSupplementStore =
    supplementStoresBySession.computeIfAbsent(sessionId, supplementStoreProvider)

  internal fun compactionStoreForSession(sessionId: String): SessionCompactionStore =
    compactionStoresBySession.computeIfAbsent(sessionId, compactionStoreProvider)

  internal fun subAgentHandleStoreForSession(sessionId: String): SubAgentHandleStore =
    subAgentHandleStoresBySession.computeIfAbsent(sessionId, subAgentHandleStoreProvider)

  internal fun subAgentExecutionCoordinatorForSession(
    sessionId: String,
  ): SubAgentExecutionCoordinator =
    subAgentExecutionCoordinatorsBySession.computeIfAbsent(
      sessionId,
    ) { resolvedSessionId ->
      subAgentExecutionCoordinatorProvider?.invoke(resolvedSessionId)
        ?: PersistentSessionSubAgentExecutionCoordinator(
          store = subAgentHandleStoreForSession(resolvedSessionId),
        )
    }

  internal fun recordSuccessfulToolInteraction(
    sessionId: String,
    event: OpenCrayToolResultEvent,
  ) {
    if (event.result.status != AgentToolResultStatus.SUCCESS) {
      return
    }
    recordSuccessfulToolInteraction(
      transcriptStore = transcriptStoreForSession(sessionId),
      event = event,
    )
  }

  internal fun recordApprovalRejection(
    sessionId: String,
    taskId: String,
    runId: String,
    toolName: String?,
    isHighRisk: Boolean,
    executionContext: RuntimeReplayExecutionContext = RuntimeReplayExecutionContext(),
  ) {
    transcriptStoreForSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildString {
          append("approval_rejected")
          append(" task_id=")
          append(taskId)
          append(" run_id=")
          append(runId)
          toolName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { resolvedToolName ->
              append(" tool_name=")
              append(resolvedToolName)
            }
          append(" outcome=user_rejected")
          append(" executed=false")
          if (isHighRisk) {
            append(" risk=high_risk")
          }
          append(" next_step=await_user_instruction")
          appendReplayExecutionContext(executionContext)
        },
      ),
    )
  }

  internal fun recordApprovalApproved(
    sessionId: String,
    taskId: String,
    runId: String,
    toolName: String?,
    isHighRisk: Boolean,
    executionContext: RuntimeReplayExecutionContext = RuntimeReplayExecutionContext(),
  ) {
    transcriptStoreForSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildString {
          append("approval_approved")
          append(" task_id=")
          append(taskId)
          append(" run_id=")
          append(runId)
          toolName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { resolvedToolName ->
              append(" tool_name=")
              append(resolvedToolName)
            }
          append(" outcome=user_approved")
          append(" executed=false")
          if (isHighRisk) {
            append(" risk=high_risk")
          }
          append(" next_step=agent_resumed")
          appendReplayExecutionContext(executionContext)
        },
      ),
    )
  }

  internal fun recordSubAgentReplayEvent(
    sessionId: String,
    event: OpenCraySubAgentEvent,
  ) {
    appendIfMissing(
      transcriptStore = transcriptStoreForSession(sessionId),
      message = RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildSubAgentReplayContent(event),
      ),
    )
  }

  internal fun recordRunCancellation(
    sessionId: String,
    taskId: String,
    runId: String,
    toolName: String? = null,
    executionContext: RuntimeReplayExecutionContext = RuntimeReplayExecutionContext(),
  ) {
    transcriptStoreForSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildString {
          append("run_interrupted")
          append(" task_id=")
          append(taskId)
          append(" run_id=")
          append(runId)
          toolName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { resolvedToolName ->
              append(" tool_name=")
              append(resolvedToolName)
              append(" executed=false")
            }
          append(" outcome=user_interrupted")
          append(" next_step=await_user_instruction")
          appendReplayExecutionContext(executionContext)
        },
      ),
    )
  }

  internal fun repairTerminalReplayFromRunSnapshots(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ) {
    val store = transcriptStoreForSession(sessionId)
    runs
      .sortedBy(AgentRunSnapshot::acceptedAtEpochMs)
      .forEach { run ->
        val observation = terminalReplayObservationFor(
          existingMessages = store.snapshot(),
          run = run,
        ) ?: return@forEach
        store.appendIfDistinct(observation)
      }
  }

  internal fun recalledMemoryFor(
    sessionId: String,
    taskInput: String,
    memoryRecords: List<MemoryRecord> = memoryRecordsProvider(),
    workspaceId: String? = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()),
  ): MemoryRecallResult = memoryRetriever.retrieve(
    records = memoryRecords,
    request = MemoryRecallRequest(
      sessionId = sessionId,
      userInput = taskInput,
      workspaceId = workspaceId,
    ),
  )

  internal fun effectiveSoulProfileFor(
    sessionId: String,
    soulProfile: WorkspaceSoulProfile?,
    memoryRecords: List<MemoryRecord> = memoryRecordsProvider(),
    workspaceId: String? = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()),
    ) = memoryBackedSoulResolver.overlay(
    baseProfile = sessionContextFactory.create(
      sessionId = sessionId,
      soulProfile = soulProfile,
    ).soulProfile,
    records = memoryRecords,
    sessionId = sessionId,
    workspaceId = workspaceId,
  )

  internal fun visibleSkillInventoryFor() =
    skillInventoryResolver.resolve(skillsRootsProvider())

  internal fun bootstrapContextFor(mode: BootstrapMode) =
    bootstrapContextResolver.resolve(
      workspaceRoots = workspaceRootsProvider(),
      mode = mode,
    )

  internal fun liveContextPolicyFor(mode: LiveContextMode): LiveContextPolicy =
    liveContextPolicyFrom(mode)

  internal fun skillCatalogFor() =
    skillCatalogResolver.resolve(skillsRootsProvider())

  private fun buildRuntimeLlmMetadata(
    requiresLlmConfig: Boolean,
    taskMetadata: Map<String, String>,
    sessionId: String,
    nativeWebSearchRunApproved: Boolean,
    nativeWebSearchSessionApproved: Boolean,
    llmSettings: LlmSettingsState,
    routeMetadata: Map<String, String>,
  ): Map<String, String> = if (requiresLlmConfig) {
    buildMap {
      putAll(taskMetadata.filterKeys(::isLlmVisibleMetadataKey))
      put("sessionId", sessionId)
      put(
        ProviderNativeWebSearchSupport.LLM_METADATA_RUN_APPROVED,
        nativeWebSearchRunApproved.toString(),
      )
      put(
        ProviderNativeWebSearchSupport.LLM_METADATA_SESSION_APPROVED,
        nativeWebSearchSessionApproved.toString(),
      )
      put(HOST_METADATA_PROVIDER_ID, llmSettings.providerId)
      putAll(routeMetadata)
      putAll(llmSettings.agentCapability.runtimeMetadataOverrides())
    }
  } else {
    mapOf("sessionId" to sessionId)
  }

  internal fun prepareSessionContext(
    sessionId: String,
    workspaceId: String?,
    visibleThroughMessageId: String?,
    excludedMessageIds: Set<String>,
    soulProfile: WorkspaceSoulProfile?,
    taskType: com.opencray.core.contracts.AgentTaskType,
    taskId: String,
    taskInput: String,
    transcriptStore: SessionTranscriptStore,
    memoryRecords: List<MemoryRecord>,
    appendTaskInputToTranscript: Boolean = taskType == com.opencray.core.contracts.AgentTaskType.PROMPT,
    llmMetadata: Map<String, String> = emptyMap(),
    liveContextMode: LiveContextMode = LiveContextMode.FULL,
    memoryToolsEnabled: Boolean = safetySettingsProvider().sanitized().memoryToolsEnabled,
  ): PreparedSessionContext {
    val liveContextPolicy = liveContextPolicyFor(liveContextMode)
    val baseContext = sessionContextFactory.create(
      sessionId = sessionId,
      visibleThroughMessageId = visibleThroughMessageId,
      excludedMessageIds = excludedMessageIds,
      soulProfile = soulProfile.takeIf { liveContextPolicy.soulEnabled },
      workingState = workingStateStoreForSession(sessionId).snapshot(),
    )
    transcriptStore.seedIfEmpty(baseContext.conversation)
    if (appendTaskInputToTranscript) {
      taskInput.trim()
        .takeIf(String::isNotBlank)
        ?.let { normalizedInput ->
          transcriptStore.appendIfDistinct(
            RuntimeConversationMessage(
              role = RuntimeConversationRole.USER,
              content = normalizedInput,
            ),
          )
        }
    }
    val memoryFlushSummary = if (taskType == com.opencray.core.contracts.AgentTaskType.PROMPT) {
      memoryIngestionCoordinator?.flushBeforeCompaction(
        sessionId = sessionId,
        conversation = transcriptStore.snapshot(),
        llmMetadata = llmMetadata,
        taskId = taskId,
      )
    } else {
      null
    }
    val effectiveMemoryRecords = if (memoryFlushSummary?.wasWritten == true) {
      memoryRecordsProvider()
    } else {
      memoryRecords
    }
    val durableCompaction = if (taskType == com.opencray.core.contracts.AgentTaskType.PROMPT) {
      durableCompactionCoordinator.compactIfNeeded(
        transcriptStore = transcriptStore,
        compactionStore = compactionStoreForSession(sessionId),
        llmMetadata = llmMetadata,
      )
    } else {
      durableCompactionCoordinator.currentContext(compactionStoreForSession(sessionId))
    }
    val skillCatalog = skillCatalogFor()
    val bootstrapContext = bootstrapContextFor(
      mode = if (taskType == com.opencray.core.contracts.AgentTaskType.PROMPT) {
        liveContextPolicy.bootstrapMode
      } else {
        BootstrapMode.NONE
      },
    )
    val turnSemanticSignal = if (
      taskType == com.opencray.core.contracts.AgentTaskType.PROMPT &&
      liveContextPolicy.injectionPolicy.soulTurnPolicyEnabled
    ) {
      when (
        val interpretation = soulTurnSemanticSignalInterpreter.interpret(
          SoulTurnSemanticSignalRequest(
            sessionId = sessionId,
            taskId = taskId,
            userInput = taskInput,
            conversation = transcriptStore.snapshot(),
          ),
        )
      ) {
        is SoulTurnSemanticSignalInterpretation.Success -> interpretation.signal
        is SoulTurnSemanticSignalInterpretation.Unavailable -> null
      }
    } else {
      null
    }
    return PreparedSessionContext(
      sessionContext = baseContext.copy(
        soulProfile = if (liveContextPolicy.soulEnabled) {
          memoryBackedSoulResolver.overlay(
            baseProfile = baseContext.soulProfile,
            records = effectiveMemoryRecords,
            sessionId = sessionId,
            workspaceId = workspaceId,
          )
        } else {
          baseContext.soulProfile
        },
        turnSemanticSignal = turnSemanticSignal,
        injectionPolicy = liveContextPolicy.injectionPolicy,
        memoryToolsEnabled = memoryToolsEnabled,
        liveContextTrace = LiveContextTrace(
          mode = liveContextMode.wireValue,
          soulEnabled = liveContextPolicy.soulEnabled,
          memoryRecallEnabled = liveContextPolicy.memoryRecallEnabled,
        ),
        recalledMemory = if (liveContextPolicy.memoryRecallEnabled) {
          recalledMemoryFor(
            sessionId = sessionId,
            taskInput = taskInput,
            memoryRecords = effectiveMemoryRecords,
            workspaceId = workspaceId,
          )
        } else {
          MemoryRecallResult()
        },
        memoryFlushTrace = memoryFlushSummary?.trace
          ?: com.opencray.runtime.memory.MemoryFlushTrace(),
        durableCompaction = durableCompaction,
        bootstrapContext = bootstrapContext,
        skillInventory = skillCatalog.inventory,
        skillCatalog = skillCatalog,
        conversation = transcriptStore.snapshot(),
      ),
      effectiveMemoryRecords = effectiveMemoryRecords,
    )
  }

  private fun transcriptAwareEventSink(
    sessionId: String,
    transcriptStore: SessionTranscriptStore,
    delegate: OpenCrayAgentRuntimeEventSink,
  ): OpenCrayAgentRuntimeEventSink = object : OpenCrayAgentRuntimeEventSink {
    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      when (event) {
        is OpenCraySupplementEvent -> recordSupplementReplayEvent(
          transcriptStore = transcriptStore,
          event = event,
        )

        is OpenCrayToolResultEvent -> if (event.result.status == AgentToolResultStatus.SUCCESS) {
          recordSuccessfulToolInteraction(
            transcriptStore = transcriptStore,
            event = event,
          )
        }

        is OpenCrayAssistantPhaseEvent -> recordAssistantReplayEvent(
          transcriptStore = transcriptStore,
          event = event.toAssistantEvent(),
        )

        is OpenCraySubAgentEvent -> recordSubAgentReplayEvent(sessionId = sessionId, event = event)

        else -> Unit
      }
      delegate.onRunEvent(task, event)
    }
  }

  private fun recordSuccessfulAssistantTurn(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    if (task.type != com.opencray.core.contracts.AgentTaskType.PROMPT) {
      return
    }
    if (result.status != ExecutionStatus.SUCCESS) {
      return
    }
    result.stdout
      .trim()
      .takeIf(String::isNotBlank)
      ?.let { assistantText ->
        transcriptStoreForSession(sessionId).appendIfDistinct(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = assistantText,
            assistantPhase = RuntimeConversationAssistantPhase.FINAL_ANSWER,
          ),
        )
      }
  }

  internal fun finalizeWorkingStateAfterTask(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    if (task.type != com.opencray.core.contracts.AgentTaskType.PROMPT) {
      return
    }
    if (result.status != ExecutionStatus.SUCCESS) {
      return
    }
    val hasOpenTodo = todoStoreForSession(sessionId).snapshot().any { entry ->
      entry.status != AgentTodoStatus.COMPLETED
    }
    if (hasOpenTodo) {
      return
    }
    workingStateStoreForSession(sessionId).replace(WorkingState())
  }

  private fun recordSupplementReplayEvent(
    transcriptStore: SessionTranscriptStore,
    event: OpenCraySupplementEvent,
  ) {
    val replayContent = buildSupplementReplayContent(event)
    if (transcriptStore.snapshot().any { message -> message.content == replayContent }) {
      return
    }
    val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
      metadata = event.metadata,
      json = replayJson,
    )
    if (event.text.isBlank() && attachments.isEmpty()) {
      return
    }
    transcriptStore.appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = event.text,
        attachments = attachments,
      ),
    )
    appendIfMissing(
      transcriptStore = transcriptStore,
      message = RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = replayContent,
      ),
    )
  }

  private fun recordSuccessfulToolInteraction(
    transcriptStore: SessionTranscriptStore,
    event: OpenCrayToolResultEvent,
  ) {
    val callObservation = RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = buildToolCallReplayContent(event),
      kind = RuntimeConversationMessageKind.TOOL_CALL,
      toolCall = RuntimeConversationToolCall(
        id = event.call.id,
        toolName = event.call.toolName,
        arguments = event.call.arguments,
        reason = event.call.reason,
      ),
    )
    val resultObservation = RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildToolResultReplayContent(event),
      kind = RuntimeConversationMessageKind.TOOL_RESULT,
      toolResult = RuntimeConversationToolResult(
        toolCallId = event.call.id,
        toolName = event.result.toolName,
        status = event.result.status.name.lowercase(),
        isError = event.result.status != AgentToolResultStatus.SUCCESS,
      ),
    )
    appendIfMissing(
      transcriptStore = transcriptStore,
      message = callObservation,
    )
    appendIfMissing(
      transcriptStore = transcriptStore,
      message = resultObservation,
    )
  }

  private fun recordAssistantReplayEvent(
    transcriptStore: SessionTranscriptStore,
    event: OpenCrayAssistantEvent,
  ) {
    if (event.isFinal) {
      return
    }
    appendIfMissing(
      transcriptStore = transcriptStore,
      message = RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = buildCommentaryReplayContent(event),
        kind = RuntimeConversationMessageKind.COMMENTARY,
        commentary = RuntimeConversationCommentary(
          runId = event.runId,
          taskId = event.taskId,
          turn = event.turn,
          text = event.text,
          stage = event.stage,
        ),
        assistantPhase = RuntimeConversationAssistantPhase.COMMENTARY,
      ),
    )
  }

  private fun appendIfMissing(
    transcriptStore: SessionTranscriptStore,
    message: RuntimeConversationMessage,
  ) {
    val existingContents = transcriptStore.snapshot().mapTo(linkedSetOf(), RuntimeConversationMessage::content)
    if (message.content in existingContents) {
      return
    }
    transcriptStore.appendIfDistinct(message)
  }

  private fun buildToolCallReplayContent(event: OpenCrayToolResultEvent): String =
    encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      event.executionId?.let { executionId -> put("execution_id", executionId) }
      event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
      event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
      put("turn", event.turn)
      event.call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
      put("tool_name", event.call.toolName)
      event.call.reason
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reason ->
          put("reason", collapseReplayWhitespace(reason))
        }
      put("arguments", event.call.arguments)
    }

  private fun buildToolResultReplayContent(event: OpenCrayToolResultEvent): String =
    encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      event.executionId?.let { executionId -> put("execution_id", executionId) }
      event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
      event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
      put("turn", event.turn)
      event.call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
      put("tool_name", event.result.toolName)
      put("status", event.result.status.name.lowercase())
      put("content", event.result.content)
      event.result.exitCode?.let { exitCode -> put("exit_code", exitCode) }
      if (event.result.stdout.isNotBlank()) {
        put("stdout", event.result.stdout)
      }
      if (event.result.stderr.isNotBlank()) {
        put("stderr", event.result.stderr)
      }
      event.result.errorCode?.let { errorCode -> put("error_code", errorCode) }
      event.result.errorMessage?.let { errorMessage -> put("error_message", errorMessage) }
      val replayMetadata = replayMetadataSnapshot(event.result.metadata)
      if (replayMetadata.isNotEmpty()) {
        put(
          "metadata",
          buildJsonObject {
            replayMetadata.toSortedMap().forEach { (key, value) ->
              put(key, value)
            }
          },
        )
      }
    }

  private fun buildCommentaryReplayContent(event: OpenCrayAssistantEvent): String =
    encodeReplayJsonObject {
      put("event_kind", "assistant_phase")
      put("phase", event.phase.name.lowercase())
      put("run_id", event.runId)
      put("task_id", event.taskId)
      event.executionId?.let { executionId -> put("execution_id", executionId) }
      event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
      event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
      put("turn", event.turn)
      put("text", collapseReplayWhitespace(event.text))
      event.responseFormat
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { responseFormat -> put("response_format", responseFormat) }
      event.stage
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { stage -> put("stage", stage) }
    }

  private fun buildSupplementReplayContent(event: OpenCraySupplementEvent): String =
    encodeReplayJsonObject {
      put("event_kind", "supplement")
      put("run_id", event.runId)
      put("task_id", event.taskId)
      event.executionId?.let { executionId -> put("execution_id", executionId) }
      event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
      event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
      put("turn", event.turn)
      put("entry_id", event.entryId)
      put("text", collapseReplayWhitespace(event.text))
      put("checkpoint", event.checkpoint)
      val replayMetadata = replayMetadataSnapshot(event.metadata)
      if (replayMetadata.isNotEmpty()) {
        put(
          "metadata",
          buildJsonObject {
            replayMetadata.toSortedMap().forEach { (key, value) ->
              put(key, value)
            }
          },
        )
      }
    }

  private fun replayMetadataSnapshot(metadata: Map<String, String>): Map<String, String> =
    metadata.filterKeys { key ->
      key != OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON
    }

  private fun buildSubAgentReplayContent(event: OpenCraySubAgentEvent): String =
    encodeReplayJsonObject {
      put("event_kind", "subagent")
      put("run_id", event.runId)
      put("task_id", event.taskId)
      event.executionId?.let { executionId -> put("execution_id", executionId) }
      event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
      event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
      put("turn", event.turn)
      put("phase", event.phase.name.lowercase())
      put("child_run_id", event.childRunId)
      put("child_task_id", event.childTaskId)
      put("label", collapseReplayWhitespace(event.label))
      put("subagent_type", event.subagentType)
      put("context_mode", event.contextMode)
      put("depth", event.depth)
      event.executionState
        ?.wireValue
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { executionState -> put("execution_state", executionState) }
      event.continuationKind
        ?.wireValue
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { continuationKind -> put("continuation_kind", continuationKind) }
      put("resumable", event.resumable)
      put("requires_user_action", event.requiresUserAction)
      put("is_high_risk", event.isHighRisk)
      event.summary
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { summary -> put("summary", collapseReplayWhitespace(summary)) }
      event.liveContext
        ?.takeUnless { it.isEmpty }
        ?.toMap()
        ?.let { liveContext ->
          put(
            "live_context",
            buildJsonObject {
              (liveContext["mode"] as String?)?.let { put("mode", it) }
              (liveContext["soulEnabled"] as Boolean?)?.let { put("soulEnabled", it) }
              (liveContext["memoryRecallEnabled"] as Boolean?)?.let {
                put("memoryRecallEnabled", it)
              }
              (liveContext["replaySource"] as String?)?.let { put("replaySource", it) }
              (liveContext["replayMessageCount"] as Int?)?.let {
                put("replayMessageCount", it)
              }
              (liveContext["canonicalSource"] as String?)?.let {
                put("canonicalSource", it)
              }
              (liveContext["canonicalMessageCount"] as Int?)?.let {
                put("canonicalMessageCount", it)
              }
              (liveContext["canonicalHistoryPreserved"] as Boolean?)?.let {
                put("canonicalHistoryPreserved", it)
              }
            },
          )
        }
    }

  private fun encodeReplayJsonObject(builder: JsonObjectBuilder.() -> Unit): String =
    replayJson.encodeToString(
      serializer = JsonObject.serializer(),
      value = buildJsonObject(builder),
    )

  private fun collapseReplayWhitespace(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()

  private fun StringBuilder.appendReplayExecutionContext(
    executionContext: RuntimeReplayExecutionContext,
  ) {
    executionContext.executionId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { executionId ->
        append(" execution_id=")
        append(executionId)
      }
    executionContext.executionOrdinal
      ?.takeIf { executionOrdinal -> executionOrdinal > 0 }
      ?.let { executionOrdinal ->
        append(" execution_ordinal=")
        append(executionOrdinal)
      }
    executionContext.executionKind
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { executionKind ->
        append(" execution_kind=")
        append(executionKind)
      }
  }

  private fun terminalReplayObservationFor(
    existingMessages: List<RuntimeConversationMessage>,
    run: AgentRunSnapshot,
  ): RuntimeConversationMessage? {
    if (!run.isTerminal || hasTerminalReplayObservation(existingMessages, run)) {
      return null
    }
    return when {
      run.lifecycleState == QueueTaskLifecycleState.CANCELLED ||
        run.executionStatus == ExecutionStatus.CANCELLED -> RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = buildTerminalReplayContent(
            prefix = "run_interrupted",
            run = run,
            outcome = "restored_terminal_state",
          ),
        )

      run.lifecycleState == QueueTaskLifecycleState.FAILED ||
        run.executionStatus == ExecutionStatus.FAILED -> RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = buildTerminalReplayContent(
            prefix = if (run.attempt > 1) "retry_abandoned" else "run_interrupted",
            run = run,
            outcome = when {
              run.isInterruptedManagedProcessRestore() -> "restored_process_interrupted"
              run.attempt > 1 -> "retry_budget_exhausted"
              else -> "terminal_failure"
            },
          ),
        )

      else -> null
    }
  }

  private fun buildTerminalReplayContent(
    prefix: String,
    run: AgentRunSnapshot,
    outcome: String,
  ): String = buildString {
    append(prefix)
    append(" task_id=")
    append(run.taskId)
    append(" run_id=")
    append(run.runId)
    append(" outcome=")
    append(outcome)
    run.executionId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { executionId ->
        append(" execution_id=")
        append(executionId)
      }
    if (run.executionOrdinal > 0) {
      append(" execution_ordinal=")
      append(run.executionOrdinal)
    }
    run.executionKind
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { executionKind ->
        append(" execution_kind=")
        append(executionKind)
      }
    if (run.attempt > 0) {
      append(" attempt=")
      append(run.attempt)
    }
    run.errorCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedErrorCode ->
        append(" error_code=")
        append(resolvedErrorCode)
      }
    append(" next_step=await_user_instruction")
  }

  private fun hasTerminalReplayObservation(
    existingMessages: List<RuntimeConversationMessage>,
    run: AgentRunSnapshot,
  ): Boolean = existingMessages.any { message ->
    message.role == RuntimeConversationRole.TOOL &&
      message.content.contains("task_id=${run.taskId}") &&
      message.content.contains("run_id=${run.runId}") &&
      (
        message.content.startsWith("run_interrupted") ||
          message.content.startsWith("retry_abandoned")
        )
  }

  private fun AgentRunSnapshot.isInterruptedManagedProcessRestore(): Boolean =
    errorCode == ERROR_CODE_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE &&
      resultMetadata[METADATA_KEY_RESTORED_TERMINAL_STATE] ==
      RESTORED_TERMINAL_STATE_VALUE_INTERRUPTED

  private fun SkillPackageManager.compatStagingRootPath(): Path? =
    runCatching {
      javaClass.methods
        .firstOrNull { method ->
          method.name == "stagingRootPath" && method.parameterCount == 0
        }
        ?.invoke(this) as? File
    }.getOrNull()?.toPath()

  companion object {
    const val ERROR_CODE_MISSING_LLM_CONFIG: String = "MISSING_LLM_CONFIG"
    const val METADATA_HOST_PREFIX: String = "_host."
    const val HOST_METADATA_PROVIDER_ID: String = "${METADATA_HOST_PREFIX}providerId"
    const val METADATA_RUN_ID: String = "${METADATA_HOST_PREFIX}runId"
    const val METADATA_HOST_SESSION_ID: String = "${METADATA_HOST_PREFIX}sessionId"
    const val METADATA_PENDING_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}pendingMessageId"
    const val METADATA_VISIBLE_THROUGH_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}visibleThroughMessageId"
    const val METADATA_PROMPT_USER_TEXT: String = "${METADATA_HOST_PREFIX}promptUserText"
    const val METADATA_PROMPT_RUNTIME_ATTACHMENTS_JSON: String = "${METADATA_HOST_PREFIX}promptRuntimeAttachmentsJson"
    fun isLlmVisibleMetadataKey(key: String): Boolean = !key.startsWith(METADATA_HOST_PREFIX)
  }
}

internal data class PreparedSessionContext(
  val sessionContext: AgentRuntimeSessionContext,
  val effectiveMemoryRecords: List<MemoryRecord>,
)

internal data class ApprovalContinuation(
  val grant: AgentTaskApprovalGrant? = null,
  val rejection: AgentTaskApprovalRejection? = null,
)

internal fun mediaToolSettingsFor(
  mediaSettings: MediaSpeechSettingsState,
  llmSettings: LlmSettingsState,
): OpenCrayMediaToolSettings {
  val authHeaders = LlmProviderProtocols.authHeaders(
    protocol = llmSettings.protocol,
    apiKey = llmSettings.apiKey,
  )
  val imageSettings = mediaSettings.imageGeneration
  val voiceSettings = mediaSettings.voiceGeneration
  return OpenCrayMediaToolSettings(
    imageGeneration = OpenCrayImageGenerationSettings(
      provider = imageSettings.provider,
      baseUrl = imageSettings.baseUrl,
      endpoint = imageSettings.endpoint,
      model = imageSettings.model,
      authHeaders = authHeaders,
    ),
    speechSynthesis = OpenCraySpeechSynthesisSettings(
      provider = voiceSettings.provider,
      baseUrl = voiceSettings.baseUrl,
      endpoint = voiceSettings.endpoint,
      defaultModel = OpenCraySpeechSynthesisSettings.DEFAULT_MODEL,
      defaultVoice = mediaVoiceIdFromPreset(voiceSettings.voicePreset),
      authHeaders = authHeaders,
    ),
  )
}

internal fun mediaVoiceIdFromPreset(rawValue: String): String {
  val normalized = rawValue.trim()
  if (normalized.isBlank()) {
    return "alloy"
  }
  return normalized
    .substringBefore('·')
    .substringBefore('|')
    .substringBefore(',')
    .trim()
    .ifBlank { normalized }
}

private fun liveContextPolicyFrom(mode: LiveContextMode): LiveContextPolicy = when (mode) {
  LiveContextMode.FULL -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.FULL,
    soulEnabled = true,
    memoryRecallEnabled = true,
    injectionPolicy = com.opencray.runtime.context.ContextInjectionPolicy(),
  )

  LiveContextMode.LIGHTWEIGHT -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.LIGHTWEIGHT,
    soulEnabled = true,
    memoryRecallEnabled = true,
    injectionPolicy = com.opencray.runtime.context.ContextInjectionPolicy(),
  )

  LiveContextMode.NONE -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.NONE,
    soulEnabled = true,
    memoryRecallEnabled = true,
    injectionPolicy = com.opencray.runtime.context.ContextInjectionPolicy(),
  )

  LiveContextMode.NO_SOUL -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.LIGHTWEIGHT,
    soulEnabled = false,
    memoryRecallEnabled = true,
    injectionPolicy = com.opencray.runtime.context.ContextInjectionPolicy(
      soulContractEnabled = false,
      soulTurnPolicyEnabled = false,
      automaticMemoryInjectionEnabled = true,
      memoryDerivedPolicyEnabled = true,
    ),
  )

  LiveContextMode.NO_MEMORY_OR_SOUL -> LiveContextPolicy(
    bootstrapMode = BootstrapMode.LIGHTWEIGHT,
    soulEnabled = false,
    memoryRecallEnabled = false,
    injectionPolicy = com.opencray.runtime.context.ContextInjectionPolicy(
      soulContractEnabled = false,
      soulTurnPolicyEnabled = false,
      automaticMemoryInjectionEnabled = false,
      memoryDerivedPolicyEnabled = false,
    ),
  )
}

private const val ERROR_CODE_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE: String =
  "PROCESS_INTERRUPTED_ON_RESTORE"
private const val METADATA_KEY_RESTORED_TERMINAL_STATE: String = "restoredTerminalState"
private const val RESTORED_TERMINAL_STATE_VALUE_INTERRUPTED: String = "interrupted"
