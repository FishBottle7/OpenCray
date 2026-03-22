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
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.ContextAssemblyReport
import com.opencray.runtime.context.DuplicateDiscoveryToolHit
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.context.RecentToolObservationSupport
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationProgress
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.subagent.BuiltInSubAgentProfiles
import com.opencray.runtime.subagent.SubAgentContextBuilder
import com.opencray.runtime.subagent.SubAgentContextBuildRequest
import com.opencray.runtime.subagent.SubAgentContextMode
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.subagent.SubAgentResultCompressor
import com.opencray.runtime.subagent.SubAgentTask
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillCapsuleResolver
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class OpenCrayAgentRuntimeConfig(
  val maxTurns: Int = DEFAULT_MAX_TURNS,
  val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
  val systemPrompt: String = DEFAULT_OPENCRAY_SYSTEM_PROMPT,
  val sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
  val promptResumeState: OpenCrayPromptResumeState? = null,
  val approvedSubAgentResume: SubAgentApprovalResume? = null,
  val rejectedSubAgentResume: SubAgentApprovalResume? = null,
  val contextManager: ContextManager = ContextManager(),
  val promptAssembler: PromptAssembler = PromptAssembler(),
  val supplementInputProvider: (String, String) -> List<OpenCraySupplementInput> = { _, _ -> emptyList() },
  val llmMetadata: Map<String, String> = emptyMap(),
  val llmAuthHeaders: Map<String, String> = emptyMap(),
  val subAgentContextBuilder: SubAgentContextBuilder = SubAgentContextBuilder(),
  val maxSubAgentDepth: Int = 1,
  val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
  init {
    require(maxTurns >= 0) { "OpenCrayAgentRuntimeConfig maxTurns must be >= 0." }
    require(maxToolCalls >= 0) { "OpenCrayAgentRuntimeConfig maxToolCalls must be >= 0." }
    require(maxSubAgentDepth >= 0) { "OpenCrayAgentRuntimeConfig maxSubAgentDepth must be >= 0." }
    require(systemPrompt.isNotBlank()) { "OpenCrayAgentRuntimeConfig systemPrompt must not be blank." }
  }

  companion object {
    const val DEFAULT_MAX_TURNS: Int = 16
    const val DEFAULT_MAX_TOOL_CALLS: Int = 0
    const val DEFAULT_OPENCRAY_SYSTEM_PROMPT: String =
      "You are OpenCray, a workspace-first coding agent. " +
        "You may call one tool at a time when you need concrete workspace facts or to make a change. " +
        "Always prefer tools over guessing when the answer depends on files or local execution."
  }
}

class OpenCrayAgentRuntime(
  private val gateway: LiteLlmGateway,
  private val toolDispatcher: OpenCrayToolDispatcher,
  private val config: OpenCrayAgentRuntimeConfig = OpenCrayAgentRuntimeConfig(),
  private val eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
  private val clock: () -> Long = System::currentTimeMillis,
) : SessionTaskRuntime {
  private val activeSkillCapsuleResolver: ActiveSkillCapsuleResolver = ActiveSkillCapsuleResolver()
  private val recentToolObservationSupport: RecentToolObservationSupport = RecentToolObservationSupport()
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

  private fun executePromptTask(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult {
    val startedAt = clock()
    val seededTranscript = seededConversation(task)
    val executionTranscript = promptTranscriptForExecution(
      seededTranscript = seededTranscript,
      resumeState = config.promptResumeState,
    )
    val cursor = PromptTurnCursor(
      transcript = executionTranscript.toMutableList(),
      turn = config.promptResumeState?.turnIndex ?: 0,
      toolCallCount = config.promptResumeState?.toolCallCount ?: 0,
      activeSkillName = config.promptResumeState?.activeSkillName,
      activeSkillActivationSource = config.promptResumeState?.activeSkillActivationSource,
      nextSyntheticToolCallSequence = nextSyntheticToolCallSequence(executionTranscript),
    )
    var lastGatewayResult: LiteLlmGatewayResult? = null
    var lastContextReport: ContextAssemblyReport? = null
    var protocolErrorCount = 0
    var lastProtocolErrorMessage: String? = null
    val diagnostics = PromptRunDiagnostics()

    config.promptResumeState?.let { resumeState ->
      val resumedActions = resumeState.resumableActions()
      if (resumedActions.isEmpty()) {
        return@let
      }
      val resumeActionIndex = resumeState.normalizedNextActionIndex()
      val activeSkillCapsule = activeSkillCapsuleResolver.resolve(
        catalog = config.sessionContext.skillCatalog,
        activeSkillName = cursor.activeSkillName,
        activationSource = cursor.activeSkillActivationSource,
      )
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
          actionStartIndex = resumeActionIndex,
          suppressToolCallEventAtActionIndex = resumeActionIndex,
        )
      ) {
        is PromptBatchExecutionOutcome.Continue -> Unit
        is PromptBatchExecutionOutcome.Terminal -> return outcome.result
      }
    }

    while (hasTurnBudgetRemaining(cursor.turn)) {
      if (hooks.isCancellationRequested()) {
        return cancelledResult(task = task, startedAt = startedAt, finishedAt = clock())
      }

      applyTurnStartSupplements(
        task = task,
        turn = cursor.turn,
        transcript = cursor.transcript,
      )

      val turnAwareConversation = promptConversationForTurn(
        transcript = cursor.transcript,
        turn = cursor.turn,
      )
      val activeSkillCapsule = activeSkillCapsuleResolver.resolve(
        catalog = config.sessionContext.skillCatalog,
        activeSkillName = cursor.activeSkillName,
        activationSource = cursor.activeSkillActivationSource,
      )
      val visibleToolDefinitions = visibleToolDefinitionsForTurn(
        allDefinitions = toolDispatcher.definitions(),
        activeSkillCapsule = activeSkillCapsule,
        memoryToolsEnabled = config.sessionContext.memoryToolsEnabled,
      )
      val nativeToolDefinitions = visibleToolDefinitions.map(AgentToolDefinition::toLiteLlmToolDefinition)
      diagnostics.nativeToolCallRequested = nativeToolDefinitions.isNotEmpty()
      val managedContext = config.contextManager.prepare(
        PromptAssemblyInput(
          task = task,
          baseSystemPrompt = config.systemPrompt,
          sessionContext = config.sessionContext,
          activeSkillCapsule = activeSkillCapsule,
          nativeToolCallingEnabled = nativeToolDefinitions.isNotEmpty(),
          toolDefinitions = visibleToolDefinitions,
          liveConversation = turnAwareConversation,
        ),
      )
      val assembledPrompt = config.promptAssembler.assemble(managedContext)
      lastContextReport = assembledPrompt.report
      val enforcedSystemPrompt = enforcedSystemPromptForTurn(
        systemPrompt = assembledPrompt.systemPrompt,
        turn = cursor.turn,
      )
      val gatewayMessages = if (nativeToolDefinitions.isNotEmpty()) {
        buildGatewayMessages(
          contextPrompt = assembledPrompt.contextPrompt,
          transcript = turnAwareConversation,
        )
      } else {
        emptyList()
      }

      val runId = runIdFor(task)
      val request = LiteLlmGatewayRequest(
        requestId = "agent-$runId-turn-${cursor.turn}-${UUID.randomUUID().toString().take(8)}",
        prompt = assembledPrompt.taskPrompt,
        systemPrompt = enforcedSystemPrompt,
        messages = gatewayMessages,
        tools = nativeToolDefinitions,
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
          put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, nativeToolDefinitions.isNotEmpty().toString())
          putAll(task.metadata.filterKeys(::isGatewayVisibleMetadataKey))
          putAll(config.llmMetadata)
        },
        authHeaders = config.llmAuthHeaders,
      )
      val gatewayResult = gateway.execute(request)
      lastGatewayResult = gatewayResult
      if (gatewayResult.status != LiteLlmGatewayStatus.SUCCESS) {
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

      val parsedBatch = parseGatewayResultActionBatch(
        gatewayResult = gatewayResult,
        diagnostics = diagnostics,
      )
        ?: return llmFailureResult(
          task = task,
          startedAt = startedAt,
          gatewayResult = gatewayResult,
          turn = cursor.turn,
          toolCallCount = cursor.toolCallCount,
          contextReport = lastContextReport,
          diagnostics = diagnostics,
        )
      when (parsedBatch) {
        is ParsedModelActionBatch.ProtocolError -> {
          protocolErrorCount += 1
          lastProtocolErrorMessage = parsedBatch.reason
          val outputPreview = gatewayResult.completion?.rawText
            ?: gatewayResult.outputText
            ?: ""
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = outputPreview.trim().take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS),
          )
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = buildProtocolRecoveryObservation(
              rawOutput = outputPreview,
              reason = parsedBatch.reason,
            ),
          )
          cursor.turn += 1
          continue
        }

        is ParsedModelActionBatch.Actions -> {
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
            )
          ) {
            is PromptBatchExecutionOutcome.Continue -> continue
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
    val toolResult = gateDisabledSessionToolCall(
      call = toolCall,
      memoryToolsEnabled = config.sessionContext.memoryToolsEnabled,
    ) ?: maybeExecuteSubAgentCall(
      task = task,
      turn = 0,
      call = toolCall,
      transcript = config.sessionContext.conversation,
      hooks = hooks,
      activeSkillCapsule = null,
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
  ): ParsedModelActionBatch? {
    gatewayResult.completion?.let { completion ->
      parseStructuredCompletion(completion)?.let { parsed ->
        return parsed
      }
      completion.rawText?.takeIf(String::isNotBlank)?.let { rawText ->
        diagnostics.fallbackParserAttempted = true
        val parsed = parseModelActionBatch(rawText)
        diagnostics.fallbackParserSucceeded = parsed is ParsedModelActionBatch.Actions
        return parsed
      }
    }
    val outputText = gatewayResult.outputText?.takeIf { it.isNotBlank() } ?: return null
    diagnostics.fallbackParserAttempted = true
    val parsed = parseModelActionBatch(outputText)
    diagnostics.fallbackParserSucceeded = parsed is ParsedModelActionBatch.Actions
    return parsed
  }

  private fun parseStructuredCompletion(
    completion: LiteLlmStructuredCompletion,
  ): ParsedModelActionBatch.Actions? {
    val actions = mutableListOf<AgentModelAction>()
    completion.progressText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { progressText ->
        actions += AgentModelAction.Progress(text = progressText)
      }
    completion.toolCalls
      .mapTo(actions) { toolCall ->
        AgentModelAction.ToolCall(call = parseStructuredToolCall(toolCall))
      }
    completion.finalText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.takeIf { actions.isEmpty() }
      ?.let { finalText ->
        actions += AgentModelAction.Final(
          answer = finalText,
          responseFormat = "native_text_final",
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
          AgentModelAction.Progress(
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
                        error("progress action must contain a non-blank 'text'.")
                      }
                  }
              },
            stage = parsed.primitiveContent("stage")?.trim()?.takeIf(String::isNotBlank),
          ),
        ),
        ignoredNonActionContent = parsed.primitiveContent("answer")?.isNotBlank() == true ||
          parsed.primitiveContent("tool_name")?.isNotBlank() == true,
      )

      type in setOf("final", "answer") || (type == null && hasFinalAnswerShape) -> ParsedActionObject(
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
      if (!report.memoryFlushTrace.isEmpty) {
        report.memoryFlushTrace.outcome?.let { outcome ->
          put("contextMemoryFlushOutcome", outcome.name.lowercase())
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
      }
      if (!report.durableCompactionTrace.isEmpty) {
        put(
          "contextDurableCompactionCompactedThisRun",
          report.durableCompactionTrace.compactedThisRun.toString(),
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
    gatewayResult?.metadata?.forEach { (key, value) ->
      if (key.isNotBlank() && value.isNotBlank()) {
        put(key, value)
      }
    }
    diagnostics?.let { promptDiagnostics ->
      put(LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED, promptDiagnostics.nativeToolCallRequested.toString())
      put(LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED, promptDiagnostics.parsedToolCallObserved.toString())
      put(LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED, promptDiagnostics.fallbackParserAttempted.toString())
      put(LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED, promptDiagnostics.fallbackParserSucceeded.toString())
      put(LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED, promptDiagnostics.toolCallEventEmitted.toString())
      put(LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED, promptDiagnostics.toolResultEventEmitted.toString())
      promptDiagnostics.lastSuccessfulToolName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { toolName ->
          put(LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME, toolName)
        }
    }
  }

  private fun visibleToolDefinitionsForTurn(
    allDefinitions: List<AgentToolDefinition>,
    activeSkillCapsule: ActiveSkillCapsule?,
    memoryToolsEnabled: Boolean,
  ): List<AgentToolDefinition> {
    val baseDefinitions = if (memoryToolsEnabled) {
      allDefinitions
    } else {
      allDefinitions.filterNot { definition -> isMemoryTool(definition.name) }
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
    if (toolPolicyKey(call.toolName) != "skill_read") {
      return null
    }
    return result.metadata["skillName"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
  }

  private fun normalizedAllowedToolKeys(
    activeSkillCapsule: ActiveSkillCapsule?,
  ): Set<String> = activeSkillCapsule
    ?.allowedToolKeys
    ?.map(::toolPolicyKey)
    ?.toSet()
    ?: emptySet()

  private fun isMemoryTool(toolName: String): Boolean = when (toolPolicyKey(toolName)) {
    "memory_search", "memory_get" -> true
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

  private fun JsonObject.longContent(key: String): Long? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toLongOrNull()

  private fun JsonObject.intArrayContent(key: String): List<Int>? =
    (this[key] as? JsonArray)
      ?.mapNotNull { element ->
        (element as? JsonPrimitive)
          ?.content
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.toIntOrNull()
      }

  private fun runIdFor(task: AgentTask): String =
    task.metadata[RUN_ID_METADATA_KEY]
      ?.takeIf(String::isNotBlank)
      ?: task.id

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
    actionStartIndex: Int = 0,
    suppressToolCallEventAtActionIndex: Int? = null,
  ): PromptBatchExecutionOutcome {
    val batchActions = normalizeToolCallIds(
      actions = parsedBatch.actions,
      cursor = cursor,
    )
    val containsToolAction = batchActions.any { action -> action is AgentModelAction.ToolCall }
    val containsProgressAction = batchActions.any { action -> action is AgentModelAction.Progress }
    val containsFinalAction = batchActions.any { action -> action is AgentModelAction.Final }
    if (!containsToolAction && !containsProgressAction && !containsFinalAction) {
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

    var shouldContinueBatch = true
    for (index in actionStartIndex until batchActions.size) {
      if (!shouldContinueBatch) {
        break
      }
      when (val action = batchActions[index]) {
        is AgentModelAction.Progress -> {
          emitProgressEvent(
            task = task,
            turn = cursor.turn,
            text = action.text,
            stage = action.stage,
          )
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            kind = RuntimeConversationMessageKind.PROGRESS,
            content = buildProgressTranscriptEntry(
              task = task,
              turn = cursor.turn,
              progress = action,
            ),
            progress = RuntimeConversationProgress(
              runId = runIdFor(task),
              taskId = task.id,
              turn = cursor.turn,
              text = action.text,
              stage = action.stage,
            ),
          )
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

          val suppressToolCallEvent = suppressToolCallEventAtActionIndex == index
          if (!suppressToolCallEvent) {
            cursor.transcript += RuntimeConversationMessage(
              role = RuntimeConversationRole.ASSISTANT,
              kind = RuntimeConversationMessageKind.TOOL_CALL,
              content = buildToolCallTranscriptEntry(
                task = task,
                turn = cursor.turn,
                call = action.call,
              ),
              toolCall = action.call.toRuntimeConversationToolCall(),
            )
            eventSink.onRunEvent(
              task = task,
              event = OpenCrayToolCallEvent(
                runId = runIdFor(task),
                taskId = task.id,
                turn = cursor.turn,
                call = action.call,
                emittedAtEpochMs = clock(),
              ),
            )
            diagnostics.toolCallEventEmitted = true
          }
          val toolResult = dispatchPromptToolCall(
            task = task,
            turn = cursor.turn,
            call = action.call,
            transcript = cursor.transcript,
            hooks = hooks,
            activeSkillCapsule = activeSkillCapsule,
            allowDuplicateShortCircuit = !suppressToolCallEvent,
          )
          eventSink.onRunEvent(
            task = task,
            event = OpenCrayToolResultEvent(
              runId = runIdFor(task),
              taskId = task.id,
              turn = cursor.turn,
              call = action.call,
              result = toolResult,
              emittedAtEpochMs = clock(),
            ),
          )
          diagnostics.toolResultEventEmitted = true
          emitMemoryRetrievalEvent(
            task = task,
            turn = cursor.turn,
            call = action.call,
            result = toolResult,
          )
          if (toolResult.status == AgentToolResultStatus.CANCELLED) {
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
                toolCall = action.call,
                toolResult = toolResult,
                activeSkillName = cursor.activeSkillName,
                activeSkillActivationSource = cursor.activeSkillActivationSource,
                hooks = hooks,
                diagnostics = diagnostics,
              ),
            )
          }
          if (toolResult.status == AgentToolResultStatus.SUCCESS) {
            diagnostics.lastSuccessfulToolName = toolResult.toolName
            val activatedSkillName = activatedSkillNameFrom(
              call = action.call,
              result = toolResult,
            )
            if (!activatedSkillName.isNullOrBlank()) {
              cursor.activeSkillName = activatedSkillName
              cursor.activeSkillActivationSource = ACTIVATION_SOURCE_SKILL_READ
            }
          }
          cursor.transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            content = buildToolResultTranscriptEntry(
              task = task,
              turn = cursor.turn,
              call = action.call,
              result = toolResult,
            ),
            toolResult = RuntimeConversationToolResult(
              toolCallId = action.call.id,
              toolName = toolResult.toolName,
              status = toolResult.status.name.lowercase(),
              isError = toolResult.status != AgentToolResultStatus.SUCCESS,
            ),
          )
          cursor.toolCallCount += 1
          if (toolResult.status != AgentToolResultStatus.SUCCESS) {
            shouldContinueBatch = false
          }
        }

        is AgentModelAction.Final -> if (!containsToolAction) {
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
    }

    if (parsedBatch.requiresSingleActionReminder) {
      cursor.transcript += RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildSingleActionReminderObservation(),
      )
    }
    cursor.turn += 1
    return PromptBatchExecutionOutcome.Continue
  }

  private fun dispatchPromptToolCall(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    transcript: List<RuntimeConversationMessage>,
    hooks: RuntimeExecutionHooks,
    activeSkillCapsule: ActiveSkillCapsule?,
    allowDuplicateShortCircuit: Boolean,
  ): AgentToolResult = gateActiveSkillToolCall(
    call = call,
    activeSkillCapsule = activeSkillCapsule,
  ) ?: gateDisabledSessionToolCall(
    call = call,
    memoryToolsEnabled = config.sessionContext.memoryToolsEnabled,
  ) ?: maybeExecuteSubAgentCall(
    task = task,
    turn = turn,
    call = call,
    transcript = transcript,
    hooks = hooks,
    activeSkillCapsule = activeSkillCapsule,
  ) ?: if (allowDuplicateShortCircuit) {
    maybeShortCircuitDuplicateDiscoveryCall(
      call = call,
      transcript = transcript,
    )
  } else {
    null
  } ?: toolDispatcher.dispatch(task = task, call = call, hooks = hooks)

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
    val normalizedInput = task.input.trim()
    if (normalizedInput.isBlank()) {
      return seeded
    }
    val lastEntry = seeded.lastOrNull()
    if (lastEntry?.role == RuntimeConversationRole.USER && lastEntry.content == normalizedInput) {
      return seeded
    }
    seeded += RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = normalizedInput,
    )
    return seeded
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
    hooks: RuntimeExecutionHooks,
    diagnostics: PromptRunDiagnostics,
  ): ExecutionResult {
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
            toolCallCount = toolCallCount,
            pendingActions = pendingActions.map { action -> action.toSerializableModelAction() },
            nextActionIndex = nextActionIndex,
            requiresSingleActionReminder = requiresSingleActionReminder,
            activeSkillName = activeSkillName,
            activeSkillActivationSource = activeSkillActivationSource,
          ),
          json = config.json,
        ),
    )
  }

  private fun hasTurnBudgetRemaining(turn: Int): Boolean =
    config.maxTurns == 0 || turn < config.maxTurns

  private fun applyTurnStartSupplements(
    task: AgentTask,
    turn: Int,
    transcript: MutableList<RuntimeConversationMessage>,
  ) {
    val supplements = config.supplementInputProvider(runIdFor(task), task.id)
      .sortedBy(OpenCraySupplementInput::createdAtEpochMs)
    if (supplements.isEmpty()) {
      return
    }
    supplements.forEach { supplement ->
      val text = supplement.text.trim().takeIf(String::isNotBlank) ?: return@forEach
      transcript += RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = text,
      )
      eventSink.onRunEvent(
        task = task,
        event = OpenCraySupplementEvent(
          runId = runIdFor(task),
          taskId = task.id,
          turn = turn,
          entryId = supplement.entryId,
          text = text,
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
  ): List<RuntimeConversationMessage> {
    val reminder = turnBudgetReminderFor(turn) ?: return transcript
    if (transcript.lastOrNull() == reminder) {
      return transcript
    }
    return transcript + reminder
  }

  private fun enforcedSystemPromptForTurn(
    systemPrompt: String?,
    turn: Int,
  ): String? {
    val appendix = when (remainingTurnBudget(turn)) {
      1 -> FINAL_TURN_SYSTEM_PROMPT_APPENDIX
      2 -> PENULTIMATE_TURN_SYSTEM_PROMPT_APPENDIX
      else -> null
    } ?: return systemPrompt
    return listOfNotNull(systemPrompt?.trim()?.takeIf(String::isNotBlank), appendix)
      .joinToString(separator = "\n\n")
  }

  private fun turnBudgetReminderFor(turn: Int): RuntimeConversationMessage? = when (remainingTurnBudget(turn)) {
    1 -> RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildFinalAnswerRequiredObservation(),
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
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayAssistantEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        text = text,
        responseFormat = responseFormat,
        isFinal = isFinal,
        emittedAtEpochMs = clock(),
      ),
    )
  }

  private fun emitProgressEvent(
    task: AgentTask,
    turn: Int,
    text: String,
    stage: String?,
  ) {
    eventSink.onRunEvent(
      task = task,
      event = OpenCrayProgressEvent(
        runId = runIdFor(task),
        taskId = task.id,
        turn = turn,
        text = text,
        stage = stage,
        emittedAtEpochMs = clock(),
      ),
    )
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
      RuntimeConversationMessageKind.PROGRESS,
      -> null
    } ?: when (message.role) {
      RuntimeConversationRole.ASSISTANT -> parseToolCallTranscriptEntry(message.content)?.id
      RuntimeConversationRole.TOOL -> parseToolResultObservation(message.content)?.toolCallId
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
  ): String = "tool_call ${config.json.encodeToString(
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
  )}"

  private fun buildToolResultTranscriptEntry(
    task: AgentTask,
    turn: Int,
    call: AgentToolCall,
    result: AgentToolResult,
  ): String = config.json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
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
          result.metadata.toSortedMap().forEach { (key, value) -> put(key, value) }
        },
      )
    },
  )

  private fun buildProgressTranscriptEntry(
    task: AgentTask,
    turn: Int,
    progress: AgentModelAction.Progress,
  ): String = buildString {
    append("progress ")
    append(
      config.json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put("run_id", runIdFor(task))
          put("task_id", task.id)
          put("turn", turn)
          put("text", progress.text)
          progress.stage?.let { stage -> put("stage", stage) }
        },
      ),
    )
  }

  private fun buildGatewayMessages(
    contextPrompt: String,
    transcript: List<RuntimeConversationMessage>,
  ): List<LiteLlmGatewayMessage> {
    val messages = mutableListOf<LiteLlmGatewayMessage>()
    contextPrompt
      .trim()
      .takeIf(String::isNotBlank)
      ?.let { prompt ->
        messages += LiteLlmGatewayMessage(
          role = LiteLlmGatewayMessageRole.USER,
          content = prompt,
        )
      }
    var syntheticToolCallIndex = nextSyntheticToolCallSequence(transcript) - 1
    var pendingToolCallId: String? = null
    var pendingToolName: String? = null
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
          )
        }

        RuntimeConversationRole.ASSISTANT -> {
          val toolCall = runtimeToolCallFor(entry)
          if (toolCall != null) {
            val normalizedToolCall = toolCall.id
              ?.takeIf(String::isNotBlank)
              ?.let { existingId -> toolCall.copy(id = existingId) }
              ?: toolCall.copy(
                id = "oc-call-${++syntheticToolCallIndex}",
              )
            pendingToolCallId = normalizedToolCall.id
            pendingToolName = normalizedToolCall.toolName
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
            )
          } else {
            messages += LiteLlmGatewayMessage(
              role = LiteLlmGatewayMessageRole.ASSISTANT,
              content = entry.content,
            )
          }
        }

        RuntimeConversationRole.TOOL -> {
          if (entry.kind == RuntimeConversationMessageKind.PROGRESS) {
            return@forEach
          }
          val observation = runtimeToolResultFor(entry) ?: return@forEach
          messages += LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.TOOL,
            toolResult = LiteLlmGatewayToolResult(
              toolCallId = observation.toolCallId ?: pendingToolCallId,
              toolName = observation.toolName,
              content = entry.content,
              isError = observation.isError,
            ),
          )
          pendingToolCallId = null
          pendingToolName = null
        }
      }
    }
    return messages
  }

  private fun parseToolCallTranscriptEntry(content: String): AgentToolCall? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("tool_call ")) {
      return null
    }
    val payload = trimmed.removePrefix("tool_call ").trim()
    if (payload.startsWith("{")) {
      val parsed = runCatching {
        config.json.parseToJsonElement(payload) as? JsonObject
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
    val argumentsStart = trimmed.indexOf('{')
    if (argumentsStart <= "tool_call ".length) {
      return null
    }
    val header = trimmed.substring("tool_call ".length, argumentsStart).trim()
    val arguments = runCatching {
      config.json.parseToJsonElement(trimmed.substring(argumentsStart)) as? JsonObject
    }.getOrNull() ?: return null
    val toolName = header.substringBefore(" id=", missingDelimiterValue = header)
      .substringBefore(" reason=", missingDelimiterValue = header)
      .trim()
      .takeIf(String::isNotBlank)
      ?: return null
    val reason = header.substringAfter(" reason=", missingDelimiterValue = "")
      .trim()
      .takeIf(String::isNotBlank)
    return AgentToolCall(
      id = header.substringAfter(" id=", missingDelimiterValue = "")
        .substringBefore(" reason=", missingDelimiterValue = "")
        .trim()
        .takeIf(String::isNotBlank),
      toolName = toolName,
      arguments = arguments,
      reason = reason,
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

  private fun runtimeToolResultFor(entry: RuntimeConversationMessage): ParsedToolResultObservation? =
    parseToolResultObservation(entry.content)
      ?: entry.toolResult?.let { toolResult ->
        ParsedToolResultObservation(
          toolCallId = toolResult.toolCallId,
          toolName = toolResult.toolName,
          isError = toolResult.isError ?: toolResult.status
            ?.equals(AgentToolResultStatus.SUCCESS.name, ignoreCase = true)
            ?.not()
            ?: false,
        )
      }

  private fun parseToolResultObservation(content: String): ParsedToolResultObservation? {
    val payload = content.trim().removePrefix("tool_result ").trim()
    val parsed = runCatching {
      config.json.parseToJsonElement(payload) as? JsonObject
    }.getOrNull() ?: return null
    val toolCallId = parsed.primitiveContent("tool_call_id")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val toolName = parsed.primitiveContent("tool_name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val status = parsed.primitiveContent("status")
      ?.trim()
      ?.lowercase()
    return ParsedToolResultObservation(
      toolCallId = toolCallId,
      toolName = toolName,
      isError = status != null && status != AgentToolResultStatus.SUCCESS.name.lowercase(),
    )
  }

  private fun AgentToolCall.toRuntimeConversationToolCall(): RuntimeConversationToolCall =
    RuntimeConversationToolCall(
      id = id,
      toolName = toolName,
      arguments = arguments,
      reason = reason,
    )

  private fun AgentModelAction.toSerializableModelAction(): OpenCraySerializableModelAction = when (this) {
    is AgentModelAction.Progress -> OpenCraySerializableModelAction.Progress(
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
    is OpenCraySerializableModelAction.Progress -> AgentModelAction.Progress(
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
  ): AgentToolResult? {
    if (!call.toolName.trim().equals("Task", ignoreCase = true)) {
      return null
    }
    if (toolDispatcher.definitions().none { definition ->
        definition.name.trim().equals("Task", ignoreCase = true)
      }
    ) {
      return toolDispatcher.dispatch(task = task, call = call, hooks = hooks)
    }
    val description = call.arguments.primitiveContent("description")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return invalidSubAgentCallResult(call, "Task description must not be blank.")
    val prompt = call.arguments.primitiveContent("prompt")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return invalidSubAgentCallResult(call, "Task prompt must not be blank.")
    val subagentType = call.arguments.primitiveContent("subagent_type")
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return invalidSubAgentCallResult(call, "Task subagent_type must not be blank.")
    val profile = BuiltInSubAgentProfiles.resolve(subagentType)
      ?: return invalidSubAgentCallResult(
        call = call,
        message = "Unknown Task subagent_type '$subagentType'.",
      )
    val requestedContextMode = call.arguments.primitiveContent("context_mode")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val resolvedContextMode = when {
      requestedContextMode == null -> profile.defaultContextMode
      else -> SubAgentContextMode.fromWireValue(requestedContextMode)
        ?: return invalidSubAgentCallResult(
          call = call,
          message = "Unknown Task context_mode '$requestedContextMode'. Expected one of: minimal, delegated, mirrored.",
        )
    }
    val parentDepth = task.metadata[SubAgentMetadataKeys.DEPTH]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toIntOrNull()
      ?: 0
    val childDepth = parentDepth + 1
    if (childDepth > config.maxSubAgentDepth) {
      return AgentToolResult(
        toolName = call.toolName,
        status = AgentToolResultStatus.FAILED,
        content = "Task delegation depth exceeded the configured child-runtime limit.",
        errorCode = "SUBAGENT_DEPTH_EXCEEDED",
        errorMessage = "Task delegation depth exceeded the configured child-runtime limit.",
        metadata = mapOf(
          "subagentType" to profile.id,
          "subagentDepth" to childDepth.toString(),
          "maxSubAgentDepth" to config.maxSubAgentDepth.toString(),
        ),
      )
    }
    val childTask = SubAgentTask(
      description = description,
      prompt = prompt,
      subagentType = profile.id,
      contextMode = resolvedContextMode,
      parentRunId = runIdFor(task),
      parentTaskId = task.id,
      parentTurn = turn,
      depth = childDepth,
      activeSkillName = activeSkillCapsule?.name,
    )
    val delegationPlan = toolDispatcher.planTaskDelegation(
      task = task,
      description = description,
      prompt = prompt,
      subagentType = profile.id,
      contextMode = childTask.contextMode.wireValue,
      allowedToolNames = profile.allowedToolNames,
    )
    toolDispatcher.gateTaskDelegation(delegationPlan)?.let { deniedResult ->
      return deniedResult.copy(toolName = call.toolName)
    }
    val childContext = config.subAgentContextBuilder.build(
      SubAgentContextBuildRequest(
        parentSessionContext = config.sessionContext,
        childTask = childTask,
        parentGoalSummary = task.input.trim(),
        parentObservationLines = recentToolObservationSupport.summaryLines(transcript),
        parentConversation = transcript.toList(),
        activeSkillCapsule = activeSkillCapsule,
      ),
    )
    val childRunId = "subagent-${runIdFor(task)}-$turn-${UUID.randomUUID().toString().take(8)}"
    val childPromptTask = AgentTask(
      id = "subagent-task-${UUID.randomUUID().toString().take(8)}",
      type = AgentTaskType.PROMPT,
      input = prompt,
      policyDecision = task.policyDecision,
      createdAtEpochMs = clock(),
      metadata = task.metadata
        .filterKeys { key -> !key.startsWith(HIDDEN_METADATA_PREFIX) } +
        childTask.metadata() +
        mapOf(RUN_ID_METADATA_KEY to childRunId),
    )
    emitSubAgentEvent(
      task = task,
      turn = turn,
      phase = OpenCraySubAgentPhase.STARTED,
      childTask = childTask,
      childRunId = childRunId,
      childTaskId = childPromptTask.id,
      summary = null,
      snapshot = SubAgentExecutionSnapshot.running(),
    )
    val approvedSubAgentResume = pendingApprovedSubAgentResume
    pendingApprovedSubAgentResume = null
    val rejectedSubAgentResume = pendingRejectedSubAgentResume
    pendingRejectedSubAgentResume = null
    check(approvedSubAgentResume == null || rejectedSubAgentResume == null) {
      "Only one subagent approval continuation can be pending at a time."
    }
    val childToolDispatcher = when {
      approvedSubAgentResume != null -> toolDispatcher.withApprovalGrant(
        approvedTaskId = childPromptTask.id,
        approvedToolName = approvedSubAgentResume.approvedToolName,
      )
      rejectedSubAgentResume != null -> toolDispatcher.withApprovalRejection(
        rejectedTaskId = childPromptTask.id,
        rejectedToolName = rejectedSubAgentResume.approvedToolName,
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
        promptResumeState = approvedSubAgentResume?.promptResumeState ?: rejectedSubAgentResume?.promptResumeState,
        approvedSubAgentResume = null,
        rejectedSubAgentResume = null,
        supplementInputProvider = { _, _ -> emptyList() },
      ),
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
      clock = clock,
    )
    val childResult = childRuntime.execute(childPromptTask, hooks)
    val compressedChildResult = SubAgentResultCompressor.compress(childResult)
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
      childTask = childTask,
      childRunId = childRunId,
      childTaskId = childPromptTask.id,
      summary = compressedChildResult.summaryText(),
      snapshot = compressedChildResult,
    )
    return childResultToTaskToolResult(
      call = call,
      profileId = profile.id,
      childTask = childTask,
      delegationPlan = delegationPlan,
      childRunId = childRunId,
      childResult = childResult,
      compressedChildResult = compressedChildResult,
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

  private fun childResultToTaskToolResult(
    call: AgentToolCall,
    profileId: String,
    childTask: SubAgentTask,
    delegationPlan: ToolPolicyPlan,
    childRunId: String,
    childResult: ExecutionResult,
    compressedChildResult: SubAgentExecutionSnapshot,
  ): AgentToolResult {
    val childTurnCount = childResult.metadata["turnCount"].orEmpty()
    val childToolCallCount = childResult.metadata["toolCallCount"].orEmpty()
    val childApprovalResume = childApprovalResume(childResult)
    val childApprovalMetadata = childApprovalMetadata(
      childMetadata = childResult.metadata,
      childApprovalResume = childApprovalResume,
    )
    val baseMetadata = linkedMapOf(
      "subagentType" to profileId,
      "subagentContextMode" to childTask.contextMode.wireValue,
      "subagentDepth" to childTask.depth.toString(),
      "childRunId" to childRunId,
      "childTaskId" to childResult.taskId,
      "childExecutionStatus" to childResult.status.name,
    ).apply {
      if (childTurnCount.isNotBlank()) {
        put("childTurnCount", childTurnCount)
      }
      if (childToolCallCount.isNotBlank()) {
        put("childToolCallCount", childToolCallCount)
      }
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
  ): SubAgentApprovalResume? {
    val encodedResume = SubAgentApprovalResumeMetadata.decodeFromMetadata(
      metadata = childResult.metadata,
      json = config.json,
    )
    if (encodedResume != null) {
      return encodedResume
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
    metadata["normalizedToolName"]
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

  private fun buildSingleActionReminderObservation(): String = buildString {
    appendLine("Protocol note: return only the next step on each turn.")
    appendLine("If native tool calling is available, prefer it over the legacy JSON tool_call fallback.")
    appendLine("You may include one short public progress summary before that action.")
    appendLine("Do not include a final answer alongside a tool_call.")
    append("If you need multiple tools, call them one at a time across turns.")
  }.trim()

  private fun buildFinalAnswerSoonObservation(): String = buildString {
    appendLine("Turn budget note: after this turn, only one model turn remains.")
    appendLine("If you still need one last tool, use it now.")
    append("The next turn must return a final answer without calling another tool.")
  }.trim()

  private fun buildFinalAnswerRequiredObservation(): String = buildString {
    appendLine("Turn budget note: this is the last allowed model turn.")
    appendLine("Do not call any more tools.")
    append("Return the best user-facing final answer now. Prefer plain assistant text. If this endpoint is still on the legacy fallback path, return exactly one JSON final action.")
  }.trim()

  private fun buildProtocolRecoveryObservation(
    rawOutput: String,
    reason: String,
  ): String = buildString {
    appendLine("Protocol error: either use native tool calling or return exactly one JSON object whose legacy action is progress, tool_call, or final.")
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    appendLine("If you include progress, keep it public, short, and non-sensitive.")
    appendLine("If you need multiple tools, call only the next tool now and wait for the next turn.")
    appendLine("Do not explain the protocol. Do not answer in prose unless you emit type=final.")
    appendLine("Reason: $reason")
    val preview = rawOutput.trim().take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS)
    if (preview.isNotBlank()) {
      appendLine("Previous response preview:")
      append(preview)
    }
  }.trim()

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
    "mcp_list_servers" -> "mcp_list_servers"
    else -> toolName.trim().lowercase()
  }

  private sealed interface AgentModelAction {
    data class Progress(
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
    var turn: Int,
    var toolCallCount: Int,
    var activeSkillName: String?,
    var activeSkillActivationSource: String?,
    var nextSyntheticToolCallSequence: Int,
  )

  private data class PromptRunDiagnostics(
    var nativeToolCallRequested: Boolean = false,
    var parsedToolCallObserved: Boolean = false,
    var fallbackParserAttempted: Boolean = false,
    var fallbackParserSucceeded: Boolean = false,
    var toolCallEventEmitted: Boolean = false,
    var toolResultEventEmitted: Boolean = false,
    var lastSuccessfulToolName: String? = null,
  )

  private data class ParsedToolResultObservation(
    val toolCallId: String? = null,
    val toolName: String,
    val isError: Boolean,
  )

  private data class MemoryRetrievalTrace(
    val operation: String,
    val toolName: String,
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
            actions.count { action -> action !is AgentModelAction.Progress } > 1 ||
            actions.count { action -> action is AgentModelAction.Progress } > 1
    }

    data class ProtocolError(
      val reason: String,
    ) : ParsedModelActionBatch
  }

  private companion object {
    const val HIDDEN_METADATA_PREFIX: String = "_host."
    const val RUN_ID_METADATA_KEY: String = "${HIDDEN_METADATA_PREFIX}runId"
    const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    const val ERROR_SKILL_TOOL_POLICY_BLOCKED: String = "SKILL_TOOL_POLICY_BLOCKED"
    const val MAX_PROTOCOL_ERROR_PREVIEW_CHARS: Int = 600
    const val ACTIVATION_SOURCE_SKILL_READ: String = "skill_read"
    const val PENULTIMATE_TURN_SYSTEM_PROMPT_APPENDIX: String =
      "[Turn Budget]\nYou have two model turns left including this one. If another tool is still necessary, use at most one more tool now and be ready to answer on the next turn."
    const val FINAL_TURN_SYSTEM_PROMPT_APPENDIX: String =
      "[Turn Budget]\nThis is the last allowed model turn. Do not call any more tools. Return the final user-facing answer now. Prefer plain assistant text, and use the legacy JSON final action only if this endpoint is still on fallback."
    val CHILD_APPROVAL_METADATA_KEYS: Set<String> = setOf(
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
      "sourcePath",
      "destinationPath",
      "workingDirectory",
      "policyOutcome",
      "policyReasonCode",
      "approvalRisk",
    )
    val DEFAULT_ACTIVE_SKILL_EXEMPT_TOOL_KEYS: Set<String> = setOf("skills_list", "skill_read")
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
