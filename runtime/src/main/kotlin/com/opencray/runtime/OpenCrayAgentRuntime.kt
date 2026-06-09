package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.AgentLoop
import com.opencray.core.orchestrator.QueueClock
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionQueue
import com.opencray.core.orchestrator.SessionQueueConfig
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.core.orchestrator.SystemQueueClock
import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredFinalAttachment
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
import com.opencray.llm.LiteLlmVisibleTextObserver
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.compaction.DurableCompactionEntryTrace
import com.opencray.runtime.context.ContextBudgetReport
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.ContextAssemblyReport
import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.ContextSourceBudgetProfile
import com.opencray.runtime.context.DuplicateDiscoveryToolHit
import com.opencray.runtime.context.FrontContextZones
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.context.PromptLayerTransportGroup
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
import com.opencray.runtime.context.FrozenToolResultReplayProjection
import com.opencray.runtime.context.ToolProtocolTrace
import com.opencray.runtime.context.ToolResultReplayProjector
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.subagent.BuiltInSubAgentProfiles
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentContextBuilder
import com.opencray.runtime.subagent.SubAgentContextBuildRequest
import com.opencray.runtime.subagent.SubAgentContextMode
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContextPolicy
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentLiveContextSnapshot
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.subagent.SubAgentProfile
import com.opencray.runtime.subagent.SubAgentResultCompressor
import com.opencray.runtime.subagent.SubAgentTask
import com.opencray.runtime.subagent.restoredInterruptedBackgroundSubAgentHandle
import com.opencray.runtime.subagent.withClearedChildPromptCheckpoint
import com.opencray.runtime.subagent.withUpdatedChildPromptCheckpoint
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillCapsuleResolver
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.workingstate.InMemoryWorkingStateStore
import com.opencray.runtime.workingstate.WorkingStateResumeContext
import com.opencray.runtime.workingstate.WorkingStateSupport
import com.opencray.runtime.workingstate.WorkingStateStore
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME: String =
  "LLM_RETRY_EXHAUSTED_AWAITING_RESUME"
const val ERROR_EMPTY_RESPONSE_RECOVERY_EXHAUSTED: String =
  "EMPTY_RESPONSE_RECOVERY_EXHAUSTED"
private const val SUBAGENT_WAIT_PROGRESS_POLL_INTERVAL_MS: Long = 100L

data class OpenCrayAgentRuntimeConfig(
  val maxTurns: Int = DEFAULT_MAX_TURNS,
  val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
  val maxRecoverableLlmRetries: Int = DEFAULT_MAX_RECOVERABLE_LLM_RETRIES,
  val recoverableLlmRetryDelayMs: Long = DEFAULT_RECOVERABLE_LLM_RETRY_DELAY_MS,
  val systemPrompt: String = DEFAULT_OPENCRAY_SYSTEM_PROMPT,
  val sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
  val promptResumeState: OpenCrayPromptResumeState? = null,
  val promptResumeCheckpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
  val approvedSubAgentResume: SubAgentApprovalResume? = null,
  val rejectedSubAgentResume: SubAgentApprovalResume? = null,
  val contextManager: ContextManager = ContextManager(),
  val promptAssembler: PromptAssembler = PromptAssembler(),
  val workingStateStore: WorkingStateStore = InMemoryWorkingStateStore(),
  val supplementInputProvider: (String, String) -> List<OpenCraySupplementInput> = { _, _ -> emptyList() },
  val promptCheckpointSink: (OpenCrayPromptCheckpointEmission) -> Unit = { _ -> },
  val llmMetadata: Map<String, String> = emptyMap(),
  val contextSourceBudgetProfile: ContextSourceBudgetProfile = ContextSourceBudgetPolicy().resolve(llmMetadata),
  val llmAuthHeaders: Map<String, String> = emptyMap(),
  val inheritedActiveSkillCapsule: ActiveSkillCapsule? = null,
  val subAgentContextBuilder: SubAgentContextBuilder = SubAgentContextBuilder(),
  val subAgentContextPolicy: SubAgentContextPolicy = SubAgentContextPolicy(),
  val subAgentExecutionCoordinator: SubAgentExecutionCoordinator =
    InMemorySubAgentExecutionCoordinator(),
  val seededSubAgentHandles: List<SubAgentHandleState> = emptyList(),
  val midTurnMaintenance: (OpenCrayMidTurnMaintenanceRequest) -> OpenCrayMidTurnMaintenanceResult = { request ->
    OpenCrayMidTurnMaintenanceResult(
      sessionContext = request.sessionContext,
      conversation = request.conversation,
    )
  },
  val maxSubAgentDepth: Int = 1,
  val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
  val sleep: (Long) -> Unit = { durationMs -> Thread.sleep(durationMs) },
) {
  init {
    require(maxTurns >= 0) { "OpenCrayAgentRuntimeConfig maxTurns must be >= 0." }
    require(maxToolCalls >= 0) { "OpenCrayAgentRuntimeConfig maxToolCalls must be >= 0." }
    require(maxRecoverableLlmRetries >= 0) {
      "OpenCrayAgentRuntimeConfig maxRecoverableLlmRetries must be >= 0."
    }
    require(recoverableLlmRetryDelayMs >= 0) {
      "OpenCrayAgentRuntimeConfig recoverableLlmRetryDelayMs must be >= 0."
    }
    require(maxSubAgentDepth >= 0) { "OpenCrayAgentRuntimeConfig maxSubAgentDepth must be >= 0." }
    require(systemPrompt.isNotBlank()) { "OpenCrayAgentRuntimeConfig systemPrompt must not be blank." }
  }

  companion object {
    const val DEFAULT_MAX_TURNS: Int = 16
    const val DEFAULT_MAX_TOOL_CALLS: Int = 0
    const val DEFAULT_MAX_RECOVERABLE_LLM_RETRIES: Int = 5
    const val DEFAULT_RECOVERABLE_LLM_RETRY_DELAY_MS: Long = 15_000L
    const val DEFAULT_OPENCRAY_SYSTEM_PROMPT: String =
      "You are OpenCray, a workspace-first coding agent. " +
        "You may call tools when you need concrete workspace facts or to make a change. " +
        "Always prefer tools over guessing when the answer depends on files or local execution."
  }
}

data class OpenCrayMidTurnMaintenanceRequest(
  val task: AgentTask,
  val runId: String,
  val turn: Int,
  val conversation: List<RuntimeConversationMessage>,
  val sessionContext: AgentRuntimeSessionContext,
  val llmMetadata: Map<String, String>,
)

data class OpenCrayMidTurnMaintenanceResult(
  val sessionContext: AgentRuntimeSessionContext? = null,
  val conversation: List<RuntimeConversationMessage>? = null,
)

class OpenCrayAgentRuntime(
  private val gateway: LiteLlmGateway,
  private val toolDispatcher: OpenCrayToolDispatcher,
  private val config: OpenCrayAgentRuntimeConfig = OpenCrayAgentRuntimeConfig(),
  private val eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
  private val clock: () -> Long = System::currentTimeMillis,
) : SessionTaskRuntime {
  private val activeSkillCapsuleResolver: ActiveSkillCapsuleResolver = ActiveSkillCapsuleResolver()
  private val recentToolObservationSupport: RecentToolObservationSupport = RecentToolObservationSupport(
    config = config.contextSourceBudgetProfile.recentToolObservationConfig,
  )
  private val toolResultReplayProjector: ToolResultReplayProjector = ToolResultReplayProjector()
  private val workingStateSupport: WorkingStateSupport = WorkingStateSupport()
  private var pendingApprovedSubAgentResume: SubAgentApprovalResume? = config.approvedSubAgentResume
  private var pendingRejectedSubAgentResume: SubAgentApprovalResume? = config.rejectedSubAgentResume

  override fun execute(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult {
    emitLifecycleEvent(task = task, phase = OpenCrayRunLifecyclePhase.START)
    return try {
      val result = when (task.type) {
        AgentTaskType.PROMPT -> executePromptTask(task = task, hooks = hooks)
        AgentTaskType.TOOL_CALL -> executeDirectToolCall(task = task, hooks = hooks)
        AgentTaskType.SKILL_CALL -> executeDirectSkillCall(task = task)
        AgentTaskType.SYSTEM -> successResult(
          task = task,
          body = task.input,
          startedAt = clock(),
          finishedAt = clock(),
          metadata = mapOf("taskType" to task.type.name, "responseFormat" to "passthrough"),
        )
      }
      emitLifecycleEvent(
        task = task,
        phase = when (result.status) {
          ExecutionStatus.CANCELLED -> OpenCrayRunLifecyclePhase.CANCELLED
          ExecutionStatus.FAILED,
          ExecutionStatus.TIMEOUT,
          -> OpenCrayRunLifecyclePhase.ERROR
          ExecutionStatus.SUCCESS,
          ExecutionStatus.DENIED,
          -> OpenCrayRunLifecyclePhase.END
        },
        result = result,
      )
      result
    } catch (throwable: Throwable) {
      emitLifecycleEvent(
        task = task,
        phase = OpenCrayRunLifecyclePhase.ERROR,
        errorCode = "RUNTIME_EXCEPTION",
        errorMessage = throwable.message ?: throwable::class.java.simpleName,
      )
      throw throwable
    }
  }

  fun executeDetachedSubAgentRecoveryWait(
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult {
    val startedAt = clock()
    val call = AgentToolCall(
      toolName = "wait_agent",
      arguments = buildJsonObject {
        put("agent_id", agentId)
      },
    )
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolCallEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = 0,
        call = call,
        emittedAtEpochMs = clock(),
      ),
    )
    val activeSkillCapsule = resolveActiveSkillCapsule(
      activeSkillName = config.promptResumeState?.activeSkillName
        ?: config.inheritedActiveSkillCapsule?.name,
      activationSource = config.promptResumeState?.activeSkillActivationSource
        ?: config.inheritedActiveSkillCapsule?.activationSource,
      pinned = config.promptResumeState?.activeSkillPinned
        ?: config.inheritedActiveSkillCapsule?.pinned,
    )
    val toolResult = waitOnDetachedRecoverySubAgentHandle(
      task = task,
      turn = 0,
      call = call,
      transcript = config.sessionContext.conversation,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      agentId = agentId,
      parentRunId = parentRunId,
    )
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolResultEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = 0,
        call = call,
        result = toolResult,
        emittedAtEpochMs = clock(),
      ),
    )
    emitMemoryRetrievalEvent(
      task = task,
      turn = 0,
      call = call,
      result = toolResult,
    )
    return toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      json = config.json,
    ).also { result ->
      if (toolResult.isApprovalRequiredDenial()) {
        hooks.requestSuspend(
          SuspensionRequest(
            reasonCode = result.errorCode ?: "APPROVAL_REQUIRED",
            detail = result.errorMessage,
          ),
        )
      }
    }
  }

  private fun executePromptTask(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult {
    val startedAt = clock()
    val parentRunId = runIdFor(task)
    val seededTranscript = seededConversation(task)
    val executionTranscript = promptTranscriptForExecution(
      seededTranscript = seededTranscript,
      resumeState = config.promptResumeState,
    )
    val cursor = PromptTurnCursor(
      transcript = executionTranscript.toMutableList(),
      sessionContext = config.sessionContext.copy(conversation = executionTranscript),
      turn = config.promptResumeState?.turnIndex ?: 0,
      toolCallCount = config.promptResumeState?.toolCallCount ?: 0,
      todoWriteUsed = executionTranscript.any { entry ->
        entry.role == RuntimeConversationRole.ASSISTANT &&
          runtimeToolCallFor(entry)?.toolName == "TodoWrite"
      },
      activeSkillName = config.promptResumeState?.activeSkillName
        ?: config.inheritedActiveSkillCapsule?.name,
      activeSkillActivationSource = config.promptResumeState?.activeSkillActivationSource
        ?: config.inheritedActiveSkillCapsule?.activationSource,
      activeSkillPinned = config.promptResumeState?.activeSkillPinned
        ?: config.inheritedActiveSkillCapsule?.pinned
        ?: false,
      nextSyntheticToolCallSequence = nextSyntheticToolCallSequence(executionTranscript),
      legacyJsonFallbackEnabled = false,
      responsesPreviousResponseId = config.promptResumeState?.responsesPreviousResponseId,
      responsesProviderLineageId = config.promptResumeState?.responsesProviderLineageId,
      responsesLineageTrusted = config.promptResumeState?.responsesLineageTrusted
        ?: false,
      responsesFullReplayRequired = false,
      responsesContinuationShape = config.promptResumeState?.responsesContinuationShape?.let { shape ->
        ResponsesContinuationShape(
          stableAnchor = shape.stableAnchor,
          frontContextZones = shape.restoredFrontContextZones(),
          toolPoolFingerprint = shape.toolPoolFingerprint,
          toolSchemaFingerprint = shape.toolSchemaFingerprint,
          requestSettingsFingerprint = shape.requestSettingsFingerprint,
        )
      },
      responsesPendingMessages = config.promptResumeState
        ?.restoredResponsesPendingMessages()
        ?.toMutableList()
        ?: mutableListOf(),
      replayToolResultProjections = config.promptResumeState
        ?.replayToolResultProjections
        ?.toMutableMap()
        ?: linkedMapOf(),
      localContinuationEnvelope = config.promptResumeState?.localContinuationEnvelope?.let { envelope ->
        LocalContinuationEnvelope(
          stableAnchor = envelope.stableAnchor,
          frontContextZones = envelope.restoredFrontContextZones(),
          toolPoolFingerprint = envelope.toolPoolFingerprint,
          toolSchemaFingerprint = envelope.toolSchemaFingerprint,
          requestSettingsFingerprint = envelope.requestSettingsFingerprint,
          transcriptFrontier = envelope.restoredTranscriptFrontier(executionTranscript),
          gatewayMessages = envelope.restoredGatewayMessages(),
        )
      },
      subAgentHandles = seededSubAgentHandles(parentRunId),
      subAgentExecutionLock = Any(),
    )
    maybeSelectImplicitInlineSkill(task.input)?.let { implicitSkill ->
      if (cursor.activeSkillName.isNullOrBlank()) {
        cursor.activeSkillName = implicitSkill.name
        cursor.activeSkillActivationSource = ACTIVATION_SOURCE_IMPLICIT_SKILL
        cursor.activeSkillPinned = false
      }
    }
    var lastGatewayResult: LiteLlmGatewayResult? = null
    var lastContextReport: ContextAssemblyReport? = null
    var protocolErrorCount = 0
    var lastProtocolErrorMessage: String? = null
    val diagnostics = PromptRunDiagnostics()
    var skipTurnStartSupplements = false
    var cancelOpenSubAgentsOnExit = false
    var cancelOpenSubAgentsReason = "Parent run was cancelled."
    try {
      config.promptResumeState?.let { resumeState ->
        val resumedActions = resumeState.resumableActions()
        if (resumedActions.isEmpty()) {
          return@let
        }
        val resumeActionIndex = resumeState.normalizedNextActionIndex()
        val activeSkillCapsule = resolveActiveSkillCapsule(
          activeSkillName = cursor.activeSkillName,
          activationSource = cursor.activeSkillActivationSource,
          pinned = cursor.activeSkillPinned,
        )
        val visibleToolDefinitions = visibleToolDefinitionsForTurn(
          allDefinitions = toolDispatcher.definitions(),
          activeSkillCapsule = activeSkillCapsule,
          memoryToolsEnabled = cursor.sessionContext.memoryToolsEnabled,
        )
        val requestedBuiltinTools = providerBuiltinToolsForTurn(
          visibleToolDefinitions = visibleToolDefinitions,
        )
        val functionVisibleToolDefinitions = functionToolDefinitionsForTurn(
          visibleToolDefinitions = visibleToolDefinitions,
          builtinTools = requestedBuiltinTools,
        )
        val nativeToolCallingEnabled = nativeToolCallingEnabledForTurn(
          visibleToolDefinitions = functionVisibleToolDefinitions,
          builtinTools = requestedBuiltinTools,
        )
        val legacyJsonFallbackEnabled = legacyJsonFallbackEnabledForTurn(
          nativeToolCallingEnabled = nativeToolCallingEnabled,
          priorFallbackEnabled = cursor.legacyJsonFallbackEnabled,
        )
        cursor.legacyJsonFallbackEnabled = legacyJsonFallbackEnabled
        when (
          val outcome = executePromptActionBatch(
            task = task,
            startedAt = startedAt,
            gatewayResult = null,
            contextReport = null,
            parsedBatch = ParsedModelActionBatch.Actions(
              actions = resumedActions.map { action -> action.toRuntimeAction() },
              ignoredNonActionContent = resumeState.requiresSingleActionReminder,
            ),
            cursor = cursor,
            hooks = hooks,
            activeSkillCapsule = activeSkillCapsule,
            diagnostics = diagnostics,
            nativeToolCallingEnabled = nativeToolCallingEnabled,
            legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
            actionStartIndex = resumeActionIndex,
            suppressToolCallEventAtActionIndex = resumeActionIndex,
            localContinuationToolPoolFingerprint = null,
            localContinuationToolSchemaFingerprint = null,
            localContinuationRequestSettingsFingerprint = null,
          )
        ) {
          is PromptBatchExecutionOutcome.Continue -> Unit
          is PromptBatchExecutionOutcome.Terminal -> return outcome.result
        }
      }

      while (hasTurnBudgetRemaining(cursor.turn)) {
        if (hooks.isCancellationRequested()) {
          cancelOpenSubAgentsOnExit = true
          cancelOpenSubAgentsReason = "Parent run was cancelled by the user."
          return cancelledResult(task = task, startedAt = startedAt, finishedAt = clock())
        }

        if (!skipTurnStartSupplements) {
          applyTurnStartSupplements(
            task = task,
            turn = cursor.turn,
            cursor = cursor,
          )
        }
        skipTurnStartSupplements = false

        val activeSkillCapsule = resolveActiveSkillCapsule(
          activeSkillName = cursor.activeSkillName,
          activationSource = cursor.activeSkillActivationSource,
          pinned = cursor.activeSkillPinned,
        )
        val visibleToolDefinitions = visibleToolDefinitionsForTurn(
          allDefinitions = toolDispatcher.definitions(),
          activeSkillCapsule = activeSkillCapsule,
          memoryToolsEnabled = cursor.sessionContext.memoryToolsEnabled,
        )
        val requestedBuiltinTools = providerBuiltinToolsForTurn(
          visibleToolDefinitions = visibleToolDefinitions,
        )
        val functionVisibleToolDefinitions = functionToolDefinitionsForTurn(
          visibleToolDefinitions = visibleToolDefinitions,
          builtinTools = requestedBuiltinTools,
        )
        val nativeToolCallingEnabled = nativeToolCallingEnabledForTurn(
          visibleToolDefinitions = functionVisibleToolDefinitions,
          builtinTools = requestedBuiltinTools,
        )
        val legacyJsonFallbackEnabled = legacyJsonFallbackEnabledForTurn(
          nativeToolCallingEnabled = nativeToolCallingEnabled,
          priorFallbackEnabled = cursor.legacyJsonFallbackEnabled,
        )
        cursor.legacyJsonFallbackEnabled = legacyJsonFallbackEnabled
        val turnAwareConversation = promptConversationForTurn(
          transcript = cursor.transcript,
          turn = cursor.turn,
          legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
        )
        val strictToolSchemaEnabled = config.llmMetadata["toolSchemaStrict"]
          ?.trim()
          ?.lowercase() == "true"
        val nativeToolDefinitions = functionVisibleToolDefinitions.map { definition ->
          agentToolDefinitionToLiteLlmToolDefinition(
            definition = definition,
            strict = strictToolSchemaEnabled,
          )
        }
        val requestedNativeToolDefinitions = if (nativeToolCallingEnabled) {
          nativeToolDefinitions
        } else {
          emptyList()
        }
        val activeBuiltinTools = if (nativeToolCallingEnabled) {
          requestedBuiltinTools
        } else {
          emptyList()
        }
        val requestedParallelToolCallsOverride = requestedParallelToolCalls(
          nativeToolCallingEnabled = requestedNativeToolDefinitions.isNotEmpty(),
        )
        diagnostics.nativeToolCallRequested =
          requestedNativeToolDefinitions.isNotEmpty() || activeBuiltinTools.isNotEmpty()
        val managedContext = config.contextManager.prepare(
          PromptAssemblyInput(
            task = task,
            runId = parentRunId,
            baseSystemPrompt = config.systemPrompt,
            sessionContext = cursor.sessionContext,
            activeSkillCapsule = activeSkillCapsule,
            nativeToolCallingEnabled = nativeToolCallingEnabled,
            parallelToolCallsEnabled = requestedParallelToolCallsOverride == true,
            legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
            toolDefinitions = functionVisibleToolDefinitions,
            liveConversation = turnAwareConversation,
            todoSnapshot = toolDispatcher.todoSnapshot(),
            resumeContext = WorkingStateResumeContext.from(
              promptResumeState = config.promptResumeState,
              checkpointBoundary = config.promptResumeCheckpointBoundary,
            ),
            llmMetadata = config.llmMetadata,
          ),
        )
        config.workingStateStore.replace(managedContext.workingState)
        val assembledPrompt = config.promptAssembler.assemble(managedContext)
        lastContextReport = assembledPrompt.report
        val stableLocalContinuationAnchor = stableLocalContinuationAnchor(assembledPrompt)
        val enforcedSystemPrompt = enforcedSystemPromptForTurn(
          systemPrompt = assembledPrompt.systemPrompt,
          turn = cursor.turn,
          legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
        )
        val requestedToolChoiceOverride = requestedToolChoice()
        val responseApiPreferredOverride = responseApiPreferred() || isResponsesProtocol()
        val toolPoolFingerprint = promptCacheToolPoolFingerprint(
          requestedNativeToolDefinitions = requestedNativeToolDefinitions,
          activeBuiltinTools = activeBuiltinTools,
        )
        val toolSchemaFingerprint = promptCacheToolSchemaFingerprint(
          requestedNativeToolDefinitions = requestedNativeToolDefinitions,
          activeBuiltinTools = activeBuiltinTools,
        )
        val requestSettingsFingerprint = promptCacheRequestSettingsFingerprint(
          nativeToolCallingEnabled = nativeToolCallingEnabled,
          requestedParallelToolCalls = requestedParallelToolCallsOverride,
          requestedToolChoice = requestedToolChoiceOverride,
          responseApiPreferred = responseApiPreferredOverride,
        )
        val gatewayMessagePlan = if (isResponsesProtocol()) {
          buildResponsesGatewayMessagePlan(
            cursor = cursor,
            frontContextPrompts = assembledPrompt.frontContextPrompts,
            stableAnchor = stableLocalContinuationAnchor,
            toolPoolFingerprint = toolPoolFingerprint,
            toolSchemaFingerprint = toolSchemaFingerprint,
            requestSettingsFingerprint = requestSettingsFingerprint,
            transcript = turnAwareConversation,
          )
        } else {
          buildNonResponsesGatewayMessagePlan(
            cursor = cursor,
            transcript = cursor.transcript,
            turnAwareConversation = turnAwareConversation,
            frontContextPrompts = assembledPrompt.frontContextPrompts,
            stableAnchor = stableLocalContinuationAnchor,
            toolPoolFingerprint = toolPoolFingerprint,
            toolSchemaFingerprint = toolSchemaFingerprint,
            requestSettingsFingerprint = requestSettingsFingerprint,
          )
        }
        val contextCacheBreakReason = contextCacheBreakReason(
          cursor = cursor,
          plan = gatewayMessagePlan,
        )
        diagnostics.recordGatewayMessagePlan(
          plan = gatewayMessagePlan,
          contextCacheBreakReason = contextCacheBreakReason,
        )
        val contextCacheShapeMetadata = nonResponsesContextCacheShapeMetadata(
          stableAnchor = stableLocalContinuationAnchor,
          frontContextZones = assembledPrompt.frontContextZones,
        )
        diagnostics.recordContextCacheShapeMetadata(contextCacheShapeMetadata)
        val gatewayMessages = gatewayMessagePlan.messages
        check(gatewayMessages.isNotEmpty()) {
          buildString {
            append("Prompt-task gateway request must always use message assembly. ")
            append("Received an empty messages list for task_id=")
            append(task.id)
            append(", turn=")
            append(cursor.turn)
            append(", mode=")
            append(gatewayMessagePlan.mode.wireValue)
            gatewayMessagePlan.reason?.let { reason ->
              append(", reason=")
              append(reason)
            }
          }
        }
        val gatewayMessagesEnabled = true
        val runId = runIdFor(task)
        val requestedPreviousResponseIdOverride =
          gatewayMessagePlan.previousResponseId ?: requestedPreviousResponseId(cursor)
        val request = LiteLlmGatewayRequest(
          requestId = "agent-$runId-turn-${cursor.turn}-${UUID.randomUUID().toString().take(8)}",
          systemPrompt = enforcedSystemPrompt,
          messages = gatewayMessages,
          tools = requestedNativeToolDefinitions,
          builtinTools = activeBuiltinTools,
          toolChoice = requestedToolChoiceOverride,
          parallelToolCalls = requestedParallelToolCallsOverride,
          previousResponseId = requestedPreviousResponseIdOverride,
          responseApiPreferred = responseApiPreferredOverride,
          metadata = buildMap {
            put("runId", runId)
            put("taskId", task.id)
            put("taskType", task.type.name)
            put("turnIndex", cursor.turn.toString())
            remainingTurnBudget(cursor.turn)?.let { remainingTurns ->
              put("remainingTurnCount", remainingTurns.toString())
              put("maxTurnCount", config.maxTurns.toString())
            }
            put("contextSourceMessageCount", assembledPrompt.report.sourceTranscriptMessageCount.toString())
            put("contextWindowMessageCount", assembledPrompt.report.windowedTranscriptMessageCount.toString())
            put("contextMessageCount", assembledPrompt.report.windowedTranscriptMessageCount.toString())
            put("contextOmittedMessageCount", assembledPrompt.report.omittedTranscriptMessageCount.toString())
            put("contextTruncatedMessageCount", assembledPrompt.report.truncatedTranscriptMessageCount.toString())
            put("contextPrunedMessageCount", assembledPrompt.report.prunedTranscriptMessageCount.toString())
            put("contextRewrittenMessageCount", assembledPrompt.report.rewrittenTranscriptMessageCount.toString())
            put("contextPruningSummaryIncluded", assembledPrompt.report.pruningSummaryIncluded.toString())
            put("contextRecentObservationCount", assembledPrompt.report.recentToolObservationCount.toString())
            put("contextRecentObservationLayerIncluded", assembledPrompt.report.recentToolObservationLayerIncluded.toString())
            put("contextWorkingStateIncluded", assembledPrompt.report.workingStateTrace.included.toString())
            put("contextWorkingStateObjectivePresent", assembledPrompt.report.workingStateTrace.objectivePresent.toString())
            put("contextWorkingStateFindingCount", assembledPrompt.report.workingStateTrace.findingCount.toString())
            put("contextWorkingStateRecentActionCount", assembledPrompt.report.workingStateTrace.recentActionCount.toString())
            put("contextWorkingStateDecisionCount", assembledPrompt.report.workingStateTrace.decisionCount.toString())
            put("contextWorkingStateBlockerCount", assembledPrompt.report.workingStateTrace.blockerCount.toString())
            put("contextWorkingStateNextActionCount", assembledPrompt.report.workingStateTrace.nextActionCount.toString())
            put(
              "contextWorkingStateSynthesizedFromResumeContext",
              assembledPrompt.report.workingStateTrace.synthesizedFromResumeContext.toString(),
            )
            put("contextWorkingStateSynthesizedFromTodos", assembledPrompt.report.workingStateTrace.synthesizedFromTodoSnapshot.toString())
            putContextBudgetMetadata(assembledPrompt.report.budgetReport)
            putContextToolProtocolMetadata(this, assembledPrompt.report.toolProtocolTrace)
            put("gatewayTransportMode", "messages_primary")
            put("gatewayPromptFieldRole", "fallback_debug_only")
            put("localContinuationMode", gatewayMessagePlan.mode.wireValue)
            gatewayMessagePlan.reason?.let { reason ->
              put("localContinuationReason", reason)
            }
            contextCacheBreakReason?.let { reason ->
              put(LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON, reason)
            }
            putAll(contextCacheShapeMetadata)
            cursor.responsesProviderLineageId
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?.let { lineageId ->
                put(LiteLlmMetadataKeys.RESPONSES_LINEAGE_ID, lineageId)
              }
            put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, requestedNativeToolDefinitions.isNotEmpty().toString())
            putAll(task.metadata.filterKeys(::isGatewayVisibleMetadataKey))
            putAll(config.llmMetadata)
          },
          authHeaders = config.llmAuthHeaders,
        )
        val preModelCheckpointEpochMs = clock()
        emitPromptCheckpoint(
          boundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
          cursor = cursor,
          turnIndex = cursor.turn,
          emittedAtEpochMs = preModelCheckpointEpochMs,
          localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
          localContinuationStableAnchor = stableLocalContinuationAnchor,
          localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
          localContinuationToolPoolFingerprint = toolPoolFingerprint,
          localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
          localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
        )
        emitInternalCheckpointJournalMarker(
          task = task,
          turn = cursor.turn,
          boundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
          cursor = cursor,
          turnIndex = cursor.turn,
          emittedAtEpochMs = preModelCheckpointEpochMs,
          localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
          localContinuationStableAnchor = stableLocalContinuationAnchor,
          localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
          localContinuationToolPoolFingerprint = toolPoolFingerprint,
          localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
          localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
        )
        val gatewayTurnExecution = executeGatewayTurnWithRecovery(
          task = task,
          request = request,
          turn = cursor.turn,
          hooks = hooks,
          diagnostics = diagnostics,
        )
      if (gatewayTurnExecution is GatewayTurnExecution.Cancelled) {
        cancelOpenSubAgentsOnExit = true
        cancelOpenSubAgentsReason = "Parent run was cancelled by the user."
        return cancelledResult(
          task = task,
          startedAt = startedAt,
          finishedAt = clock(),
        )
      }
      val completedGatewayTurn = gatewayTurnExecution as GatewayTurnExecution.Completed
      val gatewayResult = completedGatewayTurn.result
      lastGatewayResult = gatewayResult
      if (completedGatewayTurn.retryBudgetExhausted) {
        return llmRetryExhaustedPauseResult(
          task = task,
          startedAt = startedAt,
          gatewayResult = gatewayResult,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount,
          contextReport = lastContextReport,
          cursor = cursor,
          hooks = hooks,
          diagnostics = diagnostics,
          localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
          localContinuationStableAnchor = stableLocalContinuationAnchor,
          localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
          localContinuationToolPoolFingerprint = toolPoolFingerprint,
          localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
          localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
        )
      }
      if (gatewayResult.status != LiteLlmGatewayStatus.SUCCESS) {
        val responsesContinuationRecoveryReason = responsesContinuationRecoveryReason(
          request = request,
          gatewayResult = gatewayResult,
        )
        if (responsesContinuationRecoveryReason != null) {
          diagnostics.responsesContinuationRecoveryCount += 1
          diagnostics.responsesContinuationRecoveryLastReason = responsesContinuationRecoveryReason
          clearAssistantDraft(task)
          emitResponsesContinuationRecoveryEvent(
            task = task,
            turn = cursor.turn,
            reason = responsesContinuationRecoveryReason,
          )
          invalidateResponsesLineage(cursor)
          invalidateLocalContinuation(cursor)
          skipTurnStartSupplements = true
          continue
        }
        invalidateResponsesLineage(cursor)
        invalidateLocalContinuation(cursor)
        val providerEmptyResponseFailure = isProviderEmptyResponseFailure(gatewayResult)
        val recoveryObservation = recoverableGatewayFailureObservation(
          gatewayResult = gatewayResult,
          nativeToolCallingEnabled = nativeToolCallingEnabled,
          legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
          diagnostics = diagnostics,
        )
        if (recoveryObservation != null) {
          clearAssistantDraft(task)
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = recoveryObservation,
          )
          cursor.turn += 1
          continue
        }
        if (providerEmptyResponseFailure) {
          clearAssistantDraft(task)
          return emptyResponseRecoveryExhaustedResult(
            task = task,
            startedAt = startedAt,
            gatewayResult = gatewayResult,
            turn = cursor.turn,
            toolCallCount = cursor.toolCallCount,
            contextReport = lastContextReport,
            diagnostics = diagnostics,
          )
        }
        return llmFailureResult(
          task = task,
          startedAt = startedAt,
          gatewayResult = gatewayResult,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount,
          contextReport = lastContextReport,
          diagnostics = diagnostics,
        )
      }
      emitBuiltinWebSearchObservations(
        task = task,
        turn = cursor.turn,
        cursor = cursor,
        gatewayResult = gatewayResult,
        diagnostics = diagnostics,
        localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
        localContinuationStableAnchor = stableLocalContinuationAnchor,
        localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
        localContinuationToolPoolFingerprint = toolPoolFingerprint,
        localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
        localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
      )

      val parsedGatewayResult = parseGatewayResultActionBatch(
        gatewayResult = gatewayResult,
        diagnostics = diagnostics,
      )
      if (parsedGatewayResult == null) {
        invalidateResponsesLineage(cursor)
        invalidateLocalContinuation(cursor)
        val successfulEmptyResponse = isSuccessfulEmptyResponse(gatewayResult)
        val recoveryObservation = recoverableSuccessfulEmptyResponseObservation(
          gatewayResult = gatewayResult,
          nativeToolCallingEnabled = nativeToolCallingEnabled,
          legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
          diagnostics = diagnostics,
        )
        if (recoveryObservation != null) {
          clearAssistantDraft(task)
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = recoveryObservation,
          )
          cursor.turn += 1
          continue
        }
        if (successfulEmptyResponse) {
          clearAssistantDraft(task)
          return emptyResponseRecoveryExhaustedResult(
            task = task,
            startedAt = startedAt,
            gatewayResult = gatewayResult,
            turn = cursor.turn,
            toolCallCount = cursor.toolCallCount,
            contextReport = lastContextReport,
            diagnostics = diagnostics,
          )
        }
        error("Parsed gateway result is null despite visible output.")
      }
      if (nativeToolCallingEnabled && parsedGatewayResult.usedLegacyJsonFallback) {
        cursor.legacyJsonFallbackEnabled = true
      }
      when (val parsedBatch = parsedGatewayResult.batch) {
        is ParsedModelActionBatch.ProtocolError -> {
          invalidateResponsesLineage(cursor)
          invalidateLocalContinuation(cursor)
          protocolErrorCount += 1
          lastProtocolErrorMessage = parsedBatch.reason
          if (nativeToolCallingEnabled) {
            cursor.legacyJsonFallbackEnabled = true
          }
          val outputPreview = gatewayResult.completion?.rawText
            ?: gatewayResult.outputText
            ?: ""
          outputPreview
            .trim()
            .take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS)
            .takeIf(String::isNotBlank)
            ?.let { preview ->
              cursor.transcript += RuntimeConversationMessage(
                role = RuntimeConversationRole.ASSISTANT,
                content = preview,
              )
            }
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = buildProtocolRecoveryObservation(
              nativeToolCallingEnabled = nativeToolCallingEnabled,
              legacyJsonFallbackEnabled = legacyJsonFallbackEnabledForTurn(
                nativeToolCallingEnabled = nativeToolCallingEnabled,
                priorFallbackEnabled = true,
              ),
              rawOutput = outputPreview,
              reason = parsedBatch.reason,
            ),
          )
          clearAssistantDraft(task)
          cursor.turn += 1
          continue
        }

        is ParsedModelActionBatch.Actions -> {
          updateResponsesContinuationState(
            cursor = cursor,
            gatewayResult = gatewayResult,
            continuationShape = ResponsesContinuationShape(
              stableAnchor = stableLocalContinuationAnchor,
              frontContextZones = assembledPrompt.frontContextZones,
              toolPoolFingerprint = toolPoolFingerprint,
              toolSchemaFingerprint = toolSchemaFingerprint,
              requestSettingsFingerprint = requestSettingsFingerprint,
            ),
          )
          val actionBatchCheckpointEpochMs = clock()
          emitPromptCheckpoint(
            boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
            cursor = cursor,
            turnIndex = cursor.turn,
            emittedAtEpochMs = actionBatchCheckpointEpochMs,
            pendingActions = parsedBatch.actions,
            nextActionIndex = 0,
            requiresSingleActionReminder = parsedBatch.requiresSingleActionReminder,
            localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
            localContinuationStableAnchor = stableLocalContinuationAnchor,
            localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
            localContinuationToolPoolFingerprint = toolPoolFingerprint,
            localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
            localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
          )
          emitInternalCheckpointJournalMarker(
            task = task,
            turn = cursor.turn,
            boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
            cursor = cursor,
            turnIndex = cursor.turn,
            emittedAtEpochMs = actionBatchCheckpointEpochMs,
            pendingActions = parsedBatch.actions,
            nextActionIndex = 0,
            requiresSingleActionReminder = parsedBatch.requiresSingleActionReminder,
            localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
            localContinuationStableAnchor = stableLocalContinuationAnchor,
            localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
            localContinuationToolPoolFingerprint = toolPoolFingerprint,
            localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
            localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
          )
          when (
            val outcome = executePromptActionBatch(
              task = task,
              startedAt = startedAt,
              gatewayResult = gatewayResult,
              contextReport = lastContextReport,
              parsedBatch = parsedBatch,
              cursor = cursor,
              hooks = hooks,
              activeSkillCapsule = activeSkillCapsule,
              diagnostics = diagnostics,
              nativeToolCallingEnabled = nativeToolCallingEnabled,
              legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
              localContinuationContextPrompts = assembledPrompt.frontContextPrompts,
              localContinuationStableAnchor = stableLocalContinuationAnchor,
              localContinuationGatewayMessagesEnabled = gatewayMessagesEnabled,
              localContinuationToolPoolFingerprint = toolPoolFingerprint,
              localContinuationToolSchemaFingerprint = toolSchemaFingerprint,
              localContinuationRequestSettingsFingerprint = requestSettingsFingerprint,
            )
          ) {
            is PromptBatchExecutionOutcome.Continue -> {
              clearAssistantDraft(task)
              refreshLocalContinuationEnvelope(
                cursor = cursor,
                frontContextPrompts = assembledPrompt.frontContextPrompts,
                stableAnchor = stableLocalContinuationAnchor,
                gatewayMessagesEnabled = gatewayMessagesEnabled,
                toolPoolFingerprint = toolPoolFingerprint,
                toolSchemaFingerprint = toolSchemaFingerprint,
                requestSettingsFingerprint = requestSettingsFingerprint,
              )
              continue
            }
            is PromptBatchExecutionOutcome.Terminal -> return outcome.result
          }
        }
      }
    }

      if (lastProtocolErrorMessage != null) {
        return failedResult(
          task = task,
          startedAt = startedAt,
          finishedAt = clock(),
          errorCode = "MODEL_ACTION_FORMAT_ERROR",
          errorMessage = "The model returned invalid tool/action payloads and never recovered.",
          metadata = buildResultMetadata(
            gatewayResult = lastGatewayResult,
            turn = cursor.turn,
            toolCallCount = cursor.toolCallCount,
            responseFormat = "protocol_error_exhausted",
            contextReport = lastContextReport,
            diagnostics = diagnostics,
          ) + mapOf(
            "protocolErrorCount" to protocolErrorCount.toString(),
            "lastProtocolError" to lastProtocolErrorMessage.orEmpty(),
          ),
        )
      }

      return failedResult(
        task = task,
        startedAt = startedAt,
        finishedAt = clock(),
        errorCode = "MAX_TURNS_EXCEEDED",
        errorMessage = "Agent exceeded the configured turn limit without producing a final answer.",
        metadata = buildResultMetadata(
          gatewayResult = lastGatewayResult,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount,
          responseFormat = "turn_limit_exceeded",
          contextReport = lastContextReport,
          diagnostics = diagnostics,
        ),
      )
    } finally {
      if (cancelOpenSubAgentsOnExit) {
        cancelActiveSubAgentExecutions(
          task = task,
          turn = cursor.turn,
          cursor = cursor,
          reason = cancelOpenSubAgentsReason,
          removeHandles = false,
        )
      }
    }
  }

  private fun executeDirectToolCall(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult {
    val startedAt = clock()
    val parsedBatch = parseModelActionBatch(task.input)
    val toolCall = (parsedBatch as? ParsedModelActionBatch.Actions)
      ?.actions
      ?.filterIsInstance<AgentModelAction.ToolCall>()
      ?.firstOrNull()
      ?.call
      ?: return failedResult(
        task = task,
        startedAt = startedAt,
        finishedAt = clock(),
        errorCode = "INVALID_TOOL_CALL_TASK",
        errorMessage = "TOOL_CALL task input must be a JSON tool_call payload.",
        metadata = mapOf("taskType" to task.type.name),
      )

    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolCallEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = 0,
        call = toolCall,
        emittedAtEpochMs = clock(),
      ),
    )
    val activeSkillCapsule = resolveActiveSkillCapsule(
      activeSkillName = config.promptResumeState?.activeSkillName
        ?: config.inheritedActiveSkillCapsule?.name,
      activationSource = config.promptResumeState?.activeSkillActivationSource
        ?: config.inheritedActiveSkillCapsule?.activationSource,
      pinned = config.promptResumeState?.activeSkillPinned
        ?: config.inheritedActiveSkillCapsule?.pinned,
    )
    val toolResult = gateDisabledSessionToolCall(
      call = toolCall,
      memoryToolsEnabled = config.sessionContext.memoryToolsEnabled,
    ) ?: maybeExecuteSkillCall(
      task = task,
      turn = 0,
      call = toolCall,
      transcript = config.sessionContext.conversation,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      cursor = null,
    ) ?: gateActiveSkillToolCall(
      call = toolCall,
      activeSkillCapsule = activeSkillCapsule,
    ) ?: maybeExecuteSubAgentCall(
      task = task,
      turn = 0,
      call = toolCall,
      transcript = config.sessionContext.conversation,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      cursor = null,
    ) ?: toolDispatcher.dispatch(task = task, call = toolCall, hooks = hooks)
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolResultEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = 0,
        call = toolCall,
        result = toolResult,
        emittedAtEpochMs = clock(),
      ),
    )
    emitMemoryRetrievalEvent(
      task = task,
      turn = 0,
      call = toolCall,
      result = toolResult,
    )
    return toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      json = config.json,
    ).also { result ->
      if (toolResult.isApprovalRequiredDenial()) {
        hooks.requestSuspend(
          SuspensionRequest(
            reasonCode = result.errorCode ?: "APPROVAL_REQUIRED",
            detail = result.errorMessage,
          ),
        )
      }
    }
  }

  private fun executeDirectSkillCall(task: AgentTask): ExecutionResult {
    val startedAt = clock()
    val toolCall = AgentToolCall(
      toolName = "skill_read",
      arguments = JsonObject(
        mapOf(
          "name" to JsonPrimitive(task.skillName ?: task.input.trim()),
        ),
      ),
    )
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolCallEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = 0,
        call = toolCall,
        emittedAtEpochMs = clock(),
      ),
    )
    val toolResult = toolDispatcher.dispatch(
      task = task,
      call = toolCall,
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> Unit },
      ),
    )
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolResultEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = 0,
        call = toolCall,
        result = toolResult,
        emittedAtEpochMs = clock(),
      ),
    )
    return toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      json = config.json,
    )
  }

  fun ensureDetachedSubAgentRecoveryExecution(
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    agentId: String,
    parentRunId: String,
  ): SubAgentHandleState? {
    val activeSkillCapsule = resolveActiveSkillCapsule(
      activeSkillName = config.promptResumeState?.activeSkillName
        ?: config.inheritedActiveSkillCapsule?.name,
      activationSource = config.promptResumeState?.activeSkillActivationSource
        ?: config.inheritedActiveSkillCapsule?.activationSource,
      pinned = config.promptResumeState?.activeSkillPinned
        ?: config.inheritedActiveSkillCapsule?.pinned,
    )
    val key = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    )
    val handle = config.subAgentExecutionCoordinator.currentHandle(key)
      ?: config.seededSubAgentHandles.firstOrNull { seeded ->
        seeded.agentId == agentId && seeded.parentRunId == parentRunId
      }
      ?: return null
    val restoredHandle = restoredSubAgentHandle(handle)
    return ensureDetachedSubAgentHandleBackgroundExecution(
      task = task,
      turn = 0,
      transcript = config.sessionContext.conversation,
      parentSessionContext = config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = restoredHandle,
      handles = linkedMapOf(restoredHandle.agentId to restoredHandle),
    )
  }

  private fun parseModelActionBatch(rawOutput: String): ParsedModelActionBatch {
    val trimmed = rawOutput.trim()
    val jsonSequence = extractJsonSequence(trimmed)
    if (jsonSequence == null) {
      return ParsedModelActionBatch.ProtocolError(
        reason = "Model output must be a single JSON action object.",
      )
    }
    return runCatching {
      val actions = mutableListOf<AgentModelAction>()
      var ignoredNonActionContent = false
      jsonSequence.envelopes.forEach { envelope ->
        if (envelope.leadingText.isNotBlank()) {
          ignoredNonActionContent = true
        }
        val parsedObjectActions = parseActionObject(envelope.json)
        actions += parsedObjectActions.actions
        ignoredNonActionContent = ignoredNonActionContent || parsedObjectActions.ignoredNonActionContent
      }
      if (jsonSequence.trailingText.isNotBlank()) {
        ignoredNonActionContent = true
      }
      if (actions.isEmpty()) {
        error("Model output did not contain any executable actions.")
      }
      ParsedModelActionBatch.Actions(
        actions = actions,
        ignoredNonActionContent = ignoredNonActionContent,
      )
    }.getOrElse {
      ParsedModelActionBatch.ProtocolError(
        reason = it.message ?: "Model output could not be parsed as a JSON action.",
      )
    }
  }

  private fun parseGatewayResultActionBatch(
    gatewayResult: LiteLlmGatewayResult,
    diagnostics: PromptRunDiagnostics,
  ): ParsedGatewayActionBatch? {
    gatewayResult.completion?.let { completion ->
      completion.reasoningText
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reasoningText ->
          diagnostics.providerReasoningObserved = true
          diagnostics.providerReasoningTurnCount += 1
          diagnostics.providerReasoningChars += reasoningText.length
        }
      parseStructuredCompletion(completion)?.let { parsed ->
        return ParsedGatewayActionBatch(batch = parsed)
      }
      completion.rawText?.takeIf(String::isNotBlank)?.let { rawText ->
        diagnostics.fallbackParserAttempted = true
        val parsed = parseModelActionBatch(rawText)
        diagnostics.fallbackParserSucceeded = parsed is ParsedModelActionBatch.Actions
        return ParsedGatewayActionBatch(
          batch = parsed,
          usedLegacyJsonFallback = parsed is ParsedModelActionBatch.Actions,
        )
      }
    }
    val outputText = gatewayResult.outputText?.takeIf { it.isNotBlank() } ?: return null
    diagnostics.fallbackParserAttempted = true
    val parsed = parseModelActionBatch(outputText)
    diagnostics.fallbackParserSucceeded = parsed is ParsedModelActionBatch.Actions
    return ParsedGatewayActionBatch(
      batch = parsed,
      usedLegacyJsonFallback = parsed is ParsedModelActionBatch.Actions,
    )
  }

  private fun parseStructuredCompletion(
    completion: LiteLlmStructuredCompletion,
  ): ParsedModelActionBatch? {
    if (completion.toolCallErrors.isNotEmpty()) {
      return ParsedModelActionBatch.ProtocolError(
        reason = buildStructuredToolCallRecoveryReason(completion.toolCallErrors),
      )
    }
    duplicateStructuredToolCallErrors(completion.toolCalls)
      .takeIf { duplicateErrors -> duplicateErrors.isNotEmpty() }
      ?.let { duplicateErrors ->
        return ParsedModelActionBatch.ProtocolError(
          reason = buildStructuredToolCallRecoveryReason(duplicateErrors),
        )
      }
    val actions = mutableListOf<AgentModelAction>()
    structuredCompletionCommentaryTexts(completion).forEach { commentaryText ->
      actions += AgentModelAction.Commentary(text = commentaryText)
    }
    completion.toolCalls
      .mapTo(actions) { toolCall ->
        AgentModelAction.ToolCall(call = parseStructuredToolCall(toolCall))
      }
    val finalText = completion.finalText
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val finalAttachments = completion.finalAttachments.map(::toOpenCrayFinalAttachment)
    if (completion.toolCalls.isEmpty() && (finalText != null || finalAttachments.isNotEmpty())) {
      actions += AgentModelAction.Final(
        answer = finalText.orEmpty(),
        responseFormat = if (finalAttachments.isEmpty()) {
          "native_text_final"
        } else {
          "native_structured_final"
        },
        attachments = finalAttachments,
      )
    }
    if (actions.isNotEmpty()) {
      return ParsedModelActionBatch.Actions(
        actions = actions,
        ignoredNonActionContent = !completion.rawText.isNullOrBlank() &&
          actions.any { action -> action !is AgentModelAction.Final },
      )
    }
    return null
  }

  private fun executeGatewayTurnWithRecovery(
    task: AgentTask,
    request: LiteLlmGatewayRequest,
    turn: Int,
    hooks: RuntimeExecutionHooks,
    diagnostics: PromptRunDiagnostics,
  ): GatewayTurnExecution {
    val observedRequest = request.copy(
      streamObserver = combineVisibleTextObservers(
        primary = request.streamObserver,
        secondary = assistantDraftObserver(task),
      ),
    )
    var retryCount = 0
    while (true) {
      if (hooks.isCancellationRequested()) {
        return GatewayTurnExecution.Cancelled
      }
      val gatewayResult = gateway.execute(observedRequest)
      if (hooks.isCancellationRequested()) {
        return GatewayTurnExecution.Cancelled
      }
      val retryDelayMs = recoverableGatewayRetryDelayMs(gatewayResult)
      if (retryDelayMs == null) {
        return GatewayTurnExecution.Completed(result = gatewayResult)
      }
      if (retryCount >= config.maxRecoverableLlmRetries) {
        return GatewayTurnExecution.Completed(
          result = gatewayResult,
          retryBudgetExhausted = true,
        )
      }
      retryCount += 1
      diagnostics.llmRetryCount += 1
      clearAssistantDraft(task)
      emitCommentaryEvent(
        task = task,
        turn = turn,
        text = buildRecoverableRetryCommentaryText(
          gatewayResult = gatewayResult,
          retryCount = retryCount,
          delayMs = retryDelayMs,
        ),
        stage = "llm_retry",
      )
      if (!sleepForRecoverableRetry(delayMs = retryDelayMs, hooks = hooks)) {
        return GatewayTurnExecution.Cancelled
      }
    }
  }

  private fun recoverableGatewayRetryDelayMs(gatewayResult: LiteLlmGatewayResult): Long? = when {
    gatewayResult.status == LiteLlmGatewayStatus.TIMEOUT &&
      !isTerminalProviderTimeout(gatewayResult) ->
      config.recoverableLlmRetryDelayMs

    gatewayResult.status == LiteLlmGatewayStatus.RATE_LIMITED ->
      maxOf(
        config.recoverableLlmRetryDelayMs,
        gatewayResult.metadata["retryAfterMs"]?.toLongOrNull() ?: 0L,
      )

    gatewayResult.status == LiteLlmGatewayStatus.FAILED &&
      gatewayResult.errorCode.isTransientGatewayFailureCode() ->
      config.recoverableLlmRetryDelayMs

    else -> null
  }

  private fun isTerminalProviderTimeout(gatewayResult: LiteLlmGatewayResult): Boolean =
    gatewayResult.metadata["statusCode"] in TERMINAL_PROVIDER_TIMEOUT_STATUS_CODES

  private fun recoverableGatewayFailureObservation(
    gatewayResult: LiteLlmGatewayResult,
    nativeToolCallingEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    diagnostics: PromptRunDiagnostics,
  ): String? {
    if (!isProviderEmptyResponseFailure(gatewayResult)) {
      return null
    }
    if (diagnostics.emptyResponseRecoveryCount >= config.maxRecoverableLlmRetries) {
      return null
    }
    diagnostics.emptyResponseRecoveryCount += 1
    return buildEmptyResponseRecoveryObservation(
      nativeToolCallingEnabled = nativeToolCallingEnabled,
      legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
      detail = gatewayResult.errorMessage ?: "Provider returned an empty completion payload.",
      reasoningText = gatewayResult.completion?.reasoningText,
      rawOutput = gatewayResult.completion?.rawText ?: gatewayResult.outputText,
    )
  }

  private fun responsesContinuationRecoveryReason(
    request: LiteLlmGatewayRequest,
    gatewayResult: LiteLlmGatewayResult,
  ): String? {
    if (!isResponsesProtocol()) {
      return null
    }
    if (request.previousResponseId.isNullOrBlank()) {
      return null
    }
    if (gatewayResult.status != LiteLlmGatewayStatus.FAILED) {
      return null
    }
    val diagnosticText = buildString {
      append(gatewayResult.errorCode.orEmpty())
      append('\n')
      append(gatewayResult.errorMessage.orEmpty())
      gatewayResult.metadata.forEach { (key, value) ->
        append('\n')
        append(key)
        append('=')
        append(value)
      }
    }.lowercase()
    val missingToolCallForOutput =
      diagnosticText.contains("no tool call found") &&
        diagnosticText.contains("call_id") &&
        (
          diagnosticText.contains("function call output") ||
            diagnosticText.contains("function_call_output")
        )
    val previousResponseMismatch =
      diagnosticText.contains("previous_response_id") &&
        (
          diagnosticText.contains("not found") ||
            diagnosticText.contains("invalid") ||
            diagnosticText.contains("mismatch")
        )
    return when {
      missingToolCallForOutput -> "missing_tool_call_for_output"
      previousResponseMismatch -> "previous_response_mismatch"
      else -> null
    }
  }

  private fun recoverableSuccessfulEmptyResponseObservation(
    gatewayResult: LiteLlmGatewayResult,
    nativeToolCallingEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    diagnostics: PromptRunDiagnostics,
  ): String? {
    if (!isSuccessfulEmptyResponse(gatewayResult)) {
      return null
    }
    if (diagnostics.emptyResponseRecoveryCount >= config.maxRecoverableLlmRetries) {
      return null
    }
    diagnostics.emptyResponseRecoveryCount += 1
    return buildEmptyResponseRecoveryObservation(
      nativeToolCallingEnabled = nativeToolCallingEnabled,
      legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
      detail = "The previous response contained no usable tool call, commentary update, or final answer.",
      reasoningText = gatewayResult.completion?.reasoningText,
      rawOutput = null,
    )
  }

  private fun isProviderEmptyResponseFailure(
    gatewayResult: LiteLlmGatewayResult,
  ): Boolean = gatewayResult.status == LiteLlmGatewayStatus.FAILED &&
    gatewayResult.errorCode == "PROVIDER_EMPTY_RESPONSE"

  private fun isSuccessfulEmptyResponse(
    gatewayResult: LiteLlmGatewayResult,
  ): Boolean = gatewayResult.status == LiteLlmGatewayStatus.SUCCESS &&
    !hasVisibleOutput(gatewayResult)

  private fun hasVisibleOutput(
    gatewayResult: LiteLlmGatewayResult,
  ): Boolean = !gatewayResult.outputText.isNullOrBlank() ||
    !gatewayResult.completion?.rawText.isNullOrBlank() ||
    !gatewayResult.completion?.finalText.isNullOrBlank() ||
    gatewayResult.completion?.finalAttachments?.isNotEmpty() == true ||
    gatewayResult.completion?.let(::structuredCompletionCommentaryTexts).orEmpty().isNotEmpty() ||
    gatewayResult.completion?.toolCalls?.isNotEmpty() == true

  private fun sleepForRecoverableRetry(
    delayMs: Long,
    hooks: RuntimeExecutionHooks,
  ): Boolean {
    var remainingDelayMs = delayMs.coerceAtLeast(0L)
    while (remainingDelayMs > 0) {
      if (hooks.isCancellationRequested()) {
        return false
      }
      val sleepChunkMs = minOf(remainingDelayMs, RECOVERABLE_LLM_RETRY_SLEEP_CHUNK_MS)
      val sleepOutcome = runCatching { config.sleep(sleepChunkMs) }
      if (sleepOutcome.isFailure) {
        sleepOutcome.exceptionOrNull()
          ?.takeIf { error -> error is InterruptedException }
          ?.let { Thread.currentThread().interrupt() }
        return false
      }
      remainingDelayMs -= sleepChunkMs
    }
    return !hooks.isCancellationRequested()
  }

  private fun buildRecoverableRetryCommentaryText(
    gatewayResult: LiteLlmGatewayResult,
    retryCount: Int,
    delayMs: Long,
  ): String {
    val reason = gatewayResult.errorCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: gatewayResult.status.name
    val detail = gatewayResult.errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "LLM request failed."
    return buildString {
      append("LLM request failed with ")
      append(reason)
      append(". Retrying in ")
      append(delayMs / 1_000L)
      append("s (retry ")
      append(retryCount)
      append("/")
      append(config.maxRecoverableLlmRetries)
      append("). ")
      append(detail)
    }
  }

  private fun buildRecoverableRetryExhaustedMessage(
    gatewayResult: LiteLlmGatewayResult,
    retryCount: Int,
  ): String {
    val reason = gatewayResult.errorCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: gatewayResult.status.name
    val detail = gatewayResult.errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "LLM request failed."
    return buildString {
      append("Recoverable LLM retries were exhausted after ")
      append(retryCount)
      append(" retries. The run is paused and can resume from the current checkpoint. ")
      append("Latest failure: ")
      append(reason)
      append(". ")
      append(detail)
    }
  }

  private fun buildStructuredToolCallRecoveryReason(
    toolCallErrors: List<String>,
  ): String = buildString {
    append("Native tool call payload could not be parsed. ")
    append("Return the same next step again with a valid tool call payload. ")
    append("Diagnostics: ")
    append(
      toolCallErrors
        .take(MAX_STRUCTURED_TOOL_CALL_ERROR_COUNT)
        .joinToString(separator = " | "),
    )
  }

  private fun duplicateStructuredToolCallErrors(
    toolCalls: List<LiteLlmStructuredToolCall>,
  ): List<String> {
    if (toolCalls.isEmpty()) {
      return emptyList()
    }
    val seenToolCallIds = linkedSetOf<String>()
    val errors = mutableListOf<String>()
    toolCalls.forEachIndexed { index, toolCall ->
      val toolCallId = toolCall.id?.trim()?.takeIf(String::isNotBlank) ?: return@forEachIndexed
      if (!seenToolCallIds.add(toolCallId)) {
        errors += "tool_calls[$index].id duplicates tool call id '$toolCallId'."
      }
    }
    return errors
  }

  private fun assistantDraftObserver(
    task: AgentTask,
  ): LiteLlmVisibleTextObserver = object : LiteLlmVisibleTextObserver {
    private var hasVisibleDraft: Boolean = false
    private var lastVisibleDraftText: String? = null

    override fun onVisibleTextSnapshot(text: String) {
      val normalized = visibleAssistantDraftText(text) ?: return
      if (normalized == lastVisibleDraftText) {
        return
      }
      hasVisibleDraft = true
      lastVisibleDraftText = normalized
      eventSink.onAssistantDraftUpdated(
        task = task,
        text = normalized,
        emittedAtEpochMs = clock(),
      )
    }

    override fun onVisibleTextReset() {
      if (!hasVisibleDraft) {
        return
      }
      clearAssistantDraft(task)
      hasVisibleDraft = false
      lastVisibleDraftText = null
    }
  }

  private fun visibleAssistantDraftText(rawText: String): String? {
    val normalized = rawText.trim().takeIf(String::isNotBlank) ?: return null
    val startsLikeJson = normalized.startsWith('{') || normalized.startsWith('[')
    val lowercase = normalized.lowercase()
    val looksLikeStructuredProtocol =
      startsLikeJson && (
        "\"type\"" in lowercase ||
          "\"decision\"" in lowercase ||
          "\"actions\"" in lowercase ||
          "\"tool_name\"" in lowercase ||
          "\"tool_calls\"" in lowercase ||
          "\"arguments\"" in lowercase
        )
    val looksLikeInternalSignal =
      startsLikeJson && (
        "\"is_task_bearing_request\"" in lowercase ||
          "\"user_affect\"" in lowercase ||
          "\"user_invites_playfulness\"" in lowercase ||
          "\"user_requests_relational_support\"" in lowercase ||
          "\"clarification_needed\"" in lowercase
        )
    if (!startsLikeJson) {
      return normalized
    }
    if (normalized == "{" || normalized == "[") {
      return null
    }
    extractStructuredAssistantDraftText(normalized)?.let { return it }
    if (looksLikeStructuredProtocol || looksLikeInternalSignal) {
      return null
    }
    return normalized
  }

  private fun structuredCompletionCommentaryTexts(
    completion: LiteLlmStructuredCompletion,
  ): List<String> = completion.commentaryTexts
    .map(String::trim)
    .filter(String::isNotBlank)
    .ifEmpty {
      completion.commentaryText
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(::listOf)
        ?: emptyList()
    }

  private fun extractStructuredAssistantDraftText(rawText: String): String? {
    val lowercase = rawText.lowercase()
    val hasExplicitTypeField = "\"type\"" in lowercase || "\"decision\"" in lowercase
    if ("\"actions\"" in lowercase) {
      extractStructuredActionsDraftText(rawText)?.let { return it }
    }
    if (containsStructuredAssistantExecutionSignal(lowercase)) {
      return null
    }
    val actionType = structuredAssistantDraftActionType(rawText)
    return when (actionType) {
      "final",
      "answer",
      -> firstNonBlankAssistantDraftField(
        partialJsonStringFieldValue(rawText, "answer"),
        partialJsonStringFieldValue(rawText, "text"),
        partialJsonStringFieldValue(rawText, "message"),
        partialJsonStringFieldValue(rawText, "summary"),
      )?.trim()?.takeIf(String::isNotBlank)

      null,
      "",
      -> if (hasExplicitTypeField) {
        partialJsonStringFieldValue(rawText, "answer")
          ?.trim()
          ?.takeIf(String::isNotBlank)
      } else {
        null
      }

      else -> null
    }
  }

  private fun extractStructuredActionsDraftText(rawText: String): String? {
    val actions = partialJsonObjectFieldArrayElements(rawText, "actions")
    if (actions.isEmpty()) {
      return null
    }
    val hasExecutionAction = actions.any(::structuredAssistantActionSuppressesFinalDraft)
    return actions
      .mapNotNull { rawAction ->
        val visibleText = extractStructuredAssistantDraftTextFromAction(rawAction) ?: return@mapNotNull null
        if (hasExecutionAction && isStructuredAssistantFinalAction(rawAction)) {
          return@mapNotNull null
        }
        visibleText
      }
      .lastOrNull()
  }

  private fun extractStructuredAssistantDraftTextFromAction(rawAction: String): String? {
    val actionType = structuredAssistantDraftActionType(rawAction)
    return when (actionType) {
      "final",
      "answer",
      -> firstNonBlankAssistantDraftField(
        partialJsonStringFieldValue(rawAction, "answer"),
        partialJsonStringFieldValue(rawAction, "text"),
        partialJsonStringFieldValue(rawAction, "message"),
        partialJsonStringFieldValue(rawAction, "summary"),
      )?.trim()?.takeIf(String::isNotBlank)

      else -> null
    }
  }

  private fun structuredAssistantDraftActionType(rawText: String): String? =
    firstNonBlankAssistantDraftField(
      partialJsonStringFieldValue(rawText, "type")?.trim()?.lowercase()?.takeIf(String::isNotBlank),
      partialJsonStringFieldValue(rawText, "decision")?.trim()?.lowercase()?.takeIf(String::isNotBlank),
    )

  private fun isStructuredAssistantFinalAction(rawText: String): Boolean =
    structuredAssistantDraftActionType(rawText) in setOf("final", "answer")

  private fun structuredAssistantActionSuppressesFinalDraft(rawAction: String): Boolean {
    val lowercase = rawAction.lowercase()
    if (containsStructuredAssistantExecutionSignal(lowercase)) {
      return true
    }
    return when (structuredAssistantDraftActionType(rawAction)) {
      null,
      "",
      "final",
      "answer",
      "progress",
      "commentary",
      "status",
      -> false

      else -> true
    }
  }

  private fun containsStructuredAssistantExecutionSignal(lowercase: String): Boolean =
    containsStructuredAssistantToolSignal(lowercase) ||
      "\"is_task_bearing_request\"" in lowercase ||
      "\"user_affect\"" in lowercase ||
      "\"user_invites_playfulness\"" in lowercase ||
      "\"user_requests_relational_support\"" in lowercase ||
      "\"clarification_needed\"" in lowercase

  private fun containsStructuredAssistantToolSignal(lowercase: String): Boolean =
    "\"tool_name\"" in lowercase ||
      "\"tool_calls\"" in lowercase ||
      "\"arguments\"" in lowercase

  private fun firstNonBlankAssistantDraftField(vararg values: String?): String? =
    values.firstOrNull { value -> !value.isNullOrBlank() }

  private fun partialJsonObjectFieldArrayElements(
    rawText: String,
    fieldName: String,
  ): List<String> {
    val fieldPattern = "\"$fieldName\""
    var searchFrom = 0
    var keyIndex = -1
    while (searchFrom < rawText.length) {
      val candidateIndex = rawText.indexOf(fieldPattern, searchFrom)
      if (candidateIndex < 0) {
        return emptyList()
      }
      if (isTopLevelPartialJsonObjectKey(rawText = rawText, keyIndex = candidateIndex)) {
        keyIndex = candidateIndex
        break
      }
      searchFrom = candidateIndex + fieldPattern.length
    }
    var index = keyIndex + fieldPattern.length
    while (index < rawText.length && rawText[index].isWhitespace()) {
      index += 1
    }
    if (index >= rawText.length || rawText[index] != ':') {
      return emptyList()
    }
    index += 1
    while (index < rawText.length && rawText[index].isWhitespace()) {
      index += 1
    }
    if (index >= rawText.length || rawText[index] != '[') {
      return emptyList()
    }
    index += 1
    val elements = mutableListOf<String>()
    var objectStart = -1
    var objectDepth = 0
    var inString = false
    var escaped = false
    while (index < rawText.length) {
      val character = rawText[index]
      if (inString) {
        if (escaped) {
          escaped = false
        } else {
          when (character) {
            '\\' -> escaped = true
            '"' -> inString = false
          }
        }
        index += 1
        continue
      }
      when (character) {
        '"' -> inString = true
        '{' -> {
          if (objectDepth == 0) {
            objectStart = index
          }
          objectDepth += 1
        }
        '}' -> {
          if (objectDepth > 0) {
            objectDepth -= 1
            if (objectDepth == 0 && objectStart >= 0) {
              elements += rawText.substring(objectStart, index + 1)
              objectStart = -1
            }
          }
        }
        ']' -> {
          if (objectDepth == 0) {
            return elements
          }
        }
      }
      index += 1
    }
    if (objectStart >= 0) {
      elements += rawText.substring(objectStart)
    }
    return elements
  }

  private fun isTopLevelPartialJsonObjectKey(
    rawText: String,
    keyIndex: Int,
  ): Boolean {
    var objectDepth = 0
    var arrayDepth = 0
    var inString = false
    var escaped = false
    for (index in 0 until keyIndex) {
      val character = rawText[index]
      if (inString) {
        if (escaped) {
          escaped = false
        } else {
          when (character) {
            '\\' -> escaped = true
            '"' -> inString = false
          }
        }
        continue
      }
      when (character) {
        '"' -> inString = true
        '{' -> objectDepth += 1
        '}' -> if (objectDepth > 0) {
          objectDepth -= 1
        }
        '[' -> arrayDepth += 1
        ']' -> if (arrayDepth > 0) {
          arrayDepth -= 1
        }
      }
    }
    return objectDepth == 1 && arrayDepth == 0 && !inString
  }

  private fun partialJsonStringFieldValue(
    rawText: String,
    fieldName: String,
  ): String? {
    val fieldPattern = "\"$fieldName\""
    var searchStart = 0
    while (true) {
      val keyIndex = rawText.indexOf(fieldPattern, startIndex = searchStart)
      if (keyIndex < 0) {
        return null
      }
      var index = keyIndex + fieldPattern.length
      while (index < rawText.length && rawText[index].isWhitespace()) {
        index += 1
      }
      if (index >= rawText.length || rawText[index] != ':') {
        searchStart = keyIndex + fieldPattern.length
        continue
      }
      index += 1
      while (index < rawText.length && rawText[index].isWhitespace()) {
        index += 1
      }
      if (index >= rawText.length || rawText[index] != '"') {
        return null
      }
      index += 1
      val builder = StringBuilder()
      var escaped = false
      while (index < rawText.length) {
        val character = rawText[index]
        if (escaped) {
          builder.append(
            when (character) {
              'n' -> '\n'
              'r' -> '\r'
              't' -> '\t'
              '\\',
              '"',
              '/',
              -> character
              else -> character
            },
          )
          escaped = false
          index += 1
          continue
        }
        when (character) {
          '\\' -> {
            escaped = true
            index += 1
          }

          '"' -> return builder.toString()
          else -> {
            builder.append(character)
            index += 1
          }
        }
      }
      return builder.toString()
    }
  }

  private fun clearAssistantDraft(task: AgentTask) {
    eventSink.onAssistantDraftCleared(
      task = task,
      emittedAtEpochMs = clock(),
    )
  }

  private fun combineVisibleTextObservers(
    primary: LiteLlmVisibleTextObserver,
    secondary: LiteLlmVisibleTextObserver,
  ): LiteLlmVisibleTextObserver = object : LiteLlmVisibleTextObserver {
    override fun onVisibleTextSnapshot(text: String) {
      primary.onVisibleTextSnapshot(text)
      secondary.onVisibleTextSnapshot(text)
    }

    override fun onVisibleTextReset() {
      primary.onVisibleTextReset()
      secondary.onVisibleTextReset()
    }
  }

  private fun nativeToolCallingEnabledForTurn(
    visibleToolDefinitions: List<AgentToolDefinition>,
    builtinTools: List<LiteLlmBuiltinToolDefinition> = emptyList(),
  ): Boolean {
    if (visibleToolDefinitions.isEmpty() && builtinTools.isEmpty()) {
      return false
    }
    config.llmMetadata["nativeToolCallingAvailable"]
      ?.trim()
      ?.lowercase()
      ?.let { rawValue ->
        if (rawValue == "true" || rawValue == "false") {
          return rawValue.toBoolean()
        }
      }
    return when (config.llmMetadata["protocol"]?.trim()?.lowercase()) {
      null,
      "",
      "openai",
      "openai_responses",
      "anthropic",
      -> true
      else -> false
    }
  }

  private fun providerBuiltinToolsForTurn(
    visibleToolDefinitions: List<AgentToolDefinition>,
  ): List<LiteLlmBuiltinToolDefinition> {
    if (!nativeProviderWebSearchEnabled()) {
      return emptyList()
    }
    val hostWebSearchVisible = visibleToolDefinitions.any { definition ->
      definition.name.equals("WebSearch", ignoreCase = true)
    }
    if (!hostWebSearchVisible) {
      return emptyList()
    }
    return listOf(
      LiteLlmBuiltinToolDefinition(
        type = LiteLlmBuiltinToolType.WEB_SEARCH,
        includeSources = true,
      ),
    )
  }

  private fun functionToolDefinitionsForTurn(
    visibleToolDefinitions: List<AgentToolDefinition>,
    builtinTools: List<LiteLlmBuiltinToolDefinition>,
  ): List<AgentToolDefinition> {
    val hidesHostWebSearch = builtinTools.any { tool ->
      tool.type == LiteLlmBuiltinToolType.WEB_SEARCH
    } && !dualExposeWebSearchEnabled()
    if (!hidesHostWebSearch) {
      return visibleToolDefinitions
    }
    return visibleToolDefinitions.filterNot { definition ->
      definition.name.equals("WebSearch", ignoreCase = true)
    }
  }

  private fun legacyJsonFallbackEnabledForTurn(
    nativeToolCallingEnabled: Boolean,
    priorFallbackEnabled: Boolean,
  ): Boolean {
    if (priorFallbackEnabled) {
      return true
    }
    // Keep native Responses continuation reachable until the route actually
    // falls back to raw JSON parsing. Attachment-capable final answers still
    // keep a JSON final-action escape hatch through prompt guidance.
    return !nativeToolCallingEnabled
  }

  private fun requestedToolChoice(): LiteLlmToolChoice? {
    val mode = when (config.llmMetadata["toolChoiceMode"]?.trim()?.lowercase()) {
      "auto" -> LiteLlmToolChoiceMode.AUTO
      "none" -> LiteLlmToolChoiceMode.NONE
      "required", "any" -> LiteLlmToolChoiceMode.REQUIRED
      "tool", "function" -> LiteLlmToolChoiceMode.TOOL
      else -> null
    } ?: return null
    val toolName = config.llmMetadata["toolChoiceName"]?.trim()?.takeIf(String::isNotBlank)
    return runCatching {
      LiteLlmToolChoice(
        mode = mode,
        toolName = toolName,
      )
    }.getOrNull()
  }

  private fun requestedParallelToolCalls(
    nativeToolCallingEnabled: Boolean,
  ): Boolean? {
    if (!nativeToolCallingEnabled) {
      return null
    }
    return config.llmMetadata["parallelToolCalls"]
      ?.trim()
      ?.lowercase()
      ?.let { rawValue ->
        when (rawValue) {
          "true" -> true
          "false" -> false
          else -> null
        }
      }
  }

  private fun requestedPreviousResponseId(
    cursor: PromptTurnCursor,
  ): String? = config.llmMetadata["previousResponseId"]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.takeUnless { cursor.responsesFullReplayRequired }

  private fun responseApiPreferred(): Boolean {
    val rawValue = config.llmMetadata["responseApiPreferred"]
      ?.trim()
      ?.lowercase()
    return rawValue == "true"
  }

  private fun isResponsesProtocol(): Boolean =
    config.llmMetadata["protocol"]?.trim()?.lowercase() == RESPONSES_PROTOCOL

  private fun nativeProviderWebSearchEnabled(): Boolean {
    config.llmMetadata["nativeWebSearchEnabled"]
      ?.trim()
      ?.lowercase()
      ?.let { rawValue ->
        if (rawValue == "true" || rawValue == "false") {
          return rawValue.toBoolean()
        }
      }
    return isResponsesProtocol() && officialOpenAiRouteForHostMetadata()
  }

  private fun officialOpenAiRouteForHostMetadata(): Boolean {
    val hostBaseUrl = config.llmMetadata[HOST_METADATA_BASE_URL]
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (hostBaseUrl != null) {
      val host = runCatching {
        URI(hostBaseUrl).host.orEmpty().lowercase()
      }.getOrDefault("")
      return host == "api.openai.com" || host.endsWith(".openai.com")
    }
    return config.llmMetadata[HOST_PROVIDER_ID_METADATA_KEY]
      ?.trim()
      .equals("openai", ignoreCase = true)
  }

  private fun webSearchEnabledForTurn(): Boolean =
    config.llmMetadata["webSearchEnabled"]
      ?.trim()
      ?.lowercase()
      ?.let { rawValue ->
        when (rawValue) {
          "true" -> true
          "false" -> false
          else -> null
        }
      } ?: true

  private fun onDeviceLiteModeEnabledForTurn(): Boolean =
    config.llmMetadata["onDeviceLiteModeEnabled"]
      ?.trim()
      ?.lowercase()
      ?.let { rawValue ->
        when (rawValue) {
          "true" -> true
          "false" -> false
          else -> null
        }
      } ?: false

  private fun dualExposeWebSearchEnabled(): Boolean =
    config.llmMetadata["dualExposeWebSearch"]
      ?.trim()
      ?.lowercase() == "true"

  private fun responsesContinuationSupported(): Boolean =
    config.llmMetadata["responsesContinuationSupported"]
      ?.trim()
      ?.lowercase() == "true"

  private fun promptCacheToolPoolFingerprint(
    requestedNativeToolDefinitions: List<LiteLlmToolDefinition>,
    activeBuiltinTools: List<LiteLlmBuiltinToolDefinition>,
  ): String = promptCacheFingerprint(
    buildString {
      appendLine("native_tools")
      requestedNativeToolDefinitions.forEach { definition ->
        appendLine(definition.name)
      }
      appendLine("builtin_tools")
      activeBuiltinTools.forEach { tool ->
        append(tool.type.name)
        append('|')
        append(tool.includeSources.toString())
        appendLine()
      }
    },
  )

  private fun promptCacheToolSchemaFingerprint(
    requestedNativeToolDefinitions: List<LiteLlmToolDefinition>,
    activeBuiltinTools: List<LiteLlmBuiltinToolDefinition>,
  ): String = promptCacheFingerprint(
    buildString {
      appendLine("native_tool_schemas")
      requestedNativeToolDefinitions.forEach { definition ->
        append(definition.name)
        append('|')
        append(definition.description)
        append('|')
        append(definition.strict?.toString() ?: "null")
        append('|')
        append(definition.inputSchema.toString())
        appendLine()
      }
      appendLine("builtin_tool_schemas")
      activeBuiltinTools.forEach { tool ->
        append(tool.type.name)
        append('|')
        append(tool.includeSources.toString())
        appendLine()
      }
    },
  )

  private fun promptCacheRequestSettingsFingerprint(
    nativeToolCallingEnabled: Boolean,
    requestedParallelToolCalls: Boolean?,
    requestedToolChoice: LiteLlmToolChoice?,
    responseApiPreferred: Boolean,
  ): String = promptCacheFingerprint(
    buildString {
      appendLine("protocol=${config.llmMetadata["protocol"].orEmpty()}")
      appendLine("responses_continuation_supported=${responsesContinuationSupported()}")
      appendLine("response_api_preferred=$responseApiPreferred")
      appendLine("native_tool_calling_enabled=$nativeToolCallingEnabled")
      appendLine("parallel_tool_calls=${requestedParallelToolCalls?.toString() ?: "null"}")
      appendLine("tool_choice_mode=${requestedToolChoice?.mode?.name ?: "null"}")
      appendLine("tool_choice_name=${requestedToolChoice?.toolName ?: "null"}")
      appendLine("native_provider_web_search_enabled=${nativeProviderWebSearchEnabled()}")
      appendLine("dual_expose_web_search=${dualExposeWebSearchEnabled()}")
    },
  )

  private fun promptCacheFingerprint(
    source: String,
  ): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)
  }

  private fun nonResponsesContextCacheShapeMetadata(
    stableAnchor: String,
    frontContextZones: FrontContextZones,
  ): Map<String, String> {
    if (isResponsesProtocol()) {
      return emptyMap()
    }
    val zoneMask = buildList {
      if (frontContextZones.durableContextPrompt.trim().isNotBlank()) {
        add("durable")
      }
      if (frontContextZones.dynamicContextPrompt.trim().isNotBlank()) {
        add("dynamic")
      }
    }.joinToString(separator = ",")
      .ifBlank { "none" }
    return buildMap {
      put(LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION, NON_RESPONSES_CONTEXT_CACHE_CONTRACT_VERSION)
      put(LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH, promptCacheFingerprint(stableAnchor))
      put(
        LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH,
        promptCacheFingerprint(frontContextZones.durableContextPrompt.trim()),
      )
      put(
        LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH,
        promptCacheFingerprint(frontContextZones.dynamicContextPrompt.trim()),
      )
      put(LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK, zoneMask)
      put(
        LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT,
        frontContextZones.promptsInTransportOrder.size.toString(),
      )
    }
  }

  private fun buildResponsesGatewayMessagePlan(
    cursor: PromptTurnCursor,
    frontContextPrompts: List<String>,
    stableAnchor: String,
    toolPoolFingerprint: String,
    toolSchemaFingerprint: String,
    requestSettingsFingerprint: String,
    transcript: List<RuntimeConversationMessage>,
  ): GatewayMessagePlan {
    val decision = responsesContinuationDecision(
      cursor = cursor,
      requestedShape = ResponsesContinuationShape(
        stableAnchor = stableAnchor,
        frontContextZones = normalizeFrontContextZones(frontContextPrompts),
        toolPoolFingerprint = toolPoolFingerprint,
        toolSchemaFingerprint = toolSchemaFingerprint,
        requestSettingsFingerprint = requestSettingsFingerprint,
      ),
    )
    val previousResponseId = decision.previousResponseId
    return if (previousResponseId != null) {
      GatewayMessagePlan(
        messages = cursor.responsesPendingMessages.toList(),
        mode = LocalContinuationMode.RESPONSES_NATIVE,
        reason = decision.reason,
        previousResponseId = previousResponseId,
      )
    } else {
      fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = transcript,
        reason = decision.reason,
      )
    }
  }

  private fun responsesContinuationDecision(
    cursor: PromptTurnCursor,
    requestedShape: ResponsesContinuationShape,
  ): ResponsesContinuationDecision {
    if (!responsesContinuationSupported()) {
      return ResponsesContinuationDecision(reason = "responses_continuation_disabled")
    }
    if (cursor.responsesFullReplayRequired) {
      return ResponsesContinuationDecision(reason = "responses_restored_replay_required")
    }
    if (cursor.legacyJsonFallbackEnabled) {
      return ResponsesContinuationDecision(reason = "responses_legacy_json_fallback_enabled")
    }
    if (!hasResponsesLineage(cursor)) {
      return ResponsesContinuationDecision(reason = "responses_lineage_unavailable")
    }
    val previousResponseId = cursor.responsesPreviousResponseId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return ResponsesContinuationDecision(reason = "responses_lineage_unavailable")
    val pendingMessages = cursor.responsesPendingMessages
    if (pendingMessages.isEmpty()) {
      return ResponsesContinuationDecision(reason = "responses_no_pending_messages")
    }
    if (hasDuplicateResponsesPendingToolResultIds(pendingMessages)) {
      return ResponsesContinuationDecision(
        reason = "responses_pending_tool_result_duplicate_call_id",
      )
    }
    pendingMessages.firstOrNull { message -> !isSafeResponsesContinuationPendingMessage(message) }
      ?.let { message ->
        return ResponsesContinuationDecision(
          reason = responsesContinuationFallbackReason(message),
        )
      }
    responsesContinuationShapeMismatchReason(
      storedShape = cursor.responsesContinuationShape,
      requestedShape = requestedShape,
    )?.let { reason ->
      return ResponsesContinuationDecision(reason = reason)
    }
    return ResponsesContinuationDecision(
      previousResponseId = previousResponseId,
      reason = "responses_previous_response_id",
    )
  }

  private fun responsesContinuationShapeMismatchReason(
    storedShape: ResponsesContinuationShape?,
    requestedShape: ResponsesContinuationShape,
  ): String? {
    val shape = storedShape ?: return "responses_shape_unavailable"
    if (shape.toolPoolFingerprint != requestedShape.toolPoolFingerprint) {
      return "tool_pool_changed"
    }
    if (shape.toolSchemaFingerprint != requestedShape.toolSchemaFingerprint) {
      return "tool_schema_changed"
    }
    if (shape.requestSettingsFingerprint != requestedShape.requestSettingsFingerprint) {
      return "user_setting_changed"
    }
    if (shape.stableAnchor != requestedShape.stableAnchor) {
      return "anchor_changed"
    }
    if (shape.frontContextZones.durableContextPrompt != requestedShape.frontContextZones.durableContextPrompt) {
      return "durable_context_changed"
    }
    if (shape.frontContextZones.dynamicContextPrompt != requestedShape.frontContextZones.dynamicContextPrompt) {
      return "dynamic_context_changed"
    }
    return null
  }

  private fun hasResponsesLineage(cursor: PromptTurnCursor): Boolean =
    cursor.responsesLineageTrusted &&
      !cursor.responsesProviderLineageId.isNullOrBlank() &&
      !cursor.responsesPreviousResponseId.isNullOrBlank()

  private fun isSafeResponsesContinuationPendingMessage(
    message: LiteLlmGatewayMessage,
  ): Boolean {
    return when (message.role) {
      LiteLlmGatewayMessageRole.TOOL -> {
        val toolResult = message.toolResult ?: return false
        !toolResult.toolCallId.isNullOrBlank() &&
          !toolResult.toolName.isNullOrBlank() &&
          toolResult.content.isNotBlank() &&
          !toolResultPublishesAttachmentArtifacts(toolResult)
      }

      else -> false
    }
  }

  private fun toolResultPublishesAttachmentArtifacts(
    toolResult: LiteLlmGatewayToolResult,
  ): Boolean = OpenCrayAttachmentArtifacts.decodeMetadata(
    json = config.json,
    metadata = toolResult.metadata,
  ).isNotEmpty()

  private fun responsesContinuationFallbackReason(
    message: LiteLlmGatewayMessage,
  ): String = when (message.role) {
    LiteLlmGatewayMessageRole.USER -> "responses_pending_user_message"
    LiteLlmGatewayMessageRole.SYSTEM -> "responses_pending_system_message"
    LiteLlmGatewayMessageRole.ASSISTANT -> "responses_pending_assistant_message"
    LiteLlmGatewayMessageRole.TOOL -> {
      val toolResult = message.toolResult
      when {
        toolResult == null -> "responses_pending_tool_result_missing_payload"
        toolResult.toolCallId.isNullOrBlank() -> "responses_pending_tool_result_missing_call_id"
        toolResult.toolName.isNullOrBlank() -> "responses_pending_tool_result_missing_name"
        toolResult.content.isBlank() -> "responses_pending_tool_result_blank_content"
        toolResultPublishesAttachmentArtifacts(toolResult) ->
          "responses_pending_tool_result_attachment_artifact"
        else -> "responses_pending_tool_result_invalid"
      }
    }
  }

  private fun hasDuplicateResponsesPendingToolResultIds(
    pendingMessages: List<LiteLlmGatewayMessage>,
  ): Boolean {
    val seenToolCallIds = linkedSetOf<String>()
    pendingMessages.forEach { message ->
      val toolCallId = message.toolResult?.toolCallId?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
      if (!seenToolCallIds.add(toolCallId)) {
        return true
      }
    }
    return false
  }

  private fun buildNonResponsesGatewayMessagePlan(
    cursor: PromptTurnCursor,
    transcript: List<RuntimeConversationMessage>,
    turnAwareConversation: List<RuntimeConversationMessage>,
    frontContextPrompts: List<String>,
    stableAnchor: String,
    toolPoolFingerprint: String,
    toolSchemaFingerprint: String,
    requestSettingsFingerprint: String,
  ): GatewayMessagePlan {
    val envelope = cursor.localContinuationEnvelope ?: return fullGatewayMessageRebuild(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      transcript = turnAwareConversation,
      reason = "no_envelope",
    )
    val normalizedFrontContextZones = normalizeFrontContextZones(frontContextPrompts)
    if (envelope.toolPoolFingerprint != null && envelope.toolPoolFingerprint != toolPoolFingerprint) {
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "tool_pool_changed",
      )
    }
    if (envelope.toolSchemaFingerprint != null && envelope.toolSchemaFingerprint != toolSchemaFingerprint) {
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "tool_schema_changed",
      )
    }
    if (
      envelope.requestSettingsFingerprint != null &&
      envelope.requestSettingsFingerprint != requestSettingsFingerprint
    ) {
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "user_setting_changed",
      )
    }
    if (envelope.stableAnchor != stableAnchor) {
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "anchor_changed",
      )
    }
    if (envelope.frontContextZones.durableContextPrompt != normalizedFrontContextZones.durableContextPrompt) {
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "durable_context_changed",
      )
    }
    val transcriptDelta = transcriptDeltaSince(
      frontier = envelope.transcriptFrontier,
      transcript = transcript,
    ) ?: run {
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "transcript_mismatch",
      )
    }
    val promptOnlyDelta = if (turnAwareConversation.size > transcript.size) {
      turnAwareConversation.subList(transcript.size, turnAwareConversation.size)
    } else {
      emptyList()
    }
    if (envelope.frontContextZones.dynamicContextPrompt != normalizedFrontContextZones.dynamicContextPrompt) {
      val patchedGatewayMessages = patchDynamicFrontContextGatewayMessages(
        gatewayMessages = envelope.gatewayMessages,
        existingFrontContextZones = envelope.frontContextZones,
        requestedFrontContextZones = normalizedFrontContextZones,
      )
      if (patchedGatewayMessages != null) {
        return GatewayMessagePlan(
          messages = patchedGatewayMessages + buildLocalContinuationDeltaMessages(
            transcriptDelta = transcriptDelta,
            promptOnlyDelta = promptOnlyDelta,
            replayToolResultProjections = cursor.replayToolResultProjections,
          ),
          mode = LocalContinuationMode.LOCAL_FRONT_PATCH,
          reason = "dynamic_context_changed",
        )
      }
      invalidateLocalContinuation(cursor)
      return fullGatewayMessageRebuild(
        cursor = cursor,
        frontContextPrompts = frontContextPrompts,
        transcript = turnAwareConversation,
        reason = "dynamic_context_changed",
      )
    }
    return GatewayMessagePlan(
      messages = envelope.gatewayMessages + buildLocalContinuationDeltaMessages(
        transcriptDelta = transcriptDelta,
        promptOnlyDelta = promptOnlyDelta,
        replayToolResultProjections = cursor.replayToolResultProjections,
      ),
      mode = LocalContinuationMode.LOCAL_DELTA,
      reason = if (transcriptDelta.isEmpty()) "steady_turn" else "transcript_delta",
    )
  }

  private fun fullGatewayMessageRebuild(
    cursor: PromptTurnCursor,
    frontContextPrompts: List<String>,
    transcript: List<RuntimeConversationMessage>,
    reason: String,
  ): GatewayMessagePlan = GatewayMessagePlan(
    messages = buildGatewayMessages(
      frontContextPrompts = frontContextPrompts,
      transcript = transcript,
      replayToolResultProjections = cursor.replayToolResultProjections,
    ),
    mode = LocalContinuationMode.FULL_REBUILD,
      reason = reason,
  )

  private fun buildLocalContinuationDeltaMessages(
    transcriptDelta: List<RuntimeConversationMessage>,
    promptOnlyDelta: List<RuntimeConversationMessage>,
    replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>?,
  ): List<LiteLlmGatewayMessage> = buildGatewayMessages(
    frontContextPrompts = emptyList(),
    transcript = transcriptDelta + promptOnlyDelta,
    replayToolResultProjections = replayToolResultProjections,
  )

  private fun patchDynamicFrontContextGatewayMessages(
    gatewayMessages: List<LiteLlmGatewayMessage>,
    existingFrontContextZones: FrontContextZones,
    requestedFrontContextZones: FrontContextZones,
  ): List<LiteLlmGatewayMessage>? {
    val existingFrontContextPrompts = existingFrontContextZones.promptsInTransportOrder
    if (!gatewayMessagesStartWithFrontContextPrompts(gatewayMessages, existingFrontContextPrompts)) {
      return null
    }
    return requestedFrontContextZones.promptsInTransportOrder.map(::frontContextGatewayMessage) +
      gatewayMessages.drop(existingFrontContextPrompts.size)
  }

  private fun gatewayMessagesStartWithFrontContextPrompts(
    gatewayMessages: List<LiteLlmGatewayMessage>,
    frontContextPrompts: List<String>,
  ): Boolean {
    if (frontContextPrompts.size > gatewayMessages.size) {
      return false
    }
    return frontContextPrompts.indices.all { index ->
      val message = gatewayMessages[index]
      message.role == LiteLlmGatewayMessageRole.USER &&
        message.attachments.isEmpty() &&
        message.toolCalls.isEmpty() &&
        message.toolResult == null &&
        message.assistantPhase == null &&
        message.content?.trim().orEmpty() == frontContextPrompts[index]
    }
  }

  private fun frontContextGatewayMessage(prompt: String): LiteLlmGatewayMessage =
    LiteLlmGatewayMessage(
      role = LiteLlmGatewayMessageRole.USER,
      content = prompt,
    )

  private fun contextCacheBreakReason(
    cursor: PromptTurnCursor,
    plan: GatewayMessagePlan,
  ): String? = deriveContextCacheBreakReason(
    localContinuationReason = plan.reason,
    hasHistoricalResponsesContinuation = config.promptResumeState != null ||
      cursor.turn > 0 ||
      cursor.toolCallCount > 0 ||
      !cursor.responsesPreviousResponseId.isNullOrBlank() ||
      !cursor.responsesProviderLineageId.isNullOrBlank() ||
      cursor.responsesPendingMessages.isNotEmpty(),
  )

  private fun updateResponsesContinuationState(
    cursor: PromptTurnCursor,
    gatewayResult: LiteLlmGatewayResult,
    continuationShape: ResponsesContinuationShape,
  ) {
    if (!isResponsesProtocol()) {
      invalidateResponsesLineage(cursor)
      return
    }
    val providerResponseId = gatewayResult.providerResponseId?.trim()?.takeIf(String::isNotBlank)
    if (providerResponseId == null) {
      invalidateResponsesLineage(cursor)
      return
    }
    val providerLineageId = gatewayResult.providerLineageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: cursor.responsesProviderLineageId
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: providerResponseId
    cursor.responsesPreviousResponseId = providerResponseId
    cursor.responsesProviderLineageId = providerLineageId
    cursor.responsesLineageTrusted = providerLineageId.isNotBlank()
    cursor.responsesFullReplayRequired = false
    cursor.responsesContinuationShape = continuationShape
    cursor.responsesPendingMessages.clear()
  }

  private fun invalidateResponsesLineage(cursor: PromptTurnCursor) {
    cursor.responsesPreviousResponseId = null
    cursor.responsesProviderLineageId = null
    cursor.responsesLineageTrusted = false
    cursor.responsesContinuationShape = null
    cursor.responsesPendingMessages.clear()
  }

  private fun refreshLocalContinuationEnvelope(
    cursor: PromptTurnCursor,
    frontContextPrompts: List<String>,
    stableAnchor: String,
    gatewayMessagesEnabled: Boolean,
    toolPoolFingerprint: String,
    toolSchemaFingerprint: String,
    requestSettingsFingerprint: String,
  ) {
    cursor.localContinuationEnvelope = buildLocalContinuationEnvelope(
      cursor = cursor,
      frontContextPrompts = frontContextPrompts,
      stableAnchor = stableAnchor,
      gatewayMessagesEnabled = gatewayMessagesEnabled,
      toolPoolFingerprint = toolPoolFingerprint,
      toolSchemaFingerprint = toolSchemaFingerprint,
      requestSettingsFingerprint = requestSettingsFingerprint,
    )
  }

  private fun invalidateLocalContinuation(cursor: PromptTurnCursor) {
    cursor.localContinuationEnvelope = null
  }

  private fun buildLocalContinuationEnvelope(
    cursor: PromptTurnCursor,
    frontContextPrompts: List<String>,
    stableAnchor: String,
    gatewayMessagesEnabled: Boolean,
    toolPoolFingerprint: String,
    toolSchemaFingerprint: String,
    requestSettingsFingerprint: String,
  ): LocalContinuationEnvelope? {
    if (isResponsesProtocol() || !gatewayMessagesEnabled) {
      return null
    }
    val normalizedFrontContextZones = normalizeFrontContextZones(frontContextPrompts)
    return LocalContinuationEnvelope(
      stableAnchor = stableAnchor,
      frontContextZones = normalizedFrontContextZones,
      toolPoolFingerprint = toolPoolFingerprint,
      toolSchemaFingerprint = toolSchemaFingerprint,
      requestSettingsFingerprint = requestSettingsFingerprint,
      transcriptFrontier = cursor.transcript.toList(),
      gatewayMessages = buildGatewayMessages(
        frontContextPrompts = normalizedFrontContextZones.promptsInTransportOrder,
        transcript = cursor.transcript,
        replayToolResultProjections = cursor.replayToolResultProjections,
      ),
    )
  }

  private fun transcriptDeltaSince(
    frontier: List<RuntimeConversationMessage>,
    transcript: List<RuntimeConversationMessage>,
  ): List<RuntimeConversationMessage>? {
    if (frontier.size > transcript.size) {
      return null
    }
    if (!transcript.subList(0, frontier.size).equals(frontier)) {
      return null
    }
    return transcript.drop(frontier.size)
  }

  private fun normalizeFrontContextZones(
    prompts: List<String>,
  ): FrontContextZones = FrontContextZones.fromTransportPrompts(prompts)

  private fun stableLocalContinuationAnchor(assembledPrompt: com.opencray.runtime.context.AssembledPrompt): String =
    assembledPrompt.layers
      .filter { layer ->
        layer.transportGroup == PromptLayerTransportGroup.SYSTEM_PREFIX
      }
      .joinToString(separator = "\n\n") { layer ->
        buildString {
          append("[")
          append(layer.kind.name)
          append(":")
          append(layer.name)
          appendLine("]")
          append(stableLocalContinuationLayerContent(layer))
        }
      }

  private fun stableLocalContinuationLayerContent(
    layer: com.opencray.runtime.context.PromptLayer,
  ): String = layer.content.trim()

  private fun parseStructuredToolCall(
    toolCall: LiteLlmStructuredToolCall,
  ): AgentToolCall = AgentToolCall(
    id = toolCall.id,
    toolName = toolCall.toolName,
    arguments = toolCall.arguments,
    reason = toolCall.reason,
  )

  private fun parseActionObject(rawJson: String): ParsedActionObject {
    val parsed = config.json.parseToJsonElement(rawJson) as? JsonObject
      ?: error("Model output must decode to a JSON object.")
    val nestedActions = (parsed["actions"] as? kotlinx.serialization.json.JsonArray)
      ?.map { element ->
        val nestedObject = element as? JsonObject ?: error("Each action inside 'actions' must be a JSON object.")
        parseActionObject(config.json.encodeToString(JsonObject.serializer(), nestedObject))
      }
      .orEmpty()
    if (nestedActions.isNotEmpty()) {
      return ParsedActionObject(
        actions = nestedActions.flatMap { action -> action.actions },
        ignoredNonActionContent = nestedActions.any(ParsedActionObject::ignoredNonActionContent),
      )
    }

    val type = parsed.primitiveContent("type")?.trim()?.lowercase()
      ?: parsed.primitiveContent("decision")?.trim()?.lowercase()
    val hasToolCallShape = parsed.primitiveContent("tool_name")?.isNotBlank() == true
    val hasFinalAnswerShape = parsed.primitiveContent("answer")?.isNotBlank() == true
    val hasFinalAttachmentShape = (parsed["attachments"] as? JsonArray)?.isNotEmpty() == true
    val toolCalls = (parsed["tool_calls"] as? JsonArray)
      ?.map { element ->
        val toolCallObject = element as? JsonObject ?: error("Each entry inside 'tool_calls' must be a JSON object.")
        AgentModelAction.ToolCall(call = parseToolCall(toolCallObject))
      }
      .orEmpty()
    if (toolCalls.isNotEmpty()) {
      return ParsedActionObject(
        actions = toolCalls,
        ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
          parsed.primitiveContent("message")?.isNotBlank() == true,
      )
    }

    return when {
      type in setOf("tool_call", "tool") || hasToolCallShape -> ParsedActionObject(
        actions = listOf(AgentModelAction.ToolCall(call = parseToolCall(parsed))),
        ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
          parsed.primitiveContent("message")?.isNotBlank() == true,
      )

      type in setOf("progress", "commentary", "status") -> ParsedActionObject(
        actions = listOf(
          AgentModelAction.Commentary(
            text = parsed.primitiveContent("text")
              ?.trim()
              .orEmpty()
              .ifBlank {
                parsed.primitiveContent("summary")
                  ?.trim()
                  .orEmpty()
                  .ifBlank {
                    parsed.primitiveContent("message")
                      ?.trim()
                      .orEmpty()
                      .ifBlank {
                        error("commentary action must contain a non-blank 'text'.")
                      }
                  }
              },
            stage = parsed.primitiveContent("stage")?.trim()?.takeIf(String::isNotBlank),
          ),
        ),
        ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
          parsed.primitiveContent("tool_name")?.isNotBlank() == true,
      )

      type in setOf("final", "answer") ||
        (type == null && (hasFinalAnswerShape || hasFinalAttachmentShape)) -> ParsedActionObject(
        actions = listOf(parseFinalAction(parsed)),
      )

      type == null -> error("Model output must contain 'type' or 'decision'.")

      else -> error("Unsupported model action type '$type'.")
    }
  }

  private fun parseToolCall(parsed: JsonObject): AgentToolCall = AgentToolCall(
    id = parsed.primitiveContent("id")?.trim()?.takeIf(String::isNotBlank)
      ?: parsed.primitiveContent("tool_call_id")?.trim()?.takeIf(String::isNotBlank),
    toolName = parsed.primitiveContent("tool_name")?.trim().orEmpty().ifBlank {
      error("tool_call action must contain a non-blank 'tool_name'.")
    },
    arguments = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
    reason = parsed.primitiveContent("reason")?.trim()?.takeIf(String::isNotBlank)
      ?: parsed.primitiveContent("justification")?.trim()?.takeIf(String::isNotBlank),
  )

  private fun parseFinalAction(parsed: JsonObject): AgentModelAction.Final {
    val attachments = parseFinalAttachments(parsed["attachments"])
    val answer = parsed.primitiveContent("answer")?.trim().orEmpty()
    require(answer.isNotBlank() || attachments.isNotEmpty()) {
      "Final action must contain a non-blank 'answer' or a non-empty 'attachments' array."
    }
    return AgentModelAction.Final(
      answer = answer,
      responseFormat = "json_final",
      attachments = attachments,
    )
  }

  private fun parseFinalAttachments(
    rawAttachments: kotlinx.serialization.json.JsonElement?,
  ): List<OpenCrayFinalAttachment> = (rawAttachments as? JsonArray)
    ?.map { element ->
      val attachmentObject = element as? JsonObject
        ?: error("Each entry inside 'attachments' must be a JSON object.")
      OpenCrayFinalAttachment(
        kind = attachmentObject.primitiveContent("kind")?.trim()?.takeIf(String::isNotBlank),
        relativePath = attachmentObject.primitiveContent("relative_path")
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachmentObject.primitiveContent("relativePath")
            ?.trim()
            ?.takeIf(String::isNotBlank),
        path = attachmentObject.primitiveContent("path")?.trim()?.takeIf(String::isNotBlank),
        artifactId = attachmentObject.primitiveContent("artifact_id")
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachmentObject.primitiveContent("artifactId")
            ?.trim()
            ?.takeIf(String::isNotBlank),
        chatAttachmentId = attachmentObject.primitiveContent("chat_attachment_id")
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachmentObject.primitiveContent("chatAttachmentId")
            ?.trim()
            ?.takeIf(String::isNotBlank),
        displayName = attachmentObject.primitiveContent("display_name")
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachmentObject.primitiveContent("displayName")
            ?.trim()
            ?.takeIf(String::isNotBlank),
        mimeType = attachmentObject.primitiveContent("mime_type")
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachmentObject.primitiveContent("mimeType")
            ?.trim()
            ?.takeIf(String::isNotBlank),
        durationMs = attachmentObject.longContent("duration_ms")
          ?: attachmentObject.longContent("durationMs"),
        waveformBars = attachmentObject.intArrayContent("waveform_bars")
          ?: attachmentObject.intArrayContent("waveformBars")
          ?: emptyList(),
        transcriptText = attachmentObject.primitiveContent("transcript_text")
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachmentObject.primitiveContent("transcriptText")
            ?.trim()
            ?.takeIf(String::isNotBlank),
      )
    }
    .orEmpty()

  private fun toOpenCrayFinalAttachment(
    attachment: LiteLlmStructuredFinalAttachment,
  ): OpenCrayFinalAttachment = OpenCrayFinalAttachment(
    kind = attachment.kind?.trim()?.takeIf(String::isNotBlank),
    relativePath = attachment.relativePath?.trim()?.takeIf(String::isNotBlank),
    path = attachment.path?.trim()?.takeIf(String::isNotBlank),
    artifactId = attachment.artifactId?.trim()?.takeIf(String::isNotBlank),
    chatAttachmentId = attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank),
    displayName = attachment.displayName?.trim()?.takeIf(String::isNotBlank),
    mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
    durationMs = attachment.durationMs,
    waveformBars = attachment.waveformBars,
    transcriptText = attachment.transcriptText?.trim()?.takeIf(String::isNotBlank),
  )

  private fun finalAttachmentMetadata(
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, String> = attachments
    .takeIf(List<OpenCrayFinalAttachment>::isNotEmpty)
    ?.let { resolved ->
      mapOf(
        OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to config.json.encodeToString(
          ListSerializer(OpenCrayFinalAttachment.serializer()),
          resolved,
        ),
      )
    }
    ?: emptyMap()

  private fun durableCompactionEntryTraceSummary(
    entries: List<DurableCompactionEntryTrace>,
  ): String? = entries
    .takeIf(List<DurableCompactionEntryTrace>::isNotEmpty)
    ?.joinToString(separator = ";") { entry ->
      listOf(
        entry.compactedMessageCount,
        entry.omittedUserMessageCount,
        entry.omittedAssistantMessageCount,
        entry.omittedToolMessageCount,
        entry.omittedSystemMessageCount,
        entry.compactedAtEpochMs ?: 0L,
      ).joinToString(separator = "|")
    }

  private fun buildResultMetadata(
    gatewayResult: LiteLlmGatewayResult?,
    turn: Int,
    toolCallCount: Int,
    responseFormat: String,
    contextReport: ContextAssemblyReport? = null,
    diagnostics: PromptRunDiagnostics? = null,
  ): Map<String, String> = buildMap {
    put("turnCount", (turn + 1).toString())
    put("toolCallCount", toolCallCount.toString())
    put("responseFormat", responseFormat)
    contextReport?.let { report ->
      put("contextLayerNames", report.layers.joinToString(separator = ",") { layer -> layer.name })
      put("contextSourceMessageCount", report.sourceTranscriptMessageCount.toString())
      put("contextWindowMessageCount", report.windowedTranscriptMessageCount.toString())
      put("contextMessageCount", report.windowedTranscriptMessageCount.toString())
      put("contextOmittedMessageCount", report.omittedTranscriptMessageCount.toString())
      put("contextTruncatedMessageCount", report.truncatedTranscriptMessageCount.toString())
      put("contextPrunedMessageCount", report.prunedTranscriptMessageCount.toString())
      put("contextRewrittenMessageCount", report.rewrittenTranscriptMessageCount.toString())
      put("contextPruningSummaryIncluded", report.pruningSummaryIncluded.toString())
      put("contextRecentObservationCount", report.recentToolObservationCount.toString())
      put("contextRecentObservationLayerIncluded", report.recentToolObservationLayerIncluded.toString())
      put("contextWorkingStateIncluded", report.workingStateTrace.included.toString())
      put("contextWorkingStateObjectivePresent", report.workingStateTrace.objectivePresent.toString())
      put("contextWorkingStateFindingCount", report.workingStateTrace.findingCount.toString())
      put("contextWorkingStateRecentActionCount", report.workingStateTrace.recentActionCount.toString())
      put("contextWorkingStateDecisionCount", report.workingStateTrace.decisionCount.toString())
      put("contextWorkingStateBlockerCount", report.workingStateTrace.blockerCount.toString())
      put("contextWorkingStateNextActionCount", report.workingStateTrace.nextActionCount.toString())
      put(
        "contextWorkingStateSynthesizedFromResumeContext",
        report.workingStateTrace.synthesizedFromResumeContext.toString(),
      )
      put("contextWorkingStateSynthesizedFromTodos", report.workingStateTrace.synthesizedFromTodoSnapshot.toString())
      putContextBudgetMetadata(report.budgetReport)
      putContextToolProtocolMetadata(this, report.toolProtocolTrace)
      put("contextMatchedMemoryCount", report.matchedMemoryRecordCount.toString())
      put("contextInjectedMemoryCount", report.injectedMemoryRecordCount.toString())
      put("contextOmittedMemoryCount", report.omittedMemoryRecordCount.toString())
      report.memoryRecallTrace.queryTerms
        .takeIf { terms -> terms.isNotEmpty() }
        ?.let { terms -> put("contextMemoryQueryTerms", terms.joinToString(separator = ",")) }
      report.memoryRecallTrace.selected
        .takeIf { selected -> selected.isNotEmpty() }
        ?.let { selected ->
          put(
            "contextMemorySelectedSummary",
            selected.joinToString(separator = ";") { trace ->
              buildString {
                append(trace.id)
                append("@")
                append(trace.score)
                if (trace.matchedTerms.isNotEmpty()) {
                  append("[")
                  append(trace.matchedTerms.joinToString(separator = "|"))
                  append("]")
                }
              }
            },
          )
        }
      report.memoryRecallTrace.omitted
        .takeIf { omitted -> omitted.isNotEmpty() }
        ?.let { omitted ->
          put(
            "contextMemoryOmittedSummary",
            omitted.joinToString(separator = ";") { trace ->
              "${trace.id}:${trace.omissionReason.name.lowercase()}"
            },
          )
        }
      report.memoryRecallTrace.filteredCounts
        .takeIf { counts -> counts.isNotEmpty() }
        ?.let { counts ->
          put(
            "contextMemoryFilteredCounts",
            counts.entries.joinToString(separator = ",") { (reason, count) ->
              "${reason.name.lowercase()}:$count"
            },
          )
        }
      if (!report.stickyMemoryTrace.isEmpty) {
        put("contextStickyMemoryInjectedRecordCount", report.stickyMemoryTrace.injectedRecordCount.toString())
        put("contextStickyMemoryOmittedRecordCount", report.stickyMemoryTrace.omittedRecordCount.toString())
        report.stickyMemoryTrace.selectedRecordIds
          .takeIf { recordIds -> recordIds.isNotEmpty() }
          ?.let { recordIds ->
            put("contextStickyMemoryRecordIds", recordIds.joinToString(separator = ","))
          }
      }
      if (!report.memoryFlushTrace.isEmpty) {
        report.memoryFlushTrace.outcome?.let { outcome ->
          put("contextMemoryFlushOutcome", outcome.name.lowercase())
        }
        report.memoryFlushTrace.triggerStage
          .takeIf(String::isNotBlank)
          ?.let { triggerStage -> put("contextMemoryFlushTriggerStage", triggerStage) }
        report.memoryFlushTrace.maintenanceTask
          .takeIf(String::isNotBlank)
          ?.let { maintenanceTask -> put("contextMemoryFlushMaintenanceTask", maintenanceTask) }
        put("contextMemoryFlushContextWindowTokens", report.memoryFlushTrace.contextWindowTokens.toString())
        put("contextMemoryFlushAutoCompactTokenLimit", report.memoryFlushTrace.autoCompactTokenLimit.toString())
        put("contextMemoryFlushEstimatedReplayTokens", report.memoryFlushTrace.estimatedReplayTokens.toString())
        put("contextMemoryFlushTokenThresholdTriggered", report.memoryFlushTrace.tokenThresholdTriggered.toString())
        put("contextMemoryFlushOmittedMessageCount", report.memoryFlushTrace.omittedMessageCount.toString())
        put("contextMemoryFlushOmittedCharCount", report.memoryFlushTrace.omittedCharCount.toString())
        report.memoryFlushTrace.signature?.let { signature ->
          put("contextMemoryFlushSignature", signature)
        }
        put("contextMemoryFlushCandidateCount", report.memoryFlushTrace.candidateCount.toString())
        put("contextMemoryFlushWrittenRecordCount", report.memoryFlushTrace.writtenRecordCount.toString())
        report.memoryFlushTrace.writtenKinds
          .takeIf { writtenKinds -> writtenKinds.isNotEmpty() }
          ?.let { writtenKinds ->
            put("contextMemoryFlushWrittenKinds", writtenKinds.joinToString(separator = ","))
          }
        report.memoryFlushTrace.writtenRecordIds
          .takeIf { writtenRecordIds -> writtenRecordIds.isNotEmpty() }
          ?.let { writtenRecordIds ->
            put("contextMemoryFlushWrittenRecordIds", writtenRecordIds.joinToString(separator = ","))
          }
        report.memoryFlushTrace.candidateRecordIds
          .takeIf { candidateRecordIds -> candidateRecordIds.isNotEmpty() }
          ?.let { candidateRecordIds ->
            put("contextMemoryFlushCandidateRecordIds", candidateRecordIds.joinToString(separator = ","))
          }
      }
      if (!report.durableCompactionTrace.isEmpty) {
        put(
          "contextDurableCompactionCompactedThisRun",
          report.durableCompactionTrace.compactedThisRun.toString(),
        )
        report.durableCompactionTrace.triggerStage
          .takeIf(String::isNotBlank)
          ?.let { triggerStage -> put("contextDurableCompactionTriggerStage", triggerStage) }
        report.durableCompactionTrace.maintenanceTask
          .takeIf(String::isNotBlank)
          ?.let { maintenanceTask -> put("contextDurableCompactionMaintenanceTask", maintenanceTask) }
        put(
          "contextDurableCompactionContextWindowTokens",
          report.durableCompactionTrace.contextWindowTokens.toString(),
        )
        put(
          "contextDurableCompactionAutoCompactTokenLimit",
          report.durableCompactionTrace.autoCompactTokenLimit.toString(),
        )
        put(
          "contextDurableCompactionEstimatedReplayTokens",
          report.durableCompactionTrace.estimatedReplayTokens.toString(),
        )
        put(
          "contextDurableCompactionTokenThresholdTriggered",
          report.durableCompactionTrace.tokenThresholdTriggered.toString(),
        )
        put(
          "contextDurableCompactionSourceTranscriptMessageCount",
          report.durableCompactionTrace.sourceTranscriptMessageCount.toString(),
        )
        put(
          "contextDurableCompactionRetainedTranscriptMessageCount",
          report.durableCompactionTrace.retainedTranscriptMessageCount.toString(),
        )
        put(
          "contextDurableCompactionLatestMessageCount",
          report.durableCompactionTrace.latestCompactedMessageCount.toString(),
        )
        put(
          "contextDurableCompactionIncludedSummaryCount",
          report.durableCompactionTrace.includedSummaryCount.toString(),
        )
        put(
          "contextDurableCompactionOmittedSummaryCount",
          report.durableCompactionTrace.omittedSummaryCount.toString(),
        )
        put(
          "contextDurableCompactionTotalCompactedMessageCount",
          report.durableCompactionTrace.totalCompactedMessageCount.toString(),
        )
        report.durableCompactionTrace.latestCompactedAtEpochMs
          ?.let { latestCompactedAtEpochMs ->
            put("contextDurableCompactionLatestAtEpochMs", latestCompactedAtEpochMs.toString())
          }
        durableCompactionEntryTraceSummary(report.durableCompactionTrace.entryTraces)
          ?.let { entryTraceSummary ->
            put("contextDurableCompactionEntryTraceSummary", entryTraceSummary)
          }
        report.durableCompactionTrace.remoteCompactionMetadata.forEach { (key, value) ->
          if (key.isNotBlank() && value.isNotBlank()) {
            put(key, value)
          }
        }
      }
      if (!report.liveContextTrace.isEmpty) {
        report.liveContextTrace.mode?.let { mode ->
          put("contextLiveMode", mode)
        }
        report.liveContextTrace.soulEnabled?.let { soulEnabled ->
          put("contextLiveSoulEnabled", soulEnabled.toString())
        }
        report.liveContextTrace.memoryRecallEnabled?.let { memoryRecallEnabled ->
          put("contextLiveMemoryRecallEnabled", memoryRecallEnabled.toString())
        }
        report.liveContextTrace.replaySource
          ?.takeIf(String::isNotBlank)
          ?.let { replaySource -> put("contextLiveReplaySource", replaySource) }
        report.liveContextTrace.replayMessageCount?.let { replayMessageCount ->
          put("contextLiveReplayMessageCount", replayMessageCount.toString())
        }
        report.liveContextTrace.canonicalSource
          ?.takeIf(String::isNotBlank)
          ?.let { canonicalSource -> put("contextLiveCanonicalSource", canonicalSource) }
        report.liveContextTrace.canonicalMessageCount?.let { canonicalMessageCount ->
          put("contextLiveCanonicalMessageCount", canonicalMessageCount.toString())
        }
        report.liveContextTrace.canonicalHistoryPreserved?.let { canonicalHistoryPreserved ->
          put("contextLiveCanonicalHistoryPreserved", canonicalHistoryPreserved.toString())
        }
        report.liveContextTrace.inheritanceSource
          ?.takeIf(String::isNotBlank)
          ?.let { inheritanceSource -> put("contextLiveInheritanceSource", inheritanceSource) }
        report.liveContextTrace.parentMode
          ?.takeIf(String::isNotBlank)
          ?.let { parentMode -> put("contextLiveParentMode", parentMode) }
        report.liveContextTrace.parentReplayMessageCount?.let { parentReplayMessageCount ->
          put("contextLiveParentReplayMessageCount", parentReplayMessageCount.toString())
        }
        report.liveContextTrace.budgetPreset
          ?.takeIf(String::isNotBlank)
          ?.let { budgetPreset -> put("contextLiveBudgetPreset", budgetPreset) }
      }
      if (!report.bootstrapTrace.isEmpty) {
        put("contextBootstrapMode", report.bootstrapTrace.mode)
        put("contextBootstrapVisibleFileCount", report.bootstrapTrace.visibleFileCount.toString())
        put("contextBootstrapInjectedFileCount", report.bootstrapTrace.injectedFileCount.toString())
        put("contextBootstrapOmittedFileCount", report.bootstrapTrace.omittedFileCount.toString())
        put("contextBootstrapTruncatedFileCount", report.bootstrapTrace.truncatedFileCount.toString())
        report.bootstrapTrace.files
          .takeIf { files -> files.isNotEmpty() }
          ?.let { files ->
            put(
              "contextBootstrapFileSummary",
              files.joinToString(separator = ";") { file ->
                buildString {
                  append(file.name)
                  append("@")
                  append(file.relativePath)
                  append("[")
                  append(file.sourceCharCount)
                  append("|")
                  append(file.injectedCharCount)
                  append("|")
                  append(file.truncated)
                  append("]")
                }
              },
            )
          }
      }
      put("contextVisibleSkillCount", report.visibleSkillCount.toString())
      put("contextInjectedSkillCount", report.injectedSkillCount.toString())
      put("contextOmittedSkillCount", report.omittedSkillCount.toString())
      put("contextImplicitSkillCount", report.skillInventoryTrace.implicitSkillCount.toString())
      put("contextInvalidSkillCount", report.invalidSkillCount.toString())
      report.skillInventoryTrace.visible
        .takeIf { visible -> visible.isNotEmpty() }
        ?.let { visible ->
          put(
            "contextVisibleSkillSummary",
            visible.joinToString(separator = ";") { trace ->
              buildString {
                append(trace.name)
                append("@")
                append(trace.relativePath)
                append("[")
                append(trace.invocationControl)
                append("|")
                append(trace.userInvocable)
                append("|")
                append(trace.executionContext)
                append("]")
              }
            },
          )
        }
      report.skillInventoryTrace.omittedTraceSkillCount
        .takeIf { omittedTraceCount -> omittedTraceCount > 0 }
        ?.let { omittedTraceCount ->
          put("contextVisibleSkillTraceOmittedCount", omittedTraceCount.toString())
        }
      report.activeSkillTrace.name?.let { put("contextActiveSkillName", it) }
      report.activeSkillTrace.relativePath?.let { put("contextActiveSkillRelativePath", it) }
      report.activeSkillTrace.invocationControl?.let { put("contextActiveSkillInvocationControl", it) }
      report.activeSkillTrace.executionContext?.let { put("contextActiveSkillExecutionContext", it) }
      report.activeSkillTrace.activationSource?.let { put("contextActiveSkillActivationSource", it) }
      put("contextActiveSkillPinned", report.activeSkillTrace.pinned.toString())
      put("contextActiveSkillToolRestrictionEnabled", report.activeSkillTrace.toolRestrictionEnabled.toString())
      put("contextActiveSkillTruncated", report.activeSkillTrace.truncated.toString())
      report.activeSkillTrace.allowedToolKeys
        .takeIf { allowedToolKeys -> allowedToolKeys.isNotEmpty() }
        ?.let { allowedToolKeys ->
          put("contextActiveSkillAllowedTools", allowedToolKeys.joinToString(separator = ","))
        }
    }
    gatewayResult?.selectedRoute?.routeId?.let { put("selectedRouteId", it) }
    gatewayResult?.selectedRoute?.providerId?.let { put("selectedProviderId", it) }
    gatewayResult?.selectedRoute?.model?.let { put("selectedModel", it) }
    gatewayResult?.completionMode?.name?.let { put("completionMode", it) }
    gatewayResult?.status?.name?.let { put("llmStatus", it) }
    gatewayResult?.providerResponseId?.let { providerResponseId ->
      if (providerResponseId.isNotBlank()) {
        put("providerResponseId", providerResponseId)
      }
    }
    gatewayResult?.providerLineageId?.let { providerLineageId ->
      if (providerLineageId.isNotBlank()) {
        put("providerLineageId", providerLineageId)
      }
    }
    gatewayResult?.metadata?.forEach { (key, value) ->
      if (key.isNotBlank() && value.isNotBlank()) {
        put(key, value)
      }
    }
    val gatewayReasoningText = gatewayResult?.completion?.reasoningText
      ?.trim()
      ?.takeIf(String::isNotBlank)
    diagnostics?.let { promptDiagnostics ->
      put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, promptDiagnostics.nativeToolCallRequested.toString())
      put(LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED, promptDiagnostics.parsedToolCallObserved.toString())
      put(LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED, promptDiagnostics.fallbackParserAttempted.toString())
      put(LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED, promptDiagnostics.fallbackParserSucceeded.toString())
      put(LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED, promptDiagnostics.toolCallEventEmitted.toString())
      put(LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED, promptDiagnostics.toolResultEventEmitted.toString())
      put("llmRetryCount", promptDiagnostics.llmRetryCount.toString())
      put("emptyResponseRecoveryCount", promptDiagnostics.emptyResponseRecoveryCount.toString())
      put("responsesContinuationRecoveryCount", promptDiagnostics.responsesContinuationRecoveryCount.toString())
      promptDiagnostics.responsesContinuationRecoveryLastReason
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reason -> put("responsesContinuationRecoveryLastReason", reason) }
      put("localContinuationUsedCount", promptDiagnostics.localContinuationUsedCount.toString())
      put("localContinuationFallbackCount", promptDiagnostics.localContinuationFallbackCount.toString())
      put("localContinuationLastMode", promptDiagnostics.localContinuationLastMode.wireValue)
      promptDiagnostics.localContinuationLastReason
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reason -> put("localContinuationLastReason", reason) }
      promptDiagnostics.contextCacheBreakReason
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reason -> put(LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON, reason) }
      putAll(promptDiagnostics.contextCacheShapeMetadata)
      promptDiagnostics.lastSuccessfulToolName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { toolName ->
          put(LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME, toolName)
        }
      val providerReasoningObserved = promptDiagnostics.providerReasoningObserved || gatewayReasoningText != null
      put(LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED, providerReasoningObserved.toString())
      if (providerReasoningObserved) {
        put(
          LiteLlmMetadataKeys.PROVIDER_REASONING_TURN_COUNT,
          (if (promptDiagnostics.providerReasoningObserved) promptDiagnostics.providerReasoningTurnCount else 1).toString(),
        )
        put(
          LiteLlmMetadataKeys.PROVIDER_REASONING_CHARS,
          (if (promptDiagnostics.providerReasoningObserved) promptDiagnostics.providerReasoningChars else gatewayReasoningText?.length ?: 0).toString(),
        )
      }
    }
  }

  private fun MutableMap<String, String>.putContextBudgetMetadata(
    report: ContextBudgetReport,
  ) {
    val sourceBudgetProfile = config.contextSourceBudgetProfile
    put("contextBudgetApplied", report.applied.toString())
    put("contextBudgetPressureMode", report.pressureMode.name)
    put("contextBudgetContextWindowTokens", report.contextWindowTokens.toString())
    put("contextBudgetReservedOutputTokens", report.reservedOutputTokens.toString())
    put("contextBudgetSafetyMarginTokens", report.safetyMarginTokens.toString())
    put("contextBudgetSelectedPreset", report.selectedPreset)
    put("contextBudgetEffectivePreset", report.effectivePreset)
    put("contextBudgetPresetSource", report.presetSource)
    put("contextBudgetPresetDiverged", report.presetDiverged.toString())
    put("contextBudgetSourcePreset", sourceBudgetProfile.sourcePresetWireValue)
    put(
      "contextBudgetSourceTranscriptMaxMessages",
      sourceBudgetProfile.transcriptWindowConfig.maxMessages.toString(),
    )
    put(
      "contextBudgetSourceInjectedMemoryMaxRecords",
      sourceBudgetProfile.contextManagerConfig.maxInjectedMemoryRecords.toString(),
    )
    put(
      "contextBudgetSourceMemoryRecallMaxRecords",
      sourceBudgetProfile.memoryPolicy.recallBudget.maxRecords.toString(),
    )
    put(
      "contextBudgetSourceBootstrapMaxChars",
      sourceBudgetProfile.bootstrapContextResolverConfig.maxTotalChars.toString(),
    )
    put(
      "contextBudgetSourceSkillInventoryMaxSkills",
      sourceBudgetProfile.skillInventoryPromptLayerConfig.maxSkills.toString(),
    )
    put(
      "contextBudgetSourceActiveSkillMaxChars",
      sourceBudgetProfile.activeSkillPromptLayerConfig.maxBodyChars.toString(),
    )
    put(
      "contextBudgetSourceRecentObservationMaxEntries",
      sourceBudgetProfile.recentToolObservationConfig.maxEntries.toString(),
    )
    put(
      "contextBudgetSourceMemoryFlushMaxToolObservations",
      sourceBudgetProfile.memoryFlushPolicy.maxToolObservations.toString(),
    )
    put("contextBudgetHardInputTokens", report.hardInputBudgetTokens.toString())
    put("contextBudgetTargetInputTokens", report.targetInputBudgetTokens.toString())
    put("contextBudgetEmergencyInputTokens", report.emergencyInputBudgetTokens.toString())
    put("contextBudgetEffectiveInputPercent", report.effectiveInputPercent.toString())
    put("contextBudgetEstimatedInputTokensBefore", report.estimatedInputTokensBefore.toString())
    put("contextBudgetEstimatedInputTokensAfter", report.estimatedInputTokensAfter.toString())
    put("contextBudgetFullLayerCount", report.fullLayerCount.toString())
    put("contextBudgetCompactLayerCount", report.compactLayerCount.toString())
    put("contextBudgetMinimalLayerCount", report.minimalLayerCount.toString())
    put("contextBudgetOmittedLayerCount", report.omittedLayerCount.toString())
    put("contextBudgetReducedLayerCount", report.reducedLayerCount.toString())
    put("contextBudgetUnresolvedOverflow", report.unresolvedOverflow.toString())
    report.omittedLayerNames
      .takeIf { omittedLayers -> omittedLayers.isNotEmpty() }
      ?.let { omittedLayers ->
        put("contextBudgetOmittedLayerNames", omittedLayers.joinToString(separator = ","))
      }
    report.reducedLayerNames
      .takeIf { reducedLayers -> reducedLayers.isNotEmpty() }
      ?.let { reducedLayers ->
        put("contextBudgetReducedLayerNames", reducedLayers.joinToString(separator = ","))
      }
    report.layers
      .takeIf { layers -> layers.isNotEmpty() }
      ?.let { layers ->
        put(
          "contextBudgetLayerDetails",
          buildJsonArray {
            layers.forEach { layer ->
              add(
                buildJsonObject {
                  put("id", layer.id.name)
                  put("name", layer.name)
                  put("priorityClass", layer.priorityClass.name)
                  put("retentionPriority", layer.retentionPriority)
                  put("estimatedTokensBefore", layer.estimatedTokensBefore)
                  put("estimatedTokensAfter", layer.estimatedTokensAfter)
                  put("finalState", layer.finalState.wireValue)
                  put("omitted", layer.omitted)
                  put("reduced", layer.reduced)
                  put(
                    "appliedOperators",
                    buildJsonArray {
                      layer.appliedOperators.forEach { operator ->
                        add(JsonPrimitive(operator))
                      }
                    },
                  )
                },
              )
            }
          }.toString(),
        )
        put(
          "contextBudgetLayerSummary",
          layers.joinToString(separator = ";") { layer ->
            buildString {
              append(layer.id.name)
              append(":")
              append(layer.estimatedTokensBefore)
              append(">")
              append(layer.estimatedTokensAfter)
              append(":")
              append(layer.finalState.wireValue)
              if (layer.appliedOperators.isNotEmpty()) {
                append("[")
                append(layer.appliedOperators.joinToString(separator = "|"))
                append("]")
              }
            }
          },
        )
      }
  }

  private fun putContextToolProtocolMetadata(
    target: MutableMap<String, String>,
    trace: ToolProtocolTrace,
  ) {
    target["contextToolProtocolDetailMode"] = trace.detailMode
    target["contextToolProtocolReducedForBudget"] = trace.reducedForBudget.toString()
    target["contextToolProtocolExampleCount"] = trace.exampleCount.toString()
    target["contextToolProtocolAttachmentExampleCount"] = trace.attachmentExampleCount.toString()
    target["contextToolProtocolToolGuidanceCount"] = trace.toolSpecificGuidanceCount.toString()
    target["contextToolProtocolAvailableToolCount"] = trace.availableToolCount.toString()
  }

  private fun visibleToolDefinitionsForTurn(
    allDefinitions: List<AgentToolDefinition>,
    activeSkillCapsule: ActiveSkillCapsule?,
    memoryToolsEnabled: Boolean,
  ): List<AgentToolDefinition> {
    if (onDeviceLiteModeEnabledForTurn()) {
      return emptyList()
    }
    val memoryAwareDefinitions = if (memoryToolsEnabled) {
      allDefinitions
    } else {
      allDefinitions.filterNot { definition -> isMemoryTool(definition.name) }
    }
    val baseDefinitions = if (webSearchEnabledForTurn()) {
      memoryAwareDefinitions
    } else {
      memoryAwareDefinitions.filterNot { definition ->
        definition.name.equals("WebSearch", ignoreCase = true)
      }
    }
    val allowedToolKeys = normalizedAllowedToolKeys(activeSkillCapsule)
      .takeIf { keys -> keys.isNotEmpty() }
      ?.plus(DEFAULT_ACTIVE_SKILL_EXEMPT_TOOL_KEYS)
      ?: return baseDefinitions
    return baseDefinitions.filter { definition ->
      toolPolicyKey(definition.name) in allowedToolKeys
    }
  }

  private fun gateDisabledSessionToolCall(
    call: AgentToolCall,
    memoryToolsEnabled: Boolean,
  ): AgentToolResult? {
    if (memoryToolsEnabled || !isMemoryTool(call.toolName)) {
      return null
    }
    val detail = "Memory tools are disabled for this run by operator settings."
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.DENIED,
      content = detail,
      errorCode = "MEMORY_TOOL_DISABLED",
      errorMessage = detail,
      metadata = mapOf("memoryToolsEnabled" to "false"),
    )
  }

  private fun gateActiveSkillToolCall(
    call: AgentToolCall,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): AgentToolResult? {
    val capsule = activeSkillCapsule
      ?.takeIf { active -> active.toolRestrictionEnabled }
      ?: return null
    val allowedToolKeys = normalizedAllowedToolKeys(capsule)
    val requestedToolKey = toolPolicyKey(call.toolName)
    if (requestedToolKey in allowedToolKeys || requestedToolKey in DEFAULT_ACTIVE_SKILL_EXEMPT_TOOL_KEYS) {
      return null
    }
    val detail = buildString {
      append("Active skill '")
      append(capsule.name)
      append("' restricts tool usage for this run. ")
      append("Requested tool '")
      append(call.toolName)
      append("' is outside the active allowlist.")
    }
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.DENIED,
      content = detail,
      errorCode = ERROR_SKILL_TOOL_POLICY_BLOCKED,
      errorMessage = detail,
      metadata = mapOf(
        "activeSkillName" to capsule.name,
        "activeSkillRelativePath" to capsule.relativePath,
        "requestedToolKey" to requestedToolKey,
        "allowedToolKeys" to allowedToolKeys.sorted().joinToString(separator = ","),
      ),
    )
  }

  private fun activatedSkillNameFrom(
    call: AgentToolCall,
    result: AgentToolResult,
  ): String? {
    if (toolPolicyKey(call.toolName) !in setOf("skill_read", "skill_execute")) {
      return null
    }
    return result.metadata["skillName"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
  }

  private fun activeSkillActivationSourceFrom(
    result: AgentToolResult,
  ): String = result.metadata["activationSource"]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: ACTIVATION_SOURCE_SKILL_READ

  private fun activeSkillPinnedFrom(
    result: AgentToolResult,
  ): Boolean = result.metadata["pinned"]
    ?.trim()
    ?.toBooleanStrictOrNull()
    ?: false

  private fun resolveActiveSkillCapsule(
    activeSkillName: String?,
    activationSource: String?,
    pinned: Boolean? = null,
  ): ActiveSkillCapsule? {
    val resolved = activeSkillCapsuleResolver.resolve(
      catalog = config.sessionContext.skillCatalog,
      activeSkillName = activeSkillName,
      activationSource = activationSource,
    )
    if (resolved != null) {
      return resolved.copy(pinned = pinned ?: resolved.pinned)
    }
    val inherited = config.inheritedActiveSkillCapsule ?: return null
    val normalizedName = activeSkillName?.trim()?.takeIf(String::isNotBlank) ?: return null
    val normalizedSource = activationSource?.trim()?.takeIf(String::isNotBlank) ?: return null
    return inherited.takeIf { capsule ->
      capsule.name == normalizedName && capsule.activationSource == normalizedSource
    }?.copy(pinned = pinned ?: inherited.pinned)
  }

  private fun maybeSelectImplicitInlineSkill(
    userInput: String,
  ): ActiveSkillCapsule? {
    val queryTerms = implicitSkillTerms(userInput)
    if (queryTerms.isEmpty()) {
      return null
    }
    val candidate = config.sessionContext.skillInventory.skills
      .asSequence()
      .filter { skill ->
        skill.invocationControl.name == "EXPLICIT_AND_IMPLICIT" &&
          skill.executionContext.name == "INLINE"
      }
      .mapNotNull { skill ->
        val score = implicitSkillScore(skill.name, skill.description, queryTerms)
        if (score > 0) skill to score else null
      }
      .sortedWith(
        compareByDescending<Pair<VisibleSkill, Int>> { it.second }
          .thenBy { it.first.name },
      )
      .firstOrNull()
      ?.first
      ?: return null
    return resolveActiveSkillCapsule(
      activeSkillName = candidate.name,
      activationSource = ACTIVATION_SOURCE_IMPLICIT_SKILL,
      pinned = false,
    )
  }

  private fun implicitSkillTerms(text: String): Set<String> =
    text
      .lowercase()
      .split(Regex("[^a-z0-9_\\-]+"))
      .map(String::trim)
      .filter { term -> term.length >= 3 }
      .toSet()

  private fun implicitSkillScore(
    name: String,
    description: String,
    queryTerms: Set<String>,
  ): Int {
    val normalizedName = name.lowercase()
    val normalizedDescription = description.lowercase()
    return queryTerms.sumOf { term ->
      when {
        normalizedName == term -> 100
        normalizedName.contains(term) -> 40
        normalizedDescription.contains(term) -> 10
        else -> 0
      }
    }
  }

  private fun normalizedAllowedToolKeys(
    activeSkillCapsule: ActiveSkillCapsule?,
  ): Set<String> = activeSkillCapsule
    ?.allowedToolKeys
    ?.map(::toolPolicyKey)
    ?.toSet()
    ?: emptySet()

  private fun isMemoryTool(toolName: String): Boolean = when (toolPolicyKey(toolName)) {
    "memory_search",
    "memory_get",
    "session_search",
    "session_get",
    "past_session_search",
    "past_session_get",
    -> true
    else -> false
  }

  private fun llmFailureResult(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult,
    turn: Int,
    toolCallCount: Int,
    contextReport: ContextAssemblyReport?,
    diagnostics: PromptRunDiagnostics? = null,
  ): ExecutionResult = when (gatewayResult.status) {
    LiteLlmGatewayStatus.TIMEOUT -> ExecutionResult(
      taskId = task.id,
      status = ExecutionStatus.TIMEOUT,
      errorCode = gatewayResult.errorCode ?: "LLM_TIMEOUT",
      errorMessage = gatewayResult.errorMessage ?: "LLM request timed out.",
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = maxOf(startedAt, clock()),
      metadata = buildResultMetadata(
        gatewayResult = gatewayResult,
        turn = turn,
        toolCallCount = toolCallCount,
        responseFormat = "llm_timeout",
        contextReport = contextReport,
        diagnostics = diagnostics,
      ),
    )

    LiteLlmGatewayStatus.FAILED,
    LiteLlmGatewayStatus.RATE_LIMITED,
    -> failedResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      errorCode = gatewayResult.errorCode ?: "LLM_FAILURE",
      errorMessage = gatewayResult.errorMessage ?: "LLM request failed.",
      metadata = buildResultMetadata(
        gatewayResult = gatewayResult,
        turn = turn,
        toolCallCount = toolCallCount,
        responseFormat = "llm_failure",
        contextReport = contextReport,
        diagnostics = diagnostics,
      ),
    )

    LiteLlmGatewayStatus.SUCCESS -> error("llmFailureResult should not be called for success.")
  }

  private fun emptyResponseRecoveryExhaustedResult(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult,
    turn: Int,
    toolCallCount: Int,
    contextReport: ContextAssemblyReport?,
    diagnostics: PromptRunDiagnostics,
  ): ExecutionResult {
    val message = buildEmptyResponseRecoveryExhaustedMessage(
      gatewayResult = gatewayResult,
      recoveryCount = diagnostics.emptyResponseRecoveryCount,
    )
    return failedResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      errorCode = ERROR_EMPTY_RESPONSE_RECOVERY_EXHAUSTED,
      errorMessage = message,
      metadata = buildResultMetadata(
        gatewayResult = gatewayResult,
        turn = turn,
        toolCallCount = toolCallCount,
        responseFormat = "empty_response_recovery_exhausted",
        contextReport = contextReport,
        diagnostics = diagnostics,
      ) + mapOf(
        "emptyResponseRecoveryExhausted" to true.toString(),
        "emptyResponseRecoveryLimit" to config.maxRecoverableLlmRetries.toString(),
        "emptyResponseRecoveryStatus" to gatewayResult.status.name.lowercase(),
        "emptyResponseRecoveryReasonCode" to (
          gatewayResult.errorCode?.trim()?.takeIf(String::isNotBlank)
            ?: "SUCCESS_EMPTY_RESPONSE"
          ),
      ),
    )
  }

  private fun buildEmptyResponseRecoveryExhaustedMessage(
    gatewayResult: LiteLlmGatewayResult,
    recoveryCount: Int,
  ): String {
    val detail = when {
      isProviderEmptyResponseFailure(gatewayResult) ->
        gatewayResult.errorMessage ?: "Provider returned an empty completion payload."
      else ->
        "Provider returned no usable tool call, commentary update, or final answer."
    }
    return buildString {
      append("Automatic empty-response recovery stopped after ")
      append(recoveryCount)
      append(" attempt")
      if (recoveryCount != 1) {
        append('s')
      }
      append(". ")
      append(detail)
    }
  }

  private fun llmRetryExhaustedPauseResult(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult,
    turn: Int,
    toolCallCount: Int,
    contextReport: ContextAssemblyReport?,
    cursor: PromptTurnCursor,
    hooks: RuntimeExecutionHooks,
    diagnostics: PromptRunDiagnostics,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): ExecutionResult {
    val message = buildRecoverableRetryExhaustedMessage(
      gatewayResult = gatewayResult,
      retryCount = diagnostics.llmRetryCount,
    )
    hooks.requestSuspend(
      SuspensionRequest(
        reasonCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
        detail = message,
      ),
    )
    return failedResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      errorCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
      errorMessage = message,
      metadata = buildResultMetadata(
        gatewayResult = gatewayResult,
        turn = turn,
        toolCallCount = toolCallCount,
        responseFormat = "llm_retry_exhausted_paused",
        contextReport = contextReport,
        diagnostics = diagnostics,
      ) + mapOf(
        "llmRetryExhausted" to true.toString(),
        "llmRetryExhaustedStatus" to gatewayResult.status.name.lowercase(),
        "llmRetryExhaustedReasonCode" to (
          gatewayResult.errorCode?.trim()?.takeIf(String::isNotBlank)
            ?: gatewayResult.status.name
          ),
      ) + OpenCrayPromptResumeMetadata.encodeToMetadata(
        state = promptCheckpointState(
          cursor = cursor,
          turnIndex = cursor.turn,
          localContinuationContextPrompts = localContinuationContextPrompts,
          localContinuationStableAnchor = localContinuationStableAnchor,
          localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
          localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
          localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
          localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
        ),
        json = config.json,
      ),
    )
  }

  private fun successResult(
    task: AgentTask,
    body: String,
    startedAt: Long,
    finishedAt: Long,
    metadata: Map<String, String>,
  ): ExecutionResult = ExecutionResult(
    taskId = task.id,
    status = ExecutionStatus.SUCCESS,
    stdout = body,
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = maxOf(startedAt, finishedAt),
    metadata = metadata,
  )

  private fun failedResult(
    task: AgentTask,
    startedAt: Long,
    finishedAt: Long,
    errorCode: String,
    errorMessage: String,
    metadata: Map<String, String> = emptyMap(),
  ): ExecutionResult = ExecutionResult(
    taskId = task.id,
    status = ExecutionStatus.FAILED,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = maxOf(startedAt, finishedAt),
    metadata = metadata,
  )

  private fun cancelledResult(
    task: AgentTask,
    startedAt: Long,
    finishedAt: Long,
  ): ExecutionResult = ExecutionResult(
    taskId = task.id,
    status = ExecutionStatus.CANCELLED,
    errorCode = "AGENT_CANCELLED",
    errorMessage = "Agent execution was cancelled.",
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = maxOf(startedAt, finishedAt),
    metadata = mapOf("responseFormat" to "cancelled"),
  )

  private fun cancelledToolExecutionResult(
    task: AgentTask,
    startedAt: Long,
    finishedAt: Long,
    toolResult: AgentToolResult,
    gatewayResult: LiteLlmGatewayResult?,
    turn: Int,
    toolCallCount: Int,
    contextReport: ContextAssemblyReport?,
    diagnostics: PromptRunDiagnostics? = null,
  ): ExecutionResult = ExecutionResult(
    taskId = task.id,
    status = ExecutionStatus.CANCELLED,
    stderr = toolResult.content.takeIf(String::isNotBlank).orEmpty(),
    errorCode = toolResult.errorCode ?: "AGENT_CANCELLED",
    errorMessage = toolResult.errorMessage ?: "Agent execution was cancelled.",
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = maxOf(startedAt, finishedAt),
    metadata = buildResultMetadata(
      gatewayResult = gatewayResult,
      turn = turn,
      toolCallCount = toolCallCount,
      responseFormat = "cancelled",
      contextReport = contextReport,
      diagnostics = diagnostics,
    ) + toolResult.metadata + mapOf(
      "cancelledToolName" to toolResult.toolName,
      "toolName" to toolResult.toolName,
      "observation" to toolResult.toObservationText(config.json),
    ),
  )

  private fun failedToolExecutionResult(
    task: AgentTask,
    startedAt: Long,
    finishedAt: Long,
    toolResult: AgentToolResult,
    gatewayResult: LiteLlmGatewayResult?,
    turn: Int,
    toolCallCount: Int,
    contextReport: ContextAssemblyReport?,
    diagnostics: PromptRunDiagnostics? = null,
  ): ExecutionResult = ExecutionResult(
    taskId = task.id,
    status = ExecutionStatus.FAILED,
    stderr = toolResult.content.takeIf(String::isNotBlank).orEmpty(),
    errorCode = toolResult.errorCode ?: "TOOL_EXECUTION_FAILED",
    errorMessage = toolResult.errorMessage ?: "Tool execution failed.",
    startedAtEpochMs = startedAt,
    finishedAtEpochMs = maxOf(startedAt, finishedAt),
    metadata = buildResultMetadata(
      gatewayResult = gatewayResult,
      turn = turn,
      toolCallCount = toolCallCount,
      responseFormat = "tool_execution_failed",
      contextReport = contextReport,
      diagnostics = diagnostics,
    ) + toolResult.metadata + mapOf(
      "failedToolName" to toolResult.toolName,
      "toolName" to toolResult.toolName,
      "observation" to toolResult.toObservationText(config.json),
    ),
  )

  private fun AgentToolResult.toExecutionResult(
    task: AgentTask,
    startedAt: Long,
    finishedAt: Long,
    json: Json,
  ): ExecutionResult {
    val executionStatus = when (status) {
      AgentToolResultStatus.SUCCESS -> ExecutionStatus.SUCCESS
      AgentToolResultStatus.DENIED -> ExecutionStatus.DENIED
      AgentToolResultStatus.CANCELLED -> ExecutionStatus.CANCELLED
      AgentToolResultStatus.TIMEOUT -> ExecutionStatus.TIMEOUT
      AgentToolResultStatus.FAILED -> ExecutionStatus.FAILED
    }
    return ExecutionResult(
      taskId = task.id,
      status = executionStatus,
      exitCode = exitCode,
      stdout = if (executionStatus == ExecutionStatus.SUCCESS) content else "",
      stderr = if (stderr.isNotBlank()) stderr else if (executionStatus != ExecutionStatus.SUCCESS) content else "",
      errorCode = errorCode,
      errorMessage = errorMessage,
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = maxOf(startedAt, finishedAt),
      metadata = metadata + mapOf(
        "toolName" to toolName,
        "observation" to toObservationText(json),
      ),
    )
  }

  private fun AgentToolResult.isApprovalRequiredDenial(): Boolean =
    status == AgentToolResultStatus.DENIED &&
      (errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED)

  private fun AgentToolResult.isSubAgentToolNotAllowed(): Boolean =
    errorCode == "SUBAGENT_TOOL_NOT_ALLOWED"

  private fun extractJsonSequence(raw: String): JsonSequence? {
    val fenced = raw.lines()
      .dropWhile { line -> !line.trimStart().startsWith("```") }
      .drop(1)
      .takeWhile { line -> !line.trimStart().startsWith("```") }
      .joinToString(separator = "\n")
      .trim()
    val source = when {
      fenced.startsWith("{") && fenced.endsWith("}") -> fenced
      raw.startsWith("{") && raw.endsWith("}") -> raw
      else -> raw
    }

    val envelopes = mutableListOf<JsonEnvelope>()
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    var cursor = 0
    for ((index, character) in source.withIndex()) {
      when {
        inString && escaped -> escaped = false
        inString && character == '\\' -> escaped = true
        character == '"' -> inString = !inString
        !inString && character == '{' -> {
          if (depth == 0) {
            startIndex = index
          }
          depth += 1
        }

        !inString && character == '}' -> {
          depth -= 1
          if (depth == 0 && startIndex >= 0) {
            envelopes += JsonEnvelope(
              json = source.substring(startIndex, index + 1),
              leadingText = source.substring(cursor, startIndex),
            )
            cursor = index + 1
          }
        }
      }
    }
    if (envelopes.isEmpty()) return null
    return JsonSequence(
      envelopes = envelopes,
      trailingText = source.substring(cursor),
    )
  }

  private fun JsonObject.primitiveContent(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

  private fun JsonObject.booleanContent(key: String): Boolean? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toBooleanStrictOrNull()

  private fun JsonObject.longContent(key: String): Long? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toLongOrNull()

  private fun JsonObject.intContent(key: String): Int? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toIntOrNull()

  private fun JsonObject.jsonObjectContent(key: String): JsonObject? =
    this[key] as? JsonObject

  private fun JsonObject.toStringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
    (value as? JsonPrimitive)?.content?.let { content -> key to content }
  }.toMap()

  private fun JsonObject.intArrayContent(key: String): List<Int>? =
    (this[key] as? JsonArray)
      ?.mapNotNull { element ->
        (element as? JsonPrimitive)
          ?.content
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.toIntOrNull()
      }

  private fun JsonObject.stringArrayContent(key: String): List<String>? =
    (this[key] as? JsonArray)
      ?.mapNotNull { element ->
        (element as? JsonPrimitive)
          ?.content
          ?.trim()
          ?.takeIf(String::isNotBlank)
      }

  private fun runIdFor(task: AgentTask): String =
    task.metadata[RUN_ID_METADATA_KEY]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun seededSubAgentHandles(
    parentRunId: String,
  ): MutableMap<String, SubAgentHandleState> = linkedMapOf<String, SubAgentHandleState>().apply {
    (
      config.promptResumeState?.subAgentHandles.orEmpty() +
        config.seededSubAgentHandles +
        config.subAgentExecutionCoordinator.handlesForParentRun(parentRunId)
      ).map(::restoredSubAgentHandle)
      .forEach { handle ->
        val existing = this[handle.agentId]
        if (existing == null || handle.updatedAtEpochMs >= existing.updatedAtEpochMs) {
          this[handle.agentId] = handle
        }
      }
  }

  private fun isGatewayVisibleMetadataKey(key: String): Boolean = !key.startsWith(HIDDEN_METADATA_PREFIX)

  private fun promptTranscriptForExecution(
    seededTranscript: List<RuntimeConversationMessage>,
    resumeState: OpenCrayPromptResumeState?,
  ): List<RuntimeConversationMessage> {
    if (resumeState == null) {
      return seededTranscript
    }
    return resumeState.transcriptFor(seededTranscript)
  }

  private fun executePromptActionBatch(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult?,
    contextReport: ContextAssemblyReport?,
    parsedBatch: ParsedModelActionBatch.Actions,
    cursor: PromptTurnCursor,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    diagnostics: PromptRunDiagnostics,
    nativeToolCallingEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    actionStartIndex: Int = 0,
    suppressToolCallEventAtActionIndex: Int? = null,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): PromptBatchExecutionOutcome {
    val batchActions = normalizeToolCallIds(
      actions = parsedBatch.actions,
      cursor = cursor,
    )
    val containsToolAction = batchActions.any { action -> action is AgentModelAction.ToolCall }
    val containsCommentaryAction = batchActions.any { action -> action is AgentModelAction.Commentary }
    val containsFinalAction = batchActions.any { action -> action is AgentModelAction.Final }
    if (!containsToolAction && !containsCommentaryAction && !containsFinalAction) {
      return PromptBatchExecutionOutcome.Terminal(
        failedResult(
          task = task,
          startedAt = startedAt,
          finishedAt = clock(),
          errorCode = "MODEL_ACTION_FORMAT_ERROR",
          errorMessage = "Model output did not contain a usable action.",
          metadata = buildResultMetadata(
            gatewayResult = gatewayResult,
            turn = cursor.turn,
            toolCallCount = cursor.toolCallCount,
            responseFormat = "protocol_error_exhausted",
            contextReport = contextReport,
            diagnostics = diagnostics,
          ),
        ),
      )
    }
    if (containsToolAction) {
      diagnostics.parsedToolCallObserved = true
    }

    val parallelToolCallsEnabled =
      requestedParallelToolCalls(nativeToolCallingEnabled) == true &&
        suppressToolCallEventAtActionIndex == null
    var shouldContinueBatch = true
    var index = actionStartIndex
    while (index < batchActions.size) {
      if (!shouldContinueBatch) {
        break
      }
      when (val action = batchActions[index]) {
        is AgentModelAction.Commentary -> {
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            kind = RuntimeConversationMessageKind.COMMENTARY,
            content = buildCommentaryTranscriptEntry(
              task = task,
              turn = cursor.turn,
              commentary = action,
            ),
            commentary = RuntimeConversationCommentary(
              runId = runIdFor(task),
              taskId = task.id,
              turn = cursor.turn,
              text = action.text,
              stage = action.stage,
            ),
            assistantPhase = RuntimeConversationAssistantPhase.COMMENTARY,
          )
          emitCommentaryEvent(
            task = task,
            turn = cursor.turn,
            text = action.text,
            stage = action.stage,
            metadata = promptCheckpointMetadataAfterActionIndex(
              boundary = OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED,
              cursor = cursor,
              batchActions = batchActions,
              nextActionIndex = index + 1,
              requiresSingleActionReminder = parsedBatch.requiresSingleActionReminder,
              localContinuationContextPrompts = localContinuationContextPrompts,
              localContinuationStableAnchor = localContinuationStableAnchor,
              localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
              localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
              localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
              localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
            ),
          )
          index += 1
          continue
        }

        is AgentModelAction.ToolCall -> {
          if (isFinalAnswerOnlyTurn(cursor.turn)) {
            return PromptBatchExecutionOutcome.Terminal(
              failedResult(
                task = task,
                startedAt = startedAt,
                finishedAt = clock(),
                errorCode = "MAX_TURNS_EXCEEDED",
                errorMessage = "Agent reached the configured turn limit and still tried to call another tool instead of returning a final answer.",
                metadata = buildResultMetadata(
                  gatewayResult = gatewayResult,
                  turn = cursor.turn,
                  toolCallCount = cursor.toolCallCount,
                  responseFormat = "turn_limit_final_answer_required",
                  contextReport = contextReport,
                  diagnostics = diagnostics,
                ) + mapOf("finalAnswerRequired" to "true"),
              ),
            )
          }
          if (config.maxToolCalls > 0 && cursor.toolCallCount >= config.maxToolCalls) {
            return PromptBatchExecutionOutcome.Terminal(
              failedResult(
                task = task,
                startedAt = startedAt,
                finishedAt = clock(),
                errorCode = "MAX_TOOL_CALLS_EXCEEDED",
                errorMessage = "Agent exceeded the configured tool call budget.",
                metadata = buildResultMetadata(
                  gatewayResult = gatewayResult,
                  turn = cursor.turn,
                  toolCallCount = cursor.toolCallCount,
                  responseFormat = "tool_budget_exceeded",
                  contextReport = contextReport,
                  diagnostics = diagnostics,
                ),
              ),
            )
          }

          if (parallelToolCallsEnabled) {
            val parallelGroup = collectParallelToolActionGroup(
              task = task,
              actions = batchActions,
              startIndex = index,
              cursor = cursor,
              activeSkillCapsule = activeSkillCapsule,
            )
            if (parallelGroup != null) {
              when (
                val outcome = executeParallelToolActionGroup(
                  task = task,
                  startedAt = startedAt,
                  gatewayResult = gatewayResult,
                  contextReport = contextReport,
                  batchActions = batchActions,
                  parsedBatch = parsedBatch,
                  cursor = cursor,
                  hooks = hooks,
                  activeSkillCapsule = activeSkillCapsule,
                  diagnostics = diagnostics,
                  group = parallelGroup,
                  localContinuationContextPrompts = localContinuationContextPrompts,
                  localContinuationStableAnchor = localContinuationStableAnchor,
                  localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
                  localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
                  localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
                  localContinuationRequestSettingsFingerprint =
                    localContinuationRequestSettingsFingerprint,
                )
              ) {
                is ParallelToolActionGroupOutcome.Advance -> {
                  shouldContinueBatch = outcome.shouldContinueBatch
                  index = outcome.nextActionIndex
                  continue
                }

                is ParallelToolActionGroupOutcome.Terminal -> {
                  return PromptBatchExecutionOutcome.Terminal(outcome.result)
                }
              }
            }
          }

          val suppressToolCallEvent = suppressToolCallEventAtActionIndex == index
          announcePromptToolCall(
            task = task,
            turn = cursor.turn,
            call = action.call,
            cursor = cursor,
            diagnostics = diagnostics,
            suppressToolCallEvent = suppressToolCallEvent,
          )
          if (action.call.toolName == "TodoWrite") {
            cursor.todoWriteUsed = true
          }
          val toolResult = dispatchPromptToolCall(
            task = task,
            turn = cursor.turn,
            call = action.call,
            transcript = cursor.transcript,
            cursor = cursor,
            hooks = hooks,
            activeSkillCapsule = activeSkillCapsule,
            allowDuplicateShortCircuit = !suppressToolCallEvent,
          )
          terminalOutcomeForPromptToolCall(
            task = task,
            startedAt = startedAt,
            gatewayResult = gatewayResult,
            contextReport = contextReport,
            batchActions = batchActions,
            parsedBatch = parsedBatch,
            cursor = cursor,
            hooks = hooks,
            diagnostics = diagnostics,
            index = index,
            call = action.call,
            toolResult = toolResult,
          )?.let { terminalOutcome ->
            return terminalOutcome
          }
          shouldContinueBatch = applyPromptToolResult(
            task = task,
            turn = cursor.turn,
            call = action.call,
            toolResult = toolResult,
            cursor = cursor,
            diagnostics = diagnostics,
            batchActions = batchActions,
            nextActionIndex = index + 1,
            requiresSingleActionReminder = parsedBatch.requiresSingleActionReminder,
            localContinuationContextPrompts = localContinuationContextPrompts,
            localContinuationStableAnchor = localContinuationStableAnchor,
            localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
            localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
            localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
            localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
          )
          index += 1
          continue
        }

        is AgentModelAction.Final -> if (!containsToolAction) {
          val todoPlanClosureObservation = todoPlanClosureObservation(cursor)
          if (todoPlanClosureObservation != null) {
            cursor.transcript += RuntimeConversationMessage(
              role = RuntimeConversationRole.TOOL,
              content = todoPlanClosureObservation,
            )
            invalidateResponsesLineage(cursor)
            invalidateLocalContinuation(cursor)
            shouldContinueBatch = false
            continue
          }
          emitAssistantEvent(
            task = task,
            turn = cursor.turn,
            text = action.answer,
            responseFormat = action.responseFormat,
            isFinal = true,
          )
          return PromptBatchExecutionOutcome.Terminal(
            successResult(
              task = task,
              body = action.answer,
              startedAt = startedAt,
              finishedAt = clock(),
              metadata = buildResultMetadata(
                gatewayResult = gatewayResult,
                turn = cursor.turn,
                toolCallCount = cursor.toolCallCount,
                responseFormat = action.responseFormat,
                contextReport = contextReport,
                diagnostics = diagnostics,
              ) + finalAttachmentMetadata(action.attachments),
            ),
          )
        }
      }
      index += 1
    }

    if (parsedBatch.requiresSingleActionReminder) {
      cursor.transcript += RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildSingleActionReminderObservation(
          nativeToolCallingEnabled = nativeToolCallingEnabled,
          legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
        ),
      )
    }
    if (containsToolAction) {
      applyMidTurnMaintenance(
        task = task,
        cursor = cursor,
      )
    }
    cursor.turn += 1
    return PromptBatchExecutionOutcome.Continue
  }

  private fun applyMidTurnMaintenance(
    task: AgentTask,
    cursor: PromptTurnCursor,
  ) {
    val beforeSessionContext = cursor.sessionContext
    val beforeConversation = cursor.transcript.toList()
    val result = runCatching {
      config.midTurnMaintenance(
        OpenCrayMidTurnMaintenanceRequest(
          task = task,
          runId = runIdFor(task),
          turn = cursor.turn,
          conversation = beforeConversation,
          sessionContext = beforeSessionContext,
          llmMetadata = config.llmMetadata,
        ),
      )
    }.getOrElse {
      return
    }
    val requestedSessionContext = result.sessionContext ?: beforeSessionContext
    val requestedConversation = result.conversation
      ?: requestedSessionContext.conversation.takeIf { conversation -> conversation.isNotEmpty() }
      ?: beforeConversation
    val nextSessionContext = requestedSessionContext.copy(conversation = requestedConversation)
    val changed = requestedConversation != beforeConversation ||
      nextSessionContext.memoryFlushTrace != beforeSessionContext.memoryFlushTrace ||
      nextSessionContext.durableCompaction.trace != beforeSessionContext.durableCompaction.trace ||
      nextSessionContext.recalledMemory != beforeSessionContext.recalledMemory
    cursor.sessionContext = nextSessionContext
    if (requestedConversation != beforeConversation) {
      cursor.transcript.clear()
      cursor.transcript += requestedConversation
    }
    if (changed) {
      invalidateResponsesLineage(cursor)
      invalidateLocalContinuation(cursor)
    }
  }

  private fun collectParallelToolActionGroup(
    task: AgentTask,
    actions: List<AgentModelAction>,
    startIndex: Int,
    cursor: PromptTurnCursor,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): List<ParallelToolActionStep>? {
    val remainingToolBudget = if (config.maxToolCalls > 0) {
      (config.maxToolCalls - cursor.toolCallCount).coerceAtLeast(0)
    } else {
      Int.MAX_VALUE
    }
    if (remainingToolBudget < 2) {
      return null
    }
    val transcriptSnapshot = cursor.transcript.toList()
    val seenDuplicateSignatures = linkedSetOf<String>()
    val group = mutableListOf<ParallelToolActionStep>()
    var index = startIndex
    while (index < actions.size && group.size < remainingToolBudget) {
      val action = actions[index] as? AgentModelAction.ToolCall ?: break
    if (
      !canExecutePromptToolCallInParallel(
        task = task,
        call = action.call,
        transcript = transcriptSnapshot,
        memoryToolsEnabled = cursor.sessionContext.memoryToolsEnabled,
        activeSkillCapsule = activeSkillCapsule,
      )
    ) {
        break
      }
      val duplicateSignature = recentToolObservationSupport.duplicateDiscoverySignature(action.call)
      if (duplicateSignature != null && !seenDuplicateSignatures.add(duplicateSignature)) {
        break
      }
      group += ParallelToolActionStep(
        index = index,
        call = action.call,
      )
      index += 1
    }
    return group.takeIf { candidates -> candidates.size > 1 }
  }

  private fun canExecutePromptToolCallInParallel(
    task: AgentTask,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    memoryToolsEnabled: Boolean,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): Boolean {
    if (
      gateActiveSkillToolCall(
        call = call,
        activeSkillCapsule = activeSkillCapsule,
      ) != null
    ) {
      return false
    }
    if (
      gateDisabledSessionToolCall(
        call = call,
        memoryToolsEnabled = memoryToolsEnabled,
      ) != null
    ) {
      return false
    }
    if (maybeShortCircuitDuplicateDiscoveryCall(call = call, transcript = transcript) != null) {
      return false
    }
    return toolDispatcher.canExecuteInParallel(task = task, call = call)
  }

  private fun executeParallelToolActionGroup(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult?,
    contextReport: ContextAssemblyReport?,
    batchActions: List<AgentModelAction>,
    parsedBatch: ParsedModelActionBatch.Actions,
    cursor: PromptTurnCursor,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    diagnostics: PromptRunDiagnostics,
    group: List<ParallelToolActionStep>,
    localContinuationContextPrompts: List<String>?,
    localContinuationStableAnchor: String?,
    localContinuationGatewayMessagesEnabled: Boolean,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): ParallelToolActionGroupOutcome {
    group.forEach { step ->
      announcePromptToolCall(
        task = task,
        turn = cursor.turn,
        call = step.call,
        cursor = cursor,
        diagnostics = diagnostics,
        suppressToolCallEvent = false,
      )
    }
    val dispatches = dispatchPromptToolCallsInParallel(
      task = task,
      turn = cursor.turn,
      calls = group,
      transcript = cursor.transcript.toList(),
      cursor = cursor,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
    )
    var shouldContinueBatch = true
    for (dispatchIndex in dispatches.indices) {
      val dispatch = dispatches[dispatchIndex]
      terminalOutcomeForPromptToolCall(
        task = task,
        startedAt = startedAt,
        gatewayResult = gatewayResult,
        contextReport = contextReport,
        batchActions = batchActions,
        parsedBatch = parsedBatch,
        cursor = cursor,
        hooks = hooks,
        diagnostics = diagnostics,
        index = dispatch.step.index,
        call = dispatch.step.call,
        toolResult = dispatch.result,
      )?.let { terminalOutcome ->
        return ParallelToolActionGroupOutcome.Terminal(terminalOutcome.result)
      }
      shouldContinueBatch = applyPromptToolResult(
        task = task,
        turn = cursor.turn,
        call = dispatch.step.call,
        toolResult = dispatch.result,
        cursor = cursor,
        diagnostics = diagnostics,
        batchActions = batchActions,
        nextActionIndex = if (dispatchIndex == dispatches.lastIndex) {
          group.last().index + 1
        } else {
          null
        },
        requiresSingleActionReminder = parsedBatch.requiresSingleActionReminder,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
        localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
        localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
        localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
      )
      if (!shouldContinueBatch) {
        break
      }
    }
    return ParallelToolActionGroupOutcome.Advance(
      nextActionIndex = group.last().index + 1,
      shouldContinueBatch = shouldContinueBatch,
    )
  }

  private fun dispatchPromptToolCallsInParallel(
    task: AgentTask,
    turn: Int,
    calls: List<ParallelToolActionStep>,
    transcript: List<RuntimeConversationMessage>,
    cursor: PromptTurnCursor,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): List<ParallelToolDispatch> {
    val executor = Executors.newFixedThreadPool(calls.size)
    try {
      val futures = calls.map { step ->
        executor.submit<ParallelToolDispatch> {
          val result = runCatching {
            dispatchPromptToolCall(
              task = task,
              turn = turn,
              call = step.call,
              transcript = transcript,
              cursor = cursor,
              hooks = hooks,
              activeSkillCapsule = activeSkillCapsule,
              allowDuplicateShortCircuit = false,
            )
          }.getOrElse { error ->
            unexpectedPromptToolDispatchFailure(
              call = step.call,
              error = error,
            )
          }
          ParallelToolDispatch(
            step = step,
            result = result,
          )
        }
      }
      return futures.map { future -> future.get() }
    } finally {
      executor.shutdownNow()
    }
  }

  private fun announcePromptToolCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    cursor: PromptTurnCursor,
    diagnostics: PromptRunDiagnostics,
    suppressToolCallEvent: Boolean,
  ) {
    if (suppressToolCallEvent) {
      return
    }
    cursor.transcript += RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      kind = RuntimeConversationMessageKind.TOOL_CALL,
      content = buildToolCallTranscriptEntry(
        task = task,
        turn = turn,
        call = call,
      ),
      toolCall = call.toRuntimeConversationToolCall(),
    )
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolCallEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        call = call,
        emittedAtEpochMs = clock(),
      ),
    )
    diagnostics.toolCallEventEmitted = true
  }

  private fun terminalOutcomeForPromptToolCall(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult?,
    contextReport: ContextAssemblyReport?,
    batchActions: List<AgentModelAction>,
    parsedBatch: ParsedModelActionBatch.Actions,
    cursor: PromptTurnCursor,
    hooks: RuntimeExecutionHooks,
    diagnostics: PromptRunDiagnostics,
    index: Int,
    call: AgentToolCall,
    toolResult: AgentToolResult,
  ): PromptBatchExecutionOutcome.Terminal? {
    if (toolResult.status == AgentToolResultStatus.CANCELLED) {
      emitToolResultEvent(
        task = task,
        turn = cursor.turn,
        call = call,
        result = toolResult,
        diagnostics = diagnostics,
      )
      return PromptBatchExecutionOutcome.Terminal(
        cancelledToolExecutionResult(
          task = task,
          startedAt = startedAt,
          finishedAt = clock(),
          toolResult = toolResult,
          gatewayResult = gatewayResult,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount + 1,
          contextReport = contextReport,
          diagnostics = diagnostics,
        ),
      )
    }
    if (toolResult.isSubAgentToolNotAllowed()) {
      emitToolResultEvent(
        task = task,
        turn = cursor.turn,
        call = call,
        result = toolResult,
        diagnostics = diagnostics,
      )
      return PromptBatchExecutionOutcome.Terminal(
        failedToolExecutionResult(
          task = task,
          startedAt = startedAt,
          finishedAt = clock(),
          toolResult = toolResult,
          gatewayResult = gatewayResult,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount + 1,
          contextReport = contextReport,
          diagnostics = diagnostics,
        ),
      )
    }
    if (toolResult.isApprovalRequiredDenial()) {
      emitToolResultEvent(
        task = task,
        turn = cursor.turn,
        call = call,
        result = toolResult,
        diagnostics = diagnostics,
      )
      cancelActiveSubAgentExecutions(
        task = task,
        turn = cursor.turn,
        cursor = cursor,
        reason = "Parent run suspended while awaiting tool approval.",
        removeHandles = false,
      )
      return PromptBatchExecutionOutcome.Terminal(
        approvalRequiredPromptToolResult(
          task = task,
          startedAt = startedAt,
          finishedAt = clock(),
          gatewayResult = gatewayResult,
          contextReport = contextReport,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount,
          transcript = cursor.transcript,
          pendingActions = batchActions,
          nextActionIndex = index,
          requiresSingleActionReminder = parsedBatch.requiresSingleActionReminder,
          toolCall = call,
          toolResult = toolResult,
          activeSkillName = cursor.activeSkillName,
          activeSkillActivationSource = cursor.activeSkillActivationSource,
          activeSkillPinned = cursor.activeSkillPinned,
          responsesPreviousResponseId = cursor.responsesPreviousResponseId,
          responsesProviderLineageId = cursor.responsesProviderLineageId,
          responsesLineageTrusted = cursor.responsesLineageTrusted,
          responsesContinuationShape = cursor.responsesContinuationShape,
          responsesPendingMessages = cursor.responsesPendingMessages.toList(),
          replayToolResultProjections = cursor.replayToolResultProjections.toMap(),
          subAgentHandles = synchronizedSubAgentHandles(cursor),
          hooks = hooks,
          diagnostics = diagnostics,
        ),
      )
    }
    return null
  }

  private fun applyPromptToolResult(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    toolResult: AgentToolResult,
    cursor: PromptTurnCursor,
    diagnostics: PromptRunDiagnostics,
    batchActions: List<AgentModelAction>? = null,
    nextActionIndex: Int? = null,
    requiresSingleActionReminder: Boolean = false,
    localContinuationContextPrompts: List<String>?,
    localContinuationStableAnchor: String?,
    localContinuationGatewayMessagesEnabled: Boolean,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): Boolean {
    val promptSupplement = successfulPromptSupplementFrom(toolResult)
    if (toolResult.status == AgentToolResultStatus.SUCCESS) {
      diagnostics.lastSuccessfulToolName = toolResult.toolName
      val activatedSkillName = activatedSkillNameFrom(
        call = call,
        result = toolResult,
      )
      if (!activatedSkillName.isNullOrBlank()) {
        cursor.activeSkillName = activatedSkillName
        cursor.activeSkillActivationSource = activeSkillActivationSourceFrom(toolResult)
        cursor.activeSkillPinned = activeSkillPinnedFrom(toolResult)
      }
    }
    val transcriptEntry = transcriptWithToolResult(
      task = task,
      turn = turn,
      transcript = emptyList(),
      toolCall = call,
      toolResult = toolResult,
    ).single()
    cursor.transcript += transcriptEntry
    if (isResponsesProtocol() && hasResponsesLineage(cursor)) {
      val pendingToolResult = applyFrozenReplayProjection(
        entry = transcriptEntry,
        toolResult = LiteLlmGatewayToolResult(
          toolCallId = call.id,
          toolName = toolResult.toolName,
          content = toolResult.content,
          isError = toolResult.status != AgentToolResultStatus.SUCCESS,
          exitCode = toolResult.exitCode,
          stdout = toolResult.stdout,
          stderr = toolResult.stderr,
          errorCode = toolResult.errorCode,
          errorMessage = toolResult.errorMessage,
          metadata = toolResult.metadata,
        ),
        replayToolResultProjections = cursor.replayToolResultProjections,
      )
      cursor.responsesPendingMessages += LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.TOOL,
        toolResult = pendingToolResult,
      )
    }
    cursor.toolCallCount += 1
    persistWorkingStateSnapshot(
      task = task,
      transcript = cursor.transcript,
    )
    val checkpointMetadata = if (promptSupplement != null) {
      promptCheckpointMetadata(
        boundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        cursor = cursor,
        turnIndex = cursor.turn + 1,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
      )
    } else if (batchActions != null && nextActionIndex != null) {
      promptCheckpointMetadataAfterActionIndex(
        boundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        cursor = cursor,
        batchActions = batchActions,
        nextActionIndex = nextActionIndex,
        requiresSingleActionReminder = requiresSingleActionReminder,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
        localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
        localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
        localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
      )
    } else {
      emptyMap()
    }
    val eventToolResult = if (checkpointMetadata.isEmpty()) {
      toolResult
    } else {
      toolResult.copy(metadata = toolResult.metadata + checkpointMetadata)
    }
    emitToolResultEvent(
      task = task,
      turn = turn,
      call = call,
      result = eventToolResult,
      diagnostics = diagnostics,
    )
    if (promptSupplement != null) {
      ingestToolPromptSupplement(
        task = task,
        turn = turn,
        call = call,
        supplement = promptSupplement,
        cursor = cursor,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
      )
    }
    return toolResult.status == AgentToolResultStatus.SUCCESS && promptSupplement == null
  }

  private fun successfulPromptSupplementFrom(
    toolResult: AgentToolResult,
  ): PromptSupplementPayload? {
    if (toolResult.status != AgentToolResultStatus.SUCCESS) {
      return null
    }
    val text = OpenCrayPromptSupplementMetadata.decodeText(toolResult.metadata).orEmpty()
    val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
      metadata = toolResult.metadata,
      json = config.json,
    )
    if (text.isBlank() && attachments.isEmpty()) {
      return null
    }
    return PromptSupplementPayload(
      text = text,
      attachments = attachments,
    )
  }

  private fun ingestToolPromptSupplement(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    supplement: PromptSupplementPayload,
    cursor: PromptTurnCursor,
    localContinuationContextPrompts: List<String>?,
    localContinuationStableAnchor: String?,
    localContinuationGatewayMessagesEnabled: Boolean,
  ) {
    val checkpoint = supplementCheckpointFor(cursor.transcript)
    cursor.transcript += RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = supplement.text,
      attachments = supplement.attachments,
    )
    if (isResponsesProtocol() && hasResponsesLineage(cursor)) {
      cursor.responsesPendingMessages += LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.USER,
        content = supplement.text,
        attachments = supplement.attachments.map(::liteLlmGatewayAttachmentFor),
      )
    }
    val supplementMetadata = OpenCrayPromptSupplementMetadata.encodeMetadata(
      json = config.json,
      attachments = supplement.attachments,
    ) + promptCheckpointMetadata(
      boundary = OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED,
      cursor = cursor,
      turnIndex = cursor.turn + 1,
      localContinuationContextPrompts = localContinuationContextPrompts,
      localContinuationStableAnchor = localContinuationStableAnchor,
      localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
    )
    eventSink.onRunEvent(
      task = task,
      event = OpenCraySupplementEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        entryId = "tool-supplement-${call.id ?: call.toolName}-${UUID.randomUUID().toString().take(8)}",
        text = supplement.text,
        checkpoint = checkpoint,
        metadata = supplementMetadata,
        emittedAtEpochMs = clock(),
      ),
    )
  }

  private fun unexpectedPromptToolDispatchFailure(
    call: AgentToolCall,
    error: Throwable,
  ): AgentToolResult = AgentToolResult(
    toolName = call.toolName,
    status = AgentToolResultStatus.FAILED,
    content = error.message ?: "${call.toolName} failed.",
    errorCode = "TOOL_EXECUTION_FAILED",
    errorMessage = error.message ?: error::class.java.simpleName,
  )

  private fun dispatchPromptToolCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: PromptTurnCursor,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    allowDuplicateShortCircuit: Boolean,
  ): AgentToolResult = maybeExecuteSkillCall(
    task = task,
    turn = turn,
    call = call,
    transcript = transcript,
    hooks = hooks,
    activeSkillCapsule = activeSkillCapsule,
    cursor = cursor,
  ) ?: gateActiveSkillToolCall(
    call = call,
    activeSkillCapsule = activeSkillCapsule,
  ) ?: gateDisabledSessionToolCall(
    call = call,
    memoryToolsEnabled = cursor.sessionContext.memoryToolsEnabled,
  ) ?: maybeExecuteSubAgentCall(
    task = task,
    turn = turn,
    call = call,
    transcript = transcript,
    hooks = hooks,
    activeSkillCapsule = activeSkillCapsule,
    cursor = cursor,
  ) ?: if (allowDuplicateShortCircuit) {
    maybeShortCircuitDuplicateDiscoveryCall(
      call = call,
      transcript = transcript,
    )
  } else {
    null
  } ?: toolDispatcher.dispatch(task = task, call = call, hooks = hooks)

  private fun maybeExecuteSkillCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: PromptTurnCursor?,
  ): AgentToolResult? {
    return when (toolPolicyKey(call.toolName)) {
      "skill_read" -> null
      "skill_execute" -> executeSkillCall(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
      )

      else -> null
    }
  }

  private fun executeSkillCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: PromptTurnCursor?,
  ): AgentToolResult {
    val skillName = call.arguments.primitiveContent("name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return invalidSkillExecuteResult(call, "skill_execute name must not be blank.")
    val requestedPin = call.arguments.booleanContent("pin") ?: false
    val capsule = resolveActiveSkillCapsule(
      activeSkillName = skillName,
      activationSource = ACTIVATION_SOURCE_SKILL_EXECUTE,
      pinned = requestedPin,
    ) ?: return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.FAILED,
      content = "Skill '$skillName' was not found.",
      errorCode = "SKILL_NOT_FOUND",
      errorMessage = "Skill '$skillName' was not found.",
      metadata = mapOf(
        "skillName" to skillName,
        "activationSource" to ACTIVATION_SOURCE_SKILL_EXECUTE,
        "pinned" to requestedPin.toString(),
      ),
    )
    return if (capsule.executionContext == "fork") {
      executeForkSkillCall(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        hooks = hooks,
        activeSkillCapsule = capsule,
        cursor = cursor,
      )
    } else {
      AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = "Activated inline skill '${capsule.name}'. Its capsule will be injected into subsequent prompt context.",
        metadata = skillActivationMetadata(capsule),
      )
    }
  }

  private fun executeForkSkillCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule,
    cursor: PromptTurnCursor?,
  ): AgentToolResult {
    val prompt = call.arguments.primitiveContent("prompt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "Execute the ${activeSkillCapsule.name} skill workflow for the current parent task."
    val forkCall = AgentToolCall(
      id = call.id,
      toolName = "Task",
      arguments = buildJsonObject {
        put("description", "Skill ${activeSkillCapsule.name}")
        put(
          "prompt",
          buildString {
            appendLine("Use the active skill capsule '${activeSkillCapsule.name}' as the controlling workflow.")
            appendLine()
            append(prompt)
          }.trim(),
        )
        put("subagent_type", call.arguments.primitiveContent("subagent_type")?.trim()?.takeIf(String::isNotBlank) ?: "general-purpose")
        put("context_mode", call.arguments.primitiveContent("context_mode")?.trim()?.takeIf(String::isNotBlank) ?: "delegated")
      },
      reason = call.reason,
    )
    val prepared = when (
      val result = prepareSubAgentDelegation(
        task = task,
        turn = turn,
        call = forkCall,
        activeSkillCapsule = activeSkillCapsule,
        toolName = "skill_execute",
      )
    ) {
      is PreparedSubAgentDelegationResult.Invalid -> return result.result.copy(
        toolName = call.toolName,
        metadata = result.result.metadata + skillActivationMetadata(activeSkillCapsule),
      )

      is PreparedSubAgentDelegationResult.Ready -> result.delegation
    }
    val handles = subAgentHandleRegistry(cursor)
    val handle = createSubAgentHandle(
      task = task,
      prepared = prepared,
      agentId = call.arguments.primitiveContent("agent_id")?.trim()?.takeIf(String::isNotBlank),
    ).also { createdHandle ->
      handles[createdHandle.agentId] = createdHandle
      config.subAgentExecutionCoordinator.upsertHandle(createdHandle)
    }
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = OpenCraySubAgentPhase.STARTED,
      childTask = prepared.childTask,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      summary = null,
      snapshot = SubAgentExecutionSnapshot.running(),
      liveContext = handle.childLiveContext,
    )
    val execution = executeSubAgentHandleLifecycle(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      profile = prepared.profile,
      handles = handles,
      approvalContinuation = takePendingApprovalContinuation(handle, handles),
      emitResumedPhaseWithoutApproval = false,
      retainTerminalHandle = cursor != null,
    )
    val childToolResult = childResultToTaskToolResult(
      call = forkCall,
      handle = execution.handle,
      delegationPlan = prepared.delegationPlan,
      childResult = execution.childResult,
      compressedChildResult = execution.handle.snapshot,
    )
    return childToolResult.copy(
      toolName = call.toolName,
      metadata = childToolResult.metadata + skillActivationMetadata(activeSkillCapsule),
    )
  }

  private fun invalidSkillExecuteResult(
    call: AgentToolCall,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = call.toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "INVALID_SKILL_EXECUTE",
    errorMessage = message,
  )

  private fun skillActivationMetadata(
    capsule: ActiveSkillCapsule,
  ): Map<String, String> = buildMap {
    put("skillName", capsule.name)
    put("relativePath", capsule.relativePath)
    put("executionContext", capsule.executionContext)
    put("activationSource", capsule.activationSource)
    put("pinned", capsule.pinned.toString())
  }

  private fun normalizeToolCallIds(
    actions: List<AgentModelAction>,
    cursor: PromptTurnCursor,
  ): List<AgentModelAction> = actions.map { action ->
    when (action) {
      is AgentModelAction.ToolCall -> AgentModelAction.ToolCall(
        call = action.call.ensureToolCallId(cursor),
      )

      else -> action
    }
  }

  private fun AgentToolCall.ensureToolCallId(cursor: PromptTurnCursor): AgentToolCall {
    if (!id.isNullOrBlank()) {
      return this
    }
    val syntheticId = "oc-call-${cursor.nextSyntheticToolCallSequence}"
    cursor.nextSyntheticToolCallSequence += 1
    return copy(id = syntheticId)
  }

  private fun seededConversation(task: AgentTask): List<RuntimeConversationMessage> {
    val seeded = config.sessionContext.conversation.toMutableList()
    val normalizedInput = promptUserText(task)
    val attachments = promptUserAttachments(task)
    if (normalizedInput.isBlank() && attachments.isEmpty()) {
      return seeded
    }
    val lastEntry = seeded.lastOrNull()
    if (
      lastEntry?.role == RuntimeConversationRole.USER &&
      lastEntry.content == normalizedInput &&
      promptAttachmentsEquivalent(
        existing = lastEntry.attachments,
        incoming = attachments,
      )
    ) {
      return seeded
    }
    seeded += RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = normalizedInput,
      attachments = attachments,
    )
    return seeded
  }

  private fun promptAttachmentsEquivalent(
    existing: List<RuntimeConversationAttachment>,
    incoming: List<RuntimeConversationAttachment>,
  ): Boolean {
    if (existing.size != incoming.size) {
      return false
    }
    return existing.zip(incoming).all { (seededAttachment, incomingAttachment) ->
      seededAttachment.kind == incomingAttachment.kind &&
        promptAttachmentFieldEquivalent(
          seededAttachment.attachmentId,
          incomingAttachment.attachmentId,
        ) &&
        promptAttachmentFieldEquivalent(
          seededAttachment.displayName,
          incomingAttachment.displayName,
        ) &&
        promptAttachmentFieldEquivalent(
          seededAttachment.filePath,
          incomingAttachment.filePath,
        ) &&
        promptAttachmentFieldEquivalent(
          seededAttachment.mimeType,
          incomingAttachment.mimeType,
        ) &&
        promptAttachmentFieldEquivalent(
          seededAttachment.transcriptText,
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

  private fun promptUserText(task: AgentTask): String =
    if (task.metadata.containsKey(PROMPT_USER_TEXT_METADATA_KEY)) {
      task.metadata[PROMPT_USER_TEXT_METADATA_KEY]?.trim().orEmpty()
    } else {
      task.input.trim()
    }

  private fun promptUserAttachments(task: AgentTask): List<RuntimeConversationAttachment> =
    task.metadata[PROMPT_RUNTIME_ATTACHMENTS_JSON_METADATA_KEY]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { encoded ->
        runCatching {
          config.json.decodeFromString(
            ListSerializer(RuntimeConversationAttachment.serializer()),
            encoded,
          )
        }.getOrDefault(emptyList())
      }
      ?: emptyList()

  private fun liteLlmGatewayAttachmentFor(
    attachment: RuntimeConversationAttachment,
  ): LiteLlmGatewayAttachment =
    LiteLlmGatewayAttachment(
      attachmentId = attachment.attachmentId,
      kind = liteLlmGatewayAttachmentKindFor(attachment.kind),
      displayName = attachment.displayName,
      filePath = attachment.filePath,
      mimeType = attachment.mimeType,
      transcriptText = attachment.transcriptText,
    )

  private fun liteLlmGatewayAttachmentKindFor(
    kind: RuntimeConversationAttachmentKind,
  ): LiteLlmGatewayAttachmentKind =
    when (kind) {
      RuntimeConversationAttachmentKind.IMAGE -> LiteLlmGatewayAttachmentKind.IMAGE
      RuntimeConversationAttachmentKind.VOICE -> LiteLlmGatewayAttachmentKind.VOICE
      RuntimeConversationAttachmentKind.AUDIO -> LiteLlmGatewayAttachmentKind.AUDIO
      RuntimeConversationAttachmentKind.FILE -> LiteLlmGatewayAttachmentKind.FILE
    }

  private fun approvalRequiredPromptToolResult(
    task: AgentTask,
    startedAt: Long,
    finishedAt: Long,
    gatewayResult: LiteLlmGatewayResult?,
    contextReport: ContextAssemblyReport?,
    turn: Int,
    toolCallCount: Int,
    transcript: List<RuntimeConversationMessage>,
    pendingActions: List<AgentModelAction>,
    nextActionIndex: Int,
    requiresSingleActionReminder: Boolean,
    toolCall: AgentToolCall,
    toolResult: AgentToolResult,
    activeSkillName: String?,
    activeSkillActivationSource: String?,
    activeSkillPinned: Boolean,
    responsesPreviousResponseId: String?,
    responsesProviderLineageId: String?,
    responsesLineageTrusted: Boolean,
    responsesContinuationShape: ResponsesContinuationShape?,
    responsesPendingMessages: List<LiteLlmGatewayMessage>,
    replayToolResultProjections: Map<String, FrozenToolResultReplayProjection>,
    subAgentHandles: List<SubAgentHandleState>,
    hooks: RuntimeExecutionHooks,
    diagnostics: PromptRunDiagnostics,
  ): ExecutionResult {
    persistWorkingStateSnapshot(
      task = task,
      transcript = transcriptWithToolResult(
        task = task,
        turn = turn,
        transcript = transcript,
        toolCall = toolCall,
        toolResult = toolResult,
      ),
    )
    val approvalResult = toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = finishedAt,
      json = config.json,
    )
    hooks.requestSuspend(
      SuspensionRequest(
        reasonCode = approvalResult.errorCode ?: "APPROVAL_REQUIRED",
        detail = approvalResult.errorMessage,
      ),
    )
    return approvalResult.copy(
      metadata = approvalResult.metadata +
        buildResultMetadata(
          gatewayResult = gatewayResult,
          turn = turn,
          toolCallCount = toolCallCount + 1,
          responseFormat = "tool_approval_required",
          contextReport = contextReport,
          diagnostics = diagnostics,
        ) +
        toolReasonMetadata(toolCall) +
        OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = OpenCrayPromptResumeState(
            transcript = transcript,
            turnIndex = turn,
            toolCallCount = toolCallCount + 1,
            pendingActions = pendingActions.map { action -> action.toSerializableModelAction() },
            nextActionIndex = nextActionIndex,
            requiresSingleActionReminder = requiresSingleActionReminder,
            activeSkillName = activeSkillName,
            activeSkillActivationSource = activeSkillActivationSource,
            activeSkillPinned = activeSkillPinned,
            responsesPreviousResponseId = responsesPreviousResponseId,
            responsesProviderLineageId = responsesProviderLineageId,
            responsesLineageTrusted = responsesLineageTrusted,
            responsesContinuationShape = responsesContinuationShape?.toSerializable(),
            responsesPendingMessages = responsesPendingMessages.map(OpenCraySerializableGatewayMessage::from),
            replayToolResultProjections = replayToolResultProjections.toSortedMap(),
            subAgentHandles = subAgentHandles,
          ),
          json = config.json,
        ),
    )
  }

  private fun persistWorkingStateSnapshot(
    task: AgentTask,
    transcript: List<RuntimeConversationMessage>,
  ) {
    val seededWorkingState = config.workingStateStore.snapshot()
      .takeUnless { state -> state.isEmpty }
      ?: config.sessionContext.workingState
    val resolvedWorkingState = workingStateSupport.resolve(
      task = task,
      runId = runIdFor(task),
      seededState = seededWorkingState,
      resumeContext = WorkingStateResumeContext.from(
        promptResumeState = config.promptResumeState,
        checkpointBoundary = config.promptResumeCheckpointBoundary,
      ),
      recentActionEntries = recentToolObservationSupport.workingStateEntries(transcript),
      decisionEntries = recentToolObservationSupport.decisionEntries(transcript),
      blockerEntries = recentToolObservationSupport.blockerEntries(transcript),
      recentObservationLines = recentToolObservationSupport.summaryLines(transcript),
      todoSnapshot = toolDispatcher.todoSnapshot(),
    )
    config.workingStateStore.replace(resolvedWorkingState.state)
  }

  private fun transcriptWithToolResult(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    toolCall: AgentToolCall,
    toolResult: AgentToolResult,
  ): List<RuntimeConversationMessage> = transcript + RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    kind = RuntimeConversationMessageKind.TOOL_RESULT,
    content = buildToolResultTranscriptEntry(
      task = task,
      turn = turn,
      call = toolCall,
      result = toolResult,
    ),
    toolResult = RuntimeConversationToolResult(
      toolCallId = toolCall.id,
      toolName = toolResult.toolName,
      status = toolResult.status.name.lowercase(),
      isError = toolResult.status != AgentToolResultStatus.SUCCESS,
    ),
  )

  private fun emitBuiltinWebSearchObservations(
    task: AgentTask,
    turn: Int,
    cursor: PromptTurnCursor,
    gatewayResult: LiteLlmGatewayResult,
    diagnostics: PromptRunDiagnostics,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ) {
    val observations = decodeBuiltinWebSearchObservations(gatewayResult)
    if (observations.isEmpty()) {
      return
    }
    observations.forEach { observation ->
      val call = syntheticProviderNativeWebSearchCall(cursor, observation)
      val result = providerNativeWebSearchResult(observation)
      val checkpointMetadata = promptCheckpointMetadata(
        boundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        cursor = cursor,
        turnIndex = cursor.turn + 1,
        localContinuationContextPrompts = localContinuationContextPrompts,
        localContinuationStableAnchor = localContinuationStableAnchor,
        localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
        localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
        localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
        localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
      )
      val eventToolResult = if (checkpointMetadata.isEmpty()) {
        result
      } else {
        result.copy(metadata = result.metadata + checkpointMetadata)
      }
      announcePromptToolCall(
        task = task,
        turn = turn,
        call = call,
        cursor = cursor,
        diagnostics = diagnostics,
        suppressToolCallEvent = false,
      )
      cursor.transcript += RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        kind = RuntimeConversationMessageKind.TOOL_RESULT,
        content = buildToolResultTranscriptEntry(
          task = task,
          turn = turn,
          call = call,
          result = eventToolResult,
        ),
        toolResult = RuntimeConversationToolResult(
          toolCallId = call.id,
          toolName = eventToolResult.toolName,
          status = eventToolResult.status.name.lowercase(),
          isError = eventToolResult.status != AgentToolResultStatus.SUCCESS,
        ),
      )
      emitToolResultEvent(
        task = task,
        turn = turn,
        call = call,
        result = eventToolResult,
        diagnostics = diagnostics,
      )
    }
  }

  private fun decodeBuiltinWebSearchObservations(
    gatewayResult: LiteLlmGatewayResult,
  ): List<LiteLlmBuiltinWebSearchObservation> {
    val raw = gatewayResult.metadata[LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return runCatching {
      config.json.decodeFromString(
        ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
        raw,
      )
    }.getOrDefault(emptyList())
  }

  private fun syntheticProviderNativeWebSearchCall(
    cursor: PromptTurnCursor,
    observation: LiteLlmBuiltinWebSearchObservation,
  ): AgentToolCall = AgentToolCall(
    id = "oc-call-${cursor.nextSyntheticToolCallSequence++}",
    toolName = "WebSearch",
    arguments = providerNativeWebSearchArguments(observation),
  )

  private fun providerNativeWebSearchArguments(
    observation: LiteLlmBuiltinWebSearchObservation,
  ): JsonObject = buildJsonObject {
    put("operation", observation.actionType)
    observation.queries.firstOrNull()?.let { query -> put("query", query) }
    if (observation.queries.isNotEmpty()) {
      put(
        "queries",
        JsonArray(observation.queries.map(::JsonPrimitive)),
      )
    }
    if (observation.domains.isNotEmpty()) {
      put(
        "domains",
        JsonArray(observation.domains.map(::JsonPrimitive)),
      )
    }
    observation.url?.let { url -> put("url", url) }
    observation.findText?.let { text -> put("text", text) }
  }

  private fun providerNativeWebSearchResult(
    observation: LiteLlmBuiltinWebSearchObservation,
  ): AgentToolResult {
    val status = providerNativeWebSearchResultStatus(observation)
    val content = providerNativeWebSearchResultContent(observation)
    val errorCode = if (status == AgentToolResultStatus.SUCCESS) {
      null
    } else {
      "PROVIDER_MANAGED_WEB_SEARCH_FAILED"
    }
    val errorMessage = if (status == AgentToolResultStatus.SUCCESS) {
      null
    } else {
      content
    }
    return AgentToolResult(
      toolName = "WebSearch",
      status = status,
      content = content,
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = buildMap {
        put(ProviderNativeWebSearchSupport.RESULT_METADATA_PROVIDER_MANAGED, "true")
        put(ProviderNativeWebSearchSupport.RESULT_METADATA_OPERATION, observation.actionType)
        observation.status
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { statusValue ->
            put(ProviderNativeWebSearchSupport.RESULT_METADATA_STATUS, statusValue)
          }
        observation.queries.firstOrNull()?.let { query -> put("query", query) }
        if (observation.queries.isNotEmpty()) {
          put("queries", observation.queries.joinToString(separator = ","))
        }
        if (observation.domains.isNotEmpty()) {
          put("domains", observation.domains.joinToString(separator = ","))
        }
        observation.url?.let { url -> put("url", url) }
        observation.findText?.let { text -> put("text", text) }
        if (observation.sources.isNotEmpty()) {
          put("sourceCount", observation.sources.size.toString())
          put(
            "sourceUrls",
            observation.sources.joinToString(separator = ",") { source -> source.url },
          )
        }
      },
    )
  }

  private fun providerNativeWebSearchResultStatus(
    observation: LiteLlmBuiltinWebSearchObservation,
  ): AgentToolResultStatus = when (observation.status?.trim()?.lowercase()) {
    "failed",
    "error",
    "incomplete",
    "cancelled",
    -> AgentToolResultStatus.FAILED

    else -> AgentToolResultStatus.SUCCESS
  }

  private fun providerNativeWebSearchResultContent(
    observation: LiteLlmBuiltinWebSearchObservation,
  ): String = buildString {
    when (observation.actionType.trim().lowercase()) {
      "open_page" -> {
        append("Provider-native web search opened a page")
        observation.url?.let { url ->
          append(": ")
          append(url)
        }
        append(".")
      }

      "find_in_page" -> {
        append("Provider-native web search searched within a page")
        observation.url?.let { url ->
          append(": ")
          append(url)
        }
        observation.findText?.let { text ->
          append(" for \"")
          append(text)
          append("\"")
        }
        append(".")
      }

      else -> {
        append("Provider-native web search ran")
        observation.queries.firstOrNull()?.let { query ->
          append(" for \"")
          append(query)
          append("\"")
        }
        if (observation.domains.isNotEmpty()) {
          append(" within ")
          append(observation.domains.joinToString(separator = ", "))
        }
        append(".")
      }
    }
    observation.status
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { status ->
        appendLine()
        append("Status: ")
        append(status)
      }
    if (observation.sources.isNotEmpty()) {
      appendLine()
      appendLine("Sources:")
      observation.sources.forEach { source ->
        append("- ")
        append(source.title?.takeIf(String::isNotBlank) ?: source.url)
        append(" - ")
        append(source.url)
        appendLine()
      }
    }
  }.trim()

  private fun hasTurnBudgetRemaining(turn: Int): Boolean =
    config.maxTurns == 0 || turn < config.maxTurns

  private fun applyTurnStartSupplements(
    task: AgentTask,
    turn: Int,
    cursor: PromptTurnCursor,
  ) {
    val supplements = config.supplementInputProvider(runIdFor(task), task.id)
      .sortedBy(OpenCraySupplementInput::createdAtEpochMs)
    if (supplements.isEmpty()) {
      return
    }
    val checkpoint = supplementCheckpointFor(cursor.transcript)
    supplements.forEach { supplement ->
      val text = supplement.text.trim().takeIf(String::isNotBlank) ?: return@forEach
      cursor.transcript += RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = text,
      )
      if (isResponsesProtocol() && hasResponsesLineage(cursor)) {
        cursor.responsesPendingMessages += LiteLlmGatewayMessage(
          role = LiteLlmGatewayMessageRole.USER,
          content = text,
        )
      }
      eventSink.onRunEvent(
        task = task,
        event = OpenCraySupplementEvent(
          runId = runIdFor(task),
          taskId = task.id,
          turn = turn,
          entryId = supplement.entryId,
          text = text,
          checkpoint = checkpoint,
          metadata = promptCheckpointMetadata(
            boundary = OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED,
            cursor = cursor,
            turnIndex = cursor.turn,
          ),
          emittedAtEpochMs = clock(),
        ),
      )
    }
  }

  private fun supplementCheckpointFor(
    transcript: List<RuntimeConversationMessage>,
  ): String = transcript.lastOrNull()
    ?.takeIf { message ->
      message.role == RuntimeConversationRole.TOOL &&
        message.kind == RuntimeConversationMessageKind.TOOL_RESULT
    }
    ?.let { SUPPLEMENT_CHECKPOINT_POST_TOOL_PRE_MODEL }
    ?: SUPPLEMENT_CHECKPOINT_TURN_START

  private fun remainingTurnBudget(turn: Int): Int? =
    config.maxTurns
      .takeIf { configuredLimit -> configuredLimit > 0 }
      ?.let { configuredLimit -> (configuredLimit - turn).coerceAtLeast(0) }

  private fun isFinalAnswerOnlyTurn(turn: Int): Boolean = remainingTurnBudget(turn) == 1

  private fun promptConversationForTurn(
    transcript: List<RuntimeConversationMessage>,
    turn: Int,
    legacyJsonFallbackEnabled: Boolean,
  ): List<RuntimeConversationMessage> {
    val reminder = turnBudgetReminderFor(
      turn = turn,
      legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
    ) ?: return transcript
    if (transcript.lastOrNull() == reminder) {
      return transcript
    }
    return transcript + reminder
  }

  private fun enforcedSystemPromptForTurn(
    systemPrompt: String?,
    turn: Int,
    legacyJsonFallbackEnabled: Boolean,
  ): String? {
    val appendix = when (remainingTurnBudget(turn)) {
      1 -> finalTurnSystemPromptAppendix(legacyJsonFallbackEnabled)
      2 -> PENULTIMATE_TURN_SYSTEM_PROMPT_APPENDIX
      else -> null
    } ?: return systemPrompt
    return listOfNotNull(systemPrompt?.trim()?.takeIf(String::isNotBlank), appendix)
      .joinToString(separator = "\n\n")
  }

  private fun turnBudgetReminderFor(
    turn: Int,
    legacyJsonFallbackEnabled: Boolean,
  ): RuntimeConversationMessage? = when (remainingTurnBudget(turn)) {
    1 -> RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildFinalAnswerRequiredObservation(legacyJsonFallbackEnabled),
    )

    2 -> RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildFinalAnswerSoonObservation(),
    )

    else -> null
  }

  private fun emitLifecycleEvent(
    task: AgentTask,
    phase: OpenCrayRunLifecyclePhase,
    result: ExecutionResult? = null,
    errorCode: String? = null,
    errorMessage: String? = null,
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayLifecycleEvent(
        runId = runIdFor(task),
        taskId = task.id,
        phase = phase,
        status = result?.status,
        errorCode = result?.errorCode ?: errorCode,
        errorMessage = result?.errorMessage ?: errorMessage,
        turn = result?.metadata?.get("turnCount")?.toIntOrNull()?.minus(1),
        emittedAtEpochMs = clock(),
      ),
    )
  }

  private fun emitAssistantEvent(
    task: AgentTask,
    turn: Int,
    text: String,
    responseFormat: String,
    isFinal: Boolean,
    metadata: Map<String, String> = emptyMap(),
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayAssistantEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        text = text,
        responseFormat = responseFormat.takeIf(String::isNotBlank),
        isFinal = isFinal,
        metadata = metadata,
        emittedAtEpochMs = clock(),
      ),
    )
  }

  private fun emitCommentaryEvent(
    task: AgentTask,
    turn: Int,
    text: String,
    stage: String?,
    metadata: Map<String, String> = emptyMap(),
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayAssistantEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        text = text,
        isFinal = false,
        stage = stage,
        metadata = metadata,
        emittedAtEpochMs = clock(),
      ),
    )
  }

  private fun emitResponsesContinuationRecoveryEvent(
    task: AgentTask,
    turn: Int,
    reason: String,
  ) {
    emitCommentaryEvent(
      task = task,
      turn = turn,
      text = responsesContinuationRecoveryText(reason),
      stage = "responses_recovery",
    )
  }

  private fun responsesContinuationRecoveryText(reason: String): String = when (reason) {
    "missing_tool_call_for_output" ->
      "Responses continuation lost the pending tool call; retrying this turn with full transcript replay."
    "previous_response_mismatch" ->
      "Responses continuation no longer matched provider state; retrying this turn with full transcript replay."
    else ->
      "Responses continuation failed; retrying this turn with full transcript replay."
  }

  private fun emitMemoryRetrievalEvent(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    result: AgentToolResult,
  ) {
    memoryRetrievalTraceFor(call = call, result = result)
      ?.let { trace ->
        eventSink.onRunEvent(
          task = task,
          event = OpenCrayMemoryRetrievalEvent(
            runId = runIdFor(task),
            taskId = task.id,
            turn = turn,
            toolName = trace.toolName,
            operation = trace.operation,
            surface = trace.surface,
            query = trace.query,
            queryTerms = trace.queryTerms,
            resultCount = trace.resultCount,
            corpusFileCount = trace.corpusFileCount,
            recordIds = trace.recordIds,
            paths = trace.paths,
            lineRanges = trace.lineRanges,
            path = trace.path,
            fromLine = trace.fromLine,
            returnedLineCount = trace.returnedLineCount,
            totalLineCount = trace.totalLineCount,
            emittedAtEpochMs = clock(),
          ),
        )
      }
  }

  private fun memoryRetrievalTraceFor(
    call: AgentToolCall,
    result: AgentToolResult,
  ): MemoryRetrievalTrace? = when (call.toolName) {
    "memory_search" -> MemoryRetrievalTrace(
      operation = "search",
      toolName = call.toolName,
      surface = result.metadata["surface"]?.takeIf(String::isNotBlank),
      query = result.metadata["query"]?.takeIf(String::isNotBlank),
      queryTerms = splitCsvMetadata(result.metadata["queryTerms"]),
      resultCount = result.metadata["resultCount"]?.toIntOrNull(),
      corpusFileCount = result.metadata["corpusFileCount"]?.toIntOrNull(),
      recordIds = splitCsvMetadata(result.metadata["recordIds"]),
      paths = splitCsvMetadata(result.metadata["paths"]),
      lineRanges = splitCsvMetadata(result.metadata["lineRanges"]),
    ).takeIf { trace ->
      trace.query != null ||
        trace.queryTerms.isNotEmpty() ||
        trace.resultCount != null ||
        trace.corpusFileCount != null ||
        trace.recordIds.isNotEmpty() ||
        trace.paths.isNotEmpty()
    }

    "memory_get" -> MemoryRetrievalTrace(
      operation = "get",
      toolName = call.toolName,
      surface = result.metadata["surface"]?.takeIf(String::isNotBlank),
      recordIds = splitCsvMetadata(result.metadata["recordIds"]),
      path = result.metadata["path"]?.takeIf(String::isNotBlank),
      fromLine = result.metadata["from"]?.toIntOrNull(),
      returnedLineCount = result.metadata["returnedLineCount"]?.toIntOrNull(),
      totalLineCount = result.metadata["totalLineCount"]?.toIntOrNull(),
    ).takeIf { trace ->
      trace.recordIds.isNotEmpty() ||
        trace.path != null ||
        trace.fromLine != null ||
        trace.returnedLineCount != null ||
        trace.totalLineCount != null
    }

    "session_search" -> MemoryRetrievalTrace(
      operation = "search",
      toolName = call.toolName,
      surface = result.metadata["surface"]?.takeIf(String::isNotBlank),
      query = result.metadata["query"]?.takeIf(String::isNotBlank),
      queryTerms = splitCsvMetadata(result.metadata["queryTerms"]),
      resultCount = result.metadata["resultCount"]?.toIntOrNull(),
      corpusFileCount = result.metadata["corpusFileCount"]?.toIntOrNull(),
      recordIds = splitCsvMetadata(result.metadata["recordIds"]),
      paths = splitCsvMetadata(result.metadata["paths"]),
      lineRanges = splitCsvMetadata(result.metadata["lineRanges"]),
    ).takeIf { trace ->
      trace.query != null ||
        trace.queryTerms.isNotEmpty() ||
        trace.resultCount != null ||
        trace.corpusFileCount != null ||
        trace.recordIds.isNotEmpty() ||
        trace.paths.isNotEmpty()
    }

    "session_get" -> MemoryRetrievalTrace(
      operation = "get",
      toolName = call.toolName,
      surface = result.metadata["surface"]?.takeIf(String::isNotBlank),
      recordIds = splitCsvMetadata(result.metadata["recordIds"]),
      path = result.metadata["path"]?.takeIf(String::isNotBlank),
      fromLine = result.metadata["from"]?.toIntOrNull(),
      returnedLineCount = result.metadata["returnedLineCount"]?.toIntOrNull(),
      totalLineCount = result.metadata["totalLineCount"]?.toIntOrNull(),
    ).takeIf { trace ->
      trace.recordIds.isNotEmpty() ||
        trace.path != null ||
        trace.fromLine != null ||
        trace.returnedLineCount != null ||
        trace.totalLineCount != null
    }

    "past_session_search" -> MemoryRetrievalTrace(
      operation = "search",
      toolName = call.toolName,
      surface = result.metadata["surface"]?.takeIf(String::isNotBlank),
      query = result.metadata["query"]?.takeIf(String::isNotBlank),
      queryTerms = splitCsvMetadata(result.metadata["queryTerms"]),
      resultCount = result.metadata["resultCount"]?.toIntOrNull(),
      corpusFileCount = result.metadata["corpusFileCount"]?.toIntOrNull(),
      recordIds = splitCsvMetadata(result.metadata["recordIds"]),
      paths = splitCsvMetadata(result.metadata["paths"]),
      lineRanges = splitCsvMetadata(result.metadata["lineRanges"]),
    ).takeIf { trace ->
      trace.query != null ||
        trace.queryTerms.isNotEmpty() ||
        trace.resultCount != null ||
        trace.corpusFileCount != null ||
        trace.recordIds.isNotEmpty() ||
        trace.paths.isNotEmpty()
    }

    "past_session_get" -> MemoryRetrievalTrace(
      operation = "get",
      toolName = call.toolName,
      surface = result.metadata["surface"]?.takeIf(String::isNotBlank),
      recordIds = splitCsvMetadata(result.metadata["recordIds"]),
      path = result.metadata["path"]?.takeIf(String::isNotBlank),
      fromLine = result.metadata["from"]?.toIntOrNull(),
      returnedLineCount = result.metadata["returnedLineCount"]?.toIntOrNull(),
      totalLineCount = result.metadata["totalLineCount"]?.toIntOrNull(),
    ).takeIf { trace ->
      trace.recordIds.isNotEmpty() ||
        trace.path != null ||
        trace.fromLine != null ||
        trace.returnedLineCount != null ||
        trace.totalLineCount != null
    }

    else -> null
  }

  private fun splitCsvMetadata(raw: String?): List<String> = raw
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)

  private fun nextSyntheticToolCallSequence(
    transcript: List<RuntimeConversationMessage>,
  ): Int = transcript
    .asSequence()
    .mapNotNull(::syntheticToolCallSequence)
    .maxOrNull()
    ?.plus(1)
    ?: 1

  private fun syntheticToolCallSequence(message: RuntimeConversationMessage): Int? {
    val toolCallId = when (message.kind) {
      RuntimeConversationMessageKind.TOOL_CALL -> message.toolCall?.id
      RuntimeConversationMessageKind.TOOL_RESULT -> message.toolResult?.toolCallId
      RuntimeConversationMessageKind.PLAIN,
      RuntimeConversationMessageKind.COMMENTARY,
      -> null
    } ?: when (message.role) {
      RuntimeConversationRole.ASSISTANT -> parseToolCallTranscriptEntry(message.content)?.id
      RuntimeConversationRole.TOOL -> parseToolResultObservation(message)?.toolCallId
      else -> null
    }
    return toolCallId
      ?.takeIf { id -> id.startsWith("oc-call-") }
      ?.removePrefix("oc-call-")
      ?.toIntOrNull()
  }

  private fun buildToolCallTranscriptEntry(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
  ): String = config.json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
      put("run_id", runIdFor(task))
      put("task_id", task.id)
      put("turn", turn)
      call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
      put("tool_name", call.toolName)
      call.reason
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reason -> put("reason", reason) }
      put("arguments", call.arguments)
    },
  )

  private fun buildToolResultTranscriptEntry(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    result: AgentToolResult,
  ): String = config.json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
      val normalizedMetadata = OpenCrayPromptResumeMetadata.sanitizeToolResultMetadata(result.metadata)
      put("run_id", runIdFor(task))
      put("task_id", task.id)
      put("turn", turn)
      call.id?.let { toolCallId -> put("tool_call_id", toolCallId) }
      put("tool_name", result.toolName)
      put("status", result.status.name.lowercase())
      put("content", result.content)
      result.exitCode?.let { exitCode -> put("exit_code", exitCode) }
      if (result.stdout.isNotBlank()) {
        put("stdout", result.stdout)
      }
      if (result.stderr.isNotBlank()) {
        put("stderr", result.stderr)
      }
      result.errorCode?.let { errorCode -> put("error_code", errorCode) }
      result.errorMessage?.let { errorMessage -> put("error_message", errorMessage) }
      put(
        "metadata",
        buildJsonObject {
          normalizedMetadata.toSortedMap().forEach { (key, value) -> put(key, value) }
        },
      )
    },
  )

  private fun buildCommentaryTranscriptEntry(
    task: AgentTask,
    turn: Int,
    commentary: AgentModelAction.Commentary,
  ): String = config.json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
      put("event_kind", "assistant_phase")
      put("phase", "commentary")
      put("run_id", runIdFor(task))
      put("task_id", task.id)
      put("turn", turn)
      put("text", commentary.text)
      commentary.stage?.let { stage -> put("stage", stage) }
    },
  )

  private fun buildGatewayMessages(
    frontContextPrompts: List<String>,
    transcript: List<RuntimeConversationMessage>,
    replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>? = null,
  ): List<LiteLlmGatewayMessage> {
    val messages = mutableListOf<LiteLlmGatewayMessage>()
    frontContextPrompts
      .map(String::trim)
      .filter(String::isNotBlank)
      .forEach { prompt ->
        messages += LiteLlmGatewayMessage(
          role = LiteLlmGatewayMessageRole.USER,
          content = prompt,
        )
      }
    var syntheticToolCallIndex = nextSyntheticToolCallSequence(transcript) - 1
    var pendingToolCallId: String? = null
    transcript.forEach { entry ->
      when (entry.role) {
        RuntimeConversationRole.SYSTEM -> {
          messages += LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.USER,
            content = "[system]\n${entry.content}",
          )
        }

        RuntimeConversationRole.USER -> {
          messages += LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.USER,
            content = entry.content,
            attachments = entry.attachments.map { attachment ->
              liteLlmGatewayAttachmentFor(attachment)
            },
          )
        }

        RuntimeConversationRole.ASSISTANT -> {
          if (entry.kind == RuntimeConversationMessageKind.COMMENTARY) {
            val commentary = entry.commentary?.text?.trim()?.takeIf(String::isNotBlank)
              ?: entry.content.trim().takeIf(String::isNotBlank)
            commentary?.let { commentaryText ->
              messages += LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.ASSISTANT,
                content = commentaryText,
                assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase()
                  ?: LiteLlmAssistantPhase.COMMENTARY,
              )
            }
            return@forEach
          }
          val toolCall = runtimeToolCallFor(entry)
          if (toolCall != null) {
            val normalizedToolCall = toolCall.id
              ?.takeIf(String::isNotBlank)
              ?.let { existingId -> toolCall.copy(id = existingId) }
              ?: toolCall.copy(
                id = "oc-call-${++syntheticToolCallIndex}",
              )
            pendingToolCallId = normalizedToolCall.id
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = normalizedToolCall.id,
                  toolName = normalizedToolCall.toolName,
                  arguments = normalizedToolCall.arguments,
                  reason = normalizedToolCall.reason,
                ),
              ),
              assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase(),
            )
          } else {
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              content = entry.content,
              assistantPhase = entry.assistantPhase?.toLiteLlmAssistantPhase(),
            )
          }
        }

        RuntimeConversationRole.TOOL -> {
          if (entry.kind == RuntimeConversationMessageKind.COMMENTARY) {
            val commentaryText = entry.commentary?.text?.trim()?.takeIf(String::isNotBlank)
              ?: entry.content.trim().takeIf(String::isNotBlank)
            commentaryText?.let { commentary ->
              messages += LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.ASSISTANT,
                content = commentary,
                assistantPhase = LiteLlmAssistantPhase.COMMENTARY,
              )
            }
            return@forEach
          }
          val observation = runtimeToolResultFor(entry)
          if (observation != null) {
            val canonicalToolResult = LiteLlmGatewayToolResult(
              toolCallId = observation.toolCallId ?: pendingToolCallId,
              toolName = observation.toolName,
              content = observation.content,
              structuredContent = observation.structuredContent,
              isError = observation.isError,
              exitCode = observation.exitCode,
              stdout = observation.stdout,
              stderr = observation.stderr,
              errorCode = observation.errorCode,
              errorMessage = observation.errorMessage,
              metadata = observation.metadata,
            )
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.TOOL,
              toolResult = applyFrozenReplayProjection(
                entry = entry,
                toolResult = canonicalToolResult,
                replayToolResultProjections = replayToolResultProjections,
              ),
            )
            pendingToolCallId = null
          } else {
            projectedPlainToolTranscriptContent(entry)?.let { toolContent ->
              messages += LiteLlmGatewayMessage(
                role = LiteLlmGatewayMessageRole.USER,
                content = "[tool]\n$toolContent",
              )
            }
          }
        }
      }
    }
    return messages
  }
  private fun applyFrozenReplayProjection(
    entry: RuntimeConversationMessage,
    toolResult: LiteLlmGatewayToolResult,
    replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>?,
  ): LiteLlmGatewayToolResult {
    if (replayToolResultProjections == null) {
      return toolResult
    }
    val projectionKey = toolResultReplayProjector.projectionKey(
      entry = entry,
      toolResult = toolResult,
    )
    replayToolResultProjections[projectionKey]?.let { projection ->
      return projection.restoredToolResult()
    }
    val projection = toolResultReplayProjector.maybeProject(
      entry = entry,
      toolResult = toolResult,
    ) ?: return toolResult
    replayToolResultProjections.putIfAbsent(
      projection.projectionKey,
      projection,
    )
    return replayToolResultProjections[projection.projectionKey]
      ?.restoredToolResult()
      ?: projection.restoredToolResult()
  }
  private fun projectedPlainToolTranscriptContent(
    entry: RuntimeConversationMessage,
  ): String? {
    val content = entry.content.trim().takeIf(String::isNotBlank) ?: return null
    if (entry.role != RuntimeConversationRole.TOOL || entry.kind != RuntimeConversationMessageKind.PLAIN) {
      return content
    }
    return runCatching {
      com.opencray.runtime.context.ContextPruner()
        .prune(listOf(entry.copy(content = content)))
        .messages
        .single()
        .content
        .trim()
    }.getOrNull()?.takeIf(String::isNotBlank) ?: content
  }

  private fun PromptRunDiagnostics.recordGatewayMessagePlan(
    plan: GatewayMessagePlan,
    contextCacheBreakReason: String?,
  ) {
    localContinuationLastMode = plan.mode
    localContinuationLastReason = plan.reason
    this.contextCacheBreakReason = contextCacheBreakReason
    if (plan.mode == LocalContinuationMode.LOCAL_DELTA || plan.mode == LocalContinuationMode.LOCAL_FRONT_PATCH) {
      localContinuationUsedCount += 1
    } else if (
      plan.mode == LocalContinuationMode.FULL_REBUILD &&
      (
        plan.reason == "anchor_changed" ||
          plan.reason == "durable_context_changed" ||
          plan.reason == "dynamic_context_changed" ||
          plan.reason == "transcript_mismatch" ||
          plan.reason == "responses_shape_unavailable" ||
          plan.reason == "tool_pool_changed" ||
          plan.reason == "tool_schema_changed" ||
          plan.reason == "user_setting_changed"
        )
    ) {
      localContinuationFallbackCount += 1
    }
  }

  private fun parseToolCallTranscriptEntry(content: String): AgentToolCall? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{")) {
      return null
    }
    val parsed = runCatching {
      config.json.parseToJsonElement(trimmed) as? JsonObject
    }.getOrNull() ?: return null
    return AgentToolCall(
      id = parsed.primitiveContent("tool_call_id")?.trim()?.takeIf(String::isNotBlank)
        ?: parsed.primitiveContent("id")?.trim()?.takeIf(String::isNotBlank),
      toolName = parsed.primitiveContent("tool_name")?.trim()?.takeIf(String::isNotBlank)
        ?: return null,
      arguments = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
      reason = parsed.primitiveContent("reason")?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun runtimeToolCallFor(entry: RuntimeConversationMessage): AgentToolCall? =
    entry.toolCall?.let { toolCall ->
      AgentToolCall(
        id = toolCall.id,
        toolName = toolCall.toolName,
        arguments = toolCall.arguments,
        reason = toolCall.reason,
      )
    } ?: parseToolCallTranscriptEntry(entry.content)

  private fun agentToolDefinitionToLiteLlmToolDefinition(
    definition: AgentToolDefinition,
    strict: Boolean,
  ): LiteLlmToolDefinition = definition.toLiteLlmToolDefinition(strict = strict)

  private fun runtimeToolResultFor(entry: RuntimeConversationMessage): ParsedToolResultObservation? =
    parseToolResultObservation(entry)
      ?: entry.toolResult?.let { toolResult ->
        ParsedToolResultObservation(
          toolCallId = toolResult.toolCallId,
          toolName = toolResult.toolName,
          content = entry.runtimeToolResultContentOrNull() ?: entry.content,
          isError = toolResult.isError ?: toolResult.status
            ?.equals(AgentToolResultStatus.SUCCESS.name, ignoreCase = true)
            ?.not()
            ?: false,
        )
      }

  private fun parseToolResultObservation(
    entry: RuntimeConversationMessage,
  ): ParsedToolResultObservation? {
    val payload = entry.runtimeToolResultJsonPayloadOrNull() ?: return null
    val parsed = runCatching {
      config.json.parseToJsonElement(payload) as? JsonObject
    }.getOrNull() ?: return null
    val toolCallId = parsed.primitiveContent("tool_call_id")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: entry.toolResult?.toolCallId
    val toolName = parsed.primitiveContent("tool_name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: entry.toolResult?.toolName
      ?: return null
    val status = parsed.primitiveContent("status")
      ?.trim()
      ?.lowercase()
      ?: entry.toolResult?.status
        ?.trim()
        ?.lowercase()
    val metadata = parsed.jsonObjectContent("metadata")
      ?.toStringMap()
      ?: emptyMap()
    return ParsedToolResultObservation(
      toolCallId = toolCallId,
      toolName = toolName,
      content = parsed.primitiveContent("content")
        ?: entry.runtimeToolResultContentOrNull()
        ?: entry.content,
      structuredContent = parsed.jsonObjectContent("structured_content")
        ?: parsed.jsonObjectContent("structuredContent"),
      isError = entry.toolResult?.isError
        ?: (status != null && status != AgentToolResultStatus.SUCCESS.name.lowercase()),
      exitCode = parsed.intContent("exit_code") ?: parsed.intContent("exitCode"),
      stdout = parsed.primitiveContent("stdout")?.trim()?.takeIf(String::isNotBlank),
      stderr = parsed.primitiveContent("stderr")?.trim()?.takeIf(String::isNotBlank),
      errorCode = parsed.primitiveContent("error_code")
        ?.trim()
        ?.takeIf(String::isNotBlank),
      errorMessage = parsed.primitiveContent("error_message")
        ?.trim()
        ?.takeIf(String::isNotBlank),
      metadata = metadata,
    )
  }

  private fun RuntimeConversationMessage.runtimeToolResultJsonPayloadOrNull(): String? {
    if (role != RuntimeConversationRole.TOOL || kind != RuntimeConversationMessageKind.TOOL_RESULT) {
      return null
    }
    val normalized = content.trim()
    if (normalized.isBlank()) {
      return null
    }
    return normalized.takeIf { payload -> payload.startsWith("{") }
  }

  private fun RuntimeConversationMessage.runtimeToolResultContentOrNull(): String? {
    if (role != RuntimeConversationRole.TOOL || kind != RuntimeConversationMessageKind.TOOL_RESULT) {
      return null
    }
    val decoded = runtimeToolResultJsonPayloadOrNull()?.let { payload ->
      runCatching { config.json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
    }
    return decoded?.primitiveContent("content")
      ?: content.takeIf(String::isNotBlank)
  }

  private fun AgentToolCall.toRuntimeConversationToolCall(): RuntimeConversationToolCall =
    RuntimeConversationToolCall(
      id = id,
      toolName = toolName,
      arguments = arguments,
      reason = reason,
    )

  private fun RuntimeConversationAssistantPhase.toLiteLlmAssistantPhase(): LiteLlmAssistantPhase = when (this) {
    RuntimeConversationAssistantPhase.COMMENTARY -> LiteLlmAssistantPhase.COMMENTARY
    RuntimeConversationAssistantPhase.FINAL_ANSWER -> LiteLlmAssistantPhase.FINAL_ANSWER
  }

  private fun AgentModelAction.toSerializableModelAction(): OpenCraySerializableModelAction = when (this) {
    is AgentModelAction.Commentary -> OpenCraySerializableModelAction.Commentary(
      text = text,
      stage = stage,
    )

    is AgentModelAction.Final -> OpenCraySerializableModelAction.Final(
      answer = answer,
      responseFormat = responseFormat,
      attachments = attachments,
    )

    is AgentModelAction.ToolCall -> OpenCraySerializableModelAction.ToolCall(
      call = OpenCraySerializableToolCall.from(call),
    )
  }

  private fun OpenCraySerializableModelAction.toRuntimeAction(): AgentModelAction = when (this) {
    is OpenCraySerializableModelAction.Commentary -> AgentModelAction.Commentary(
      text = text,
      stage = stage,
    )

    is OpenCraySerializableModelAction.Final -> AgentModelAction.Final(
      answer = answer,
      responseFormat = responseFormat,
      attachments = attachments,
    )

    is OpenCraySerializableModelAction.ToolCall -> AgentModelAction.ToolCall(
      call = call.toAgentToolCall(),
    )
  }

  private fun toolReasonMetadata(call: AgentToolCall): Map<String, String> =
    call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reason -> mapOf("toolReason" to reason) }
      ?: emptyMap()

  private fun maybeExecuteSubAgentCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: PromptTurnCursor?,
  ): AgentToolResult? {
    val canonicalToolName = canonicalSubAgentToolName(call.toolName) ?: return null
    if (!isSubAgentToolExposed(canonicalToolName)) {
      return toolDispatcher.dispatch(task = task, call = call, hooks = hooks)
    }
    when (canonicalToolName) {
      "spawn_agent" -> return spawnSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        cursor = cursor,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
      )

      "wait_agent" -> return waitOnSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        transcript = transcript,
        cursor = cursor,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
      )

      "send_input" -> return sendInputToSubAgentHandle(
        call = call,
        cursor = cursor,
      )

      "close_agent" -> return closeSubAgentHandle(
        task = task,
        turn = turn,
        call = call,
        cursor = cursor,
      )

      "list_subagents" -> return listSubAgentHandles(
        call = call,
        cursor = cursor,
      )

      "Task" -> Unit
      else -> return null
    }
    if (!call.toolName.trim().equals("Task", ignoreCase = true)) {
      return null
    }
    val preparedDelegation = when (
      val prepared = prepareSubAgentDelegation(
        task = task,
        turn = turn,
        call = call,
        activeSkillCapsule = activeSkillCapsule,
        toolName = "Task",
      )
    ) {
      is PreparedSubAgentDelegationResult.Invalid -> return prepared.result
      is PreparedSubAgentDelegationResult.Ready -> prepared.delegation
    }
    val handles = subAgentHandleRegistry(cursor)
    val existingHandle = findContinuationHandle(handles)
    val handle = existingHandle ?: createSubAgentHandle(
      task = task,
      prepared = preparedDelegation,
      agentId = continuationResume()?.agentId,
      childRunId = continuationResume()?.childRunId,
      childTaskId = continuationResume()?.childTaskId,
    ).also { createdHandle ->
      if (cursor != null) {
        handles[createdHandle.agentId] = createdHandle
      }
    }
    val childTask = handle.toTask()
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = OpenCraySubAgentPhase.STARTED,
      childTask = childTask,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      summary = null,
      snapshot = SubAgentExecutionSnapshot.running(),
      liveContext = handle.childLiveContext,
    )
    val approvalContinuation = takePendingApprovalContinuation(handle, handles)
    val execution = executeSubAgentHandleLifecycle(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      profile = preparedDelegation.profile,
      handles = handles,
      approvalContinuation = approvalContinuation,
      emitResumedPhaseWithoutApproval = false,
      retainTerminalHandle = false,
    )
    return childResultToTaskToolResult(
      call = call,
      handle = execution.handle,
      delegationPlan = preparedDelegation.delegationPlan,
      childResult = execution.childResult,
      compressedChildResult = execution.handle.snapshot,
    )
  }

  private fun canonicalSubAgentToolName(toolName: String): String? = when (toolName.trim().lowercase()) {
    "task" -> "Task"
    "spawn_agent",
    "spawnagent",
    -> "spawn_agent"

    "wait_agent",
    "waitagent",
    -> "wait_agent"

    "send_input",
    "sendinput",
    -> "send_input"

    "close_agent",
    "closeagent",
    -> "close_agent"

    "list_subagents",
    "listsubagents",
    "list_handles",
    "listhandles",
    -> "list_subagents"

    else -> null
  }

  private fun isSubAgentToolExposed(toolName: String): Boolean = toolDispatcher.definitions().any { definition ->
    definition.name.trim().equals(toolName, ignoreCase = true)
  }

  private fun spawnSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): AgentToolResult {
    val preparedDelegation = when (
      val prepared = prepareSubAgentDelegation(
        task = task,
        turn = turn,
        call = call,
        activeSkillCapsule = activeSkillCapsule,
        toolName = "spawn_agent",
      )
    ) {
      is PreparedSubAgentDelegationResult.Invalid -> return prepared.result
      is PreparedSubAgentDelegationResult.Ready -> prepared.delegation
    }
    val handles = subAgentHandleRegistry(cursor)
    val existingHandle = findContinuationHandle(handles)
    val requestedAgentId = call.arguments.primitiveContent("agent_id")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (
      existingHandle != null &&
      requestedAgentId != null &&
      requestedAgentId != existingHandle.agentId
    ) {
      return AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.FAILED,
        content = "Delegated child handle '$requestedAgentId' does not match the resumable child handle '${existingHandle.agentId}'.",
        errorCode = "SUBAGENT_HANDLE_MISMATCH",
        errorMessage = "Delegated child handle '$requestedAgentId' does not match the resumable child handle '${existingHandle.agentId}'.",
        metadata = mapOf(
          "agentId" to existingHandle.agentId,
          "requestedAgentId" to requestedAgentId,
        ),
      )
    }
    val handle = existingHandle ?: run {
      val agentId = requestedAgentId ?: "agent-${UUID.randomUUID().toString().take(8)}"
      if (handles.containsKey(agentId)) {
        return AgentToolResult(
          toolName = call.toolName,
          status = AgentToolResultStatus.FAILED,
          content = "Delegated child handle '$agentId' already exists.",
          errorCode = "SUBAGENT_HANDLE_EXISTS",
          errorMessage = "Delegated child handle '$agentId' already exists.",
          metadata = mapOf("agentId" to agentId),
        )
      }
      createSubAgentHandle(
        task = task,
        prepared = preparedDelegation,
        agentId = agentId,
      ).also { createdHandle ->
        handles[createdHandle.agentId] = createdHandle
        config.subAgentExecutionCoordinator.upsertHandle(createdHandle)
        emitSubAgentEvent(
          task = task,
          turn = turn,
          phase = OpenCraySubAgentPhase.STARTED,
          childTask = preparedDelegation.childTask,
          childRunId = createdHandle.childRunId,
          childTaskId = createdHandle.childTaskId,
          summary = createdHandle.snapshot.summaryText(),
          snapshot = createdHandle.snapshot,
          liveContext = createdHandle.childLiveContext,
        )
      }
    }
    if (cursor != null) {
      activeSubAgentExecution(cursor = cursor, agentId = handle.agentId)?.let {
        val latestHandle = synchronizedSubAgentHandle(
          cursor = cursor,
          agentId = handle.agentId,
        ) ?: handle
        return storedSpawnAgentHandleResult(
          call = call,
          handle = latestHandle,
          delegationPlan = preparedDelegation.delegationPlan,
        )
      }
      if (isTerminalSubAgentState(handle.snapshot.state) && handle.pendingApprovalResume == null) {
        return storedSpawnAgentHandleResult(
          call = call,
          handle = handle,
          delegationPlan = preparedDelegation.delegationPlan,
        )
      }
      val approvalContinuation = takePendingApprovalContinuation(
        handle = handle,
        handles = handles,
      )
      if (handle.pendingApprovalResume != null && approvalContinuation == null) {
        return storedSpawnAgentHandleResult(
          call = call,
          handle = handle,
          delegationPlan = preparedDelegation.delegationPlan,
        )
      }
      val startedHandle = startSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = cursor.sessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
        handle = handle,
        profile = preparedDelegation.profile,
        approvalContinuation = approvalContinuation,
        emitResumedPhaseWithoutApproval = existingHandle == null,
      )
      return storedSpawnAgentHandleResult(
        call = call,
        handle = startedHandle,
        delegationPlan = preparedDelegation.delegationPlan,
      )
    }
    if (isTerminalSubAgentState(handle.snapshot.state) && handle.pendingApprovalResume == null) {
      return storedSpawnAgentHandleResult(
        call = call,
        handle = handle,
        delegationPlan = preparedDelegation.delegationPlan,
      )
    }
    val approvalContinuation = takePendingApprovalContinuation(
      handle = handle,
      handles = handles,
    )
    if (handle.pendingApprovalResume != null && approvalContinuation == null) {
      return storedSpawnAgentHandleResult(
        call = call,
        handle = handle,
        delegationPlan = preparedDelegation.delegationPlan,
      )
    }
    val execution = executeSubAgentHandleLifecycle(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      profile = preparedDelegation.profile,
      handles = handles,
      approvalContinuation = approvalContinuation,
      emitResumedPhaseWithoutApproval = existingHandle == null,
      retainTerminalHandle = true,
    )
    return childResultToSpawnAgentToolResult(
      call = call,
      handle = execution.handle,
      delegationPlan = preparedDelegation.delegationPlan,
      childResult = execution.childResult,
      childApprovalResume = execution.childApprovalResume,
    )
  }

  private fun waitOnSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): AgentToolResult {
    val handles = subAgentHandleRegistry(cursor)
    val agentId = resolveSubAgentHandleId(call)
      ?: return invalidSubAgentCallResult(call, "wait_agent agent_id must not be blank.")
    val handle = handles[agentId] ?: return unknownSubAgentHandleResult(
      call = call,
      agentId = agentId,
    )
    return waitOnResolvedSubAgentHandle(
      task = task,
      turn = turn,
      call = call,
      transcript = transcript,
      cursor = cursor,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      handles = handles,
    )
  }

  private fun waitOnDetachedRecoverySubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    agentId: String,
    parentRunId: String,
  ): AgentToolResult {
    val key = SubAgentExecutionKey(
      parentRunId = parentRunId,
      agentId = agentId,
    )
    val handle = config.subAgentExecutionCoordinator.currentHandle(key)
      ?: config.seededSubAgentHandles.firstOrNull { seeded ->
        seeded.agentId == agentId && seeded.parentRunId == parentRunId
      }
      ?: return unknownSubAgentHandleResult(
        call = call,
        agentId = agentId,
      )
    val restoredHandle = restoredSubAgentHandle(handle)
    return waitOnResolvedSubAgentHandle(
      task = task,
      turn = turn,
      call = call,
      transcript = transcript,
      cursor = null,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = restoredHandle,
      handles = linkedMapOf(restoredHandle.agentId to restoredHandle),
      startDetachedExecutionIfNeeded = false,
    )
  }

  private fun waitOnResolvedSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    cursor: PromptTurnCursor?,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    handles: MutableMap<String, SubAgentHandleState>,
    startDetachedExecutionIfNeeded: Boolean = true,
  ): AgentToolResult {
    val agentId = handle.agentId
    if (isTerminalSubAgentState(handle.snapshot.state) && handle.pendingApprovalResume == null) {
      return storedSubAgentHandleResult(call = call, handle = handle)
    }
    val hadActiveExecution = cursor != null && activeSubAgentExecution(cursor = cursor, agentId = agentId) != null
    if (cursor != null && hadActiveExecution) {
      waitForActiveSubAgentExecution(
        cursor = cursor,
        agentId = agentId,
        onProgress = { progressHandle ->
          emitSubAgentWaitProgressEvent(
            task = task,
            turn = turn,
            handle = progressHandle,
          )
        },
      )
      val latestHandleAfterJoin = synchronizedSubAgentHandle(
        cursor = cursor,
        agentId = agentId,
      )
      if (latestHandleAfterJoin != null &&
        (isTerminalSubAgentState(latestHandleAfterJoin.snapshot.state) ||
          latestHandleAfterJoin.pendingApprovalResume != null)
      ) {
        return storedSubAgentHandleResult(call = call, handle = latestHandleAfterJoin)
      }
    }
    val hadDetachedActiveExecution = cursor == null &&
      config.subAgentExecutionCoordinator.activeExecution(subAgentExecutionKey(handle)) != null
    if (cursor == null && hadDetachedActiveExecution) {
      waitForDetachedSubAgentExecution(
        handle = handle,
        onProgress = { progressHandle ->
          emitSubAgentWaitProgressEvent(
            task = task,
            turn = turn,
            handle = progressHandle,
          )
        },
      )
      val latestHandleAfterJoin = coordinatedSubAgentHandle(handle) ?: handle
      if (
        isTerminalSubAgentState(latestHandleAfterJoin.snapshot.state) ||
        latestHandleAfterJoin.pendingApprovalResume != null
      ) {
        return storedSubAgentHandleResult(call = call, handle = latestHandleAfterJoin)
      }
    }
    if (cursor == null && !startDetachedExecutionIfNeeded) {
      return storedSubAgentHandleResult(
        call = call,
        handle = coordinatedSubAgentHandle(handle) ?: handle,
      )
    }
    val approvalContinuation = takePendingApprovalContinuation(
      handle = handle,
      handles = handles,
    )
    if (handle.pendingApprovalResume != null && approvalContinuation == null) {
      return storedSubAgentHandleResult(call = call, handle = handle)
    }
    val profile = BuiltInSubAgentProfiles.resolve(handle.subagentType)
      ?: return invalidSubAgentCallResult(
        call = call,
        message = "Unknown delegated subagent_type '${handle.subagentType}'.",
      )
    if (cursor != null) {
      val startedHandle = startSubAgentHandleBackgroundExecution(
        task = task,
        turn = turn,
        transcript = transcript,
        parentSessionContext = cursor.sessionContext,
        hooks = hooks,
        activeSkillCapsule = activeSkillCapsule,
        cursor = cursor,
        handle = handle,
        profile = profile,
        approvalContinuation = approvalContinuation,
        emitResumedPhaseWithoutApproval = true,
      )
      waitForActiveSubAgentExecution(
        cursor = cursor,
        agentId = agentId,
        onProgress = { progressHandle ->
          emitSubAgentWaitProgressEvent(
            task = task,
            turn = turn,
            handle = progressHandle,
          )
        },
      )
      val latestHandle = synchronizedSubAgentHandle(
        cursor = cursor,
        agentId = agentId,
      ) ?: startedHandle
      return storedSubAgentHandleResult(call = call, handle = latestHandle)
    }
    val startedHandle = ensureDetachedSubAgentHandleBackgroundExecution(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = cursor?.sessionContext ?: config.sessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = handle,
      handles = handles,
    ) ?: handle
    waitForDetachedSubAgentExecution(
      handle = startedHandle,
      onProgress = { progressHandle ->
        emitSubAgentWaitProgressEvent(
          task = task,
          turn = turn,
          handle = progressHandle,
        )
      },
    )
    val latestHandle = coordinatedSubAgentHandle(startedHandle) ?: startedHandle
    return storedSubAgentHandleResult(call = call, handle = latestHandle)
  }

  private fun ensureDetachedSubAgentHandleBackgroundExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    handles: MutableMap<String, SubAgentHandleState>,
  ): SubAgentHandleState? {
    val normalizedHandle = coordinatedSubAgentHandle(handle) ?: handle
    if (
      isTerminalSubAgentState(normalizedHandle.snapshot.state) &&
      normalizedHandle.pendingApprovalResume == null
    ) {
      return normalizedHandle
    }
    if (
      config.subAgentExecutionCoordinator.activeExecution(subAgentExecutionKey(normalizedHandle)) != null
    ) {
      return coordinatedSubAgentHandle(normalizedHandle) ?: normalizedHandle
    }
    val approvalContinuation = takePendingApprovalContinuation(
      handle = normalizedHandle,
      handles = handles,
    )
    if (normalizedHandle.pendingApprovalResume != null && approvalContinuation == null) {
      return normalizedHandle
    }
    val profile = BuiltInSubAgentProfiles.resolve(normalizedHandle.subagentType)
      ?: error("Unknown delegated subagent_type '${normalizedHandle.subagentType}'.")
    return startDetachedSubAgentHandleBackgroundExecution(
      task = task,
      turn = turn,
      transcript = transcript,
      parentSessionContext = parentSessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = normalizedHandle,
      profile = profile,
      approvalContinuation = approvalContinuation,
      emitResumedPhaseWithoutApproval = true,
    )
  }

  private fun prepareSubAgentMailboxDelivery(
    handle: SubAgentHandleState,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
  ): PreparedSubAgentMailboxDelivery {
    val normalizedHandle = handle.withNormalizedMailbox()
    val mailbox = normalizedHandle.normalizedMailbox()
    val pendingMessages = mailbox.pendingMessages()
    val promptResumeState = pendingMessages.fold(
      approvalContinuation?.resume?.promptResumeState ?: normalizedHandle.childPromptResumeState,
    ) { state, message ->
      state?.withAppendedUserInput(message.text)
    }
    if (pendingMessages.isEmpty()) {
      return PreparedSubAgentMailboxDelivery(
        handle = normalizedHandle,
        promptResumeState = promptResumeState,
        includeMailboxMessagesInPrompt = promptResumeState == null,
      )
    }
    val deliveredMailbox = mailbox.markDeliveredThrough(pendingMessages.last().messageId)
    return PreparedSubAgentMailboxDelivery(
      handle = normalizedHandle.copy(
        supplementalInputs = emptyList(),
        mailbox = deliveredMailbox,
        childPromptResumeState = promptResumeState ?: normalizedHandle.childPromptResumeState,
        updatedAtEpochMs = maxOf(
          normalizedHandle.updatedAtEpochMs,
          pendingMessages.last().createdAtEpochMs,
        ),
      ),
      promptResumeState = promptResumeState,
      includeMailboxMessagesInPrompt = promptResumeState == null,
    )
  }

  private fun executeSubAgentHandleLifecycle(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    handles: MutableMap<String, SubAgentHandleState>,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
    retainTerminalHandle: Boolean,
  ): SubAgentHandleLifecycleExecution {
    val preparedMailboxDelivery = prepareSubAgentMailboxDelivery(
      handle = handle,
      approvalContinuation = approvalContinuation,
    )
    val runningSnapshot = SubAgentExecutionSnapshot.backgroundRunning(
      headline = when {
        approvalContinuation?.approved == true -> "Queued delegated child run resumed after approval."
        approvalContinuation?.approved == false -> "Queued delegated child run resumed after rejection."
        else -> "Queued delegated child run started."
      },
    )
    val runningHandle = preparedMailboxDelivery.handle.copy(
      snapshot = runningSnapshot,
      pendingApprovalResume = null,
      updatedAtEpochMs = clock(),
    )
    handles[handle.agentId] = runningHandle
    config.subAgentExecutionCoordinator.upsertHandle(runningHandle)
    if (approvalContinuation != null || emitResumedPhaseWithoutApproval) {
      emitSubAgentEvent(
        task = task,
        turn = turn,
        phase = OpenCraySubAgentPhase.RESUMED,
        childTask = runningHandle.toTask(),
        childRunId = runningHandle.childRunId,
        childTaskId = runningHandle.childTaskId,
        summary = when {
          approvalContinuation?.approved == true ->
            "Delegated child run resumed after approval for ${approvalContinuation.resume.approvedToolName}."
          approvalContinuation?.approved == false ->
            "Delegated child run resumed after rejection for ${approvalContinuation.resume.approvedToolName}."
          else -> "Queued delegated child run started."
        },
        snapshot = runningSnapshot,
        liveContext = runningHandle.childLiveContext,
      )
    }
    val childResult = executeSubAgentHandleRuntime(
      parentTask = task,
      transcript = transcript,
      parentSessionContext = parentSessionContext,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      handle = runningHandle,
      profile = profile,
      approvalContinuation = approvalContinuation,
      promptResumeStateOverride = preparedMailboxDelivery.promptResumeState,
      includeMailboxMessagesInPrompt = preparedMailboxDelivery.includeMailboxMessagesInPrompt,
    )
    val compressedChildResult = SubAgentResultCompressor.compress(childResult)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = runningHandle.agentId,
      childRunId = runningHandle.childRunId,
      childTaskId = runningHandle.childTaskId,
    )
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    val updatedHandle = runningHandle
      .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
      .copy(
        snapshot = compressedChildResult,
        pendingApprovalResume = childApprovalResume,
        childLiveContext = childLiveContext,
        childExecutionStatus = childResult.status.name,
        childTurnCount = childResult.metadata["turnCount"]?.toIntOrNull(),
        childToolCallCount = childResult.metadata["toolCallCount"]?.toIntOrNull(),
      )
    if (retainTerminalHandle || childApprovalResume != null) {
      handles[handle.agentId] = updatedHandle
      config.subAgentExecutionCoordinator.upsertHandle(updatedHandle)
    } else {
      handles.remove(handle.agentId)
      config.subAgentExecutionCoordinator.removeHandle(subAgentExecutionKey(updatedHandle))
    }
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = when (childResult.status) {
        ExecutionStatus.SUCCESS -> OpenCraySubAgentPhase.COMPLETED
        ExecutionStatus.CANCELLED -> OpenCraySubAgentPhase.CANCELLED
        ExecutionStatus.FAILED,
        ExecutionStatus.DENIED,
        ExecutionStatus.TIMEOUT,
        -> OpenCraySubAgentPhase.FAILED
      },
      childTask = updatedHandle.toTask(),
      childRunId = updatedHandle.childRunId,
      childTaskId = updatedHandle.childTaskId,
      summary = compressedChildResult.summaryText(),
      snapshot = compressedChildResult,
      liveContext = updatedHandle.childLiveContext,
    )
    return SubAgentHandleLifecycleExecution(
      handle = updatedHandle,
      childResult = childResult,
      childApprovalResume = childApprovalResume,
    )
  }

  private fun startSubAgentHandleBackgroundExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    cursor: PromptTurnCursor,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
  ): SubAgentHandleState {
    activeSubAgentExecution(cursor = cursor, agentId = handle.agentId)?.let {
      return synchronizedSubAgentHandle(cursor = cursor, agentId = handle.agentId) ?: handle
    }
    val preparedMailboxDelivery = prepareSubAgentMailboxDelivery(
      handle = handle,
      approvalContinuation = approvalContinuation,
    )
    val runningSnapshot = backgroundRunningSnapshot(approvalContinuation)
    val runningHandle = preparedMailboxDelivery.handle.copy(
      snapshot = runningSnapshot,
      pendingApprovalResume = null,
      updatedAtEpochMs = clock(),
    )
    val shouldEmitResumed = approvalContinuation != null || emitResumedPhaseWithoutApproval
    val cancelRequested = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()
    val future = FutureTask<Unit> {
      val childResult = runCatching {
        executeSubAgentHandleRuntime(
          parentTask = task,
          transcript = transcript,
          parentSessionContext = parentSessionContext,
          hooks = RuntimeExecutionHooks(
            isCancellationRequested = {
              cancelRequested.get() || hooks.isCancellationRequested()
            },
            requestRetry = { _ -> Unit },
            requestSuspend = { _ -> Unit },
          ),
          activeSkillCapsule = activeSkillCapsule,
          handle = runningHandle,
          profile = profile,
          approvalContinuation = approvalContinuation,
          promptResumeStateOverride = preparedMailboxDelivery.promptResumeState,
          includeMailboxMessagesInPrompt = preparedMailboxDelivery.includeMailboxMessagesInPrompt,
        )
      }.getOrElse { error ->
        unexpectedSubAgentBackgroundExecutionResult(
          handle = runningHandle,
          error = error,
        )
      }
      completeBackgroundSubAgentExecution(
        task = task,
        turn = turn,
        cursor = cursor,
        handle = runningHandle,
        childResult = childResult,
        executor = executor,
        closed = closed,
      )
    }
    val activeExecution = SubAgentActiveExecution(
      executor = executor,
      future = future,
      cancelRequested = cancelRequested,
      closed = closed,
    )
    val registration = config.subAgentExecutionCoordinator.beginExecution(
      handle = runningHandle,
      execution = activeExecution,
    )
    if (!registration.started) {
      closed.set(true)
      future.cancel(true)
      executor.shutdownNow()
      return synchronizedSubAgentHandle(cursor = cursor, agentId = handle.agentId)
        ?: registration.handle
    }
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[handle.agentId] = registration.handle
    }
    executor.execute(future)
    if (shouldEmitResumed) {
      emitResumedSubAgentEvent(
        task = task,
        turn = turn,
        handle = registration.handle,
        approvalContinuation = approvalContinuation,
      )
    }
    return synchronizedSubAgentHandle(cursor = cursor, agentId = handle.agentId) ?: registration.handle
  }

  private fun waitForActiveSubAgentExecution(
    cursor: PromptTurnCursor,
    agentId: String,
    onProgress: ((SubAgentHandleState) -> Unit)? = null,
  ) {
    waitForSubAgentExecution(
      execution = activeSubAgentExecution(cursor = cursor, agentId = agentId),
      latestHandleProvider = {
        synchronizedSubAgentHandle(cursor = cursor, agentId = agentId)
      },
      onProgress = onProgress,
    )
  }

  private fun waitForDetachedSubAgentExecution(
    handle: SubAgentHandleState,
    onProgress: ((SubAgentHandleState) -> Unit)? = null,
  ) {
    val key = subAgentExecutionKey(handle)
    waitForSubAgentExecution(
      execution = config.subAgentExecutionCoordinator.activeExecution(key),
      latestHandleProvider = {
        config.subAgentExecutionCoordinator.currentHandle(key) ?: handle
      },
      onProgress = onProgress,
    )
  }

  private fun waitForSubAgentExecution(
    execution: SubAgentActiveExecution?,
    latestHandleProvider: (() -> SubAgentHandleState?)? = null,
    onProgress: ((SubAgentHandleState) -> Unit)? = null,
  ) {
    val activeExecution = execution ?: return
    var emittedHeartbeat = false
    var lastCheckpointAtEpochMs = latestHandleProvider?.invoke()?.childPromptCheckpointAtEpochMs
    try {
      while (true) {
        try {
          activeExecution.future.get(
            SUBAGENT_WAIT_PROGRESS_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
          )
          return
        } catch (_: TimeoutException) {
          val latestHandle = latestHandleProvider?.invoke() ?: continue
          val checkpointAtEpochMs = latestHandle.childPromptCheckpointAtEpochMs
          val shouldEmitProgress = when {
            checkpointAtEpochMs != null && checkpointAtEpochMs != lastCheckpointAtEpochMs -> true
            !emittedHeartbeat && isWaitingSubAgentState(latestHandle.snapshot.state) -> true
            else -> false
          }
          if (checkpointAtEpochMs != null) {
            lastCheckpointAtEpochMs = checkpointAtEpochMs
          }
          if (shouldEmitProgress) {
            emittedHeartbeat = true
            onProgress?.invoke(latestHandle)
          }
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (_: java.util.concurrent.CancellationException) {
      Unit
    } catch (_: java.util.concurrent.ExecutionException) {
      Unit
    }
  }

  private fun isWaitingSubAgentState(
    state: SubAgentExecutionState,
  ): Boolean = when (state) {
    SubAgentExecutionState.RUNNING,
    SubAgentExecutionState.BACKGROUND_QUEUED,
    SubAgentExecutionState.BACKGROUND_RUNNING,
    -> true

    else -> false
  }

  private fun activeSubAgentExecution(
    cursor: PromptTurnCursor,
    agentId: String,
  ): SubAgentActiveExecution? = synchronizedSubAgentHandle(
    cursor = cursor,
    agentId = agentId,
  )?.let { handle ->
    config.subAgentExecutionCoordinator.activeExecution(subAgentExecutionKey(handle))
  }

  private fun completeBackgroundSubAgentExecution(
    task: AgentTask,
    turn: Int,
    cursor: PromptTurnCursor,
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
    executor: ExecutorService,
    closed: AtomicBoolean,
  ) {
    var updatedHandle: SubAgentHandleState? = null
    var updatedSnapshot: SubAgentExecutionSnapshot? = null
    var completionPhase: OpenCraySubAgentPhase? = null
    if (closed.get()) {
      config.subAgentExecutionCoordinator.takeActiveExecution(subAgentExecutionKey(handle))
      executor.shutdownNow()
      return
    }
    synchronized(cursor.subAgentExecutionLock) {
      val compressedChildResult = SubAgentResultCompressor.compress(childResult)
      val childApprovalResume = childApprovalResume(
        childResult = childResult,
        agentId = handle.agentId,
        childRunId = handle.childRunId,
        childTaskId = handle.childTaskId,
      )
      val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
      updatedHandle = handle
        .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
        .copy(
          snapshot = compressedChildResult,
          pendingApprovalResume = childApprovalResume,
          childLiveContext = childLiveContext,
          childExecutionStatus = childResult.status.name,
          childTurnCount = childResult.metadata["turnCount"]?.toIntOrNull(),
          childToolCallCount = childResult.metadata["toolCallCount"]?.toIntOrNull(),
        )
      cursor.subAgentHandles[handle.agentId] = requireNotNull(updatedHandle)
      updatedSnapshot = compressedChildResult
      completionPhase = subAgentCompletionPhase(childResult.status)
    }
    val finalizedHandle = requireNotNull(
      config.subAgentExecutionCoordinator.finishExecution(requireNotNull(updatedHandle)),
    )
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[handle.agentId] = finalizedHandle
    }
    executor.shutdownNow()
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = requireNotNull(completionPhase),
      childTask = finalizedHandle.toTask(),
      childRunId = finalizedHandle.childRunId,
      childTaskId = finalizedHandle.childTaskId,
      summary = requireNotNull(updatedSnapshot).summaryText(),
      snapshot = requireNotNull(updatedSnapshot),
      liveContext = finalizedHandle.childLiveContext,
    )
  }

  private fun startDetachedSubAgentHandleBackgroundExecution(
    task: AgentTask,
    turn: Int,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
    emitResumedPhaseWithoutApproval: Boolean,
  ): SubAgentHandleState {
    val existingExecution = config.subAgentExecutionCoordinator.activeExecution(
      subAgentExecutionKey(handle),
    )
    if (existingExecution != null) {
      return coordinatedSubAgentHandle(handle) ?: handle
    }
    val preparedMailboxDelivery = prepareSubAgentMailboxDelivery(
      handle = handle,
      approvalContinuation = approvalContinuation,
    )
    val runningSnapshot = backgroundRunningSnapshot(approvalContinuation)
    val runningHandle = preparedMailboxDelivery.handle.copy(
      snapshot = runningSnapshot,
      pendingApprovalResume = null,
      updatedAtEpochMs = clock(),
    )
    val shouldEmitResumed = approvalContinuation != null || emitResumedPhaseWithoutApproval
    val cancelRequested = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    val executor = Executors.newSingleThreadExecutor()
    val future = FutureTask<Unit> {
      val childResult = runCatching {
        executeSubAgentHandleRuntime(
          parentTask = task,
          transcript = transcript,
          parentSessionContext = parentSessionContext,
          hooks = RuntimeExecutionHooks(
            isCancellationRequested = {
              cancelRequested.get() || hooks.isCancellationRequested()
            },
            requestRetry = { _ -> Unit },
            requestSuspend = { _ -> Unit },
          ),
          activeSkillCapsule = activeSkillCapsule,
          handle = runningHandle,
          profile = profile,
          approvalContinuation = approvalContinuation,
          promptResumeStateOverride = preparedMailboxDelivery.promptResumeState,
          includeMailboxMessagesInPrompt = preparedMailboxDelivery.includeMailboxMessagesInPrompt,
        )
      }.getOrElse { error ->
        unexpectedSubAgentBackgroundExecutionResult(
          handle = runningHandle,
          error = error,
        )
      }
      completeDetachedBackgroundSubAgentExecution(
        task = task,
        turn = turn,
        handle = runningHandle,
        childResult = childResult,
        executor = executor,
        closed = closed,
      )
    }
    val activeExecution = SubAgentActiveExecution(
      executor = executor,
      future = future,
      cancelRequested = cancelRequested,
      closed = closed,
    )
    val registration = config.subAgentExecutionCoordinator.beginExecution(
      handle = runningHandle,
      execution = activeExecution,
    )
    if (!registration.started) {
      closed.set(true)
      future.cancel(true)
      executor.shutdownNow()
      return coordinatedSubAgentHandle(runningHandle) ?: registration.handle
    }
    executor.execute(future)
    if (shouldEmitResumed) {
      emitResumedSubAgentEvent(
        task = task,
        turn = turn,
        handle = registration.handle,
        approvalContinuation = approvalContinuation,
      )
    }
    return coordinatedSubAgentHandle(runningHandle) ?: registration.handle
  }

  private fun completeDetachedBackgroundSubAgentExecution(
    task: AgentTask,
    turn: Int,
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
    executor: ExecutorService,
    closed: AtomicBoolean,
  ) {
    var updatedHandle: SubAgentHandleState? = null
    var updatedSnapshot: SubAgentExecutionSnapshot? = null
    var completionPhase: OpenCraySubAgentPhase? = null
    if (closed.get()) {
      config.subAgentExecutionCoordinator.takeActiveExecution(subAgentExecutionKey(handle))
      executor.shutdownNow()
      return
    }
    val compressedChildResult = SubAgentResultCompressor.compress(childResult)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = handle.agentId,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
    )
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    updatedHandle = handle
      .withClearedChildPromptCheckpoint(updatedAtEpochMs = clock())
      .copy(
        snapshot = compressedChildResult,
        pendingApprovalResume = childApprovalResume,
        childLiveContext = childLiveContext,
        childExecutionStatus = childResult.status.name,
        childTurnCount = childResult.metadata["turnCount"]?.toIntOrNull(),
        childToolCallCount = childResult.metadata["toolCallCount"]?.toIntOrNull(),
      )
    updatedSnapshot = compressedChildResult
    completionPhase = subAgentCompletionPhase(childResult.status)
    val finalizedHandle = requireNotNull(
      config.subAgentExecutionCoordinator.finishExecution(requireNotNull(updatedHandle)),
    )
    executor.shutdownNow()
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = requireNotNull(completionPhase),
      childTask = finalizedHandle.toTask(),
      childRunId = finalizedHandle.childRunId,
      childTaskId = finalizedHandle.childTaskId,
      summary = requireNotNull(updatedSnapshot).summaryText(),
      snapshot = requireNotNull(updatedSnapshot),
      liveContext = finalizedHandle.childLiveContext,
    )
  }

  private fun subAgentExecutionKey(
    handle: SubAgentHandleState,
  ): SubAgentExecutionKey = SubAgentExecutionKey.from(handle)

  private fun coordinatedSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState? = config.subAgentExecutionCoordinator
    .currentHandle(subAgentExecutionKey(handle))
    ?.takeIf { coordinated ->
      coordinated.childRunId == handle.childRunId ||
        coordinated.childTaskId == handle.childTaskId ||
        (
          coordinated.parentRunId == handle.parentRunId &&
            coordinated.parentTaskId == handle.parentTaskId
          )
    }

  private fun synchronizedSubAgentHandle(
    cursor: PromptTurnCursor,
    agentId: String,
  ): SubAgentHandleState? {
    val localHandle = synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[agentId]
    } ?: return null
    val coordinatedHandle = (coordinatedSubAgentHandle(localHandle) ?: localHandle)
      .withNormalizedMailbox()
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles[agentId] = coordinatedHandle
    }
    return coordinatedHandle
  }

  private fun synchronizedSubAgentHandles(
    cursor: PromptTurnCursor,
  ): List<SubAgentHandleState> {
    val localHandles = synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles.values.toList()
    }
    if (localHandles.isEmpty()) {
      return emptyList()
    }
    val synchronizedHandles = localHandles.map { handle ->
      (coordinatedSubAgentHandle(handle) ?: handle).withNormalizedMailbox()
    }
    synchronized(cursor.subAgentExecutionLock) {
      synchronizedHandles.forEach { handle ->
        cursor.subAgentHandles[handle.agentId] = handle
      }
      return cursor.subAgentHandles.values.toList()
    }
  }

  private fun backgroundRunningSnapshot(
    approvalContinuation: PendingSubAgentApprovalContinuation?,
  ): SubAgentExecutionSnapshot = SubAgentExecutionSnapshot.backgroundRunning(
    headline = when {
      approvalContinuation?.approved == true -> "Queued delegated child run resumed after approval."
      approvalContinuation?.approved == false -> "Queued delegated child run resumed after rejection."
      else -> "Queued delegated child run started."
    },
  )

  private fun emitResumedSubAgentEvent(
    task: AgentTask,
    turn: Int,
    handle: SubAgentHandleState,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
  ) {
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = OpenCraySubAgentPhase.RESUMED,
      childTask = handle.toTask(),
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      summary = when {
        approvalContinuation?.approved == true ->
          "Delegated child run resumed after approval for ${approvalContinuation.resume.approvedToolName}."
        approvalContinuation?.approved == false ->
          "Delegated child run resumed after rejection for ${approvalContinuation.resume.approvedToolName}."
        else -> "Queued delegated child run started."
      },
      snapshot = handle.snapshot,
      liveContext = handle.childLiveContext,
    )
  }

  private fun emitSubAgentWaitProgressEvent(
    task: AgentTask,
    turn: Int,
    handle: SubAgentHandleState,
  ) {
    val checkpointHeadline = when (handle.childPromptCheckpointBoundary) {
      OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST ->
        "Delegated child run prepared its next model request."
      OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED ->
        "Delegated child run parsed its next action batch."
      OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED ->
        "Delegated child run emitted a commentary update."
      OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED ->
        "Delegated child run committed a tool result."
      OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED ->
        "Delegated child run ingested new context."
      null -> null
    }
    val progressSnapshot = handle.snapshot.copy(
      headline = checkpointHeadline ?: when (handle.snapshot.state) {
        SubAgentExecutionState.BACKGROUND_QUEUED ->
          "Delegated child run is queued and waiting to continue."
        SubAgentExecutionState.BACKGROUND_RUNNING,
        SubAgentExecutionState.RUNNING,
        -> "Delegated child run is still running."
        else -> handle.snapshot.headline
      },
      detailLines = emptyList(),
    )
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = when (handle.snapshot.state) {
        SubAgentExecutionState.BACKGROUND_RUNNING -> OpenCraySubAgentPhase.RESUMED
        else -> OpenCraySubAgentPhase.STARTED
      },
      childTask = handle.toTask(),
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      summary = progressSnapshot.summaryText(),
      snapshot = progressSnapshot,
      liveContext = handle.childLiveContext,
    )
  }

  private fun subAgentCompletionPhase(
    status: ExecutionStatus,
  ): OpenCraySubAgentPhase = when (status) {
    ExecutionStatus.SUCCESS -> OpenCraySubAgentPhase.COMPLETED
    ExecutionStatus.CANCELLED -> OpenCraySubAgentPhase.CANCELLED
    ExecutionStatus.FAILED,
    ExecutionStatus.DENIED,
    ExecutionStatus.TIMEOUT,
    -> OpenCraySubAgentPhase.FAILED
  }

  private fun unexpectedSubAgentBackgroundExecutionResult(
    handle: SubAgentHandleState,
    error: Throwable,
  ): ExecutionResult = ExecutionResult(
    taskId = handle.childTaskId,
    status = ExecutionStatus.FAILED,
    stderr = error.stackTraceToString(),
    errorCode = "SUBAGENT_RUNTIME_EXCEPTION",
    errorMessage = error.message ?: error::class.java.simpleName,
    startedAtEpochMs = handle.updatedAtEpochMs,
    finishedAtEpochMs = clock(),
  )

  private fun sendInputToSubAgentHandle(
    call: AgentToolCall,
    cursor: PromptTurnCursor?,
  ): AgentToolResult {
    val handles = subAgentHandleRegistry(cursor)
    val agentId = resolveSubAgentHandleId(call)
      ?: return invalidSubAgentCallResult(call, "send_input agent_id must not be blank.")
    val message = call.arguments.primitiveContent("message")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: call.arguments.primitiveContent("input")
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: return invalidSubAgentCallResult(call, "send_input message must not be blank.")
    val handle = handles[agentId] ?: return unknownSubAgentHandleResult(
      call = call,
      agentId = agentId,
    )
    if (!canAppendSupplementalInput(handle)) {
      return AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.FAILED,
        content = "send_input can only target a queued or approval-waiting delegated child handle mailbox.",
        errorCode = "SUBAGENT_NOT_QUEUEABLE",
        errorMessage = "send_input can only target a queued or approval-waiting delegated child handle mailbox.",
        metadata = subAgentHandleMetadata(handle),
      )
    }
    val now = clock()
    val updatedHandle = handle.withQueuedMailboxInput(
      messageId = "mailbox-${now}-${UUID.randomUUID().toString().take(8)}",
      message = message,
      createdAtEpochMs = now,
    )
    handles[agentId] = updatedHandle
    config.subAgentExecutionCoordinator.upsertHandle(updatedHandle)
    val mailbox = updatedHandle.normalizedMailbox()
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Delegated child input queued in mailbox.",
      metadata = subAgentHandleMetadata(updatedHandle) + mapOf(
        "supplementalInputCount" to mailbox.messages.size.toString(),
        "mailboxPendingInputCount" to mailbox.pendingMessages().size.toString(),
      ),
    )
  }

  private fun canAppendSupplementalInput(handle: SubAgentHandleState): Boolean = when (handle.snapshot.state) {
    SubAgentExecutionState.BACKGROUND_QUEUED -> true
    SubAgentExecutionState.WAITING_APPROVAL,
    SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
    -> handle.pendingApprovalResume != null
    else -> false
  }

  private fun closeSubAgentHandle(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    cursor: PromptTurnCursor?,
  ): AgentToolResult {
    val handles = subAgentHandleRegistry(cursor)
    val agentId = resolveSubAgentHandleId(call)
      ?: return invalidSubAgentCallResult(call, "close_agent agent_id must not be blank.")
    val handle = handles[agentId] ?: return unknownSubAgentHandleResult(
      call = call,
      agentId = agentId,
    )
    config.subAgentExecutionCoordinator.cancelActiveExecution(
      key = subAgentExecutionKey(handle),
      markClosed = true,
    )
    if (cursor != null) {
      synchronized(cursor.subAgentExecutionLock) {
        cursor.subAgentHandles.remove(agentId)
      }
    } else {
      handles.remove(agentId)
    }
    config.subAgentExecutionCoordinator.removeHandle(subAgentExecutionKey(handle))
    clearPendingApprovalContinuationForHandle(handle)
    if (!isTerminalSubAgentState(handle.snapshot.state)) {
      val cancelledSnapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "Queued delegated child run '${handle.description}' was closed.",
      )
      emitSubAgentEvent(
        task = task,
        turn = turn,
        phase = OpenCraySubAgentPhase.CANCELLED,
        childTask = handle.toTask(),
        childRunId = handle.childRunId,
        childTaskId = handle.childTaskId,
        summary = cancelledSnapshot.summaryText(),
        snapshot = cancelledSnapshot,
        liveContext = handle.childLiveContext,
      )
      return AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = cancelledSnapshot.summaryText(),
        metadata = subAgentHandleMetadata(
          handle
            .withClearedChildPromptCheckpoint()
            .copy(
              snapshot = cancelledSnapshot,
              pendingApprovalResume = null,
              childExecutionStatus = ExecutionStatus.CANCELLED.name,
            ),
        ) + mapOf("closed" to "true"),
      )
    }
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = "Delegated child handle closed.",
      metadata = subAgentHandleMetadata(handle) + mapOf("closed" to "true"),
    )
  }

  private fun listSubAgentHandles(
    call: AgentToolCall,
    cursor: PromptTurnCursor?,
  ): AgentToolResult {
    val handles = listableSubAgentHandles(cursor)
    val openHandleCount = handles.count { handle ->
      !isTerminalSubAgentState(handle.snapshot.state)
    }
    val payload = buildJsonObject {
      put("count", handles.size)
      put("openCount", openHandleCount)
      put(
        "subagents",
        JsonArray(handles.map(::subAgentHandleJson)),
      )
    }
    return AgentToolResult(
      toolName = call.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = config.json.encodeToString(JsonObject.serializer(), payload),
      metadata = mapOf(
        "subagentCount" to handles.size.toString(),
        "openSubagentCount" to openHandleCount.toString(),
      ),
    )
  }

  private fun openSubAgentHandleObservation(
    cursor: PromptTurnCursor,
  ): String? {
    val openHandles = synchronizedSubAgentHandles(cursor).filter { handle ->
      !isTerminalSubAgentState(handle.snapshot.state)
    }
    if (openHandles.isEmpty()) {
      return null
    }
    val summary = openHandles.joinToString(separator = ", ") { handle ->
      "${handle.agentId}(${handle.snapshot.state.wireValue})"
    }
    return buildString {
      append("Delegated child handles are still open: ")
      append(summary)
      append(". Use wait_agent to harvest a running or approval-paused child later. ")
      append("Use close_agent to discard any child you no longer need. ")
      append("If you intentionally leave a child running across turns, mention that in your user-facing answer.")
    }
  }

  private fun cancelActiveSubAgentExecutions(
    task: AgentTask,
    turn: Int,
    cursor: PromptTurnCursor,
    reason: String,
    removeHandles: Boolean,
  ) {
    val cancelledEvents = mutableListOf<Pair<SubAgentHandleState, SubAgentExecutionSnapshot>>()
    synchronizedSubAgentHandles(cursor).forEach { handle ->
      config.subAgentExecutionCoordinator.cancelActiveExecution(
        subAgentExecutionKey(handle),
        markClosed = true,
      ) ?: return@forEach
      val cancelledHandle = cancelledSubAgentHandle(
        handle = handle,
        reason = reason,
      )
      clearPendingApprovalContinuationForHandle(handle)
      synchronized(cursor.subAgentExecutionLock) {
        if (removeHandles) {
          cursor.subAgentHandles.remove(handle.agentId)
        } else {
          cursor.subAgentHandles[handle.agentId] = cancelledHandle
        }
      }
      if (removeHandles) {
        config.subAgentExecutionCoordinator.removeHandle(subAgentExecutionKey(handle))
      } else {
        config.subAgentExecutionCoordinator.upsertHandle(cancelledHandle)
      }
      cancelledEvents += cancelledHandle to cancelledHandle.snapshot
    }
    cancelledEvents.forEach { (handle, snapshot) ->
      emitSubAgentEvent(
        task = task,
        turn = turn,
        phase = OpenCraySubAgentPhase.CANCELLED,
        childTask = handle.toTask(),
        childRunId = handle.childRunId,
        childTaskId = handle.childTaskId,
        summary = snapshot.summaryText(),
        snapshot = snapshot,
        liveContext = handle.childLiveContext,
      )
    }
  }

  private fun cancelledSubAgentHandle(
    handle: SubAgentHandleState,
    reason: String,
  ): SubAgentHandleState {
    val headline = when (handle.snapshot.state) {
      SubAgentExecutionState.BACKGROUND_RUNNING -> "Background delegated child run '${handle.description}' was cancelled."
      else -> "Queued delegated child run '${handle.description}' was cancelled."
    }
    return handle.copy(
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = headline,
        detailLines = listOf(reason),
      ),
      pendingApprovalResume = null,
      childPromptResumeState = null,
      childPromptCheckpointBoundary = null,
      childPromptCheckpointAtEpochMs = null,
      childExecutionStatus = ExecutionStatus.CANCELLED.name,
      updatedAtEpochMs = clock(),
    )
  }

  private fun clearPendingApprovalContinuationForHandle(handle: SubAgentHandleState) {
    pendingApprovedSubAgentResume = pendingApprovedSubAgentResume?.takeUnless { resume ->
      resumeTargetsHandle(resume = resume, handle = handle)
    }
    pendingRejectedSubAgentResume = pendingRejectedSubAgentResume?.takeUnless { resume ->
      resumeTargetsHandle(resume = resume, handle = handle)
    }
  }

  private fun resumeTargetsHandle(
    resume: SubAgentApprovalResume,
    handle: SubAgentHandleState,
  ): Boolean = when {
    !resume.agentId.isNullOrBlank() -> resume.agentId == handle.agentId
    !resume.childTaskId.isNullOrBlank() -> resume.childTaskId == handle.childTaskId
    !resume.childRunId.isNullOrBlank() -> resume.childRunId == handle.childRunId
    else -> handle.pendingApprovalResume != null
  }

  private fun restoredSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState = (
    coordinatedSubAgentHandle(handle)
      ?: restoredInterruptedBackgroundSubAgentHandle(
        handle = handle,
        restoredAtEpochMs = clock(),
      )
    ).withNormalizedMailbox()

  private fun subAgentHandleRegistry(
    cursor: PromptTurnCursor?,
  ): MutableMap<String, SubAgentHandleState> = if (cursor != null) {
    synchronizedSubAgentHandles(cursor)
    cursor.subAgentHandles
  } else {
    linkedMapOf<String, SubAgentHandleState>().apply {
      (
        config.promptResumeState?.subAgentHandles.orEmpty() +
          config.seededSubAgentHandles
        ).map(::restoredSubAgentHandle)
        .forEach { handle ->
          val existing = this[handle.agentId]
          if (existing == null || handle.updatedAtEpochMs >= existing.updatedAtEpochMs) {
            put(handle.agentId, handle)
          }
        }
    }
  }

  private fun listableSubAgentHandles(
    cursor: PromptTurnCursor?,
  ): List<SubAgentHandleState> {
    val dedupedHandles = linkedMapOf<SubAgentExecutionKey, SubAgentHandleState>()
    buildList {
      if (cursor != null) {
        addAll(synchronizedSubAgentHandles(cursor))
      } else {
        addAll(config.promptResumeState?.subAgentHandles.orEmpty())
      }
      addAll(config.seededSubAgentHandles)
      addAll(config.subAgentExecutionCoordinator.allHandles())
    }.map(::restoredSubAgentHandle)
      .forEach { handle ->
        val key = subAgentExecutionKey(handle)
        val existing = dedupedHandles[key]
        if (existing == null || handle.updatedAtEpochMs >= existing.updatedAtEpochMs) {
          dedupedHandles[key] = handle
        }
      }
    return dedupedHandles.values.sortedWith(
      compareByDescending<SubAgentHandleState>(SubAgentHandleState::updatedAtEpochMs)
        .thenBy(SubAgentHandleState::parentRunId)
        .thenBy(SubAgentHandleState::agentId),
    )
  }

  private fun resolveSubAgentHandleId(call: AgentToolCall): String? =
    call.arguments.primitiveContent("agent_id")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: call.arguments.primitiveContent("id")
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: call.arguments.stringArrayContent("agent_ids")
        ?.firstOrNull()
      ?: call.arguments.stringArrayContent("ids")
        ?.firstOrNull()

  private fun unknownSubAgentHandleResult(
    call: AgentToolCall,
    agentId: String,
  ): AgentToolResult = AgentToolResult(
    toolName = call.toolName,
    status = AgentToolResultStatus.FAILED,
    content = "Unknown delegated child handle '$agentId'.",
    errorCode = "UNKNOWN_SUBAGENT_HANDLE",
    errorMessage = "Unknown delegated child handle '$agentId'.",
    metadata = mapOf("agentId" to agentId),
  )

  private fun isTerminalSubAgentState(state: SubAgentExecutionState): Boolean = when (state) {
    SubAgentExecutionState.COMPLETED,
    SubAgentExecutionState.FAILED,
    SubAgentExecutionState.CANCELLED,
    -> true

    else -> false
  }

  private fun takePendingApprovalContinuation(
    handle: SubAgentHandleState,
    handles: Map<String, SubAgentHandleState>,
  ): PendingSubAgentApprovalContinuation? {
    val approvedResume = pendingApprovedSubAgentResume
    val rejectedResume = pendingRejectedSubAgentResume
    check(approvedResume == null || rejectedResume == null) {
      "Only one subagent approval continuation can be pending at a time."
    }
    approvedResume?.takeIf { resumeMatchesHandle(it, handle, handles) }?.let { resume ->
      pendingApprovedSubAgentResume = null
      return PendingSubAgentApprovalContinuation(
        resume = effectiveApprovalResume(handle, resume),
        approved = true,
      )
    }
    rejectedResume?.takeIf { resumeMatchesHandle(it, handle, handles) }?.let { resume ->
      pendingRejectedSubAgentResume = null
      return PendingSubAgentApprovalContinuation(
        resume = effectiveApprovalResume(handle, resume),
        approved = false,
      )
    }
    return null
  }

  private fun effectiveApprovalResume(
    handle: SubAgentHandleState,
    resume: SubAgentApprovalResume,
  ): SubAgentApprovalResume = (handle.pendingApprovalResume ?: resume).copy(
    isHighRisk = resume.isHighRisk || (handle.pendingApprovalResume?.isHighRisk == true),
    agentId = handle.pendingApprovalResume?.agentId ?: resume.agentId ?: handle.agentId,
    childRunId = handle.pendingApprovalResume?.childRunId ?: resume.childRunId ?: handle.childRunId,
    childTaskId = handle.pendingApprovalResume?.childTaskId ?: resume.childTaskId ?: handle.childTaskId,
  )

  private fun resumeMatchesHandle(
    resume: SubAgentApprovalResume,
    handle: SubAgentHandleState,
    handles: Map<String, SubAgentHandleState>,
  ): Boolean = when {
    !resume.agentId.isNullOrBlank() -> resume.agentId == handle.agentId
    !resume.childTaskId.isNullOrBlank() -> resume.childTaskId == handle.childTaskId
    !resume.childRunId.isNullOrBlank() -> resume.childRunId == handle.childRunId
    else -> handle.pendingApprovalResume != null &&
      handles.values.count { candidate -> candidate.pendingApprovalResume != null } == 1
  }

  private fun executeSubAgentHandleRuntime(
    parentTask: AgentTask,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
    promptResumeStateOverride: OpenCrayPromptResumeState?,
    includeMailboxMessagesInPrompt: Boolean,
  ): ExecutionResult {
    val childTask = handle.toTask(
      includeMailboxMessagesInPrompt = includeMailboxMessagesInPrompt,
    )
    val inheritedActiveSkillCapsule = resolveInheritedSubAgentSkillCapsule(
      handle = handle,
      activeSkillCapsule = activeSkillCapsule,
    )
    val childContext = config.subAgentContextBuilder.build(
      SubAgentContextBuildRequest(
        parentSessionContext = parentSessionContext,
        childTask = childTask,
        parentGoalSummary = parentTask.input.trim(),
        parentObservationLines = recentToolObservationSupport.summaryLines(transcript),
        parentConversation = transcript.toList(),
        activeSkillCapsule = inheritedActiveSkillCapsule,
      ),
    )
    val childPromptTask = AgentTask(
      id = handle.childTaskId,
      type = AgentTaskType.PROMPT,
      input = childTask.prompt,
      policyDecision = parentTask.policyDecision,
      createdAtEpochMs = handle.createdAtEpochMs,
      metadata = parentTask.metadata
        .filterKeys { key -> !key.startsWith(HIDDEN_METADATA_PREFIX) } +
        childTask.metadata() +
        mapOf(RUN_ID_METADATA_KEY to handle.childRunId),
    )
    val childToolDispatcher = when {
      approvalContinuation?.approved == true -> toolDispatcher.withApprovalGrant(
        approvedTaskId = childPromptTask.id,
        approvedToolName = approvalContinuation.resume.approvedToolName,
      )

      approvalContinuation?.approved == false -> toolDispatcher.withApprovalRejection(
        rejectedTaskId = childPromptTask.id,
        rejectedToolName = approvalContinuation.resume.approvedToolName,
      )

      else -> toolDispatcher
    }
    val childRuntime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = childToolDispatcher.restrictTo(profile.allowedToolNames),
      config = config.copy(
        maxTurns = profile.maxTurns,
        maxToolCalls = 0,
        sessionContext = childContext.sessionContext,
        promptResumeState = promptResumeStateOverride,
        approvedSubAgentResume = null,
        rejectedSubAgentResume = null,
        inheritedActiveSkillCapsule = inheritedActiveSkillCapsule,
        supplementInputProvider = { _, _ -> emptyList() },
        promptCheckpointSink = { emission ->
          config.promptCheckpointSink(emission)
          val checkpointBaseHandle = latestSubAgentHandle(handle)
          config.subAgentExecutionCoordinator.upsertHandle(
            checkpointBaseHandle.withUpdatedChildPromptCheckpoint(
              checkpointState = emission.state,
              checkpointBoundary = emission.boundary,
              emittedAtEpochMs = emission.emittedAtEpochMs,
            ),
          )
        },
      ),
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
      clock = clock,
    )
    return childRuntime.execute(childPromptTask, hooks)
  }

  private fun latestSubAgentHandle(
    handle: SubAgentHandleState,
  ): SubAgentHandleState = coordinatedSubAgentHandle(handle)?.let { coordinated ->
    if (coordinated.updatedAtEpochMs >= handle.updatedAtEpochMs) {
      coordinated
    } else {
      handle
    }
  } ?: handle

  private fun subAgentHandleMetadata(
    handle: SubAgentHandleState,
  ): Map<String, String> = linkedMapOf(
    "agentId" to handle.agentId,
    "subagentType" to handle.subagentType,
    "subagentContextMode" to handle.contextMode,
    "subagentDepth" to handle.depth.toString(),
    "childRunId" to handle.childRunId,
    "childTaskId" to handle.childTaskId,
  ).apply {
    val mailbox = handle.normalizedMailbox()
    if (mailbox.messages.isNotEmpty()) {
      put("supplementalInputCount", mailbox.messages.size.toString())
      put("mailboxPendingInputCount", mailbox.pendingMessages().size.toString())
      mailbox.lastDeliveredMessageId
        ?.takeIf(String::isNotBlank)
        ?.let { put("mailboxLastDeliveredMessageId", it) }
    }
    handle.childPromptResumeState?.let { put("childHasPromptResumeState", "true") }
    handle.childPromptCheckpointBoundary?.wireValue?.let { put("childPromptCheckpointBoundary", it) }
    handle.childPromptCheckpointAtEpochMs?.let { put("childPromptCheckpointAtEpochMs", it.toString()) }
    handle.childExecutionStatus
      ?.takeIf(String::isNotBlank)
      ?.let { put("childExecutionStatus", it) }
    handle.childTurnCount?.let { put("childTurnCount", it.toString()) }
    handle.childToolCallCount?.let { put("childToolCallCount", it.toString()) }
    putAll(handle.childLiveContext.toMetadataMap())
    putAll(handle.snapshot.metadata())
    handle.pendingApprovalResume?.let { resume ->
      putAll(
        SubAgentApprovalResumeMetadata.encodeToMetadata(
          resume = resume,
          json = config.json,
        ),
      )
    }
  }

  private fun subAgentHandleJson(
    handle: SubAgentHandleState,
  ): JsonObject {
    val mailbox = handle.normalizedMailbox()
    return buildJsonObject {
      put("agentId", handle.agentId)
      put("parentRunId", handle.parentRunId)
      put("parentTaskId", handle.parentTaskId)
      put("childRunId", handle.childRunId)
      put("childTaskId", handle.childTaskId)
      put("label", handle.description)
      put("subagentType", handle.subagentType)
      put("contextMode", handle.contextMode)
      put("depth", handle.depth)
      put("state", handle.snapshot.state.wireValue)
      put("continuationKind", handle.snapshot.continuationKind.wireValue)
      put("resumable", handle.snapshot.resumable)
      put("requiresUserAction", handle.snapshot.requiresUserAction)
      put("isHighRisk", handle.snapshot.isHighRisk)
      put("summary", handle.snapshot.headline)
      put("mailboxMessageCount", mailbox.messages.size)
      put("mailboxPendingMessageCount", mailbox.pendingMessages().size)
      mailbox.lastDeliveredMessageId
        ?.takeIf(String::isNotBlank)
        ?.let { messageId -> put("mailboxLastDeliveredMessageId", messageId) }
      handle.childExecutionStatus
        ?.takeIf(String::isNotBlank)
        ?.let { status -> put("childExecutionStatus", status) }
      handle.childTurnCount?.let { turnCount -> put("childTurnCount", turnCount) }
      handle.childToolCallCount?.let { toolCallCount -> put("childToolCallCount", toolCallCount) }
      handle.childLiveContext.toMap()?.let { liveContext ->
        put(
          "liveContext",
          buildJsonObject {
            (liveContext["mode"] as String?)?.let { put("mode", it) }
            (liveContext["soulEnabled"] as Boolean?)?.let { put("soulEnabled", it) }
            (liveContext["memoryRecallEnabled"] as Boolean?)?.let {
              put("memoryRecallEnabled", it)
            }
            (liveContext["replaySource"] as String?)?.let { put("replaySource", it) }
            (liveContext["replayMessageCount"] as Int?)?.let { put("replayMessageCount", it) }
            (liveContext["canonicalSource"] as String?)?.let { put("canonicalSource", it) }
            (liveContext["canonicalMessageCount"] as Int?)?.let {
              put("canonicalMessageCount", it)
            }
            (liveContext["canonicalHistoryPreserved"] as Boolean?)?.let {
              put("canonicalHistoryPreserved", it)
            }
          },
        )
      }
      handle.childPromptResumeState?.let { put("hasPromptResumeState", true) }
      handle.childPromptCheckpointBoundary?.wireValue?.let { boundary ->
        put("childPromptCheckpointBoundary", boundary)
      }
      handle.childPromptCheckpointAtEpochMs?.let { checkpointAt ->
        put("childPromptCheckpointAtEpochMs", checkpointAt)
      }
      handle.activeSkillName
        ?.takeIf(String::isNotBlank)
        ?.let { skillName -> put("activeSkillName", skillName) }
      handle.activeSkillActivationSource
        ?.takeIf(String::isNotBlank)
        ?.let { activationSource -> put("activeSkillActivationSource", activationSource) }
      put("createdAtEpochMs", handle.createdAtEpochMs)
      put("updatedAtEpochMs", handle.updatedAtEpochMs)
    }
  }

  private fun storedSubAgentHandleResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
  ): AgentToolResult {
    val status = when (handle.snapshot.state) {
      SubAgentExecutionState.COMPLETED -> AgentToolResultStatus.SUCCESS
      SubAgentExecutionState.CANCELLED -> AgentToolResultStatus.CANCELLED
      SubAgentExecutionState.FAILED -> when (handle.childExecutionStatus) {
        ExecutionStatus.TIMEOUT.name -> AgentToolResultStatus.TIMEOUT
        else -> AgentToolResultStatus.FAILED
      }

      SubAgentExecutionState.WAITING_APPROVAL,
      SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
      -> AgentToolResultStatus.DENIED

      else -> AgentToolResultStatus.SUCCESS
    }
    val errorCode = when (handle.snapshot.state) {
      SubAgentExecutionState.CANCELLED -> "SUBAGENT_CANCELLED"
      SubAgentExecutionState.FAILED -> handle.snapshot.childErrorCode ?: "SUBAGENT_FAILED"
      SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL -> ERROR_HIGH_RISK_APPROVAL_REQUIRED
      SubAgentExecutionState.WAITING_APPROVAL -> ERROR_APPROVAL_REQUIRED
      else -> null
    }
    val errorMessage = when (handle.snapshot.state) {
      SubAgentExecutionState.CANCELLED -> "Delegated child run was cancelled."
      SubAgentExecutionState.FAILED -> "Delegated child run failed."
      SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
      SubAgentExecutionState.WAITING_APPROVAL,
      -> "Delegated child run needs approval before it can continue."

      else -> null
    }
    return AgentToolResult(
      toolName = call.toolName,
      status = status,
      content = handle.snapshot.summaryText(),
      errorCode = errorCode,
      errorMessage = errorMessage,
      metadata = subAgentHandleMetadata(handle) + mapOf(
        SubAgentMetadataKeys.CONTROL_TOOL to call.toolName.lowercase(),
      ),
    )
  }

  private fun childResultToWaitAgentToolResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    childResult: ExecutionResult,
    childApprovalResume: SubAgentApprovalResume?,
  ): AgentToolResult {
    val metadata = subAgentHandleMetadata(handle) + mapOf(
      SubAgentMetadataKeys.CONTROL_TOOL to call.toolName.lowercase(),
    )
    return when (childResult.status) {
      ExecutionStatus.SUCCESS -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = handle.snapshot.summaryText(),
        metadata = metadata,
      )

      ExecutionStatus.CANCELLED -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.CANCELLED,
        content = handle.snapshot.summaryText(),
        errorCode = "SUBAGENT_CANCELLED",
        errorMessage = childResult.errorMessage ?: "Delegated child run was cancelled.",
        metadata = metadata,
      )

      ExecutionStatus.DENIED -> AgentToolResult(
        toolName = call.toolName,
        status = if (childApprovalResume != null) AgentToolResultStatus.DENIED else AgentToolResultStatus.FAILED,
        content = handle.snapshot.summaryText(),
        errorCode = if (childApprovalResume != null) {
          childResult.errorCode ?: ERROR_APPROVAL_REQUIRED
        } else {
          "SUBAGENT_POLICY_BLOCKED"
        },
        errorMessage = childResult.errorMessage ?: if (childApprovalResume != null) {
          "Delegated child run needs approval before it can continue."
        } else {
          "Delegated child run was blocked by policy."
        },
        metadata = metadata,
      )

      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> AgentToolResult(
        toolName = call.toolName,
        status = if (childResult.status == ExecutionStatus.TIMEOUT) {
          AgentToolResultStatus.TIMEOUT
        } else {
          AgentToolResultStatus.FAILED
        },
        content = handle.snapshot.summaryText(),
        errorCode = childResult.errorCode ?: "SUBAGENT_FAILED",
        errorMessage = childResult.errorMessage ?: "Delegated child run failed.",
        metadata = metadata,
      )
    }
  }

  private fun childResultToSpawnAgentToolResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    delegationPlan: ToolPolicyPlan,
    childResult: ExecutionResult,
    childApprovalResume: SubAgentApprovalResume?,
  ): AgentToolResult {
    val waitLikeResult = childResultToWaitAgentToolResult(
      call = call,
      handle = handle,
      childResult = childResult,
      childApprovalResume = childApprovalResume,
    )
    return waitLikeResult.copy(
      toolName = call.toolName,
      metadata = toolDispatcher.taskDelegationResultMetadata(
        plan = delegationPlan,
        metadata = waitLikeResult.metadata,
      ),
    )
  }

  private fun storedSpawnAgentHandleResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    delegationPlan: ToolPolicyPlan,
  ): AgentToolResult {
    val storedResult = storedSubAgentHandleResult(
      call = call,
      handle = handle,
    )
    return storedResult.copy(
      metadata = toolDispatcher.taskDelegationResultMetadata(
        plan = delegationPlan,
        metadata = storedResult.metadata,
      ),
    )
  }

  private fun invalidSubAgentCallResult(
    call: AgentToolCall,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = call.toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "INVALID_SUBAGENT_TASK",
    errorMessage = message,
  )

  private fun prepareSubAgentDelegation(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    activeSkillCapsule: ActiveSkillCapsule?,
    toolName: String,
  ): PreparedSubAgentDelegationResult {
    val description = call.arguments.primitiveContent("description")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(call, "$toolName description must not be blank."),
      )
    val prompt = call.arguments.primitiveContent("prompt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(call, "$toolName prompt must not be blank."),
      )
    val subagentType = call.arguments.primitiveContent("subagent_type")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(call, "$toolName subagent_type must not be blank."),
      )
    val resolvedSubagentType = BuiltInSubAgentProfiles.normalizedRequestedId(subagentType)
      ?: subagentType
    val profile = BuiltInSubAgentProfiles.resolve(subagentType)
      ?: return PreparedSubAgentDelegationResult.Invalid(
        invalidSubAgentCallResult(
          call = call,
          message = "Unknown $toolName subagent_type '$subagentType'.",
        ),
      )
    val requestedContextMode = call.arguments.primitiveContent("context_mode")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val resolvedContextMode = when {
      requestedContextMode == null -> profile.defaultContextMode
      else -> {
        val requestedMode = SubAgentContextMode.fromWireValue(requestedContextMode)
          ?: return PreparedSubAgentDelegationResult.Invalid(
            invalidSubAgentCallResult(
              call = call,
              message = "Unknown $toolName context_mode '$requestedContextMode'. Expected one of: ${SubAgentContextMode.publicWireValuesDescription()}.",
            ),
          )
        if (!requestedMode.publicControlPlaneEnabled) {
          return PreparedSubAgentDelegationResult.Invalid(
            invalidSubAgentCallResult(
              call = call,
              message = "Unsupported $toolName context_mode '$requestedContextMode'. Expected one of: ${SubAgentContextMode.publicWireValuesDescription()}. mirrored is reserved for internal-only child-runtime flows.",
            ),
          )
        }
        requestedMode
      }
    }
    val parentDepth = task.metadata[SubAgentMetadataKeys.DEPTH]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toIntOrNull()
      ?: 0
    val childDepth = parentDepth + 1
    if (childDepth > config.maxSubAgentDepth) {
      return PreparedSubAgentDelegationResult.Invalid(
        AgentToolResult(
          toolName = call.toolName,
          status = AgentToolResultStatus.FAILED,
          content = "$toolName delegation depth exceeded the configured child-runtime limit.",
          errorCode = "SUBAGENT_DEPTH_EXCEEDED",
          errorMessage = "$toolName delegation depth exceeded the configured child-runtime limit.",
          metadata = mapOf(
            "subagentType" to resolvedSubagentType,
            "subagentDepth" to childDepth.toString(),
            "maxSubAgentDepth" to config.maxSubAgentDepth.toString(),
          ),
        ),
      )
    }
    val childTask = SubAgentTask(
      description = description,
      prompt = prompt,
      subagentType = resolvedSubagentType,
      contextMode = resolvedContextMode,
      parentRunId = runIdFor(task),
      parentTaskId = task.id,
      parentTurn = turn,
      depth = childDepth,
      activeSkillName = activeSkillCapsule?.name,
      activeSkillActivationSource = activeSkillCapsule?.activationSource,
      activeSkillPinned = activeSkillCapsule?.pinned ?: false,
    )
    val delegationPlan = toolDispatcher.planTaskDelegation(
      task = task,
      toolName = toolName,
      description = description,
      prompt = prompt,
      subagentType = resolvedSubagentType,
      contextMode = childTask.contextMode.wireValue,
      allowedToolNames = profile.allowedToolNames,
    )
    toolDispatcher.gateTaskDelegation(
      plan = delegationPlan,
      toolName = toolName,
    )?.let { deniedResult ->
      return PreparedSubAgentDelegationResult.Invalid(deniedResult.copy(toolName = call.toolName))
    }
    return PreparedSubAgentDelegationResult.Ready(
      PreparedSubAgentDelegation(
        profile = profile,
        childTask = childTask,
        delegationPlan = delegationPlan,
      ),
    )
  }

  private fun createSubAgentHandle(
    task: AgentTask,
    prepared: PreparedSubAgentDelegation,
    agentId: String? = null,
    childRunId: String? = null,
    childTaskId: String? = null,
  ): SubAgentHandleState {
    val createdAt = clock()
    return SubAgentHandleState.queued(
      agentId = agentId ?: "agent-${UUID.randomUUID().toString().take(8)}",
      childRunId = childRunId ?: "subagent-${runIdFor(task)}-${prepared.childTask.parentTurn}-${UUID.randomUUID().toString().take(8)}",
      childTaskId = childTaskId ?: "subagent-task-${UUID.randomUUID().toString().take(8)}",
      description = prepared.childTask.description,
      prompt = prepared.childTask.prompt,
      subagentType = prepared.profile.id,
      contextMode = prepared.childTask.contextMode.wireValue,
      parentRunId = prepared.childTask.parentRunId,
      parentTaskId = prepared.childTask.parentTaskId,
      parentTurn = prepared.childTask.parentTurn,
      depth = prepared.childTask.depth,
      activeSkillName = prepared.childTask.activeSkillName,
      activeSkillActivationSource = prepared.childTask.activeSkillActivationSource,
      activeSkillPinned = prepared.childTask.activeSkillPinned,
      createdAtEpochMs = createdAt,
    )
  }

  private fun resolveInheritedSubAgentSkillCapsule(
    handle: SubAgentHandleState,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): ActiveSkillCapsule? = resolveActiveSkillCapsule(
    activeSkillName = handle.activeSkillName ?: activeSkillCapsule?.name,
    activationSource = handle.activeSkillActivationSource ?: activeSkillCapsule?.activationSource,
    pinned = if (handle.activeSkillName != null) {
      handle.activeSkillPinned
    } else {
      activeSkillCapsule?.pinned
    },
  ) ?: activeSkillCapsule

  private fun continuationResume(): SubAgentApprovalResume? =
    pendingApprovedSubAgentResume ?: pendingRejectedSubAgentResume

  private fun findContinuationHandle(
    handles: Map<String, SubAgentHandleState>,
  ): SubAgentHandleState? {
    val resume = continuationResume() ?: return null
    return handles.values.firstOrNull { handle -> resumeMatchesHandle(resume, handle, handles) }
  }

  private fun childResultToTaskToolResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    delegationPlan: ToolPolicyPlan,
    childResult: ExecutionResult,
    compressedChildResult: SubAgentExecutionSnapshot,
  ): AgentToolResult {
    val childTurnCount = childResult.metadata["turnCount"].orEmpty()
    val childToolCallCount = childResult.metadata["toolCallCount"].orEmpty()
    val childLiveContext = SubAgentLiveContextSnapshot.fromRuntimeMetadata(childResult.metadata)
    val childApprovalResume = childApprovalResume(
      childResult = childResult,
      agentId = handle.agentId,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
    )
    val childApprovalMetadata = childApprovalMetadata(
      childMetadata = childResult.metadata,
      childApprovalResume = childApprovalResume,
    )
    val baseMetadata = linkedMapOf(
      "agentId" to handle.agentId,
      "subagentType" to handle.subagentType,
      "subagentContextMode" to handle.contextMode,
      "subagentDepth" to handle.depth.toString(),
      SubAgentMetadataKeys.CONTROL_TOOL to call.toolName.lowercase(),
      "childRunId" to handle.childRunId,
      "childTaskId" to childResult.taskId,
      "childExecutionStatus" to childResult.status.name,
    ).apply {
      if (childTurnCount.isNotBlank()) {
        put("childTurnCount", childTurnCount)
      }
      if (childToolCallCount.isNotBlank()) {
        put("childToolCallCount", childToolCallCount)
      }
      putAll(childLiveContext.toMetadataMap())
      putAll(compressedChildResult.metadata())
      putAll(childApprovalMetadata)
    }
    val metadataWithPolicy = toolDispatcher.taskDelegationResultMetadata(
      plan = delegationPlan,
      metadata = baseMetadata,
    )
    return when (childResult.status) {
      ExecutionStatus.SUCCESS -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.SUCCESS,
        content = compressedChildResult.summaryText(),
        metadata = metadataWithPolicy,
      )

      ExecutionStatus.CANCELLED -> AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.CANCELLED,
        content = compressedChildResult.summaryText(),
        errorCode = "SUBAGENT_CANCELLED",
        errorMessage = childResult.errorMessage ?: "Delegated child run was cancelled.",
        metadata = metadataWithPolicy,
      )

      ExecutionStatus.DENIED -> AgentToolResult(
        toolName = call.toolName,
        status = if (childApprovalResume != null) {
          AgentToolResultStatus.DENIED
        } else {
          AgentToolResultStatus.FAILED
        },
        content = compressedChildResult.summaryText(),
        errorCode = if (childApprovalResume != null) {
          childResult.errorCode ?: ERROR_APPROVAL_REQUIRED
        } else {
          "SUBAGENT_POLICY_BLOCKED"
        },
        errorMessage = childResult.errorMessage ?: if (childApprovalResume != null) {
          "Delegated child run needs approval before it can continue."
        } else {
          "Delegated child run was blocked by policy."
        },
        metadata = metadataWithPolicy,
      )

      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> AgentToolResult(
        toolName = call.toolName,
        status = if (childResult.status == ExecutionStatus.TIMEOUT) {
          AgentToolResultStatus.TIMEOUT
        } else {
          AgentToolResultStatus.FAILED
        },
        content = compressedChildResult.summaryText(),
        errorCode = childResult.errorCode ?: "SUBAGENT_FAILED",
        errorMessage = childResult.errorMessage ?: "Delegated child run failed.",
        metadata = metadataWithPolicy,
      )
    }
  }

  private fun emitSubAgentEvent(
    task: AgentTask,
    turn: Int,
    phase: OpenCraySubAgentPhase,
    childTask: SubAgentTask,
    childRunId: String,
    childTaskId: String,
    summary: String?,
    snapshot: SubAgentExecutionSnapshot,
    liveContext: SubAgentLiveContextSnapshot? = null,
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCraySubAgentEvent(
        runId = runIdFor(task),
        taskId = task.id,
        phase = phase,
        childRunId = childRunId,
        childTaskId = childTaskId,
        label = childTask.description,
        subagentType = childTask.subagentType,
        contextMode = childTask.contextMode.wireValue,
        depth = childTask.depth,
        summary = summary,
        executionState = snapshot.state,
        continuationKind = snapshot.continuationKind,
        liveContext = liveContext?.takeUnless { it.isEmpty },
        resumable = snapshot.resumable,
        requiresUserAction = snapshot.requiresUserAction,
        isHighRisk = snapshot.isHighRisk,
        turn = turn,
        emittedAtEpochMs = clock(),
      ),
    )
  }

  private fun childApprovalResume(
    childResult: ExecutionResult,
    agentId: String? = null,
    childRunId: String? = null,
    childTaskId: String? = null,
  ): SubAgentApprovalResume? {
    val encodedResume = SubAgentApprovalResumeMetadata.decodeFromMetadata(
      metadata = childResult.metadata,
      json = config.json,
    )
    if (encodedResume != null) {
      return encodedResume.copy(
        agentId = encodedResume.agentId ?: agentId,
        childRunId = encodedResume.childRunId ?: childRunId,
        childTaskId = encodedResume.childTaskId ?: childTaskId,
      )
    }
    val approvedToolName = approvalToolName(childResult.metadata) ?: return null
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = childResult.metadata,
      json = config.json,
    ) ?: return null
    return SubAgentApprovalResume(
      approvedToolName = approvedToolName,
      promptResumeState = promptResumeState,
      isHighRisk = childResult.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED,
      agentId = agentId,
      childRunId = childRunId,
      childTaskId = childTaskId,
    )
  }

  private fun childApprovalMetadata(
    childMetadata: Map<String, String>,
    childApprovalResume: SubAgentApprovalResume?,
  ): Map<String, String> {
    val forwarded = childMetadata.filterKeys { key -> key in CHILD_APPROVAL_METADATA_KEYS }
    if (childApprovalResume == null) {
      return forwarded
    }
    return forwarded + SubAgentApprovalResumeMetadata.encodeToMetadata(
      resume = childApprovalResume,
      json = config.json,
    )
  }

  private fun approvalToolName(metadata: Map<String, String>): String? =
    metadata[OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: metadata["normalizedToolName"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: metadata["canonicalToolName"]
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: metadata["toolName"]
        ?.trim()
        ?.takeIf(String::isNotBlank)

  private fun maybeShortCircuitDuplicateDiscoveryCall(
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
  ): AgentToolResult? {
    val duplicateHit = recentToolObservationSupport.findDuplicateDiscoveryCall(
      messages = transcript,
      call = call,
    ) ?: return null
    return AgentToolResult(
      toolName = duplicateHit.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = buildDuplicateDiscoveryObservation(duplicateHit),
      metadata = duplicateHit.metadata + mapOf(
        "duplicateGuard" to "true",
        "duplicateGuardSignature" to duplicateHit.signature,
      ),
    )
  }

  private fun buildDuplicateDiscoveryObservation(hit: DuplicateDiscoveryToolHit): String = buildString {
    appendLine("Identical discovery call already succeeded earlier in this task.")
    appendLine("Reuse the recent workspace observation context instead of repeating the same call.")
    appendLine("previous_observation=${hit.summaryLine}")
    appendLine()
    append(hit.excerpt)
  }.trim()

  @Suppress("UNUSED_PARAMETER")
  private fun buildSingleActionReminderObservation(
    nativeToolCallingEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
  ): String = buildString {
    appendLine("Protocol note: return only the next step on each turn.")
    if (nativeToolCallingEnabled) {
      appendLine("Use native tool calling for the next tool action.")
    } else {
      appendLine("Return the next action as a single JSON object.")
    }
    appendLine("You may include one short public commentary update before that action.")
    appendLine("Do not include a final answer alongside a tool_call.")
    append("If you need multiple tools, call them one at a time across turns.")
  }.trim()

  private fun buildFinalAnswerSoonObservation(): String = buildString {
    appendLine("Turn budget note: after this turn, only one model turn remains.")
    appendLine("If you still need one last tool, use it now.")
    append("The next turn must return a final answer without calling another tool.")
  }.trim()

  private fun buildFinalAnswerRequiredObservation(
    legacyJsonFallbackEnabled: Boolean,
  ): String = buildString {
    appendLine("Turn budget note: this is the last allowed model turn.")
    appendLine("Do not call any more tools.")
    if (legacyJsonFallbackEnabled) {
      append("Return the best user-facing final answer now. If plain assistant text is sufficient, prefer that. If you must attach existing artifacts, return exactly one JSON final action.")
    } else {
      append("Return the best user-facing final answer now as plain assistant text. If you must attach existing artifacts, return exactly one JSON final action instead.")
    }
  }.trim()

  private fun promptCheckpointState(
    cursor: PromptTurnCursor,
    turnIndex: Int,
    pendingActions: List<AgentModelAction> = emptyList(),
    nextActionIndex: Int = 0,
    requiresSingleActionReminder: Boolean = false,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): OpenCrayPromptResumeState = OpenCrayPromptResumeState(
    transcript = cursor.transcript.toList(),
    turnIndex = turnIndex,
    toolCallCount = cursor.toolCallCount,
    pendingActions = pendingActions.map { action -> action.toSerializableModelAction() },
    nextActionIndex = nextActionIndex,
    requiresSingleActionReminder = requiresSingleActionReminder,
    activeSkillName = cursor.activeSkillName,
    activeSkillActivationSource = cursor.activeSkillActivationSource,
    activeSkillPinned = cursor.activeSkillPinned,
    localContinuationEnvelope = localContinuationContextPrompts
      ?.takeIf { prompts -> prompts.isNotEmpty() }
      ?.let { frontContextPrompts ->
        localContinuationStableAnchor?.let { stableAnchor ->
          buildLocalContinuationEnvelope(
            cursor = cursor,
            frontContextPrompts = frontContextPrompts,
            stableAnchor = stableAnchor,
            gatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
            toolPoolFingerprint = localContinuationToolPoolFingerprint
              ?: cursor.localContinuationEnvelope?.toolPoolFingerprint
              ?: "absent",
            toolSchemaFingerprint = localContinuationToolSchemaFingerprint
              ?: cursor.localContinuationEnvelope?.toolSchemaFingerprint
              ?: "absent",
            requestSettingsFingerprint = localContinuationRequestSettingsFingerprint
              ?: cursor.localContinuationEnvelope?.requestSettingsFingerprint
              ?: "absent",
          )?.toSerializable()
        }
      }
      ?: cursor.localContinuationEnvelope?.toSerializable(),
    responsesPreviousResponseId = cursor.responsesPreviousResponseId,
    responsesProviderLineageId = cursor.responsesProviderLineageId,
    responsesLineageTrusted = cursor.responsesLineageTrusted,
    responsesContinuationShape = cursor.responsesContinuationShape?.toSerializable(),
    responsesPendingMessages = cursor.responsesPendingMessages.map(OpenCraySerializableGatewayMessage::from),
    replayToolResultProjections = cursor.replayToolResultProjections.toSortedMap(),
    subAgentHandles = synchronizedSubAgentHandles(cursor),
  )

  private fun promptCheckpointMetadata(
    boundary: OpenCrayPromptCheckpointBoundary,
    cursor: PromptTurnCursor,
    turnIndex: Int,
    pendingActions: List<AgentModelAction> = emptyList(),
    nextActionIndex: Int = 0,
    requiresSingleActionReminder: Boolean = false,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): Map<String, String> = OpenCrayPromptResumeMetadata.encodeToMetadata(
    state = promptCheckpointState(
      cursor = cursor,
      turnIndex = turnIndex,
      pendingActions = pendingActions,
      nextActionIndex = nextActionIndex,
      requiresSingleActionReminder = requiresSingleActionReminder,
      localContinuationContextPrompts = localContinuationContextPrompts,
      localContinuationStableAnchor = localContinuationStableAnchor,
      localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
      localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
      localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
      localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
    ),
    json = config.json,
    checkpointBoundary = boundary,
  )

  private fun emitPromptCheckpoint(
    boundary: OpenCrayPromptCheckpointBoundary,
    cursor: PromptTurnCursor,
    turnIndex: Int,
    emittedAtEpochMs: Long,
    toolName: String? = null,
    pendingActions: List<AgentModelAction> = emptyList(),
    nextActionIndex: Int = 0,
    requiresSingleActionReminder: Boolean = false,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ) {
    config.promptCheckpointSink(
      OpenCrayPromptCheckpointEmission(
        boundary = boundary,
        state = promptCheckpointState(
          cursor = cursor,
          turnIndex = turnIndex,
          pendingActions = pendingActions,
          nextActionIndex = nextActionIndex,
          requiresSingleActionReminder = requiresSingleActionReminder,
          localContinuationContextPrompts = localContinuationContextPrompts,
          localContinuationStableAnchor = localContinuationStableAnchor,
          localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
          localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
          localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
          localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
        ),
        emittedAtEpochMs = emittedAtEpochMs,
        toolName = toolName,
      ),
    )
  }

  private fun emitInternalCheckpointJournalMarker(
    task: AgentTask,
    turn: Int,
    boundary: OpenCrayPromptCheckpointBoundary,
    cursor: PromptTurnCursor,
    turnIndex: Int,
    emittedAtEpochMs: Long,
    pendingActions: List<AgentModelAction> = emptyList(),
    nextActionIndex: Int = 0,
    requiresSingleActionReminder: Boolean = false,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ) {
    if (
      boundary != OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST &&
        boundary != OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED
    ) {
      return
    }
    eventSink.onRunEvent(
      task = task,
      event = OpenCraySupplementEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        entryId = "checkpoint-${boundary.wireValue}-${UUID.randomUUID().toString().take(8)}",
        text = "",
        checkpoint = INTERNAL_PROMPT_CHECKPOINT_MARKER,
        metadata = promptCheckpointMetadata(
          boundary = boundary,
          cursor = cursor,
          turnIndex = turnIndex,
          pendingActions = pendingActions,
          nextActionIndex = nextActionIndex,
          requiresSingleActionReminder = requiresSingleActionReminder,
          localContinuationContextPrompts = localContinuationContextPrompts,
          localContinuationStableAnchor = localContinuationStableAnchor,
          localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
          localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
          localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
          localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
        ),
        emittedAtEpochMs = emittedAtEpochMs,
      ),
    )
  }

  private fun promptCheckpointMetadataAfterActionIndex(
    boundary: OpenCrayPromptCheckpointBoundary,
    cursor: PromptTurnCursor,
    batchActions: List<AgentModelAction>,
    nextActionIndex: Int,
    requiresSingleActionReminder: Boolean,
    localContinuationContextPrompts: List<String>? = null,
    localContinuationStableAnchor: String? = null,
    localContinuationGatewayMessagesEnabled: Boolean = false,
    localContinuationToolPoolFingerprint: String? = null,
    localContinuationToolSchemaFingerprint: String? = null,
    localContinuationRequestSettingsFingerprint: String? = null,
  ): Map<String, String> = if (nextActionIndex < batchActions.size) {
    promptCheckpointMetadata(
      boundary = boundary,
      cursor = cursor,
      turnIndex = cursor.turn,
      pendingActions = batchActions,
      nextActionIndex = nextActionIndex,
      requiresSingleActionReminder = requiresSingleActionReminder,
      localContinuationContextPrompts = localContinuationContextPrompts,
      localContinuationStableAnchor = localContinuationStableAnchor,
      localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
      localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
      localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
      localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
    )
  } else {
    promptCheckpointMetadata(
      boundary = boundary,
      cursor = cursor,
      turnIndex = cursor.turn + 1,
      localContinuationContextPrompts = localContinuationContextPrompts,
      localContinuationStableAnchor = localContinuationStableAnchor,
      localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
      localContinuationToolPoolFingerprint = localContinuationToolPoolFingerprint,
      localContinuationToolSchemaFingerprint = localContinuationToolSchemaFingerprint,
      localContinuationRequestSettingsFingerprint = localContinuationRequestSettingsFingerprint,
    )
  }

  private fun emitToolResultEvent(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    result: AgentToolResult,
    diagnostics: PromptRunDiagnostics,
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayToolResultEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        call = call,
        result = result,
        emittedAtEpochMs = clock(),
      ),
    )
    diagnostics.toolResultEventEmitted = true
    emitMemoryRetrievalEvent(
      task = task,
      turn = turn,
      call = call,
      result = result,
    )
  }

  private fun todoPlanClosureObservation(
    cursor: PromptTurnCursor,
  ): String? {
    if (!cursor.todoWriteUsed) {
      return null
    }
    val activeTodo = toolDispatcher.todoSnapshot()
      .firstOrNull { entry -> entry.status == AgentTodoStatus.IN_PROGRESS }
      ?: return null
    return buildTodoPlanClosureObservation(activeTodo)
  }

  private fun buildTodoPlanClosureObservation(
    activeTodo: AgentTodoEntry,
  ): String = buildString {
    appendLine("Plan note: TodoWrite still has an in_progress item.")
    appendLine("Before returning the final answer, update TodoWrite so the active item is no longer in_progress.")
    appendLine("If the work is done, mark it completed or clear the plan intentionally.")
    append("Active todo: ${activeTodo.content}")
  }.trim()

  @Suppress("UNUSED_PARAMETER")
  private fun buildProtocolRecoveryObservation(
    nativeToolCallingEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    rawOutput: String,
    reason: String,
  ): String = buildString {
    when {
      nativeToolCallingEnabled ->
        appendLine("Protocol error: use native tool calling for the next tool action, or return a plain final answer when you are done.")
      else ->
        appendLine("Protocol error: return exactly one JSON object whose action is commentary, tool_call, or final.")
    }
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    appendLine("If you include commentary, keep it public, short, and non-sensitive.")
    appendLine("If you need multiple tools, call only the next tool now and wait for the next turn.")
    if (nativeToolCallingEnabled) {
      appendLine("If the final answer must attach existing artifacts, return exactly one JSON final action instead of plain text.")
    }
    appendLine("Do not explain the protocol. Do not answer in prose unless you emit type=final.")
    appendLine("Reason: $reason")
    val preview = rawOutput.trim().take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS)
    if (preview.isNotBlank()) {
      appendLine("Previous response preview:")
      append(preview)
    }
  }.trim()

  @Suppress("UNUSED_PARAMETER")
  private fun buildEmptyResponseRecoveryObservation(
    nativeToolCallingEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    detail: String,
    reasoningText: String?,
    rawOutput: String?,
  ): String = buildString {
    when {
      nativeToolCallingEnabled ->
        appendLine("Response error: the previous model response did not produce a usable native tool call, commentary update, or final answer.")
      else ->
        appendLine("Response error: the previous model response did not produce a usable commentary, tool_call, or final action.")
    }
    appendLine("Return only the next valid step now.")
    appendLine("Do not emit hidden reasoning by itself.")
    appendLine("If you need a tool, emit one valid tool call with schema-correct arguments.")
    if (nativeToolCallingEnabled) {
      appendLine("If you are done, return the final user-facing answer as plain assistant text, or one JSON final action if you must attach artifacts.")
    } else {
      appendLine("If you are done, return the final user-facing answer.")
    }
    appendLine("Reason: $detail")
    reasoningText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reasoning ->
        appendLine("Provider reasoning preview:")
        appendLine(reasoning.take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS))
      }
    rawOutput
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { preview ->
        appendLine("Previous response preview:")
        append(preview.take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS))
      }
  }.trim()

  private fun String?.isTransientGatewayFailureCode(): Boolean {
    val normalized = this?.trim()?.uppercase() ?: return false
    if (normalized == "PROVIDER_TRANSPORT_ERROR" || normalized == "PROVIDER_CLIENT_EXCEPTION") {
      return true
    }
    if (!normalized.startsWith("HTTP_")) {
      return false
    }
    val statusCode = normalized.removePrefix("HTTP_").toIntOrNull() ?: return false
    return statusCode == 408 ||
      statusCode == 409 ||
      statusCode == 425 ||
      statusCode == 429 ||
      statusCode in 500..599
  }

  private fun finalTurnSystemPromptAppendix(
    legacyJsonFallbackEnabled: Boolean,
  ): String = if (legacyJsonFallbackEnabled) {
    "[Turn Budget]\nThis is the last allowed model turn. Do not call any more tools. Return the final user-facing answer now. If plain assistant text is sufficient, prefer that. If you must attach existing artifacts, return exactly one JSON final action."
  } else {
    "[Turn Budget]\nThis is the last allowed model turn. Do not call any more tools. Return the final user-facing answer now as plain assistant text. If you must attach existing artifacts, return exactly one JSON final action instead."
  }

  private fun toolPolicyKey(toolName: String): String = when (toolName.trim().lowercase()) {
    "ls",
    "list",
    "workspace_list_files",
    -> "ls"

    "read",
    "workspace_read_file",
    -> "read"

    "write",
    "workspace_write_file",
    -> "write"

    "grep" -> "grep"
    "glob" -> "glob"
    "edit" -> "edit"
    "multiedit" -> "multiedit"
    "importfile",
    "import",
    "workspace_import_file",
    "import_chat_attachment",
    -> "importfile"

    "workspace_move_file" -> "move"
    "workspace_delete_file" -> "delete"
    "bash",
    "command_exec",
    -> "bash"

    "python_exec" -> "python_exec"
    "websearch" -> "websearch"
    "webfetch" -> "webfetch"
    "generateimage",
    "imagegenerate",
    -> "generateimage"

    "synthesizespeech",
    "texttospeech",
    "tts",
    -> "synthesizespeech"

    "todowrite" -> "todowrite"
    "processstart" -> "processstart"
    "processlist" -> "processlist"
    "processread" -> "processread"
    "processwait" -> "processwait"
    "processterminate" -> "processterminate"
    "skills_list" -> "skills_list"
    "skill_read" -> "skill_read"
    "memory_search" -> "memory_search"
    "memory_get" -> "memory_get"
    "session_search" -> "session_search"
    "session_get" -> "session_get"
    "past_session_search" -> "past_session_search"
    "past_session_get" -> "past_session_get"
    "mcp_list_servers" -> "mcp_list_servers"
    else -> toolName.trim().lowercase()
  }

  private sealed interface AgentModelAction {
    data class Commentary(
      val text: String,
      val stage: String? = null,
    ) : AgentModelAction

    data class Final(
      val answer: String,
      val responseFormat: String,
      val attachments: List<OpenCrayFinalAttachment> = emptyList(),
    ) : AgentModelAction

    data class ToolCall(
      val call: AgentToolCall,
    ) : AgentModelAction
  }

  private data class PromptTurnCursor(
    val transcript: MutableList<RuntimeConversationMessage>,
    var sessionContext: AgentRuntimeSessionContext,
    var turn: Int,
    var toolCallCount: Int,
    var todoWriteUsed: Boolean,
    var activeSkillName: String?,
    var activeSkillActivationSource: String?,
    var activeSkillPinned: Boolean,
    var nextSyntheticToolCallSequence: Int,
    var legacyJsonFallbackEnabled: Boolean,
    var responsesPreviousResponseId: String?,
    var responsesProviderLineageId: String?,
    var responsesLineageTrusted: Boolean,
    var responsesFullReplayRequired: Boolean,
    var responsesContinuationShape: ResponsesContinuationShape?,
    val responsesPendingMessages: MutableList<LiteLlmGatewayMessage>,
    val replayToolResultProjections: MutableMap<String, FrozenToolResultReplayProjection>,
    var localContinuationEnvelope: LocalContinuationEnvelope?,
    val subAgentHandles: MutableMap<String, SubAgentHandleState>,
    val subAgentExecutionLock: Any,
  )

  private data class PromptSupplementPayload(
    val text: String,
    val attachments: List<RuntimeConversationAttachment>,
  )

  private data class PromptRunDiagnostics(
    var nativeToolCallRequested: Boolean = false,
    var parsedToolCallObserved: Boolean = false,
    var fallbackParserAttempted: Boolean = false,
    var fallbackParserSucceeded: Boolean = false,
    var toolCallEventEmitted: Boolean = false,
    var toolResultEventEmitted: Boolean = false,
    var lastSuccessfulToolName: String? = null,
    var providerReasoningObserved: Boolean = false,
    var providerReasoningTurnCount: Int = 0,
    var providerReasoningChars: Int = 0,
    var llmRetryCount: Int = 0,
    var emptyResponseRecoveryCount: Int = 0,
    var responsesContinuationRecoveryCount: Int = 0,
    var responsesContinuationRecoveryLastReason: String? = null,
    var localContinuationUsedCount: Int = 0,
    var localContinuationFallbackCount: Int = 0,
    var localContinuationLastMode: LocalContinuationMode = LocalContinuationMode.DISABLED,
    var localContinuationLastReason: String? = null,
    var contextCacheBreakReason: String? = null,
    var contextCacheShapeMetadata: Map<String, String> = emptyMap(),
  )

  private fun PromptRunDiagnostics.recordContextCacheShapeMetadata(
    metadata: Map<String, String>,
  ) {
    contextCacheShapeMetadata = metadata
  }

  private data class GatewayMessagePlan(
    val messages: List<LiteLlmGatewayMessage>,
    val mode: LocalContinuationMode,
    val reason: String? = null,
    val previousResponseId: String? = null,
  )

  private data class ResponsesContinuationDecision(
    val previousResponseId: String? = null,
    val reason: String,
  )

  private data class PendingSubAgentApprovalContinuation(
    val resume: SubAgentApprovalResume,
    val approved: Boolean,
  )

  private data class SubAgentHandleLifecycleExecution(
    val handle: SubAgentHandleState,
    val childResult: ExecutionResult,
    val childApprovalResume: SubAgentApprovalResume?,
  )

  private data class PreparedSubAgentMailboxDelivery(
    val handle: SubAgentHandleState,
    val promptResumeState: OpenCrayPromptResumeState?,
    val includeMailboxMessagesInPrompt: Boolean,
  )

  private data class PreparedSubAgentDelegation(
    val profile: SubAgentProfile,
    val childTask: SubAgentTask,
    val delegationPlan: ToolPolicyPlan,
  )

  private sealed interface PreparedSubAgentDelegationResult {
    data class Ready(
      val delegation: PreparedSubAgentDelegation,
    ) : PreparedSubAgentDelegationResult

    data class Invalid(
      val result: AgentToolResult,
    ) : PreparedSubAgentDelegationResult
  }

  private data class LocalContinuationEnvelope(
    val stableAnchor: String,
    val frontContextZones: FrontContextZones,
    val toolPoolFingerprint: String? = null,
    val toolSchemaFingerprint: String? = null,
    val requestSettingsFingerprint: String? = null,
    val transcriptFrontier: List<RuntimeConversationMessage>,
    val gatewayMessages: List<LiteLlmGatewayMessage>,
  ) {
    val frontContextPrompts: List<String>
      get() = frontContextZones.promptsInTransportOrder
  }

  private fun LocalContinuationEnvelope.toSerializable(): OpenCraySerializableLocalContinuationEnvelope =
    OpenCraySerializableLocalContinuationEnvelope(
      stableAnchor = stableAnchor,
      frontContextPrompts = frontContextPrompts,
      durableContextPrompt = frontContextZones.durableContextPrompt.takeIf(String::isNotBlank),
      dynamicContextPrompt = frontContextZones.dynamicContextPrompt.takeIf(String::isNotBlank),
      toolPoolFingerprint = toolPoolFingerprint,
      toolSchemaFingerprint = toolSchemaFingerprint,
      requestSettingsFingerprint = requestSettingsFingerprint,
      transcriptFrontier = transcriptFrontier,
      gatewayMessages = gatewayMessages.map(OpenCraySerializableGatewayMessage::from),
    )

  private data class ResponsesContinuationShape(
    val stableAnchor: String,
    val frontContextZones: FrontContextZones,
    val toolPoolFingerprint: String,
    val toolSchemaFingerprint: String,
    val requestSettingsFingerprint: String,
  )

  private fun ResponsesContinuationShape.toSerializable(): OpenCraySerializableResponsesContinuationShape =
    OpenCraySerializableResponsesContinuationShape(
      stableAnchor = stableAnchor,
      durableContextPrompt = frontContextZones.durableContextPrompt.takeIf(String::isNotBlank),
      dynamicContextPrompt = frontContextZones.dynamicContextPrompt.takeIf(String::isNotBlank),
      toolPoolFingerprint = toolPoolFingerprint,
      toolSchemaFingerprint = toolSchemaFingerprint,
      requestSettingsFingerprint = requestSettingsFingerprint,
    )

  private enum class LocalContinuationMode(val wireValue: String) {
    DISABLED("disabled"),
    FULL_REBUILD("full_rebuild"),
    LOCAL_FRONT_PATCH("local_front_patch"),
    LOCAL_DELTA("local_delta"),
    RESPONSES_NATIVE("responses_native"),
  }

  private data class ParsedGatewayActionBatch(
    val batch: ParsedModelActionBatch,
    val usedLegacyJsonFallback: Boolean = false,
  )

  private data class ParsedToolResultObservation(
    val toolCallId: String? = null,
    val toolName: String,
    val content: String,
    val isError: Boolean,
    val structuredContent: JsonObject? = null,
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val metadata: Map<String, String> = emptyMap(),
  )

  private data class MemoryRetrievalTrace(
    val operation: String,
    val toolName: String,
    val surface: String? = null,
    val query: String? = null,
    val queryTerms: List<String> = emptyList(),
    val resultCount: Int? = null,
    val corpusFileCount: Int? = null,
    val recordIds: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val lineRanges: List<String> = emptyList(),
    val path: String? = null,
    val fromLine: Int? = null,
    val returnedLineCount: Int? = null,
    val totalLineCount: Int? = null,
  )

  private sealed interface PromptBatchExecutionOutcome {
    data object Continue : PromptBatchExecutionOutcome

    data class Terminal(
      val result: ExecutionResult,
    ) : PromptBatchExecutionOutcome
  }

  private data class ParallelToolActionStep(
    val index: Int,
    val call: AgentToolCall,
  )

  private data class ParallelToolDispatch(
    val step: ParallelToolActionStep,
    val result: AgentToolResult,
  )

  private sealed interface ParallelToolActionGroupOutcome {
    data class Advance(
      val nextActionIndex: Int,
      val shouldContinueBatch: Boolean,
    ) : ParallelToolActionGroupOutcome

    data class Terminal(
      val result: ExecutionResult,
    ) : ParallelToolActionGroupOutcome
  }

  private data class JsonEnvelope(
    val json: String,
    val leadingText: String,
  )

  private data class JsonSequence(
    val envelopes: List<JsonEnvelope>,
    val trailingText: String,
  )

  private data class ParsedActionObject(
    val actions: List<AgentModelAction>,
    val ignoredNonActionContent: Boolean = false,
  )

  private sealed interface ParsedModelActionBatch {
    data class Actions(
      val actions: List<AgentModelAction>,
      val ignoredNonActionContent: Boolean,
    ) : ParsedModelActionBatch {
      val requiresSingleActionReminder: Boolean
        get() =
          ignoredNonActionContent ||
            actions.count { action -> action !is AgentModelAction.Commentary } > 1 ||
            actions.count { action -> action is AgentModelAction.Commentary } > 1
    }

    data class ProtocolError(
      val reason: String,
    ) : ParsedModelActionBatch
  }

  private sealed interface GatewayTurnExecution {
    data class Completed(
      val result: LiteLlmGatewayResult,
      val retryBudgetExhausted: Boolean = false,
    ) : GatewayTurnExecution

    data object Cancelled : GatewayTurnExecution
  }

  private companion object {
    const val NON_RESPONSES_CONTEXT_CACHE_CONTRACT_VERSION: String = "non_responses_front_zone_v1"
    const val RESPONSES_PROTOCOL: String = "openai_responses"
    const val HIDDEN_METADATA_PREFIX: String = "_host."
    private const val HOST_METADATA_BASE_URL: String = "${HIDDEN_METADATA_PREFIX}baseUrl"
    const val HOST_PROVIDER_ID_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}providerId"
    const val RUN_ID_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}runId"
    const val PROMPT_USER_TEXT_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}promptUserText"
    const val PROMPT_RUNTIME_ATTACHMENTS_JSON_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}promptRuntimeAttachmentsJson"
    const val SUPPLEMENT_CHECKPOINT_TURN_START: String = "turn_start"
    const val SUPPLEMENT_CHECKPOINT_POST_TOOL_PRE_MODEL: String = "post_tool_pre_model"
    const val INTERNAL_PROMPT_CHECKPOINT_MARKER: String = "internal_prompt_checkpoint"
    const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    const val ERROR_SKILL_TOOL_POLICY_BLOCKED: String = "SKILL_TOOL_POLICY_BLOCKED"
    const val MAX_PROTOCOL_ERROR_PREVIEW_CHARS: Int = 600
    const val MAX_STRUCTURED_TOOL_CALL_ERROR_COUNT: Int = 3
    const val RECOVERABLE_LLM_RETRY_SLEEP_CHUNK_MS: Long = 250L
    val TERMINAL_PROVIDER_TIMEOUT_STATUS_CODES: Set<String> = setOf("449", "499")
    const val ACTIVATION_SOURCE_SKILL_READ: String = "skill_read"
    const val ACTIVATION_SOURCE_SKILL_EXECUTE: String = "skill_execute"
    const val ACTIVATION_SOURCE_IMPLICIT_SKILL: String = "implicit_skill"
    const val PENULTIMATE_TURN_SYSTEM_PROMPT_APPENDIX: String =
      "[Turn Budget]\nYou have two model turns left including this one. If another tool is still necessary, use at most one more tool now and be ready to answer on the next turn."
    val CHILD_APPROVAL_METADATA_KEYS: Set<String> = setOf(
      OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME,
      "normalizedToolName",
      "canonicalToolName",
      "toolName",
      "toolReason",
      "targetSummary",
      "primaryTargetPath",
      "secondaryTargetPath",
      "scriptPath",
      "query",
      "requestedUrl",
      "finalUrl",
      "processId",
      "targetKind",
      "workspaceRelation",
      "sourcePath",
      "destinationPath",
      "workingDirectory",
      "policyOutcome",
      "policyReasonCode",
      "approvalRisk",
    )
    val DEFAULT_ACTIVE_SKILL_EXEMPT_TOOL_KEYS: Set<String> = setOf("skills_list", "skill_read", "skill_execute")
  }
}

class OpenCrayAgentEngine(
  private val runtime: SessionTaskRuntime,
  private val clock: QueueClock = SystemQueueClock,
  private val queueConfig: SessionQueueConfig = SessionQueueConfig(),
) {
  fun create(
    sessionId: String,
    agentId: String,
    snapshotStore: SessionQueueSnapshotStore,
  ): AgentLoop = AgentLoop(
    queue = SessionQueue(
      sessionId = sessionId,
      agentId = agentId,
      runtime = runtime,
      snapshotStore = snapshotStore,
      clock = clock,
      config = queueConfig,
    ),
  )
}

