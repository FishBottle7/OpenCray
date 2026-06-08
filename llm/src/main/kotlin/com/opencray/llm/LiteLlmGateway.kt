package com.opencray.llm

import java.util.UUID
import kotlinx.serialization.json.JsonObject

interface LiteLlmGateway {
  fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult
}

interface LiteLlmVisibleTextObserver {
  fun onVisibleTextSnapshot(text: String) = Unit

  fun onVisibleTextReset() = Unit
}

object NoOpLiteLlmVisibleTextObserver : LiteLlmVisibleTextObserver

data class LiteLlmGatewayRequest(
  val requestId: String = "llm-${UUID.randomUUID()}",
  // Compatibility/debug fallback for providers or flows that still accept a single text blob.
  var prompt: String = "",
  val systemPrompt: String? = null,
  // Authoritative transport for the main agent request path.
  val messages: List<LiteLlmGatewayMessage> = emptyList(),
  val tools: List<LiteLlmToolDefinition> = emptyList(),
  val builtinTools: List<LiteLlmBuiltinToolDefinition> = emptyList(),
  val toolChoice: LiteLlmToolChoice? = null,
  val parallelToolCalls: Boolean? = null,
  val previousResponseId: String? = null,
  val responseApiPreferred: Boolean = false,
  val metadata: Map<String, String> = emptyMap(),
  val authHeaders: Map<String, String> = emptyMap(),
  val streamObserver: LiteLlmVisibleTextObserver = NoOpLiteLlmVisibleTextObserver,
) {
  init {
    prompt = prompt.trim().takeIf(String::isNotBlank)
      ?: projectedPromptFromConversation(
        systemPrompt = systemPrompt,
        messages = messages,
      )
    require(requestId.isNotBlank()) { "LiteLlmGatewayRequest requestId must not be blank." }
    require(prompt.isNotBlank() || messages.isNotEmpty()) {
      "LiteLlmGatewayRequest must provide a non-blank prompt or at least one message."
    }
    require(previousResponseId == null || previousResponseId.isNotBlank()) {
      "LiteLlmGatewayRequest previousResponseId must not be blank."
    }
  }
}

private fun projectedPromptFromConversation(
  systemPrompt: String?,
  messages: List<LiteLlmGatewayMessage>,
): String = buildList {
  systemPrompt?.trim()?.takeIf(String::isNotBlank)?.let(::add)
  projectedPromptFromMessages(messages).takeIf(String::isNotBlank)?.let(::add)
}.joinToString(separator = "\n\n").trim()

private fun projectedPromptFromMessages(
  messages: List<LiteLlmGatewayMessage>,
): String = messages.joinToString(separator = "\n\n") { message ->
  buildString {
    message.content?.trim()?.takeIf(String::isNotBlank)?.let(::append)
    when (message.role) {
      LiteLlmGatewayMessageRole.SYSTEM -> Unit

      LiteLlmGatewayMessageRole.USER -> {
        projectedAttachmentFallback(message.attachments)
          ?.let { attachmentsText ->
            if (isNotEmpty()) {
              append("\n\n")
            }
            append(attachmentsText)
          }
      }

      LiteLlmGatewayMessageRole.ASSISTANT -> {
        if (message.toolCalls.isNotEmpty()) {
          if (isNotEmpty()) {
            appendLine()
          }
          message.toolCalls.forEachIndexed { index, toolCall ->
            if (index > 0) {
              appendLine()
            }
            append("{\"tool_name\": \"")
            append(toolCall.toolName)
            append("\", \"arguments\": ")
            append(toolCall.arguments.toString())
            toolCall.id?.takeIf(String::isNotBlank)?.let { toolCallId ->
              append(", \"id\": \"")
              append(escapePromptProjectionText(toolCallId))
              append("\"")
            }
            toolCall.reason?.takeIf(String::isNotBlank)?.let { reason ->
              append(", \"reason\": \"")
              append(escapePromptProjectionText(reason))
              append("\"")
            }
            append('}')
          }
        }
      }

      LiteLlmGatewayMessageRole.TOOL -> {
        message.toolResult?.let { toolResult ->
          toolResult.errorCode?.trim()?.takeIf(String::isNotBlank)?.let { errorCode ->
            if (isNotEmpty()) {
              appendLine()
            }
            append(errorCode)
          }
          toolResult.content.trim().takeIf(String::isNotBlank)?.let { toolResultContent ->
            if (isNotEmpty()) {
              appendLine()
            }
            append(toolResultContent)
          }
        }
      }
    }
  }.trim()
}.trim()

private fun escapePromptProjectionText(
  raw: String,
): String = raw
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")

private fun projectedAttachmentFallback(
  attachments: List<LiteLlmGatewayAttachment>,
): String? {
  if (attachments.isEmpty()) {
    return null
  }
  return buildString {
    appendLine("Attachments:")
    attachments.forEach { attachment ->
      append("- ")
      append(
        attachment.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachment.attachmentId
          ?: "attachment",
      )
      append(" [kind=")
      append(attachment.kind.name.lowercase())
      attachment.filePath?.trim()?.takeIf(String::isNotBlank)?.let { filePath ->
        append(", file_path=")
        append(filePath)
      }
      attachment.mimeType?.trim()?.takeIf(String::isNotBlank)?.let { mimeType ->
        append(", mime_type=")
        append(mimeType)
      }
      append(']')
      appendLine()
    }
  }.trim().takeIf(String::isNotBlank)
}

enum class LiteLlmToolChoiceMode {
  AUTO,
  NONE,
  REQUIRED,
  TOOL,
}

data class LiteLlmToolChoice(
  val mode: LiteLlmToolChoiceMode,
  val toolName: String? = null,
) {
  init {
    require(mode != LiteLlmToolChoiceMode.TOOL || !toolName.isNullOrBlank()) {
      "LiteLlmToolChoice TOOL mode requires a toolName."
    }
    require(mode == LiteLlmToolChoiceMode.TOOL || toolName == null) {
      "LiteLlmToolChoice toolName is only valid in TOOL mode."
    }
  }
}

enum class LiteLlmGatewayMessageRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

enum class LiteLlmAssistantPhase(
  val wireValue: String,
) {
  COMMENTARY("commentary"),
  FINAL_ANSWER("final_answer"),
}

enum class LiteLlmGatewayAttachmentKind {
  IMAGE,
  VOICE,
  AUDIO,
  FILE,
}

data class LiteLlmGatewayAttachment(
  val attachmentId: String? = null,
  val kind: LiteLlmGatewayAttachmentKind,
  val displayName: String? = null,
  val filePath: String? = null,
  val mimeType: String? = null,
  val transcriptText: String? = null,
) {
  init {
    require(attachmentId == null || attachmentId.isNotBlank()) {
      "LiteLlmGatewayAttachment attachmentId must not be blank."
    }
    require(displayName == null || displayName.isNotBlank()) {
      "LiteLlmGatewayAttachment displayName must not be blank."
    }
    require(filePath == null || filePath.isNotBlank()) {
      "LiteLlmGatewayAttachment filePath must not be blank."
    }
    require(mimeType == null || mimeType.isNotBlank()) {
      "LiteLlmGatewayAttachment mimeType must not be blank."
    }
    require(transcriptText == null || transcriptText.isNotBlank()) {
      "LiteLlmGatewayAttachment transcriptText must not be blank."
    }
  }
}

data class LiteLlmGatewayToolResult(
  val toolCallId: String? = null,
  val toolName: String? = null,
  val content: String,
  val structuredContent: JsonObject? = null,
  val isError: Boolean? = null,
  val exitCode: Int? = null,
  val stdout: String? = null,
  val stderr: String? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(content.isNotBlank()) { "LiteLlmGatewayToolResult content must not be blank." }
    require(toolCallId == null || toolCallId.isNotBlank()) {
      "LiteLlmGatewayToolResult toolCallId must not be blank."
    }
    require(toolName == null || toolName.isNotBlank()) {
      "LiteLlmGatewayToolResult toolName must not be blank."
    }
    require(errorCode == null || errorCode.isNotBlank()) {
      "LiteLlmGatewayToolResult errorCode must not be blank."
    }
    require(errorMessage == null || errorMessage.isNotBlank()) {
      "LiteLlmGatewayToolResult errorMessage must not be blank."
    }
  }
}

data class LiteLlmGatewayMessage(
  val role: LiteLlmGatewayMessageRole,
  val content: String? = null,
  val attachments: List<LiteLlmGatewayAttachment> = emptyList(),
  val toolCalls: List<LiteLlmStructuredToolCall> = emptyList(),
  val toolResult: LiteLlmGatewayToolResult? = null,
  val assistantPhase: LiteLlmAssistantPhase? = null,
) {
  init {
    val hasContent = !content.isNullOrBlank()
    require(hasContent || attachments.isNotEmpty() || toolCalls.isNotEmpty() || toolResult != null) {
      "LiteLlmGatewayMessage must carry content, attachments, toolCalls, or toolResult."
    }
    require(role != LiteLlmGatewayMessageRole.ASSISTANT || toolResult == null) {
      "LiteLlmGatewayMessage assistant messages cannot carry toolResult."
    }
    require(role != LiteLlmGatewayMessageRole.TOOL || toolResult != null) {
      "LiteLlmGatewayMessage tool messages must carry toolResult."
    }
    require(role == LiteLlmGatewayMessageRole.ASSISTANT || toolCalls.isEmpty()) {
      "LiteLlmGatewayMessage only assistant messages may carry toolCalls."
    }
    require(role == LiteLlmGatewayMessageRole.ASSISTANT || assistantPhase == null) {
      "LiteLlmGatewayMessage assistantPhase is only valid for assistant messages."
    }
  }
}

data class LiteLlmToolDefinition(
  val name: String,
  val description: String,
  val inputSchema: JsonObject = JsonObject(emptyMap()),
  val strict: Boolean? = null,
) {
  init {
    require(name.isNotBlank()) { "LiteLlmToolDefinition name must not be blank." }
    require(description.isNotBlank()) { "LiteLlmToolDefinition description must not be blank." }
  }
}

enum class LiteLlmBuiltinToolType {
  WEB_SEARCH,
}

data class LiteLlmBuiltinToolDefinition(
  val type: LiteLlmBuiltinToolType,
  val domains: List<String> = emptyList(),
  val includeSources: Boolean = false,
) {
  init {
    require(domains.none(String::isBlank)) {
      "LiteLlmBuiltinToolDefinition domains must not contain blank values."
    }
  }
}

enum class LiteLlmGatewayStatus {
  SUCCESS,
  FAILED,
  TIMEOUT,
  RATE_LIMITED,
}

enum class LiteLlmCompletionMode {
  PRIMARY,
  FALLBACK,
  TERMINAL,
}

enum class LiteLlmAttemptOutcome {
  SUCCESS,
  TIMEOUT,
  RATE_LIMITED,
  FAILED,
}

data class LiteLlmRouteSelectionMetadata(
  val profileId: String,
  val routeId: String,
  val providerId: String,
  val model: String,
  val attemptIndex: Int,
  val fallbackTrigger: FallbackTrigger? = null,
  val isFallbackAttempt: Boolean = attemptIndex > 0,
) {
  init {
    require(profileId.isNotBlank()) { "LiteLlmRouteSelectionMetadata profileId must not be blank." }
    require(routeId.isNotBlank()) { "LiteLlmRouteSelectionMetadata routeId must not be blank." }
    require(providerId.isNotBlank()) { "LiteLlmRouteSelectionMetadata providerId must not be blank." }
    require(model.isNotBlank()) { "LiteLlmRouteSelectionMetadata model must not be blank." }
    require(attemptIndex >= 0) { "LiteLlmRouteSelectionMetadata attemptIndex must be >= 0." }
  }
}

data class LiteLlmAttemptRecord(
  val route: LiteLlmRouteSelectionMetadata,
  val outcome: LiteLlmAttemptOutcome,
  val fallbackAction: FallbackAction? = null,
  val outputChars: Int = 0,
  val finishReason: String? = null,
  val errorCode: String? = null,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
  val metadataKeys: List<String> = emptyList(),
) {
  init {
    require(finishedAtEpochMs >= startedAtEpochMs) {
      "LiteLlmAttemptRecord finishedAtEpochMs must be >= startedAtEpochMs."
    }
    require(outputChars >= 0) { "LiteLlmAttemptRecord outputChars must be >= 0." }
  }
}

data class LiteLlmStructuredToolCall(
  val id: String? = null,
  val toolName: String,
  val arguments: JsonObject = JsonObject(emptyMap()),
  val reason: String? = null,
) {
  init {
    require(id == null || id.isNotBlank()) {
      "LiteLlmStructuredToolCall id must not be blank."
    }
    require(toolName.isNotBlank()) { "LiteLlmStructuredToolCall toolName must not be blank." }
  }
}

data class LiteLlmStructuredFinalAttachment(
  val kind: String? = null,
  val relativePath: String? = null,
  val path: String? = null,
  val artifactId: String? = null,
  val chatAttachmentId: String? = null,
  val displayName: String? = null,
  val mimeType: String? = null,
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
)

data class LiteLlmStructuredCompletion(
  val toolCalls: List<LiteLlmStructuredToolCall> = emptyList(),
  val finalText: String? = null,
  val finalAttachments: List<LiteLlmStructuredFinalAttachment> = emptyList(),
  val commentaryText: String? = null,
  val commentaryTexts: List<String> = commentaryText
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let(::listOf)
    ?: emptyList(),
  val reasoningText: String? = null,
  val rawText: String? = null,
  val toolCallErrors: List<String> = emptyList(),
) {
  val hasStructuredActions: Boolean
    get() = toolCalls.isNotEmpty() ||
      !finalText.isNullOrBlank() ||
      finalAttachments.isNotEmpty() ||
      commentaryTexts.any(String::isNotBlank) ||
      !commentaryText.isNullOrBlank()

  val hasRecoverableDiagnostics: Boolean
    get() = toolCallErrors.isNotEmpty()

  val hasVisibleContent: Boolean
    get() = hasStructuredActions || !rawText.isNullOrBlank() || hasRecoverableDiagnostics
}

data class LiteLlmGatewayResult(
  val requestId: String,
  val status: LiteLlmGatewayStatus,
  val completionMode: LiteLlmCompletionMode,
  val outputText: String? = null,
  val completion: LiteLlmStructuredCompletion? = null,
  val providerResponseId: String? = null,
  val providerLineageId: String? = null,
  val selectedRoute: LiteLlmRouteSelectionMetadata? = null,
  val attempts: List<LiteLlmAttemptRecord>,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(requestId.isNotBlank()) { "LiteLlmGatewayResult requestId must not be blank." }
    require(attempts.isNotEmpty()) { "LiteLlmGatewayResult attempts must not be empty." }
    require(finishedAtEpochMs >= startedAtEpochMs) {
      "LiteLlmGatewayResult finishedAtEpochMs must be >= startedAtEpochMs."
    }
  }
}

data class LiteLlmProviderRequest(
  val route: ProviderRoute,
  val request: LiteLlmGatewayRequest,
  val selection: LiteLlmRouteSelectionMetadata,
)

sealed interface LiteLlmProviderResult {
  data class Success(
    val outputText: String,
    val completion: LiteLlmStructuredCompletion? = null,
    val finishReason: String? = null,
    val providerResponseId: String? = null,
    val providerLineageId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteLlmProviderResult

  data class Timeout(
    val errorMessage: String = "Provider request timed out.",
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteLlmProviderResult

  data class RateLimited(
    val retryAfterMs: Long? = null,
    val errorMessage: String = "Provider returned HTTP 429.",
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteLlmProviderResult

  data class Failure(
    val errorCode: String = "PROVIDER_FAILURE",
    val errorMessage: String,
    val completion: LiteLlmStructuredCompletion? = null,
    val providerResponseId: String? = null,
    val providerLineageId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
  ) : LiteLlmProviderResult {
    init {
      require(errorCode.isNotBlank()) { "LiteLlmProviderResult.Failure errorCode must not be blank." }
      require(errorMessage.isNotBlank()) { "LiteLlmProviderResult.Failure errorMessage must not be blank." }
    }
  }
}

interface LiteLlmProviderClient {
  fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult
}

interface LiteLlmRoutingSettingsStore {
  fun readRouting(): ProviderRouting

  fun writeRouting(routing: ProviderRouting)

  fun setActiveProfileId(profileId: String): ProviderRouting {
    val updatedRouting = readRouting().switchProfile(profileId)
    writeRouting(updatedRouting)
    return updatedRouting
  }
}

class InMemoryLiteLlmRoutingSettingsStore(
  initialRouting: ProviderRouting,
) : LiteLlmRoutingSettingsStore {
  @Volatile
  private var routing: ProviderRouting = initialRouting

  @Synchronized
  override fun readRouting(): ProviderRouting = routing

  @Synchronized
  override fun writeRouting(routing: ProviderRouting) {
    this.routing = routing
  }
}

data class LiteLlmFallbackEvent(
  val requestId: String,
  val route: LiteLlmRouteSelectionMetadata,
  val trigger: FallbackTrigger,
  val action: FallbackAction,
  val nextRoute: LiteLlmRouteSelectionMetadata? = null,
  val occurredAtEpochMs: Long,
) {
  init {
    require(requestId.isNotBlank()) { "LiteLlmFallbackEvent requestId must not be blank." }
  }
}

interface LiteLlmFallbackEventLog {
  fun record(event: LiteLlmFallbackEvent)
}

object NoOpLiteLlmFallbackEventLog : LiteLlmFallbackEventLog {
  override fun record(event: LiteLlmFallbackEvent) = Unit
}

class InMemoryLiteLlmFallbackEventLog : LiteLlmFallbackEventLog {
  private val events = mutableListOf<LiteLlmFallbackEvent>()

  @Synchronized
  override fun record(event: LiteLlmFallbackEvent) {
    events += event
  }

  @Synchronized
  fun snapshot(): List<LiteLlmFallbackEvent> = events.toList()
}

data class LiteLlmGatewayRequestLog(
  val requestId: String,
  val route: LiteLlmRouteSelectionMetadata,
  val promptChars: Int,
  val systemPromptChars: Int = 0,
  val metadataKeys: List<String> = emptyList(),
) {
  init {
    require(requestId.isNotBlank()) { "LiteLlmGatewayRequestLog requestId must not be blank." }
    require(promptChars >= 0) { "LiteLlmGatewayRequestLog promptChars must be >= 0." }
    require(systemPromptChars >= 0) { "LiteLlmGatewayRequestLog systemPromptChars must be >= 0." }
  }
}

data class LiteLlmGatewayResponseLog(
  val requestId: String,
  val route: LiteLlmRouteSelectionMetadata,
  val outcome: LiteLlmAttemptOutcome,
  val outputChars: Int = 0,
  val finishReason: String? = null,
  val errorCode: String? = null,
  val metadataKeys: List<String> = emptyList(),
) {
  init {
    require(requestId.isNotBlank()) { "LiteLlmGatewayResponseLog requestId must not be blank." }
    require(outputChars >= 0) { "LiteLlmGatewayResponseLog outputChars must be >= 0." }
  }
}

interface LiteLlmGatewayLogger {
  fun logRequest(entry: LiteLlmGatewayRequestLog)

  fun logResponse(entry: LiteLlmGatewayResponseLog)
}

object NoOpLiteLlmGatewayLogger : LiteLlmGatewayLogger {
  override fun logRequest(entry: LiteLlmGatewayRequestLog) = Unit

  override fun logResponse(entry: LiteLlmGatewayResponseLog) = Unit
}

class InMemoryLiteLlmGatewayLogger : LiteLlmGatewayLogger {
  private val requests = mutableListOf<LiteLlmGatewayRequestLog>()
  private val responses = mutableListOf<LiteLlmGatewayResponseLog>()

  @Synchronized
  override fun logRequest(entry: LiteLlmGatewayRequestLog) {
    requests += entry
  }

  @Synchronized
  override fun logResponse(entry: LiteLlmGatewayResponseLog) {
    responses += entry
  }

  @Synchronized
  fun requestSnapshot(): List<LiteLlmGatewayRequestLog> = requests.toList()

  @Synchronized
  fun responseSnapshot(): List<LiteLlmGatewayResponseLog> = responses.toList()
}

class DefaultLiteLlmGateway(
  private val routingStore: LiteLlmRoutingSettingsStore,
  private val providerClient: LiteLlmProviderClient,
  private val fallbackEventLog: LiteLlmFallbackEventLog = NoOpLiteLlmFallbackEventLog,
  private val logger: LiteLlmGatewayLogger = NoOpLiteLlmGatewayLogger,
  private val clock: () -> Long = System::currentTimeMillis,
) : LiteLlmGateway {

  override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
    val startedAtEpochMs = clock()
    val routing = routingStore.readRouting()
    val activeProfile = routing.activeProfile()
    val orderedRoutes = activeProfile.orderedRoutes()
    val attempts = mutableListOf<LiteLlmAttemptRecord>()
    var routeIndex = 0
    var fallbackTrigger: FallbackTrigger? = null

    while (routeIndex < orderedRoutes.size) {
      val route = orderedRoutes[routeIndex]
      val selection = activeProfile.safeSelectionMetadata(
        routeId = route.id,
        attemptIndex = routeIndex,
        fallbackTrigger = fallbackTrigger,
      )
      val attemptStartedAtEpochMs = clock()

      request.streamObserver.onVisibleTextReset()
      logger.logRequest(request.toSafeLog(selection))
      val providerResult = executeProviderRequest(
        LiteLlmProviderRequest(
          route = route,
          request = request,
          selection = selection,
        ),
      )
      val attemptFinishedAtEpochMs = clock()

      logger.logResponse(providerResult.toSafeLog(request.requestId, selection))

      when (providerResult) {
        is LiteLlmProviderResult.Success -> {
          val providerMetadata = providerResult.metadata.toMap()
          attempts += LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = providerResult.outputTextChars(),
            finishReason = providerResult.finishReason,
            startedAtEpochMs = attemptStartedAtEpochMs,
            finishedAtEpochMs = attemptFinishedAtEpochMs,
            metadataKeys = providerMetadata.safeMetadataKeys(),
          )
          return LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = if (selection.isFallbackAttempt) {
              LiteLlmCompletionMode.FALLBACK
            } else {
              LiteLlmCompletionMode.PRIMARY
            },
            outputText = providerResult.outputText,
            completion = providerResult.completion,
            providerResponseId = providerResult.providerResponseId,
            providerLineageId = providerResult.providerLineageId,
            selectedRoute = selection,
            attempts = attempts.toList(),
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = attemptFinishedAtEpochMs,
            metadata = providerMetadata,
          )
        }

        is LiteLlmProviderResult.Timeout -> {
          val terminalResult = handleTriggeredFallback(
            request = request,
            profile = activeProfile,
            routeIndex = routeIndex,
            selection = selection,
            trigger = FallbackTrigger.TIMEOUT,
            attemptOutcome = LiteLlmAttemptOutcome.TIMEOUT,
            providerMetadata = providerResult.metadata,
            errorMessage = providerResult.errorMessage,
            attempts = attempts,
            startedAtEpochMs = startedAtEpochMs,
            attemptStartedAtEpochMs = attemptStartedAtEpochMs,
            attemptFinishedAtEpochMs = attemptFinishedAtEpochMs,
          )
          if (terminalResult != null) return terminalResult
          routeIndex += 1
          fallbackTrigger = FallbackTrigger.TIMEOUT
        }

        is LiteLlmProviderResult.RateLimited -> {
          val providerMetadata = providerResult.metadata.toMutableMap().apply {
            providerResult.retryAfterMs?.let { put("retryAfterMs", it.toString()) }
          }
          val terminalResult = handleTriggeredFallback(
            request = request,
            profile = activeProfile,
            routeIndex = routeIndex,
            selection = selection,
            trigger = FallbackTrigger.RATE_LIMIT_429,
            attemptOutcome = LiteLlmAttemptOutcome.RATE_LIMITED,
            providerMetadata = providerMetadata,
            errorMessage = providerResult.errorMessage,
            attempts = attempts,
            startedAtEpochMs = startedAtEpochMs,
            attemptStartedAtEpochMs = attemptStartedAtEpochMs,
            attemptFinishedAtEpochMs = attemptFinishedAtEpochMs,
          )
          if (terminalResult != null) return terminalResult
          routeIndex += 1
          fallbackTrigger = FallbackTrigger.RATE_LIMIT_429
        }

        is LiteLlmProviderResult.Failure -> {
          val providerMetadata = providerResult.metadata.toMap()
          attempts += LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.FAILED,
            errorCode = providerResult.errorCode,
            startedAtEpochMs = attemptStartedAtEpochMs,
            finishedAtEpochMs = attemptFinishedAtEpochMs,
            metadataKeys = providerMetadata.safeMetadataKeys(),
          )
          return LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.FAILED,
            completionMode = LiteLlmCompletionMode.TERMINAL,
            completion = providerResult.completion,
            providerResponseId = providerResult.providerResponseId,
            providerLineageId = providerResult.providerLineageId,
            selectedRoute = selection,
            attempts = attempts.toList(),
            errorCode = providerResult.errorCode,
            errorMessage = providerResult.errorMessage,
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = attemptFinishedAtEpochMs,
            metadata = providerMetadata,
          )
        }
      }
    }

    val finishedAtEpochMs = clock()
    return LiteLlmGatewayResult(
      requestId = request.requestId,
      status = LiteLlmGatewayStatus.FAILED,
      completionMode = LiteLlmCompletionMode.TERMINAL,
      selectedRoute = attempts.lastOrNull()?.route,
      attempts = attempts.toList().ifEmpty {
        listOf(
          LiteLlmAttemptRecord(
            route = activeProfile.safeSelectionMetadata(activeProfile.primaryRouteId),
            outcome = LiteLlmAttemptOutcome.FAILED,
            errorCode = "ROUTING_CONFIGURATION_EMPTY",
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
          ),
        )
      },
      errorCode = "ROUTING_CONFIGURATION_EMPTY",
      errorMessage = "No route was available for the active profile.",
      startedAtEpochMs = startedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
    )
  }

  private fun executeProviderRequest(request: LiteLlmProviderRequest): LiteLlmProviderResult = try {
    providerClient.execute(request)
  } catch (throwable: Throwable) {
    LiteLlmProviderResult.Failure(
      errorCode = "PROVIDER_CLIENT_EXCEPTION",
      errorMessage = throwable.message ?: throwable::class.java.simpleName,
      metadata = mapOf("exceptionType" to throwable::class.java.name),
    )
  }

  private fun handleTriggeredFallback(
    request: LiteLlmGatewayRequest,
    profile: ModelProfile,
    routeIndex: Int,
    selection: LiteLlmRouteSelectionMetadata,
    trigger: FallbackTrigger,
    attemptOutcome: LiteLlmAttemptOutcome,
    providerMetadata: Map<String, String>,
    errorMessage: String,
    attempts: MutableList<LiteLlmAttemptRecord>,
    startedAtEpochMs: Long,
    attemptStartedAtEpochMs: Long,
    attemptFinishedAtEpochMs: Long,
  ): LiteLlmGatewayResult? {
    val policyAction = profile.fallbackPolicy.actionFor(trigger)
    val nextRoute = profile.nextRoute(selection.routeId, trigger)
    val resolvedAction = if (nextRoute != null) {
      FallbackAction.TRY_NEXT_ROUTE
    } else {
      FallbackAction.TERMINAL_FAILURE
    }
    val nextSelection = nextRoute?.let {
      profile.safeSelectionMetadata(
        routeId = it.id,
        attemptIndex = routeIndex + 1,
        fallbackTrigger = trigger,
      )
    }

    attempts += LiteLlmAttemptRecord(
      route = selection,
      outcome = attemptOutcome,
      fallbackAction = resolvedAction,
      errorCode = trigger.toTerminalErrorCode(action = policyAction, nextRoute = nextRoute),
      startedAtEpochMs = attemptStartedAtEpochMs,
      finishedAtEpochMs = attemptFinishedAtEpochMs,
      metadataKeys = providerMetadata.safeMetadataKeys(),
    )

    fallbackEventLog.record(
      LiteLlmFallbackEvent(
        requestId = request.requestId,
        route = selection,
        trigger = trigger,
        action = resolvedAction,
        nextRoute = nextSelection,
        occurredAtEpochMs = attemptFinishedAtEpochMs,
      ),
    )

    if (nextRoute != null) return null

    return LiteLlmGatewayResult(
      requestId = request.requestId,
      status = trigger.toGatewayStatus(),
      completionMode = LiteLlmCompletionMode.TERMINAL,
      selectedRoute = selection,
      attempts = attempts.toList(),
      errorCode = trigger.toTerminalErrorCode(action = policyAction, nextRoute = nextRoute),
      errorMessage = errorMessage,
      startedAtEpochMs = startedAtEpochMs,
      finishedAtEpochMs = attemptFinishedAtEpochMs,
      metadata = providerMetadata,
    )
  }
}

private fun ModelProfile.safeSelectionMetadata(
  routeId: String,
  attemptIndex: Int = 0,
  fallbackTrigger: FallbackTrigger? = null,
): LiteLlmRouteSelectionMetadata {
  val metadata = loggingMetadata(
    routeId = routeId,
    attemptIndex = attemptIndex,
    fallbackTrigger = fallbackTrigger,
  )
  return LiteLlmRouteSelectionMetadata(
    profileId = metadata.profileId,
    routeId = metadata.routeId,
    providerId = metadata.providerId,
    model = metadata.model,
    attemptIndex = metadata.attemptIndex,
    fallbackTrigger = metadata.fallbackTrigger,
    isFallbackAttempt = metadata.isFallbackAttempt,
  )
}

private fun LiteLlmGatewayRequest.toSafeLog(
  selection: LiteLlmRouteSelectionMetadata,
): LiteLlmGatewayRequestLog = LiteLlmGatewayRequestLog(
  requestId = requestId,
  route = selection,
  promptChars = prompt.length,
  systemPromptChars = systemPrompt?.length ?: 0,
  metadataKeys = metadata.safeMetadataKeys(),
)

private fun LiteLlmProviderResult.toSafeLog(
  requestId: String,
  selection: LiteLlmRouteSelectionMetadata,
): LiteLlmGatewayResponseLog = when (this) {
  is LiteLlmProviderResult.Success -> LiteLlmGatewayResponseLog(
    requestId = requestId,
    route = selection,
    outcome = LiteLlmAttemptOutcome.SUCCESS,
    outputChars = outputTextChars(),
    finishReason = finishReason,
    metadataKeys = metadata.safeMetadataKeys(),
  )

  is LiteLlmProviderResult.Timeout -> LiteLlmGatewayResponseLog(
    requestId = requestId,
    route = selection,
    outcome = LiteLlmAttemptOutcome.TIMEOUT,
    errorCode = FallbackTrigger.TIMEOUT.toGatewayStatus().name,
    metadataKeys = metadata.safeMetadataKeys(),
  )

  is LiteLlmProviderResult.RateLimited -> LiteLlmGatewayResponseLog(
    requestId = requestId,
    route = selection,
    outcome = LiteLlmAttemptOutcome.RATE_LIMITED,
    errorCode = FallbackTrigger.RATE_LIMIT_429.toGatewayStatus().name,
    metadataKeys = metadata.toMutableMap().apply {
      retryAfterMs?.let { put("retryAfterMs", it.toString()) }
    }.safeMetadataKeys(),
  )

  is LiteLlmProviderResult.Failure -> LiteLlmGatewayResponseLog(
    requestId = requestId,
    route = selection,
    outcome = LiteLlmAttemptOutcome.FAILED,
    errorCode = errorCode,
    metadataKeys = metadata.safeMetadataKeys(),
  )
}

private fun LiteLlmProviderResult.Success.outputTextChars(): Int =
  outputText.length.takeIf { it > 0 }
    ?: completion?.rawText?.length
    ?: completion?.finalText?.length
    ?: completion?.commentaryText?.length
    ?: completion?.reasoningText?.length
    ?: 0

private fun FallbackTrigger.toGatewayStatus(): LiteLlmGatewayStatus = when (this) {
  FallbackTrigger.TIMEOUT -> LiteLlmGatewayStatus.TIMEOUT
  FallbackTrigger.RATE_LIMIT_429 -> LiteLlmGatewayStatus.RATE_LIMITED
}

private fun FallbackTrigger.toTerminalErrorCode(
  action: FallbackAction,
  nextRoute: ProviderRoute?,
): String {
  val prefix = when (this) {
    FallbackTrigger.TIMEOUT -> "PROVIDER_TIMEOUT"
    FallbackTrigger.RATE_LIMIT_429 -> "PROVIDER_RATE_LIMIT_429"
  }
  return when {
    nextRoute != null -> "${prefix}_FALLBACK_APPLIED"
    action == FallbackAction.TERMINAL_FAILURE -> "${prefix}_TERMINAL_POLICY"
    else -> "${prefix}_FALLBACK_EXHAUSTED"
  }
}

private fun Map<String, String>.safeMetadataKeys(): List<String> = keys
  .filterNot { it.isSensitiveKey() }
  .sorted()

private fun String.isSensitiveKey(): Boolean {
  val normalized = lowercase()
  return normalized.contains("auth") ||
    normalized.contains("token") ||
    normalized.contains("secret") ||
    normalized.contains("password") ||
    normalized.contains("api-key") ||
    normalized.contains("apikey") ||
    normalized == "key"
}
