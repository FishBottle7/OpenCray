package com.opencray.app

import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.llm.LiteLlmVisibleTextObserver

internal class LiteRtOnDeviceLlmProviderClient(
  private val runtime: LiteRtOnDeviceRuntime,
) : LiteLlmProviderClient {
  override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
    val streamingEnabled = request.route.metadata["stream"]
      ?.toBooleanStrictOrNull()
      ?: LlmSettingsState.DEFAULT_STREAMING_ENABLED
    val modelId = request.route.metadata[LiteRtOnDeviceMetadataKeys.MODEL_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: request.route.model.trim()
    val requestMetadata = requestDiagnosticsMetadata(
      request = request,
      modelId = modelId,
      streamingEnabled = streamingEnabled,
    )
    val transportPrompt = if (request.request.messages.isEmpty()) {
      request.request.prompt
    } else {
      ""
    }
    val runtimeRequest = LiteRtOnDeviceRuntimeRequest(
      requestId = request.request.requestId,
      modelId = modelId,
      backend = OnDeviceLlmAccelerators.normalize(
        request.route.metadata[LiteRtOnDeviceMetadataKeys.BACKEND].orEmpty(),
      ),
      maxContextWindow =
        request.route.metadata[LiteRtOnDeviceMetadataKeys.MAX_CONTEXT_WINDOW]
          ?.toIntOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_MAX_CONTEXT_WINDOW,
      maxTokens =
        request.route.metadata["max_tokens"]
          ?.toIntOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_MAX_TOKENS,
      topK =
        request.route.metadata["top_k"]
          ?.toIntOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_TOP_K,
      topP =
        request.route.metadata["top_p"]
          ?.toDoubleOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_TOP_P,
      temperature =
        request.route.metadata["temperature"]
          ?.toDoubleOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_TEMPERATURE,
      streamingEnabled = streamingEnabled,
      thinkingEnabled =
        request.route.metadata[LiteRtOnDeviceMetadataKeys.THINKING_ENABLED]
          ?.toBooleanStrictOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_THINKING_ENABLED,
      prompt = transportPrompt,
      systemPrompt = request.request.systemPrompt,
      messages = request.request.messages,
      tools = request.request.tools,
      builtinTools = request.request.builtinTools,
      toolChoice = request.request.toolChoice,
      parallelToolCalls = request.request.parallelToolCalls,
      timeoutMs = request.route.timeoutMs,
      automaticToolExecutionContext = LiteRtAutomaticToolExecutionRegistry.current(),
    )
    val streamObserver = request.request.streamObserver
    val visibleTextPulse = if (streamingEnabled) {
      OnDeviceVisibleTextPulse(
        observer = streamObserver,
        label = request.route.metadata[LiteRtOnDeviceMetadataKeys.THINKING_LABEL].orEmpty(),
      )
    } else {
      null
    }
    val result = try {
      runtime.execute(runtimeRequest)
    } finally {
      visibleTextPulse?.close()
    }
    return when (result) {
      is LiteRtOnDeviceRuntimeResult.Success -> {
        if (streamingEnabled) {
          visibleTextSnapshot(result)?.let(streamObserver::onVisibleTextSnapshot)
            ?: streamObserver.onVisibleTextReset()
        }
        LiteLlmProviderResult.Success(
          outputText = result.outputText,
          completion = result.completion,
          finishReason = result.finishReason,
          metadata = requestMetadata + result.metadata,
        )
      }

      is LiteRtOnDeviceRuntimeResult.Timeout -> {
        if (streamingEnabled) {
          streamObserver.onVisibleTextReset()
        }
        LiteLlmProviderResult.Timeout(
          errorMessage = result.errorMessage,
          metadata = requestMetadata + result.metadata,
        )
      }

      is LiteRtOnDeviceRuntimeResult.Failure -> {
        if (streamingEnabled) {
          streamObserver.onVisibleTextReset()
        }
        LiteLlmProviderResult.Failure(
          errorCode = result.errorCode,
          errorMessage = result.errorMessage,
          completion = result.completion,
          metadata = requestMetadata + result.metadata,
        )
      }
    }
  }

  private fun requestDiagnosticsMetadata(
    request: LiteLlmProviderRequest,
    modelId: String,
    streamingEnabled: Boolean,
  ): Map<String, String> = buildMap {
    put(LiteRtOnDeviceMetadataKeys.PROVIDER_MODE, LlmProviderModes.ON_DEVICE_MODEL)
    put(LiteRtOnDeviceMetadataKeys.RUNTIME, OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM)
    put(LiteRtOnDeviceMetadataKeys.MODEL_ID, modelId)
    put("stream", streamingEnabled.toString())
    put(
      LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED,
      request.request.tools.isNotEmpty().toString(),
    )
    put(
      LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_REQUESTED,
      request.request.builtinTools.any { tool -> tool.type == LiteLlmBuiltinToolType.WEB_SEARCH }
        .toString(),
    )
    request.request.previousResponseId
      ?.takeIf(String::isNotBlank)
      ?.let { put("previousResponseIdPresent", "true") }
    if (request.request.responseApiPreferred) {
      put("responseApiPreferred", "true")
    }
  }

  private fun visibleTextSnapshot(
    result: LiteRtOnDeviceRuntimeResult.Success,
  ): String? = result.completion?.let { completion ->
    completion.finalText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: completion.commentaryText
        ?.trim()
        ?.takeIf(String::isNotBlank)
  } ?: result.outputText
    .trim()
    .takeIf(String::isNotBlank)

}

private class OnDeviceVisibleTextPulse(
  private val observer: LiteLlmVisibleTextObserver,
  label: String,
) : AutoCloseable {
  @Volatile
  private var closed: Boolean = false
  private val pulseFrames: List<String> = resolvePulseFrames(label)

  private val thread = Thread(
    ::run,
    "opencray-litertlm-visible-pulse",
  ).apply {
    isDaemon = true
    start()
  }

  override fun close() {
    closed = true
    thread.interrupt()
    if (Thread.currentThread() != thread) {
      runCatching {
        thread.join(CLOSE_JOIN_TIMEOUT_MS)
      }
    }
  }

  private fun run() {
    try {
      Thread.sleep(INITIAL_DELAY_MS)
      var frameIndex = 0
      while (!closed) {
        observer.onVisibleTextSnapshot(pulseFrames[frameIndex % pulseFrames.size])
        frameIndex += 1
        Thread.sleep(PULSE_INTERVAL_MS)
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private companion object {
    private const val INITIAL_DELAY_MS: Long = 350L
    private const val PULSE_INTERVAL_MS: Long = 350L
    private const val CLOSE_JOIN_TIMEOUT_MS: Long = 1_500L
    private const val DEFAULT_THINKING_LABEL: String = "Thinking…"

    private fun resolvePulseFrames(label: String): List<String> {
      val base = label
        .trim()
        .ifBlank { DEFAULT_THINKING_LABEL }
      return listOf(base)
    }
  }
}
