package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RUNTIME_INTERRUPTED
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmBuiltinToolDefinition
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
import com.opencray.llm.LiteLlmToolDefinition
import com.opencray.llm.LiteLlmToolChoice
import com.opencray.llm.LiteLlmToolChoiceMode
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
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentContextBuilder
import com.opencray.runtime.subagent.SubAgentContextBuildRequest
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentContextPolicy
import com.opencray.runtime.subagent.SubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.subagent.SubAgentProfile
import com.opencray.runtime.subagent.SubAgentTask
import com.opencray.runtime.subagent.childApprovalResume
import com.opencray.runtime.subagent.childResultToTaskToolResult
import com.opencray.runtime.subagent.createSubAgentHandle
import com.opencray.runtime.subagent.emitSubAgentEvent
import com.opencray.runtime.subagent.maybeExecuteSubAgentCall
import com.opencray.runtime.subagent.prepareSubAgentDelegation
import com.opencray.runtime.subagent.resolveInheritedSubAgentSkillCapsule
import com.opencray.runtime.subagent.takePendingApprovalContinuation
import com.opencray.runtime.subagent.waitOnRecoverySubAgentHandle
import com.opencray.runtime.subagent.ensureDetachedSubAgentHandleBackgroundExecution
import com.opencray.runtime.subagent.executeSubAgentHandleLifecycle
import com.opencray.runtime.subagent.synchronizedSubAgentHandles
import com.opencray.runtime.subagent.cancelActiveSubAgentExecutions
import com.opencray.runtime.subagent.restoredSubAgentHandle
import com.opencray.runtime.subagent.subAgentHandleRegistry
import com.opencray.runtime.subagent.latestSubAgentHandle
import com.opencray.runtime.subagent.subAgentHandleMetadata
import com.opencray.runtime.subagent.withUpdatedChildPromptCheckpoint
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillCapsuleResolver
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.web.emitBuiltinWebSearchObservations
import com.opencray.runtime.workingstate.InMemoryWorkingStateStore
import com.opencray.runtime.workingstate.WorkingStateResumeContext
import com.opencray.runtime.workingstate.WorkingStateSupport
import com.opencray.runtime.workingstate.WorkingStateStore
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME: String =
  "LLM_RETRY_EXHAUSTED_AWAITING_RESUME"
const val ERROR_EMPTY_RESPONSE_RECOVERY_EXHAUSTED: String =
  "EMPTY_RESPONSE_RECOVERY_EXHAUSTED"
internal const val SUBAGENT_WAIT_PROGRESS_POLL_INTERVAL_MS: Long = 100L
private const val PARALLEL_TOOL_DISPATCH_POLL_INTERVAL_MS: Long = 250L

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
  val seededDetachedSubAgentHandlesRequireCoordinatorOwnership: Boolean = false,
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
  internal val toolDispatcher: OpenCrayToolDispatcher,
  internal val config: OpenCrayAgentRuntimeConfig = OpenCrayAgentRuntimeConfig(),
  internal val eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
  internal val clock: () -> Long = System::currentTimeMillis,
) : SessionTaskRuntime {
  private val activeSkillCapsuleResolver: ActiveSkillCapsuleResolver = ActiveSkillCapsuleResolver()
  private val recentToolObservationSupport: RecentToolObservationSupport = RecentToolObservationSupport(
    config = config.contextSourceBudgetProfile.recentToolObservationConfig,
  )
  internal val toolResultReplayProjector: ToolResultReplayProjector = ToolResultReplayProjector()
  private val workingStateSupport: WorkingStateSupport = WorkingStateSupport()
  internal var pendingApprovedSubAgentResume: SubAgentApprovalResume? = config.approvedSubAgentResume
  internal var pendingRejectedSubAgentResume: SubAgentApprovalResume? = config.rejectedSubAgentResume

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
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      emitLifecycleEvent(
        task = task,
        phase = OpenCrayRunLifecyclePhase.CANCELLED,
        errorCode = ERROR_RUNTIME_INTERRUPTED,
        errorMessage = interrupted.message ?: "Runtime execution was interrupted.",
      )
      throw interrupted
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

  fun executeSubAgentActorTask(
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
    val toolResult = waitOnRecoverySubAgentHandle(
      task = task,
      turn = 0,
      call = call,
      transcript = config.sessionContext.conversation,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      agentId = agentId,
      parentRunId = parentRunId,
      startDetachedExecutionIfNeeded = true,
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

  fun executeSubAgentRecoveryWait(
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
    )
    val toolResult = waitOnRecoverySubAgentHandle(
      task = task,
      turn = 0,
      call = call,
      transcript = config.sessionContext.conversation,
      hooks = hooks,
      activeSkillCapsule = activeSkillCapsule,
      agentId = agentId,
      parentRunId = parentRunId,
      startDetachedExecutionIfNeeded = false,
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
        val restoredFrontContextZones = shape.restoredFrontContextZones()
        ResponsesContinuationShape(
          stableAnchor = shape.stableAnchor,
          baseline = ResponsesContextBaselineSnapshot(
            durableContextPrompt = restoredFrontContextZones.durableContextPrompt,
          ),
          referenceState = ResponsesContextReferenceState(
            dynamicContextHash = shape.dynamicContextHash
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: promptCacheFingerprint(restoredFrontContextZones.dynamicContextPrompt),
            appliedUpdateCount = shape.appliedContextUpdateCount,
          ),
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
    var cancelOpenSubAgentsOwnerRunId: String? = null
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
            put("responsesPendingContextUpdateCount", gatewayMessagePlan.responsesPendingContextUpdateCount.toString())
            gatewayMessagePlan.responsesPendingContextUpdateHash
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?.let { hash -> put("responsesPendingContextUpdateHash", hash) }
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
          val identifiedBatch = parsedBatch.copy(
            actions = parsedBatch.actions.mapIndexed { index, action ->
              action.withAssistantActionEventId(
                task = task,
                turn = cursor.turn,
                actionIndex = index,
                batchRequestId = gatewayResult.requestId,
              )
            },
          )
          val responsesAppliedUpdateCount = if (gatewayMessagePlan.mode == LocalContinuationMode.RESPONSES_NATIVE) {
            (cursor.responsesContinuationShape?.referenceState?.appliedUpdateCount ?: 0) +
              gatewayMessagePlan.responsesPendingContextUpdateCount
          } else {
            0
          }
          updateResponsesContinuationState(
            cursor = cursor,
            gatewayResult = gatewayResult,
            continuationShape = ResponsesContinuationShape(
              stableAnchor = stableLocalContinuationAnchor,
              baseline = ResponsesContextBaselineSnapshot(
                durableContextPrompt = assembledPrompt.frontContextZones.durableContextPrompt,
              ),
              referenceState = ResponsesContextReferenceState(
                dynamicContextHash = promptCacheFingerprint(assembledPrompt.frontContextZones.dynamicContextPrompt),
                appliedUpdateCount = responsesAppliedUpdateCount,
              ),
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
            pendingActions = identifiedBatch.actions,
            nextActionIndex = 0,
            requiresSingleActionReminder = identifiedBatch.requiresSingleActionReminder,
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
            pendingActions = identifiedBatch.actions,
            nextActionIndex = 0,
            requiresSingleActionReminder = identifiedBatch.requiresSingleActionReminder,
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
              parsedBatch = identifiedBatch,
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
    } catch (unexpected: Throwable) {
      cancelOpenSubAgentsOnExit = true
      cancelOpenSubAgentsReason = "Parent run failed unexpectedly."
      cancelOpenSubAgentsOwnerRunId = parentRunId
      throw unexpected
    } finally {
      if (cancelOpenSubAgentsOnExit) {
        cancelActiveSubAgentExecutions(
          task = task,
          turn = cursor.turn,
          cursor = cursor,
          reason = cancelOpenSubAgentsReason,
          removeHandles = false,
          includeInactiveHandles = cancelOpenSubAgentsOwnerRunId == null,
          owningParentRunId = cancelOpenSubAgentsOwnerRunId,
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

  fun ensureSubAgentRecoveryExecution(
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

  /** Compatibility alias for the pre-absorption master entry point name. */
  fun ensureDetachedSubAgentRecoveryExecution(
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    agentId: String,
    parentRunId: String,
  ): SubAgentHandleState? = ensureSubAgentRecoveryExecution(
    task = task,
    hooks = hooks,
    agentId = agentId,
    parentRunId = parentRunId,
  )

  /** Compatibility alias for the pre-absorption master entry point name. */
  fun executeDetachedSubAgentRecoveryWait(
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    agentId: String,
    parentRunId: String,
  ): ExecutionResult = executeSubAgentRecoveryWait(
    task = task,
    hooks = hooks,
    agentId = agentId,
    parentRunId = parentRunId,
  )

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

  internal fun isResponsesProtocol(): Boolean =
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

  internal fun responsesContinuationSupported(): Boolean =
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

  internal fun promptCacheFingerprint(
    source: String,
  ): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)
  }

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
        report.memoryFlushTrace.executionMode
          .takeIf(String::isNotBlank)
          ?.let { executionMode -> put("contextMemoryFlushExecutionMode", executionMode) }
        put("contextMemoryFlushContextWindowTokens", report.memoryFlushTrace.contextWindowTokens.toString())
        report.memoryFlushTrace.previousContextWindowTokens?.let { previousContextWindowTokens ->
          put("contextMemoryFlushPreviousContextWindowTokens", previousContextWindowTokens.toString())
        }
        put("contextMemoryFlushAutoCompactTokenLimit", report.memoryFlushTrace.autoCompactTokenLimit.toString())
        put("contextMemoryFlushEstimatedReplayTokens", report.memoryFlushTrace.estimatedReplayTokens.toString())
        put("contextMemoryFlushTokenThresholdTriggered", report.memoryFlushTrace.tokenThresholdTriggered.toString())
        if (report.memoryFlushTrace.smallerWindowModelSwitchDetected) {
          put(
            "contextMemoryFlushSmallerWindowModelSwitchDetected",
            report.memoryFlushTrace.smallerWindowModelSwitchDetected.toString(),
          )
        }
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
        report.durableCompactionTrace.executionMode
          .takeIf(String::isNotBlank)
          ?.let { executionMode -> put("contextDurableCompactionExecutionMode", executionMode) }
        put(
          "contextDurableCompactionContextWindowTokens",
          report.durableCompactionTrace.contextWindowTokens.toString(),
        )
        report.durableCompactionTrace.previousContextWindowTokens?.let { previousContextWindowTokens ->
          put(
            "contextDurableCompactionPreviousContextWindowTokens",
            previousContextWindowTokens.toString(),
          )
        }
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
        if (report.durableCompactionTrace.smallerWindowModelSwitchDetected) {
          put(
            "contextDurableCompactionSmallerWindowModelSwitchDetected",
            report.durableCompactionTrace.smallerWindowModelSwitchDetected.toString(),
          )
        }
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
      put("responsesPendingContextUpdateCount", promptDiagnostics.responsesPendingContextUpdateCount.toString())
      promptDiagnostics.responsesPendingContextUpdateHash
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { hash -> put("responsesPendingContextUpdateHash", hash) }
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

  internal fun resolveActiveSkillCapsule(
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

  private fun JsonObject.toStringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
    (value as? JsonPrimitive)?.content?.let { content -> key to content }
  }.toMap()

  internal fun runIdFor(task: AgentTask): String =
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
      ).map { restoredSubAgentHandle(it) }
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
    ).mapIndexed { index, action ->
      action.withAssistantActionEventId(
        task = task,
        turn = cursor.turn,
        actionIndex = index,
        batchRequestId = gatewayResult?.requestId,
      )
    }
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
            eventId = action.eventId,
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
          val finalizationMetadata = promptCheckpointMetadata(
            boundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
            cursor = cursor,
            turnIndex = cursor.turn + 1,
            localContinuationContextPrompts = localContinuationContextPrompts,
            localContinuationStableAnchor = localContinuationStableAnchor,
            localContinuationGatewayMessagesEnabled = localContinuationGatewayMessagesEnabled,
          ) + finalAttachmentMetadata(action.attachments)
          emitAssistantEvent(
            task = task,
            turn = cursor.turn,
            text = action.answer,
            responseFormat = action.responseFormat,
            isFinal = true,
            eventId = action.eventId,
            metadata = finalizationMetadata,
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
              ) + finalizationMetadata,
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
    val requestedConversation = result.conversation ?: beforeConversation
    val nextSessionContext = requestedSessionContext.copy(conversation = requestedConversation)
    val transcriptChanged = requestedConversation != beforeConversation
    val durableMaintenanceChanged = nextSessionContext.memoryFlushTrace != beforeSessionContext.memoryFlushTrace ||
      nextSessionContext.durableCompaction.trace != beforeSessionContext.durableCompaction.trace ||
      stickyMemoryRecallChanged(
        before = beforeSessionContext.recalledMemory,
        after = nextSessionContext.recalledMemory,
      )
    cursor.sessionContext = nextSessionContext
    if (transcriptChanged) {
      cursor.transcript.clear()
      cursor.transcript += requestedConversation
    }
    if (transcriptChanged || durableMaintenanceChanged) {
      invalidateResponsesLineage(cursor)
      invalidateLocalContinuation(cursor)
    }
  }

  private fun stickyMemoryRecallChanged(
    before: MemoryRecallResult,
    after: MemoryRecallResult,
  ): Boolean =
    before.memories.filter { memory -> memory.sticky } != after.memories.filter { memory -> memory.sticky }

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

  internal fun dispatchPromptToolCallsInParallel(
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
      val collectedResults = arrayOfNulls<ParallelToolDispatch>(futures.size)
      val pendingFutures = LinkedHashMap<Int, Future<ParallelToolDispatch>>()
      futures.forEachIndexed { index, future -> pendingFutures[index] = future }
      dispatchLoop@ while (pendingFutures.isNotEmpty()) {
        if (hooks.isCancellationRequested()) {
          pendingFutures.values.forEach { future -> future.cancel(true) }
          break@dispatchLoop
        }
        val roundIterator = pendingFutures.entries.iterator()
        while (roundIterator.hasNext()) {
          val entry = roundIterator.next()
          if (hooks.isCancellationRequested()) {
            pendingFutures.values.forEach { future -> future.cancel(true) }
            break@dispatchLoop
          }
          val polledDispatch = try {
            entry.value.get(PARALLEL_TOOL_DISPATCH_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
          } catch (_: TimeoutException) {
            null
          } catch (_: CancellationException) {
            roundIterator.remove()
            null
          }
          if (polledDispatch != null) {
            collectedResults[entry.key] = polledDispatch
            roundIterator.remove()
          }
        }
      }
      return collectedResults.filterNotNull()
    } finally {
      executor.shutdownNow()
    }
  }

  internal fun announcePromptToolCall(
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

  private fun AgentModelAction.withAssistantActionEventId(
    task: AgentTask,
    turn: Int,
    actionIndex: Int,
    batchRequestId: String?,
  ): AgentModelAction = when (this) {
    is AgentModelAction.Commentary -> copy(
      eventId = eventId ?: assistantActionEventId(
        task = task,
        turn = turn,
        actionIndex = actionIndex,
        phase = "commentary",
        batchRequestId = batchRequestId,
      ),
    )

    is AgentModelAction.Final -> copy(
      eventId = eventId ?: assistantActionEventId(
        task = task,
        turn = turn,
        actionIndex = actionIndex,
        phase = "final",
        batchRequestId = batchRequestId,
      ),
    )

    is AgentModelAction.ToolCall -> this
  }

  private fun assistantActionEventId(
    task: AgentTask,
    turn: Int,
    actionIndex: Int,
    phase: String,
    batchRequestId: String?,
  ): String {
    val batchIdentity = batchRequestId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: "resume"
    val identity = listOf(
      "assistant-action-v1",
      runIdFor(task),
      task.id,
      batchIdentity,
      turn.toString(),
      actionIndex.toString(),
      phase,
    ).joinToString(separator = "\u001f")
    return "assistant-item-${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}"
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

  internal fun liteLlmGatewayAttachmentFor(
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
    eventId: String? = null,
    metadata: Map<String, String> = emptyMap(),
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayAssistantEvent(
        runId = runIdFor(task),
        taskId = task.id,
        eventId = eventId ?: syntheticAssistantEventId(),
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
    eventId: String? = null,
    metadata: Map<String, String> = emptyMap(),
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayAssistantEvent(
        runId = runIdFor(task),
        taskId = task.id,
        eventId = eventId ?: syntheticAssistantEventId(),
        turn = turn,
        text = text,
        isFinal = false,
        stage = stage,
        metadata = metadata,
        emittedAtEpochMs = clock(),
      ),
    )
  }

  // Assistant/commentary events must never share a fallback identity: dedup keys and
  // projected message ids collapse distinct narrations when eventId is absent.
  private fun syntheticAssistantEventId(): String = "assistant-item-${UUID.randomUUID()}"

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

  internal fun nextSyntheticToolCallSequence(
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

  internal fun buildToolResultTranscriptEntry(
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

  internal fun runtimeToolCallFor(entry: RuntimeConversationMessage): AgentToolCall? =
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

  internal fun runtimeToolResultFor(entry: RuntimeConversationMessage): ParsedToolResultObservation? =
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

  internal fun RuntimeConversationAssistantPhase.toLiteLlmAssistantPhase(): LiteLlmAssistantPhase = when (this) {
    RuntimeConversationAssistantPhase.COMMENTARY -> LiteLlmAssistantPhase.COMMENTARY
    RuntimeConversationAssistantPhase.FINAL_ANSWER -> LiteLlmAssistantPhase.FINAL_ANSWER
  }

  internal fun AgentModelAction.toSerializableModelAction(): OpenCraySerializableModelAction = when (this) {
    is AgentModelAction.Commentary -> OpenCraySerializableModelAction.Commentary(
      text = text,
      stage = stage,
      eventId = eventId,
    )

    is AgentModelAction.Final -> OpenCraySerializableModelAction.Final(
      answer = answer,
      responseFormat = responseFormat,
      attachments = attachments,
      eventId = eventId,
    )

    is AgentModelAction.ToolCall -> OpenCraySerializableModelAction.ToolCall(
      call = OpenCraySerializableToolCall.from(call),
    )
  }

  private fun OpenCraySerializableModelAction.toRuntimeAction(): AgentModelAction = when (this) {
    is OpenCraySerializableModelAction.Commentary -> AgentModelAction.Commentary(
      text = text,
      stage = stage,
      eventId = eventId,
    )

    is OpenCraySerializableModelAction.Final -> AgentModelAction.Final(
      answer = answer,
      responseFormat = responseFormat,
      attachments = attachments,
      eventId = eventId,
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


  internal data class DetachedSubAgentRunningTurn(
    val handle: SubAgentHandleState,
    val approvalContinuation: PendingSubAgentApprovalContinuation?,
    val promptResumeStateOverride: OpenCrayPromptResumeState?,
    val includeMailboxMessagesInPrompt: Boolean,
    val emitResumedPhase: Boolean,
    val mailboxDeliveryCursorBeforeCurrentTurn: String?,
  )

  internal data class DetachedSubAgentTurnCompletion(
    val storedHandle: SubAgentHandleState,
    val completedHandle: SubAgentHandleState,
    val snapshot: SubAgentExecutionSnapshot,
    val completionPhase: OpenCraySubAgentPhase,
    val shouldAutoContinue: Boolean,
  )


  internal fun executeSubAgentHandleRuntime(
    parentTask: AgentTask,
    transcript: List<RuntimeConversationMessage>,
    parentSessionContext: AgentRuntimeSessionContext,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    handle: SubAgentHandleState,
    profile: SubAgentProfile,
    approvalContinuation: PendingSubAgentApprovalContinuation?,
    owningExecution: SubAgentActiveExecution? = null,
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
          val checkpointHandle = checkpointBaseHandle.withUpdatedChildPromptCheckpoint(
            checkpointState = emission.state,
            checkpointBoundary = emission.boundary,
            emittedAtEpochMs = emission.emittedAtEpochMs,
          )
          if (owningExecution != null) {
            config.subAgentExecutionCoordinator.upsertHandleIfOwnedByExecution(
              handle = checkpointHandle,
              expectedExecution = owningExecution,
            )
          } else {
            config.subAgentExecutionCoordinator.upsertHandle(checkpointHandle)
          }
        },
      ),
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
      clock = clock,
    )
    return childRuntime.execute(childPromptTask, hooks)
  }


  internal fun storedSubAgentHandleResult(
    call: AgentToolCall,
    handle: SubAgentHandleState,
    unharvestedRunningStatus: AgentToolResultStatus = AgentToolResultStatus.FAILED,
  ): AgentToolResult {
    val status = when (handle.snapshot.state) {
      SubAgentExecutionState.COMPLETED -> AgentToolResultStatus.SUCCESS
      SubAgentExecutionState.BACKGROUND_QUEUED -> AgentToolResultStatus.SUCCESS
      SubAgentExecutionState.RUNNING,
      SubAgentExecutionState.BACKGROUND_RUNNING,
      -> unharvestedRunningStatus

      SubAgentExecutionState.CANCELLED -> AgentToolResultStatus.CANCELLED
      SubAgentExecutionState.FAILED -> when (handle.childExecutionStatus) {
        ExecutionStatus.TIMEOUT.name -> AgentToolResultStatus.TIMEOUT
        else -> AgentToolResultStatus.FAILED
      }

      SubAgentExecutionState.WAITING_APPROVAL,
      SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
      -> AgentToolResultStatus.DENIED
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

      SubAgentExecutionState.RUNNING,
      SubAgentExecutionState.BACKGROUND_RUNNING,
      -> if (status == AgentToolResultStatus.SUCCESS) {
        null
      } else {
        "Delegated child run is still running and was not harvested."
      }

      SubAgentExecutionState.COMPLETED,
      SubAgentExecutionState.BACKGROUND_QUEUED,
      -> null
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

  internal fun emitToolResultEvent(
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
  internal fun buildEmptyResponseRecoveryObservation(
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

  internal sealed interface AgentModelAction {
    data class Commentary(
      val text: String,
      val stage: String? = null,
      val eventId: String? = null,
    ) : AgentModelAction

    data class Final(
      val answer: String,
      val responseFormat: String,
      val attachments: List<OpenCrayFinalAttachment> = emptyList(),
      val eventId: String? = null,
    ) : AgentModelAction

    data class ToolCall(
      val call: AgentToolCall,
    ) : AgentModelAction
  }

  internal data class PromptTurnCursor(
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

  internal data class PromptRunDiagnostics(
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
    var responsesPendingContextUpdateCount: Int = 0,
    var responsesPendingContextUpdateHash: String? = null,
    var contextCacheBreakReason: String? = null,
    var contextCacheShapeMetadata: Map<String, String> = emptyMap(),
  )

  internal data class GatewayMessagePlan(
    val messages: List<LiteLlmGatewayMessage>,
    val mode: LocalContinuationMode,
    val reason: String? = null,
    val previousResponseId: String? = null,
    val responsesPendingContextUpdateCount: Int = 0,
    val responsesPendingContextUpdateHash: String? = null,
  )

  internal data class ResponsesContinuationDecision(
    val previousResponseId: String? = null,
    val reason: String,
    val pendingContextUpdates: List<ResponsesPendingContextUpdate> = emptyList(),
  )

  internal data class ResponsesPendingContextUpdatePlan(
    val updates: List<ResponsesPendingContextUpdate> = emptyList(),
    val fallbackReason: String? = null,
  )

  internal data class PendingSubAgentApprovalContinuation(
    val resume: SubAgentApprovalResume,
    val approved: Boolean,
  )

  internal data class SubAgentHandleLifecycleExecution(
    val handle: SubAgentHandleState,
    val childResult: ExecutionResult,
    val childApprovalResume: SubAgentApprovalResume?,
  )

  internal data class PreparedSubAgentMailboxDelivery(
    val handle: SubAgentHandleState,
    val promptResumeState: OpenCrayPromptResumeState?,
    val includeMailboxMessagesInPrompt: Boolean,
    val mailboxDeliveryCursorBeforeCurrentTurn: String?,
  )

  internal data class PreparedSubAgentDelegation(
    val profile: SubAgentProfile,
    val childTask: SubAgentTask,
    val delegationPlan: ToolPolicyPlan,
  )

  internal data class SpawnPreparedSubAgentHandleExecution(
    val handle: SubAgentHandleState,
    val handles: MutableMap<String, SubAgentHandleState>,
    val childResult: ExecutionResult? = null,
    val childApprovalResume: SubAgentApprovalResume? = null,
  )

  internal sealed interface SpawnPreparedSubAgentHandleResult {
    data class Ready(
      val execution: SpawnPreparedSubAgentHandleExecution,
    ) : SpawnPreparedSubAgentHandleResult

    data class Invalid(
      val result: AgentToolResult,
    ) : SpawnPreparedSubAgentHandleResult
  }

  internal sealed interface PreparedSubAgentDelegationResult {
    data class Ready(
      val delegation: PreparedSubAgentDelegation,
    ) : PreparedSubAgentDelegationResult

    data class Invalid(
      val result: AgentToolResult,
    ) : PreparedSubAgentDelegationResult
  }

  internal data class LocalContinuationEnvelope(
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

  internal data class ResponsesContinuationShape(
    val stableAnchor: String,
    val baseline: ResponsesContextBaselineSnapshot,
    val referenceState: ResponsesContextReferenceState,
    val toolPoolFingerprint: String,
    val toolSchemaFingerprint: String,
    val requestSettingsFingerprint: String,
  )

  internal data class ResponsesContextBaselineSnapshot(
    val durableContextPrompt: String,
  )

  internal data class ResponsesContextReferenceState(
    val dynamicContextHash: String,
    val appliedUpdateCount: Int = 0,
  )

  internal data class ResponsesPendingContextUpdate(
    val sequence: Int,
    val dynamicContextHash: String,
    val content: String,
    val truncated: Boolean,
  )

  internal enum class LocalContinuationMode(val wireValue: String) {
    DISABLED("disabled"),
    FULL_REBUILD("full_rebuild"),
    LOCAL_FRONT_PATCH("local_front_patch"),
    LOCAL_DELTA("local_delta"),
    RESPONSES_NATIVE("responses_native"),
  }

  internal data class ParsedToolResultObservation(
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

  internal data class ParallelToolActionStep(
    val index: Int,
    val call: AgentToolCall,
  )

  internal data class ParallelToolDispatch(
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

  internal sealed interface ParsedModelActionBatch {
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

  internal companion object {
    const val NON_RESPONSES_CONTEXT_CACHE_CONTRACT_VERSION: String = "non_responses_front_zone_v1"
    const val RESPONSES_PROTOCOL: String = "openai_responses"
    const val HIDDEN_METADATA_PREFIX: String = "_host."
    private const val HOST_METADATA_BASE_URL: String = "${HIDDEN_METADATA_PREFIX}baseUrl"
    const val HOST_PROVIDER_ID_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}providerId"
    const val RUN_ID_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}runId"
    const val PROMPT_USER_TEXT_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}promptUserText"
    const val PROMPT_RUNTIME_ATTACHMENTS_JSON_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}promptRuntimeAttachmentsJson"
    const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    const val ERROR_SKILL_TOOL_POLICY_BLOCKED: String = "SKILL_TOOL_POLICY_BLOCKED"
    const val MAX_PROTOCOL_ERROR_PREVIEW_CHARS: Int = 600
    const val RESPONSES_CONTEXT_UPDATE_CHAIN_LIMIT: Int = 8
    const val RESPONSES_CONTEXT_UPDATE_MAX_CHARS: Int = 6_000
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
