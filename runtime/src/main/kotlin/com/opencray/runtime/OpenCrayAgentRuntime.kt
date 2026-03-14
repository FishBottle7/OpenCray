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
  override fun execute(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult = when (task.type) {
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

  private fun executePromptTask(task: AgentTask, hooks: RuntimeExecutionHooks): ExecutionResult {
    val startedAt = clock()
    val transcript = seededConversation(task).toMutableList()
    var toolCallCount = 0
    var turn = 0
    var lastGatewayResult: LiteLlmGatewayResult? = null
    var lastContextReport: ContextAssemblyReport? = null

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

      val request = LiteLlmGatewayRequest(
        requestId = "agent-${task.id}-turn-$turn-${UUID.randomUUID().toString().take(8)}",
        prompt = assembledPrompt.taskPrompt,
        systemPrompt = assembledPrompt.systemPrompt,
        metadata = buildMap {
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

      val modelAction = parseModelAction(outputText)
      when (modelAction) {
        is AgentModelAction.Final -> {
          return successResult(
            task = task,
            body = modelAction.answer,
            startedAt = startedAt,
            finishedAt = clock(),
            metadata = buildResultMetadata(
              gatewayResult = gatewayResult,
              turn = turn,
              toolCallCount = toolCallCount,
              responseFormat = modelAction.responseFormat,
              contextReport = lastContextReport,
            ),
          )
        }

        is AgentModelAction.ToolCall -> {
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
            content = "tool_call ${modelAction.call.toolName} ${config.json.encodeToString(JsonObject.serializer(), modelAction.call.arguments)}",
          )
          eventSink.onToolCall(task = task, turn = turn, call = modelAction.call)
          val toolResult = toolDispatcher.dispatch(task = task, call = modelAction.call, hooks = hooks)
          eventSink.onToolResult(task = task, turn = turn, call = modelAction.call, result = toolResult)
          if (toolResult.status == AgentToolResultStatus.CANCELLED) {
            return cancelledResult(task = task, startedAt = startedAt, finishedAt = clock())
          }
          transcript += RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = toolResult.toObservationText(config.json),
          )
          toolCallCount += 1
        }
      }
      turn += 1
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
    val parsedAction = parseModelAction(task.input)
    val toolCall = (parsedAction as? AgentModelAction.ToolCall)?.call
      ?: return failedResult(
        task = task,
        startedAt = startedAt,
        finishedAt = clock(),
        errorCode = "INVALID_TOOL_CALL_TASK",
        errorMessage = "TOOL_CALL task input must be a JSON tool_call payload.",
        metadata = mapOf("taskType" to task.type.name),
      )

    val toolResult = toolDispatcher.dispatch(task = task, call = toolCall, hooks = hooks)
    return toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      json = config.json,
    )
  }

  private fun executeDirectSkillCall(task: AgentTask): ExecutionResult {
    val startedAt = clock()
    val toolResult = toolDispatcher.dispatch(
      task = task,
      call = AgentToolCall(
        toolName = "skill_read",
        arguments = JsonObject(
          mapOf(
            "name" to JsonPrimitive(task.skillName ?: task.input.trim()),
          ),
        ),
      ),
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> Unit },
      ),
    )
    return toolResult.toExecutionResult(
      task = task,
      startedAt = startedAt,
      finishedAt = clock(),
      json = config.json,
    )
  }

  private fun parseModelAction(rawOutput: String): AgentModelAction {
    val trimmed = rawOutput.trim()
    val jsonCandidate = extractJsonObject(trimmed)
    if (jsonCandidate == null) {
      return AgentModelAction.Final(answer = trimmed, responseFormat = "raw_text")
    }

    return runCatching {
      val parsed = config.json.parseToJsonElement(jsonCandidate) as? JsonObject
        ?: error("Model output must decode to a JSON object.")
      val type = parsed.primitiveContent("type")?.trim()?.lowercase()
        ?: parsed.primitiveContent("decision")?.trim()?.lowercase()
        ?: error("Model output must contain 'type' or 'decision'.")
      when (type) {
        "final", "answer" -> AgentModelAction.Final(
          answer = parsed.primitiveContent("answer")?.trim().orEmpty().ifBlank {
            error("Final action must contain a non-blank 'answer'.")
          },
          responseFormat = "json_final",
        )

        "tool_call", "tool" -> AgentModelAction.ToolCall(
          call = AgentToolCall(
            toolName = parsed.primitiveContent("tool_name")?.trim().orEmpty().ifBlank {
              error("tool_call action must contain a non-blank 'tool_name'.")
            },
            arguments = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
          ),
        )

        else -> error("Unsupported model action type '$type'.")
      }
    }.getOrElse {
      AgentModelAction.Final(answer = trimmed, responseFormat = "raw_fallback")
    }
  }

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

  private fun extractJsonObject(raw: String): String? {
    val fenced = raw.lines()
      .dropWhile { line -> !line.trimStart().startsWith("```") }
      .drop(1)
      .takeWhile { line -> !line.trimStart().startsWith("```") }
      .joinToString(separator = "\n")
      .trim()
    if (fenced.startsWith("{") && fenced.endsWith("}")) {
      return fenced
    }
    if (raw.startsWith("{") && raw.endsWith("}")) {
      return raw
    }

    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
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
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  private fun JsonObject.primitiveContent(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

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

  private sealed interface AgentModelAction {
    data class Final(
      val answer: String,
      val responseFormat: String,
    ) : AgentModelAction

    data class ToolCall(
      val call: AgentToolCall,
    ) : AgentModelAction
  }

  private companion object {
    const val HIDDEN_METADATA_PREFIX: String = "_host."
  }
}

interface OpenCrayAgentRuntimeEventSink {
  fun onToolCall(task: AgentTask, turn: Int, call: AgentToolCall) = Unit

  fun onToolResult(task: AgentTask, turn: Int, call: AgentToolCall, result: AgentToolResult) = Unit
}

object NoOpOpenCrayAgentRuntimeEventSink : OpenCrayAgentRuntimeEventSink

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
