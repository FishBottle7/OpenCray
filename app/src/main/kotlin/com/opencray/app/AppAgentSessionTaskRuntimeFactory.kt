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
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.HostProcessPythonRuntime
import com.opencray.runtime.InMemoryAgentTodoStore
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayImageGenerationClient
import com.opencray.runtime.OpenCrayMediaToolSettings
import com.opencray.runtime.OpenCrayProgressEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySpeechSynthesisClient
import com.opencray.runtime.OpenCraySpeechSynthesisSettings
import com.opencray.runtime.OpenCrayImageGenerationSettings
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCraySupplementInput
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.bootstrap.BootstrapContextResolver
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.compaction.DurableCompactionCoordinator
import com.opencray.runtime.compaction.InMemorySessionCompactionStore
import com.opencray.runtime.compaction.SessionCompactionStore
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationProgress
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
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.NoOpSoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpretation
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalRequest
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import com.opencray.runtime.web.WebSearchProvider
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
  private val todoStoreProvider: (String) -> AgentTodoStore = { InMemoryAgentTodoStore() },
  private val processRegistryProvider: (String) -> AgentProcessRegistry = { InMemoryAgentProcessRegistry() },
  private val transcriptStoreProvider: (String) -> SessionTranscriptStore = { InMemorySessionTranscriptStore() },
  private val supplementStoreProvider: (String) -> SessionSupplementStore = { InMemorySessionSupplementStore() },
  private val compactionStoreProvider: (String) -> SessionCompactionStore = { InMemorySessionCompactionStore() },
  private val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  private val soulTurnSemanticSignalInterpreter: SoulTurnSemanticSignalInterpreter =
    NoOpSoulTurnSemanticSignalInterpreter,
  private val pythonRuntimeProvider: () -> PythonScriptRuntime = { HostProcessPythonRuntime() },
  private val webSearchProviderFactory: () -> WebSearchProvider = { UnconfiguredWebSearchProvider },
  private val skillPackageManagerProvider: () -> SkillPackageManager? = { null },
  private val mediaToolSettingsProvider: () -> OpenCrayMediaToolSettings? = { null },
  private val imageGenerationClientProvider: () -> OpenCrayImageGenerationClient? = { null },
  private val speechSynthesisClientProvider: () -> OpenCraySpeechSynthesisClient? = { null },
) : AgentSessionTaskRuntimeFactory {
  private val todoStoresBySession: ConcurrentMap<String, AgentTodoStore> = ConcurrentHashMap()
  private val promptCheckpointStoresBySession: ConcurrentMap<String, PromptCheckpointStore> =
    ConcurrentHashMap()
  private val processRegistriesBySession: ConcurrentMap<String, AgentProcessRegistry> = ConcurrentHashMap()
  private val transcriptStoresBySession: ConcurrentMap<String, SessionTranscriptStore> = ConcurrentHashMap()
  private val supplementStoresBySession: ConcurrentMap<String, SessionSupplementStore> = ConcurrentHashMap()
  private val compactionStoresBySession: ConcurrentMap<String, SessionCompactionStore> = ConcurrentHashMap()
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

  private fun executeTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): ExecutionResult {
    val llmSettings = llmSettingsProvider().sanitized()
    val safetySettings = safetySettingsProvider().sanitized()
    val requiresLlmConfig = task.type == com.opencray.core.contracts.AgentTaskType.PROMPT
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

    val gateway: LiteLlmGateway = if (requiresLlmConfig) {
      val route = ProviderRoute(
        id = "route-${llmSettings.providerId.ifBlank { "openai-compatible" }}",
        providerId = llmSettings.providerId.ifBlank { "openai-compatible" },
        baseUrl = llmSettings.baseUrl,
        model = llmSettings.model,
        metadata = LlmProviderProtocols.routeMetadata(
          protocol = llmSettings.protocol,
          model = llmSettings.model,
          reasoningEffort = llmSettings.reasoningEffort,
        ),
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
    val approvalContinuation = approvalContinuationForExecution(sessionId, task.id)
    val approvalGrant = approvalContinuation.grant
    val approvalRejection = approvalContinuation.rejection
    val promptResumeState = (approvalGrant?.promptResumeState ?: approvalRejection?.promptResumeState)
      ?.takeIf { task.type == com.opencray.core.contracts.AgentTaskType.PROMPT }
    val approvedSubAgentResume = approvalGrant?.subAgentApprovalResume
    val rejectedSubAgentResume = approvalRejection?.subAgentApprovalResume
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
          extraPolicyReadRoots = skillPolicyReadRoots,
          extraPolicyWriteRoots = skillPolicyWriteRoots,
          skillsRoots = skillsRootsProvider(),
          skillPackageManager = skillPackageManager,
          mcpExposureReport = mcpReportProvider(),
          approvedTaskId = task.id.takeIf { approvalGrant != null },
          approvedToolName = approvalGrant?.toolName,
          rejectedTaskId = task.id.takeIf { approvalRejection != null },
          rejectedToolName = approvalRejection?.toolName,
          pythonRuntimeAdapter = pythonRuntimeProvider(),
          supportsManagedPythonProcessStart = true,
          managedPythonProcessUsesRuntimeAdapter = true,
          todoStore = todoStoreForSession(sessionId),
          processRegistry = processRegistryForSession(sessionId),
          webSearchProvider = webSearchProviderFactory(),
          mediaToolSettingsProvider = mediaToolSettingsProvider,
          imageGenerationClient = imageGenerationClientProvider(),
          speechSynthesisClient = speechSynthesisClientProvider(),
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
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = safetySettings.maxAgentTurns,
        maxToolCalls = safetySettings.maxToolCalls,
        systemPrompt = llmSettings.systemPrompt.ifBlank {
          OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT
        },
        sessionContext = sessionContext,
        promptResumeState = promptResumeState,
        approvedSubAgentResume = approvedSubAgentResume,
        rejectedSubAgentResume = rejectedSubAgentResume,
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
        llmMetadata = if (requiresLlmConfig) {
          task.metadata.filterKeys(::isLlmVisibleMetadataKey) + mapOf("sessionId" to sessionId)
        } else {
          mapOf("sessionId" to sessionId)
        },
        llmAuthHeaders = if (requiresLlmConfig) {
          LlmProviderProtocols.authHeaders(
            protocol = llmSettings.protocol,
            apiKey = llmSettings.apiKey,
          )
        } else {
          emptyMap()
        },
      ),
      eventSink = transcriptAwareEventSink(
        sessionId = sessionId,
        transcriptStore = transcriptStore,
        delegate = eventSink,
      ),
    )
    val result = runtime.execute(task, hooks)
    recordSuccessfulAssistantTurn(
      sessionId = sessionId,
      task = task,
      result = result,
    )
    return result
  }

  internal fun todoStoreForSession(sessionId: String): AgentTodoStore =
    todoStoresBySession.computeIfAbsent(sessionId, todoStoreProvider)

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
    val durableCheckpoint = promptCheckpointStoreForSession(sessionId).get(taskId)
      ?: return ApprovalContinuation()
    return when (durableCheckpoint.checkpointKind) {
      PromptCheckpointKind.APPROVED_PENDING_RESUME ->
        ApprovalContinuation(grant = approvalGrantFromCheckpoint(durableCheckpoint))

      PromptCheckpointKind.REJECTED_PENDING_RESUME ->
        ApprovalContinuation(rejection = approvalRejectionFromCheckpoint(durableCheckpoint))

      PromptCheckpointKind.WAITING_APPROVAL -> ApprovalContinuation()
    }
  }

  private object NonPromptTaskLiteLlmGateway : LiteLlmGateway {
    override fun execute(request: LiteLlmGatewayRequest) =
      error("LiteLlmGateway is unavailable for non-prompt tasks.")
  }

  internal fun processRegistryForSession(sessionId: String): AgentProcessRegistry =
    processRegistriesBySession.computeIfAbsent(sessionId, processRegistryProvider)

  internal fun transcriptStoreForSession(sessionId: String): SessionTranscriptStore =
    transcriptStoresBySession.computeIfAbsent(sessionId, transcriptStoreProvider)

  internal fun supplementStoreForSession(sessionId: String): SessionSupplementStore =
    supplementStoresBySession.computeIfAbsent(sessionId, supplementStoreProvider)

  internal fun compactionStoreForSession(sessionId: String): SessionCompactionStore =
    compactionStoresBySession.computeIfAbsent(sessionId, compactionStoreProvider)

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
  ) {
    transcriptStoreForSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildString {
          append("run_cancelled")
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
          append(" outcome=user_cancelled")
          append(" next_step=await_user_instruction")
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
    liveContextMode: LiveContextMode = LiveContextMode.FULL,
    memoryToolsEnabled: Boolean = safetySettingsProvider().sanitized().memoryToolsEnabled,
  ): PreparedSessionContext {
    val liveContextPolicy = liveContextPolicyFor(liveContextMode)
    val baseContext = sessionContextFactory.create(
      sessionId = sessionId,
      visibleThroughMessageId = visibleThroughMessageId,
      excludedMessageIds = excludedMessageIds,
      soulProfile = soulProfile.takeIf { liveContextPolicy.soulEnabled },
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
        is OpenCraySupplementEvent -> {
          transcriptStore.appendIfDistinct(
            RuntimeConversationMessage(
              role = RuntimeConversationRole.USER,
              content = event.text,
            ),
          )
          appendIfMissing(
            transcriptStore = transcriptStore,
            message = RuntimeConversationMessage(
              role = RuntimeConversationRole.TOOL,
              content = buildSupplementReplayContent(event),
            ),
          )
        }

        is OpenCrayToolResultEvent -> if (event.result.status == AgentToolResultStatus.SUCCESS) {
          recordSuccessfulToolInteraction(
            transcriptStore = transcriptStore,
            event = event,
          )
        }

        is OpenCrayProgressEvent -> appendIfMissing(
          transcriptStore = transcriptStore,
          message = RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = buildProgressReplayContent(event),
            kind = RuntimeConversationMessageKind.PROGRESS,
            progress = RuntimeConversationProgress(
              runId = event.runId,
              taskId = event.taskId,
              turn = event.turn,
              text = event.text,
              stage = event.stage,
            ),
          ),
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
          ),
        )
      }
  }

  private fun recordSuccessfulToolInteraction(
    transcriptStore: SessionTranscriptStore,
    event: OpenCrayToolResultEvent,
  ) {
    val callObservation = RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
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
    "tool_call ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
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
    }}"

  private fun buildToolResultReplayContent(event: OpenCrayToolResultEvent): String =
    "tool_result ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      put("turn", event.turn)
      event.call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
      put("tool_name", event.result.toolName)
      put("status", event.result.status.name.lowercase())
      put("content_preview", previewForReplay(event.result.content))
      if (event.result.metadata.isNotEmpty()) {
        put(
          "metadata",
          buildJsonObject {
            event.result.metadata.toSortedMap().forEach { (key, value) ->
              put(key, value)
            }
          },
        )
      }
    }}"

  private fun buildProgressReplayContent(event: OpenCrayProgressEvent): String =
    "progress ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      put("turn", event.turn)
      put("text", collapseReplayWhitespace(event.text))
      event.stage
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { stage -> put("stage", stage) }
    }}"

  private fun buildSupplementReplayContent(event: OpenCraySupplementEvent): String =
    "supplement ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      put("turn", event.turn)
      put("entry_id", event.entryId)
      put("text", collapseReplayWhitespace(event.text))
      put("checkpoint", event.checkpoint)
    }}"

  private fun buildSubAgentReplayContent(event: OpenCraySubAgentEvent): String =
    "subagent ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
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
    }}"

  private fun encodeReplayJsonObject(builder: JsonObjectBuilder.() -> Unit): String =
    replayJson.encodeToString(
      serializer = JsonObject.serializer(),
      value = buildJsonObject(builder),
    )

  private fun previewForReplay(content: String): String =
    collapseReplayWhitespace(content).let { normalized ->
      if (normalized.length <= TOOL_REPLAY_PREVIEW_LIMIT_CHARS) {
        normalized
      } else {
        normalized.take(TOOL_REPLAY_PREVIEW_LIMIT_CHARS - 1).trimEnd() + "…"
      }
    }

  private fun collapseReplayWhitespace(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()

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
            prefix = "run_cancelled",
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
        message.content.startsWith("run_cancelled") ||
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
    const val METADATA_RUN_ID: String = "${METADATA_HOST_PREFIX}runId"
    const val METADATA_HOST_SESSION_ID: String = "${METADATA_HOST_PREFIX}sessionId"
    const val METADATA_PENDING_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}pendingMessageId"
    const val METADATA_VISIBLE_THROUGH_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}visibleThroughMessageId"
    private const val TOOL_REPLAY_PREVIEW_LIMIT_CHARS: Int = 240

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

private fun approvalGrantFromCheckpoint(
  checkpoint: PersistedPromptCheckpoint,
): AgentTaskApprovalGrant? {
  if (checkpoint.checkpointKind != PromptCheckpointKind.APPROVED_PENDING_RESUME) {
    return null
  }
  return AgentTaskApprovalGrant(
    taskId = checkpoint.taskId,
    toolName = checkpoint.toolName,
    promptResumeState = checkpoint.promptResumeState,
    subAgentApprovalResume = restoredSubAgentApprovalResumeFrom(checkpoint),
  )
}

private fun approvalRejectionFromCheckpoint(
  checkpoint: PersistedPromptCheckpoint,
): AgentTaskApprovalRejection? {
  if (checkpoint.checkpointKind != PromptCheckpointKind.REJECTED_PENDING_RESUME) {
    return null
  }
  return AgentTaskApprovalRejection(
    taskId = checkpoint.taskId,
    toolName = checkpoint.toolName,
    promptResumeState = checkpoint.promptResumeState,
    subAgentApprovalResume = restoredSubAgentApprovalResumeFrom(checkpoint),
  )
}

private fun restoredSubAgentApprovalResumeFrom(
  checkpoint: PersistedPromptCheckpoint,
): com.opencray.runtime.subagent.SubAgentApprovalResume? {
  val approvedToolName = checkpoint.subAgentApprovedToolName
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val promptResumeState = checkpoint.subAgentPromptResumeState ?: return null
  return com.opencray.runtime.subagent.SubAgentApprovalResume(
    approvedToolName = approvedToolName,
    promptResumeState = promptResumeState,
    isHighRisk = checkpoint.subAgentIsHighRisk == true,
  )
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
