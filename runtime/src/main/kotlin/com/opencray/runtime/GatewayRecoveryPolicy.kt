package com.opencray.runtime

import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.OpenCrayAgentRuntime.PromptRunDiagnostics

private const val MAX_STRUCTURED_TOOL_CALL_ERROR_COUNT: Int = 3
private const val RECOVERABLE_LLM_RETRY_SLEEP_CHUNK_MS: Long = 250L
private val TERMINAL_PROVIDER_TIMEOUT_STATUS_CODES: Set<String> = setOf("449", "499")

internal fun OpenCrayAgentRuntime.recoverableGatewayRetryDelayMs(gatewayResult: LiteLlmGatewayResult): Long? = when {
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

internal fun isTerminalProviderTimeout(gatewayResult: LiteLlmGatewayResult): Boolean =
  gatewayResult.metadata["statusCode"] in TERMINAL_PROVIDER_TIMEOUT_STATUS_CODES

internal fun OpenCrayAgentRuntime.recoverableGatewayFailureObservation(
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

internal fun OpenCrayAgentRuntime.responsesContinuationRecoveryReason(
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

internal fun OpenCrayAgentRuntime.recoverableSuccessfulEmptyResponseObservation(
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

internal fun isProviderEmptyResponseFailure(
  gatewayResult: LiteLlmGatewayResult,
): Boolean = gatewayResult.status == LiteLlmGatewayStatus.FAILED &&
  gatewayResult.errorCode == "PROVIDER_EMPTY_RESPONSE"

internal fun isSuccessfulEmptyResponse(
  gatewayResult: LiteLlmGatewayResult,
): Boolean = gatewayResult.status == LiteLlmGatewayStatus.SUCCESS &&
  !hasVisibleOutput(gatewayResult)

internal fun hasVisibleOutput(
  gatewayResult: LiteLlmGatewayResult,
): Boolean = !gatewayResult.outputText.isNullOrBlank() ||
  !gatewayResult.completion?.rawText.isNullOrBlank() ||
  !gatewayResult.completion?.finalText.isNullOrBlank() ||
  gatewayResult.completion?.finalAttachments?.isNotEmpty() == true ||
  gatewayResult.completion?.let(::structuredCompletionCommentaryTexts).orEmpty().isNotEmpty() ||
  gatewayResult.completion?.toolCalls?.isNotEmpty() == true

internal fun OpenCrayAgentRuntime.sleepForRecoverableRetry(
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

internal fun OpenCrayAgentRuntime.buildRecoverableRetryCommentaryText(
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

internal fun buildRecoverableRetryExhaustedMessage(
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

internal fun buildStructuredToolCallRecoveryReason(
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

internal fun duplicateStructuredToolCallErrors(
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

internal fun String?.isTransientGatewayFailureCode(): Boolean {
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
