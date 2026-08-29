package com.opencray.app

import android.util.Log
import com.opencray.app.builtinToolsForWarmup as builtinToolsForWarmupFromFile
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.error.UserFacingErrorCodes
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmCompactRequest
import com.opencray.llm.LiteLlmCompactResult
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.mcp.McpClientExposureReport
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.AgentTodoStore
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.HostProcessPythonRuntime
import com.opencray.runtime.InMemoryAgentTodoStore
import com.opencray.runtime.ManagedProcessObservationTracker
import com.opencray.runtime.NoOpOpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.OpenCrayChatAttachmentSource
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayMidTurnMaintenanceRequest
import com.opencray.runtime.OpenCrayMidTurnMaintenanceResult
import com.opencray.runtime.OpenCrayImageGenerationClient
import com.opencray.runtime.OpenCrayMediaArtifactRegistry
import com.opencray.runtime.OpenCrayMediaToolSettings
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptSupplementMetadata
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySpeechSynthesisClient
import com.opencray.runtime.OpenCraySpeechSynthesisSettings
import com.opencray.runtime.OpenCrayImageGenerationSettings
import com.opencray.runtime.OpenCrayVideoGenerationSettings
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
import com.opencray.runtime.ScheduledTaskManager
import com.opencray.runtime.defaultOpenCrayMediaArtifactRegistry
import com.opencray.runtime.bootstrap.BootstrapContextResolver
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.compaction.DurableCompactionCoordinator
import com.opencray.runtime.compaction.InMemorySessionCompactionStore
import com.opencray.runtime.compaction.NoOpRemoteCompactionProvider
import com.opencray.runtime.compaction.RemoteCompactionProvider
import com.opencray.runtime.compaction.RemoteCompactionRequest
import com.opencray.runtime.compaction.RemoteCompactionResult
import com.opencray.runtime.compaction.SessionCompactionStore
import com.opencray.runtime.context.CompactionSummary
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.ContextSourceBudgetProfile
import com.opencray.runtime.context.GlobalContextBudgetCoordinator
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.RecentToolObservationSupport
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationAssistantPhase
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.context.TranscriptWindowBuilder
import com.opencray.runtime.memory.MemoryFlushTrace
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
import com.opencray.runtime.skills.ActiveSkillCapsuleResolver
import com.opencray.runtime.skills.ActiveSkillPromptLayer
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillInventoryPromptLayer
import com.opencray.runtime.skills.SkillInventoryResolver
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.subagent.SubAgentChildSessionBootstrap
import com.opencray.runtime.subagent.SubAgentChildSessionBootstrapMetadata
import com.opencray.runtime.subagent.SubAgentContextBuilder
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecision
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SESSION_CONTEXT_DEBUG_TAG: String = "OpenCrayDiag"

private fun sessionContextDebug(message: String) {
  runCatching { Log.d(SESSION_CONTEXT_DEBUG_TAG, message) }
}

internal class AppAgentSessionTaskRuntimeFactory(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val safetySettingsProvider: () -> SafetySettingsState = { SafetySettingsState() },
  private val liveContextModeProvider: () -> LiveContextMode = { LiveContextMode.FULL },
  private val sessionContextFactory: ChatRuntimeSessionContextFactory,
  private val soulProfileProvider: () -> WorkspaceSoulProfile?,
  private val workspaceRootsProvider: () -> Set<Path>,
  private val readRootsProvider: () -> Set<Path> = workspaceRootsProvider,
  private val fileMutationLockDirectoryProvider: () -> Path? = { null },
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
  private val subAgentSessionLinkStoreProvider: (String) -> SubAgentSessionLinkStore = { sessionId ->
    InMemorySubAgentSessionLinkStoreFactory().forChatSession(sessionId)
  },
  private val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  private val soulTurnSemanticSignalInterpreter: SoulTurnSemanticSignalInterpreter =
    NoOpSoulTurnSemanticSignalInterpreter,
  private val providerClient: LiteLlmProviderClient = defaultProviderClient(providerUserAgent),
  private val onDeviceThinkingTextProvider: () -> String = { "Thinking…" },
  private val onDeviceModelReadyProvider: (String) -> Boolean = { true },
  private val enableLiteRtDevAutomaticToolExecution: Boolean = false,
  private val commandExecutorProvider: () -> CommandExecutor? = { null },
  private val pythonRuntimeProvider: () -> PythonScriptRuntime = { HostProcessPythonRuntime() },
  private val pythonRuntimeManifestProvider: (() -> PythonRuntimeManifestSnapshot?)? = null,
  private val webSearchProviderFactory: () -> WebSearchProvider = { UnconfiguredWebSearchProvider },
  private val sandboxPreviewServiceProvider: () -> SandboxPreviewService? = { null },
  private val sandboxSessionControlServiceProvider: () -> SandboxSessionControlService? = { null },
  private val sandboxSessionInfoServiceProvider: () -> SandboxSessionInfoService? = { null },
  private val skillPackageManagerProvider: () -> SkillPackageManager? = { null },
  private val scheduledTaskManagerProvider: () -> ScheduledTaskManager? = { null },
  private val mediaToolSettingsProvider: () -> OpenCrayMediaToolSettings? = { null },
  private val imageGenerationClientProvider: () -> OpenCrayImageGenerationClient? = { null },
  private val speechSynthesisClientProvider: () -> OpenCraySpeechSynthesisClient? = { null },
  private val mediaArtifactRegistryProvider: () -> OpenCrayMediaArtifactRegistry = {
    defaultOpenCrayMediaArtifactRegistry(workspaceRootsProvider().first())
  },
  private val nativeWebSearchSessionApprovalProvider: (String) -> Boolean = { false },
  private val maintainedContextWindowTokensProvider: (String) -> Int? = { null },
  private val maintainedContextWindowTokensRecorder: (String, Int?) -> Unit = { _, _ -> },
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
  private val subAgentSessionLinkStoresBySession: ConcurrentMap<String, SubAgentSessionLinkStore> =
    ConcurrentHashMap()
  private val subAgentExecutionCoordinatorsBySession: ConcurrentMap<String, SubAgentExecutionCoordinator> =
    ConcurrentHashMap()
  private val contextSourceBudgetPolicy: ContextSourceBudgetPolicy = ContextSourceBudgetPolicy()
  private val memoryBackedSoulResolver: MemoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver()
  private val durableCompactionCoordinator: DurableCompactionCoordinator = DurableCompactionCoordinator(
    sourceBudgetPolicy = contextSourceBudgetPolicy,
  )
  private val skillCatalogResolver: SkillCatalogResolver = SkillCatalogResolver()
  private val skillInventoryResolver: SkillInventoryResolver = SkillInventoryResolver()
  private val activeSkillCapsuleResolver: ActiveSkillCapsuleResolver = ActiveSkillCapsuleResolver()
  private val subAgentContextBuilder: SubAgentContextBuilder = SubAgentContextBuilder()
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

  override fun readManagedProcess(
    sessionId: String,
    processId: String,
  ): ManagedProcessSnapshot? = processRegistryForSession(sessionId).read(processId)

  override fun terminateManagedProcess(
    sessionId: String,
    processId: String,
  ): ManagedProcessSnapshot? = processRegistryForSession(sessionId).terminate(processId)

  override fun listSubAgentHandles(sessionId: String): List<SubAgentHandleState> {
    val coordinator = subAgentExecutionCoordinatorsBySession[sessionId]
    val merged = if (coordinator != null) {
      mergeSubAgentHandlesByLatestState(
        liveHandles = coordinator.allHandles(),
        closedHandles = coordinator.allClosedHandles(),
      )
    } else {
      val store = subAgentHandleStoreForSession(sessionId)
      mergeSubAgentHandlesByLatestState(
        liveHandles = store.list(),
        closedHandles = store.listClosed(),
      )
    }
    return merged.map(MergedSubAgentHandleState::handle)
  }

  override fun listClosedSubAgentHandles(sessionId: String): List<SubAgentHandleState> {
    val coordinator = subAgentExecutionCoordinatorsBySession[sessionId]
    val merged = if (coordinator != null) {
      mergeSubAgentHandlesByLatestState(
        liveHandles = coordinator.allHandles(),
        closedHandles = coordinator.allClosedHandles(),
      )
    } else {
      val store = subAgentHandleStoreForSession(sessionId)
      mergeSubAgentHandlesByLatestState(
        liveHandles = store.list(),
        closedHandles = store.listClosed(),
      )
    }
    return merged
      .filter(MergedSubAgentHandleState::closed)
      .map(MergedSubAgentHandleState::handle)
  }

  override fun updateSubAgentHandlePendingApprovalDecision(
    sessionId: String,
    agentId: String,
    parentRunId: String,
    pendingApprovalDecision: SubAgentPendingApprovalDecision?,
  ): SubAgentHandleState? = subAgentExecutionCoordinatorForSession(sessionId).updateHandle(
    key = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    ),
  ) { existingHandle ->
    if (pendingApprovalDecision == null) {
      existingHandle.copy(pendingApprovalDecision = null)
    } else {
      existingHandle.copy(
        pendingApprovalDecision = pendingApprovalDecision,
        updatedAtEpochMs = maxOf(
          existingHandle.updatedAtEpochMs,
          pendingApprovalDecision.recordedAtEpochMs,
        ),
      )
    }
  }

  override fun hasActiveSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean = subAgentExecutionCoordinatorForSession(sessionId).activeExecution(
    key = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    ),
  ) != null

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
    subAgentSessionLinkStoresBySession.remove(sessionId)
    subAgentExecutionCoordinatorsBySession.remove(sessionId)
  }

  override fun executeSubAgentRecoveryTask(
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
      syntheticSubAgentTask = SyntheticSubAgentRecoveryWaitTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      ),
    )

  override fun executeSubAgentActorTask(
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
      syntheticSubAgentTask = SyntheticSubAgentActorTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      ),
    )

  override fun ensureSubAgentRecoveryExecution(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): Boolean {
    val preparedRuntime = prepareRuntimeExecution(
      sessionId = sessionId,
      task = task,
      eventSink = eventSink,
      syntheticSubAgentTask = SyntheticSubAgentRecoveryWaitTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      ),
    )
    val runtime = when (preparedRuntime) {
      is PreparedAppTaskRuntimeExecution.Ready -> preparedRuntime.runtime
      is PreparedAppTaskRuntimeExecution.Failed -> return false
    }
    return runtime.ensureSubAgentRecoveryExecution(
      task = task,
      hooks = hooks,
      agentId = agentId,
      parentRunId = parentRunId,
    ) != null
  }

  override fun ensureBackgroundSubAgentExecution(
    sessionId: String,
    agentId: String,
    parentRunId: String,
  ): Boolean {
    val now = System.currentTimeMillis()
    val task = syntheticSubAgentActorTask(
      sessionId = sessionId,
      agentId = agentId,
      parentRunId = parentRunId,
      createdAtEpochMs = now,
      metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
        submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
      ),
    )
    return ensureSubAgentRecoveryExecution(
      sessionId = sessionId,
      task = task,
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> Unit },
      ),
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
      agentId = agentId,
      parentRunId = parentRunId,
    )
  }

  override fun waitForSubAgentRecoveryExecution(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult? {
    val preparedRuntime = prepareRuntimeExecution(
      sessionId = sessionId,
      task = task,
      eventSink = eventSink,
      syntheticSubAgentTask = SyntheticSubAgentRecoveryWaitTaskSpec(
        agentId = agentId,
        parentRunId = parentRunId,
      ),
    )
    val runtime = when (preparedRuntime) {
      is PreparedAppTaskRuntimeExecution.Ready -> preparedRuntime.runtime
      is PreparedAppTaskRuntimeExecution.Failed -> return preparedRuntime.result
    }
    return runtime.executeSubAgentRecoveryWait(
      task = task,
      hooks = hooks,
      agentId = agentId,
      parentRunId = parentRunId,
    )
  }

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
    syntheticSubAgentTask: SyntheticSubAgentTaskSpec? = null,
  ): ExecutionResult {
    val preparedRuntime = prepareRuntimeExecution(
      sessionId = sessionId,
      task = task,
      eventSink = eventSink,
      syntheticSubAgentTask = syntheticSubAgentTask,
    )
    val runtime: OpenCrayAgentRuntime
    val preparedToolDispatcher: OpenCrayToolDispatcher
    val preparedOnDeviceProviderMode: Boolean
    when (preparedRuntime) {
      is PreparedAppTaskRuntimeExecution.Failed -> return preparedRuntime.result
      is PreparedAppTaskRuntimeExecution.Ready -> {
        runtime = preparedRuntime.runtime
        preparedToolDispatcher = preparedRuntime.toolDispatcher
        preparedOnDeviceProviderMode = preparedRuntime.onDeviceProviderMode
      }
    }
    val liteRtAutomaticToolExecutionContext = if (
      enableLiteRtDevAutomaticToolExecution &&
      preparedOnDeviceProviderMode
    ) {
      LiteRtAutomaticToolExecutionContext(
        task = task,
        hooks = hooks,
        toolDispatcher = preparedToolDispatcher,
      )
    } else {
      null
    }
    val result = LiteRtAutomaticToolExecutionRegistry.withContext(
      liteRtAutomaticToolExecutionContext,
    ) {
      when (syntheticSubAgentTask) {
        is SyntheticSubAgentActorTaskSpec -> {
          runtime.executeSubAgentActorTask(
            task = task,
            hooks = hooks,
            agentId = syntheticSubAgentTask.agentId,
            parentRunId = syntheticSubAgentTask.parentRunId,
          )
        }

        is SyntheticSubAgentRecoveryWaitTaskSpec -> {
          runtime.ensureSubAgentRecoveryExecution(
            task = task,
            hooks = hooks,
            agentId = syntheticSubAgentTask.agentId,
            parentRunId = syntheticSubAgentTask.parentRunId,
          )
          runtime.executeSubAgentRecoveryWait(
            task = task,
            hooks = hooks,
            agentId = syntheticSubAgentTask.agentId,
            parentRunId = syntheticSubAgentTask.parentRunId,
          )
        }

        null -> runtime.execute(task, hooks)
      }
    }
    recordFinalAssistantTurn(
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

  private fun prepareRuntimeExecution(
    sessionId: String,
    task: AgentTask,
    eventSink: OpenCrayAgentRuntimeEventSink,
    syntheticSubAgentTask: SyntheticSubAgentTaskSpec? = null,
  ): PreparedAppTaskRuntimeExecution {
    val llmSettings = llmSettingsProvider().sanitized()
    val safetySettings = safetySettingsProvider().sanitized()
    val approvalContinuation = approvalContinuationForExecution(sessionId, task.id)
    val approvalGrant = approvalContinuation.grant
    val approvalRejection = approvalContinuation.rejection
    val approvedSubAgentResume = approvalGrant?.subAgentApprovalResume
    val rejectedSubAgentResume = approvalRejection?.subAgentApprovalResume
    val requiresLlmConfig = task.type == com.opencray.core.contracts.AgentTaskType.PROMPT ||
      syntheticSubAgentTaskRequiresLlm(
        sessionId = sessionId,
        syntheticSubAgentTask = syntheticSubAgentTask,
        approvedSubAgentResume = approvedSubAgentResume,
        rejectedSubAgentResume = rejectedSubAgentResume,
      ) ||
      directToolCallRequiresLlm(
        sessionId = sessionId,
        task = task,
        approvedSubAgentResume = approvedSubAgentResume,
        rejectedSubAgentResume = rejectedSubAgentResume,
      )
    val hasOperationalLlmConfig = when {
      !llmSettings.isConfigured() -> false
      llmSettings.isOnDeviceProviderMode() ->
        onDeviceModelReadyProvider(llmSettings.selectedOnDeviceModelId)
      else -> true
    }
    if (requiresLlmConfig && !hasOperationalLlmConfig) {
      return PreparedAppTaskRuntimeExecution.Failed(
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.FAILED,
          errorCode = ERROR_CODE_MISSING_LLM_CONFIG,
          errorMessage = "LLM configuration is incomplete.",
          startedAtEpochMs = System.currentTimeMillis(),
          finishedAtEpochMs = System.currentTimeMillis(),
          metadata = task.metadata,
        ),
      )
    }
    val routeProviderId = llmSettings.providerId.ifBlank {
      if (llmSettings.isOnDeviceProviderMode()) {
        "on-device"
      } else {
        "openai-compatible"
      }
    }
    val routeMetadata = if (requiresLlmConfig) {
      effectiveRuntimeRouteMetadata(settings = llmSettings)
    } else {
      emptyMap()
    }
    val gateway: LiteLlmGateway = if (requiresLlmConfig) {
      val routeModel = if (llmSettings.isOnDeviceProviderMode()) {
        llmSettings.selectedOnDeviceModelId
      } else {
        llmSettings.model
      }
      val route = ProviderRoute(
        id = "route-$routeProviderId",
        providerId = routeProviderId,
        baseUrl = llmSettings.baseUrl.takeUnless { llmSettings.isOnDeviceProviderMode() },
        model = routeModel,
        timeoutMs = recommendedInteractiveProviderRouteTimeoutMs(routeModel),
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
        providerClient = providerClient,
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
    val subAgentChildSessionBootstrap = SubAgentChildSessionBootstrapMetadata.decodeFromMetadata(
      metadata = task.metadata,
      json = replayJson,
    )
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
    val scheduledTaskManager = scheduledTaskManagerProvider()
    val scheduledTaskPolicyRoots = scheduledTaskManager?.let { manager ->
      setOf(manager.policyTargetPath())
    }.orEmpty()
    val workspaceId = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider())
    val skillCatalog = skillCatalogFor()
    val inheritedSubAgentChildSkillCapsule = subAgentChildSessionBootstrap?.let { bootstrap ->
      activeSkillCapsuleResolver.resolve(
        catalog = skillCatalog,
        activeSkillName = bootstrap.childTask.activeSkillName,
        activationSource = bootstrap.childTask.activeSkillActivationSource,
      )
    }
    val llmMetadata = buildRuntimeLlmMetadata(
      requiresLlmConfig = requiresLlmConfig,
      taskMetadata = task.metadata,
      sessionId = sessionId,
      nativeWebSearchRunApproved = nativeWebSearchRunApproved,
      nativeWebSearchSessionApproved = nativeWebSearchSessionApproved,
      llmSettings = llmSettings,
      routeMetadata = routeMetadata,
    )
    val llmAuthHeaders = if (requiresLlmConfig) {
      LlmProviderProtocols.authHeaders(
        protocol = llmSettings.protocol,
        apiKey = llmSettings.apiKey,
      )
    } else {
      emptyMap()
    }
    val sourceBudgetProfile = contextSourceBudgetPolicy.resolve(llmMetadata)
    val effectiveLiveContextMode = effectiveLiveContextMode(
      configuredMode = liveContextModeProvider(),
      llmSettings = llmSettings,
    )
    val effectiveMemoryToolsEnabled = effectiveMemoryToolsEnabled(
      configuredValue = safetySettings.memoryToolsEnabled,
      llmSettings = llmSettings,
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
      taskMetadata = task.metadata,
      transcriptStore = transcriptStore,
      memoryRecords = memoryRecords,
      appendTaskInputToTranscript = task.type == com.opencray.core.contracts.AgentTaskType.PROMPT &&
        promptResumeState == null,
      llmMetadata = llmMetadata,
      sourceBudgetProfile = sourceBudgetProfile,
      liveContextMode = effectiveLiveContextMode,
      memoryToolsEnabled = effectiveMemoryToolsEnabled,
      remoteCompactionProvider = remoteCompactionProviderFor(
        gateway = gateway,
        llmMetadata = llmMetadata,
        authHeaders = llmAuthHeaders,
      ),
      skillCatalog = skillCatalog,
      subAgentChildSessionBootstrap = subAgentChildSessionBootstrap,
    )
    val sessionContext = preparedContext.sessionContext
    val effectiveMemoryRecords = preparedContext.effectiveMemoryRecords
    val toolDispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = workspaceRootsProvider(),
        readRoots = readRootsProvider(),
        fileMutationLockDirectory = fileMutationLockDirectoryProvider(),
        hiddenToolNamePrefixes = hiddenToolNamePrefixesProvider(),
        extraPolicyReadRoots = skillPolicyReadRoots + scheduledTaskPolicyRoots,
        extraPolicyWriteRoots = skillPolicyWriteRoots + scheduledTaskPolicyRoots,
        skillsRoots = skillsRootsProvider(),
        skillPackageManager = skillPackageManager,
        scheduledTaskManager = scheduledTaskManager,
        mcpExposureReport = mcpReportProvider(),
        // Host UI tool actions are already user-initiated, so nested policy gates should not
        // bounce them back into chat approval just because the internal gate uses Read/WebFetch.
        approvedTaskId = task.id.takeIf {
          approvalGrant != null || hostUiTaskPreapproved
        },
        approvedToolName = approvalGrant?.toolName,
        approvedTaskGrantScopedToFirstRequest = approvalGrant != null,
        rejectedTaskId = task.id.takeIf { approvalRejection != null },
        rejectedToolName = approvalRejection?.toolName,
        commandApprovalToken = approvalGrant?.commandApprovalToken,
        commandBatchApprovalToken = approvalRegistry.batchCommandApprovalToken(sessionId),
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
        mediaArtifactRegistry = mediaArtifactRegistryProvider(),
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
        memoryToolContext = if (effectiveMemoryToolsEnabled) {
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
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = toolDispatcher,
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
        seededDetachedSubAgentHandlesRequireCoordinatorOwnership = true,
        inheritedActiveSkillCapsule = inheritedSubAgentChildSkillCapsule,
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
        midTurnMaintenance = { request ->
          runMidTurnContextMaintenance(
            sessionId = sessionId,
            workspaceId = workspaceId,
            request = request,
            transcriptStore = transcriptStore,
            llmMetadata = llmMetadata,
            remoteCompactionProvider = remoteCompactionProviderFor(
              gateway = gateway,
              llmMetadata = llmMetadata,
              authHeaders = llmAuthHeaders,
            ),
            sourceBudgetProfile = sourceBudgetProfile,
            liveContextMode = effectiveLiveContextMode,
            liveContextPolicy = liveContextPolicyFor(effectiveLiveContextMode),
            memoryToolsEnabled = effectiveMemoryToolsEnabled,
            enabled = task.type == com.opencray.core.contracts.AgentTaskType.PROMPT &&
              syntheticSubAgentTask == null,
          )
        },
        contextManager = contextManagerFor(sourceBudgetProfile),
        promptAssembler = promptAssemblerFor(sourceBudgetProfile),
        llmMetadata = llmMetadata,
        contextSourceBudgetProfile = sourceBudgetProfile,
        llmAuthHeaders = llmAuthHeaders,
        subAgentContextPolicy = safetySettings.toRuntimeSubAgentContextPolicy(),
      ),
      eventSink = transcriptAwareEventSink(
        sessionId = sessionId,
        transcriptStore = transcriptStore,
        delegate = eventSink,
      ),
    )
    return PreparedAppTaskRuntimeExecution.Ready(
      runtime = runtime,
      toolDispatcher = toolDispatcher,
      onDeviceProviderMode = llmSettings.isOnDeviceProviderMode(),
    )
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
      PromptCheckpointKind.FINALIZATION_COMPLETE,
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
    val coordinator = subAgentExecutionCoordinatorForSession(sessionId)
    val agentId = directToolAgentIdFrom(task.input)
    val existingHandle = agentId?.let { resolvedAgentId ->
      coordinator
        .allHandles()
        .firstOrNull { handle -> handle.agentId == resolvedAgentId }
    }
    val hasApprovalContinuation = approvedSubAgentResume != null || rejectedSubAgentResume != null
    return when (normalizedToolName) {
      "spawn_agent" -> when {
        existingHandle == null -> true
        !existingHandle.canContinueDetachedExecution(hasApprovalContinuation = hasApprovalContinuation) -> false
        else -> true
      }

      "wait_agent" -> existingHandle?.let { handle ->
        detachedSubAgentWaitRequiresLlm(
          coordinator = coordinator,
          handle = handle,
          hasApprovalContinuation = hasApprovalContinuation,
        )
      } ?: false

      else -> false
    }
  }

  private fun syntheticSubAgentTaskRequiresLlm(
    sessionId: String,
    syntheticSubAgentTask: SyntheticSubAgentTaskSpec?,
    approvedSubAgentResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    rejectedSubAgentResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
  ): Boolean {
    val recoveryTask = syntheticSubAgentTask ?: return false
    val coordinator = subAgentExecutionCoordinatorForSession(sessionId)
    val existingHandle = coordinator.currentHandle(
      com.opencray.runtime.subagent.SubAgentExecutionKey(
        parentRunId = recoveryTask.parentRunId,
        agentId = recoveryTask.agentId,
      ),
    ) ?: return false
    val hasApprovalContinuation = approvedSubAgentResume != null || rejectedSubAgentResume != null
    return detachedSubAgentWaitRequiresLlm(
      coordinator = coordinator,
      handle = existingHandle,
      hasApprovalContinuation = hasApprovalContinuation,
    )
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

  private fun detachedSubAgentWaitRequiresLlm(
    coordinator: SubAgentExecutionCoordinator,
    handle: SubAgentHandleState,
    hasApprovalContinuation: Boolean,
  ): Boolean {
    if (handle.isTerminalWithoutPendingApprovalResume()) {
      return false
    }
    if (coordinator.activeExecution(SubAgentExecutionKey.from(handle)) != null) {
      return false
    }
    return handle.canContinueDetachedExecution(
      hasApprovalContinuation = hasApprovalContinuation,
    )
  }

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

      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE ->
        PromptCheckpointKind.FINALIZATION_COMPLETE
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

  internal fun subAgentSessionLinkStoreForSession(sessionId: String): SubAgentSessionLinkStore =
    subAgentSessionLinkStoresBySession.computeIfAbsent(sessionId, subAgentSessionLinkStoreProvider)

  internal fun subAgentExecutionCoordinatorForSession(
    sessionId: String,
  ): SubAgentExecutionCoordinator =
    subAgentExecutionCoordinatorsBySession.computeIfAbsent(
      sessionId,
    ) { resolvedSessionId ->
      subAgentExecutionCoordinatorProvider?.invoke(resolvedSessionId)
        ?: PersistentSessionSubAgentExecutionCoordinator(
          sessionId = resolvedSessionId,
          store = subAgentHandleStoreForSession(resolvedSessionId),
          linkStore = subAgentSessionLinkStoreForSession(resolvedSessionId),
        )
    }

  private fun buildSupplementReplayContent(event: OpenCraySupplementEvent): String =
    buildSupplementReplayContent(
      json = replayJson,
      event = event,
    )

  internal fun recordSuccessfulToolInteraction(
    sessionId: String,
    event: OpenCrayToolResultEvent,
  ) {
    if (event.result.status != AgentToolResultStatus.SUCCESS) {
      return
    }
    recordToolInteraction(
      json = replayJson,
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
    appendSubAgentReplayEvent(
      json = replayJson,
      transcriptStore = transcriptStoreForSession(sessionId),
      event = event,
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
    sourceBudgetProfile: ContextSourceBudgetProfile = contextSourceBudgetPolicy.resolve(emptyMap()),
  ): MemoryRecallResult = MemoryRetriever(
    policy = sourceBudgetProfile.memoryPolicy,
  ).retrieve(
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

  internal fun bootstrapContextFor(
    mode: BootstrapMode,
    sourceBudgetProfile: ContextSourceBudgetProfile = contextSourceBudgetPolicy.resolve(emptyMap()),
  ) = BootstrapContextResolver(
    config = sourceBudgetProfile.bootstrapContextResolverConfig,
  ).resolve(
      workspaceRoots = workspaceRootsProvider(),
      mode = mode,
    )

  internal fun liveContextPolicyFor(mode: LiveContextMode): LiveContextPolicy =
    liveContextPolicyFrom(mode)

  internal fun skillCatalogFor() =
    skillCatalogResolver.resolve(skillsRootsProvider())

  internal fun contextManagerFor(
    sourceBudgetProfile: ContextSourceBudgetProfile,
  ): ContextManager {
    val transcriptWindowBuilder = TranscriptWindowBuilder(sourceBudgetProfile.transcriptWindowConfig)
    val skillInventoryPromptLayer = SkillInventoryPromptLayer(sourceBudgetProfile.skillInventoryPromptLayerConfig)
    val activeSkillPromptLayer = ActiveSkillPromptLayer(sourceBudgetProfile.activeSkillPromptLayerConfig)
    val recentToolObservationSupport = RecentToolObservationSupport(sourceBudgetProfile.recentToolObservationConfig)
    return ContextManager(
      transcriptWindowBuilder = transcriptWindowBuilder,
      skillInventoryPromptLayer = skillInventoryPromptLayer,
      activeSkillPromptLayer = activeSkillPromptLayer,
      recentToolObservationSupport = recentToolObservationSupport,
      config = sourceBudgetProfile.contextManagerConfig,
    )
  }

  internal fun promptAssemblerFor(
    sourceBudgetProfile: ContextSourceBudgetProfile,
  ): PromptAssembler {
    val skillInventoryPromptLayer = SkillInventoryPromptLayer(sourceBudgetProfile.skillInventoryPromptLayerConfig)
    val activeSkillPromptLayer = ActiveSkillPromptLayer(sourceBudgetProfile.activeSkillPromptLayerConfig)
    val recentToolObservationSupport = RecentToolObservationSupport(sourceBudgetProfile.recentToolObservationConfig)
    return PromptAssembler(
      budgetCoordinator = GlobalContextBudgetCoordinator(
        skillInventoryPromptLayer = skillInventoryPromptLayer,
        activeSkillPromptLayer = activeSkillPromptLayer,
        recentToolObservationSupport = recentToolObservationSupport,
      ),
    )
  }

  internal fun buildOnDeviceWarmupSpec(
    sessionId: String,
  ): OnDeviceLlmWarmupSpec? {
    val llmSettings = llmSettingsProvider().sanitized()
    if (!llmSettings.enabled || !llmSettings.isOnDeviceProviderMode()) {
      return null
    }
    val modelId = llmSettings.selectedOnDeviceModelId.trim()
    if (modelId.isBlank() || !onDeviceModelReadyProvider(modelId)) {
      return null
    }
    val safetySettings = safetySettingsProvider().sanitized()
    val routeMetadata = effectiveRuntimeRouteMetadata(settings = llmSettings)
    val llmMetadata = buildRuntimeLlmMetadata(
      requiresLlmConfig = true,
      taskMetadata = emptyMap(),
      sessionId = sessionId,
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = nativeWebSearchSessionApprovalProvider(sessionId),
      llmSettings = llmSettings,
      routeMetadata = routeMetadata,
    )
    val sourceBudgetProfile = contextSourceBudgetPolicy.resolve(llmMetadata)
    val effectiveLiveContextMode = effectiveLiveContextMode(
      configuredMode = liveContextModeProvider(),
      llmSettings = llmSettings,
    )
    val effectiveMemoryToolsEnabled = effectiveMemoryToolsEnabled(
      configuredValue = safetySettings.memoryToolsEnabled,
      llmSettings = llmSettings,
    )
    val workspaceId = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider())
    val memoryRecords = memoryRecordsProvider()
    val sessionContext = prepareOnDeviceWarmupSessionContext(
      sessionId = sessionId,
      workspaceId = workspaceId,
      soulProfile = soulProfileProvider(),
      memoryRecords = memoryRecords,
      sourceBudgetProfile = sourceBudgetProfile,
      liveContextMode = effectiveLiveContextMode,
      memoryToolsEnabled = effectiveMemoryToolsEnabled,
    )
    val toolDispatcher = onDeviceWarmupToolDispatcher(
      sessionId = sessionId,
      workspaceId = workspaceId,
      effectiveMemoryRecords = memoryRecords,
      memoryToolsEnabled = effectiveMemoryToolsEnabled,
      approvedTaskId = null,
    )
    val allDefinitions = toolDispatcher.definitions()
    val visibleToolDefinitions = visibleWarmupToolDefinitions(
      allDefinitions = allDefinitions,
      llmMetadata = llmMetadata,
      memoryToolsEnabled = effectiveMemoryToolsEnabled,
    )
    val builtinTools = builtinToolsForWarmup(
      visibleToolDefinitions = visibleToolDefinitions,
      llmMetadata = llmMetadata,
    )
    val functionVisibleToolDefinitions = functionToolDefinitionsForWarmup(
      visibleToolDefinitions = visibleToolDefinitions,
      builtinTools = builtinTools,
      llmMetadata = llmMetadata,
    )
    val nativeToolCallingEnabled =
      functionVisibleToolDefinitions.isNotEmpty() || builtinTools.isNotEmpty()
    val parallelToolCallsEnabled =
      nativeToolCallingEnabled &&
        llmMetadata["parallelToolCalls"]?.trim()?.lowercase() == "true"
    val strictToolSchemaEnabled = llmMetadata["toolSchemaStrict"]?.trim()?.lowercase() == "true"
    val managedContext = contextManagerFor(sourceBudgetProfile).prepare(
      PromptAssemblyInput(
        task = onDeviceWarmupTask(sessionId),
        baseSystemPrompt = llmSettings.systemPrompt.ifBlank {
          OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT
        },
        sessionContext = sessionContext,
        nativeToolCallingEnabled = nativeToolCallingEnabled,
        parallelToolCallsEnabled = parallelToolCallsEnabled,
        legacyJsonFallbackEnabled = !nativeToolCallingEnabled,
        toolDefinitions = functionVisibleToolDefinitions,
        liveConversation = sessionContext.conversation,
        todoSnapshot = toolDispatcher.todoSnapshot(),
        llmMetadata = llmMetadata,
      ),
    )
    val assembledPrompt = promptAssemblerFor(sourceBudgetProfile).assemble(managedContext)
    return OnDeviceLlmWarmupSpec(
      modelId = llmSettings.selectedOnDeviceModelId,
      backend = llmSettings.onDeviceAccelerator,
      maxContextWindow = llmSettings.onDeviceMaxContextWindow,
      maxTokens = llmSettings.onDeviceMaxTokens,
      topK = llmSettings.onDeviceTopK,
      topP = llmSettings.onDeviceTopP,
      temperature = llmSettings.onDeviceTemperature,
      thinkingEnabled = llmSettings.onDeviceThinkingEnabled,
      systemPrompt = assembledPrompt.systemPrompt.trim().takeIf(String::isNotBlank),
      messages = assembledPrompt.frontContextZones.durableContextPrompt
        .trim()
        .takeIf(String::isNotBlank)
        ?.let { durablePrompt ->
          listOf(
            LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.USER,
              content = durablePrompt,
            ),
          )
        }
        .orEmpty(),
      tools = if (nativeToolCallingEnabled) {
        functionVisibleToolDefinitions.map { definition ->
          definition.toWarmupLiteLlmToolDefinition(strict = strictToolSchemaEnabled)
        }
      } else {
        emptyList()
      },
      builtinTools = if (nativeToolCallingEnabled) builtinTools else emptyList(),
    )
  }

  private fun prepareOnDeviceWarmupSessionContext(
    sessionId: String,
    workspaceId: String?,
    soulProfile: WorkspaceSoulProfile?,
    memoryRecords: List<MemoryRecord>,
    sourceBudgetProfile: ContextSourceBudgetProfile,
    liveContextMode: LiveContextMode,
    memoryToolsEnabled: Boolean,
  ): AgentRuntimeSessionContext {
    val liveContextPolicy = liveContextPolicyFor(liveContextMode)
    val baseContext = sessionContextFactory.create(
      sessionId = sessionId,
      soulProfile = soulProfile.takeIf { liveContextPolicy.soulEnabled },
      workingState = workingStateStoreForSession(sessionId).snapshot(),
    )
    val skillCatalog = skillCatalogFor()
    return baseContext.copy(
      soulProfile = if (liveContextPolicy.soulEnabled) {
        memoryBackedSoulResolver.overlay(
          baseProfile = baseContext.soulProfile,
          records = memoryRecords,
          sessionId = sessionId,
          workspaceId = workspaceId,
        )
      } else {
        baseContext.soulProfile
      },
      turnSemanticSignal = null,
      injectionPolicy = liveContextPolicy.injectionPolicy,
      memoryToolsEnabled = memoryToolsEnabled,
      liveContextTrace = LiveContextTrace(
        mode = liveContextMode.wireValue,
        soulEnabled = liveContextPolicy.soulEnabled,
        memoryRecallEnabled = liveContextPolicy.memoryRecallEnabled,
      ),
      bootstrapContext = bootstrapContextFor(
        mode = liveContextPolicy.bootstrapMode,
        sourceBudgetProfile = sourceBudgetProfile,
      ),
      recalledMemory = MemoryRecallResult(),
      memoryFlushTrace = MemoryFlushTrace(),
      durableCompaction = durableCompactionCoordinator.currentContext(
        compactionStoreForSession(sessionId),
      ),
      skillInventory = skillCatalog.inventory,
      skillCatalog = skillCatalog,
      conversation = baseContext.conversation,
    )
  }

  private fun onDeviceWarmupToolDispatcher(
    sessionId: String,
    workspaceId: String?,
    effectiveMemoryRecords: List<MemoryRecord>,
    memoryToolsEnabled: Boolean,
    approvedTaskId: String?,
  ): OpenCrayToolDispatcher {
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
    val scheduledTaskManager = scheduledTaskManagerProvider()
    val scheduledTaskPolicyRoots = scheduledTaskManager?.let { manager ->
      setOf(manager.policyTargetPath())
    }.orEmpty()
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = workspaceRootsProvider(),
        readRoots = readRootsProvider(),
        fileMutationLockDirectory = fileMutationLockDirectoryProvider(),
        hiddenToolNamePrefixes = hiddenToolNamePrefixesProvider(),
        extraPolicyReadRoots = skillPolicyReadRoots + scheduledTaskPolicyRoots,
        extraPolicyWriteRoots = skillPolicyWriteRoots + scheduledTaskPolicyRoots,
        skillsRoots = skillsRootsProvider(),
        skillPackageManager = skillPackageManager,
        scheduledTaskManager = scheduledTaskManager,
        mcpExposureReport = mcpReportProvider(),
        approvedTaskId = approvedTaskId,
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
        mediaArtifactRegistry = mediaArtifactRegistryProvider(),
        chatAttachmentResolver = { null },
        memoryToolContext = if (memoryToolsEnabled) {
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
    )
  }

  private fun builtinToolsForWarmup(
    visibleToolDefinitions: List<AgentToolDefinition>,
    llmMetadata: Map<String, String>,
  ): List<LiteLlmBuiltinToolDefinition> = builtinToolsForWarmupFromFile(
    visibleToolDefinitions = visibleToolDefinitions,
    llmMetadata = llmMetadata,
  )

  internal fun buildRuntimeLlmMetadata(
    requiresLlmConfig: Boolean,
    taskMetadata: Map<String, String>,
    sessionId: String,
    nativeWebSearchRunApproved: Boolean,
    nativeWebSearchSessionApproved: Boolean,
    llmSettings: LlmSettingsState,
    routeMetadata: Map<String, String>,
  ): Map<String, String> = if (requiresLlmConfig) {
    withMaintainedContextWindowMetadata(
      sessionId = sessionId,
      metadata = buildMap {
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
        put(HOST_METADATA_BASE_URL, llmSettings.baseUrl)
        putAll(routeMetadata)
        putAll(llmSettings.contextBudgetRuntimeMetadataOverrides())
        putAll(llmSettings.agentCapability.runtimeMetadataOverrides())
      },
    )
  } else {
    mapOf("sessionId" to sessionId)
  }

  private fun withMaintainedContextWindowMetadata(
    sessionId: String,
    metadata: Map<String, String>,
  ): Map<String, String> {
    val explicitPreviousContextWindowTokens = metadata.positiveIntValue(
      "previousContextWindowTokens",
      "previous_context_window_tokens",
    )
    if (explicitPreviousContextWindowTokens != null) {
      return metadata
    }
    val currentContextWindowTokens = metadata.positiveIntValue(
      "contextWindowTokens",
      "context_window_tokens",
    ) ?: return metadata
    val maintainedContextWindowTokens = maintainedContextWindowTokensProvider(sessionId)
      ?.takeIf { previous -> previous > currentContextWindowTokens }
      ?: return metadata
    return metadata + ("previous_context_window_tokens" to maintainedContextWindowTokens.toString())
  }

  private fun recordMaintainedContextWindowTokens(
    sessionId: String,
    llmMetadata: Map<String, String>,
  ) {
    val contextWindowTokens = llmMetadata.positiveIntValue(
      "contextWindowTokens",
      "context_window_tokens",
    ) ?: return
    maintainedContextWindowTokensRecorder(sessionId, contextWindowTokens)
  }

  private fun Map<String, String>.positiveIntValue(vararg keys: String): Int? = keys
    .asSequence()
    .mapNotNull { key -> this[key] }
    .map { value -> value.trim() }
    .firstNotNullOfOrNull { value -> value.toIntOrNull()?.takeIf { it > 0 } }

  private fun effectiveRuntimeRouteMetadata(
    settings: LlmSettingsState,
  ): Map<String, String> {
    val baseMetadata = effectiveLlmRouteMetadata(settings = settings)
    if (!settings.isOnDeviceProviderMode()) {
      return baseMetadata
    }
    val thinkingLabel = onDeviceThinkingTextProvider()
      .trim()
      .takeIf(String::isNotBlank)
      ?: return baseMetadata
    return baseMetadata + mapOf(
      LiteRtOnDeviceMetadataKeys.THINKING_LABEL to thinkingLabel,
    )
  }

  private fun effectiveLiveContextMode(
    configuredMode: LiveContextMode,
    llmSettings: LlmSettingsState,
  ): LiveContextMode =
    if (llmSettings.isOnDeviceLiteModeEnabled()) {
      LiveContextMode.NO_MEMORY_OR_SOUL
    } else {
      configuredMode
    }

  private fun effectiveMemoryToolsEnabled(
    configuredValue: Boolean,
    llmSettings: LlmSettingsState,
  ): Boolean =
    if (llmSettings.isOnDeviceLiteModeEnabled()) {
      false
    } else {
      configuredValue
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
    taskMetadata: Map<String, String> = emptyMap(),
    transcriptStore: SessionTranscriptStore,
    memoryRecords: List<MemoryRecord>,
    appendTaskInputToTranscript: Boolean = taskType == com.opencray.core.contracts.AgentTaskType.PROMPT,
    llmMetadata: Map<String, String> = emptyMap(),
    sourceBudgetProfile: ContextSourceBudgetProfile = contextSourceBudgetPolicy.resolve(llmMetadata),
    liveContextMode: LiveContextMode = LiveContextMode.FULL,
    memoryToolsEnabled: Boolean = safetySettingsProvider().sanitized().memoryToolsEnabled,
    remoteCompactionProvider: RemoteCompactionProvider = NoOpRemoteCompactionProvider,
    skillCatalog: com.opencray.runtime.skills.SkillCatalog = skillCatalogFor(),
    subAgentChildSessionBootstrap: SubAgentChildSessionBootstrap? = null,
  ): PreparedSessionContext {
    subAgentChildSessionBootstrap?.let { bootstrap ->
      return prepareSubAgentChildSessionContext(
        sessionId = sessionId,
        taskInput = taskInput,
        transcriptStore = transcriptStore,
        memoryRecords = memoryRecords,
        appendTaskInputToTranscript = appendTaskInputToTranscript,
        bootstrap = bootstrap,
      )
    }
    val prepareStartedAtEpochMs = System.currentTimeMillis()
    val liveContextPolicy = liveContextPolicyFor(liveContextMode)
    sessionContextDebug(
      "context.prepareStart session=$sessionId task=$taskId type=${taskType.name} inputLen=${taskInput.length} liveContextMode=${liveContextMode.wireValue}",
    )
    val baseContext = sessionContextFactory.create(
      sessionId = sessionId,
      visibleThroughMessageId = visibleThroughMessageId,
      excludedMessageIds = excludedMessageIds,
      soulProfile = soulProfile.takeIf { liveContextPolicy.soulEnabled },
      workingState = workingStateStoreForSession(sessionId).snapshot(),
    )
    val transcriptWasEmptyBeforeSeed = transcriptStore.snapshot().isEmpty()
    transcriptStore.seedIfEmpty(baseContext.conversation)
    val promptMessage = if (appendTaskInputToTranscript) {
      promptTranscriptInputMessage(
        taskInput = taskInput,
        taskMetadata = taskMetadata,
      )
    } else {
      null
    }
    if (promptMessage != null) {
      if (!transcriptWasEmptyBeforeSeed || baseContext.conversation.isEmpty()) {
        transcriptStore.appendIfDistinct(promptMessage)
      } else if (
        !mergePromptMessageIntoSeededTranscript(
          transcriptStore = transcriptStore,
          promptMessage = promptMessage,
        )
      ) {
        transcriptStore.appendIfDistinct(promptMessage)
      }
    }
    val memoryFlushStartedAtEpochMs = System.currentTimeMillis()
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
    sessionContextDebug(
      "context.memoryFlush session=$sessionId task=$taskId durationMs=${System.currentTimeMillis() - memoryFlushStartedAtEpochMs} outcome=${memoryFlushSummary?.trace?.outcome ?: "skipped"} written=${memoryFlushSummary?.writtenRecords?.size ?: 0} candidates=${memoryFlushSummary?.trace?.candidateCount ?: 0} omitted=${memoryFlushSummary?.trace?.omittedMessageCount ?: 0}",
    )
    val compactionStartedAtEpochMs = System.currentTimeMillis()
    val durableCompaction = if (taskType == com.opencray.core.contracts.AgentTaskType.PROMPT) {
      durableCompactionCoordinator.compactIfNeeded(
        transcriptStore = transcriptStore,
        compactionStore = compactionStoreForSession(sessionId),
        llmMetadata = llmMetadata,
        remoteCompactionProvider = remoteCompactionProvider,
      )
    } else {
      durableCompactionCoordinator.currentContext(compactionStoreForSession(sessionId))
    }
    sessionContextDebug(
      "context.compaction session=$sessionId task=$taskId durationMs=${System.currentTimeMillis() - compactionStartedAtEpochMs}",
    )
    val bootstrapContext = bootstrapContextFor(
      mode = if (taskType == com.opencray.core.contracts.AgentTaskType.PROMPT) {
        liveContextPolicy.bootstrapMode
      } else {
        BootstrapMode.NONE
      },
      sourceBudgetProfile = sourceBudgetProfile,
    )
    val soulTurnStartedAtEpochMs = System.currentTimeMillis()
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
        is SoulTurnSemanticSignalInterpretation.Success -> {
          sessionContextDebug(
            "context.soulTurn session=$sessionId task=$taskId durationMs=${System.currentTimeMillis() - soulTurnStartedAtEpochMs} outcome=success taskBearing=${interpretation.signal.isTaskBearingRequest} affect=${interpretation.signal.userAffect.name.lowercase()} clarification=${interpretation.signal.clarificationNeeded}",
          )
          interpretation.signal
        }

        is SoulTurnSemanticSignalInterpretation.Unavailable -> {
          sessionContextDebug(
            "context.soulTurn session=$sessionId task=$taskId durationMs=${System.currentTimeMillis() - soulTurnStartedAtEpochMs} outcome=unavailable reason=${interpretation.reason?.take(120) ?: "-"}",
          )
          null
        }
      }
    } else {
      sessionContextDebug(
        "context.soulTurn session=$sessionId task=$taskId durationMs=${System.currentTimeMillis() - soulTurnStartedAtEpochMs} outcome=skipped",
      )
      null
    }
    val preparedContext = PreparedSessionContext(
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
          budgetPreset = llmMetadata["contextBudgetPreset"]
            ?: llmMetadata["context_budget_preset"],
        ),
        recalledMemory = if (liveContextPolicy.memoryRecallEnabled) {
          recalledMemoryFor(
            sessionId = sessionId,
            taskInput = taskInput,
            memoryRecords = effectiveMemoryRecords,
            workspaceId = workspaceId,
            sourceBudgetProfile = sourceBudgetProfile,
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
    sessionContextDebug(
      "context.prepareDone session=$sessionId task=$taskId durationMs=${System.currentTimeMillis() - prepareStartedAtEpochMs} turnSemanticSignal=${if (preparedContext.sessionContext.turnSemanticSignal != null) "present" else "absent"} durableMemoryCount=${preparedContext.effectiveMemoryRecords.size}",
    )
    if (taskType == com.opencray.core.contracts.AgentTaskType.PROMPT) {
      recordMaintainedContextWindowTokens(
        sessionId = sessionId,
        llmMetadata = llmMetadata,
      )
    }
    return preparedContext
  }

  private fun prepareSubAgentChildSessionContext(
    sessionId: String,
    taskInput: String,
    transcriptStore: SessionTranscriptStore,
    memoryRecords: List<MemoryRecord>,
    appendTaskInputToTranscript: Boolean,
    bootstrap: SubAgentChildSessionBootstrap,
  ): PreparedSessionContext {
    val storedWorkingState = workingStateStoreForSession(sessionId).snapshot()
    val initialContext = bootstrap.buildInitialContext(
      contextBuilder = subAgentContextBuilder,
    ).sessionContext
    val baseContext = initialContext.copy(
      workingState = if (storedWorkingState.isEmpty) {
        initialContext.workingState
      } else {
        storedWorkingState
      },
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
    return PreparedSessionContext(
      sessionContext = baseContext.copy(
        conversation = transcriptStore.snapshot(),
      ),
      effectiveMemoryRecords = memoryRecords,
    )
  }

  internal fun runMidTurnContextMaintenance(
    sessionId: String,
    workspaceId: String?,
    request: OpenCrayMidTurnMaintenanceRequest,
    transcriptStore: SessionTranscriptStore,
    llmMetadata: Map<String, String>,
    remoteCompactionProvider: RemoteCompactionProvider,
    sourceBudgetProfile: ContextSourceBudgetProfile,
    liveContextMode: LiveContextMode,
    liveContextPolicy: LiveContextPolicy,
    memoryToolsEnabled: Boolean,
    enabled: Boolean,
  ): OpenCrayMidTurnMaintenanceResult {
    if (!enabled) {
      return OpenCrayMidTurnMaintenanceResult(
        sessionContext = request.sessionContext,
        conversation = request.conversation,
      )
    }
    val maintenanceStartedAtEpochMs = System.currentTimeMillis()
    transcriptStore.replaceReplayWorkingCopy(request.conversation)
    val memoryFlushSummary = memoryIngestionCoordinator?.flushMidTurn(
      sessionId = sessionId,
      conversation = transcriptStore.snapshot(),
      llmMetadata = llmMetadata,
      taskId = request.task.id,
    )
    val effectiveMemoryRecords = if (memoryFlushSummary?.wasWritten == true) {
      memoryRecordsProvider()
    } else {
      memoryRecordsProvider()
    }
    val durableCompaction = durableCompactionCoordinator.compactMidTurn(
      transcriptStore = transcriptStore,
      compactionStore = compactionStoreForSession(sessionId),
      llmMetadata = llmMetadata,
      remoteCompactionProvider = remoteCompactionProvider,
    )
    val updatedConversation = transcriptStore.snapshot()
    val updatedContext = request.sessionContext.copy(
      soulProfile = if (liveContextPolicy.soulEnabled) {
        memoryBackedSoulResolver.overlay(
          baseProfile = request.sessionContext.soulProfile,
          records = effectiveMemoryRecords,
          sessionId = sessionId,
          workspaceId = workspaceId,
        )
      } else {
        request.sessionContext.soulProfile
      },
      injectionPolicy = liveContextPolicy.injectionPolicy,
      memoryToolsEnabled = memoryToolsEnabled,
      liveContextTrace = request.sessionContext.liveContextTrace.copy(
        mode = liveContextMode.wireValue,
        soulEnabled = liveContextPolicy.soulEnabled,
        memoryRecallEnabled = liveContextPolicy.memoryRecallEnabled,
        budgetPreset = llmMetadata["contextBudgetPreset"]
          ?: llmMetadata["context_budget_preset"],
      ),
      recalledMemory = if (liveContextPolicy.memoryRecallEnabled) {
        recalledMemoryFor(
          sessionId = sessionId,
          taskInput = request.task.input,
          memoryRecords = effectiveMemoryRecords,
          workspaceId = workspaceId,
          sourceBudgetProfile = sourceBudgetProfile,
        )
      } else {
        MemoryRecallResult()
      },
      memoryFlushTrace = memoryFlushSummary?.trace ?: request.sessionContext.memoryFlushTrace,
      durableCompaction = durableCompaction,
      conversation = updatedConversation,
    )
    sessionContextDebug(
      "context.midTurnMaintenance session=$sessionId task=${request.task.id} turn=${request.turn} durationMs=${System.currentTimeMillis() - maintenanceStartedAtEpochMs} flush=${memoryFlushSummary?.trace?.outcome ?: "skipped"} compacted=${durableCompaction.trace.compactedThisRun} messages=${updatedConversation.size}",
    )
    recordMaintainedContextWindowTokens(
      sessionId = sessionId,
      llmMetadata = llmMetadata,
    )
    return OpenCrayMidTurnMaintenanceResult(
      sessionContext = updatedContext,
      conversation = updatedConversation,
    )
  }

  private fun remoteCompactionProviderFor(
    gateway: LiteLlmGateway,
    llmMetadata: Map<String, String>,
    authHeaders: Map<String, String>,
  ): RemoteCompactionProvider {
    if (!responsesRemoteCompactionAvailable(llmMetadata)) {
      return NoOpRemoteCompactionProvider
    }
    return RemoteCompactionProvider { request ->
      val messages = request.omittedMessages.toRemoteCompactionGatewayMessages()
      if (messages.isEmpty()) {
        return@RemoteCompactionProvider RemoteCompactionResult.Unavailable(
          reason = "responses_remote_compaction_no_omitted_messages",
          metadata = remoteCompactionFallbackMetadata("responses_remote_compaction_no_omitted_messages"),
        )
      }
      val compactResult = gateway.compactConversation(
        LiteLlmCompactRequest(
          gatewayRequest = LiteLlmGatewayRequest(
            messages = messages,
            metadata = llmMetadata + mapOf(
              LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_REMOTE_COMPACTION to "true",
            ),
            authHeaders = authHeaders,
          ),
          triggerStage = request.triggerStage,
          metadata = request.llmMetadata + mapOf(
            LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_REMOTE_COMPACTION to "true",
          ),
        ),
      )
      when (compactResult) {
        is LiteLlmCompactResult.Success -> {
          val summaryText = compactResult.summaryText.trim()
          if (summaryText.isBlank()) {
            RemoteCompactionResult.Unavailable(
              reason = "responses_remote_compaction_no_text_summary",
              metadata = remoteCompactionFallbackMetadata(
                reason = "responses_remote_compaction_no_text_summary",
                metadata = compactResult.metadata,
              ),
            )
          } else {
            RemoteCompactionResult.Success(
              summary = CompactionSummary(
                text = summaryText,
                compactedMessageCount = request.omittedMessages.size,
                omittedUserMessageCount = request.omittedMessages.count {
                  it.role == RuntimeConversationRole.USER
                },
                omittedAssistantMessageCount = request.omittedMessages.count {
                  it.role == RuntimeConversationRole.ASSISTANT
                },
                omittedToolMessageCount = request.omittedMessages.count {
                  it.role == RuntimeConversationRole.TOOL
                },
                omittedSystemMessageCount = request.omittedMessages.count {
                  it.role == RuntimeConversationRole.SYSTEM
                },
              ),
              metadata = compactResult.metadata,
            )
          }
        }

        is LiteLlmCompactResult.Unavailable -> RemoteCompactionResult.Unavailable(
          reason = compactResult.reason,
          metadata = remoteCompactionFallbackMetadata(
            reason = compactResult.reason,
            metadata = compactResult.metadata,
          ),
        )

        is LiteLlmCompactResult.Failure -> RemoteCompactionResult.Failure(
          errorCode = compactResult.errorCode,
          errorMessage = compactResult.errorMessage,
          metadata = remoteCompactionFallbackMetadata(
            reason = compactResult.errorCode,
            metadata = compactResult.metadata,
          ),
        )
      }
    }
  }

  private fun responsesRemoteCompactionAvailable(llmMetadata: Map<String, String>): Boolean {
    if (LlmProviderProtocols.normalize(llmMetadata["protocol"]) != LlmProviderProtocols.OPENAI_RESPONSES) {
      return false
    }
    return metadataBoolean(llmMetadata, LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_SUPPORTED) ||
      metadataBoolean(llmMetadata, LiteLlmMetadataKeys.VALIDATION_ENABLE_RESPONSES_REMOTE_COMPACTION)
  }

  private fun metadataBoolean(
    metadata: Map<String, String>,
    key: String,
  ): Boolean = metadata[key]
    ?.trim()
    ?.lowercase() == "true"

  private fun remoteCompactionFallbackMetadata(
    reason: String,
    metadata: Map<String, String> = emptyMap(),
  ): Map<String, String> = metadata + mapOf(
    LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_REQUESTED to "true",
    LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_USED to "false",
    LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_FALLBACK_REASON to reason,
  )

  private fun List<RuntimeConversationMessage>.toRemoteCompactionGatewayMessages(): List<LiteLlmGatewayMessage> {
    val messages = mutableListOf<LiteLlmGatewayMessage>()
    var syntheticToolCallIndex = 0
    var pendingToolCallId: String? = null
    forEach { entry ->
      when (entry.role) {
        RuntimeConversationRole.SYSTEM -> {
          entry.content.trim().takeIf(String::isNotBlank)?.let { content ->
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.USER,
              content = "[system]\n$content",
            )
          }
        }

        RuntimeConversationRole.USER -> {
          val content = entry.content.trim().takeIf(String::isNotBlank)
          val attachments = entry.attachments.map { attachment ->
            attachment.toLiteLlmGatewayAttachment()
          }
          if (content != null || attachments.isNotEmpty()) {
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.USER,
              content = content,
              attachments = attachments,
            )
          }
        }

        RuntimeConversationRole.ASSISTANT -> {
          val toolCall = entry.toolCall
          if (entry.kind == RuntimeConversationMessageKind.TOOL_CALL && toolCall != null) {
            val toolCallId = toolCall.id
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: "remote-compact-call-${++syntheticToolCallIndex}"
            pendingToolCallId = toolCallId
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              content = entry.content.trim().takeIf(String::isNotBlank),
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = toolCallId,
                  toolName = toolCall.toolName,
                  arguments = toolCall.arguments,
                  reason = toolCall.reason,
                ),
              ),
              assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase(),
            )
          } else {
            entry.content.trim().takeIf(String::isNotBlank)?.let { content ->
              messages += LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.ASSISTANT,
                content = content,
                assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase()
                  ?: if (entry.kind == RuntimeConversationMessageKind.COMMENTARY) {
                    LiteLlmAssistantPhase.COMMENTARY
                  } else {
                    null
                  },
              )
            }
          }
        }

        RuntimeConversationRole.TOOL -> {
          val toolResult = entry.toolResult
          val toolCallId = toolResult?.toolCallId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: pendingToolCallId
          if (entry.kind == RuntimeConversationMessageKind.TOOL_RESULT && toolResult != null && toolCallId != null) {
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.TOOL,
              toolResult = LiteLlmGatewayToolResult(
                toolCallId = toolCallId,
                toolName = toolResult.toolName,
                content = entry.content.trim().takeIf(String::isNotBlank) ?: "{}",
                isError = toolResult.isError ?: toolResult.status
                  ?.equals("success", ignoreCase = true)
                  ?.not(),
              ),
            )
            pendingToolCallId = null
          } else {
            entry.content.trim().takeIf(String::isNotBlank)?.let { content ->
              messages += LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "[tool]\n$content",
              )
            }
          }
        }
      }
    }
    return messages
  }

  private fun RuntimeConversationAttachment.toLiteLlmGatewayAttachment(): LiteLlmGatewayAttachment =
    LiteLlmGatewayAttachment(
      attachmentId = attachmentId,
      kind = when (kind) {
        RuntimeConversationAttachmentKind.IMAGE -> LiteLlmGatewayAttachmentKind.IMAGE
        RuntimeConversationAttachmentKind.VOICE -> LiteLlmGatewayAttachmentKind.VOICE
        RuntimeConversationAttachmentKind.AUDIO -> LiteLlmGatewayAttachmentKind.AUDIO
        RuntimeConversationAttachmentKind.FILE -> LiteLlmGatewayAttachmentKind.FILE
      },
      displayName = displayName,
      filePath = filePath,
      mimeType = mimeType,
      transcriptText = transcriptText,
    )

  private fun RuntimeConversationAssistantPhase.toLiteLlmAssistantPhase(): LiteLlmAssistantPhase = when (this) {
    RuntimeConversationAssistantPhase.COMMENTARY -> LiteLlmAssistantPhase.COMMENTARY
    RuntimeConversationAssistantPhase.FINAL_ANSWER -> LiteLlmAssistantPhase.FINAL_ANSWER
  }

  private fun transcriptAwareEventSink(
    sessionId: String,
    transcriptStore: SessionTranscriptStore,
    delegate: OpenCrayAgentRuntimeEventSink,
  ): OpenCrayAgentRuntimeEventSink =
    appTranscriptEventSink(
      replayJson = replayJson,
      sessionId = sessionId,
      transcriptStore = transcriptStore,
      delegate = delegate,
    )

  private fun recordFinalAssistantTurn(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    if (task.type != com.opencray.core.contracts.AgentTaskType.PROMPT) {
      return
    }
    if (isLlmRetryPausedResult(result)) {
      return
    }
    val finalTurn = finalTranscriptTurn(
      sessionId = sessionId,
      result = result,
    )
    if (finalTurn.text.isBlank() && finalTurn.attachments.isEmpty()) {
      return
    }
    upsertTrailingFinalAssistantTurn(
      transcriptStore = transcriptStoreForSession(sessionId),
      message = RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = finalTurn.text,
        attachments = finalTurn.attachments,
        assistantPhase = RuntimeConversationAssistantPhase.FINAL_ANSWER,
      ),
    )
  }

  private fun finalTranscriptTurn(
    sessionId: String,
    result: ExecutionResult,
  ): FinalTranscriptTurnSnapshot {
    val assistantText = finalTranscriptText(result)
    val markdownCompatibility = attachmentMarkdownCompatibilityForTranscript(
      sessionId = sessionId,
      result = result,
      text = assistantText,
    )
    val attachments = finalTranscriptAttachments(
      result = result,
      compatibilityAttachments = markdownCompatibility.attachments,
    )
    val rewrittenText = if (markdownCompatibility.rewrittenText.isBlank() && attachments.isNotEmpty()) {
      ""
    } else {
      markdownCompatibility.rewrittenText
    }
    return FinalTranscriptTurnSnapshot(
      text = rewrittenText,
      attachments = attachments,
    )
  }

  private fun finalTranscriptText(result: ExecutionResult): String = when (result.status) {
    ExecutionStatus.SUCCESS -> result.stdout.trim()
    ExecutionStatus.CANCELLED -> TRANSCRIPT_AGENT_CANCELLED
    ExecutionStatus.DENIED -> approvalSupportSanitizePotentialInternalAgentText(
      text = result.errorMessage
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: transcriptAgentFailedText(
          errorCode = result.errorCode,
          detail = result.errorCode ?: result.status.name,
        ),
      fallback = transcriptFinalTextFallback(result),
    )

    ExecutionStatus.FAILED -> approvalSupportSanitizePotentialInternalAgentText(
      text = if (result.errorCode == ERROR_CODE_MISSING_LLM_CONFIG) {
        TRANSCRIPT_AGENT_MISSING_LLM
      } else {
        transcriptAgentFailedText(
          errorCode = result.errorCode,
          detail = result.errorMessage ?: result.errorCode ?: result.status.name,
        )
      },
      fallback = transcriptFinalTextFallback(result),
    )

    else -> approvalSupportSanitizePotentialInternalAgentText(
      text = transcriptAgentFailedText(
        errorCode = result.errorCode,
        detail = result.errorMessage ?: result.errorCode ?: result.status.name,
      ),
      fallback = transcriptFinalTextFallback(result),
    )
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

  private fun upsertTrailingFinalAssistantTurn(
    transcriptStore: SessionTranscriptStore,
    message: RuntimeConversationMessage,
  ) {
    val existingMessages = transcriptStore.snapshot()
    val trailingMessage = existingMessages.lastOrNull()
    if (!isFinalAssistantTranscriptTurn(trailingMessage)) {
      transcriptStore.appendIfDistinct(message)
      return
    }
    val existingFinalMessage = trailingMessage ?: return
    val mergedMessage = existingFinalMessage.copy(
      content = message.content,
      attachments = dedupeTranscriptAttachments(existingFinalMessage.attachments + message.attachments),
      assistantPhase = RuntimeConversationAssistantPhase.FINAL_ANSWER,
    )
    if (mergedMessage == existingFinalMessage) {
      return
    }
    val updatedMessages = existingMessages.toMutableList()
    updatedMessages[updatedMessages.lastIndex] = mergedMessage
    transcriptStore.replace(updatedMessages)
  }

  private fun promptTranscriptInputMessage(
    taskInput: String,
    taskMetadata: Map<String, String>,
  ): RuntimeConversationMessage? {
    val attachments = promptTranscriptAttachments(taskMetadata)
    val promptUserText = taskMetadata[METADATA_PROMPT_USER_TEXT]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val content = promptUserText ?: taskInput.trim().takeIf { input ->
      input.isNotBlank() && attachments.isEmpty()
    }.orEmpty()
    if (content.isBlank() && attachments.isEmpty()) {
      return null
    }
    return RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = content,
      attachments = attachments,
    )
  }

  private fun promptTranscriptAttachments(
    taskMetadata: Map<String, String>,
  ): List<RuntimeConversationAttachment> {
    val attachmentsJson = taskMetadata[METADATA_PROMPT_RUNTIME_ATTACHMENTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return runCatching {
      replayJson.decodeFromString(
        ListSerializer(RuntimeConversationAttachment.serializer()),
        attachmentsJson,
      )
    }.getOrDefault(emptyList())
  }

  private fun mergePromptMessageIntoSeededTranscript(
    transcriptStore: SessionTranscriptStore,
    promptMessage: RuntimeConversationMessage,
  ): Boolean {
    val existingMessages = transcriptStore.snapshot()
    val matchIndex = existingMessages.indexOfLast { message ->
      message.role == RuntimeConversationRole.USER &&
        message.content == promptMessage.content &&
        promptAttachmentsEquivalent(
          existing = message.attachments,
          incoming = promptMessage.attachments,
        )
    }
    if (matchIndex < 0) {
      return false
    }
    val existingMessage = existingMessages[matchIndex]
    val mergedMessage = existingMessage.copy(
      attachments = mergePromptAttachments(
        existing = existingMessage.attachments,
        incoming = promptMessage.attachments,
      ),
    )
    if (mergedMessage == existingMessage) {
      return true
    }
    val updatedMessages = existingMessages.toMutableList()
    updatedMessages[matchIndex] = mergedMessage
    transcriptStore.replace(updatedMessages)
    return true
  }

  private fun mergePromptAttachments(
    existing: List<RuntimeConversationAttachment>,
    incoming: List<RuntimeConversationAttachment>,
  ): List<RuntimeConversationAttachment> {
    if (existing.size != incoming.size) {
      return existing
    }
    return existing.zip(incoming).map { (currentAttachment, incomingAttachment) ->
      currentAttachment.copy(
        attachmentId = incomingAttachment.attachmentId,
        kind = incomingAttachment.kind,
        displayName = incomingAttachment.displayName,
        filePath = incomingAttachment.filePath
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: currentAttachment.filePath,
        mimeType = incomingAttachment.mimeType
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: currentAttachment.mimeType,
        transcriptText = incomingAttachment.transcriptText
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: currentAttachment.transcriptText,
      )
    }
  }

  private fun promptAttachmentsEquivalent(
    existing: List<RuntimeConversationAttachment>,
    incoming: List<RuntimeConversationAttachment>,
  ): Boolean {
    if (existing.size != incoming.size) {
      return false
    }
    return existing.zip(incoming).all { (existingAttachment, incomingAttachment) ->
      existingAttachment.kind == incomingAttachment.kind &&
        promptAttachmentFieldEquivalent(
          existingAttachment.attachmentId,
          incomingAttachment.attachmentId,
        ) &&
        promptAttachmentFieldEquivalent(
          existingAttachment.displayName,
          incomingAttachment.displayName,
        ) &&
        promptAttachmentFieldEquivalent(
          existingAttachment.filePath,
          incomingAttachment.filePath,
        ) &&
        promptAttachmentFieldEquivalent(
          existingAttachment.mimeType,
          incomingAttachment.mimeType,
        ) &&
        promptAttachmentFieldEquivalent(
          existingAttachment.transcriptText,
          incomingAttachment.transcriptText,
        )
    }
  }

  private fun promptAttachmentFieldEquivalent(
    existing: String?,
    incoming: String?,
  ): Boolean {
    val normalizedExisting = existing?.trim()?.takeIf(String::isNotBlank)
    val normalizedIncoming = incoming?.trim()?.takeIf(String::isNotBlank)
    return normalizedExisting == normalizedIncoming ||
      normalizedExisting == null ||
      normalizedIncoming == null
  }

  private fun finalTranscriptAttachments(
    result: ExecutionResult,
    compatibilityAttachments: List<RuntimeConversationAttachment> = emptyList(),
  ): List<RuntimeConversationAttachment> {
    val requests = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { finalAttachmentsJson ->
        runCatching {
          replayJson.decodeFromString(
            ListSerializer(OpenCrayFinalAttachment.serializer()),
            finalAttachmentsJson,
          )
        }.getOrDefault(emptyList())
      }
      .orEmpty()
    val workspaceRoot = workspaceRootsProvider()
      .firstOrNull()
      ?.toAbsolutePath()
      ?.normalize()
    val artifactsById = OpenCrayAttachmentArtifacts.decodeMetadata(
      json = replayJson,
      metadata = result.metadata,
    ).associateByTo(linkedMapOf(), OpenCrayAttachmentArtifact::artifactId)
    val explicitAttachments = requests.mapIndexedNotNull { index, request ->
      val requestedArtifactId = request.artifactId
        ?.trim()
        ?.takeIf(String::isNotBlank)
      val artifact = requestedArtifactId?.let { artifactId ->
        artifactsById[artifactId]
          ?: resolveWorkspaceMediaArtifact(
            workspaceRoot = workspaceRoot,
            artifactId = artifactId,
            mediaArtifactRegistry = workspaceRoot?.let { mediaArtifactRegistryProvider() },
          )?.also { resolved -> artifactsById[resolved.artifactId] = resolved }
      }
      val relativePath = request.relativePath
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: artifact?.relativePath
          ?.trim()
          ?.takeIf(String::isNotBlank)
      val displayName = request.displayName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: artifact?.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: relativePath
          ?.substringAfterLast('/')
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: request.path
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { path -> runCatching { Path.of(path).fileName?.toString() }.getOrNull() }
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: request.chatAttachmentId
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: return@mapIndexedNotNull null
      val kind = request.kind
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotBlank)
        ?: artifact?.kindHint
          ?.trim()
          ?.lowercase()
          ?.takeIf(String::isNotBlank)
        ?: OpenCrayAttachmentArtifacts.kindHintForDisplayName(displayName)
        ?: "file"
      val filePath = request.path
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { rawPath ->
          runCatching {
            Path.of(rawPath).toAbsolutePath().normalize().toString().replace('\\', '/')
          }.getOrNull()
        }
        ?: relativePath?.let { resolvedRelativePath ->
          workspaceRoot?.resolve(resolvedRelativePath)
            ?.toAbsolutePath()
            ?.normalize()
            ?.toString()
            ?.replace('\\', '/')
        }
      val attachmentId = request.chatAttachmentId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: request.artifactId
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: relativePath?.let { resolvedRelativePath ->
          OpenCrayAttachmentArtifacts.buildArtifactId(
            relativePath = resolvedRelativePath,
            displayName = displayName,
          )
        }
        ?: "final-attachment-${index + 1}"
      RuntimeConversationAttachment(
        attachmentId = attachmentId,
        kind = when (kind) {
          "image" -> RuntimeConversationAttachmentKind.IMAGE
          "voice" -> RuntimeConversationAttachmentKind.VOICE
          "audio" -> RuntimeConversationAttachmentKind.AUDIO
          else -> RuntimeConversationAttachmentKind.FILE
        },
        displayName = displayName,
        filePath = filePath,
        mimeType = request.mimeType
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: artifact?.mimeType
            ?.trim()
            ?.takeIf(String::isNotBlank)
          ?: OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(displayName),
        transcriptText = request.transcriptText
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: artifact?.transcriptText
            ?.trim()
            ?.takeIf(String::isNotBlank),
      )
    }
    return dedupeTranscriptAttachments(explicitAttachments + compatibilityAttachments)
  }

  private fun attachmentMarkdownCompatibilityForTranscript(
    sessionId: String,
    result: ExecutionResult,
    text: String,
  ): TranscriptAttachmentMarkdownCompatibility {
    if (text.isBlank()) {
      return TranscriptAttachmentMarkdownCompatibility(rewrittenText = text)
    }
    val references = parseAttachmentMarkdownReferences(text)
    if (references.isEmpty()) {
      return TranscriptAttachmentMarkdownCompatibility(rewrittenText = text)
    }
    val candidates = transcriptAttachmentMarkdownCandidates(
      sessionId = sessionId,
      result = result,
    )
    if (candidates.isEmpty()) {
      return TranscriptAttachmentMarkdownCompatibility(rewrittenText = text)
    }
    val resolvedReferences = references.map { reference ->
      ResolvedAttachmentMarkdownReference(
        reference = reference,
        attachment = resolveAttachmentMarkdownReference(
          reference = reference,
          candidates = candidates,
        ),
      )
    }
    return TranscriptAttachmentMarkdownCompatibility(
      rewrittenText = rewriteAttachmentMarkdownText(
        text = text,
        resolvedReferences = resolvedReferences,
      ),
      attachments = dedupeTranscriptAttachments(
        resolvedReferences.mapNotNull { resolved ->
          resolved.attachment?.toRuntimeConversationAttachment(
            forceImage = resolved.reference.isImage,
          )
        },
      ),
    )
  }

  private fun transcriptAttachmentMarkdownCandidates(
    sessionId: String,
    result: ExecutionResult,
  ): List<AttachmentMarkdownCandidate> {
    val workspaceRoot = workspaceRootsProvider()
      .firstOrNull()
      ?.toAbsolutePath()
      ?.normalize()
    val runArtifacts = OpenCrayAttachmentArtifacts.decodeMetadata(
      json = replayJson,
      metadata = result.metadata,
    )
    val finalArtifactIds = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { finalAttachmentsJson ->
        runCatching {
          replayJson.decodeFromString(
            ListSerializer(OpenCrayFinalAttachment.serializer()),
            finalAttachmentsJson,
          )
        }.getOrDefault(emptyList())
      }
      .orEmpty()
      .mapNotNull { attachment -> attachment.artifactId?.trim()?.takeIf(String::isNotBlank) }
    val artifactsById = runArtifacts.associateByTo(linkedMapOf(), OpenCrayAttachmentArtifact::artifactId)
    finalArtifactIds.forEach { artifactId ->
      if (artifactId !in artifactsById) {
        resolveWorkspaceMediaArtifact(
          workspaceRoot = workspaceRoot,
          artifactId = artifactId,
          mediaArtifactRegistry = workspaceRoot?.let { mediaArtifactRegistryProvider() },
        )?.let { artifact -> artifactsById[artifact.artifactId] = artifact }
      }
    }
    val runCandidates = artifactsById.values.map { artifact ->
      AttachmentMarkdownCandidate(
        attachmentId = artifact.artifactId,
        artifactId = artifact.artifactId,
        relativePath = artifact.relativePath,
        displayName = artifact.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: artifact.relativePath.substringAfterLast('/'),
        kindHint = artifact.kindHint,
        mimeType = artifact.mimeType,
        transcriptText = artifact.transcriptText,
        filePath = workspaceRoot
          ?.resolve(artifact.relativePath)
          ?.toAbsolutePath()
          ?.normalize()
          ?.toString()
          ?.replace('\\', '/'),
      )
    }
    val sessionCandidates = buildList {
      val seenKeys = linkedSetOf<String>()
      sessionContextFactory.loadChatAttachmentEntries(sessionId).forEach { attachment ->
        val dedupeKey = attachment.attachmentId
          .trim()
          .takeIf(String::isNotBlank)
          ?: attachment.localPath.trim().takeIf(String::isNotBlank)
          ?: return@forEach
        if (!seenKeys.add(dedupeKey)) {
          return@forEach
        }
        add(
          AttachmentMarkdownCandidate(
            attachmentId = attachment.attachmentId.trim().takeIf(String::isNotBlank),
            relativePath = attachment.localPath,
            displayName = attachment.displayName,
            kindHint = attachment.kind.toTranscriptAttachmentKindHint(),
            mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
            transcriptText = attachment.transcriptText?.trim()?.takeIf(String::isNotBlank),
            filePath = sessionContextFactory.resolveChatAttachmentFilePath(attachment)
              ?.toString()
              ?.replace('\\', '/')
              ?: attachment.localPath
                .trim()
                .takeIf(String::isNotBlank)
                ?.let { localPath ->
                  runCatching {
                    val path = Path.of(localPath)
                    val resolved = if (path.isAbsolute) {
                      path
                    } else {
                      workspaceRoot?.resolve(path)
                    } ?: return@runCatching null
                    resolved.toAbsolutePath().normalize().toString().replace('\\', '/')
                  }.getOrNull()
                },
          ),
        )
      }
    }
    return runCandidates + sessionCandidates
  }

  private fun dedupeTranscriptAttachments(
    attachments: List<RuntimeConversationAttachment>,
  ): List<RuntimeConversationAttachment> {
    val seen = linkedSetOf<String>()
    return attachments.filter { attachment ->
      val key = attachment.attachmentId
        .trim()
        .takeIf(String::isNotBlank)
        ?.let { attachmentId -> "id:$attachmentId" }
        ?: attachment.filePath
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { filePath -> "path:$filePath" }
        ?: attachment.displayName
          .trim()
          .takeIf(String::isNotBlank)
          ?.lowercase(Locale.US)
          ?.let { displayName -> "name:$displayName" }
        ?: return@filter false
      seen.add(key)
    }
  }

  private fun isLlmRetryPausedResult(
    result: ExecutionResult,
  ): Boolean = result.status == ExecutionStatus.FAILED &&
    result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME

  private fun transcriptFinalTextFallback(result: ExecutionResult): String = when (result.status) {
    ExecutionStatus.DENIED -> transcriptApprovalFallbackText(result.errorCode)
    else -> TRANSCRIPT_AGENT_INTERNAL_PAYLOAD_HIDDEN
  }

  private fun transcriptApprovalFallbackText(errorCode: String?): String = if (
    errorCode == TRANSCRIPT_ERROR_HIGH_RISK_APPROVAL_REQUIRED
  ) {
    TRANSCRIPT_HIGH_RISK_APPROVAL_REQUIRED_BODY
  } else {
    TRANSCRIPT_APPROVAL_REQUIRED_BODY
  }

  private fun transcriptAgentFailedText(errorCode: String?, detail: String): String {
    val shortCode = UserFacingErrorCodes.shortCodeOf(errorCode)
    return if (shortCode == null) {
      "Failed: $detail"
    } else {
      "Failed [$shortCode]: $detail"
    }
  }

  private fun buildSubAgentReplayContent(event: OpenCraySubAgentEvent): String =
    encodeReplayJsonObject {
      put("event_kind", "subagent")
      put("run_id", event.runId)
      put("task_id", event.taskId)
      event.executionId?.let { executionId -> put("execution_id", executionId) }
      event.executionOrdinal?.let { executionOrdinal -> put("execution_ordinal", executionOrdinal) }
      event.executionKind?.let { executionKind -> put("execution_kind", executionKind) }
      event.agentId?.let { agentId -> put("agent_id", agentId) }
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
      put("closed", event.closed)
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
    const val ERROR_CODE_ON_DEVICE_LLM_NOT_SUPPORTED: String = "ON_DEVICE_LLM_NOT_SUPPORTED"
    const val METADATA_HOST_PREFIX: String = "_host."
    const val HOST_METADATA_PROVIDER_ID: String = "${METADATA_HOST_PREFIX}providerId"
    const val HOST_METADATA_BASE_URL: String = "${METADATA_HOST_PREFIX}baseUrl"
    const val METADATA_RUN_ID: String = "${METADATA_HOST_PREFIX}runId"
    const val METADATA_HOST_SESSION_ID: String = "${METADATA_HOST_PREFIX}sessionId"
    const val METADATA_PENDING_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}pendingMessageId"
    const val METADATA_VISIBLE_THROUGH_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}visibleThroughMessageId"
    const val METADATA_PROMPT_USER_TEXT: String = "${METADATA_HOST_PREFIX}promptUserText"
    const val METADATA_PROMPT_RUNTIME_ATTACHMENTS_JSON: String = "${METADATA_HOST_PREFIX}promptRuntimeAttachmentsJson"
    const val PERSISTED_DRAFT_ASSISTANT_STAGE: String = "Draft"
    private const val TRANSCRIPT_ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private const val TRANSCRIPT_AGENT_CANCELLED: String = "Interrupted"
    private const val TRANSCRIPT_AGENT_MISSING_LLM: String = "Missing LLM"
    private const val TRANSCRIPT_AGENT_INTERNAL_PAYLOAD_HIDDEN: String =
      "The agent produced an internal tool payload instead of a user-facing reply."
    private const val TRANSCRIPT_APPROVAL_REQUIRED_BODY: String =
      "Approval required before the agent can continue."
    private const val TRANSCRIPT_HIGH_RISK_APPROVAL_REQUIRED_BODY: String =
      "High-risk approval required. Review this request carefully before approving."
    fun isLlmVisibleMetadataKey(key: String): Boolean = !key.startsWith(METADATA_HOST_PREFIX)

    private fun defaultProviderClient(
      providerUserAgent: String,
    ): LiteLlmProviderClient = AppConfiguredLiteLlmProviderClient(
      cloudProviderClient = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = providerUserAgent,
        streamUpdateMinIntervalMs = 40L,
      ),
      onDeviceProviderClient = LiteRtOnDeviceLlmProviderClient(
        runtime = LiteRtOnDeviceRuntime(
          installStore = InMemoryLiteRtOnDeviceModelInstallStore(),
        ),
      ),
    )
  }
}

private data class FinalTranscriptTurnSnapshot(
  val text: String,
  val attachments: List<RuntimeConversationAttachment>,
)

private data class TranscriptAttachmentMarkdownCompatibility(
  val rewrittenText: String,
  val attachments: List<RuntimeConversationAttachment> = emptyList(),
)

private fun AttachmentMarkdownCandidate.toRuntimeConversationAttachment(
  forceImage: Boolean,
): RuntimeConversationAttachment = RuntimeConversationAttachment(
  attachmentId = attachmentId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: artifactId
      ?.trim()
      ?.takeIf(String::isNotBlank)
    ?: filePath
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.hashCode()
      ?.toString()
    ?: normalizeAttachmentMarkdownToken(relativePath),
  kind = when {
    forceImage -> RuntimeConversationAttachmentKind.IMAGE
    kindHint?.trim()?.equals("image", ignoreCase = true) == true -> RuntimeConversationAttachmentKind.IMAGE
    kindHint?.trim()?.equals("voice", ignoreCase = true) == true -> RuntimeConversationAttachmentKind.VOICE
    kindHint?.trim()?.equals("audio", ignoreCase = true) == true -> RuntimeConversationAttachmentKind.AUDIO
    mimeType?.trim()?.lowercase(Locale.US)?.startsWith("image/") == true ->
      RuntimeConversationAttachmentKind.IMAGE
    else -> RuntimeConversationAttachmentKind.FILE
  },
  displayName = displayName,
  filePath = filePath,
  mimeType = mimeType,
  transcriptText = transcriptText,
)

internal data class PreparedSessionContext(
  val sessionContext: AgentRuntimeSessionContext,
  val effectiveMemoryRecords: List<MemoryRecord>,
)

private sealed interface PreparedAppTaskRuntimeExecution {
  data class Ready(
    val runtime: OpenCrayAgentRuntime,
    val toolDispatcher: OpenCrayToolDispatcher,
    val onDeviceProviderMode: Boolean,
  ) : PreparedAppTaskRuntimeExecution

  data class Failed(
    val result: ExecutionResult,
  ) : PreparedAppTaskRuntimeExecution
}

internal data class ApprovalContinuation(
  val grant: AgentTaskApprovalGrant? = null,
  val rejection: AgentTaskApprovalRejection? = null,
)

internal fun mediaToolSettingsFor(
  mediaSettings: MediaSpeechSettingsState,
  llmSettings: LlmSettingsState,
): OpenCrayMediaToolSettings {
  val imageSettings = mediaSettings.imageGeneration
  val videoSettings = mediaSettings.videoGeneration
  val voiceSettings = mediaSettings.voiceGeneration
  return OpenCrayMediaToolSettings(
    imageGeneration = OpenCrayImageGenerationSettings(
      provider = imageSettings.provider,
      baseUrl = imageSettings.baseUrl,
      endpoint = imageSettings.endpoint,
      model = imageSettings.model,
      authHeaders = ProviderAuthProtocols.authHeaders(
        protocol = imageSettings.authProtocol,
        apiKey = imageSettings.apiKey,
      ),
    ),
    videoGeneration = OpenCrayVideoGenerationSettings(
      provider = videoSettings.provider,
      baseUrl = videoSettings.baseUrl,
      endpoint = videoSettings.endpoint,
      model = videoSettings.model,
      authHeaders = ProviderAuthProtocols.authHeaders(
        protocol = videoSettings.authProtocol,
        apiKey = videoSettings.apiKey,
      ),
    ),
    speechSynthesis = OpenCraySpeechSynthesisSettings(
      provider = voiceSettings.provider,
      baseUrl = voiceSettings.baseUrl,
      endpoint = voiceSettings.endpoint,
      defaultModel = voiceSettings.model,
      defaultVoice = mediaVoiceIdFromPreset(voiceSettings.voicePreset),
      authHeaders = ProviderAuthProtocols.authHeaders(
        protocol = voiceSettings.authProtocol,
        apiKey = voiceSettings.apiKey,
      ),
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

private fun com.opencray.persistence.model.ChatAttachmentKind.toTranscriptAttachmentKindHint(): String =
  when (this) {
    com.opencray.persistence.model.ChatAttachmentKind.IMAGE -> "image"
    com.opencray.persistence.model.ChatAttachmentKind.VOICE -> "voice"
    com.opencray.persistence.model.ChatAttachmentKind.AUDIO -> "audio"
    com.opencray.persistence.model.ChatAttachmentKind.FILE -> "file"
  }
