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
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.ContextAssemblyReport
import com.opencray.runtime.context.DuplicateDiscoveryToolHit
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.context.RecentToolObservationSupport
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.memory.MemoryToolOperation
import com.opencray.runtime.memory.memoryToolTraceFrom
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillCapsuleResolver
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class OpenCrayAgentRuntimeConfig(
  val maxTurns: Int = DEFAULT_MAX_TURNS,
  val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
  val systemPrompt: String = DEFAULT_OPENCRAY_SYSTEM_PROMPT,
  val sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
  val contextManager: ContextManager = ContextManager(),
  val promptAssembler: PromptAssembler = PromptAssembler(),
  val llmMetadata: Map<String, String> = emptyMap(),
  val llmAuthHeaders: Map<String, String> = emptyMap(),
  val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
  init {
    require(maxTurns >= 0) { "OpenCrayAgentRuntimeConfig maxTurns must be >= 0." }
    require(maxToolCalls >= 0) { "OpenCrayAgentRuntimeConfig maxToolCalls must be >= 0." }
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
    val transcript = seededConversation(task).toMutableList()
    var toolCallCount = 0
    var turn = 0
    var lastGatewayResult: LiteLlmGatewayResult? = null
    var lastContextReport: ContextAssemblyReport? = null
    var protocolErrorCount = 0
    var lastProtocolErrorMessage: String? = null
    var activeSkillName: String? = null
    var activeSkillActivationSource: String? = null

    while (hasTurnBudgetRemaining(turn)) {
      if (hooks.isCancellationRequested()) {
        return cancelledResult(task = task, startedAt = startedAt, finishedAt = clock())
      }

      val turnAwareConversation = promptConversationForTurn(
        transcript = transcript,
        turn = turn,
      )
      val activeSkillCapsule = activeSkillCapsuleResolver.resolve(
        catalog = config.sessionContext.skillCatalog,
        activeSkillName = activeSkillName,
        activationSource = activeSkillActivationSource,
      )
      val visibleToolDefinitions = visibleToolDefinitionsForTurn(
        allDefinitions = toolDispatcher.definitions(),
        activeSkillCapsule = activeSkillCapsule,
      )
      val managedContext = config.contextManager.prepare(
        PromptAssemblyInput(
          task = task,
          baseSystemPrompt = config.systemPrompt,
          sessionContext = config.sessionContext,
          activeSkillCapsule = activeSkillCapsule,
          toolDefinitions = visibleToolDefinitions,
          liveConversation = turnAwareConversation,
        ),
      )
      val assembledPrompt = config.promptAssembler.assemble(managedContext)
      lastContextReport = assembledPrompt.report
      val enforcedSystemPrompt = enforcedSystemPromptForTurn(
        systemPrompt = assembledPrompt.systemPrompt,
        turn = turn,
      )

      val runId = runIdFor(task)
      val request = LiteLlmGatewayRequest(
        requestId = "agent-$runId-turn-$turn-${UUID.randomUUID().toString().take(8)}",
        prompt = assembledPrompt.taskPrompt,
        systemPrompt = enforcedSystemPrompt,
        metadata = buildMap {
          put("runId", runId)
          put("taskId", task.id)
          put("taskType", task.type.name)
          put("turnIndex", turn.toString())
          remainingTurnBudget(turn)?.let { remainingTurns ->
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
          putAll(task.metadata.filterKeys(::isGatewayVisibleMetadataKey))
          putAll(config.llmMetadata)
        },
        authHeaders = config.llmAuthHeaders,
      )
      val gatewayResult = gateway.execute(request)
      lastGatewayResult = gatewayResult
      val outputText = gatewayResult.outputText
      if (gatewayResult.status != LiteLlmGatewayStatus.SUCCESS || outputText.isNullOrBlank()) {
        return llmFailureResult(
          task = task,
          startedAt = startedAt,
          gatewayResult = gatewayResult,
          turn = turn,
          toolCallCount = toolCallCount,
          contextReport = lastContextReport,
        )
      }

      val parsedBatch = parseModelActionBatch(outputText)
      when (parsedBatch) {
        is ParsedModelActionBatch.ProtocolError -> {
          protocolErrorCount += 1
          lastProtocolErrorMessage = parsedBatch.reason
          transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = outputText.trim().take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS),
          )
          transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = buildProtocolRecoveryObservation(
              rawOutput = outputText,
              reason = parsedBatch.reason,
            ),
          )
          turn += 1
          continue
        }

        is ParsedModelActionBatch.Actions -> {
          val progressActions = parsedBatch.actions.filterIsInstance<AgentModelAction.Progress>()
          progressActions.forEach { progressAction ->
            emitProgressEvent(
              task = task,
              turn = turn,
              text = progressAction.text,
              stage = progressAction.stage,
            )
            transcript += RuntimeConversationMessage(
              role = RuntimeConversationRole.TOOL,
              content = buildProgressTranscriptEntry(
                task = task,
                turn = turn,
                progress = progressAction,
              ),
            )
          }
          val toolCalls = parsedBatch.actions.filterIsInstance<AgentModelAction.ToolCall>()
          if (toolCalls.isNotEmpty()) {
            if (isFinalAnswerOnlyTurn(turn)) {
              return failedResult(
                task = task,
                startedAt = startedAt,
                finishedAt = clock(),
                errorCode = "MAX_TURNS_EXCEEDED",
                errorMessage = "Agent reached the configured turn limit and still tried to call another tool instead of returning a final answer.",
                metadata = buildResultMetadata(
                  gatewayResult = gatewayResult,
                  turn = turn,
                  toolCallCount = toolCallCount,
                  responseFormat = "turn_limit_final_answer_required",
                  contextReport = lastContextReport,
                ) + mapOf("finalAnswerRequired" to "true"),
              )
            }
            var shouldContinueBatch = true
            toolCalls.forEach { toolAction ->
              if (!shouldContinueBatch) return@forEach
              if (config.maxToolCalls > 0 && toolCallCount >= config.maxToolCalls) {
                return failedResult(
                  task = task,
                  startedAt = startedAt,
                  finishedAt = clock(),
                  errorCode = "MAX_TOOL_CALLS_EXCEEDED",
                  errorMessage = "Agent exceeded the configured tool call budget.",
                  metadata = buildResultMetadata(
                    gatewayResult = gatewayResult,
                    turn = turn,
                    toolCallCount = toolCallCount,
                    responseFormat = "tool_budget_exceeded",
                    contextReport = lastContextReport,
                  ),
                )
              }

              transcript += RuntimeConversationMessage(
                role = RuntimeConversationRole.ASSISTANT,
                content = buildToolCallTranscriptEntry(toolAction.call),
              )
              eventSink.onRunEvent(
                task = task,
              event = OpenCrayToolCallEvent(
                  runId = runIdFor(task),
                  taskId = task.id,
                  turn = turn,
                  call = toolAction.call,
                  emittedAtEpochMs = clock(),
                ),
              )
              val toolResult = gateActiveSkillToolCall(
                call = toolAction.call,
                activeSkillCapsule = activeSkillCapsule,
              ) ?: maybeShortCircuitDuplicateDiscoveryCall(
                call = toolAction.call,
                transcript = transcript,
              ) ?: toolDispatcher.dispatch(task = task, call = toolAction.call, hooks = hooks)
              eventSink.onRunEvent(
                task = task,
                event = OpenCrayToolResultEvent(
                  runId = runIdFor(task),
                  taskId = task.id,
                  turn = turn,
                  call = toolAction.call,
                  result = toolResult,
                  emittedAtEpochMs = clock(),
                ),
              )
              emitMemoryRetrievalEvent(
                task = task,
                turn = turn,
                call = toolAction.call,
                result = toolResult,
              )
              if (toolResult.status == AgentToolResultStatus.CANCELLED) {
                return cancelledResult(task = task, startedAt = startedAt, finishedAt = clock())
              }
              if (toolResult.isApprovalRequiredDenial()) {
                val approvalResult = toolResult.toExecutionResult(
                  task = task,
                  startedAt = startedAt,
                  finishedAt = clock(),
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
                      contextReport = lastContextReport,
                    ) +
                    toolReasonMetadata(toolAction.call),
                )
              }
              if (toolResult.status == AgentToolResultStatus.SUCCESS) {
                val activatedSkillName = activatedSkillNameFrom(
                  call = toolAction.call,
                  result = toolResult,
                )
                if (!activatedSkillName.isNullOrBlank()) {
                  activeSkillName = activatedSkillName
                  activeSkillActivationSource = ACTIVATION_SOURCE_SKILL_READ
                }
              }
              transcript += RuntimeConversationMessage(
                role = RuntimeConversationRole.TOOL,
                content = toolResult.toObservationText(config.json),
              )
              toolCallCount += 1
              if (toolResult.status != AgentToolResultStatus.SUCCESS) {
                shouldContinueBatch = false
              }
            }
            if (parsedBatch.requiresSingleActionReminder) {
              transcript += RuntimeConversationMessage(
                role = RuntimeConversationRole.TOOL,
                content = buildSingleActionReminderObservation(),
              )
            }
            turn += 1
            continue
          }

          val finalAction = parsedBatch.actions.lastOrNull { action -> action is AgentModelAction.Final }
            as? AgentModelAction.Final
          if (finalAction == null) {
            if (progressActions.isNotEmpty()) {
              if (parsedBatch.requiresSingleActionReminder) {
                transcript += RuntimeConversationMessage(
                  role = RuntimeConversationRole.TOOL,
                  content = buildSingleActionReminderObservation(),
                )
              }
              turn += 1
              continue
            }
            protocolErrorCount += 1
            lastProtocolErrorMessage = "Model output did not contain a usable action."
            transcript += RuntimeConversationMessage(
              role = RuntimeConversationRole.ASSISTANT,
              content = outputText.trim().take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS),
            )
            transcript += RuntimeConversationMessage(
              role = RuntimeConversationRole.TOOL,
              content = buildProtocolRecoveryObservation(
                rawOutput = outputText,
                reason = lastProtocolErrorMessage.orEmpty(),
              ),
            )
            turn += 1
            continue
          }
          emitAssistantEvent(
            task = task,
            turn = turn,
            text = finalAction.answer,
            responseFormat = finalAction.responseFormat,
            isFinal = true,
          )
          return successResult(
            task = task,
            body = finalAction.answer,
            startedAt = startedAt,
            finishedAt = clock(),
            metadata = buildResultMetadata(
              gatewayResult = gatewayResult,
              turn = turn,
              toolCallCount = toolCallCount,
              responseFormat = finalAction.responseFormat,
              contextReport = lastContextReport,
            ),
          )
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
          turn = turn,
          toolCallCount = toolCallCount,
          responseFormat = "protocol_error_exhausted",
          contextReport = lastContextReport,
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
        turn = turn,
        toolCallCount = toolCallCount,
        responseFormat = "turn_limit_exceeded",
        contextReport = lastContextReport,
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
    val toolResult = toolDispatcher.dispatch(task = task, call = toolCall, hooks = hooks)
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
    val toolCalls = (parsed["tool_calls"] as? kotlinx.serialization.json.JsonArray)
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
        actions = listOf(
          AgentModelAction.Final(
            answer = parsed.primitiveContent("answer")?.trim().orEmpty().ifBlank {
              error("Final action must contain a non-blank 'answer'.")
            },
            responseFormat = "json_final",
          ),
        ),
      )

      type == null -> error("Model output must contain 'type' or 'decision'.")

      else -> error("Unsupported model action type '$type'.")
    }
  }

  private fun parseToolCall(parsed: JsonObject): AgentToolCall = AgentToolCall(
    toolName = parsed.primitiveContent("tool_name")?.trim().orEmpty().ifBlank {
      error("tool_call action must contain a non-blank 'tool_name'.")
    },
    arguments = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
    reason = parsed.primitiveContent("reason")?.trim()?.takeIf(String::isNotBlank)
      ?: parsed.primitiveContent("justification")?.trim()?.takeIf(String::isNotBlank),
  )

  private fun buildResultMetadata(
    gatewayResult: LiteLlmGatewayResult?,
    turn: Int,
    toolCallCount: Int,
    responseFormat: String,
    contextReport: ContextAssemblyReport? = null,
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
  }

  private fun visibleToolDefinitionsForTurn(
    allDefinitions: List<AgentToolDefinition>,
    activeSkillCapsule: ActiveSkillCapsule?,
  ): List<AgentToolDefinition> {
    val allowedToolKeys = normalizedAllowedToolKeys(activeSkillCapsule)
      .takeIf { keys -> keys.isNotEmpty() }
      ?.plus(DEFAULT_ACTIVE_SKILL_EXEMPT_TOOL_KEYS)
      ?: return allDefinitions
    return allDefinitions.filter { definition ->
      toolPolicyKey(definition.name) in allowedToolKeys
    }
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

  private fun llmFailureResult(
    task: AgentTask,
    startedAt: Long,
    gatewayResult: LiteLlmGatewayResult,
    turn: Int,
    toolCallCount: Int,
    contextReport: ContextAssemblyReport?,
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

  private fun runIdFor(task: AgentTask): String =
    task.metadata[RUN_ID_METADATA_KEY]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun isGatewayVisibleMetadataKey(key: String): Boolean = !key.startsWith(HIDDEN_METADATA_PREFIX)

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

  private fun hasTurnBudgetRemaining(turn: Int): Boolean =
    config.maxTurns == 0 || turn < config.maxTurns

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
    memoryToolTraceFrom(call = call, result = result)
      ?.let { trace ->
        eventSink.onRunEvent(
          task = task,
          event = OpenCrayMemoryRetrievalEvent(
            runId = runIdFor(task),
            taskId = task.id,
            turn = turn,
            toolName = trace.toolName,
            operation = when (trace.operation) {
              MemoryToolOperation.SEARCH -> "search"
              MemoryToolOperation.GET -> "get"
            },
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

  private fun buildToolCallTranscriptEntry(call: AgentToolCall): String = buildString {
    append("tool_call ")
    append(call.toolName)
    call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reason ->
        append(" reason=")
        append(reason)
      }
    append(' ')
    append(config.json.encodeToString(JsonObject.serializer(), call.arguments))
  }

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

  private fun toolReasonMetadata(call: AgentToolCall): Map<String, String> =
    call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reason -> mapOf("toolReason" to reason) }
      ?: emptyMap()

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
    appendLine("Protocol note: return only the next action on each turn.")
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
    append("Return exactly one JSON final action now with the best user-facing answer you can provide.")
  }.trim()

  private fun buildProtocolRecoveryObservation(
    rawOutput: String,
    reason: String,
  ): String = buildString {
    appendLine("Protocol error: return exactly one JSON object whose action is progress, tool_call, or final.")
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
    ) : AgentModelAction

    data class ToolCall(
      val call: AgentToolCall,
    ) : AgentModelAction
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
      "[Turn Budget]\nThis is the last allowed model turn. You must return exactly one JSON final action now. Do not call any more tools."
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
