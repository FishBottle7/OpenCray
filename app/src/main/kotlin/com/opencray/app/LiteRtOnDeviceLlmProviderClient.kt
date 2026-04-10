package com.opencray.app

import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult

internal class LiteRtOnDeviceLlmProviderClient(
  private val runtime: LiteRtOnDeviceRuntime,
) : LiteLlmProviderClient {
  override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
    val requestMetadata = requestDiagnosticsMetadata(request)
    val modelId = request.route.metadata[LiteRtOnDeviceMetadataKeys.MODEL_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: request.route.model.trim()
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
      thinkingEnabled =
        request.route.metadata[LiteRtOnDeviceMetadataKeys.THINKING_ENABLED]
          ?.toBooleanStrictOrNull()
          ?: LlmSettingsState.DEFAULT_ON_DEVICE_THINKING_ENABLED,
      prompt = request.request.prompt,
      systemPrompt = request.request.systemPrompt,
      messages = request.request.messages,
      tools = request.request.tools,
      builtinTools = request.request.builtinTools,
      toolChoice = request.request.toolChoice,
      parallelToolCalls = request.request.parallelToolCalls,
      timeoutMs = request.route.timeoutMs,
      automaticToolExecutionContext = LiteRtAutomaticToolExecutionRegistry.current(),
    )
    return when (val result = runtime.execute(runtimeRequest)) {
      is LiteRtOnDeviceRuntimeResult.Success -> LiteLlmProviderResult.Success(
        outputText = result.outputText,
        completion = result.completion,
        finishReason = result.finishReason,
        metadata = requestMetadata + result.metadata,
      )

      is LiteRtOnDeviceRuntimeResult.Timeout -> LiteLlmProviderResult.Timeout(
        errorMessage = result.errorMessage,
        metadata = requestMetadata + result.metadata,
      )

      is LiteRtOnDeviceRuntimeResult.Failure -> LiteLlmProviderResult.Failure(
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        completion = result.completion,
        metadata = requestMetadata + result.metadata,
      )
    }
  }

  private fun requestDiagnosticsMetadata(
    request: LiteLlmProviderRequest,
  ): Map<String, String> = buildMap {
    put(LiteRtOnDeviceMetadataKeys.PROVIDER_MODE, LlmProviderModes.ON_DEVICE_MODEL)
    put(LiteRtOnDeviceMetadataKeys.RUNTIME, OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM)
    put(LiteRtOnDeviceMetadataKeys.MODEL_ID, request.route.model)
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
}
