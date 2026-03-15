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
import com.opencray.core.orchestrator.SystemQueueClock
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextAssemblyReport
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class OpenCrayAgentRuntimeConfig(
  val maxTurns: Int = 8,
  val maxToolCalls: Int = 12,
  val systemPrompt: String = DEFAULT_OPENCRAY_SYSTEM_PROMPT,
  val sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
  val promptAssembler: PromptAssembler = PromptAssembler(),
  val llmMetadata: Map<String, String> = emptyMap(),
  val llmAuthHeaders: Map<String, String> = emptyMap(),
  val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
  init {
    require(maxTurns >= 1) { "OpenCrayAgentRuntimeConfig maxTurns must be >= 1." }
    require(maxToolCalls >= 1) { "OpenCrayAgentRuntimeConfig maxToolCalls must be >= 1." }
    require(systemPrompt.isNotBlank()) { "OpenCrayAgentRuntimeConfig systemPrompt must not be blank." }
  }

  companion object {
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

    while (turn < config.maxTurns) {
      if (hooks.isCancellationRequested()) {
        return cancelledResult(task = task, startedAt = startedAt, finishedAt = clock())
      }

      val assembledPrompt = config.promptAssembler.assemble(
        PromptAssemblyInput(
          task = task,
          baseSystemPrompt = config.systemPrompt,
          sessionContext = config.sessionContext,
          toolDefinitions = toolDispatcher.definitions(),
          liveConversation = transcript,
        ),
      )
      lastContextReport = assembledPrompt.report

      val runId = runIdFor(task)
      val request = LiteLlmGatewayRequest(
        requestId = "agent-$runId-turn-$turn-${UUID.randomUUID().toString().take(8)}",
        prompt = assembledPrompt.taskPrompt,
        systemPrompt = assembledPrompt.systemPrompt,
        metadata = buildMap {
          put("runId", runId)
          put("taskId", task.id)
          put("taskType", task.type.name)
          put("turnIndex", turn.toString())
          put("contextSourceMessageCount", assembledPrompt.report.sourceTranscriptMessageCount.toString())
          put("contextWindowMessageCount", assembledPrompt.report.windowedTranscriptMessageCount.toString())
          put("contextMessageCount", assembledPrompt.report.windowedTranscriptMessageCount.toString())
          put("contextOmittedMessageCount", assembledPrompt.report.omittedTranscriptMessageCount.toString())
          put("contextTruncatedMessageCount", assembledPrompt.report.truncatedTranscriptMessageCount.toString())
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
          val toolCalls = parsedBatch.actions.filterIsInstance<AgentModelAction.ToolCall>()
          if (toolCalls.isNotEmpty()) {
            var shouldContinueBatch = true
            toolCalls.forEach { toolAction ->
              if (!shouldContinueBatch) return@forEach
              if (toolCallCount >= config.maxToolCalls) {
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
              val toolResult = toolDispatcher.dispatch(task = task, call = toolAction.call, hooks = hooks)
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
    return toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      json = config.json,
    )
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
    }
    gatewayResult?.selectedRoute?.routeId?.let { put("selectedRouteId", it) }
    gatewayResult?.selectedRoute?.providerId?.let { put("selectedProviderId", it) }
    gatewayResult?.selectedRoute?.model?.let { put("selectedModel", it) }
    gatewayResult?.completionMode?.name?.let { put("completionMode", it) }
    gatewayResult?.status?.name?.let { put("llmStatus", it) }
  }

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

  private fun toolReasonMetadata(call: AgentToolCall): Map<String, String> =
    call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { reason -> mapOf("toolReason" to reason) }
      ?: emptyMap()

  private fun buildSingleActionReminderObservation(): String = buildString {
    appendLine("Protocol note: return only the next action on each turn.")
    appendLine("Do not include a final answer alongside a tool_call.")
    append("If you need multiple tools, call them one at a time across turns.")
  }.trim()

  private fun buildProtocolRecoveryObservation(
    rawOutput: String,
    reason: String,
  ): String = buildString {
    appendLine("Protocol error: return exactly one JSON action with type=tool_call or type=final.")
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    appendLine("If you need multiple tools, call only the next tool now and wait for the next turn.")
    appendLine("Do not explain the protocol. Do not answer in prose unless you emit type=final.")
    appendLine("Reason: $reason")
    val preview = rawOutput.trim().take(MAX_PROTOCOL_ERROR_PREVIEW_CHARS)
    if (preview.isNotBlank()) {
      appendLine("Previous response preview:")
      append(preview)
    }
  }.trim()

  private sealed interface AgentModelAction {
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
        get() = ignoredNonActionContent || actions.size > 1
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
    const val MAX_PROTOCOL_ERROR_PREVIEW_CHARS: Int = 600
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
